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
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Linha do tempo estilo CapCut: cursor fixo no meio, o filme corre por baixo.
 * - Barra fina no topo = filme inteiro (toque/arraste para pular rápido).
 * - Arrastar a tira = navegar, pinça = zoom (do filme todo até ~0,4s por tela).
 * - Bordas brancas = ajustar o corte.
 */
class TimelineView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    interface Listener {
        fun onScrub(ms: Long)
        fun onScrubEnd(ms: Long)
        fun onHandleDrag(ms: Long)
        fun onSelectionChanged(inMs: Long, outMs: Long)
    }

    var listener: Listener? = null
    var thumbs: ThumbnailProvider? = null
    /** Chamado quando o zoom muda (ms visíveis na tela). */
    var onZoomChanged: ((Long) -> Unit)? = null

    var durationMs = 0L
        set(v) { field = v; clampZoom(); invalidate() }

    var timeMs = 0L
        private set
    var inMs = -1L
        private set
    var outMs = -1L
        private set
    var scenes: List<Scene> = emptyList()
        set(v) { field = v; invalidate() }

    var userActive = false
        private set

    /** Prévia travada: navegação presa entre Início e Fim do corte. */
    var lockRange = false
        set(v) { field = v; invalidate() }

    private fun locked() = lockRange && inMs >= 0 && outMs > inMs
    private fun lockClamp(ms: Long): Long =
        if (locked()) ms.coerceIn(inMs, outMs) else ms.coerceIn(0, durationMs)

    private val d = resources.displayMetrics.density
    private var pxPerMs = 0f

    // faixas verticais
    private val ovTop = 2 * d
    private val ovH = 12 * d
    private val ovBottom = ovTop + ovH
    private val rulerTop = ovBottom + 4 * d
    private val rulerH = 14 * d
    private val rulerBottom = rulerTop + rulerH
    private val stripTop = rulerBottom + 4 * d
    private val stripH = 56 * d
    private val stripBottom = stripTop + stripH
    private val sceneTop = stripBottom + 6 * d
    private val sceneH = 6 * d
    private val tileW = 56 * d

    private val pEmpty = Paint().apply { color = 0xFF1C1C20.toInt() }
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pTick = Paint().apply { color = 0xFF55555F.toInt(); strokeWidth = d }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9A9AA5.toInt(); textSize = 10 * d }
    private val pSelFill = Paint().apply { color = 0x3322D3EE }
    private val pSelEdge = Paint().apply { color = Color.WHITE }
    private val pHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pGrip = Paint().apply { color = 0xFF000000.toInt(); strokeWidth = 2 * d }
    private val pHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2 * d }
    private val pScene = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pInMark = Paint().apply { color = 0xFF22D3EE.toInt(); strokeWidth = 3 * d }
    private val pDim = Paint().apply { color = 0x88000000.toInt() }
    private val pDimLock = Paint().apply { color = 0xCC000000.toInt() }
    private val pOvBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF26262C.toInt() }
    private val pOvWin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f * d
    }
    private val pOvPos = Paint().apply { color = 0xFF22D3EE.toInt(); strokeWidth = 2 * d }
    private val pOvSel = Paint().apply { color = 0x8822D3EE.toInt() }

    private val rect = RectF()
    private val src = Rect()

    fun setTime(ms: Long) {
        if (userActive) return
        timeMs = ms.coerceIn(0, max(0, durationMs))
        invalidate()
    }

    fun jumpTo(ms: Long) {
        scroller.forceFinished(true)
        flinging = false
        userActive = false
        setTime(ms)
    }

    fun setSelection(i: Long, o: Long) {
        inMs = i; outMs = o; invalidate()
    }

    private fun x(ms: Long): Float = width / 2f + (ms - timeMs) * pxPerMs
    private fun msAt(x: Float): Long = (timeMs + (x - width / 2f) / pxPerMs).toLong()

    private fun minZoom() = if (durationMs > 0 && width > 0) (width * 0.92f) / durationMs else 0.0001f
    private fun maxZoom() = if (width > 0) width / 400f else 1f   // ~0,4s na tela = quase quadro a quadro

    private fun clampZoom() {
        if (width == 0) return
        if (pxPerMs <= 0f) pxPerMs = width / 60_000f
        val lo = minZoom()
        pxPerMs = pxPerMs.coerceIn(lo, max(lo, maxZoom()))
        onZoomChanged?.invoke(visibleMs())
    }

    fun visibleMs(): Long = if (pxPerMs > 0) (width / pxPerMs).toLong() else 0L

    // ---------------- zoom por botão ----------------

    private var zoomAnim: ValueAnimator? = null

    private fun animateZoomTo(target: Float) {
        zoomAnim?.cancel()
        val lo = minZoom()
        val t = target.coerceIn(lo, max(lo, maxZoom()))
        val from = pxPerMs
        if (from <= 0f || abs(from - t) < 1e-9f) return
        // interpola em escala logarítmica: fica suave do filme todo até o quadro
        zoomAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                pxPerMs = (from * (t / from).toDouble().pow(f.toDouble())).toFloat()
                clampZoom()
                invalidate()
            }
            start()
        }
    }

    fun zoomIn() = animateZoomTo(pxPerMs * 2.5f)
    fun zoomOut() = animateZoomTo(pxPerMs / 2.5f)
    fun zoomFit() = animateZoomTo(minZoom())
    /** Zoom num nível fixo (ms mostrados na tela inteira). */
    fun zoomToVisible(ms: Long) { if (width > 0) animateZoomTo(width.toFloat() / ms) }

    /** Estilo CapCut: dá zoom para o corte caber na tela, a partir do cursor no Início. */
    fun focusSelection() {
        if (width == 0 || inMs < 0 || outMs <= inMs) return
        animateZoomTo(width * 0.46f / (outMs - inMs))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampZoom()
    }

    // ---------------- desenho ----------------

    override fun onDraw(c: Canvas) {
        if (durationMs <= 0 || width == 0) return
        val w = width.toFloat()
        val visStart = max(0L, msAt(0f))
        val visEnd = min(durationMs, msAt(w) + 1)

        drawOverview(c, w, visStart, visEnd)

        // régua
        val labelStep = LABEL_STEPS.firstOrNull { it * pxPerMs >= 70 * d } ?: LABEL_STEPS.last()
        var t = (visStart / labelStep) * labelStep
        while (t <= visEnd) {
            val xx = x(t)
            c.drawLine(xx, rulerBottom - 5 * d, xx, rulerBottom, pTick)
            c.drawText(Fmt.time(t, labelStep < 1000), xx + 3 * d, rulerBottom - 4 * d, pText)
            val half = x(t + labelStep / 2)
            c.drawCircle(half, rulerBottom - 2 * d, 1.2f * d, pText)
            t += labelStep
        }

        // tira de miniaturas
        val msPerTile = tileW / pxPerMs
        val step = TILE_STEPS.firstOrNull { it >= msPerTile } ?: TILE_STEPS.last()
        val exact = step <= 2000   // zoom alto: quadro exato; zoom baixo: keyframe (rápido)
        t = (visStart / step) * step
        val provider = thumbs
        while (t < visEnd) {
            val l = x(t)
            val r = x(min(t + step, durationMs))
            rect.set(l, stripTop, r, stripBottom)
            val b = provider?.get(t, exact)
            if (b != null && !b.isRecycled) drawCrop(c, b, rect) else c.drawRect(rect, pEmpty)
            t += step
        }
        c.drawRect(x(0) - 2 * d, stripTop, x(0), stripBottom, pSelEdge)
        c.drawRect(x(durationMs), stripTop, x(durationMs) + 2 * d, stripBottom, pSelEdge)

        // cenas já salvas
        for (s in scenes) {
            val l = x(s.startMs); val r = x(s.endMs)
            if (r < 0 || l > w) continue
            pScene.color = s.color
            rect.set(l + d, sceneTop, r - d, sceneTop + sceneH)
            c.drawRoundRect(rect, sceneH / 2, sceneH / 2, pScene)
        }

        // seleção atual
        if (inMs >= 0) {
            val xi = x(inMs)
            if (outMs > inMs) {
                val xo = x(outMs)
                val dim = if (lockRange) pDimLock else pDim
                c.drawRect(0f, stripTop, xi, stripBottom, dim)
                c.drawRect(xo, stripTop, w, stripBottom, dim)
                c.drawRect(xi, stripTop, xo, stripBottom, pSelFill)
                c.drawRect(xi, stripTop - 2 * d, xo, stripTop, pSelEdge)
                c.drawRect(xi, stripBottom, xo, stripBottom + 2 * d, pSelEdge)
                drawHandle(c, xi - HANDLE_W * d, xi)
                drawHandle(c, xo, xo + HANDLE_W * d)
            } else {
                c.drawLine(xi, stripTop - 3 * d, xi, stripBottom + 3 * d, pInMark)
                c.drawRect(xi, stripTop - 3 * d, xi + 10 * d, stripTop + 5 * d, pInMark)
            }
        }

        // cursor
        val cx = w / 2f
        c.drawLine(cx, rulerTop, cx, height.toFloat(), pHead)
        c.drawCircle(cx, rulerTop + 2 * d, 4 * d, pHead)
    }

    /** Mini-mapa do filme inteiro: cenas, seleção, parte visível e posição. */
    private fun drawOverview(c: Canvas, w: Float, visStart: Long, visEnd: Long) {
        val pad = 8 * d
        val bw = w - 2 * pad
        fun ox(ms: Long) = pad + bw * ms / durationMs.toFloat()
        rect.set(pad, ovTop, w - pad, ovBottom)
        c.drawRoundRect(rect, ovH / 2, ovH / 2, pOvBg)
        for (s in scenes) {
            pScene.color = s.color
            rect.set(ox(s.startMs), ovTop + ovH * 0.3f, max(ox(s.endMs), ox(s.startMs) + 2 * d), ovBottom - ovH * 0.3f)
            c.drawRect(rect, pScene)
        }
        if (inMs >= 0 && outMs > inMs) {
            rect.set(ox(inMs), ovTop, max(ox(outMs), ox(inMs) + 2 * d), ovBottom)
            c.drawRect(rect, pOvSel)
        }
        // janela visível
        val l = ox(visStart); val r = max(ox(visEnd), l + 6 * d)
        rect.set(l, ovTop, r, ovBottom)
        c.drawRoundRect(rect, 3 * d, 3 * d, pOvWin)
        val px = ox(timeMs)
        c.drawLine(px, ovTop - d, px, ovBottom + d, pOvPos)
    }

    private fun drawHandle(c: Canvas, l: Float, r: Float) {
        rect.set(l, stripTop - 2 * d, r, stripBottom + 2 * d)
        c.drawRoundRect(rect, 4 * d, 4 * d, pHandle)
        val mid = (l + r) / 2
        c.drawLine(mid, stripTop + stripH * 0.35f, mid, stripTop + stripH * 0.65f, pGrip)
    }

    private fun drawCrop(c: Canvas, b: Bitmap, dst: RectF) {
        val dw = dst.width(); val dh = dst.height()
        if (dw <= 0 || dh <= 0) return
        val scale = max(dw / b.width, dh / b.height)
        val sw = (dw / scale).toInt().coerceIn(1, b.width)
        val sh = (dh / scale).toInt().coerceIn(1, b.height)
        val sx = (b.width - sw) / 2
        val sy = (b.height - sh) / 2
        src.set(sx, sy, sx + sw, sy + sh)
        c.drawBitmap(b, src, dst, pBmp)
    }

    // ---------------- toque ----------------

    private val scroller = OverScroller(ctx)
    private var flinging = false
    private var vt: VelocityTracker? = null
    private var mode = MODE_NONE
    private var anchorX = 0f
    private var anchorTime = 0L
    private var grabOffset = 0f
    private var reanchor = false
    private var scaled = false

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(det: ScaleGestureDetector): Boolean {
            zoomAnim?.cancel()
            return true
        }

        override fun onScale(det: ScaleGestureDetector): Boolean {
            // expoente > 1 = zoom mais rápido (atravessa um filme de 2h em um gesto)
            pxPerMs *= det.scaleFactor.toDouble().pow(1.8).toFloat()
            clampZoom()
            invalidate()
            return true
        }
    })

    private fun overviewMs(ex: Float): Long {
        val pad = 8 * d
        val f = ((ex - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        return (durationMs * f).toLong()
    }

    private fun hitHandle(ex: Float, ey: Float): Int {
        if (ey <= ovBottom + 5 * d) return MODE_OVERVIEW
        if (ey < stripTop - 12 * d || ey > stripBottom + 12 * d) return MODE_SCROLL
        if (inMs < 0 || outMs <= inMs) return MODE_SCROLL
        val xi = x(inMs) - HANDLE_W * d / 2
        val xo = x(outMs) + HANDLE_W * d / 2
        val di = abs(ex - xi); val dout = abs(ex - xo)
        val zone = 22 * d
        return when {
            di <= zone && di <= dout -> MODE_IN
            dout <= zone -> MODE_OUT
            else -> MODE_SCROLL
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (durationMs <= 0) return false
        if (mode != MODE_OVERVIEW) scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scroller.forceFinished(true)
                flinging = false
                userActive = true
                scaled = false
                vt?.recycle()
                vt = VelocityTracker.obtain().also { it.addMovement(e) }
                mode = hitHandle(e.x, e.y)
                anchorX = e.x; anchorTime = timeMs
                grabOffset = when (mode) {
                    MODE_IN -> e.x - x(inMs)
                    MODE_OUT -> e.x - x(outMs)
                    else -> 0f
                }
                if (mode == MODE_OVERVIEW) {
                    timeMs = lockClamp(overviewMs(e.x))
                    listener?.onScrub(timeMs)
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> { scaled = true; reanchor = true }
            MotionEvent.ACTION_POINTER_UP -> reanchor = true
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (mode == MODE_OVERVIEW) {
                    timeMs = lockClamp(overviewMs(e.x))
                    listener?.onScrub(timeMs)
                    invalidate()
                    return true
                }
                if (e.pointerCount > 1 || reanchor) {
                    anchorX = e.x; anchorTime = timeMs; reanchor = false
                    if (mode != MODE_SCROLL) mode = MODE_SCROLL
                    return true
                }
                when (mode) {
                    MODE_SCROLL -> {
                        timeMs = lockClamp((anchorTime - (e.x - anchorX) / pxPerMs).toLong())
                        listener?.onScrub(timeMs)
                        invalidate()
                    }
                    MODE_IN -> {
                        inMs = msAt(e.x - grabOffset).coerceIn(0, max(0, outMs - MIN_SCENE))
                        listener?.onHandleDrag(inMs)
                        listener?.onSelectionChanged(inMs, outMs)
                        invalidate()
                    }
                    MODE_OUT -> {
                        outMs = msAt(e.x - grabOffset).coerceIn(min(durationMs, inMs + MIN_SCENE), durationMs)
                        listener?.onHandleDrag(outMs)
                        listener?.onSelectionChanged(inMs, outMs)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                vt?.addMovement(e)
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                vt?.recycle(); vt = null
                if (mode == MODE_SCROLL && !scaled && abs(vx) > 200 * d) {
                    val pos = (timeMs * pxPerMs).toInt()
                    val lo = if (locked()) (inMs * pxPerMs).toInt() else 0
                    val hi = if (locked()) (outMs * pxPerMs).toInt() else (durationMs * pxPerMs).toInt()
                    scroller.fling(pos, 0, (-vx).toInt(), 0, lo, hi, 0, 0)
                    flinging = true
                    postInvalidateOnAnimation()
                } else finishUser()
            }
            MotionEvent.ACTION_CANCEL -> {
                vt?.recycle(); vt = null
                finishUser()
            }
        }
        return true
    }

    override fun computeScroll() {
        if (!flinging) return
        if (scroller.computeScrollOffset()) {
            timeMs = lockClamp((scroller.currX / pxPerMs).toLong())
            listener?.onScrub(timeMs)
            postInvalidateOnAnimation()
        } else {
            flinging = false
            finishUser()
        }
    }

    private fun finishUser() {
        if (locked()) timeMs = lockClamp(timeMs)
        userActive = false
        mode = MODE_NONE
        listener?.onScrubEnd(timeMs)
        invalidate()
    }

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_SCROLL = 1
        private const val MODE_IN = 2
        private const val MODE_OUT = 3
        private const val MODE_OVERVIEW = 4
        private const val HANDLE_W = 14
        const val MIN_SCENE = 200L

        private val TILE_STEPS = longArrayOf(
            100, 250, 500, 1000, 2000, 5000, 10_000, 15_000, 30_000, 60_000,
            120_000, 300_000, 600_000, 1_200_000
        )
        private val LABEL_STEPS = longArrayOf(
            200, 500, 1000, 2000, 5000, 10_000, 15_000, 30_000, 60_000, 120_000,
            300_000, 600_000, 900_000, 1_800_000, 3_600_000
        )
    }
}
