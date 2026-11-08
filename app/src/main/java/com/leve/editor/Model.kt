package com.leve.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

val SCENE_COLORS = intArrayOf(
    0xFF22D3EE.toInt(), // ciano
    0xFFF43F5E.toInt(), // vermelho
    0xFFFACC15.toInt(), // amarelo
    0xFF4ADE80.toInt(), // verde
    0xFFA78BFA.toInt(), // roxo
    0xFFFB923C.toInt(), // laranja
    0xFFF472B6.toInt(), // rosa
    0xFFE5E7EB.toInt()  // branco
)

fun newId(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 12)

/** Uma cena = um trecho do filme (não copia o vídeo, só guarda início e fim). */
class Scene(
    val id: String,
    var name: String,
    var startMs: Long,
    var endMs: Long,
    var color: Int
) {
    val durMs: Long get() = endMs - startMs

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name)
        .put("start", startMs).put("end", endMs).put("color", color)

    companion object {
        fun from(o: JSONObject) = Scene(
            o.getString("id"), o.optString("name", "Cena"),
            o.getLong("start"), o.getLong("end"),
            o.optInt("color", SCENE_COLORS[0])
        )
    }
}

/** Um item da montagem final (aponta para uma cena + texto opcional). */
class SeqItem(
    val uid: String,
    val sceneId: String,
    var text: String = "",
    var pos: Int = TextStyle.POS_BOTTOM,
    var size: Int = 1,
    var color: Int = Color.WHITE,
    var bg: Boolean = true,
    var secs: Int = 3          // 0 = a cena inteira
) {
    fun toJson(): JSONObject = JSONObject()
        .put("uid", uid).put("scene", sceneId).put("text", text)
        .put("pos", pos).put("size", size).put("color", color)
        .put("bg", bg).put("secs", secs)

    companion object {
        fun from(o: JSONObject) = SeqItem(
            o.getString("uid"), o.getString("scene"), o.optString("text", ""),
            o.optInt("pos", TextStyle.POS_BOTTOM), o.optInt("size", 1),
            o.optInt("color", Color.WHITE), o.optBoolean("bg", true), o.optInt("secs", 3)
        )
    }
}

class Project(
    val id: String,
    var name: String,
    val videoUri: String,
    var durationMs: Long,
    var videoW: Int = 1920,
    var videoH: Int = 1080,
    var lastPosMs: Long = 0,
    var updated: Long = System.currentTimeMillis(),
    val scenes: MutableList<Scene> = mutableListOf(),
    val seq: MutableList<SeqItem> = mutableListOf()
) {
    fun scene(id: String): Scene? = scenes.firstOrNull { it.id == id }
    fun sortedScenes(): List<Scene> = scenes.sortedBy { it.startMs }
    fun seqDurationMs(): Long = seq.sumOf { scene(it.sceneId)?.durMs ?: 0L }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("uri", videoUri)
        .put("duration", durationMs).put("w", videoW).put("h", videoH)
        .put("last", lastPosMs).put("updated", updated)
        .put("scenes", JSONArray().apply { scenes.forEach { put(it.toJson()) } })
        .put("seq", JSONArray().apply { seq.forEach { put(it.toJson()) } })

    companion object {
        fun from(o: JSONObject): Project {
            val p = Project(
                o.getString("id"), o.optString("name", "Projeto"), o.getString("uri"),
                o.optLong("duration", 0), o.optInt("w", 1920), o.optInt("h", 1080),
                o.optLong("last", 0), o.optLong("updated", 0)
            )
            o.optJSONArray("scenes")?.let { a -> for (i in 0 until a.length()) p.scenes.add(Scene.from(a.getJSONObject(i))) }
            o.optJSONArray("seq")?.let { a -> for (i in 0 until a.length()) p.seq.add(SeqItem.from(a.getJSONObject(i))) }
            // limpa itens que apontam para cenas apagadas
            p.seq.removeAll { s -> p.scene(s.sceneId) == null }
            return p
        }
    }
}

object Store {
    private fun dir(ctx: Context) = File(ctx.filesDir, "projects").apply { mkdirs() }

    fun thumbFile(ctx: Context, pid: String, sid: String): File =
        File(File(ctx.filesDir, "thumbs/$pid").apply { mkdirs() }, "$sid.jpg")

    fun save(ctx: Context, p: Project) {
        p.updated = System.currentTimeMillis()
        val d = dir(ctx)
        val tmp = File(d, "${p.id}.tmp")
        tmp.writeText(p.toJson().toString())
        val f = File(d, "${p.id}.json")
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
    }

    fun load(ctx: Context, id: String): Project? = try {
        Project.from(JSONObject(File(dir(ctx), "$id.json").readText()))
    } catch (e: Exception) { null }

    fun all(ctx: Context): List<Project> =
        (dir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f -> try { Project.from(JSONObject(f.readText())) } catch (e: Exception) { null } }
            .sortedByDescending { it.updated }

    fun delete(ctx: Context, id: String) {
        File(dir(ctx), "$id.json").delete()
        File(ctx.filesDir, "thumbs/$id").deleteRecursively()
    }
}

/** Cache das miniaturas das cenas (arquivos jpg pequenos). */
object ThumbCache {
    private val cache = object : LruCache<String, Bitmap>(6 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun get(f: File): Bitmap? {
        if (!f.exists()) return null
        val key = f.path + ":" + f.lastModified()
        cache.get(key)?.let { return it }
        val b = BitmapFactory.decodeFile(f.path) ?: return null
        cache.put(key, b)
        return b
    }
}

object Fmt {
    fun time(ms: Long, tenths: Boolean = true): String {
        val t = if (ms < 0) 0 else ms
        val h = t / 3_600_000
        val m = (t / 60_000) % 60
        val s = (t / 1000) % 60
        val d = (t % 1000) / 100
        val base = if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
        return if (tenths) "$base.$d" else base
    }

    fun dur(ms: Long): String =
        if (ms < 60_000) String.format("%.1fs", ms / 1000f) else time(ms, false)
}

object TextStyle {
    const val POS_TOP = 0
    const val POS_CENTER = 1
    const val POS_BOTTOM = 2

    val SIZE_NAMES = arrayOf("Pequeno", "Médio", "Grande")
    private val SIZE_FACTORS = floatArrayOf(0.05f, 0.075f, 0.11f)
    fun sizeFactor(size: Int) = SIZE_FACTORS[size.coerceIn(0, 2)]

    val COLOR_NAMES = arrayOf("Branco", "Amarelo", "Ciano", "Preto")
    val COLORS = intArrayOf(Color.WHITE, 0xFFFACC15.toInt(), 0xFF22D3EE.toInt(), Color.BLACK)

    val SECS = intArrayOf(2, 3, 5, 0)
    val SECS_NAMES = arrayOf("2s", "3s", "5s", "Cena toda")

    val BG_COLOR = 0x99000000.toInt()
}
