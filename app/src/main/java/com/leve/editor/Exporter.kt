package com.leve.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextOverlay
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

/** Texto desenhado em cima do vídeo exportado. Some depois de [SeqItem.secs] segundos. */
@UnstableApi
class SceneTextOverlay(item: SeqItem, frameW: Int, frameH: Int) : TextOverlay() {
    private val text: SpannableString
    private val shown: OverlaySettings
    private val hidden: OverlaySettings
    private val limitUs = if (item.secs <= 0) Long.MAX_VALUE else item.secs * 1_000_000L
    private var firstUs = C.TIME_UNSET

    init {
        val str = if (item.bg) "  ${item.text}  " else item.text
        var px = (frameH * TextStyle.sizeFactor(item.size)).toInt().coerceAtLeast(12)
        // diminui se o texto não couber na largura
        val paint = Paint().apply { textSize = px.toFloat(); typeface = Typeface.DEFAULT_BOLD }
        val wText = paint.measureText(str)
        if (wText > frameW * 0.94f) px = (px * frameW * 0.94f / wText).toInt().coerceAtLeast(10)

        text = SpannableString(str)
        val n = str.length
        val f = Spannable.SPAN_INCLUSIVE_EXCLUSIVE
        text.setSpan(AbsoluteSizeSpan(px), 0, n, f)
        text.setSpan(ForegroundColorSpan(item.color), 0, n, f)
        text.setSpan(StyleSpan(Typeface.BOLD), 0, n, f)
        if (item.bg) text.setSpan(BackgroundColorSpan(TextStyle.BG_COLOR), 0, n, f)

        val (bgY, ovY) = when (item.pos) {
            TextStyle.POS_TOP -> 0.88f to 1f
            TextStyle.POS_CENTER -> 0f to 0f
            else -> -0.88f to -1f
        }
        shown = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, bgY)
            .setOverlayFrameAnchor(0f, ovY)
            .build()
        hidden = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, bgY)
            .setOverlayFrameAnchor(0f, ovY)
            .setAlphaScale(0f)
            .build()
    }

    override fun getText(presentationTimeUs: Long): SpannableString = text

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        if (firstUs == C.TIME_UNSET) firstUs = presentationTimeUs
        return if (presentationTimeUs - firstUs < limitUs) shown else hidden
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

        val items = project.seq.mapNotNull { s ->
            val sc = project.scene(s.sceneId) ?: return@mapNotNull null
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
                return@mapNotNull EditedMediaItem.Builder(mi).setRemoveAudio(removeAudio).build()
            }
            val video = mutableListOf<Effect>(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT))
            if (s.text.isNotBlank()) {
                video.add(OverlayEffect(ImmutableList.of<TextureOverlay>(SceneTextOverlay(s, outW, outH))))
            }
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
