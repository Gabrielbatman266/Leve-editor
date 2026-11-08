package com.leve.editor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider

/**
 * Editor de montagem estilo CapCut: prévia em cima, linha do tempo com faixa de cenas e
 * faixa de textos, barra de ferramentas que muda conforme o que está selecionado e
 * painel de texto com Estilos, Fonte, Cor, Contorno, Brilho, Fundo, Sombra e Animação.
 */
@OptIn(UnstableApi::class)
class MontageActivity : AppCompatActivity(), MontageTimelineView.Listener, TextLayerView.Listener {

    private lateinit var project: Project
    private lateinit var uri: Uri
    private lateinit var player: ExoPlayer

    private lateinit var previewBox: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var textLayer: TextLayerView
    private lateinit var tvEmpty: View
    private lateinit var tvPos: TextView
    private lateinit var btnPlay: TextView
    private lateinit var timeline: MontageTimelineView
    private lateinit var toolbar: LinearLayout
    private lateinit var editArea: View
    private lateinit var textPanel: View
    private lateinit var etText: EditText
    private lateinit var tabRow: LinearLayout
    private lateinit var tabContent: LinearLayout

    private var thumbs: ThumbnailProvider? = null
    private val handler = Handler(Looper.getMainLooper())
    private var starts = LongArray(1)
    private val total get() = starts.last()

    private var selClip = -1
    private var selText: String? = null
    private var stopAt = -1L
    private var lastSeekAt = 0L
    private var editing: TextItem? = null
    private var currentTab = 0

    private val d get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val exportFlow by lazy { ExportFlow(this, project, uri) { closeTextPanel(); player.pause() } }
    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { exportFlow.ask() }

    // ================================================================ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = intent.getStringExtra("id")?.let { Store.load(this, it) }
        if (p == null) { finish(); return }
        project = p
        uri = Uri.parse(p.videoUri)
        setContentView(R.layout.activity_montage)

        previewBox = findViewById(R.id.previewBox)
        playerView = findViewById(R.id.playerView)
        textLayer = findViewById(R.id.textLayer)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvPos = findViewById(R.id.tvPos)
        btnPlay = findViewById(R.id.btnPlay)
        timeline = findViewById(R.id.mtimeline)
        toolbar = findViewById(R.id.toolbar)
        editArea = findViewById(R.id.editArea)
        textPanel = findViewById(R.id.textPanel)
        etText = findViewById(R.id.etText)
        tabRow = findViewById(R.id.tabRow)
        tabContent = findViewById(R.id.tabContent)

