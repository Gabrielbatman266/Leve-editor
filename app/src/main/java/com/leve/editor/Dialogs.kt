package com.leve.editor

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object Dialogs {

    private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(ctx: Context, s: String) = TextView(ctx).apply {
        text = s
        setTextColor(0xFF9A9AA5.toInt())
        textSize = 13f
        setPadding(0, ctx.dp(12), 0, ctx.dp(4))
    }

    private fun radios(ctx: Context, names: Array<String>, checked: Int): RadioGroup =
        RadioGroup(ctx).apply {
            orientation = RadioGroup.HORIZONTAL
            names.forEachIndexed { i, n ->
                addView(RadioButton(ctx).apply {
                    id = View.generateViewId()
                    text = n
                    tag = i
                    setTextColor(0xFFF2F2F5.toInt())
                    if (i == checked) isChecked = true
                })
            }
        }

    private fun RadioGroup.selectedIndex(): Int {
        val v = findViewById<View>(checkedRadioButtonId) ?: return 0
        return v.tag as? Int ?: 0
    }

    fun input(ctx: Context, title: String, value: String, onOk: (String) -> Unit) {
        val et = EditText(ctx).apply {
            setText(value)
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(60))
        }
        val box = LinearLayout(ctx).apply {
            setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0)
            addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> val t = et.text.toString().trim(); if (t.isNotEmpty()) onOk(t) }
            .setNegativeButton("Cancelar", null)
            .show()
        et.requestFocus()
    }

    /** Nome + cor da cena. */
    fun scene(ctx: Context, title: String, name: String, color: Int, okText: String, onOk: (String, Int) -> Unit) {
        var chosen = color
        val et = EditText(ctx).apply {
            setText(name)
            setSelectAllOnFocus(true)
            hint = "Nome da cena"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(60))
        }
        val dots = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val dotViews = mutableListOf<View>()
        fun paint() {
            dotViews.forEachIndexed { i, v ->
                (v.background as GradientDrawable).setStroke(
                    ctx.dp(3), if (SCENE_COLORS[i] == chosen) 0xFFFFFFFF.toInt() else 0x00000000
                )
            }
        }
        SCENE_COLORS.forEachIndexed { i, c ->
            val v = View(ctx).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
                setOnClickListener { chosen = SCENE_COLORS[i]; paint() }
            }
            dotViews.add(v)
            dots.addView(v, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)).apply { marginEnd = ctx.dp(8) })
        }
        paint()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0)
            addView(et)
            addView(label(ctx, "Cor (para organizar: ex. vermelho = Ato 1)"))
            addView(HorizontalScrollView(ctx).apply { addView(dots) })
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(okText) { _, _ ->
                val t = et.text.toString().trim().ifEmpty { name }
                onOk(t, chosen)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Texto em cima da cena (na montagem). */
    fun text(ctx: Context, item: SeqItem, sceneName: String, index: Int, onSave: () -> Unit) {
        val et = EditText(ctx).apply {
            setText(item.text)
            hint = "Ex.: Cena ${index + 1} – $sceneName"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(60))
        }
        val quick = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            fun chip(t: String, value: String) = TextView(ctx).apply {
                text = t
                setTextColor(0xFF22D3EE.toInt())
                textSize = 13f
                setPadding(0, ctx.dp(6), ctx.dp(16), ctx.dp(6))
                setOnClickListener { et.setText(value); et.setSelection(et.text.length) }
            }
            addView(chip("Cena ${index + 1} – nome", "Cena ${index + 1} – $sceneName"))
            addView(chip("Só o nome", sceneName))
        }
        val pos = radios(ctx, arrayOf("Topo", "Centro", "Embaixo"), item.pos)
        val size = radios(ctx, TextStyle.SIZE_NAMES, item.size)
        val colorIdx = TextStyle.COLORS.indexOf(item.color).let { if (it < 0) 0 else it }
        val color = radios(ctx, TextStyle.COLOR_NAMES, colorIdx)
        val secsIdx = TextStyle.SECS.indexOf(item.secs).let { if (it < 0) 1 else it }
        val secs = radios(ctx, TextStyle.SECS_NAMES, secsIdx)
        val bg = CheckBox(ctx).apply {
            text = "Fundo escuro atrás do texto"
            isChecked = item.bg
            setTextColor(0xFFF2F2F5.toInt())
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), ctx.dp(8))
            addView(et)
            addView(quick)
            addView(label(ctx, "Posição"))
            addView(HorizontalScrollView(ctx).apply { addView(pos) })
            addView(label(ctx, "Tamanho"))
            addView(HorizontalScrollView(ctx).apply { addView(size) })
            addView(label(ctx, "Cor"))
            addView(HorizontalScrollView(ctx).apply { addView(color) })
            addView(label(ctx, "Quanto tempo aparece"))
            addView(HorizontalScrollView(ctx).apply { addView(secs) })
            addView(bg)
        }
        val scroll = ScrollView(ctx).apply { addView(col) }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Texto da cena ${index + 1}")
            .setView(scroll)
            .setPositiveButton("Salvar") { _, _ ->
                item.text = et.text.toString().trim()
                item.pos = pos.selectedIndex()
                item.size = size.selectedIndex()
                item.color = TextStyle.COLORS[color.selectedIndex()]
                item.secs = TextStyle.SECS[secs.selectedIndex()]
                item.bg = bg.isChecked
                onSave()
            }
            .setNeutralButton("Sem texto") { _, _ -> item.text = ""; onSave() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun confirm(ctx: Context, title: String, msg: String, ok: String, onOk: () -> Unit) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(ok) { _, _ -> onOk() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun centerText(ctx: Context, s: String) = TextView(ctx).apply {
        text = s
        gravity = Gravity.CENTER
        setTextColor(0xFFF2F2F5.toInt())
        textSize = 15f
    }
}
