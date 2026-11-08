package com.leve.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Um texto da montagem (igual à faixa de texto do CapCut): fica na linha do tempo do vídeo
 * final, com início/duração próprios, posição livre e estilo completo.
 */
class TextItem(val id: String, var text: String, var startMs: Long, var durMs: Long) {
    var x = 0.5f          // centro, 0..1 da largura
    var y = 0.85f         // centro, 0..1 da altura
    var scale = 1f
    var rot = 0f          // graus, horário
    var font = 0
    var color = Color.WHITE
    var opacity = 1f
    var strokeColor = Color.BLACK
    var strokeW = 0f      // 0..1
    var glowColor = 0xFF22D3EE.toInt()
    var glow = 0f         // 0..1
    var bgColor = Color.BLACK
    var bgAlpha = 0f      // 0..1
    var bgRound = 0.35f   // 0..1
    var shadow = 0f       // 0..1 (opacidade)
    var shadowBlur = 0.35f
    var align = 1         // 0 esq, 1 centro, 2 dir
    var animIn = 0
    var animOut = 0
    var animLoop = 0

    val endMs: Long get() = startMs + durMs

    fun styleKey(): String = listOf(
        text, scale, font, color, opacity, strokeColor, strokeW, glowColor, glow,
        bgColor, bgAlpha, bgRound, shadow, shadowBlur, align
    ).joinToString("|")

    fun copyStyleFrom(o: TextItem) {
        scale = o.scale; rot = o.rot; font = o.font; color = o.color; opacity = o.opacity
        strokeColor = o.strokeColor; strokeW = o.strokeW; glowColor = o.glowColor; glow = o.glow
        bgColor = o.bgColor; bgAlpha = o.bgAlpha; bgRound = o.bgRound
        shadow = o.shadow; shadowBlur = o.shadowBlur; align = o.align
        animIn = o.animIn; animOut = o.animOut; animLoop = o.animLoop
    }

    fun duplicate(): TextItem = TextItem(newId(), text, startMs, durMs).also {
        it.copyStyleFrom(this); it.x = x; it.y = y
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("text", text).put("start", startMs).put("dur", durMs)
        .put("x", x.toDouble()).put("y", y.toDouble()).put("scale", scale.toDouble()).put("rot", rot.toDouble())
        .put("font", font).put("color", color).put("opacity", opacity.toDouble())
        .put("strokeColor", strokeColor).put("strokeW", strokeW.toDouble())
        .put("glowColor", glowColor).put("glow", glow.toDouble())
        .put("bgColor", bgColor).put("bgAlpha", bgAlpha.toDouble()).put("bgRound", bgRound.toDouble())
        .put("shadow", shadow.toDouble()).put("shadowBlur", shadowBlur.toDouble())
        .put("align", align).put("animIn", animIn).put("animOut", animOut).put("animLoop", animLoop)

    companion object {
        fun from(o: JSONObject): TextItem {
            val t = TextItem(o.getString("id"), o.optString("text", ""), o.optLong("start", 0), o.optLong("dur", 3000))
            t.x = o.optDouble("x", 0.5).toFloat()
            t.y = o.optDouble("y", 0.85).toFloat()
            t.scale = o.optDouble("scale", 1.0).toFloat()
            t.rot = o.optDouble("rot", 0.0).toFloat()
            t.font = o.optInt("font", 0)
            t.color = o.optInt("color", Color.WHITE)
            t.opacity = o.optDouble("opacity", 1.0).toFloat()
            t.strokeColor = o.optInt("strokeColor", Color.BLACK)
            t.strokeW = o.optDouble("strokeW", 0.0).toFloat()
            t.glowColor = o.optInt("glowColor", 0xFF22D3EE.toInt())
            t.glow = o.optDouble("glow", 0.0).toFloat()
            t.bgColor = o.optInt("bgColor", Color.BLACK)
            t.bgAlpha = o.optDouble("bgAlpha", 0.0).toFloat()
            t.bgRound = o.optDouble("bgRound", 0.35).toFloat()
            t.shadow = o.optDouble("shadow", 0.0).toFloat()
            t.shadowBlur = o.optDouble("shadowBlur", 0.35).toFloat()
            t.align = o.optInt("align", 1)
            t.animIn = o.optInt("animIn", 0)
            t.animOut = o.optInt("animOut", 0)
            t.animLoop = o.optInt("animLoop", 0)
            return t
        }
    }
}

object TextFonts {
    val NAMES = arrayOf(
        "Padrão", "Médio", "Fino", "Pesado", "Condensado", "Serifa",
        "Clássico", "Máquina", "Casual", "Cursiva", "Versalete"
    )
    private val cache = arrayOfNulls<Typeface>(NAMES.size)

