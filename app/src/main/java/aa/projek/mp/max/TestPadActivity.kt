package aa.projek.mp.max

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/** Layar uji: menampilkan semua jari yang menyentuh (termasuk sentuhan hasil injeksi). */
class TestPadActivity : Activity() {
    private lateinit var pad: Pad

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pad = Pad()
        setContentView(pad)
    }

    inner class Pad : View(this@TestPadActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var pts: List<Triple<Int, Float, Float>> = emptyList()
        private var maxPointers = 0
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val l = ArrayList<Triple<Int, Float, Float>>()
            if (e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL) {
                for (i in 0 until e.pointerCount) l.add(Triple(e.getPointerId(i), e.getX(i), e.getY(i)))
            }
            pts = l
            if (e.pointerCount > maxPointers) maxPointers = e.pointerCount
            Log.i("AAOTG", "TP action=${e.actionMasked} pointers=${e.pointerCount} max=$maxPointers ids=${(0 until e.pointerCount).map { e.getPointerId(it) }}")
            invalidate()
            return true
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.parseColor("#101418"))
            paint.color = Color.WHITE; paint.textSize = 42f
            c.drawText("Layar uji sentuhan: sentuhan/injeksi tampil di sini", 40f, 80f, paint)
            c.drawText("Jari aktif: ${pts.size}   (maks ${maxPointers})", 40f, 140f, paint)
            for ((id, x, y) in pts) {
                paint.color = intArrayOf(Color.CYAN, Color.YELLOW, Color.RED, Color.GREEN, Color.MAGENTA)[id % 5]
                c.drawCircle(x, y, 60f, paint)
                paint.color = Color.BLACK; c.drawText("$id", x - 12f, y + 14f, paint)
            }
        }
    }
}
