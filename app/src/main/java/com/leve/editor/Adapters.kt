package com.leve.editor

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProjectsAdapter(
    private val onOpen: (Project) -> Unit,
    private val onMore: (Project, View) -> Unit
) : RecyclerView.Adapter<ProjectsAdapter.VH>() {

    var items: List<Project> = emptyList()
        @SuppressLint("NotifyDataSetChanged")
        set(v) { field = v; notifyDataSetChanged() }

    private val df = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvName)
        val info: TextView = v.findViewById(R.id.tvInfo)
        val more: View = v.findViewById(R.id.btnMore)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_project, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val p = items[i]
        h.name.text = p.name
        h.info.text = "${p.scenes.size} cenas • montagem ${p.seq.size} • filme ${Fmt.time(p.durationMs, false)} • ${df.format(Date(p.updated))}"
        h.itemView.setOnClickListener { onOpen(p) }
        h.more.setOnClickListener { onMore(p, it) }
    }
}

class ScenesAdapter(
    private val project: Project,
    private val onTap: (Scene) -> Unit,
    private val onAdd: (Scene) -> Unit,
    private val onMore: (Scene, View) -> Unit
) : RecyclerView.Adapter<ScenesAdapter.VH>() {

    private var items: List<Scene> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun refresh() {
        items = project.sortedScenes()
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val bar: View = v.findViewById(R.id.colorBar)
        val thumb: ImageView = v.findViewById(R.id.ivThumb)
        val name: TextView = v.findViewById(R.id.tvName)
        val info: TextView = v.findViewById(R.id.tvInfo)
        val add: View = v.findViewById(R.id.btnAdd)
        val more: View = v.findViewById(R.id.btnMore)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_scene, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val s = items[i]
        val ctx = h.itemView.context
        h.bar.setBackgroundColor(s.color)
        h.name.text = "${i + 1}. ${s.name}"
        val used = project.seq.count { it.sceneId == s.id }
        h.info.text = "${Fmt.time(s.startMs)} → ${Fmt.time(s.endMs)} • ${Fmt.dur(s.durMs)}" +
            if (used > 0) " • na montagem" else ""
        h.thumb.setImageBitmap(ThumbCache.get(Store.thumbFile(ctx, project.id, s.id)))
        h.itemView.setOnClickListener { onTap(s) }
        h.add.setOnClickListener { onAdd(s) }
        h.more.setOnClickListener { onMore(s, it) }
    }
}

class SeqAdapter(
    private val project: Project,
    private val onTap: (Int) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<SeqAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val handle: View = v.findViewById(R.id.handle)
        val num: TextView = v.findViewById(R.id.tvNum)
        val thumb: ImageView = v.findViewById(R.id.ivThumb)
        val name: TextView = v.findViewById(R.id.tvName)
        val text: TextView = v.findViewById(R.id.tvText)
        val del: View = v.findViewById(R.id.btnDel)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_seq, p, false))

    override fun getItemCount() = project.seq.size

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(h: VH, i: Int) {
        val item = project.seq[i]
        val s = project.scene(item.sceneId)
        val ctx = h.itemView.context
        h.num.text = "${i + 1}"
        h.name.text = s?.name ?: "(cena apagada)"
        if (item.text.isNotBlank()) {
            h.text.text = "T  “${item.text}”"
            h.text.setTextColor(0xFF22D3EE.toInt())
        } else {
            h.text.text = "${Fmt.dur(s?.durMs ?: 0)} • toque para pôr texto"
            h.text.setTextColor(0xFF9A9AA5.toInt())
        }
        h.thumb.setImageBitmap(s?.let { ThumbCache.get(Store.thumbFile(ctx, project.id, it.id)) })
        h.itemView.setOnClickListener { val p = h.bindingAdapterPosition; if (p >= 0) onTap(p) }
        h.del.setOnClickListener { val p = h.bindingAdapterPosition; if (p >= 0) onDelete(p) }
        h.handle.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(h)
            false
        }
    }
}
