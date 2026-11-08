package com.leve.editor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Linha do tempo da montagem (estilo CapCut):
 *  - faixa de vídeo: as cenas uma atrás da outra, largura = duração, com miniaturas;
 *  - faixa de texto: barras roxas que você arrasta (mover) e estica pelas bordas (duração);
 *  - toque longo numa cena = arrastar para mudar a ordem;
 *  - cursor fixo no meio, pinça = zoom, encaixe (snap) no cursor e nas bordas das cenas.
 */
class MontageTimelineView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    interface Listener {
        fun onScrub(g: Long)
        fun onScrubEnd(g: Long)
        fun onSelectClip(i: Int)
        fun onSelectText(id: String)
        fun onDeselect()
        fun onTextTimingChanged(t: TextItem, done: Boolean)
        fun onReorder(from: Int, to: Int)
    }

    var listener: Listener? = null
    var project: Project? = null
    var thumbs: ThumbnailProvider? = null

    var selectedClip = -1
        set(v) { field = v; invalidate() }
    var selectedText: String? = null
        set(v) { field = v; invalidate() }

    var timeMs = 0L
        private set
    var userActive = false
        private set

    private var starts = LongArray(1)
    private val total: Long get() = starts.last()

    private val d = resources.displayMetrics.density
    private var pxPerMs = 0f

    private val rulerBottom = 16 * d
    private val vTop = 22 * d
    private val vH = 54 * d
    private val vBottom = vTop + vH
    private val tTop = vBottom + 10 * d
    private val laneH = 26 * d
    private val laneGap = 4 * d
    private val tileW = 54 * d
    private val edgeW = 12 * d

    private val pEmpty = Paint().apply { color = 0xFF1C1C20.toInt() }
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pTick = Paint().apply { color = 0xFF55555F.toInt(); strokeWidth = d }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9A9AA5.toInt(); textSize = 10 * d }
    private val pLabelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val pLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 10.5f * d; isFakeBoldText = true }
    private val pSel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val pGap = Paint().apply { color = Color.BLACK }
    private val pHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2 * d }
    private val pTextBar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6D28D9.toInt() }
    private val pTextBarSel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8B5CF6.toInt() }
    private val pTextLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 11 * d }
    private val pHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pGrip = Paint().apply { color = 0xFF6D28D9.toInt(); strokeWidth = 2 * d }
    private val pAfter = Paint().apply { color = 0x66000000 }
    private val pInsert = Paint().apply { color = 0xFF22D3EE.toInt(); strokeWidth = 3 * d }
    private val pGhost = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 220 }
    private val pSnap = Paint().apply { color = 0xFFFACC15.toInt(); strokeWidth = 1.5f * d }

    private val rect = RectF()
    private val src = Rect()

    /** Chamar depois de mudar a ordem/cenas/textos. */
    fun refresh() {
        starts = project?.clipStarts() ?: LongArray(1)
        clampZoom()
        timeMs = timeMs.coerceIn(0, max(0, total))
        invalidate()
    }

    fun setTime(g: Long) {
        if (userActive) return
        timeMs = g.coerceIn(0, max(0, total))
        invalidate()
    }

    fun jumpTo(g: Long) {
        scroller.forceFinished(true); flinging = false; userActive = false
        setTime(g)
    }

    private fun x(g: Long): Float = width / 2f + (g - timeMs) * pxPerMs
    private fun gAt(x: Float): Long = (timeMs + (x - width / 2f) / pxPerMs).toLong()

    private fun minZoom() = if (total > 0 && width > 0) width * 0.45f / max(total, 1000L) else 0.0001f
    private fun maxZoom() = if (width > 0) width / 1200f else 1f
    private fun clampZoom() {
        if (width == 0) return
        if (pxPerMs <= 0f) pxPerMs = width / 30_000f
        val lo = minZoom()
        pxPerMs = pxPerMs.coerceIn(lo, max(lo, maxZoom()))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh); clampZoom()
    }

    private var zoomAnim: ValueAnimator? = null
    private fun animateZoom(target: Float) {
        zoomAnim?.cancel()
        val from = pxPerMs
        if (from <= 0) return
        val lo = minZoom()
        val t = target.coerceIn(lo, max(lo, maxZoom()))
        zoomAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220; interpolator = DecelerateInterpolator()
            addUpdateListener {
                pxPerMs = (from * (t / from).toDouble().pow((it.animatedValue as Float).toDouble())).toFloat()
                invalidate()
            }
            start()
        }
    }
    fun zoomIn() = animateZoom(pxPerMs * 2.2f)
    fun zoomOut() = animateZoom(pxPerMs / 2.2f)

    // ---------------- faixas de texto ----------------

    private val laneOf = HashMap<String, Int>()
    private var laneCount = 1

    private fun computeLanes(texts: List<TextItem>) {
        laneOf.clear()
        val ends = ArrayList<Long>()
        for (t in texts.sortedBy { it.startMs }) {
            var lane = ends.indexOfFirst { it <= t.startMs }
            if (lane < 0) { ends.add(t.endMs); lane = ends.size - 1 } else ends[lane] = t.endMs
            laneOf[t.id] = lane
        }
        laneCount = max(1, ends.size)
    }

    private fun laneTop(lane: Int) = tTop + lane * (laneH + laneGap)

    private fun textRect(t: TextItem, out: RectF): RectF {
        val lane = laneOf[t.id] ?: 0
        out.set(x(t.startMs), laneTop(lane), x(t.endMs), laneTop(lane) + laneH)
        return out
    }

    // ---------------- desenho ----------------

    override fun onDraw(c: Canvas) {
        val p = project ?: return
        if (width == 0) return
        val w = width.toFloat()
        computeLanes(p.texts)

        // régua
        val visStart = max(0L, gAt(0f))
        val visEnd = gAt(w) + 1
        val labelStep = LABEL_STEPS.firstOrNull { it * pxPerMs >= 64 * d } ?: LABEL_STEPS.last()
        var t = (visStart / labelStep) * labelStep
        while (t <= visEnd) {
            val xx = x(t)
            c.drawLine(xx, rulerBottom - 5 * d, xx, rulerBottom, pTick)
            c.drawText(Fmt.time(t, labelStep < 1000), xx + 3 * d, rulerBottom - 4 * d, pText)
            t += labelStep
        }

        // faixa de vídeo
        val msPerTile = tileW / pxPerMs
        val step = TILE_STEPS.firstOrNull { it >= msPerTile } ?: TILE_STEPS.last()
        for (i in p.seq.indices) {
            if (i == dragClip) continue
            val l = x(starts[i]); val r = x(starts[i + 1])
            if (r < 0 || l > w) continue
            val sc = p.scene(p.seq[i].sceneId) ?: continue
            drawClip(c, sc, starts[i], l, r, step)
            if (i == selectedClip) {
                rect.set(l + d, vTop, r - d, vBottom)
                c.drawRoundRect(rect, 6 * d, 6 * d, pSel)
            }
        }
        // depois do fim
        val endX = x(total)
        if (endX < w) c.drawRect(endX, vTop, w, vBottom, pAfter)

        // faixa de texto
        val sel = selectedText
        for (tx in p.texts) {
            textRect(tx, rect)
            if (rect.right < 0 || rect.left > w) continue
            val isSel = tx.id == sel
            c.drawRoundRect(rect, 6 * d, 6 * d, if (isSel) pTextBarSel else pTextBar)
            c.save()
            c.clipRect(rect.left + 4 * d, rect.top, rect.right - 4 * d, rect.bottom)
            val label = "T  " + tx.text.replace('\n', ' ')
            c.drawText(label, max(rect.left, 0f) + (if (isSel) edgeW else 0f) + 6 * d,
                rect.centerY() + pTextLabel.textSize * 0.36f, pTextLabel)
            c.restore()
            if (isSel) {
                c.drawRoundRect(rect, 6 * d, 6 * d, pSel)
                drawEdge(c, rect.left, rect.top, rect.left + edgeW, rect.bottom)
                drawEdge(c, rect.right - edgeW, rect.top, rect.right, rect.bottom)
            }
        }

        // marca de encaixe
        if (snapLine >= 0) {
            val sx = x(snapLine)
            c.drawLine(sx, vTop - 4 * d, sx, height.toFloat(), pSnap)
        }

        // cena sendo arrastada (reordenar)
        if (dragClip >= 0 && dragClip < p.seq.size) {
            val target = reorderTarget()
            val ix = x(starts[target.coerceIn(0, p.seq.size)])
            c.drawLine(ix, vTop - 4 * d, ix, vBottom + 4 * d, pInsert)
            val sc = p.scene(p.seq[dragClip].sceneId)
            val size = vH * 1.1f
            rect.set(dragX - size / 2, vTop - 6 * d, dragX + size / 2, vTop - 6 * d + size)
            val b = sc?.let { ThumbCache.get(Store.thumbFile(context, p.id, it.id)) }
            if (b != null) drawCrop(c, b, rect, pGhost) else c.drawRect(rect, pEmpty)
            c.drawRoundRect(rect, 6 * d, 6 * d, pSel)
        }

        // cursor
        val cx = w / 2f
        c.drawLine(cx, 0f, cx, height.toFloat(), pHead)
        c.drawCircle(cx, 3 * d, 4 * d, pHead)
    }

    private fun drawClip(c: Canvas, sc: Scene, gStart: Long, l: Float, r: Float, step: Long) {
        c.save()
        rect.set(l + d, vTop, r - d, vBottom)
        c.clipRect(rect)
        val provider = thumbs
        val visL = max(l, 0f); val visR = min(r, width.toFloat())
        var g = gStart + ((gAt(visL) - gStart).coerceAtLeast(0) / step) * step
        while (x(g) < visR) {
            val tl = x(g); val tr = x(g + step)
            val srcMs = sc.startMs + (g - gStart)
            val key = (srcMs / step) * step
            rect.set(tl, vTop, tr, vBottom)
            val b = provider?.get(key, step <= 2000)
            if (b != null && !b.isRecycled) drawCrop(c, b, rect, pBmp) else c.drawRect(rect, pEmpty)
            g += step
        }
        // nome + duração
        val label = "${sc.name}  ${Fmt.dur(sc.durMs)}"
        val tw = pLabel.measureText(label)
        val lx = max(l + 4 * d, 4 * d).coerceAtMost(r - tw - 10 * d)
        rect.set(lx - 3 * d, vTop + 3 * d, lx + tw + 4 * d, vTop + 17 * d)
        c.drawRoundRect(rect, 4 * d, 4 * d, pLabelBg)
        c.drawText(label, lx, vTop + 14 * d, pLabel)
        c.restore()
        // separador entre cenas
        c.drawRect(r - d, vTop, r + d, vBottom, pGap)
    }

    private fun drawEdge(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val rr = RectF(l, t, r, b)
        c.drawRoundRect(rr, 4 * d, 4 * d, pHandle)
        val mid = (l + r) / 2
        c.drawLine(mid, t + (b - t) * 0.3f, mid, t + (b - t) * 0.7f, pGrip)
    }

    private fun drawCrop(c: Canvas, b: Bitmap, dst: RectF, p: Paint) {
        val dw = dst.width(); val dh = dst.height()
        if (dw <= 0 || dh <= 0) return
        val scale = max(dw / b.width, dh / b.height)
        val sw = (dw / scale).toInt().coerceIn(1, b.width)
        val sh = (dh / scale).toInt().coerceIn(1, b.height)
        val sx = (b.width - sw) / 2; val sy = (b.height - sh) / 2
        src.set(sx, sy, sx + sw, sy + sh)
        c.drawBitmap(b, src, dst, p)
    }

    // ---------------- toque ----------------

    private val scroller = OverScroller(ctx)
    private var flinging = false
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var mode = M_NONE
    private var pending = M_NONE
    private var downX = 0f; private var downY = 0f
    private var anchorX = 0f; private var anchorTime = 0L
    private var lastX = 0f
    private var reanchor = false
    private var scaled = false
    private var dragText: TextItem? = null
    private var grabMs = 0L
    private var origStart = 0L; private var origEnd = 0L
    private var dragClip = -1
    private var dragX = 0f
    private var snapLine = -1L

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(det: ScaleGestureDetector): Boolean { zoomAnim?.cancel(); return true }
        override fun onScale(det: ScaleGestureDetector): Boolean {
            pxPerMs *= det.scaleFactor.toDouble().pow(1.6).toFloat()
            clampZoom(); invalidate(); return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (mode != M_NONE && mode != M_SCROLL) return false
            tap(e.x, e.y); return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (mode != M_NONE || scaled) return
            val i = clipAt(e.x, e.y)
            if (i >= 0) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                dragClip = i; dragX = e.x; mode = M_REORDER; userActive = true
                invalidate()
            }
        }
    })

    private fun clipAt(ex: Float, ey: Float): Int {
        if (ey < vTop || ey > vBottom) return -1
        val g = gAt(ex)
        if (g < 0 || g >= total) return -1
        for (i in 0 until starts.size - 1) if (g >= starts[i] && g < starts[i + 1]) return i
        return -1
    }

    private fun textAt(ex: Float, ey: Float): TextItem? {
        val p = project ?: return null
        // o selecionado tem prioridade
        val all = p.texts.sortedBy { if (it.id == selectedText) 0 else 1 }
        for (t in all) {
            textRect(t, rect)
            rect.inset(-2 * d, -4 * d)
            if (rect.contains(ex, ey)) return t
        }
        return null
    }

    private fun tap(ex: Float, ey: Float) {
        val t = textAt(ex, ey)
        when {
            t != null -> { selectedText = t.id; selectedClip = -1; listener?.onSelectText(t.id) }
            clipAt(ex, ey) >= 0 -> {
                val i = clipAt(ex, ey)
                selectedClip = i; selectedText = null; listener?.onSelectClip(i)
            }
            else -> { selectedClip = -1; selectedText = null; listener?.onDeselect() }
        }
    }

    private fun reorderTarget(): Int {
        val p = project ?: return 0
        val g = gAt(dragX)
        if (g <= 0) return 0
        for (i in p.seq.indices) {
            val mid = (starts[i] + starts[i + 1]) / 2
            if (g < mid) return i
        }
        return p.seq.size
    }

    /** Encaixa [g] no cursor, nas bordas das cenas e de outros textos. */
    private fun snap(g: Long, self: TextItem): Long {
        val th = (10 * d / pxPerMs).toLong()
        var best = g; var bestD = th + 1
        fun test(v: Long) { val dd = abs(v - g); if (dd < bestD) { bestD = dd; best = v } }
        test(timeMs)
        for (s in starts) test(s)
        project?.texts?.forEach { if (it !== self) { test(it.startMs); test(it.endMs) } }
        val snapped = if (bestD <= th) best else g
        val newLine = if (bestD <= th) best else -1L
        if (newLine != snapLine && newLine >= 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        snapLine = newLine
        return snapped
    }

    private fun applyDrag(fx: Float) {
        val t = dragText ?: return
        val g = gAt(fx)
        when (mode) {
            M_T_MOVE -> {
                val len = origEnd - origStart
                var s = g - grabMs
                // encaixa o início ou o fim, o que estiver mais perto
                val s1 = snap(s, t)
                if (s1 != s) s = s1 else {
                    val e1 = snap(s + len, t); if (e1 != s + len) s = e1 - len
                }
                t.startMs = s.coerceAtLeast(0)
                t.durMs = len
            }
            M_T_START -> {
                val s = snap(g, t).coerceIn(0, origEnd - MIN_TEXT)
                t.startMs = s; t.durMs = origEnd - s
            }
            M_T_END -> {
                val e = snap(g, t).coerceAtLeast(t.startMs + MIN_TEXT)
                t.durMs = e - t.startMs
            }
        }
        listener?.onTextTimingChanged(t, false)
        invalidate()
    }

    private val autoScroll = object : Runnable {
        override fun run() {
            if (mode != M_T_MOVE && mode != M_T_START && mode != M_T_END && mode != M_REORDER) return
            val edge = 48 * d
            val speed = when {
                lastX < edge -> -(edge - lastX) / edge
                lastX > width - edge -> (lastX - (width - edge)) / edge
                else -> 0f
            }
            if (speed != 0f) {
                timeMs = (timeMs + speed * 18 * d / pxPerMs).toLong().coerceIn(0, max(total, 0))
                if (mode == M_REORDER) invalidate() else applyDrag(lastX)
                listener?.onScrub(timeMs)
            }
            postOnAnimation(this)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (project == null) return false
        if (mode != M_REORDER && mode != M_T_MOVE && mode != M_T_START && mode != M_T_END) scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scroller.forceFinished(true); flinging = false
                userActive = true; scaled = false
                vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(e) }
                downX = e.x; downY = e.y; lastX = e.x
                anchorX = e.x; anchorTime = timeMs
                mode = M_NONE
                pending = M_SCROLL
                dragText = null
                val selT = project?.texts?.firstOrNull { it.id == selectedText }
                if (selT != null) {
                    textRect(selT, rect)
                    if (e.y >= rect.top - 6 * d && e.y <= rect.bottom + 6 * d) {
                        pending = when {
                            abs(e.x - rect.left) <= edgeW + 6 * d -> M_T_START
                            abs(e.x - rect.right) <= edgeW + 6 * d -> M_T_END
                            e.x > rect.left && e.x < rect.right -> M_T_MOVE
                            else -> M_SCROLL
                        }
                        if (pending != M_SCROLL) {
                            dragText = selT
                            origStart = selT.startMs; origEnd = selT.endMs
                            grabMs = gAt(e.x) - selT.startMs
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> { scaled = true; reanchor = true; if (mode == M_NONE) mode = M_SCROLL }
            MotionEvent.ACTION_POINTER_UP -> reanchor = true
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                lastX = e.x
                if (mode == M_REORDER) { dragX = e.x; invalidate(); return true }
                if (mode == M_NONE) {
                    if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) {
                        mode = pending
                        if (mode == M_T_MOVE || mode == M_T_START || mode == M_T_END) postOnAnimation(autoScroll)
                    } else return true
                }
                when (mode) {
                    M_SCROLL -> {
                        if (e.pointerCount > 1 || reanchor) {
                            anchorX = e.x; anchorTime = timeMs; reanchor = false; return true
                        }
                        timeMs = (anchorTime - (e.x - anchorX) / pxPerMs).toLong().coerceIn(0, max(0, total))
                        listener?.onScrub(timeMs)
                        invalidate()
                    }
                    M_T_MOVE, M_T_START, M_T_END -> applyDrag(e.x)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(e)
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                vt?.recycle(); vt = null
                removeCallbacks(autoScroll)
                when (mode) {
                    M_REORDER -> {
                        val from = dragClip
                        var to = reorderTarget()
                        if (to > from) to -= 1
                        dragClip = -1
                        if (e.actionMasked == MotionEvent.ACTION_UP && to != from) listener?.onReorder(from, to)
                        finishUser()
                    }
                    M_T_MOVE, M_T_START, M_T_END -> {
                        snapLine = -1
                        dragText?.let { listener?.onTextTimingChanged(it, true) }
                        dragText = null
                        finishUser()
                    }
                    M_SCROLL -> {
                        if (!scaled && abs(vx) > 200 * d && e.actionMasked == MotionEvent.ACTION_UP) {
                            scroller.fling((timeMs * pxPerMs).toInt(), 0, (-vx).toInt(), 0, 0, (max(total, 0) * pxPerMs).toInt(), 0, 0)
                            flinging = true
                            postInvalidateOnAnimation()
                        } else finishUser()
                    }
                    else -> { userActive = false }
                }
                mode = M_NONE
            }
        }
        return true
    }

    override fun computeScroll() {
        if (!flinging) return
        if (scroller.computeScrollOffset()) {
            timeMs = (scroller.currX / pxPerMs).toLong().coerceIn(0, max(0, total))
            listener?.onScrub(timeMs)
            postInvalidateOnAnimation()
        } else { flinging = false; finishUser() }
    }

    private fun finishUser() {
        userActive = false
        listener?.onScrubEnd(timeMs)
        invalidate()
    }

    companion object {
        private const val M_NONE = 0
        private const val M_SCROLL = 1
        private const val M_T_MOVE = 2
        private const val M_T_START = 3
        private const val M_T_END = 4
        private const val M_REORDER = 5
        const val MIN_TEXT = 300L

        private val TILE_STEPS = longArrayOf(
            250, 500, 1000, 2000, 5000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000
        )
        private val LABEL_STEPS = longArrayOf(
            200, 500, 1000, 2000, 5000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000
        )
    }
}
