package aa.projek.mp.max

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * Jendela overlay layar penuh yang memegang fokus + pointer capture, sehingga mouse OTG
 * dikirim sebagai gerakan relatif (kursor sistem disembunyikan) dan keyboard masuk ke sini.
 * Sentuhan layar asli tetap tembus ke game (jendela ini FLAG_NOT_TOUCHABLE).
 */
class CaptureView(context: Context, private val sink: Sink) : View(context) {

    interface Sink {
        fun onMouseMove(dx: Float, dy: Float)
        fun onMouseButton(button: Int, down: Boolean)
        fun onScroll(v: Float)
        fun onKey(code: Int, down: Boolean, repeat: Boolean): Boolean
    }

    private var lastButtons = 0

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        focusable = FOCUSABLE
        setOnCapturedPointerListener { _, e -> handleMouse(e); true }
    }

    private fun handleMouse(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                val dx = e.x
                val dy = e.y
                if (dx != 0f || dy != 0f) {
                    Log.i(TAG, "CAP_MOVE $dx $dy")
                    sink.onMouseMove(dx, dy)
                }
            }
            MotionEvent.ACTION_SCROLL -> sink.onScroll(e.getAxisValue(MotionEvent.AXIS_VSCROLL))
        }
        val now = e.buttonState
        val changed = now xor lastButtons
        if (changed != 0) {
            for (b in intArrayOf(MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_TERTIARY,
                MotionEvent.BUTTON_BACK, MotionEvent.BUTTON_FORWARD)) {
                if (changed and b != 0) {
                    Log.i(TAG, "CAP_BTN $b ${now and b != 0}")
                    sink.onMouseButton(b, now and b != 0)
                }
            }
            lastButtons = now
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        Log.i(TAG, "FOCUS $hasWindowFocus")
        if (hasWindowFocus) {
            requestFocus()
            requestPointerCapture()
        }
    }

    override fun onPointerCaptureChange(hasCapture: Boolean) {
        super.onPointerCaptureChange(hasCapture)
        Log.i(TAG, "CAPTURE $hasCapture")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Log.i(TAG, "KEY ${event.keyCode} ${event.action}")
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
            if (sink.onKey(event.keyCode, down, event.repeatCount > 0)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val TAG = "AAOTG"
    }
}
