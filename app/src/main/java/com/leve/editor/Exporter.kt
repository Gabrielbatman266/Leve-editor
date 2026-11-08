package com.leve.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import java.io.File

/**
 * Texto da montagem desenhado no vídeo exportado. Usa o mesmo [TextRenderer] e as mesmas
 * animações da prévia. [clipStartMs] = onde essa cena começa no vídeo final.
 */
@UnstableApi
class TimedTextOverlay(
    private val t: TextItem,
    private val clipStartMs: Long,
    private val frameH: Int
) : BitmapOverlay() {
    private var bmp: Bitmap? = null
    private var firstUs = C.TIME_UNSET
    private val st = TextAnim.State()
    private val hidden = OverlaySettings.Builder().setAlphaScale(0f).build()

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        bmp?.let { return it }
        return TextRenderer.render(t, frameH).also { bmp = it }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        if (firstUs == C.TIME_UNSET) firstUs = presentationTimeUs
        val g = clipStartMs + (presentationTimeUs - firstUs) / 1000
        val s = TextAnim.state(t, g, st) ?: return hidden
        val nx = (t.x + s.dx) * 2f - 1f
        val ny = 1f - (t.y + s.dy) * 2f
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(nx, ny)
            .setScale(s.scale, s.scale)
            .setRotationDegrees(-(t.rot + s.rot))
            .setAlphaScale(s.alpha.coerceIn(0f, 1f))
            .build()
    }
}

@UnstableApi
class Exporter(private val ctx: Context, private val project: Project, private val uri: Uri) {

    private val main = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var poll: Runnable? = null
    @Volatile private var cancelled = false
    var running = false
        private set

    /**
     * [fast] = sem recompressão (cópia direta, segundos; cada cena começa no quadro-chave anterior, sem texto).
     * Senão: cada cena é exportada separada (corte exato + textos) e depois as partes são juntadas
     * sem recodificar, com o áudio de cada parte cortado no tamanho do vídeo (sem dessincronizar).
     */
    fun start(
        height: Int,
        removeAudio: Boolean,
        fast: Boolean,
        onProgress: (Int) -> Unit,
        onDone: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        ctx.cacheDir.listFiles { f -> f.name.startsWith("export_") || f.name.startsWith("part_") }?.forEach { it.delete() }
        cancelled = false
        running = true
        val out = File(ctx.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        val clips = project.seq.mapNotNull { s -> project.scene(s.sceneId)?.let { it.startMs to it.endMs } }
        if (clips.isEmpty()) { running = false; onError("A montagem está vazia."); return }

        if (fast) {
            Thread {
                try {
                    Mp4Tools.fastCopy(ctx, uri, clips, out, removeAudio, { cancelled }) { p -> main.post { onProgress(p) } }
                    main.post { running = false; if (!cancelled) onDone(out) }
                } catch (e: Mp4Tools.Cancelled) {
                    out.delete(); main.post { running = false }
                } catch (e: Exception) {
                    out.delete()
                    main.post { running = false; if (!cancelled) onError("Cópia rápida falhou: ${e.message}\nUse 480p/720p.") }
                }
            }.start()
            return
        }

        // ---- modo exato: uma parte por cena ----
        val outH = minOf(height, project.videoH).let { it - it % 2 }
        val outW = (project.videoW.toLong() * outH / project.videoH).toInt().let { it - it % 2 }
        val fps = Mp4Tools.frameRate(ctx, uri)
        val starts = project.clipStarts()
        val total = starts.last().coerceAtLeast(1)
        val parts = ArrayList<File>()
        val bitrate = when {
            outH <= 480 -> 1_500_000
            outH <= 720 -> 3_000_000
            else -> 6_000_000
        }

        fun partItem(i: Int): EditedMediaItem? {
            val sc = project.scene(project.seq[i].sceneId) ?: return null
            val mi = MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(sc.startMs)
                        .setEndPositionMs(sc.endMs)
                        .build()
                )
                .build()
            val video = mutableListOf<Effect>()
            // filme com mais de 30 quadros/s: exporta em 30 (metade do trabalho)
            if (fps > 31f) video.add(FrameDropEffect.createDefaultFrameDropEffect(30f))
            video.add(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT))
            val cs = starts[i]; val ce = starts[i + 1]
            val overlays: List<TextureOverlay> = project.texts
                .filter { it.text.isNotBlank() && it.startMs < ce && it.endMs > cs }
                .map { TimedTextOverlay(it, cs, outH) }
            if (overlays.isNotEmpty()) video.add(OverlayEffect(ImmutableList.copyOf(overlays)))
            return EditedMediaItem.Builder(mi)
                .setEffects(Effects(emptyList(), video))
                .setRemoveAudio(removeAudio)
                .build()
        }

