package aa.projek.mp.max

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Titik pada layar, koordinat 0..1 relatif ukuran layar (tidak tergantung resolusi). */
class Pt(var x: Float, var y: Float)

class KeyBtn(var key: Int, var x: Float, var y: Float, var label: String)

class Profile(
    var name: String = "Profil 1",
    // joystick WASD
    var joy: Pt = Pt(0.16f, 0.70f),
    var joyRadius: Float = 0.11f,          // relatif tinggi layar
    var kUp: Int = KeyEvent.KEYCODE_W,
    var kLeft: Int = KeyEvent.KEYCODE_A,
    var kDown: Int = KeyEvent.KEYCODE_S,
    var kRight: Int = KeyEvent.KEYCODE_D,
    // kamera (mouse look)
    var look: Pt = Pt(0.68f, 0.45f),
    var sens: Float = 1.6f,
    // tombol mouse
    var fire: Pt = Pt(0.88f, 0.70f),       // klik kiri
    var aim: Pt = Pt(0.80f, 0.58f),        // klik kanan
    var buttons: MutableList<KeyBtn> = mutableListOf(
        KeyBtn(KeyEvent.KEYCODE_SPACE, 0.92f, 0.50f, "Lompat"),
        KeyBtn(KeyEvent.KEYCODE_R, 0.80f, 0.85f, "Isi ulang"),
        KeyBtn(KeyEvent.KEYCODE_E, 0.72f, 0.72f, "Aksi"),
        KeyBtn(KeyEvent.KEYCODE_SHIFT_LEFT, 0.10f, 0.45f, "Lari"),
        KeyBtn(KeyEvent.KEYCODE_C, 0.92f, 0.85f, "Jongkok")
    )
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("joy", pt(joy)).put("joyRadius", joyRadius.toDouble())
        .put("keys", JSONArray(listOf(kUp, kLeft, kDown, kRight)))
        .put("look", pt(look)).put("sens", sens.toDouble())
        .put("fire", pt(fire)).put("aim", pt(aim))
        .put("buttons", JSONArray().also { a ->
            for (b in buttons) a.put(JSONObject().put("key", b.key).put("x", b.x.toDouble()).put("y", b.y.toDouble()).put("label", b.label))
        })

    companion object {
        private fun pt(p: Pt) = JSONObject().put("x", p.x.toDouble()).put("y", p.y.toDouble())
        private fun rpt(o: JSONObject, d: Pt) = Pt(o.optDouble("x", d.x.toDouble()).toFloat().coerceIn(0f, 1f), o.optDouble("y", d.y.toDouble()).toFloat().coerceIn(0f, 1f))

        fun fromJson(o: JSONObject): Profile {
            val d = Profile()
            val p = Profile(
                name = o.optString("name", d.name),
                joy = o.optJSONObject("joy")?.let { rpt(it, d.joy) } ?: d.joy,
                joyRadius = o.optDouble("joyRadius", d.joyRadius.toDouble()).toFloat().coerceIn(0.03f, 0.3f),
                look = o.optJSONObject("look")?.let { rpt(it, d.look) } ?: d.look,
                sens = o.optDouble("sens", d.sens.toDouble()).toFloat().coerceIn(0.2f, 6f),
                fire = o.optJSONObject("fire")?.let { rpt(it, d.fire) } ?: d.fire,
                aim = o.optJSONObject("aim")?.let { rpt(it, d.aim) } ?: d.aim
            )
            o.optJSONArray("keys")?.let { if (it.length() == 4) { p.kUp = it.getInt(0); p.kLeft = it.getInt(1); p.kDown = it.getInt(2); p.kRight = it.getInt(3) } }
            o.optJSONArray("buttons")?.let { a ->
                p.buttons = mutableListOf()
                for (i in 0 until a.length()) {
                    val b = a.getJSONObject(i)
                    p.buttons.add(KeyBtn(b.getInt("key"), b.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f), b.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f), b.optString("label", "")))
                }
            }
            return p
        }

        private fun file(ctx: Context) = File(ctx.filesDir, "profile.json")

        fun load(ctx: Context): Profile = try {
            val f = file(ctx)
            if (f.exists()) fromJson(JSONObject(f.readText())) else Profile()
        } catch (_: Exception) { Profile() }

        fun save(ctx: Context, p: Profile) {
            val f = file(ctx)
            val tmp = File(f.parentFile, "profile.json.tmp")
            tmp.writeText(p.toJson().toString())
            tmp.renameTo(f)
        }
    }
}
