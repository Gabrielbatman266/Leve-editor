package com.leve.editor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: ProjectsAdapter
    private lateinit var empty: TextView
    private val io = Executors.newSingleThreadExecutor()

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) createProject(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        empty = findViewById(R.id.tvEmpty)
        adapter = ProjectsAdapter(::open, ::more)
        findViewById<RecyclerView>(R.id.rvProjects).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        findViewById<MaterialButton>(R.id.btnNew).setOnClickListener {
            try { pick.launch(arrayOf("video/*")) } catch (e: Exception) {
                Toast.makeText(this, "Não achei um seletor de arquivos", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<TextView>(R.id.tvUpdate).apply {
            text = "Versão build ${Updater.currentBuild(this@MainActivity)}  •  Verificar atualização"
            setOnClickListener { Updater.check(this@MainActivity, manual = true) }
        }
        Updater.check(this, manual = false)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        adapter.items = Store.all(this)
        empty.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun open(p: Project) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra("id", p.id))
    }

    private fun more(p: Project, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Renomear")
            menu.add(0, 2, 1, "Apagar projeto")
            setOnMenuItemClickListener { mi ->
                when (mi.itemId) {
                    1 -> Dialogs.input(this@MainActivity, "Nome do projeto", p.name) { n ->
                        p.name = n; Store.save(this@MainActivity, p); refresh()
                    }
                    2 -> Dialogs.confirm(
                        this@MainActivity, "Apagar \"${p.name}\"?",
                        "As cenas e a montagem desse projeto serão apagadas. O filme original não é apagado.", "Apagar"
                    ) { Store.delete(this@MainActivity, p.id); refresh() }
                }
                true
            }
            show()
        }
    }

    private fun createProject(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}

        val dlg = MaterialAlertDialogBuilder(this)
            .setView(Dialogs.centerText(this, "Lendo o filme…").apply { setPadding(0, 60, 0, 60) })
            .setCancelable(false)
            .show()

        io.execute {
            val info = Frames.info(this, uri)
            var name = "Projeto"
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0)?.substringBeforeLast('.') ?: name
                }
            } catch (_: Exception) {}
            runOnUiThread {
                dlg.dismiss()
                if (info == null) {
                    Toast.makeText(this, "Não consegui ler esse vídeo. Tente outro arquivo (MP4 funciona melhor).", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val p = Project(newId(), name, uri.toString(), info.durationMs, info.w, info.h)
                Store.save(this, p)
                open(p)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }
}
