package com.leve.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.util.TreeMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object Frames {
    fun grab(r: MediaMetadataRetriever, ms: Long, w: Int, h: Int, exact: Boolean = false): Bitmap? = try {
        val us = ms * 1000
        val opt = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        if (Build.VERSION.SDK_INT >= 27) {
            r.getScaledFrameAtTime(us, opt, w, h)
        } else {
            r.getFrameAtTime(us, opt)?.let { b ->
                val scale = minOf(w.toFloat() / b.width, h.toFloat() / b.height)
                val s = Bitmap.createScaledBitmap(
                    b, (b.width * scale).toInt().coerceAtLeast(1), (b.height * scale).toInt().coerceAtLeast(1), true
                )
                if (s !== b) b.recycle()
                s
            }
        }
    } catch (e: Exception) { null }

    /** Gera e salva a miniatura de uma cena. Rodar fora da thread principal. */
    fun saveSceneThumb(ctx: Context, uri: Uri, ms: Long, out: File): Boolean {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val b = grab(r, ms, 320, 180) ?: return false
            FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            b.recycle()
            true
        } catch (e: Exception) { false } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    class Info(val durationMs: Long, val w: Int, val h: Int)

    fun info(ctx: Context, uri: Uri): Info? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            Info(d, w, h)
        } catch (e: Exception) { null } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }
}

/**
 * Miniaturas da linha do tempo.
 * - 2 threads (cada uma com seu leitor de vídeo).
 * - Cache em disco: depois da 1ª vez, o projeto abre com as miniaturas na hora.
 * - Pede primeiro as que estão na tela; enquanto não chegam, mostra a mais próxima já pronta.
 */
class ThumbnailProvider(
    private val ctx: Context,
    private val uri: Uri,
    private val diskDir: File,
    private val w: Int,
    private val h: Int,
    private val onReady: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<Long, Bitmap>(16 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount / 1024
    }
    private val coarse = TreeMap<Long, Bitmap>()
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val queue = ArrayDeque<Long>()        // chaves (ms*2 + exato)
    private val queued = HashSet<Long>()
    private val coarseQueue = ArrayDeque<Long>()  // ms
    @Volatile private var alive = true
    @Volatile private var postPending = false

    init {
        diskDir.mkdirs()
        repeat(2) { n ->
            Thread({ loop() }, "thumbs$n").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1; start() }
        }
    }

    private fun key(ms: Long, exact: Boolean) = ms * 2 + if (exact) 1 else 0

    fun prefetch(durationMs: Long) {
        if (durationMs <= 0) return
        val n = 80
        lock.withLock {
            coarseQueue.clear()
            for (i in 0 until n) coarseQueue.addLast(durationMs * i / n)
            cond.signalAll()
        }
    }

    fun get(ms: Long, exact: Boolean = false): Bitmap? {
        val k = key(ms, exact)
        cache.get(k)?.let { return it }
        lock.withLock {
            if (queued.add(k)) {
                queue.addLast(k)
                if (queue.size > 48) queued.remove(queue.removeFirst())
                cond.signalAll()
            }
        }
        // enquanto o quadro exato não chega, usa o rápido se já tiver
        if (exact) cache.get(key(ms, false))?.let { return it }
        return nearest(ms)
    }

    private fun nearest(ms: Long): Bitmap? = synchronized(coarse) {
        val lo = coarse.floorEntry(ms)
        val hi = coarse.ceilingEntry(ms)
        when {
            lo == null -> hi?.value
            hi == null -> lo.value
            ms - lo.key <= hi.key - ms -> lo.value
            else -> hi.value
        }
    }

    fun release() {
        alive = false
        lock.withLock { cond.signalAll() }
    }

    private val readOpts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }

    private fun loop() {
        var r: MediaMetadataRetriever? = null
        var failed = false
        while (alive) {
            // (chave, éCoarse)
            val job: Pair<Long, Boolean> = lock.withLock {
                while (alive && queue.isEmpty() && coarseQueue.isEmpty()) cond.await()
                if (!alive) null
                else if (queue.isNotEmpty()) {
                    val k = queue.removeLast(); queued.remove(k); Pair(k, false)
                } else Pair(key(coarseQueue.removeFirst(), false), true)
            } ?: break
            val k = job.first
            val isCoarse = job.second
            if (!isCoarse && cache.get(k) != null) continue
            val ms = k / 2
            val exact = k % 2 == 1L
            val f = File(diskDir, "$k.jpg")
            var bmp: Bitmap? = if (f.exists()) BitmapFactory.decodeFile(f.path, readOpts) else null
            if (bmp == null) {
                if (failed) continue
                if (r == null) {
                    r = MediaMetadataRetriever()
                    try { r.setDataSource(ctx, uri) } catch (e: Exception) { failed = true; continue }
                }
                bmp = Frames.grab(r, ms, w, h, exact) ?: continue
                try { FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 72, it) } } catch (_: Exception) {}
            }
            if (isCoarse) synchronized(coarse) { coarse[ms] = bmp } else cache.put(k, bmp)
            if (!postPending) {
                postPending = true
                main.postDelayed({ postPending = false; if (alive) onReady() }, 40)
            }
        }
        try { r?.release() } catch (_: Exception) {}
    }
}