    fun get(i: Int): Typeface {
        val idx = i.coerceIn(0, NAMES.size - 1)
        cache[idx]?.let { return it }
        val tf = when (idx) {
            0 -> Typeface.create("sans-serif", Typeface.BOLD)
            1 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            2 -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            3 -> Typeface.create("sans-serif-black", Typeface.NORMAL)
            4 -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
            5 -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
            6 -> Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
            7 -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            8 -> Typeface.create("casual", Typeface.NORMAL)
            9 -> Typeface.create("cursive", Typeface.BOLD)
            else -> Typeface.create("sans-serif-smallcaps", Typeface.BOLD)
        }
        cache[idx] = tf
        return tf
    }
}

/** Estilos prontos (aba "Estilos" do CapCut). */
object TextPresets {
    class Preset(val name: String, val apply: (TextItem) -> Unit)

    private fun reset(t: TextItem) {
        t.color = Color.WHITE; t.opacity = 1f; t.strokeW = 0f; t.strokeColor = Color.BLACK
        t.glow = 0f; t.bgAlpha = 0f; t.bgRound = 0.35f; t.shadow = 0f; t.shadowBlur = 0.35f
    }

    val ALL = listOf(
        Preset("Simples") { reset(it); it.shadow = 0.5f },
        Preset("Contorno") { reset(it); it.strokeW = 0.6f },
        Preset("Amarelo") { reset(it); it.color = 0xFFFACC15.toInt(); it.strokeW = 0.55f },
        Preset("Caixa preta") { reset(it); it.bgColor = Color.BLACK; it.bgAlpha = 0.75f },
        Preset("Caixa branca") { reset(it); it.color = Color.BLACK; it.bgColor = Color.WHITE; it.bgAlpha = 0.95f },
        Preset("Neon") { reset(it); it.glowColor = 0xFF22D3EE.toInt(); it.glow = 0.85f },
        Preset("Neon rosa") { reset(it); it.glowColor = 0xFFF472B6.toInt(); it.glow = 0.85f },
        Preset("Legenda") { reset(it); it.bgAlpha = 0.5f; it.bgRound = 0.2f; it.font = 1 },
        Preset("Cinema") { reset(it); it.font = 5; it.shadow = 0.85f; it.shadowBlur = 0.6f },
        Preset("Destaque") { reset(it); it.color = Color.BLACK; it.bgColor = 0xFFFACC15.toInt(); it.bgAlpha = 1f },
        Preset("Alerta") { reset(it); it.bgColor = 0xFFE11D48.toInt(); it.bgAlpha = 1f; it.bgRound = 0.15f },
        Preset("Quadrinho") { reset(it); it.font = 3; it.color = 0xFFFACC15.toInt(); it.strokeW = 0.9f; it.shadow = 0.6f },
        Preset("Fogo") { reset(it); it.color = 0xFFFFEDD5.toInt(); it.glowColor = 0xFFF97316.toInt(); it.glow = 0.9f },
        Preset("Elegante") { reset(it); it.font = 6; it.color = 0xFFFDE68A.toInt(); it.shadow = 0.6f }
    )

    val COLORS = intArrayOf(
        Color.WHITE, 0xFF000000.toInt(), 0xFF9CA3AF.toInt(), 0xFFFACC15.toInt(), 0xFFFB923C.toInt(),
        0xFFF43F5E.toInt(), 0xFFF472B6.toInt(), 0xFFA78BFA.toInt(), 0xFF6366F1.toInt(), 0xFF22D3EE.toInt(),
        0xFF4ADE80.toInt(), 0xFF16A34A.toInt(), 0xFF92400E.toInt(), 0xFFFDE68A.toInt(), 0xFF7F1D1D.toInt()
    )
}

/** Desenha o texto num Bitmap — o MESMO usado na prévia e na exportação. */
object TextRenderer {
    const val BASE = 0.065f

    fun render(t: TextItem, refH: Int): Bitmap {
        var px = refH * BASE * t.scale
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = TextFonts.get(t.font)
            textSize = px
        }
        val lines = t.text.ifEmpty { " " }.split('\n')
        var maxW = lines.maxOf { paint.measureText(it) }
        // limite de tamanho (protege a memória)
        val limit = refH * 3.2f
        if (maxW > limit) { px *= limit / maxW; paint.textSize = px; maxW = lines.maxOf { paint.measureText(it) } }

