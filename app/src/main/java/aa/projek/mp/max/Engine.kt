package aa.projek.mp.max

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.hypot

/**
 * Mengubah keyboard + mouse (dari CaptureView) menjadi sentuhan multi-jari lewat Injector.
 * ID jari: 0 joystick, 1 kamera, 2 tembak (klik kiri), 3 bidik (klik kanan), 4+ tombol.
 */
class Engine(private val ctx: Context, var profile: Profile, private val screen: () -> Pair<Int, Int>) : CaptureView.Sink {

    var onToggle: (() -> Unit)? = null
    private val h = Handler(Looper.getMainLooper())
    private val pressed = HashSet<Int>()
    private var joyDown = false
    private var lookDown = false
    private var lx = 0f
    private var ly = 0f
    private val firedBtn = HashMap<Int, Int>()      // keyCode -> id jari
    private val releaseLook = Runnable { liftLook() }

    private fun px(p: Pt): Pair<Float, Float> { val (w, hh) = screen(); return p.x * w to p.y * hh }
    private fun send(s: String) = AdbHub.send(s)

    // ---- keyboard
    override fun onKey(code: Int, down: Boolean, repeat: Boolean): Boolean {
        val p = profile
        if (code == KeyEvent.KEYCODE_F8) {
            if (down && !repeat) onToggle?.invoke()
            return true
        }
        if (code == p.kUp || code == p.kLeft || code == p.kDown || code == p.kRight) {
            if (down) pressed.add(code) else pressed.remove(code)
            updateJoy()
            return true
        }
        val idx = p.buttons.indexOfFirst { it.key == code }
        if (idx >= 0) {
            val b = p.buttons[idx]
            val id = 4 + idx
            if (down && !repeat) {
                val (x, y) = px(Pt(b.x, b.y)); send("d $id ${x.toInt()} ${y.toInt()}"); firedBtn[code] = id
            } else if (!down) {
                if (firedBtn.remove(code) != null) send("u $id")
            }
            return true
        }
        return false
    }

    private fun updateJoy() {
        val p = profile
        val vx = (if (p.kRight in pressed) 1 else 0) - (if (p.kLeft in pressed) 1 else 0)
        val vy = (if (p.kDown in pressed) 1 else 0) - (if (p.kUp in pressed) 1 else 0)
        val (cx, cy) = px(p.joy)
        if (vx == 0 && vy == 0) {
            if (joyDown) { send("u 0"); joyDown = false }
            return
        }
        val len = hypot(vx.toFloat(), vy.toFloat())
        val r = p.joyRadius * screen().second
        val tx = cx + vx / len * r
        val ty = cy + vy / len * r
        if (!joyDown) { send("d 0 ${cx.toInt()} ${cy.toInt()}"); joyDown = true }
        send("m 0 ${tx.toInt()} ${ty.toInt()}")
    }

    // ---- mouse
    override fun onMouseMove(dx: Float, dy: Float) {
        val (w, hh) = screen()
        val (cx, cy) = px(profile.look)
        if (!lookDown) { lx = cx; ly = cy; send("d 1 ${lx.toInt()} ${ly.toInt()}"); lookDown = true }
        lx += dx * profile.sens
        ly += dy * profile.sens
        // Terlalu jauh dari pusat: angkat jari, mulai lagi dari pusat (kamera tidak "habis" di tepi layar).
        if (kotlin.math.abs(lx - cx) > w * 0.28f || kotlin.math.abs(ly - cy) > hh * 0.30f) {
            send("u 1"); lx = cx; ly = cy; send("d 1 ${lx.toInt()} ${ly.toInt()}")
        }
        send("m 1 ${lx.toInt()} ${ly.toInt()}")
        h.removeCallbacks(releaseLook)
        h.postDelayed(releaseLook, 160)
    }

    private fun liftLook() {
        if (lookDown) { send("u 1"); lookDown = false }
    }

    override fun onMouseButton(button: Int, down: Boolean) {
        val (id, pt) = when (button) {
            MotionEvent.BUTTON_PRIMARY -> 2 to profile.fire
            MotionEvent.BUTTON_SECONDARY -> 3 to profile.aim
            else -> return
        }
        if (down) { val (x, y) = px(pt); send("d $id ${x.toInt()} ${y.toInt()}") } else send("u $id")
    }

    override fun onScroll(v: Float) {}

    fun releaseAll() {
        h.removeCallbacks(releaseLook)
        pressed.clear(); joyDown = false; lookDown = false; firedBtn.clear()
        send("c")
    }
}
