package com.leve.editor

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
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Linha do tempo estilo CapCut: cursor fixo no meio, o filme corre por baixo.
 * Arrastar = navegar, pinça = zoom, bordas brancas = ajustar o corte.
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

    private val d = resources.displayMetrics.density
    private var pxPerMs = 0f

    private val rulerH = 16 * d
    private val stripTop = rulerH + 4 * d
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

    private fun minZoom() = if (durationMs > 0 && width > 0) (width * 0.9f) / durationMs else 0.0001f
    private fun maxZoom() = if (width > 0) width / 1500f else 1f

    private fun clampZoom() {
        if (width == 0) return
        if (pxPerMs <= 0f) pxPerMs = width / 60_000f
        val lo = minZoom()
        pxPerMs = pxPerMs.coerceIn(lo, max(lo, maxZoom()))
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

        // régua
        val labelStep = LABEL_STEPS.firstOrNull { it * pxPerMs >= 70 * d } ?: LABEL_STEPS.last()
        var t = (visStart / labelStep) * labelStep
        while (t <= visEnd) {
            val xx = x(t)
            c.drawLine(xx, rulerH - 5 * d, xx, rulerH, pTick)
            c.drawText(Fmt.time(t, false), xx + 3 * d, rulerH - 6 * d, pText)
            val half = x(t + labelStep / 2)
            c.drawCircle(half, rulerH - 2 * d, 1.2f * d, pText)
            t += labelStep
        }

        // tira de miniaturas
        val msPerTile = tileW / pxPerMs
        val step = TILE_STEPS.firstOrNull { it >= msPerTile } ?: TILE_STEPS.last()
        t = (visStart / step) * step
        val provider = thumbs
        while (t < visEnd) {
            val l = x(t)
            val r = x(min(t + step, durationMs))
            rect.set(l, stripTop, r, stripBottom)
            val b = provider?.get(t)
            if (b != null && !b.isRecycled) drawCrop(c, b, rect) else c.drawRect(rect, pEmpty)
            t += step
        }
        // bordas do filme
        c.drawRect(x(0) - 2 * d, stripTop, x(0), stripBottom, pSelEdge)
        c.drawRect(x(durationMs), stripTop, x(durationMs) + 2 * d, stripBottom, pSelEdge)

        // cenas já salvas (barrinhas coloridas)
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
                // escurece fora da seleção
                c.drawRect(0f, stripTop, xi, stripBottom, pDim)
                c.drawRect(xo, stripTop, w, stripBottom, pDim)
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

        // cursor (meio)
        val cx = w / 2f
        c.drawLine(cx, 0f, cx, height.toFloat(), pHead)
        c.drawCircle(cx, 3 * d, 4 * d, pHead)
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
        val sw = (dw / scale).toInt().coerceAtMost(b.width)
        val sh = (dh / scale).toInt().coerceAtMost(b.height)
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
        override fun onScale(det: ScaleGestureDetector): Boolean {
            pxPerMs *= det.scaleFactor
            clampZoom()
            invalidate()
            return true
        }
    })

    private fun hitHandle(ex: Float, ey: Float): Int {
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
        scaleDetector.onTouchEvent(e)
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
            }
            MotionEvent.ACTION_POINTER_DOWN -> { scaled = true; reanchor = true }
            MotionEvent.ACTION_POINTER_UP -> reanchor = true
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (e.pointerCount > 1 || reanchor) {
                    anchorX = e.x; anchorTime = timeMs; reanchor = false
                    if (mode != MODE_SCROLL) mode = MODE_SCROLL
                    return true
                }
                when (mode) {
                    MODE_SCROLL -> {
                        timeMs = (anchorTime - (e.x - anchorX) / pxPerMs).toLong().coerceIn(0, durationMs)
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
                if (mode == MODE_SCROLL && !scaled && abs(vx) > 600 * d / 3) {
                    val pos = (timeMs * pxPerMs).toInt()
                    scroller.fling(pos, 0, (-vx).toInt(), 0, 0, (durationMs * pxPerMs).toInt(), 0, 0)
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
            timeMs = (scroller.currX / pxPerMs).toLong().coerceIn(0, durationMs)
            listener?.onScrub(timeMs)
            postInvalidateOnAnimation()
        } else {
            flinging = false
            finishUser()
        }
    }

    private fun finishUser() {
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
        private const val HANDLE_W = 14
        const val MIN_SCENE = 200L

        private val TILE_STEPS = longArrayOf(
            250, 500, 1000, 2000, 5000, 10_000, 15_000, 30_000, 60_000,
            120_000, 300_000, 600_000, 1_200_000
        )
        private val LABEL_STEPS = longArrayOf(
            1000, 2000, 5000, 10_000, 15_000, 30_000, 60_000, 120_000,
            300_000, 600_000, 900_000, 1_800_000, 3_600_000
        )
    }
}