        val fm = paint.fontMetrics
        val lineH = fm.descent - fm.ascent
        val spacing = lineH * 1.05f
        val hasBg = t.bgAlpha > 0.01f
        val padX = if (hasBg) px * 0.42f else 0f
        val padY = if (hasBg) px * 0.2f else 0f
        val strokePx = t.strokeW * px * 0.16f
        val glowR = t.glow * px * 0.55f
        val shadowR = 1f + t.shadowBlur * px * 0.3f
        val shadowOff = px * 0.06f
        val margin = ceil(strokePx + max(glowR * 1.2f, if (t.shadow > 0f) shadowR + shadowOff else 0f) + 3f)
        val boxW = maxW + 2 * padX
        val boxH = spacing * (lines.size - 1) + lineH + 2 * padY
        val w = ceil(boxW + 2 * margin).toInt().coerceIn(1, 4096)
        val h = ceil(boxH + 2 * margin).toInt().coerceIn(1, 4096)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        if (hasBg) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = t.bgColor
                alpha = (255 * t.bgAlpha * t.opacity).toInt().coerceIn(0, 255)
            }
            val r = t.bgRound * min(boxH, px * 1.2f) * 0.5f
            c.drawRoundRect(RectF(margin, margin, margin + boxW, margin + boxH), r, r, bg)
        }

        val a = (255 * t.opacity).toInt().coerceIn(0, 255)
        val glowPaint = if (t.glow > 0.01f) Paint(paint).apply {
            color = t.glowColor; this.alpha = a
            setShadowLayer(glowR, 0f, 0f, t.glowColor)
        } else null
        val shadowPaint = if (t.shadow > 0.01f) Paint(paint).apply {
            color = t.color; this.alpha = a
            setShadowLayer(shadowR, shadowOff, shadowOff, Color.argb((255 * t.shadow).toInt().coerceIn(0, 255), 0, 0, 0))
        } else null
        val strokePaint = if (strokePx > 0.3f) Paint(paint).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx * 2
            strokeJoin = Paint.Join.ROUND
            strokeMiter = 10f
            color = t.strokeColor; this.alpha = a
        } else null
        paint.color = t.color
        paint.alpha = a

        lines.forEachIndexed { i, line ->
            val lw = paint.measureText(line)
            val lx = margin + padX + when (t.align) { 0 -> 0f; 2 -> maxW - lw; else -> (maxW - lw) / 2 }
            val base = margin + padY - fm.ascent + i * spacing
            glowPaint?.let { c.drawText(line, lx, base, it); c.drawText(line, lx, base, it) }
            shadowPaint?.let { c.drawText(line, lx, base, it) }
            strokePaint?.let { c.drawText(line, lx, base, it) }
            c.drawText(line, lx, base, paint)
        }
        return bmp
    }
}

/** Animações de entrada, saída e loop — mesmas contas na prévia e na exportação. */
object TextAnim {
    val IN_NAMES = arrayOf("Nenhuma", "Aparecer", "Subir", "Zoom", "Deslizar", "Cair", "Girar")
    val OUT_NAMES = arrayOf("Nenhuma", "Sumir", "Descer", "Zoom", "Deslizar", "Subir")
    val LOOP_NAMES = arrayOf("Nenhum", "Pulsar", "Balançar", "Flutuar", "Piscar")

    class State {
        var alpha = 1f
        var dx = 0f        // fração da largura
        var dy = 0f        // fração da altura
        var scale = 1f
        var rot = 0f
    }

    private fun easeOut(p: Float) = 1f - (1f - p).pow(3)
    private fun easeOutBack(p: Float): Float {
        val c1 = 1.70158f; val c3 = c1 + 1
        return 1 + c3 * (p - 1).pow(3) + c1 * (p - 1).pow(2)
    }

    /** Retorna null se o texto não aparece em [g] (tempo global da montagem). */
    fun state(t: TextItem, g: Long, out: State = State()): State? {
        if (g < t.startMs || g >= t.endMs) return null
        out.alpha = 1f; out.dx = 0f; out.dy = 0f; out.scale = 1f; out.rot = 0f
        val d = min(450L, max(1L, t.durMs / 3)).toFloat()
        val p = ((g - t.startMs) / d).coerceIn(0f, 1f)
        val e = easeOut(p)
        when (t.animIn) {
            1 -> out.alpha *= p
            2 -> { out.alpha *= p; out.dy += (1 - e) * 0.06f }
            3 -> { out.scale *= 0.3f + 0.7f * easeOutBack(p); out.alpha *= min(1f, p * 2) }
            4 -> { out.alpha *= p; out.dx -= (1 - e) * 0.15f }
            5 -> { out.alpha *= p; out.dy -= (1 - e) * 0.06f }
            6 -> { out.alpha *= p; out.rot -= (1 - e) * 90f; out.scale *= 0.5f + 0.5f * e }
        }
        val q = ((t.endMs - g) / d).coerceIn(0f, 1f)
        val eq = easeOut(q)
        when (t.animOut) {
            1 -> out.alpha *= q
            2 -> { out.alpha *= q; out.dy += (1 - eq) * 0.06f }
            3 -> { out.scale *= 0.3f + 0.7f * eq; out.alpha *= q }
            4 -> { out.alpha *= q; out.dx += (1 - eq) * 0.15f }
            5 -> { out.alpha *= q; out.dy -= (1 - eq) * 0.06f }
        }
        val lt = (g - t.startMs).toFloat()
        when (t.animLoop) {
            1 -> out.scale *= 1f + 0.05f * sin(2 * PI.toFloat() * lt / 900f)
            2 -> out.rot += 4f * sin(2 * PI.toFloat() * lt / 1200f)
            3 -> out.dy += 0.012f * sin(2 * PI.toFloat() * lt / 1500f)
            4 -> out.alpha *= 0.65f + 0.35f * cos(2 * PI.toFloat() * lt / 1000f)
        }
        return out
    }
}
