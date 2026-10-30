package aa.projek.mp.max

import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Proses server kecil. Dijalankan lewat ADB sebagai user shell:
 *   CLASSPATH=<apk> app_process /system/bin aa.projek.mp.max.Injector
 * Membaca perintah dari stdin dan menyuntikkan sentuhan multi-jari ke sistem.
 * Protokol (satu baris per perintah, koordinat piksel):
 *   d <id> <x> <y>   jari id turun     m <id> <x> <y>   jari id bergerak
 *   u <id>           jari id naik      c                semua jari naik     q   keluar
 */
object Injector {
    private var im: Any? = null
    private var inject: java.lang.reflect.Method? = null
    private val ids = IntArray(10) { -1 }       // slot -> id jari
    private val xs = FloatArray(10)
    private val ys = FloatArray(10)
    private var count = 0
    private var downTime = 0L

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "input") as IBinder
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            im = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            inject = im!!.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            println("READY uid=" + android.os.Process.myUid())
        } catch (t: Throwable) {
            println("ERR init " + t)
            return
        }
        val r = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            val line = r.readLine() ?: break
            try {
                val p = line.trim().split(' ')
                when (p[0]) {
                    "d" -> down(p[1].toInt(), p[2].toFloat(), p[3].toFloat())
                    "m" -> move(p[1].toInt(), p[2].toFloat(), p[3].toFloat())
                    "u" -> up(p[1].toInt())
                    "c" -> while (count > 0) up(ids[0])
                    "q" -> break
                }
            } catch (t: Throwable) {
                println("ERR " + line + " " + t)
            }
        }
        while (count > 0) up(ids[0])
    }

    private fun slotOf(id: Int): Int {
        for (i in 0 until count) if (ids[i] == id) return i
        return -1
    }

    private fun send(action: Int, actionIndex: Int) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val props = Array(count) { MotionEvent.PointerProperties().also { p -> p.id = ids[it]; p.toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(count) { MotionEvent.PointerCoords().also { c -> c.x = xs[it]; c.y = ys[it]; c.pressure = 1f; c.size = 1f } }
        val act = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)
            action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        val e = MotionEvent.obtain(downTime, now, act, count, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0)
        inject!!.invoke(im, e, 0)
        e.recycle()
    }

    private fun down(id: Int, x: Float, y: Float) {
        if (slotOf(id) >= 0 || count >= 10) return
        ids[count] = id; xs[count] = x; ys[count] = y; count++
        send(if (count == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN, count - 1)
    }

    private fun move(id: Int, x: Float, y: Float) {
        val s = slotOf(id)
        if (s < 0) return
        xs[s] = x; ys[s] = y
        send(MotionEvent.ACTION_MOVE, 0)
    }

    private fun up(id: Int) {
        val s = slotOf(id)
        if (s < 0) return
        send(if (count == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP, s)
        for (i in s until count - 1) { ids[i] = ids[i + 1]; xs[i] = xs[i + 1]; ys[i] = ys[i + 1] }
        count--
    }
}
