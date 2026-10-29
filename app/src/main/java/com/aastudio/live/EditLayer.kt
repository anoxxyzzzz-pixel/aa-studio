package com.aastudio.live

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.Collections
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Editor yang digambar LANGSUNG di atas aplikasi lain (satu jendela overlay layar penuh).
 * Setiap Web Source tampil sebagai kotak: seret badan untuk memindahkan, seret sudut untuk
 * mengubah ukuran, ketuk ikon gir untuk properti. Bar bawah: Hapus semua, tambah (+), Layer.
 * Koordinat disimpan dalam satuan kanvas 1920x1080 yang dipetakan ke seluruh layar,
 * sehingga posisi tetap proporsional saat layar diputar.
 */
class EditLayer(
    context: Context,
    private val project: Project,
    private val sw: Int,
    private val sh: Int,
    private val host: Host
) : FrameLayout(context) {

    interface Host {
        fun onChanged()
        fun onRequestStream()
    }

    private val d = resources.displayMetrics.density
    private val boxLayer = FrameLayout(context)
    private val panelHolder = FrameLayout(context)
    private val boxes = HashMap<String, EditBox>()
    private var selectedId: String? = null
    private var pendingDeleteId: String? = null

    private val statusH = sysDim("status_bar_height")
    private val navH = if (sw > sh) sysDim("navigation_bar_height_landscape") else sysDim("navigation_bar_height")

    private fun dp(v: Int): Int = (v * d + 0.5f).toInt()

    private fun sysDim(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    // ---- konversi koordinat kanvas <-> piksel layar
    private fun pxX(v: Int): Int = (v * sw / CANVAS_W.toFloat()).toInt()
    private fun pxY(v: Int): Int = (v * sh / CANVAS_H.toFloat()).toInt()
    private fun lgX(px: Float): Int = (px * CANVAS_W / sw).toInt()
    private fun lgY(px: Float): Int = (px * CANVAS_H / sh).toInt()
    private val minWLogical: Int get() = max(MIN_SIZE, lgX(dp(64).toFloat()) + 1)
    private val minHLogical: Int get() = max(MIN_SIZE, lgY(dp(64).toFloat()) + 1)

    init {
        setBackgroundColor(Color.argb(90, 0, 0, 0))
        isFocusableInTouchMode = true
        addView(boxLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        boxLayer.setOnClickListener { select(null) }
        buildTop()
        buildBottom()
        panelHolder.visibility = View.GONE
        addView(panelHolder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        rebuildBoxes()
    }

    // ------------------------------------------------------------------ bar atas & bawah

    private fun circle(text: String, fill: Int, textColor: Int, sizeDp: Int, sp: Float): TextView {
        val t = TextView(context)
        t.text = text
        t.textSize = sp
        t.gravity = Gravity.CENTER
        t.setTextColor(textColor)
        t.typeface = android.graphics.Typeface.DEFAULT_BOLD
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(fill)
        t.background = g
        t.layoutParams = LayoutParams(dp(sizeDp), dp(sizeDp))
        return t
    }

    private fun buildTop() {
        val x = circle("✕", Color.WHITE, Color.BLACK, 44, 18f)
        val lp = LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        lp.topMargin = statusH + dp(8)
        x.setOnClickListener { host.onRequestStream() }
        addView(x, lp)
    }

    private fun buildBottom() {
        val bottom = LinearLayout(context)
        bottom.orientation = LinearLayout.VERTICAL

        val tabs = LinearLayout(context)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.gravity = Gravity.CENTER
        tabs.setPadding(0, 0, 0, dp(8))
        tabs.addView(pill("Editor", true) {}, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        val sp = LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP)
        sp.leftMargin = dp(8)
        tabs.addView(pill("Stream", false) { host.onRequestStream() }, sp)
        bottom.addView(tabs, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        val bar = LinearLayout(context)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Color.parseColor("#F2202020"))

        val trash = barText("🗑  Hapus semua")
        trash.setOnClickListener { showClearConfirm() }
        bar.addView(trash, LinearLayout.LayoutParams(0, dp(56), 1f))

        val plus = circle("+", Ui.ACCENT, Color.WHITE, 52, 30f)
        plus.setOnClickListener { showAdd() }
        bar.addView(plus, LinearLayout.LayoutParams(dp(52), dp(52)))

        val layers = barText("Layer  ▤")
        layers.setOnClickListener { showLayers() }
        bar.addView(layers, LinearLayout.LayoutParams(0, dp(56), 1f))
        bottom.addView(bar, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        val spacer = View(context)
        spacer.setBackgroundColor(Color.parseColor("#F2202020"))
        bottom.addView(spacer, LinearLayout.LayoutParams(Ui.MATCH, navH))

        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun barText(s: String): TextView {
        val t = TextView(context)
        t.text = s
        t.textSize = 15f
        t.gravity = Gravity.CENTER
        t.setTextColor(Color.WHITE)
        t.typeface = android.graphics.Typeface.DEFAULT_BOLD
        return t
    }

    private fun pill(s: String, active: Boolean, onClick: () -> Unit): TextView {
        val t = TextView(context)
        t.text = s
        t.textSize = 14f
        t.typeface = android.graphics.Typeface.DEFAULT_BOLD
        t.setTextColor(if (active) Color.BLACK else Color.WHITE)
        t.setPadding(dp(18), dp(8), dp(18), dp(8))
        val g = GradientDrawable()
        g.cornerRadius = dp(20).toFloat()
        g.setColor(if (active) Color.WHITE else Color.argb(150, 20, 20, 20))
        t.background = g
        t.setOnClickListener { onClick() }
        return t
    }

    // ------------------------------------------------------------------ kotak elemen

    private fun rebuildBoxes() {
        boxLayer.removeAllViews()
        boxes.clear()
        for (item in project.items) {
            val b = EditBox(item)
            boxes[item.id] = b
            boxLayer.addView(b)
        }
        boxes[selectedId]?.bringToFront()
        refreshBoxes()
    }

    private fun refreshBoxes() {
        for (b in boxes.values) {
            b.updateStyle()
            b.place()
        }
    }

    private fun select(id: String?) {
        if (selectedId == id) return
        selectedId = id
        boxes[id]?.bringToFront()
        refreshBoxes()
    }

    private inner class EditBox(val item: SourceItem) : FrameLayout(context) {
        private val label = TextView(context)
        private val gear: TextView = circle("⚙", Color.WHITE, Color.BLACK, 36, 18f)
        private val hp = Paint(Paint.ANTI_ALIAS_FLAG)

        private var mode = 0 // 0 none, 1 move, 2 resize
        private var corner = 0
        private var downX = 0f
        private var downY = 0f
        private var sx = 0
        private var sy = 0
        private var sW = 0
        private var sH = 0
        private var moved = false

        init {
            setWillNotDraw(false)
            label.textSize = 14f
            label.setTextColor(Color.WHITE)
            label.gravity = Gravity.CENTER
            label.setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            val glp = LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.END)
            glp.setMargins(0, dp(8), dp(8), 0)
            gear.setOnClickListener {
                select(item.id)
                showProps(item)
            }
            addView(gear, glp)
            updateStyle()
        }

        fun place() {
            val lp = LayoutParams(max(dp(20), pxX(item.w)), max(dp(20), pxY(item.h)))
            lp.leftMargin = pxX(item.x)
            lp.topMargin = pxY(item.y)
            layoutParams = lp
        }

        fun updateStyle() {
            val sel = item.id == selectedId
            val g = GradientDrawable()
            if (item.visible) {
                g.setColor(Color.argb(150, 30, 30, 30))
                g.setStroke(dp(2), if (sel) Ui.ACCENT else Color.argb(220, 255, 255, 255))
            } else {
                g.setColor(Color.argb(40, 255, 255, 255))
                g.setStroke(dp(2), Color.argb(200, 255, 255, 255), dp(8).toFloat(), dp(5).toFloat())
            }
            background = g
            val sub = if (item.url.isBlank()) "(URL kosong)" else UrlUtil.hostOf(item.url)
            label.text = item.name + (if (item.locked) "  🔒" else "") +
                (if (!item.visible) "  (sembunyi)" else "") + "\n" + sub
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            if (item.id != selectedId || item.locked) return
            val half = dp(7).toFloat()
            val pts = arrayOf(
                floatArrayOf(half, half),
                floatArrayOf(half, height - half),
                floatArrayOf(width - half, height - half)
            )
            for (p in pts) {
                hp.style = Paint.Style.FILL
                hp.color = Color.WHITE
                c.drawRect(p[0] - half, p[1] - half, p[0] + half, p[1] + half, hp)
                hp.style = Paint.Style.STROKE
                hp.strokeWidth = dp(2).toFloat()
                hp.color = Ui.ACCENT
                c.drawRect(p[0] - half, p[1] - half, p[0] + half, p[1] + half, hp)
            }
        }

        private fun hitCorner(x: Float, y: Float): Int {
            val tol = dp(30).toFloat()
            val left = x < tol
            val right = x > width - tol
            val top = y < tol
            val bottom = y > height - tol
            return when {
                left && top -> 0
                left && bottom -> 2
                right && bottom -> 3
                else -> -1 // sudut kanan-atas dipakai ikon gir
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    select(item.id)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    downX = e.rawX
                    downY = e.rawY
                    sx = item.x
                    sy = item.y
                    sW = item.w
                    sH = item.h
                    moved = false
                    val c = if (item.locked) -1 else hitCorner(e.x, e.y)
                    corner = c
                    mode = when {
                        item.locked -> 0
                        c >= 0 -> 2
                        else -> 1
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == 0) return true
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) < dp(4)) return true
                    moved = true
                    val dxl = lgX(dx)
                    val dyl = lgY(dy)
                    if (mode == 1) {
                        item.x = (sx + dxl).coerceIn(-item.w + 80, CANVAS_W - 80)
                        item.y = (sy + dyl).coerceIn(-item.h + 80, CANVAS_H - 80)
                        // Geser lewat translasi (tanpa layout ulang tiap frame); margin diperbarui saat jari lepas.
                        translationX = (pxX(item.x) - pxX(sx)).toFloat()
                        translationY = (pxY(item.y) - pxY(sy)).toFloat()
                        return true
                    } else {
                        var l = sx
                        var t = sy
                        var r = sx + sW
                        var b = sy + sH
                        when (corner) {
                            0 -> { l = min(sx + dxl, r - minWLogical); t = min(sy + dyl, b - minHLogical) }
                            2 -> { l = min(sx + dxl, r - minWLogical); b = max(sy + sH + dyl, t + minHLogical) }
                            else -> { r = max(sx + sW + dxl, l + minWLogical); b = max(sy + sH + dyl, t + minHLogical) }
                        }
                        item.x = l
                        item.y = t
                        item.w = (r - l).coerceIn(MIN_SIZE, MAX_SIZE)
                        item.h = (b - t).coerceIn(MIN_SIZE, MAX_SIZE)
                    }
                    place()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (mode == 1 && moved) {
                        translationX = 0f
                        translationY = 0f
                        place()
                    }
                    if (moved) host.onChanged()
                    mode = 0
                    return true
                }
            }
            return true
        }
    }

    // ------------------------------------------------------------------ panel

    private class MaxHeightScroll(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
        override fun onMeasure(w: Int, h: Int) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        }
    }

    private fun showPanel(title: String, content: View) {
        panelHolder.removeAllViews()
        panelHolder.setBackgroundColor(Color.argb(110, 0, 0, 0))
        panelHolder.setOnClickListener { closePanel() }

        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(14), dp(12), dp(14), dp(14))

        val head = LinearLayout(context)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(Ui.text(context, title, 17f, true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        head.addView(Ui.iconBtn(context, "✕") { closePanel() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        card.addView(head, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        card.addView(content, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        val maxH = max(dp(240), (sh * 0.5f).toInt())
        val sv = MaxHeightScroll(context, maxH)
        sv.background = Ui.box(context, Color.WHITE, Ui.LINE, 14)
        sv.isClickable = true
        sv.addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val w = min(sw - dp(24), dp(440))
        val lp = LayoutParams(w, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        lp.topMargin = statusH + dp(64)
        panelHolder.addView(sv, lp)
        panelHolder.visibility = View.VISIBLE
    }

    fun closePanel(): Boolean {
        if (panelHolder.visibility != View.VISIBLE) return false
        hideIme()
        panelHolder.removeAllViews()
        panelHolder.visibility = View.GONE
        pendingDeleteId = null
        requestFocus()
        return true
    }

    private fun hideIme() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (!closePanel()) host.onRequestStream()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun label(s: String): TextView {
        val t = Ui.text(context, s, 12f, true, Ui.MUTED)
        t.setPadding(0, dp(8), 0, dp(2))
        return t
    }

    private fun col(): LinearLayout {
        val l = LinearLayout(context)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    private fun changed() {
        refreshBoxes()
        host.onChanged()
    }

    // ---- tambah Web Source

    private fun showAdd() {
        val c = col()
        c.addView(label("URL halaman web"))
        val url = Ui.field(context, "https://contoh.com/overlay", "", uri = true)
        c.addView(url, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        c.addView(label("Nama (opsional)"))
        val name = Ui.field(context, "Nama elemen", "")
        c.addView(name, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        val err = Ui.text(context, "", 12f, false, Ui.BAD)
        c.addView(err)
        val add = Ui.button(context, "Tambah Web Source", true) {
            val r = UrlUtil.normalize(url.text.toString())
            if (r.url == null) {
                err.text = r.error
            } else {
                addSource(r.url, name.text.toString())
                closePanel()
            }
        }
        val lp = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP)
        lp.topMargin = dp(10)
        c.addView(add, lp)
        showPanel("Tambah Web Source", c)
        url.requestFocus()
    }

    private fun addSource(url: String, name: String) {
        val wPx = (sw * 0.6f).toInt()
        val hPx = wPx * 9 / 16
        val n = project.items.size % 5
        val item = SourceItem(
            name = name.trim().ifBlank { UrlUtil.hostOf(url) },
            url = url,
            w = lgX(wPx.toFloat()),
            h = lgY(hPx.toFloat())
        )
        item.x = lgX(((sw - wPx) / 2 + n * dp(16)).toFloat())
        item.y = lgY(((sh - hPx) / 2 + n * dp(16)).toFloat())
        project.items.add(item)
        selectedId = item.id
        rebuildBoxes()
        host.onChanged()
        if (url.startsWith("http://")) Ui.toast(context, "HTTP biasanya diblokir Android. Gunakan HTTPS bila bisa.")
    }

    // ---- properti

    private fun showProps(item: SourceItem) {
        val c = col()
        val gap = dp(6)

        c.addView(label("Nama"))
        val fName = Ui.field(context, "Nama elemen", item.name)
        c.addView(fName, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        c.addView(label("URL halaman web"))
        val fUrl = Ui.field(context, "https://contoh.com", item.url, uri = true)
        c.addView(fUrl, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        val fx = Ui.field(context, "X", pxX(item.x).toString(), numeric = true)
        val fy = Ui.field(context, "Y", pxY(item.y).toString(), numeric = true)
        val fw = Ui.field(context, "Lebar", pxX(item.w).toString(), numeric = true)
        val fh = Ui.field(context, "Tinggi", pxY(item.h).toString(), numeric = true)
        c.addView(pair("X (px)", fx, "Y (px)", fy))
        c.addView(pair("Lebar (px)", fw, "Tinggi (px)", fh))
        c.addView(Ui.text(context, "Layar ${sw} x ${sh} px", 11f, false, Ui.MUTED))

        val err = Ui.text(context, "", 12f, false, Ui.BAD)
        c.addView(err)
        val apply = Ui.button(context, "Terapkan", true) {
            val xi = fx.text.toString().trim().toIntOrNull()
            val yi = fy.text.toString().trim().toIntOrNull()
            val wi = fw.text.toString().trim().toIntOrNull()
            val hi = fh.text.toString().trim().toIntOrNull()
            val r = UrlUtil.normalize(fUrl.text.toString())
            if (xi == null || yi == null || wi == null || hi == null) {
                err.text = "Posisi dan ukuran harus berupa angka"
            } else if (r.url == null) {
                err.text = r.error
            } else {
                item.name = fName.text.toString().trim().ifBlank { "Web Source" }
                item.url = r.url
                item.w = lgX(wi.toFloat()).coerceIn(minWLogical, MAX_SIZE)
                item.h = lgY(hi.toFloat()).coerceIn(minHLogical, MAX_SIZE)
                item.x = lgX(xi.toFloat()).coerceIn(-item.w + 80, CANVAS_W - 80)
                item.y = lgY(yi.toFloat()).coerceIn(-item.h + 80, CANVAS_H - 80)
                err.text = ""
                changed()
                Ui.toast(context, "Diterapkan")
            }
        }
        val alp = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP)
        alp.topMargin = gap
        c.addView(apply, alp)

        val swVis = Switch(context)
        swVis.text = "Tampil"
        swVis.isChecked = item.visible
        swVis.setOnCheckedChangeListener { _, v -> item.visible = v; changed() }
        c.addView(swVis, topMargin(dp(10)))

        val swLock = Switch(context)
        swLock.text = "Kunci (tidak bisa digeser)"
        swLock.isChecked = item.locked
        swLock.setOnCheckedChangeListener { _, v -> item.locked = v; changed() }
        c.addView(swLock, topMargin(dp(8)))

        val swMute = Switch(context)
        swMute.text = "Mute suara elemen ini"
        swMute.isChecked = item.muted
        swMute.setOnCheckedChangeListener { _, v -> item.muted = v; host.onChanged() }
        c.addView(swMute, topMargin(dp(8)))

        val volLabel = Ui.text(context, "Volume elemen: ${item.volume}%", 13f)
        c.addView(volLabel, topMargin(dp(8)))
        val seek = SeekBar(context)
        seek.max = 100
        seek.progress = item.volume
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                volLabel.text = "Volume elemen: $p%"
                if (fromUser) item.volume = p
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                host.onChanged()
            }
        })
        c.addView(seek, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        val order = LinearLayout(context)
        order.orientation = LinearLayout.HORIZONTAL
        order.addView(
            Ui.button(context, "Ke depan") { moveLayer(item, +1) },
            Ui.lpM(0, Ui.WRAP, 1f, 0, 0, gap, 0)
        )
        order.addView(Ui.button(context, "Ke belakang") { moveLayer(item, -1) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        c.addView(order, topMargin(dp(10)))

        c.addView(
            Ui.button(context, "Hapus elemen") {
                project.items.removeAll { it.id == item.id }
                if (selectedId == item.id) selectedId = null
                rebuildBoxes()
                host.onChanged()
                closePanel()
            },
            topMargin(gap)
        )
        showPanel("Properti", c)
    }

    private fun topMargin(px: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP)
        lp.topMargin = px
        return lp
    }

    private fun pair(l1: String, f1: EditText, l2: String, f2: EditText): LinearLayout {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        val a = col()
        a.addView(label(l1))
        a.addView(f1, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        val b = col()
        b.addView(label(l2))
        b.addView(f2, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        row.addView(a, Ui.lpM(0, Ui.WRAP, 1f, 0, 0, dp(6), 0))
        row.addView(b, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        return row
    }

    private fun moveLayer(item: SourceItem, delta: Int) {
        val i = project.items.indexOfFirst { it.id == item.id }
        val j = i + delta
        if (i < 0 || j !in project.items.indices) return
        Collections.swap(project.items, i, j)
        rebuildBoxes()
        host.onChanged()
    }

    // ---- daftar layer

    private fun showLayers() {
        val c = col()
        if (project.items.isEmpty()) {
            c.addView(Ui.text(context, "Belum ada elemen. Tekan + untuk menambah Web Source.", 13f, false, Ui.MUTED))
            showPanel("Layer", c)
            return
        }
        c.addView(Ui.text(context, "Atas = paling depan", 12f, false, Ui.MUTED))
        for (i in project.items.indices.reversed()) {
            val item = project.items[i]
            val sel = item.id == selectedId
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(8), dp(4), dp(4), dp(4))
            row.background = Ui.box(context, if (sel) Ui.SEL_BG else Color.WHITE, if (sel) Ui.ACCENT else Ui.LINE)
            row.setOnClickListener {
                select(item.id)
                showLayers()
            }
            val name = Ui.text(
                context, item.name + if (item.visible) "" else " (sembunyi)", 14f, sel,
                if (item.visible) Ui.BLACK else Ui.MUTED
            )
            name.maxLines = 1
            name.ellipsize = android.text.TextUtils.TruncateAt.END
            row.addView(name, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))

            val size = dp(40)
            fun add(label: String, active: Boolean, a: () -> Unit) {
                row.addView(Ui.iconBtn(context, label, active, a), Ui.lpM(size, size, 0f, dp(3), 0, 0, 0))
            }
            add("▲", false) { moveLayer(item, +1); showLayers() }
            add("▼", false) { moveLayer(item, -1); showLayers() }
            add(if (item.visible) "◉" else "○", !item.visible) { item.visible = !item.visible; changed(); showLayers() }
            add(if (item.locked) "🔒" else "🔓", item.locked) { item.locked = !item.locked; changed(); showLayers() }
            if (pendingDeleteId == item.id) {
                add("Yakin?", true) {
                    project.items.removeAll { it.id == item.id }
                    if (selectedId == item.id) selectedId = null
                    pendingDeleteId = null
                    rebuildBoxes()
                    host.onChanged()
                    showLayers()
                }
            } else {
                add("✕", false) { pendingDeleteId = item.id; showLayers() }
            }
            c.addView(row, Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, dp(6), 0, 0))
        }
        showPanel("Layer", c)
    }

    private fun showClearConfirm() {
        val c = col()
        c.addView(
            Ui.text(
                context,
                if (project.items.isEmpty()) "Tidak ada elemen untuk dihapus."
                else "Semua ${project.items.size} elemen akan dihapus dari proyek ini.",
                14f
            )
        )
        if (project.items.isNotEmpty()) {
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(
                Ui.button(context, "Hapus semua", true) {
                    project.items.clear()
                    selectedId = null
                    rebuildBoxes()
                    host.onChanged()
                    closePanel()
                },
                Ui.lpM(0, Ui.WRAP, 1f, 0, 0, dp(6), 0)
            )
            row.addView(Ui.button(context, "Batal") { closePanel() }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            c.addView(row, topMargin(dp(12)))
        }
        showPanel("Hapus semua?", c)
    }
}
