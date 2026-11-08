package com.leve.editor

import android.content.Context
import android.graphics.Bitmap
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
    fun grab(r: MediaMetadataRetriever, ms: Long, w: Int, h: Int): Bitmap? = try {
        val us = ms * 1000
        if (Build.VERSION.SDK_INT >= 27) {
            r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
        } else {
            r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { b ->
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
 * Miniaturas da linha do tempo, geradas numa thread só (leve para celular fraco).
 * Pede as que estão na tela primeiro; enquanto não chegam, mostra a mais próxima já pronta.
 */
class ThumbnailProvider(
    private val ctx: Context,
    private val uri: Uri,
    private val w: Int,
    private val h: Int,
    private val onReady: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<Long, Bitmap>(14 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount / 1024
    }
    private val coarse = TreeMap<Long, Bitmap>()
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val queue = ArrayDeque<Long>()
    private val queued = HashSet<Long>()
    private val coarseQueue = ArrayDeque<Long>()
    @Volatile private var alive = true
    @Volatile private var postPending = false

    init {
        Thread({ loop() }, "thumbs").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1; start() }
    }

    fun prefetch(durationMs: Long) {
        if (durationMs <= 0) return
        val n = 60
        lock.withLock {
            coarseQueue.clear()
            for (i in 0 until n) coarseQueue.addLast(durationMs * i / n)
            cond.signalAll()
        }
    }

    fun get(ms: Long): Bitmap? {
        cache.get(ms)?.let { return it }
        lock.withLock {
            if (queued.add(ms)) {
                queue.addLast(ms)
                if (queue.size > 40) queued.remove(queue.removeFirst())
                cond.signalAll()
            }
        }
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

    private fun loop() {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) {}
            return
        }
        while (alive) {
            val job: Pair<Long, Boolean> = lock.withLock {
                while (alive && queue.isEmpty() && coarseQueue.isEmpty()) cond.await()
                if (!alive) null
                else if (queue.isNotEmpty()) {
                    val k = queue.removeLast(); queued.remove(k); Pair(k, false)
                } else Pair(coarseQueue.removeFirst(), true)
            } ?: break
            val key = job.first
            if (!job.second && cache.get(key) != null) continue
            val bmp = Frames.grab(r, key, w, h) ?: continue
            if (job.second) synchronized(coarse) { coarse[key] = bmp } else cache.put(key, bmp)
            if (!postPending) {
                postPending = true
                main.postDelayed({ postPending = false; if (alive) onReady() }, 50)
            }
        }
        try { r.release() } catch (_: Exception) {}
    }
}
