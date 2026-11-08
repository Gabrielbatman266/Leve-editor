package com.leve.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Camada de textos por cima do vídeo (igual CapCut):
 * tocar = selecionar, arrastar = mover (com guias no centro), pinça = tamanho e giro,
 * botões no canto: ✕ apagar, ✎ editar, ↻ girar/redimensionar com um dedo.
 */
class TextLayerView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    interface Listener {
        fun onSelect(id: String?)
        fun onEdit(id: String)
        fun onDelete(id: String)
        fun onChanged(t: TextItem, done: Boolean)
    }

    var listener: Listener? = null
    var project: Project? = null
    var videoW = 1920
    var videoH = 1080
    var timeMs = 0L
        set(v) { if (field != v) { field = v; invalidate() } }
    var selectedId: String? = null
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val content = RectF()
    private val cache = HashMap<String, Pair<String, Bitmap>>()
    private val st = TextAnim.State()

    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pBox = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f * d
        pathEffect = DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f)
    }
    private val pBtn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pBtnIcon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 13 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val pGuide = Paint().apply { color = 0xFF22D3EE.toInt(); strokeWidth = 1.2f * d }
    private val btnR = 12 * d

    private fun computeContent() {
        val vw = width.toFloat(); val vh = height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val ar = videoW.toFloat() / max(1, videoH)
        if (vw / vh > ar) {
            val w = vh * ar; content.set((vw - w) / 2, 0f, (vw + w) / 2, vh)
        } else {
            val h = vw / ar; content.set(0f, (vh - h) / 2, vw, (vh + h) / 2)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh); computeContent(); cache.clear()
    }

    fun bitmapFor(t: TextItem): Bitmap {
        val refH = content.height().toInt().coerceAtLeast(10)
        val key = t.styleKey() + "@" + refH
        cache[t.id]?.let { if (it.first == key) return it.second }
        val b = TextRenderer.render(t, refH)
        cache[t.id] = key to b
        return b
    }

    fun forget(id: String) { cache.remove(id) }

    // geometria do selecionado (sem animação) para o toque
    private var selCx = 0f; private var selCy = 0f; private var selHw = 0f; private var selHh = 0f; private var selRot = 0f
    private var selVisible = false
    private var guideX = false; private var guideY = false

    override fun onDraw(c: Canvas) {
        val p = project ?: return
        if (content.isEmpty) computeContent()
        selVisible = false
        for (t in p.texts) {
            val s = TextAnim.state(t, timeMs, st)
            val isSel = t.id == selectedId
            if (s == null) continue
            val b = bitmapFor(t)
            val cx = content.left + (t.x + s.dx) * content.width()
            val cy = content.top + (t.y + s.dy) * content.height()
            c.save()
            c.translate(cx, cy)
            c.rotate(t.rot + s.rot)
            c.scale(s.scale, s.scale)
            pBmp.alpha = (255 * s.alpha).toInt().coerceIn(0, 255)
            c.drawBitmap(b, -b.width / 2f, -b.height / 2f, pBmp)
            c.restore()
            if (isSel) {
                selVisible = true
                selCx = content.left + t.x * content.width()
                selCy = content.top + t.y * content.height()
                selHw = b.width / 2f + 4 * d; selHh = b.height / 2f + 4 * d
                selRot = t.rot
            }
        }
        if (selVisible) {
            c.save()
            c.translate(selCx, selCy)
            c.rotate(selRot)
            c.drawRect(-selHw, -selHh, selHw, selHh, pBox)
            drawBtn(c, -selHw, -selHh, "✕")
            drawBtn(c, selHw, -selHh, "✎")
            drawBtn(c, selHw, selHh, "↻")
            c.restore()
        }
        if (guideX) c.drawLine(content.centerX(), content.top, content.centerX(), content.bottom, pGuide)
        if (guideY) c.drawLine(content.left, content.centerY(), content.right, content.centerY(), pGuide)
    }

    private fun drawBtn(c: Canvas, x: Float, y: Float, icon: String) {
        c.drawCircle(x, y, btnR, pBtn)
        c.drawText(icon, x, y + pBtnIcon.textSize * 0.36f, pBtnIcon)
    }

    // ---------------- toque ----------------

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var mode = 0 // 0 nada, 1 mover, 2 girar/escala (botão), 3 pinça, 4 talvez-tocar
    private var target: TextItem? = null
    private var downX = 0f; private var downY = 0f
    private var startX = 0f; private var startY = 0f
    private var startScale = 1f; private var startRot = 0f
    private var startDist = 1f; private var startAngle = 0f
    private var moved = false
    private var wasSelected = false

    /** Converte o toque para as coordenadas do texto selecionado (sem giro). */
    private fun local(ex: Float, ey: Float): Pair<Float, Float> {
        val a = Math.toRadians(-selRot.toDouble())
        val dx = ex - selCx; val dy = ey - selCy
        return Pair((dx * cos(a) - dy * sin(a)).toFloat(), (dx * sin(a) + dy * cos(a)).toFloat())
    }

    private fun hitText(ex: Float, ey: Float): TextItem? {
        val p = project ?: return null
        for (t in p.texts.asReversed()) {
            val s = TextAnim.state(t, timeMs, st) ?: continue
            val b = bitmapFor(t)
            val cx = content.left + (t.x + s.dx) * content.width()
            val cy = content.top + (t.y + s.dy) * content.height()
            val a = Math.toRadians(-(t.rot + s.rot).toDouble())
            val dx = ex - cx; val dy = ey - cy
            val lx = (dx * cos(a) - dy * sin(a)).toFloat() / s.scale
            val ly = (dx * sin(a) + dy * cos(a)).toFloat() / s.scale
            val pad = 8 * d
            if (abs(lx) <= b.width / 2f + pad && abs(ly) <= b.height / 2f + pad) return t
        }
        return null
    }

    private fun selected(): TextItem? = project?.texts?.firstOrNull { it.id == selectedId }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = project ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; moved = false; mode = 0; target = null
                val sel = selected()
                if (sel != null && selVisible) {
                    val (lx, ly) = local(e.x, e.y)
                    val r = btnR * 1.6f
                    when {
                        hypot(lx + selHw, ly + selHh) <= r -> { listener?.onDelete(sel.id); return true }
                        hypot(lx - selHw, ly + selHh) <= r -> { listener?.onEdit(sel.id); return true }
                        hypot(lx - selHw, ly - selHh) <= r -> {
                            mode = 2; target = sel
                            startScale = sel.scale; startRot = sel.rot
                            startDist = max(1f, hypot(e.x - selCx, e.y - selCy))
                            startAngle = Math.toDegrees(atan2((e.y - selCy).toDouble(), (e.x - selCx).toDouble())).toFloat()
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                    }
                }
                val hit = hitText(e.x, e.y)
                if (hit != null) {
                    wasSelected = hit.id == selectedId
                    if (!wasSelected) { selectedId = hit.id; listener?.onSelect(hit.id) }
                    target = hit; mode = 4
                    startX = hit.x; startY = hit.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                if (selectedId != null) { selectedId = null; listener?.onSelect(null) }
                return p.texts.isNotEmpty()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val t = target ?: selected() ?: return true
                if (e.pointerCount >= 2) {
                    target = t; mode = 3
                    startScale = t.scale; startRot = t.rot
                    startDist = max(1f, hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)))
                    startAngle = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val t = target ?: return true
                when (mode) {
                    4, 1 -> {
                        if (mode == 4 && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) mode = 1
                        if (mode == 1) {
                            moved = true
                            var nx = startX + (e.x - downX) / content.width()
                            var ny = startY + (e.y - downY) / content.height()
                            val gx = abs(nx - 0.5f) < 0.015f
                            val gy = abs(ny - 0.5f) < 0.015f
                            if (gx) nx = 0.5f
                            if (gy) ny = 0.5f
                            if ((gx && !guideX) || (gy && !guideY)) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            guideX = gx; guideY = gy
                            t.x = nx.coerceIn(-0.2f, 1.2f); t.y = ny.coerceIn(-0.2f, 1.2f)
                            listener?.onChanged(t, false)
                            invalidate()
                        }
                    }
                    2 -> {
                        moved = true
                        val dist = hypot(e.x - selCx, e.y - selCy)
                        val ang = Math.toDegrees(atan2((e.y - selCy).toDouble(), (e.x - selCx).toDouble())).toFloat()
                        t.scale = (startScale * dist / startDist).coerceIn(0.2f, 6f)
                        t.rot = snapRot(startRot + (ang - startAngle))
                        listener?.onChanged(t, false)
                        invalidate()
                    }
                    3 -> if (e.pointerCount >= 2) {
                        moved = true
                        val dist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                        val ang = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
                        t.scale = (startScale * dist / startDist).coerceIn(0.2f, 6f)
                        t.rot = snapRot(startRot + (ang - startAngle))
                        listener?.onChanged(t, false)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == 3) {
                    // continua movendo com o dedo que sobrou
                    val t = target
                    val keep = if (e.actionIndex == 0) 1 else 0
                    if (t != null) { mode = 1; downX = e.getX(keep); downY = e.getY(keep); startX = t.x; startY = t.y }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val t = target
                guideX = false; guideY = false
                if (t != null) {
                    if (moved) listener?.onChanged(t, true)
                    else if (mode == 4 && wasSelected && e.actionMasked == MotionEvent.ACTION_UP) listener?.onEdit(t.id)
                }
                target = null; mode = 0
                invalidate()
            }
        }
        return true
    }

    /** Gruda em 0/90/180/270 graus quando chega perto. */
    private fun snapRot(r: Float): Float {
        var a = ((r % 360) + 360) % 360
        for (s in floatArrayOf(0f, 90f, 180f, 270f, 360f)) if (abs(a - s) < 4f) a = s
        return if (a > 180) a - 360 else a
    }

    fun contentRect(): RectF = RectF(content)
}
