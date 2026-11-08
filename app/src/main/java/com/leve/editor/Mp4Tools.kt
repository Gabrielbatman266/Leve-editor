package com.leve.editor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * Ferramentas de MP4 sem recodificar:
 *  - [fastCopy]: corta e junta direto do filme (segundos). Cada cena começa no quadro-chave
 *    anterior, e o áudio é cortado junto com o vídeo, então não sai de sincronia.
 *  - [concat]: junta as partes já exportadas, cortando o áudio de cada parte no tamanho do
 *    vídeo — assim o vídeo nunca "trava" enquanto o áudio continua.
 */
object Mp4Tools {

    class Cancelled : Exception("cancelado")

    private const val BUF = 4 * 1024 * 1024

    private fun findTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith(prefix)) return i
        }
        return -1
    }

    /** Quadros por segundo do vídeo (0 se não souber). */
    fun frameRate(ctx: Context, uri: Uri): Float {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(ctx, uri, null)
            val v = findTrack(ex, "video/")
            if (v < 0) 0f else {
                val f = ex.getTrackFormat(v)
                if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    try { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() } catch (e: Exception) { f.getFloat(MediaFormat.KEY_FRAME_RATE) }
                } else 0f
            }
        } catch (e: Exception) { 0f } finally { ex.release() }
    }

    /**
     * Exportação rápida: copia os trechos do filme sem recodificar.
     * [clips] = (inícioMs, fimMs) na ordem da montagem.
     */
    fun fastCopy(
        ctx: Context, uri: Uri, clips: List<Pair<Long, Long>>, out: File, removeAudio: Boolean,
        cancelled: () -> Boolean, progress: (Int) -> Unit
    ) {
        val vex = MediaExtractor().apply { setDataSource(ctx, uri, null) }
        val aex = MediaExtractor().apply { setDataSource(ctx, uri, null) }
        val vt = findTrack(vex, "video/")
        if (vt < 0) { vex.release(); aex.release(); throw IllegalStateException("O filme não tem vídeo") }
        val at = if (removeAudio) -1 else findTrack(aex, "audio/")
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val vFmt = vex.getTrackFormat(vt)
            if (vFmt.containsKey("rotation-degrees")) muxer.setOrientationHint(vFmt.getInteger("rotation-degrees"))
            val mv = muxer.addTrack(vFmt)
            val ma = if (at >= 0) try { muxer.addTrack(aex.getTrackFormat(at)) } catch (e: Exception) { -1 } else -1
            muxer.start()
            vex.selectTrack(vt)
            if (ma >= 0) aex.selectTrack(at)

            val buf = ByteBuffer.allocateDirect(BUF)
            val info = MediaCodec.BufferInfo()
            val totalMs = clips.sumOf { it.second - it.first }.coerceAtLeast(1)
            var doneMs = 0L
            var offsetUs = 0L
            var lastAOut = -1L

            for ((startMs, endMs) in clips) {
                if (cancelled()) throw Cancelled()
                val endUs = endMs * 1000
                vex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val kfUs = vex.sampleTime.coerceAtLeast(0)
                var maxPts = kfUs
                var count = 0
                var firstPts = Long.MAX_VALUE
                while (true) {
                    val pts = vex.sampleTime
                    // lê um pouco além do fim (ordem de decodificação) para não perder B-frames
                    if (pts < 0 || pts >= endUs + 300_000) break
                    if (pts >= endUs) { vex.advance(); continue }
                    val size = vex.readSampleData(buf, 0)
                    if (size < 0) break
                    val rel = pts - kfUs
                    if (rel >= 0) {
                        val outPts = offsetUs + rel   // não força ordem: preserva B-frames
                        val key = (vex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        info.set(0, size, outPts, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(mv, buf, info)
                        if (pts > maxPts) maxPts = pts
                        if (pts < firstPts) firstPts = pts
                        count++
                    }
                    vex.advance()
                }
                val frameUs = if (count > 1) ((maxPts - firstPts) / (count - 1)).coerceIn(10_000, 100_000) else 33_333
                val segUs = (maxPts - kfUs) + frameUs

                if (ma >= 0) {
                    aex.seekTo(kfUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    while (true) {
                        val pts = aex.sampleTime
                        if (pts < 0) break
                        val rel = pts - kfUs
                        if (rel >= segUs) break
                        val size = aex.readSampleData(buf, 0)
                        if (size < 0) break
                        if (rel >= 0) {
                            var outPts = offsetUs + rel
                            if (outPts <= lastAOut) outPts = lastAOut + 1
                            info.set(0, size, outPts, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                            muxer.writeSampleData(ma, buf, info)
                            lastAOut = outPts
                        }
                        aex.advance()
                    }
                }
                offsetUs += segUs
                doneMs += endMs - startMs
                progress((doneMs * 100 / totalMs).toInt())
            }
            muxer.stop()
        } finally {
            try { muxer.release() } catch (_: Exception) {}
            vex.release(); aex.release()
        }
    }

    /** As partes têm o mesmo formato (dá para juntar sem recodificar)? */
    fun sameFormat(parts: List<File>): Boolean {
        var refV: MediaFormat? = null
        var refA: MediaFormat? = null
        var refHasAudio: Boolean? = null
        for (p in parts) {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(p.path)
                val v = findTrack(ex, "video/"); val a = findTrack(ex, "audio/")
                if (v < 0) return false
                val fv = ex.getTrackFormat(v)
                val fa = if (a >= 0) ex.getTrackFormat(a) else null
                if (refHasAudio == null) { refHasAudio = fa != null; refV = fv; refA = fa; continue }
                if (refHasAudio != (fa != null)) return false
                val rv = refV!!
                if (fv.getString(MediaFormat.KEY_MIME) != rv.getString(MediaFormat.KEY_MIME)) return false
                if (fv.getInteger(MediaFormat.KEY_WIDTH) != rv.getInteger(MediaFormat.KEY_WIDTH)) return false
                if (fv.getInteger(MediaFormat.KEY_HEIGHT) != rv.getInteger(MediaFormat.KEY_HEIGHT)) return false
                for (k in arrayOf("csd-0", "csd-1")) {
                    val x = if (fv.containsKey(k)) fv.getByteBuffer(k) else null
                    val y = if (rv.containsKey(k)) rv.getByteBuffer(k) else null
                    if ((x == null) != (y == null)) return false
                    if (x != null && y != null && x != y) return false
                }
                if (fa != null) {
                    val ra = refA!!
                    if (fa.getInteger(MediaFormat.KEY_SAMPLE_RATE) != ra.getInteger(MediaFormat.KEY_SAMPLE_RATE)) return false
                    if (fa.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != ra.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) return false
                }
            } catch (e: Exception) { return false } finally { ex.release() }
        }
        return true
    }

    /** Junta as partes. O áudio de cada parte é cortado no fim do vídeo dela (sincronia perfeita). */
    fun concat(parts: List<File>, out: File, cancelled: () -> Boolean) {
        val first = MediaExtractor().apply { setDataSource(parts[0].path) }
        val v0 = findTrack(first, "video/"); val a0 = findTrack(first, "audio/")
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val mv = muxer.addTrack(first.getTrackFormat(v0))
            val ma = if (a0 >= 0) muxer.addTrack(first.getTrackFormat(a0)) else -1
            first.release()
            muxer.start()
            val buf = ByteBuffer.allocateDirect(BUF)
            val info = MediaCodec.BufferInfo()
            var offsetUs = 0L
            var lastA = -1L
            for (p in parts) {
                if (cancelled()) throw Cancelled()
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(p.path)
                    val vt = findTrack(ex, "video/"); val at = findTrack(ex, "audio/")
                    // vídeo
                    ex.selectTrack(vt)
                    var minPts = Long.MAX_VALUE; var maxPts = 0L; var count = 0
                    val samples = ArrayList<Long>()
                    while (true) {
                        val pts = ex.sampleTime; if (pts < 0) break
                        samples.add(pts); if (pts < minPts) minPts = pts; if (pts > maxPts) maxPts = pts
                        ex.advance()
                    }
                    if (samples.isEmpty()) continue
                    count = samples.size
                    val frameUs = if (count > 1) ((maxPts - minPts) / (count - 1)).coerceIn(10_000, 100_000) else 33_333
                    val vidDur = maxPts - minPts + frameUs
                    ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    while (true) {
                        val pts = ex.sampleTime; if (pts < 0) break
                        val size = ex.readSampleData(buf, 0); if (size < 0) break
                        val o = offsetUs + (pts - minPts)
                        val key = (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        info.set(0, size, o, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(mv, buf, info)
                        ex.advance()
                    }
                    ex.unselectTrack(vt)
                    // áudio (cortado no tamanho do vídeo)
                    if (ma >= 0 && at >= 0) {
                        ex.selectTrack(at)
                        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        var aMin = -1L
                        while (true) {
                            val pts = ex.sampleTime; if (pts < 0) break
                            if (aMin < 0) aMin = pts
                            val rel = pts - aMin
                            if (rel >= vidDur) break
                            val size = ex.readSampleData(buf, 0); if (size < 0) break
                            var o = offsetUs + rel
                            if (o <= lastA) o = lastA + 1
                            info.set(0, size, o, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                            muxer.writeSampleData(ma, buf, info)
                            lastA = o
                            ex.advance()
                        }
                    }
                    offsetUs += vidDur
                } finally { ex.release() }
            }
            muxer.stop()
        } finally {
            try { muxer.release() } catch (_: Exception) {}
        }
    }
}
