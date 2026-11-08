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
    var running = false
        private set

    /**
     * [fast] = sem recompressão: copia o vídeo como está (segundos), mas cada corte começa
     * no keyframe mais próximo e não tem texto. Senão recodifica (corte exato + texto).
     */
    fun start(
        height: Int,
        removeAudio: Boolean,
        fast: Boolean,
        onProgress: (Int) -> Unit,
        onDone: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        // limpa exports antigos
        ctx.cacheDir.listFiles { f -> f.name.startsWith("export_") }?.forEach { it.delete() }

        // tamanho final (mantém proporção do filme, números pares)
        val outH = minOf(height, project.videoH).let { it - it % 2 }
        val outW = (project.videoW.toLong() * outH / project.videoH).toInt().let { it - it % 2 }

        val starts = project.clipStarts()
        val items = project.seq.mapIndexedNotNull { i, s ->
            val sc = project.scene(s.sceneId) ?: return@mapIndexedNotNull null
            val mi = MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(sc.startMs)
                        .setEndPositionMs(sc.endMs)
                        .setStartsAtKeyFrame(fast)
                        .build()
                )
                .build()
            if (fast) {
                return@mapIndexedNotNull EditedMediaItem.Builder(mi).setRemoveAudio(removeAudio).build()
            }
            val video = mutableListOf<Effect>(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT))
            // textos que aparecem durante essa cena
            val cs = starts[i]; val ce = starts[i + 1]
            val overlays = project.texts
                .filter { it.text.isNotBlank() && it.startMs < ce && it.endMs > cs }
                .map { TimedTextOverlay(it, cs, outH) as TextureOverlay }
            if (overlays.isNotEmpty()) video.add(OverlayEffect(ImmutableList.copyOf(overlays)))
            EditedMediaItem.Builder(mi)
                .setEffects(Effects(emptyList(), video))
                .setRemoveAudio(removeAudio)
                .build()
        }
        if (items.isEmpty()) { onError("A montagem está vazia."); return }

        val composition = Composition.Builder(EditedMediaItemSequence(items)).build()
        val out = File(ctx.cacheDir, "export_${System.currentTimeMillis()}.mp4")

        val builder = Transformer.Builder(ctx)
        if (!fast) {
            // bitrate na medida: arquivo menor e codificador de hardware trabalhando folgado
            val bitrate = when {
                outH <= 480 -> 1_500_000
                outH <= 720 -> 3_000_000
                else -> 6_000_000
            }
            builder.setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(ctx)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                        .setEnableFallback(true)
                        .build()
                )
        }
        val t = builder
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finish()
                    onDone(out)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    finish()
                    out.delete()
                    val cause = exportException.cause?.message ?: exportException.message ?: ""
                    onError("${exportException.errorCodeName}\n$cause")
                }
            })
            .build()
        transformer = t
        running = true
        t.start(composition, out.absolutePath)

        val holder = ProgressHolder()
        val r = object : Runnable {
            override fun run() {
                val tr = transformer ?: return
                if (tr.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                main.postDelayed(this, 400)
            }
        }
        poll = r
        main.postDelayed(r, 400)
    }

    private fun finish() {
        running = false
        poll?.let { main.removeCallbacks(it) }
        poll = null
        transformer = null
    }

    fun cancel() {
        transformer?.cancel()
        finish()
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