        findViewById<TextView>(R.id.tvTitle).text = "Montagem • ${p.name}"
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnExport).setOnClickListener { exportClicked() }
        findViewById<View>(R.id.btnZoomIn).setOnClickListener { timeline.zoomIn() }
        findViewById<View>(R.id.btnZoomOut).setOnClickListener { timeline.zoomOut() }
        btnPlay.setOnClickListener { togglePlay() }

        previewBox.post {
            previewBox.layoutParams = previewBox.layoutParams.apply { height = previewBox.width * 9 / 16 }
        }

        textLayer.project = project
        textLayer.videoW = project.videoW
        textLayer.videoH = project.videoH
        textLayer.listener = this

        val tilePx = dp(54)
        val tlDir = java.io.File(Store.thumbFile(this, project.id, "x").parentFile, "tl")
        thumbs = ThumbnailProvider(this, uri, tlDir, (tilePx * 16 / 9 * 0.8f).toInt(), (tilePx * 0.8f).toInt()) {
            timeline.invalidate()
        }
        timeline.project = project
        timeline.thumbs = thumbs
        timeline.listener = this

        val load = DefaultLoadControl.Builder().setBufferDurationsMs(2_000, 8_000, 300, 800).build()
        player = ExoPlayer.Builder(this).setLoadControl(load).build()
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlay.text = if (isPlaying) "⏸" else "▶"
            }
        })

        etText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val t = editing ?: return
                t.text = s?.toString() ?: ""
                styleChanged()
            }
        })
        findViewById<View>(R.id.btnTextDone).setOnClickListener { closeTextPanel() }
        buildTabs()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (textPanel.visibility == View.VISIBLE) closeTextPanel() else finish()
            }
        })

        starts = project.clipStarts()
        val clip = intent.getIntExtra("clip", -1)
        val g0 = if (clip in project.seq.indices) starts[clip] else 0L
        if (clip in project.seq.indices) { selClip = clip; timeline.selectedClip = clip }
        rebuild(g0)
        updateToolbar()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        if (!exportFlow.running) player.pause()
        save()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!::project.isInitialized) return
        handler.removeCallbacksAndMessages(null)
        exportFlow.shutdown()
        thumbs?.release()
        player.release()
    }

    private fun save() = Store.save(this, project)
    private val saveRunnable = Runnable { save() }
    private fun saveSoon() { handler.removeCallbacks(saveRunnable); handler.postDelayed(saveRunnable, 500) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun exportClicked() {
        save()
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else exportFlow.ask()
    }

    // ================================================================ player (tempo global)

    private fun rebuild(g: Long) {
        starts = project.clipStarts()
        timeline.refresh()
        tvEmpty.visibility = if (project.seq.isEmpty()) View.VISIBLE else View.GONE
        if (project.seq.isEmpty()) {
            player.clearMediaItems()
            timeline.jumpTo(0); textLayer.timeMs = 0
            return
        }
        val items = project.seq.map { s ->
            val sc = project.scene(s.sceneId)!!
            MediaItem.Builder().setUri(uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(sc.startMs).setEndPositionMs(sc.endMs).build()
                ).build()
        }
        val gg = g.coerceIn(0, total - 1)
        val (i, off) = locate(gg)
        player.setSeekParameters(SeekParameters.EXACT)
        player.setMediaItems(items, i, off)
        player.prepare()
        timeline.jumpTo(gg)
        textLayer.timeMs = gg
    }

    private fun locate(g: Long): Pair<Int, Long> {
        val n = project.seq.size
        if (n == 0) return 0 to 0L
        var i = 0
        while (i < n - 1 && g >= starts[i + 1]) i++
        return i to (g - starts[i]).coerceAtLeast(0)
    }

    private fun globalPos(): Long {
        if (project.seq.isEmpty()) return 0
        val i = player.currentMediaItemIndex.coerceIn(0, project.seq.size - 1)
        return starts[i] + player.currentPosition
    }

    private fun seekGlobal(g: Long, exact: Boolean = true) {
        if (project.seq.isEmpty()) return
        val (i, off) = locate(g.coerceIn(0, total - 1))
        player.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        player.seekTo(i, off)
    }

    private fun jump(g: Long) {
        player.pause(); stopAt = -1
        seekGlobal(g)
        timeline.jumpTo(g)
        textLayer.timeMs = g
    }

    private fun playRange(from: Long, to: Long) {
        seekGlobal(from)
        timeline.jumpTo(from)
        stopAt = to
        player.play()
    }

    private fun togglePlay() {
        if (project.seq.isEmpty()) return
        if (player.isPlaying) { player.pause(); return }
        stopAt = -1
        if (globalPos() >= total - 120) seekGlobal(0)
        player.play()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!timeline.userActive) {
                val g = globalPos()
                if (stopAt >= 0 && g >= stopAt) { player.pause(); stopAt = -1 }
                timeline.setTime(g)
                textLayer.timeMs = g
                textLayer.staticSelected = !player.isPlaying
                tvPos.text = "${Fmt.time(g)} / ${Fmt.time(total, false)}"
            }
            handler.postDelayed(this, 30)
        }
    }

    // ================================================================ linha do tempo

    override fun onScrub(g: Long) {
        if (player.isPlaying) player.pause()
        stopAt = -1
        textLayer.timeMs = g
        tvPos.text = "${Fmt.time(g)} / ${Fmt.time(total, false)}"
        val now = SystemClock.uptimeMillis()
        if (now - lastSeekAt > 70) { seekGlobal(g, exact = false); lastSeekAt = now }
    }

    override fun onScrubEnd(g: Long) { seekGlobal(g, exact = true); textLayer.timeMs = g }

    override fun onSelectClip(i: Int) {
        selClip = i; selText = null
        textLayer.selectedId = null
        updateToolbar()
    }

    override fun onSelectText(id: String) {
        selText = id; selClip = -1
        textLayer.selectedId = id
        project.texts.firstOrNull { it.id == id }?.let { t ->
            val g = timeline.timeMs
            if (g < t.startMs || g >= t.endMs) jump(t.startMs + minOf(t.durMs / 2, 600L))
        }
        updateToolbar()
    }

    override fun onDeselect() {
        selClip = -1; selText = null
        textLayer.selectedId = null
        updateToolbar()
    }

    override fun onTextTimingChanged(t: TextItem, done: Boolean) {
        textLayer.invalidate()
        if (done) save()
    }

    override fun onReorder(from: Int, to: Int) {
        val item = project.seq.removeAt(from)
        project.seq.add(to.coerceIn(0, project.seq.size), item)
        save()
        selClip = to; timeline.selectedClip = to
        starts = project.clipStarts()
        rebuild(starts[to])
        updateToolbar()
        toast("Cena movida para a posição ${to + 1}")
    }

    // ================================================================ camada de texto (prévia)

    override fun onSelect(id: String?) {
        selText = id; selClip = -1
        timeline.selectedText = id; timeline.selectedClip = -1
        if (id != null && textPanel.visibility == View.VISIBLE) {
            project.texts.firstOrNull { it.id == id }?.let { bindPanel(it) }
        }
        updateToolbar()
    }

    override fun onEdit(id: String) = openTextPanel(id, keyboard = true)

    override fun onDelete(id: String) = deleteText(id)

    override fun onChanged(t: TextItem, done: Boolean) {
        timeline.invalidate()
        if (done) save()
    }

    // ================================================================ barra de ferramentas

    private fun tool(icon: String, label: String, accent: Boolean = false, onClick: () -> Unit) {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            isClickable = true
            val tv = TypedValueHolder.selectable(this@MontageActivity)
            if (tv != 0) setBackgroundResource(tv)
            setOnClickListener { onClick() }
            addView(TextView(context).apply {
                text = icon; textSize = 20f; gravity = Gravity.CENTER
                setTextColor(if (accent) 0xFF22D3EE.toInt() else Color.WHITE)
            })
            addView(TextView(context).apply {
                text = label; textSize = 11f; gravity = Gravity.CENTER
                setTextColor(0xFFB8B8C2.toInt())
            })
        }
        toolbar.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            marginEnd = dp(2)
        })
    }

    private fun updateToolbar() {
        timeline.selectedClip = selClip
        timeline.selectedText = selText
        toolbar.removeAllViews()
        val t = project.texts.firstOrNull { it.id == selText }
        when {
            t != null -> {
                tool("✕", "Fechar") { onDeselect(); timeline.selectedText = null }
                tool("✎", "Editar", true) { openTextPanel(t.id, keyboard = true) }
                tool("Aa", "Estilo") { openTextPanel(t.id, keyboard = false, tab = 0) }
                tool("✦", "Animação") { openTextPanel(t.id, keyboard = false, tab = 7) }
                tool("⇤", "Início aqui") {
                    val g = timeline.timeMs
                    if (g < t.endMs - MontageTimelineView.MIN_TEXT) { t.durMs = t.endMs - g; t.startMs = g; save(); timeline.invalidate() }
                }
                tool("⇥", "Fim aqui") {
                    val g = timeline.timeMs
                    if (g > t.startMs + MontageTimelineView.MIN_TEXT) { t.durMs = g - t.startMs; save(); timeline.invalidate() }
                }
                tool("⧉", "Duplicar") {
                    val c = t.duplicate()
                    c.startMs = t.endMs
                    project.texts.add(c); save()
                    selText = c.id; textLayer.selectedId = c.id
                    jump(c.startMs + minOf(c.durMs / 2, 600L))
                    updateToolbar()
                }
                tool("🗑", "Excluir") { deleteText(t.id) }
            }
            selClip in project.seq.indices -> {
                val i = selClip
                tool("✕", "Fechar") { onDeselect() }
                tool("T", "Texto aqui", true) {
                    if (timeline.timeMs < starts[i] || timeline.timeMs >= starts[i + 1]) jump(starts[i])
                    addText()
                }
                tool("▶", "Tocar cena") { playRange(starts[i], starts[i + 1]) }
                tool("◀", "Mover") { if (i > 0) onReorder(i, i - 1) }
                tool("▶", "Mover") { if (i < project.seq.size - 1) onReorder(i, i + 1) }
                tool("⧉", "Duplicar") {
                    project.seq.add(i + 1, SeqItem(newId(), project.seq[i].sceneId))
                    save(); selClip = i + 1
                    rebuild(project.clipStarts()[i + 1]); updateToolbar()
                }
                tool("🗑", "Excluir") {
                    project.seq.removeAt(i)
                    save(); selClip = -1
                    rebuild(starts[i].coerceAtMost(maxOf(0L, project.clipStarts().last() - 1)))
                    updateToolbar()
                }
            }
            else -> {
                tool("＋", "Cenas", true) { showScenesSheet() }
                tool("T", "Texto", true) { addText() }
                tool("☰", "Títulos auto") { autoTitles() }
                tool("⏮", "Início") { jump(0) }
                tool("⬆", "Exportar") { exportClicked() }
            }
        }
    }

    // ================================================================ cenas (jogar cortes)

    private fun showScenesSheet() {
        val sheet = BottomSheetDialog(this)
        val scenes = project.sortedScenes()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121214.toInt())
            setPadding(dp(8), dp(12), dp(8), dp(8))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        header.addView(TextView(this).apply {
            text = if (scenes.isEmpty()) "Nenhuma cena cortada ainda" else "Toque para jogar na montagem"
            setTextColor(Color.WHITE); textSize = 15f; setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "Todas"; setTextColor(0xFF22D3EE.toInt()); textSize = 14f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { scenes.forEach { insertScene(it, quiet = true) }; toast("${scenes.size} cenas adicionadas"); sheet.dismiss() }
        })
        root.addView(header)
        val rv = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MontageActivity, 3)
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = scenes.size
                override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
                    val col = LinearLayout(p.context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(4), dp(4), dp(4), dp(8))
                        isClickable = true
                        val bg = TypedValueHolder.selectable(this@MontageActivity)
                        if (bg != 0) setBackgroundResource(bg)
                    }
                    col.addView(ImageView(p.context).apply {
                        id = View.generateViewId(); tag = "img"
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(0xFF2A2A30.toInt())
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)))
                    col.addView(TextView(p.context).apply {
                        tag = "name"; setTextColor(Color.WHITE); textSize = 12f; maxLines = 1
                        setPadding(0, dp(4), 0, 0)
                    })
                    col.addView(TextView(p.context).apply { tag = "dur"; setTextColor(0xFF9A9AA5.toInt()); textSize = 11f })
                    return object : RecyclerView.ViewHolder(col) {}
                }
                override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                    val s = scenes[i]
                    h.itemView.findViewWithTag<ImageView>("img").setImageBitmap(ThumbCache.get(Store.thumbFile(this@MontageActivity, project.id, s.id)))
                    h.itemView.findViewWithTag<TextView>("name").text = s.name
                    h.itemView.findViewWithTag<TextView>("dur").text = Fmt.dur(s.durMs)
                    h.itemView.setOnClickListener { insertScene(s, quiet = false) }
                }
            }
        }
        root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)))
        sheet.setContentView(root)
        sheet.show()
    }

    /** Coloca a cena depois da selecionada (ou no fim), igual ao CapCut. */
    private fun insertScene(s: Scene, quiet: Boolean) {
        val at = if (selClip in project.seq.indices) selClip + 1 else project.seq.size
        project.seq.add(at, SeqItem(newId(), s.id))
        save()
        selClip = at
        rebuild(project.clipStarts()[at])
        updateToolbar()
        if (!quiet) toast("+ ${s.name}")
    }

    // ================================================================ textos

    private fun addText() {
        if (project.seq.isEmpty()) { toast("Coloque cenas primeiro (+ Cenas)"); return }
        val g = timeline.timeMs.coerceIn(0, maxOf(0L, total - 300))
        val dur = minOf(3000L, total - g).coerceAtLeast(300L)
        val t = TextItem(newId(), "", g, dur)
        t.y = 0.5f
        TextPresets.ALL[0].apply(t)
        t.animIn = 1; t.animOut = 1
        project.texts.add(t)
        save()
        selText = t.id; selClip = -1
        textLayer.selectedId = t.id
        updateToolbar()
        openTextPanel(t.id, keyboard = true)
    }

    private fun deleteText(id: String) {
        project.texts.removeAll { it.id == id }
        textLayer.forget(id)
        if (editing?.id == id) { editing = null; closeTextPanel() }
        selText = null; textLayer.selectedId = null
        save(); timeline.invalidate(); textLayer.invalidate()
        updateToolbar()
    }

    private fun autoTitles() {
        if (project.seq.isEmpty()) { toast("A montagem está vazia"); return }
        val opts = arrayOf("Cena 1 – Nome da cena", "Só o nome da cena", "Só o número (Cena 1)")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Colocar um título no começo de cada cena")
            .setItems(opts) { _, which ->
                project.seq.forEachIndexed { i, it ->
                    val name = project.scene(it.sceneId)?.name ?: ""
                    val txt = when (which) { 0 -> "Cena ${i + 1} – $name"; 1 -> name; else -> "Cena ${i + 1}" }
                    val len = starts[i + 1] - starts[i]
                    val t = TextItem(newId(), txt, starts[i], minOf(3000L, len).coerceAtLeast(300L))
                    t.y = 0.86f
                    TextPresets.ALL[7].apply(t)
                    t.animIn = 2; t.animOut = 1
                    project.texts.add(t)
                }
                save(); timeline.invalidate(); textLayer.invalidate()
                toast("${project.seq.size} títulos criados")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ================================================================ painel de texto

    private val TABS = arrayOf("Estilos", "Fonte", "Cor", "Contorno", "Brilho", "Fundo", "Sombra", "Animação", "Ajustes")
    private val tabViews = mutableListOf<TextView>()

    private fun buildTabs() {
        TABS.forEachIndexed { i, name ->
            val tv = TextView(this).apply {
                text = name; textSize = 14f; gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { hideKeyboard(); showTab(i) }
            }
            tabViews.add(tv)
            tabRow.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun openTextPanel(id: String, keyboard: Boolean, tab: Int = currentTab) {
        val t = project.texts.firstOrNull { it.id == id } ?: return
        player.pause(); stopAt = -1
        selText = id; textLayer.selectedId = id; timeline.selectedText = id
        val g = timeline.timeMs
        if (g < t.startMs || g >= t.endMs) jump(t.startMs + minOf(t.durMs / 2, 600L))
        editArea.visibility = View.GONE
        textPanel.visibility = View.VISIBLE
        bindPanel(t)
        showTab(tab)
        if (keyboard) {
            etText.requestFocus()
            etText.post {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(etText, InputMethodManager.SHOW_IMPLICIT)
            }
        } else hideKeyboard()
    }

    private fun bindPanel(t: TextItem) {
        editing = null
        etText.setText(t.text)
        etText.setSelection(etText.text.length)
        editing = t
        showTab(currentTab)
    }

    private fun closeTextPanel() {
        if (textPanel.visibility != View.VISIBLE) return
        hideKeyboard()
        val t = editing
        editing = null
        if (t != null && t.text.isBlank()) {
            project.texts.remove(t); textLayer.forget(t.id)
            selText = null; textLayer.selectedId = null
        }
        textPanel.visibility = View.GONE
        editArea.visibility = View.VISIBLE
        save()
        timeline.invalidate(); textLayer.invalidate()
        updateToolbar()
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(etText.windowToken, 0)
        etText.clearFocus()
    }

    private fun styleChanged() {
        textLayer.invalidate()
        timeline.invalidate()
        saveSoon()
    }

    private fun showTab(i: Int) {
        currentTab = i
        tabViews.forEachIndexed { k, tv ->
            tv.setTextColor(if (k == i) 0xFF22D3EE.toInt() else 0xFF9A9AA5.toInt())
            tv.setTypeface(null, if (k == i) Typeface.BOLD else Typeface.NORMAL)
        }
        tabContent.removeAllViews()
        val t = editing ?: return
        when (i) {
            0 -> tabStyles(t)
            1 -> tabFonts(t)
            2 -> {
                swatches(TextPresets.COLORS, t.color) { t.color = it; styleChanged() }
                slider("Opacidade", t.opacity, 0.1f, 1f) { t.opacity = it; styleChanged() }
            }
            3 -> {
                slider("Espessura", t.strokeW, 0f, 1f) { t.strokeW = it; styleChanged() }
                swatches(TextPresets.COLORS, t.strokeColor) {
                    t.strokeColor = it; if (t.strokeW < 0.05f) t.strokeW = 0.5f; styleChanged(); showTab(3)
                }
            }
            4 -> {
                slider("Intensidade", t.glow, 0f, 1f) { t.glow = it; styleChanged() }
                swatches(TextPresets.COLORS, t.glowColor) {
                    t.glowColor = it; if (t.glow < 0.05f) t.glow = 0.75f; styleChanged(); showTab(4)
                }
            }
            5 -> {
                swatches(TextPresets.COLORS, t.bgColor) {
                    t.bgColor = it; if (t.bgAlpha < 0.05f) t.bgAlpha = 0.75f; styleChanged(); showTab(5)
                }
                slider("Opacidade", t.bgAlpha, 0f, 1f) { t.bgAlpha = it; styleChanged() }
                slider("Arredondado", t.bgRound, 0f, 1f) { t.bgRound = it; styleChanged() }
            }
            6 -> {
                slider("Opacidade", t.shadow, 0f, 1f) { t.shadow = it; styleChanged() }
                slider("Desfoque", t.shadowBlur, 0f, 1f) { t.shadowBlur = it; styleChanged() }
            }
            7 -> {
                label("Entrada")
                chips(TextAnim.IN_NAMES, t.animIn) { t.animIn = it; styleChanged(); playRange(t.startMs, minOf(t.endMs, t.startMs + 1300)) }
                label("Saída")
                chips(TextAnim.OUT_NAMES, t.animOut) { t.animOut = it; styleChanged(); playRange(maxOf(t.startMs, t.endMs - 1300), t.endMs) }
                label("Loop (durante o texto)")
                chips(TextAnim.LOOP_NAMES, t.animLoop) { t.animLoop = it; styleChanged(); playRange(t.startMs, minOf(t.endMs, t.startMs + 2500)) }
            }
            8 -> tabAdjust(t)
        }
    }

    private fun tabStyles(t: TextItem) {
        var row: LinearLayout? = null
        TextPresets.ALL.forEachIndexed { i, p ->
            if (i % 4 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                tabContent.addView(row)
            }
            val sample = TextItem("s", "Aa", 0, 1000).also { it.copyStyleFrom(t); it.scale = 1f; it.rot = 0f; p.apply(it) }
            val bmp: Bitmap = TextRenderer.render(sample, dp(40))
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(3), dp(3), dp(3), dp(6))
                setOnClickListener { p.apply(t); styleChanged() }
            }
            cell.addView(FrameLayout(this).apply {
                background = GradientDrawable().apply { setColor(0xFF2A2A30.toInt()); cornerRadius = dp(8).toFloat() }
                addView(ImageView(context).apply {
                    setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
            cell.addView(TextView(this).apply {
                text = p.name; textSize = 11f; gravity = Gravity.CENTER
                setTextColor(0xFFB8B8C2.toInt()); setPadding(0, dp(3), 0, 0)
            })
            row!!.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val rest = TextPresets.ALL.size % 4
        if (rest != 0) repeat(4 - rest) { row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
    }

    private fun tabFonts(t: TextItem) {
        var row: LinearLayout? = null
        TextFonts.NAMES.forEachIndexed { i, name ->
            if (i % 3 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                tabContent.addView(row)
            }
            val sel = i == t.font
            val cell = TextView(this).apply {
                text = "Abc\n$name"; gravity = Gravity.CENTER; textSize = 14f
                typeface = TextFonts.get(i)
                setTextColor(if (sel) 0xFF22D3EE.toInt() else Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(0xFF2A2A30.toInt()); cornerRadius = dp(8).toFloat()
                    if (sel) setStroke(dp(2), 0xFF22D3EE.toInt())
                }
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener { t.font = i; styleChanged(); showTab(1) }
            }
            row!!.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        val rest = TextFonts.NAMES.size % 3
        if (rest != 0) repeat(3 - rest) { row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
    }

    private fun tabAdjust(t: TextItem) {
        slider("Tamanho", t.scale, 0.3f, 4f) { t.scale = it; styleChanged() }
        slider("Girar (graus)", t.rot.coerceIn(-180f, 180f), -180f, 180f) { t.rot = it; styleChanged() }
        label("Alinhamento")
        chips(arrayOf("Esquerda", "Centro", "Direita"), t.align) { t.align = it; styleChanged() }
        label("Posição rápida")
        chips(arrayOf("Topo", "Meio", "Embaixo", "Centralizar"), -1) {
            when (it) {
                0 -> { t.x = 0.5f; t.y = 0.12f }
                1 -> { t.x = 0.5f; t.y = 0.5f }
                2 -> { t.x = 0.5f; t.y = 0.86f }
                3 -> t.x = 0.5f
            }
            styleChanged()
        }
        label("Duração: ${Fmt.dur(t.durMs)}")
        chips(arrayOf("−0,5s", "+0,5s", "Até o fim da cena", "Cena inteira"), -1) {
            val (ci, _) = locate(t.startMs.coerceIn(0, maxOf(0L, total - 1)))
            when (it) {
                0 -> t.durMs = (t.durMs - 500).coerceAtLeast(MontageTimelineView.MIN_TEXT)
                1 -> t.durMs += 500
                2 -> t.durMs = (starts[ci + 1] - t.startMs).coerceAtLeast(MontageTimelineView.MIN_TEXT)
                3 -> { t.startMs = starts[ci]; t.durMs = starts[ci + 1] - starts[ci] }
            }
            styleChanged(); showTab(8)
        }
    }

    // ---------------- pecinhas do painel ----------------

    private fun label(s: String) {
        tabContent.addView(TextView(this).apply {
            text = s; setTextColor(0xFF9A9AA5.toInt()); textSize = 12f
            setPadding(dp(2), dp(8), 0, dp(2))
        })
    }

    private fun slider(name: String, value: Float, from: Float, to: Float, onChange: (Float) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply {
            text = name; setTextColor(Color.WHITE); textSize = 13f
        }, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(Slider(this).apply {
            valueFrom = from; valueTo = to
            this.value = value.coerceIn(from, to)
            addOnChangeListener { _, v, fromUser -> if (fromUser) onChange(v) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabContent.addView(row)
    }

    private fun swatches(colors: IntArray, selected: Int, onPick: (Int) -> Unit) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(6)) }
        colors.forEach { c ->
            line.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(c)
                    setStroke(dp(if (c == selected) 3 else 1), if (c == selected) 0xFF22D3EE.toInt() else 0xFF55555F.toInt())
                }
                setOnClickListener { onPick(c) }
            }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(8) })
        }
        tabContent.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(line) })
    }

    private fun chips(names: Array<String>, selected: Int, onPick: (Int) -> Unit) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
        val views = mutableListOf<TextView>()
        fun paint(sel: Int) {
            views.forEachIndexed { k, v ->
                v.setTextColor(if (k == sel) Color.BLACK else Color.WHITE)
                (v.background as GradientDrawable).setColor(if (k == sel) 0xFF22D3EE.toInt() else 0xFF2A2A30.toInt())
            }
        }
        names.forEachIndexed { i, n ->
            val v = TextView(this).apply {
                text = n; textSize = 13f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply { cornerRadius = dp(18).toFloat() }
                setOnClickListener { if (selected >= 0) paint(i); onPick(i) }
            }
            views.add(v)
            line.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
        paint(selected)
        tabContent.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(line) })
    }
}

/** Fundo com efeito de toque padrão do tema. */
object TypedValueHolder {
    fun selectable(ctx: Context): Int {
        val tv = android.util.TypedValue()
        return if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) tv.resourceId else 0
    }
}