        fun buildTransformer(listener: Transformer.Listener): Transformer =
            Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(ctx)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                        .setEnableFallback(true)
                        .build()
                )
                .addListener(listener)
                .build()

        val holder = ProgressHolder()
        var current = 0

        fun finishAll() {
            onProgress(99)
            Thread {
                try {
                    if (parts.size == 1) {
                        parts[0].renameTo(out)
                    } else if (Mp4Tools.sameFormat(parts)) {
                        Mp4Tools.concat(parts, out) { cancelled }
                    } else {
                        throw IllegalStateException("partes com formatos diferentes")
                    }
                    parts.forEach { it.delete() }
                    main.post { running = false; if (!cancelled) onDone(out) }
                } catch (e: Exception) {
                    // plano B: deixa o Transformer juntar as partes
                    main.post { if (!cancelled) joinWithTransformer(parts, out, onDone, onError) else running = false }
                }
            }.start()
        }

        fun next() {
            if (cancelled) return
            if (current >= project.seq.size) { stopPoll(); finishAll(); return }
            val item = partItem(current)
            if (item == null) { current++; next(); return }
            val partFile = File(ctx.cacheDir, "part_${current}.mp4")
            val t = buildTransformer(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    parts.add(partFile)
                    current++
                    next()
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    stopPoll()
                    running = false
                    transformer = null
                    parts.forEach { it.delete() }; partFile.delete()
                    val cause = exportException.cause?.message ?: exportException.message ?: ""
                    if (!cancelled) onError("Cena ${current + 1}: ${exportException.errorCodeName}\n$cause")
                }
            })
            transformer = t
            t.start(item, partFile.absolutePath)
        }

        val r = object : Runnable {
            override fun run() {
                val tr = transformer
                if (tr != null && current < project.seq.size) {
                    val p = if (tr.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else 0
                    val done = starts[current] + (starts[current + 1] - starts[current]) * p / 100
                    onProgress((done * 98 / total).toInt())
                }
                main.postDelayed(this, 400)
            }
        }
        poll = r
        main.postDelayed(r, 400)
        next()
    }

    /** Plano B: junta as partes com o Transformer (recodifica se precisar). */
    private fun joinWithTransformer(parts: List<File>, out: File, onDone: (File) -> Unit, onError: (String) -> Unit) {
        val items = parts.map { EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(it))).build() }
        val t = Transformer.Builder(ctx)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    running = false; parts.forEach { it.delete() }; onDone(out)
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    running = false; parts.forEach { it.delete() }
                    onError("Juntar as cenas falhou: ${exportException.errorCodeName}")
                }
            })
            .build()
        transformer = t
        t.start(Composition.Builder(EditedMediaItemSequence(items)).build(), out.absolutePath)
    }

    private fun stopPoll() {
        poll?.let { main.removeCallbacks(it) }
        poll = null
    }

    fun cancel() {
        cancelled = true
        transformer?.cancel()
        transformer = null
        stopPoll()
        running = false
    }

    companion object {
        /** Copia o vídeo pronto para a galeria (Filmes/LeveEditor). Retorna (uri, caminho legível). */
        fun saveToGallery(ctx: Context, file: File, name: String): Pair<Uri?, String> {
            val display = "$name.mp4"
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, display)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/LeveEditor")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val u = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Não consegui criar o arquivo na galeria")
                resolver.openOutputStream(u)!!.use { os -> file.inputStream().use { it.copyTo(os) } }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(u, values, null, null)
                file.delete()
                return u to "Filmes/LeveEditor/$display"
            }
            val dir = try {
                @Suppress("DEPRECATION")
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "LeveEditor").apply { mkdirs() }
            } catch (e: Exception) { null }
            val target = if (dir != null && dir.canWrite()) File(dir, display)
            else File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), display)
            file.copyTo(target, overwrite = true)
            file.delete()
            MediaScannerConnection.scanFile(ctx, arrayOf(target.path), arrayOf("video/mp4"), null)
            return null to target.path
        }
    }
}
