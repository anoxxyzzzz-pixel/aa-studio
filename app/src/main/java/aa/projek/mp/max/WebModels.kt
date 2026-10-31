package aa.projek.mp.max

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Satu panel Web Source. Posisi/ukuran dalam pecahan layar (0..1) agar tidak tergantung resolusi. */
data class SourceItem(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Web Source",
    var url: String = "",
    var x: Float = 0.55f,
    var y: Float = 0.08f,
    var w: Float = 0.40f,
    var h: Float = 0.45f,
    var visible: Boolean = true,
    var muted: Boolean = false,
    var volume: Int = 100
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("url", url)
        .put("x", x.toDouble()).put("y", y.toDouble()).put("w", w.toDouble()).put("h", h.toDouble())
        .put("visible", visible).put("muted", muted).put("volume", volume)

    companion object {
        fun fromJson(o: JSONObject) = SourceItem(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", "Web Source"),
            url = o.optString("url", ""),
            x = o.optDouble("x", 0.55).toFloat().coerceIn(0f, 0.95f),
            y = o.optDouble("y", 0.08).toFloat().coerceIn(0f, 0.95f),
            w = o.optDouble("w", 0.4).toFloat().coerceIn(0.1f, 1f),
            h = o.optDouble("h", 0.45).toFloat().coerceIn(0.1f, 1f),
            visible = o.optBoolean("visible", true),
            muted = o.optBoolean("muted", false),
            volume = o.optInt("volume", 100).coerceIn(0, 100)
        )
    }
}

/** Pengaturan suara umum untuk semua panel (dipakai SourceCell). */
data class AudioSettings(
    var volume: Int = 100,
    var muted: Boolean = false,
    var autoplay: Boolean = true,
    var webGain: Int = 2          // penguat suara web x1..x4
)

object WebStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "web.json")

    fun load(ctx: Context): MutableList<SourceItem> = try {
        val f = file(ctx)
        if (!f.exists()) mutableListOf() else {
            val a = JSONArray(f.readText())
            MutableList(a.length()) { SourceItem.fromJson(a.getJSONObject(it)) }
        }
    } catch (_: Exception) { mutableListOf() }

    fun save(ctx: Context, items: List<SourceItem>) {
        val f = file(ctx)
        val tmp = File(f.parentFile, "web.json.tmp")
        tmp.writeText(JSONArray().also { a -> items.forEach { a.put(it.toJson()) } }.toString())
        tmp.renameTo(f)
    }
}
