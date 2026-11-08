package com.leve.editor

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.concurrent.Executors

/** Janela de exportar (escolher qualidade, progresso, salvar na galeria). Usada nas duas telas. */
@OptIn(UnstableApi::class)
class ExportFlow(
    private val act: AppCompatActivity,
    private val project: Project,
    private val uri: Uri,
    private val beforeStart: () -> Unit
) {
    private var exporter: Exporter? = null
    private val io = Executors.newSingleThreadExecutor()

    val running: Boolean get() = exporter?.running == true

    fun cancel() { exporter?.cancel() }

    fun ask() {
        if (project.seq.isEmpty()) { toast("A montagem está vazia"); return }
        val hasText = project.texts.any { it.text.isNotBlank() }
        val opts = arrayOf(
            "⚡ Rápida – sem recompressão (segundos)\nCorte pode variar ~1s, sem textos",
            "480p – leve",
            "720p – recomendado",
            "1080p – melhor qualidade"
        )
        val heights = intArrayOf(0, 480, 720, 1080)
        MaterialAlertDialogBuilder(act)
            .setTitle("Exportar ${project.seq.size} cenas • ${Fmt.time(project.seqDurationMs(), false)}")
            .setItems(opts) { _, w ->
                if (w == 0) {
                    if (hasText) Dialogs.confirm(act, "Modo rápido sem textos",
                        "No modo rápido o vídeo é copiado sem recompressão, então os textos não aparecem e cada cena começa no quadro-chave mais próximo (pode variar ~1s).\n\nPara ter os textos, use 480p/720p/1080p.",
                        "Exportar assim") { run(0, false, true) }
                    else run(0, false, true)
                } else run(heights[w], false, false)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()

    private fun run(height: Int, removeAudio: Boolean, fast: Boolean) {
        beforeStart()
        act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val bar = LinearProgressIndicator(act).apply { max = 100; isIndeterminate = true }
        val label = Dialogs.centerText(act, "Preparando…")
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            val p = (24 * act.resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p / 2)
            addView(label)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = p / 2 })
            addView(TextView(act).apply {
                text = "Deixe o app aberto até terminar."
                setTextColor(0xFF9A9AA5.toInt()); textSize = 12f; gravity = Gravity.CENTER
                setPadding(0, p / 2, 0, 0)
            })
        }
        val ex = Exporter(act, project, uri)
        exporter = ex
        val dlg: AlertDialog = MaterialAlertDialogBuilder(act)
            .setTitle(if (fast) "Exportação rápida" else "Exportando ${height}p")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancelar") { _, _ ->
                ex.cancel()
                act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                toast("Exportação cancelada")
            }
            .show()

        val t0 = SystemClock.elapsedRealtime()
        ex.start(if (fast) 1080 else height, removeAudio, fast,
            onProgress = { pct ->
                bar.isIndeterminate = false
                bar.setProgressCompat(pct, true)
                val el = (SystemClock.elapsedRealtime() - t0) / 1000
                val eta = if (pct in 3..99) " • falta ~${Fmt.time(el * (100 - pct) / pct * 1000, false)}" else ""
                label.text = "Exportando… $pct%$eta"
            },
            onDone = { file ->
                act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                label.text = "Salvando na galeria…"
                val name = "${project.name}_${System.currentTimeMillis() / 1000}".replace(Regex("[^A-Za-z0-9À-ú _-]"), "_")
                io.execute {
                    val result = try { Exporter.saveToGallery(act, file, name) } catch (e: Exception) { null }
                    act.runOnUiThread {
                        dlg.dismiss()
                        if (result == null) {
                            MaterialAlertDialogBuilder(act).setTitle("Erro ao salvar")
                                .setMessage("O vídeo foi gerado, mas não consegui salvar na galeria.")
                                .setPositiveButton("OK", null).show()
                        } else showDone(result.first, result.second)
                    }
                }
            },
            onError = { msg ->
                act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                dlg.dismiss()
                val b = MaterialAlertDialogBuilder(act)
                    .setTitle("Erro na exportação")
                    .setMessage("$msg\n\nDica: tente uma qualidade menor" + if (!removeAudio) " ou exporte sem áudio." else ".")
                    .setNegativeButton("Fechar", null)
                    .setNeutralButton(if (fast) "Tentar 720p" else "Tentar 480p") { _, _ ->
                        run(if (fast) 720 else 480, removeAudio, false)
                    }
                if (!removeAudio) b.setPositiveButton("Sem áudio") { _, _ -> run(height, true, fast) }
                b.show()
            }
        )
    }

    private fun showDone(contentUri: Uri?, path: String) {
        val b = MaterialAlertDialogBuilder(act)
            .setTitle("Vídeo pronto! 🎬")
            .setMessage("Salvo em:\n$path")
            .setNegativeButton("OK", null)
        if (contentUri != null) {
            b.setPositiveButton("Abrir") { _, _ ->
                try {
                    act.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(contentUri, "video/mp4")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) { toast("Nenhum app para abrir vídeo") }
            }
            b.setNeutralButton("Compartilhar") { _, _ ->
                act.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("video/mp4")
                        .putExtra(Intent.EXTRA_STREAM, contentUri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Compartilhar vídeo"))
            }
        }
        b.show()
    }

    fun shutdown() { exporter?.cancel(); io.shutdown() }
}
