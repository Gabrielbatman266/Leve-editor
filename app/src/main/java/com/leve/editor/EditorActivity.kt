package com.leve.editor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class EditorActivity : AppCompatActivity(), TimelineView.Listener {

    private lateinit var project: Project
    private lateinit var uri: Uri
    private lateinit var player: ExoPlayer

    private lateinit var playerBox: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var tvOverlay: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvTitle: TextView
    private lateinit var timeline: TimelineView
    private lateinit var tvSel: TextView
    private lateinit var btnPlay: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancelEdit: MaterialButton
    private lateinit var panelCut: View
    private lateinit var panelScenes: View
    private lateinit var panelSeq: View
    private lateinit var tvScenesInfo: TextView
    private lateinit var tvScenesEmpty: View
    private lateinit var tvSeqInfo: TextView
    private lateinit var tvSeqEmpty: View
    private lateinit var btnPreviewSeq: MaterialButton
    private lateinit var nav: BottomNavigationView

    private lateinit var scenesAdapter: ScenesAdapter
    private lateinit var seqAdapter: SeqAdapter
    private lateinit var touchHelper: ItemTouchHelper

    private var thumbs: ThumbnailProvider? = null
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var inMs = -1L
    private var outMs = -1L
    private var editingSceneId: String? = null
    private var seqMode = false
    private var stopAtMs = -1L
    private var lastSeekAt = 0L
    private var fullPosBeforeSeq = 0L

    // prévia travada no corte (estilo CapCut)
    private var lockOn = true
    private var loopOn = false
    private var lastTickPos = 0L
    private lateinit var tvCutBadge: TextView
    private lateinit var cutProgress: LinearProgressIndicator
    private lateinit var lockBar: View
    private lateinit var btnLock: MaterialButton
    private lateinit var btnLoop: MaterialButton

    private fun hasCut() = inMs >= 0 && outMs > inMs
    private fun isLocked() = lockOn && hasCut() && !seqMode

    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { exportFlow.ask() }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = intent.getStringExtra("id")?.let { Store.load(this, it) }
        if (p == null) { finish(); return }
        project = p
        uri = Uri.parse(p.videoUri)
        setContentView(R.layout.activity_editor)
        bindViews()
        setupPlayer()
        setupCut()
        setupScenes()
        setupSeq()
        refreshAll()
    }

    private fun bindViews() {
        playerBox = findViewById(R.id.playerBox)
        playerView = findViewById(R.id.playerView)
        tvOverlay = findViewById(R.id.tvOverlay)
        tvTime = findViewById(R.id.tvTime)
        tvTitle = findViewById(R.id.tvTitle)
        timeline = findViewById(R.id.timeline)
        tvSel = findViewById(R.id.tvSel)
        btnPlay = findViewById(R.id.btnPlay)
        btnSave = findViewById(R.id.btnSave)
        btnCancelEdit = findViewById(R.id.btnCancelEdit)
        panelCut = findViewById(R.id.panelCut)
        panelScenes = findViewById(R.id.panelScenes)
        panelSeq = findViewById(R.id.panelSeq)
        tvScenesInfo = findViewById(R.id.tvScenesInfo)
        tvScenesEmpty = findViewById(R.id.tvScenesEmpty)
        tvSeqInfo = findViewById(R.id.tvSeqInfo)
        tvSeqEmpty = findViewById(R.id.tvSeqEmpty)
        btnPreviewSeq = findViewById(R.id.btnPreviewSeq)
        nav = findViewById(R.id.nav)
        tvCutBadge = findViewById(R.id.tvCutBadge)
        cutProgress = findViewById(R.id.cutProgress)
        lockBar = findViewById(R.id.lockBar)
        btnLock = findViewById(R.id.btnLock)
        btnLoop = findViewById(R.id.btnLoop)

        tvTitle.text = project.name
        tvTitle.setOnClickListener {
            Dialogs.input(this, "Nome do projeto", project.name) { n ->
                project.name = n; tvTitle.text = n; save()
            }
        }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        // player sempre em 16:9
        playerBox.post {
            playerBox.layoutParams = playerBox.layoutParams.apply { height = playerBox.width * 9 / 16 }
        }

        nav.setOnItemSelectedListener { item ->
            showTab(item.itemId); true
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        if (montageOpened) {
            // volta do editor de montagem: pega a ordem e os textos novos
            montageOpened = false
            Store.load(this, project.id)?.let { fresh ->
                project.seq.clear(); project.seq.addAll(fresh.seq)
                project.texts.clear(); project.texts.addAll(fresh.texts)
                project.name = fresh.name; tvTitle.text = fresh.name
            }
            refreshLists()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        if (!exportFlow.running) player.pause()
        if (!seqMode) project.lastPosMs = player.currentPosition
        save()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!::project.isInitialized) return
        handler.removeCallbacksAndMessages(null)
        exportFlow.shutdown()
        thumbs?.release()
        player.release()
        io.shutdown()
    }

    private fun save() = Store.save(this, project)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- player

    private fun setupPlayer() {
        // buffer pequeno: abre e pula mais rápido e gasta menos memória em celular fraco
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_000, 8_000, 300, 800)
            .build()
        player = ExoPlayer.Builder(this).setLoadControl(load).build()
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlay.text = if (isPlaying) "⏸" else "▶"
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !seqMode) {
                    val d = player.duration
                    if (d > 0 && (project.durationMs <= 0 || kotlin.math.abs(d - project.durationMs) > 1500)) {
                        project.durationMs = d
                        timeline.durationMs = d
                        thumbs?.prefetch(d)
                        save()
                    }
                }
                if (state == Player.STATE_ENDED && seqMode) stopSeqPreview()
            }

            override fun onPlayerError(error: PlaybackException) {
                MaterialAlertDialogBuilder(this@EditorActivity)
                    .setTitle("Não consegui tocar o vídeo")
                    .setMessage("O arquivo foi movido, apagado ou o formato não é suportado.\n\n(${error.errorCodeName})")
                    .setPositiveButton("OK", null)
                    .show()
            }
        })
        loadFull(project.lastPosMs)

        val tilePx = (56 * resources.displayMetrics.density).toInt()
        val tlDir = java.io.File(Store.thumbFile(this, project.id, "x").parentFile, "tl")
        thumbs = ThumbnailProvider(this, uri, tlDir, (tilePx * 16 / 9 * 0.8f).toInt(), (tilePx * 0.8f).toInt()) {
            timeline.invalidate()
        }.also { it.prefetch(project.durationMs) }
    }

    private fun loadFull(posMs: Long) {
        player.setSeekParameters(SeekParameters.EXACT)
        player.setMediaItem(MediaItem.fromUri(uri), posMs)
        player.prepare()
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 40)
        }
    }

    private fun tick() {
        val pos = player.currentPosition
        if (!seqMode) {
            if (stopAtMs >= 0 && pos >= stopAtMs) {
                player.pause(); stopAtMs = -1
            }
            if (isLocked()) {
                // chegou no Fim do corte tocando: repete ou para no fim
                if (player.isPlaying && lastTickPos < outMs && pos >= outMs) {
                    if (loopOn) { player.setSeekParameters(SeekParameters.EXACT); player.seekTo(inMs) }
                    else { player.pause(); player.seekTo(outMs) }
                }
                val shownPos = if (timeline.userActive) timeline.timeMs else pos
                val rel = (shownPos - inMs).coerceIn(0, outMs - inMs)
                tvCutBadge.text = "✂ PRÉVIA DO CORTE  ${Fmt.time(rel)} / ${Fmt.time(outMs - inMs)}"
                tvCutBadge.visibility = View.VISIBLE
                cutProgress.visibility = View.VISIBLE
                cutProgress.progress = (rel * 1000 / (outMs - inMs)).toInt()
            } else if (tvCutBadge.visibility == View.VISIBLE) {
                tvCutBadge.visibility = View.GONE
                cutProgress.visibility = View.GONE
            }
            lastTickPos = pos
            timeline.setTime(pos)
            val shown = if (timeline.userActive) timeline.timeMs else pos
            tvTime.text = "${Fmt.time(shown)} / ${Fmt.time(project.durationMs, false)}"
        } else {
            val idx = player.currentMediaItemIndex
            val item = project.seq.getOrNull(idx)
            updateOverlay(item, pos)
            tvTime.text = "Montagem ${idx + 1}/${project.seq.size} • ${Fmt.time(pos)}"
        }
    }

    private fun seekExact(ms: Long) {
        if (seqMode) stopSeqPreview()
        player.pause()
        stopAtMs = -1
        var t = ms.coerceIn(0, project.durationMs.coerceAtLeast(0))
        if (isLocked()) t = t.coerceIn(inMs, outMs)
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo(t)
        timeline.jumpTo(t)
    }

    // ---------------------------------------------------------------- linha do tempo

    override fun onScrub(ms: Long) {
        if (seqMode) stopSeqPreview()
        if (player.isPlaying) player.pause()
        stopAtMs = -1
        val now = SystemClock.uptimeMillis()
        val fine = timeline.visibleMs() in 1..20_000
        if (now - lastSeekAt > (if (fine) 110 else 60)) {
            // zoom alto = quadro exato; zoom baixo = keyframe (pula rápido em filme longo)
            player.setSeekParameters(if (fine) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
            player.seekTo(ms)
            lastSeekAt = now
        }
    }

    override fun onScrubEnd(ms: Long) {
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo(ms)
    }

    override fun onHandleDrag(ms: Long) = onScrub(ms)

    override fun onSelectionChanged(inMs: Long, outMs: Long) {
        this.inMs = inMs
        this.outMs = outMs
        updateSel()
    }

    // ---------------------------------------------------------------- aba Cortar

    private fun setupCut() {
        timeline.listener = this
        timeline.thumbs = thumbs
        val tvZoom = findViewById<TextView>(R.id.tvZoom)
        timeline.onZoomChanged = { ms -> tvZoom.text = "Tela = ${zoomLabel(ms)}" }
        findViewById<View>(R.id.btnZoomIn).setOnClickListener { timeline.zoomIn() }
        findViewById<View>(R.id.btnZoomOut).setOnClickListener { timeline.zoomOut() }
        findViewById<View>(R.id.btnZoomFit).setOnClickListener { timeline.zoomFit() }
        findViewById<View>(R.id.btnZoomFine).setOnClickListener { timeline.zoomToVisible(3_000) }
        timeline.durationMs = project.durationMs
        timeline.scenes = project.scenes
        timeline.jumpTo(project.lastPosMs)

        btnPlay.setOnClickListener {
            if (seqMode) { if (player.isPlaying) player.pause() else player.play(); return@setOnClickListener }
            if (player.isPlaying) player.pause() else {
                stopAtMs = -1
                val pos = player.currentPosition
                if (isLocked()) {
                    // toca só o corte: se está fora dele ou no fim, começa do Início
                    if (pos < inMs || pos >= outMs - 60) {
                        player.setSeekParameters(SeekParameters.EXACT)
                        player.seekTo(inMs)
                        timeline.jumpTo(inMs)
                    }
                } else if (pos >= project.durationMs - 100) player.seekTo(0)
                lastTickPos = player.currentPosition
                player.play()
            }
        }
        findViewById<View>(R.id.btnBack1).setOnClickListener { seekExact(player.currentPosition - 1000) }
        findViewById<View>(R.id.btnFwd1).setOnClickListener { seekExact(player.currentPosition + 1000) }
        findViewById<View>(R.id.btnBackF).setOnClickListener { seekExact(player.currentPosition - 100) }
        findViewById<View>(R.id.btnFwdF).setOnClickListener { seekExact(player.currentPosition + 100) }

        findViewById<View>(R.id.btnIn).setOnClickListener {
            if (seqMode) stopSeqPreview()
            inMs = player.currentPosition
            if (outMs in 0..inMs) outMs = -1
            if (outMs >= 0 && outMs - inMs < TimelineView.MIN_SCENE) outMs = -1
            pushSel()
        }
        findViewById<View>(R.id.btnOut).setOnClickListener {
            if (seqMode) stopSeqPreview()
            val pos = player.currentPosition
            if (inMs < 0) { toast("Marque o Início primeiro"); return@setOnClickListener }
            if (pos - inMs < TimelineView.MIN_SCENE) { toast("O Fim precisa ficar depois do Início"); return@setOnClickListener }
            outMs = pos
            player.pause()
            pushSel()
            if (lockOn) focusCut()
        }
        findViewById<View>(R.id.btnClear).setOnClickListener {
            inMs = -1; outMs = -1; pushSel()
        }
        btnSave.setOnClickListener { saveScene() }
        btnLock.setOnClickListener {
            lockOn = !lockOn
            pushSel()
            if (lockOn) focusCut()
        }
        btnLoop.setOnClickListener { loopOn = !loopOn; updateSel() }
        findViewById<View>(R.id.btnGoIn).setOnClickListener { if (hasCut()) seekExact(inMs) }
        findViewById<View>(R.id.btnGoOut).setOnClickListener { if (hasCut()) seekExact(outMs) }
        btnCancelEdit.setOnClickListener { cancelEdit() }
        updateSel()
    }

    private fun zoomLabel(ms: Long): String = when {
        ms < 10_000 -> String.format("%.1fs", ms / 1000f)
        ms < 120_000 -> "${ms / 1000}s"
        ms < 7_200_000 -> "${ms / 60_000} min"
        else -> String.format("%.1f h", ms / 3_600_000f)
    }

    private fun pushSel() {
        timeline.setSelection(inMs, outMs)
        updateSel()
    }

    /** Trava a prévia no corte: zoom no trecho e cursor no Início. */
    private fun focusCut() {
        if (!hasCut()) return
        seekExact(inMs)
        timeline.focusSelection()
    }

    private fun updateSel() {
        tvSel.text = when {
            inMs < 0 -> "Toque em  [ Início  e depois em  Fim ]  para marcar a cena"
            outMs <= inMs -> "Início ${Fmt.time(inMs)}  •  agora vá até o fim e toque em Fim ]"
            else -> "${Fmt.time(inMs)}  →  ${Fmt.time(outMs)}   (${Fmt.dur(outMs - inMs)})"
        }
        val ok = inMs >= 0 && outMs > inMs
        btnSave.isEnabled = ok
        btnSave.alpha = if (ok) 1f else 0.5f
        btnSave.text = if (editingSceneId != null) "✓  Atualizar cena" else "✂  Salvar cena"
        btnCancelEdit.visibility = if (editingSceneId != null) View.VISIBLE else View.GONE
        timeline.lockRange = lockOn
        lockBar.visibility = if (ok) View.VISIBLE else View.GONE
        btnLock.text = if (lockOn) "🔒 Travado no corte" else "🔓 Livre"
        btnLoop.text = if (loopOn) "🔁 Repetindo" else "🔁"
        btnLoop.alpha = if (loopOn) 1f else 0.6f
        if (ok && lockOn) tvSel.text = "${tvSel.text}\n▶ toca só o corte • arraste as bordas para ajustar"
    }

    private fun saveScene() {
        if (inMs < 0 || outMs <= inMs) return
        player.pause()
        val editing = editingSceneId?.let { project.scene(it) }
        if (editing != null) {
            editing.startMs = inMs
            editing.endMs = outMs
            makeThumb(editing)
            editingSceneId = null
            inMs = -1; outMs = -1
            afterScenesChanged()
            pushSel()
            toast("Cena \"${editing.name}\" atualizada")
            return
        }
        val color = project.scenes.lastOrNull()?.color ?: SCENE_COLORS[0]
        Dialogs.scene(this, "Salvar cena", "Cena ${project.scenes.size + 1}", color, "Salvar") { name, c ->
            val s = Scene(newId(), name, inMs, outMs, c)
            project.scenes.add(s)
            makeThumb(s)
            // a próxima cena já começa onde essa terminou
            inMs = outMs
            outMs = -1
            afterScenesChanged()
            pushSel()
            toast("Cena salva: $name")
        }
    }

    private fun startEdit(s: Scene) {
        editingSceneId = s.id
        inMs = s.startMs
        outMs = s.endMs
        nav.selectedItemId = R.id.tab_cut
        pushSel()
        if (lockOn) focusCut() else seekExact(s.startMs)
        toast("Ajuste as bordas e toque em Atualizar cena")
    }

    private fun cancelEdit() {
        editingSceneId = null
        inMs = -1; outMs = -1
        pushSel()
    }

    private fun makeThumb(s: Scene) {
        val ms = s.startMs + minOf(400L, s.durMs / 2)
        val f = Store.thumbFile(this, project.id, s.id)
        io.execute {
            if (Frames.saveSceneThumb(this, uri, ms, f)) runOnUiThread { refreshLists() }
        }
    }

    // ---------------------------------------------------------------- aba Cenas

    private fun setupScenes() {
        scenesAdapter = ScenesAdapter(project,
            onTap = { s -> previewScene(s) },
            onAdd = { s -> addToSeq(listOf(s)) },
            onMore = { s, v -> sceneMenu(s, v) }
        )
        findViewById<RecyclerView>(R.id.rvScenes).apply {
            layoutManager = LinearLayoutManager(this@EditorActivity)
            adapter = scenesAdapter
        }
        findViewById<View>(R.id.btnAddAll).setOnClickListener {
            if (project.scenes.isEmpty()) toast("Ainda não tem cenas")
            else addToSeq(project.sortedScenes())
        }
    }

    private fun previewScene(s: Scene) {
        if (seqMode) stopSeqPreview()
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo(s.startMs)
        timeline.jumpTo(s.startMs)
        stopAtMs = s.endMs
        player.play()
    }

    private fun addToSeq(list: List<Scene>) {
        if (seqMode) stopSeqPreview()
        list.forEach { project.seq.add(SeqItem(newId(), it.id)) }
        save()
        refreshLists()
        toast(if (list.size == 1) "\"${list[0].name}\" foi para a Montagem (${project.seq.size})" else "${list.size} cenas foram para a Montagem")
    }

    private fun sceneMenu(s: Scene, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Renomear / cor")
            menu.add(0, 2, 1, "Ajustar corte")
            menu.add(0, 3, 2, "Duplicar")
            menu.add(0, 4, 3, "Apagar cena")
            setOnMenuItemClickListener { mi ->
                when (mi.itemId) {
                    1 -> Dialogs.scene(this@EditorActivity, "Editar cena", s.name, s.color, "Salvar") { n, c ->
                        s.name = n; s.color = c; afterScenesChanged()
                    }
                    2 -> startEdit(s)
                    3 -> {
                        val copy = Scene(newId(), s.name + " (cópia)", s.startMs, s.endMs, s.color)
                        project.scenes.add(copy); makeThumb(copy); afterScenesChanged()
                    }
                    4 -> Dialogs.confirm(this@EditorActivity, "Apagar \"${s.name}\"?",
                        "Ela também sai da Montagem.", "Apagar") {
                        if (seqMode) stopSeqPreview()
                        project.scenes.remove(s)
                        project.seq.removeAll { it.sceneId == s.id }
                        Store.thumbFile(this@EditorActivity, project.id, s.id).delete()
                        if (editingSceneId == s.id) cancelEdit()
                        afterScenesChanged()
                    }
                }
                true
            }
            show()
        }
    }

    // ---------------------------------------------------------------- aba Montagem

    private fun setupSeq() {
        val rv = findViewById<RecyclerView>(R.id.rvSeq)
        seqAdapter = SeqAdapter(project,
            onTap = { i -> openMontage(i) },
            onDelete = { i ->
                if (seqMode) stopSeqPreview()
                project.seq.removeAt(i)
                save(); refreshLists()
            },
            onStartDrag = { vh -> touchHelper.startDrag(vh) }
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = seqAdapter
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(r: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder): Boolean {
                if (seqMode) stopSeqPreview()
                val from = a.bindingAdapterPosition
                val to = b.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                val item = project.seq.removeAt(from)
                project.seq.add(to, item)
                seqAdapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(r: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(r, vh)
                save()
                r.post { refreshLists() }
            }
        })
        touchHelper.attachToRecyclerView(rv)

        btnPreviewSeq.setOnClickListener { openMontage() }
        findViewById<View>(R.id.btnExport).setOnClickListener { exportClicked() }
        findViewById<View>(R.id.btnAutoText).setOnClickListener { autoTitles() }
    }

    private fun autoTitles() {
        if (project.seq.isEmpty()) { toast("A montagem está vazia"); return }
        val opts = arrayOf("Cena 1 – Nome da cena", "Só o nome da cena", "Só o número (Cena 1)", "Tirar todos os textos")
        MaterialAlertDialogBuilder(this)
            .setTitle("Títulos automáticos (no começo de cada cena)")
            .setItems(opts) { _, which ->
                project.texts.clear()
                if (which != 3) {
                    val st = project.clipStarts()
                    project.seq.forEachIndexed { i, it ->
                        val name = project.scene(it.sceneId)?.name ?: ""
                        val txt = when (which) { 0 -> "Cena ${i + 1} – $name"; 1 -> name; else -> "Cena ${i + 1}" }
                        val len = st[i + 1] - st[i]
                        val t = TextItem(newId(), txt, st[i], minOf(3000L, len).coerceAtLeast(300L))
                        t.y = 0.86f
                        TextPresets.ALL[7].apply(t)   // "Legenda"
                        t.animIn = 1; t.animOut = 1
                        project.texts.add(t)
                    }
                }
                save(); refreshLists()
                toast(if (which == 3) "Textos removidos" else "Títulos criados — abra o editor de montagem para ajustar")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startSeqPreview() {
        if (project.seq.isEmpty()) { toast("Coloque cenas na Montagem primeiro"); return }
        fullPosBeforeSeq = player.currentPosition
        seqMode = true
        stopAtMs = -1
        val items = project.seq.map { s ->
            val sc = project.scene(s.sceneId)!!
            MediaItem.Builder().setUri(uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(sc.startMs)
                        .setEndPositionMs(sc.endMs)
                        .build()
                ).build()
        }
        player.setSeekParameters(SeekParameters.EXACT)
        player.setMediaItems(items)
        player.prepare()
        player.play()
        btnPreviewSeq.text = "■  Parar"
    }

    private fun stopSeqPreview() {
        if (!seqMode) return
        seqMode = false
        tvOverlay.visibility = View.GONE
        btnPreviewSeq.text = "▶  Pré-visualizar"
        loadFull(fullPosBeforeSeq)
        timeline.jumpTo(fullPosBeforeSeq)
    }

    /** Mostra o texto por cima do player durante a pré-visualização (igual ao exportado). */
    private fun updateOverlay(item: SeqItem?, posMs: Long) {
        if (item == null || item.text.isBlank() || (item.secs > 0 && posMs >= item.secs * 1000L)) {
            tvOverlay.visibility = View.GONE
            return
        }
        val h = playerBox.height.toFloat()
        tvOverlay.text = item.text
        tvOverlay.setTextColor(item.color)
        tvOverlay.setTextSize(TypedValue.COMPLEX_UNIT_PX, h * TextStyle.sizeFactor(item.size))
        tvOverlay.setBackgroundColor(if (item.bg) TextStyle.BG_COLOR else 0)
        val lp = tvOverlay.layoutParams as FrameLayout.LayoutParams
        val g = Gravity.CENTER_HORIZONTAL or when (item.pos) {
            TextStyle.POS_TOP -> Gravity.TOP
            TextStyle.POS_CENTER -> Gravity.CENTER_VERTICAL
            else -> Gravity.BOTTOM
        }
        val margin = (h * 0.06f).toInt()
        if (lp.gravity != g || lp.topMargin != margin) {
            lp.gravity = g
            lp.topMargin = margin
            lp.bottomMargin = margin
            tvOverlay.layoutParams = lp
        }
        tvOverlay.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- exportar

    private val exportFlow by lazy {
        ExportFlow(this, project, uri) { if (seqMode) stopSeqPreview(); player.pause() }
    }

    private fun exportClicked() {
        if (project.seq.isEmpty()) { toast("A montagem está vazia"); return }
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else exportFlow.ask()
    }

    // ---------------------------------------------------------------- editor de montagem

    private var montageOpened = false

    private fun openMontage(clip: Int = -1) {
        if (seqMode) stopSeqPreview()
        player.pause()
        save()
        montageOpened = true
        startActivity(Intent(this, MontageActivity::class.java)
            .putExtra("id", project.id).putExtra("clip", clip))
    }

    // ---------------------------------------------------------------- abas / atualização

    private fun showTab(id: Int) {
        panelCut.visibility = if (id == R.id.tab_cut) View.VISIBLE else View.GONE
        panelScenes.visibility = if (id == R.id.tab_scenes) View.VISIBLE else View.GONE
        panelSeq.visibility = if (id == R.id.tab_seq) View.VISIBLE else View.GONE
        if (id != R.id.tab_seq && seqMode) stopSeqPreview()
    }

    private fun afterScenesChanged() {
        save()
        timeline.scenes = project.scenes.toList()
        refreshLists()
    }

    private fun refreshAll() {
        timeline.scenes = project.scenes.toList()
        refreshLists()
    }

    private fun refreshLists() {
        scenesAdapter.refresh()
        seqAdapter.notifyDataSetChanged()
        val n = project.scenes.size
        tvScenesInfo.text = if (n == 0) "Sem cenas" else "$n cenas • ${Fmt.time(project.scenes.sumOf { it.durMs }, false)} no total"
        tvScenesEmpty.visibility = if (n == 0) View.VISIBLE else View.GONE
        val m = project.seq.size
        tvSeqInfo.text = if (m == 0) "Montagem vazia" else "$m cenas • vídeo final ${Fmt.time(project.seqDurationMs(), false)}"
        tvSeqEmpty.visibility = if (m == 0) View.VISIBLE else View.GONE
    }
}
