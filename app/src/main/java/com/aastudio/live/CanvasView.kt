package com.aastudio.live

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Kanvas editor 16:9 (1920x1080 satuan logis). Mendukung:
 * - ketuk untuk memilih elemen, seret untuk memindahkan
 * - handle di empat sudut untuk mengubah ukuran
 * - cubit dua jari untuk zoom, geser dua jari / seret area kosong untuk pan
 * Semua perubahan memakai koordinat logis sehingga posisi stabil saat layar/orientasi berubah.
 */
class CanvasView(context: Context) : View(context) {

    interface Listener {
        fun onSelect(id: String?)
        /** Dipanggil sekali sebelum perubahan pertama dalam satu gestur (untuk undo). */
        fun onEditBegin()
        fun onEdited()
        fun onEditEnd()
    }

    var listener: Listener? = null

    var items: List<SourceItem> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var selectedId: String? = null
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private enum class Mode { NONE, DRAG, RESIZE, PAN, IGNORE }

    private var mode = Mode.NONE
    private var active: SourceItem? = null
    private var corner = 0
    private var downVX = 0f
    private var downVY = 0f
    private var lastVX = 0f
    private var lastVY = 0f
    private var startLX = 0f
    private var startLY = 0f
    private var startX = 0
    private var startY = 0
    private var startW = 0
    private var startH = 0
    private var offLX = 0f
    private var offLY = 0f
    private var begun = false
    private var multiActive = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private val slop = 6f * density

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldS = scale()
                val cx = (detector.focusX - originX()) / oldS
                val cy = (detector.focusY - originY()) / oldS
                zoom = (zoom * detector.scaleFactor).coerceIn(0.5f, 5f)
                val newS = scale()
                panX = detector.focusX - cx * newS - (width - CANVAS_W * newS) / 2f
                panY = detector.focusY - cy * newS - (height - CANVAS_H * newS) / 2f
                invalidate()
                return true
            }
        }
    )

    init {
        scaleDetector.isQuickScaleEnabled = false
    }

    fun resetView() {
        zoom = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    private fun baseScale(): Float {
        val m = 12f * density
        val sw = (width - 2 * m) / CANVAS_W
        val sh = (height - 2 * m) / CANVAS_H
        return min(sw, sh).coerceAtLeast(0.0001f)
    }

    private fun scale(): Float = baseScale() * zoom
    private fun originX(): Float = (width - CANVAS_W * scale()) / 2f + panX
    private fun originY(): Float = (height - CANVAS_H * scale()) / 2f + panY
    private fun toLX(vx: Float): Float = (vx - originX()) / scale()
    private fun toLY(vy: Float): Float = (vy - originY()) / scale()

    // ---------- Gambar ----------

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.parseColor("#E6E6E6"))
        val s = scale()
        val ox = originX()
        val oy = originY()

        rect.set(ox, oy, ox + CANVAS_W * s, oy + CANVAS_H * s)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.pathEffect = null
        c.drawRect(rect, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.parseColor("#EEEEEE")
        for (i in 1..2) {
            val gx = ox + CANVAS_W * s * i / 3f
            c.drawLine(gx, oy, gx, oy + CANVAS_H * s, paint)
            val gy = oy + CANVAS_H * s * i / 3f
            c.drawLine(ox, gy, ox + CANVAS_W * s, gy, paint)
        }

        for (item in items) drawItem(c, item, s, ox, oy)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = Color.parseColor("#9A9A9A")
        paint.pathEffect = null
        rect.set(ox, oy, ox + CANVAS_W * s, oy + CANVAS_H * s)
        c.drawRect(rect, paint)

        val sel = items.firstOrNull { it.id == selectedId }
        if (sel != null && sel.visible && !sel.locked) drawHandles(c, sel, s, ox, oy)
    }

    private fun drawItem(c: Canvas, item: SourceItem, s: Float, ox: Float, oy: Float) {
        val l = ox + item.x * s
        val t = oy + item.y * s
        val r = ox + (item.x + item.w) * s
        val b = oy + (item.y + item.h) * s
        rect.set(l, t, r, b)
        val selected = item.id == selectedId

        if (item.visible) {
            paint.style = Paint.Style.FILL
            paint.pathEffect = null
            paint.color = Color.argb(if (selected) 36 else 20, 17, 17, 17)
            c.drawRect(rect, paint)
        }

        paint.style = Paint.Style.STROKE
        if (item.visible) {
            paint.pathEffect = null
            paint.strokeWidth = (if (selected) 2.5f else 1.2f) * density
            paint.color = if (selected) Ui.ACCENT else Ui.BLACK
        } else {
            paint.pathEffect = DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
            paint.strokeWidth = 1.2f * density
            paint.color = Color.parseColor("#AAAAAA")
        }
        c.drawRect(rect, paint)
        paint.pathEffect = null

        c.save()
        c.clipRect(rect)
        textPaint.color = if (item.visible) Ui.BLACK else Color.parseColor("#999999")
        textPaint.textSize = 13f * density
        textPaint.isFakeBoldText = true
        val title = item.name + (if (item.locked) "  [kunci]" else "") + (if (!item.visible) "  [sembunyi]" else "")
        c.drawText(title, l + 6f * density, t + 18f * density, textPaint)
        textPaint.isFakeBoldText = false
        textPaint.textSize = 11f * density
        textPaint.color = Ui.MUTED
        val sub = if (item.url.isBlank()) "(URL kosong)" else UrlUtil.hostOf(item.url)
        c.drawText(sub, l + 6f * density, t + 34f * density, textPaint)
        c.restore()
    }

    private fun drawHandles(c: Canvas, item: SourceItem, s: Float, ox: Float, oy: Float) {
        val half = 7f * density
        for (i in 0..3) {
            val p = cornerView(item, i, s, ox, oy)
            rect.set(p[0] - half, p[1] - half, p[0] + half, p[1] + half)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            c.drawRect(rect, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            paint.color = Ui.ACCENT
            c.drawRect(rect, paint)
        }
    }

    private fun cornerView(item: SourceItem, i: Int, s: Float, ox: Float, oy: Float): FloatArray {
        val left = ox + item.x * s
        val top = oy + item.y * s
        val right = ox + (item.x + item.w) * s
        val bottom = oy + (item.y + item.h) * s
        return when (i) {
            0 -> floatArrayOf(left, top)
            1 -> floatArrayOf(right, top)
            2 -> floatArrayOf(left, bottom)
            else -> floatArrayOf(right, bottom)
        }
    }

    private fun hitCorner(item: SourceItem, vx: Float, vy: Float): Int {
        val s = scale()
        val ox = originX()
        val oy = originY()
        val tol = 26f * density
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in 0..3) {
            val p = cornerView(item, i, s, ox, oy)
            val d = hypot(vx - p[0], vy - p[1])
            if (d <= tol && d < bestD) {
                best = i
                bestD = d
            }
        }
        return best
    }

    // ---------- Sentuhan ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        val action = e.actionMasked

        if (e.pointerCount > 1) {
            if (mode == Mode.DRAG || mode == Mode.RESIZE) finishGesture()
            mode = Mode.IGNORE
            var fx = 0f
            var fy = 0f
            for (i in 0 until e.pointerCount) {
                fx += e.getX(i)
                fy += e.getY(i)
            }
            fx /= e.pointerCount
            fy /= e.pointerCount
            if (multiActive && action == MotionEvent.ACTION_MOVE) {
                panX += fx - lastFocusX
                panY += fy - lastFocusY
                invalidate()
            }
            lastFocusX = fx
            lastFocusY = fy
            multiActive = true
            return true
        }

        if (multiActive) {
            multiActive = false
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) mode = Mode.NONE
            if (action != MotionEvent.ACTION_DOWN) return true
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> onDown(e)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                finishGesture()
                mode = Mode.NONE
            }
        }
        return true
    }

    private fun onDown(e: MotionEvent) {
        begun = false
        downVX = e.x
        downVY = e.y
        lastVX = e.x
        lastVY = e.y
        val lx = toLX(e.x)
        val ly = toLY(e.y)

        val sel = items.firstOrNull { it.id == selectedId }
        if (sel != null && sel.visible && !sel.locked) {
            val c = hitCorner(sel, e.x, e.y)
            if (c >= 0) {
                active = sel
                corner = c
                mode = Mode.RESIZE
                snapshotStart(sel, lx, ly)
                val cx = if (c == 0 || c == 2) sel.x else sel.x + sel.w
                val cy = if (c == 0 || c == 1) sel.y else sel.y + sel.h
                offLX = lx - cx
                offLY = ly - cy
                return
            }
        }

        val hit = items.lastOrNull {
            it.visible && lx >= it.x && lx <= it.x + it.w && ly >= it.y && ly <= it.y + it.h
        }
        if (hit != null) {
            if (hit.id != selectedId) {
                selectedId = hit.id
                listener?.onSelect(hit.id)
            }
            if (!hit.locked) {
                active = hit
                mode = Mode.DRAG
                snapshotStart(hit, lx, ly)
            } else {
                active = null
                mode = Mode.NONE
            }
        } else {
            if (selectedId != null) {
                selectedId = null
                listener?.onSelect(null)
            }
            active = null
            mode = Mode.PAN
        }
    }

    private fun snapshotStart(item: SourceItem, lx: Float, ly: Float) {
        startLX = lx
        startLY = ly
        startX = item.x
        startY = item.y
        startW = item.w
        startH = item.h
    }

    private fun beginIfMoved(e: MotionEvent): Boolean {
        if (begun) return true
        if (hypot(e.x - downVX, e.y - downVY) < slop) return false
        begun = true
        listener?.onEditBegin()
        return true
    }

    private fun onMove(e: MotionEvent) {
        when (mode) {
            Mode.DRAG -> {
                val item = active ?: return
                if (!beginIfMoved(e)) return
                val lx = toLX(e.x)
                val ly = toLY(e.y)
                item.x = (startX + (lx - startLX)).toInt().coerceIn(-item.w + 80, CANVAS_W - 80)
                item.y = (startY + (ly - startLY)).toInt().coerceIn(-item.h + 80, CANVAS_H - 80)
                listener?.onEdited()
                invalidate()
            }
            Mode.RESIZE -> {
                val item = active ?: return
                if (!beginIfMoved(e)) return
                val lx = (toLX(e.x) - offLX).coerceIn(-3000f, 5000f)
                val ly = (toLY(e.y) - offLY).coerceIn(-3000f, 5000f)
                val left = startX.toFloat()
                val top = startY.toFloat()
                val right = (startX + startW).toFloat()
                val bottom = (startY + startH).toFloat()
                var nl = left
                var nt = top
                var nr = right
                var nb = bottom
                val minS = MIN_SIZE.toFloat()
                when (corner) {
                    0 -> {
                        nl = min(lx, right - minS)
                        nt = min(ly, bottom - minS)
                    }
                    1 -> {
                        nr = max(lx, left + minS)
                        nt = min(ly, bottom - minS)
                    }
                    2 -> {
                        nl = min(lx, right - minS)
                        nb = max(ly, top + minS)
                    }
                    else -> {
                        nr = max(lx, left + minS)
                        nb = max(ly, top + minS)
                    }
                }
                item.x = nl.toInt()
                item.y = nt.toInt()
                item.w = (nr - nl).toInt().coerceIn(MIN_SIZE, MAX_SIZE)
                item.h = (nb - nt).toInt().coerceIn(MIN_SIZE, MAX_SIZE)
                listener?.onEdited()
                invalidate()
            }
            Mode.PAN -> {
                panX += e.x - lastVX
                panY += e.y - lastVY
                lastVX = e.x
                lastVY = e.y
                invalidate()
            }
            else -> Unit
        }
    }

    private fun finishGesture() {
        if (begun) listener?.onEditEnd()
        begun = false
        active = null
    }
}
