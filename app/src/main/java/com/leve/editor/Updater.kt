package com.leve.editor

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Atualização automática: olha a última Release do GitHub e instala o APK novo. */
object Updater {
    private const val API = "https://api.github.com/repos/Gabrielbatman266/Leve-editor/releases/latest"
    private var checkedThisRun = false

    fun currentBuild(a: Activity): Long = try {
        val pi = a.packageManager.getPackageInfo(a.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (e: Exception) { 0L }

    /** [manual] = usuário tocou em "Verificar"; mostra aviso mesmo se já estiver atualizado. */
    fun check(a: Activity, manual: Boolean) {
        if (!manual && checkedThisRun) return
        checkedThisRun = true
        Thread {
            try {
                val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "LeveEditor")
                }
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                conn.disconnect()
                val latest = json.optString("tag_name").substringAfter("build-").toLongOrNull() ?: 0L
                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                if (assets != null) for (i in 0 until assets.length()) {
                    val o = assets.getJSONObject(i)
                    if (o.optString("name").endsWith(".apk")) { apkUrl = o.optString("browser_download_url"); break }
                }
                val cur = currentBuild(a)
                a.runOnUiThread {
                    if (a.isFinishing) return@runOnUiThread
                    if (latest > cur && apkUrl != null) offer(a, latest, cur, apkUrl)
                    else if (manual) Toast.makeText(a, "Você já está na versão mais nova (build $cur)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (manual) a.runOnUiThread {
                    Toast.makeText(a, "Sem internet ou GitHub fora do ar", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun offer(a: Activity, latest: Long, cur: Long, url: String) {
        MaterialAlertDialogBuilder(a)
            .setTitle("Atualização disponível")
            .setMessage("Nova versão: build $latest (você tem a $cur).\nSeus projetos e cenas continuam salvos.")
            .setPositiveButton("Atualizar") { _, _ -> download(a, url) }
            .setNegativeButton("Depois", null)
            .show()
    }

    private fun download(a: Activity, url: String) {
        val bar = LinearProgressIndicator(a).apply { isIndeterminate = true; max = 100 }
        val pad = (24 * a.resources.displayMetrics.density).toInt()
        bar.setPadding(pad, pad, pad, pad)
        val dlg: AlertDialog = MaterialAlertDialogBuilder(a)
            .setTitle("Baixando atualização…")
            .setView(bar)
            .setCancelable(false)
            .show()
        val out = File(a.cacheDir, "update.apk")
        Thread {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 10000; readTimeout = 20000
                    setRequestProperty("User-Agent", "LeveEditor")
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    a.runOnUiThread { bar.isIndeterminate = false; bar.setProgressCompat(pct, true) }
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
                a.runOnUiThread { dlg.dismiss(); install(a, out) }
            } catch (e: Exception) {
                a.runOnUiThread {
                    dlg.dismiss()
                    Toast.makeText(a, "Falha ao baixar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    fun install(a: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(a)
                .setTitle("Permitir instalação")
                .setMessage("Libere \"Instalar apps desconhecidos\" para o Leve Editor e depois volte e toque em Verificar atualização de novo.")
                .setPositiveButton("Abrir configuração") { _, _ ->
                    a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(a, "${a.packageName}.files", apk)
        a.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
