package com.aastudio.live

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/**
 * Menampilkan komposisi (semua Web Source yang terlihat) dalam satu container.
 * Dipakai oleh Preview di editor dan oleh jendela overlay. Lebar container menentukan skala:
 * scale = lebar / 1920. Setiap WebView dibuat seukuran logisnya lalu diskalakan.
 */
class CompositionView(context: Context) : FrameLayout(context) {

    private val cells = LinkedHashMap<String, SourceCell>()
    private val blocker = View(context)
    private var project: Project? = null
    private var scale = 0f
    private var forceMute = false

    /** false = sentuhan ke halaman web diblokir (mencegah interaksi tidak sengaja). */
    var interactive: Boolean = true
        set(value) {
            field = value
            blocker.visibility = if (value) View.GONE else View.VISIBLE
        }

    init {
        blocker.isClickable = true
        blocker.visibility = View.GONE
        addView(blocker, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setProject(p: Project) {
        project = p
        val want = p.items.filter { it.visible }
        val keep = want.map { it.id }.toSet()

        val iter = cells.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.key !in keep) {
                removeView(e.value)
                e.value.release()
                iter.remove()
            }
        }

        for (item in want) {
            val snap = item.copy()
            val existing = cells[item.id]
            if (existing == null) {
                val cell = SourceCell(context, snap)
                cells[item.id] = cell
                addView(cell)
                cell.applyAudio(p.audio, forceMute)
                cell.load()
            } else {
                val urlChanged = existing.item.url != snap.url
                existing.item = snap
                existing.applyAudio(p.audio, forceMute)
                if (urlChanged) existing.load()
            }
        }

        for (item in want) cells[item.id]?.bringToFront()
        blocker.bringToFront()
        layoutCells(true)
    }

    private fun layoutCells(force: Boolean) {
        if (width <= 0) return
        val s = width / CANVAS_W.toFloat()
        val scaleChanged = kotlin.math.abs(s - scale) > 0.0001f
        scale = s
        for (c in cells.values) {
            if (force || scaleChanged) c.applyUiScale(s)
            val it = c.item
            c.layoutParams = LayoutParams(it.w, it.h)
            c.pivotX = 0f
            c.pivotY = 0f
            c.scaleX = s
            c.scaleY = s
            c.translationX = it.x * s
            c.translationY = it.y * s
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutCells(false)
    }

    fun reloadAll() {
        for (c in cells.values) c.load()
    }

    fun setForceMute(mute: Boolean) {
        forceMute = mute
        val p = project ?: return
        for (c in cells.values) c.applyAudio(p.audio, forceMute)
    }

    fun applyAudio(p: Project) {
        project?.audio = p.audio
        for (c in cells.values) c.applyAudio(p.audio, forceMute)
    }

    fun pauseAll() {
        for (c in cells.values) c.pauseWeb()
    }

    fun resumeAll() {
        for (c in cells.values) c.resumeWeb()
    }

    fun cellCount(): Int = cells.size

    /** Melepaskan semua WebView. Setelah ini view tidak boleh dipakai lagi. */
    fun release() {
        for (c in cells.values) {
            removeView(c)
            c.release()
        }
        cells.clear()
        project = null
    }
}
