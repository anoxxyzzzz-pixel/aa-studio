package com.aastudio.live

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.Collections

/**
 * Editor komposisi overlay. Alur kerja: kanvas 16:9 di atas, lalu panel Layer / Properti.
 * Perubahan disimpan otomatis (debounce) ke penyimpanan lokal.
 */
class EditorActivity : Activity(), CanvasView.Listener {

    private lateinit var store: ProjectStore
    private lateinit var project: Project
    private val handler = Handler(Looper.getMainLooper())

    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()

    private lateinit var root: LinearLayout
    private lateinit var mainCol: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var holder: FrameLayout
    private lateinit var canvas: CanvasView
    private var composition: CompositionView? = null

    private lateinit var titleView: TextView
    private lateinit var btnUndo: Button
    private lateinit var btnRedo: Button
    private lateinit var btnPreview: Button
    private lateinit var btnOverlay: Button
    private lateinit var tabLayers: Button
    private lateinit var tabProps: Button
    private lateinit var layersBox: LinearLayout
    private lateinit var propsBox: LinearLayout

    private var fieldX: EditText? = null
    private var fieldY: EditText? = null
    private var fieldW: EditText? = null
    private var fieldH: EditText? = null

    private var selectedId: String? = null
    private var tab = 0
    private var previewOn = false
    private var audioPending = false
    private var askedNotif = false

    private val saveRunnable = Runnable { saveNow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProjectStore(this)
        val id = intent.getStringExtra(EXTRA_PROJECT)
        val loaded = if (id != null) store.load(id) else null
        if (loaded == null) {
            Ui.toast(this, "Proyek tidak ditemukan")
            finish()
            return
        }
        project = loaded
        buildUi()
        applyOrientation(resources.configuration)
        refreshAll()
        if (intent.getBooleanExtra(EXTRA_ADD_SOURCE, false) && savedInstanceState == null) {
            showAddSourceDialog()
        }
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        root = LinearLayout(this)
        root.setBackgroundColor(Ui.WHITE)

        mainCol = LinearLayout(this)
        mainCol.orientation = LinearLayout.VERTICAL

        // Baris 1: kembali, judul, undo, redo, simpan
        val bar1 = LinearLayout(this)
        bar1.orientation = LinearLayout.HORIZONTAL
        bar1.gravity = Gravity.CENTER_VERTICAL
        bar1.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 2))
        val m = Ui.dp(this, 3)
        bar1.addView(Ui.button(this, "←") { finishWithSave() }, Ui.lpM(Ui.WRAP, Ui.WRAP, 0f, m, 0, m, 0))
        titleView = Ui.text(this, project.name, 16f, true)
        titleView.maxLines = 1
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        titleView.setOnClickListener { showRenameDialog() }
        bar1.addView(titleView, Ui.lpM(0, Ui.WRAP, 1f, m * 2, 0, m * 2, 0))
        btnUndo = Ui.button(this, "Undo") { doUndo() }
        btnRedo = Ui.button(this, "Redo") { doRedo() }
        bar1.addView(btnUndo, Ui.lpM(Ui.WRAP, Ui.WRAP, 0f, m, 0, m, 0))
        bar1.addView(btnRedo, Ui.lpM(Ui.WRAP, Ui.WRAP, 0f, m, 0, m, 0))
        bar1.addView(Ui.button(this, "Simpan") { saveNow(); Ui.toast(this, "Tersimpan") }, Ui.lpM(Ui.WRAP, Ui.WRAP, 0f, m, 0, m, 0))
        mainCol.addView(bar1, Ui.lp(Ui.MATCH, Ui.WRAP))

        // Baris 2: aksi (dapat digeser horizontal)
        val bar2 = LinearLayout(this)
        bar2.orientation = LinearLayout.HORIZONTAL
        bar2.setPadding(Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 6))
        bar2.addView(Ui.button(this, "+ Web Source", true) { showAddSourceDialog() }, Ui.lpM(Ui.WRAP, Ui.WRAP, 0f, m, 0, m, 0))
        btnPreview = Ui.button(this, "Preview") { setPreview(!previewOn) }
        bar2.addView(btnPreview, Ui.lpM(Ui.WRAP, Ui.WRAP, 0f, m, 0, m, 0))
        bar2.addView(Ui.button(this, "Muat ulang") { reloadPreview() }, Ui.lpM(Ui.WRAP, Ui.WRAP, 0f, m, 0, m, 0))
        btnOverlay = Ui.button(this, "Jalankan overlay") { toggleOverlay() }
        bar2.addView(btnOverlay, Ui.lpM(Ui.WRAP, Ui.WRAP, 0f, m, 0, m, 0))
        bar2.addView(Ui.button(this, "Audio") { openAudio() }, Ui.lpM(Ui.WRAP, Ui.WRAP, 0f, m, 0, m, 0))
        bar2.addView(Ui.button(this, "Izin overlay") {
            startActivity(Intent(this, OverlaySettingsActivity::class.java))
        }, Ui.lpM(Ui.WRAP, Ui.WRAP, 0f, m, 0, m, 0))
        bar2.addView(Ui.button(this, "Reset zoom") { canvas.resetView() }, Ui.lpM(Ui.WRAP, Ui.WRAP, 0f, m, 0, m, 0))
        val scroll2 = HorizontalScrollView(this)
        scroll2.isHorizontalScrollBarEnabled = false
        scroll2.addView(bar2)
        mainCol.addView(scroll2, Ui.lp(Ui.MATCH, Ui.WRAP))

        // Kanvas
        holder = FrameLayout(this)
        holder.setBackgroundColor(Color.parseColor("#E6E6E6"))
        canvas = CanvasView(this)
        canvas.listener = this
        holder.addView(canvas, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        holder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> holder.post { sizePreview() } }
        mainCol.addView(holder, Ui.lp(Ui.MATCH, 0, 1f))

        // Panel Layer / Properti
        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(Ui.WHITE)
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6))
        tabLayers = Ui.button(this, "Layer") { setTab(0) }
        tabProps = Ui.button(this, "Properti") { setTab(1) }
        tabs.addView(tabLayers, Ui.lpM(0, Ui.WRAP, 1f, m, 0, m, 0))
        tabs.addView(tabProps, Ui.lpM(0, Ui.WRAP, 1f, m, 0, m, 0))
        panel.addView(tabs, Ui.lp(Ui.MATCH, Ui.WRAP))

        val sv = ScrollView(this)
        val content = FrameLayout(this)
        layersBox = LinearLayout(this)
        layersBox.orientation = LinearLayout.VERTICAL
        layersBox.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 12))
        propsBox = LinearLayout(this)
        propsBox.orientation = LinearLayout.VERTICAL
        propsBox.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 16))
        content.addView(layersBox, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        content.addView(propsBox, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        sv.addView(content, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        panel.addView(sv, Ui.lp(Ui.MATCH, 0, 1f))

        root.addView(mainCol)
        root.addView(panel)
        setContentView(root)
    }

    private fun applyOrientation(cfg: Configuration) {
        val land = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
        root.orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (land) {
            mainCol.layoutParams = Ui.lp(0, Ui.MATCH, 1f)
            panel.layoutParams = Ui.lp(Ui.dp(this, 330), Ui.MATCH)
        } else {
            mainCol.layoutParams = Ui.lp(Ui.MATCH, 0, 1f)
            panel.layoutParams = Ui.lp(Ui.MATCH, Ui.dp(this, 290))
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig)
        holder.post { sizePreview() }
    }

    private fun setTab(t: Int) {
        tab = t
        layersBox.visibility = if (t == 0) View.VISIBLE else View.GONE
        propsBox.visibility = if (t == 1) View.VISIBLE else View.GONE
        tabLayers.background = Ui.box(this, if (t == 0) Ui.BLACK else Ui.WHITE, if (t == 0) Ui.BLACK else Ui.LINE)
        tabLayers.setTextColor(if (t == 0) Ui.WHITE else Ui.BLACK)
        tabProps.background = Ui.box(this, if (t == 1) Ui.BLACK else Ui.WHITE, if (t == 1) Ui.BLACK else Ui.LINE)
        tabProps.setTextColor(if (t == 1) Ui.WHITE else Ui.BLACK)
    }

    private fun refreshAll() {
        titleView.text = project.name
        canvas.items = project.items
        canvas.selectedId = selectedId
        setTab(tab)
        refreshLayers()
        refreshProps()
        updateUndoButtons()
        updateOverlayButton()
    }

    // ---------------------------------------------------------------- Layer

    private fun selectedItem(): SourceItem? = project.items.firstOrNull { it.id == selectedId }

    private fun refreshLayers() {
        layersBox.removeAllViews()
        if (project.items.isEmpty()) {
            layersBox.addView(
                Ui.text(this, "Belum ada elemen. Tekan \"+ Web Source\" untuk menambahkan halaman web.", 13f, false, Ui.MUTED),
                Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 8), 0, 0)
            )
            return
        }
        layersBox.addView(
            Ui.text(this, "Urutan dari depan (atas) ke belakang (bawah)", 12f, false, Ui.MUTED),
            Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, 0, 0, Ui.dp(this, 6))
        )
        for (i in project.items.indices.reversed()) {
            layersBox.addView(layerRow(project.items[i]), Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, 0, 0, Ui.dp(this, 6)))
        }
    }

    private fun layerRow(item: SourceItem): View {
        val selected = item.id == selectedId
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4))
        row.background = Ui.box(this, if (selected) Ui.SEL_BG else Ui.WHITE, if (selected) Ui.ACCENT else Ui.LINE)
        row.setOnClickListener { select(item.id) }

        val name = Ui.text(
            this,
            item.name + if (item.visible) "" else " (tersembunyi)",
            14f, selected, if (item.visible) Ui.BLACK else Ui.MUTED
        )
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        row.addView(name, Ui.lp(0, Ui.WRAP, 1f))

        val size = Ui.dp(this, 40)
        val gap = Ui.dp(this, 3)
        fun add(label: String, active: Boolean, action: () -> Unit) {
            row.addView(Ui.iconBtn(this, label, active, action), Ui.lpM(size, size, 0f, gap, 0, 0, 0))
        }
        add("▲", false) { moveLayer(item, +1) }
        add("▼", false) { moveLayer(item, -1) }
        add(if (item.visible) "◉" else "○", !item.visible) { mutate { item.visible = !item.visible } }
        add(if (item.locked) "🔒" else "🔓", item.locked) { mutate { item.locked = !item.locked } }
        add("✕", false) { confirmDelete(item) }
        return row
    }

    private fun select(id: String?) {
        selectedId = id
        canvas.selectedId = id
        refreshLayers()
        refreshProps()
    }

    private fun moveLayer(item: SourceItem, delta: Int) {
        val i = project.items.indexOfFirst { it.id == item.id }
        val j = i + delta
        if (i < 0 || j !in project.items.indices) return
        mutate { Collections.swap(project.items, i, j) }
    }

    private fun confirmDelete(item: SourceItem) {
        AlertDialog.Builder(this)
            .setTitle("Hapus elemen?")
            .setMessage("\"${item.name}\" akan dihapus dari komposisi. Anda masih bisa Undo.")
            .setPositiveButton("Hapus") { _, _ ->
                mutate {
                    project.items.removeAll { it.id == item.id }
                    if (selectedId == item.id) selectedId = null
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---------------------------------------------------------------- Properti

    private fun refreshProps() {
        propsBox.removeAllViews()
        fieldX = null
        fieldY = null
        fieldW = null
        fieldH = null
        val item = selectedItem()
        if (item == null) {
            propsBox.addView(
                Ui.text(this, "Pilih elemen di kanvas atau di daftar Layer untuk mengubah propertinya.", 13f, false, Ui.MUTED),
                Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 8), 0, 0)
            )
            return
        }
        val gap = Ui.dp(this, 6)

        propsBox.addView(label("Nama"))
        val fName = Ui.field(this, "Nama elemen", item.name)
        propsBox.addView(fName, Ui.lp(Ui.MATCH, Ui.WRAP))

        propsBox.addView(label("URL halaman web"))
        val fUrl = Ui.field(this, "https://contoh.com/overlay", item.url, uri = true)
        propsBox.addView(fUrl, Ui.lp(Ui.MATCH, Ui.WRAP))

        val rowXY = LinearLayout(this)
        rowXY.orientation = LinearLayout.HORIZONTAL
        val fx = Ui.field(this, "X", item.x.toString(), numeric = true)
        val fy = Ui.field(this, "Y", item.y.toString(), numeric = true)
        rowXY.addView(labeled("X", fx), Ui.lpM(0, Ui.WRAP, 1f, 0, 0, gap, 0))
        rowXY.addView(labeled("Y", fy), Ui.lp(0, Ui.WRAP, 1f))
        propsBox.addView(rowXY, Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, gap, 0, 0))

        val rowWH = LinearLayout(this)
        rowWH.orientation = LinearLayout.HORIZONTAL
        val fw = Ui.field(this, "Lebar", item.w.toString(), numeric = true)
        val fh = Ui.field(this, "Tinggi", item.h.toString(), numeric = true)
        rowWH.addView(labeled("Lebar", fw), Ui.lpM(0, Ui.WRAP, 1f, 0, 0, gap, 0))
        rowWH.addView(labeled("Tinggi", fh), Ui.lp(0, Ui.WRAP, 1f))
        propsBox.addView(rowWH, Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, gap, 0, 0))
        fieldX = fx
        fieldY = fy
        fieldW = fw
        fieldH = fh

        propsBox.addView(
            Ui.text(this, "Koordinat kanvas 1920 x 1080", 11f, false, Ui.MUTED),
            Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 2), 0, Ui.dp(this, 6))
        )

        propsBox.addView(
            Ui.button(this, "Terapkan", true) {
                applyProps(item, fName.text.toString(), fUrl.text.toString(), fx.text.toString(), fy.text.toString(), fw.text.toString(), fh.text.toString())
            },
            Ui.lp(Ui.MATCH, Ui.WRAP)
        )

        val swVisible = Switch(this)
        swVisible.text = "Tampil"
        swVisible.isChecked = item.visible
        swVisible.setOnCheckedChangeListener { _, c -> mutate(keepProps = true) { item.visible = c } }
        propsBox.addView(swVisible, Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 10), 0, 0))

        val swLocked = Switch(this)
        swLocked.text = "Kunci (tidak bisa digeser di kanvas)"
        swLocked.isChecked = item.locked
        swLocked.setOnCheckedChangeListener { _, c -> mutate(keepProps = true) { item.locked = c } }
        propsBox.addView(swLocked, Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 8), 0, 0))

        val swMuted = Switch(this)
        swMuted.text = "Mute suara elemen ini"
        swMuted.isChecked = item.muted
        swMuted.setOnCheckedChangeListener { _, c -> mutate(keepProps = true) { item.muted = c } }
        propsBox.addView(swMuted, Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 8), 0, 0))

        val volLabel = Ui.text(this, "Volume elemen: ${item.volume}%", 13f)
        propsBox.addView(volLabel, Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 8), 0, 0))
        val seek = SeekBar(this)
        seek.max = 100
        seek.progress = item.volume
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                volLabel.text = "Volume elemen: $progress%"
                if (fromUser) {
                    item.volume = progress
                    scheduleSave()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                pushUndo()
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                afterChange(keepProps = true)
            }
        })
        propsBox.addView(seek, Ui.lp(Ui.MATCH, Ui.WRAP))

        val rowOrder = LinearLayout(this)
        rowOrder.orientation = LinearLayout.HORIZONTAL
        rowOrder.addView(Ui.button(this, "Ke depan") { moveLayer(item, +1) }, Ui.lpM(0, Ui.WRAP, 1f, 0, 0, gap, 0))
        rowOrder.addView(Ui.button(this, "Ke belakang") { moveLayer(item, -1) }, Ui.lp(0, Ui.WRAP, 1f))
        propsBox.addView(rowOrder, Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 10), 0, 0))

        propsBox.addView(
            Ui.button(this, "Hapus elemen") { confirmDelete(item) },
            Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, gap, 0, 0)
        )
    }

    private fun label(s: String): TextView {
        val t = Ui.text(this, s, 12f, true, Ui.MUTED)
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 2))
        return t
    }

    private fun labeled(s: String, field: EditText): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(label(s))
        col.addView(field, Ui.lp(Ui.MATCH, Ui.WRAP))
        return col
    }

    private fun applyProps(item: SourceItem, name: String, url: String, x: String, y: String, w: String, h: String) {
        val xi = x.trim().toIntOrNull()
        val yi = y.trim().toIntOrNull()
        val wi = w.trim().toIntOrNull()
        val hi = h.trim().toIntOrNull()
        if (xi == null || yi == null || wi == null || hi == null) {
            Ui.toast(this, "Posisi dan ukuran harus berupa angka")
            return
        }
        val r = UrlUtil.normalize(url)
        if (r.url == null) {
            Ui.toast(this, r.error ?: "URL tidak valid")
            return
        }
        mutate {
            item.name = name.trim().ifBlank { "Web Source" }
            item.url = r.url
            item.w = wi.coerceIn(MIN_SIZE, MAX_SIZE)
            item.h = hi.coerceIn(MIN_SIZE, MAX_SIZE)
            item.x = xi.coerceIn(-item.w + 80, CANVAS_W - 80)
            item.y = yi.coerceIn(-item.h + 80, CANVAS_H - 80)
        }
        if (r.url.startsWith("http://")) {
            Ui.toast(this, "HTTP biasanya diblokir Android. Gunakan HTTPS bila bisa.")
        }
    }

    private fun updatePosFields() {
        val item = selectedItem() ?: return
        fieldX?.setText(item.x.toString())
        fieldY?.setText(item.y.toString())
        fieldW?.setText(item.w.toString())
        fieldH?.setText(item.h.toString())
    }

    // ---------------------------------------------------------------- Tambah / ganti nama

    private fun showAddSourceDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(this, 20)
        box.setPadding(pad, Ui.dp(this, 8), pad, 0)
        val urlField = Ui.field(this, "https://contoh.com/overlay", "", uri = true)
        val nameField = Ui.field(this, "Nama (opsional)", "")
        box.addView(label("URL halaman web"))
        box.addView(urlField, Ui.lp(Ui.MATCH, Ui.WRAP))
        box.addView(label("Nama"))
        box.addView(nameField, Ui.lp(Ui.MATCH, Ui.WRAP))
        val dlg = AlertDialog.Builder(this)
            .setTitle("Tambah Web Source")
            .setView(box)
            .setPositiveButton("Tambah", null)
            .setNegativeButton("Batal", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val r = UrlUtil.normalize(urlField.text.toString())
                if (r.url == null) {
                    urlField.error = r.error
                } else {
                    addSource(r.url, nameField.text.toString())
                    dlg.dismiss()
                }
            }
        }
        dlg.show()
    }

    private fun addSource(url: String, name: String) {
        val n = project.items.size % 5
        val item = SourceItem(
            name = name.trim().ifBlank { UrlUtil.hostOf(url) },
            url = url,
            x = (CANVAS_W - 640) / 2 + n * 40,
            y = (CANVAS_H - 360) / 2 + n * 40,
            w = 640,
            h = 360
        )
        mutate {
            project.items.add(item)
            selectedId = item.id
        }
        setTab(1)
        if (url.startsWith("http://")) {
            Ui.toast(this, "HTTP biasanya diblokir Android. Gunakan HTTPS bila bisa.")
        }
    }

    private fun showRenameDialog() {
        val f = Ui.field(this, "Nama proyek", project.name)
        val box = FrameLayout(this)
        val pad = Ui.dp(this, 20)
        box.setPadding(pad, Ui.dp(this, 8), pad, 0)
        box.addView(f)
        AlertDialog.Builder(this)
            .setTitle("Ganti nama proyek")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                project.name = f.text.toString().trim().ifBlank { "Proyek" }
                titleView.text = project.name
                scheduleSave()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---------------------------------------------------------------- Undo / redo / simpan

    private fun pushUndo() {
        undo.addLast(project.itemsJson())
        if (undo.size > 50) undo.removeFirst()
        redo.clear()
        updateUndoButtons()
    }

    private fun doUndo() {
        if (undo.isEmpty()) return
        redo.addLast(project.itemsJson())
        restore(undo.removeLast())
    }

    private fun doRedo() {
        if (redo.isEmpty()) return
        undo.addLast(project.itemsJson())
        restore(redo.removeLast())
    }

    private fun restore(json: String) {
        val list = Project.parseItems(json)
        project.items.clear()
        project.items.addAll(list)
        if (project.items.none { it.id == selectedId }) selectedId = null
        afterChange()
    }

    private fun updateUndoButtons() {
        btnUndo.alpha = if (undo.isEmpty()) 0.4f else 1f
        btnRedo.alpha = if (redo.isEmpty()) 0.4f else 1f
    }

    private fun mutate(keepProps: Boolean = false, change: () -> Unit) {
        pushUndo()
        change()
        afterChange(keepProps)
    }

    private fun afterChange(keepProps: Boolean = false) {
        canvas.items = project.items
        canvas.selectedId = selectedId
        refreshLayers()
        if (keepProps) updatePosFields() else refreshProps()
        updateUndoButtons()
        composition?.setProject(project)
        scheduleSave()
    }

    private fun scheduleSave() {
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 800)
    }

    private fun saveNow() {
        handler.removeCallbacks(saveRunnable)
        if (!store.save(project)) {
            Ui.toast(this, "Gagal menyimpan proyek")
            return
        }
        // Overlay yang sedang berjalan ikut diperbarui (WebView yang URL-nya sama tidak dimuat ulang).
        if (OverlayService.running && Settings.canDrawOverlays(this)) {
            OverlayService.start(this, project.id)
        }
    }

    private fun finishWithSave() {
        saveNow()
        finish()
    }

    // ---------------------------------------------------------------- Canvas.Listener

    override fun onSelect(id: String?) {
        selectedId = id
        refreshLayers()
        refreshProps()
    }

    override fun onEditBegin() {
        pushUndo()
    }

    override fun onEdited() {
        updatePosFields()
        scheduleSave()
    }

    override fun onEditEnd() {
        composition?.setProject(project)
        refreshLayers()
    }

    // ---------------------------------------------------------------- Preview

    private fun setPreview(on: Boolean) {
        if (on == previewOn) return
        previewOn = on
        if (on) {
            saveNow()
            canvas.visibility = View.INVISIBLE
            val c = CompositionView(this)
            c.setBackgroundColor(Color.WHITE)
            holder.addView(c, FrameLayout.LayoutParams(10, 10, Gravity.CENTER))
            composition = c
            sizePreview()
            c.setProject(project)
            btnPreview.text = "Edit"
            if (project.items.none { it.visible }) {
                Ui.toast(this, "Belum ada elemen yang tampil")
            }
        } else {
            val c = composition
            composition = null
            if (c != null) {
                holder.removeView(c)
                c.release()
            }
            canvas.visibility = View.VISIBLE
            btnPreview.text = "Preview"
        }
    }

    private fun sizePreview() {
        val c = composition ?: return
        val hw = holder.width
        val hh = holder.height
        if (hw <= 0 || hh <= 0) return
        var w = hw
        var h = w * 9 / 16
        if (h > hh) {
            h = hh
            w = h * 16 / 9
        }
        val lp = c.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            c.layoutParams = lp
        }
    }

    private fun reloadPreview() {
        val c = composition
        if (c == null) {
            Ui.toast(this, "Aktifkan Preview dulu untuk memuat ulang halaman")
        } else {
            c.reloadAll()
        }
    }

    // ---------------------------------------------------------------- Overlay & audio

    private fun updateOverlayButton() {
        btnOverlay.text = if (OverlayService.running) "Hentikan overlay" else "Jalankan overlay"
    }

    private fun toggleOverlay() {
        if (OverlayService.running) {
            OverlayService.stop(this)
            handler.postDelayed({ updateOverlayButton() }, 400)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Izin overlay belum aktif")
                .setMessage(
                    "Untuk menampilkan overlay di atas aplikasi lain, aktifkan izin " +
                        "\"Tampil di atas aplikasi lain\". Editor dan Preview tetap bisa dipakai tanpa izin ini."
                )
                .setPositiveButton("Buka halaman izin") { _, _ ->
                    startActivity(Intent(this, OverlaySettingsActivity::class.java))
                }
                .setNegativeButton("Nanti", null)
                .show()
            return
        }
        if (project.items.none { it.visible && it.url.isNotBlank() }) {
            Ui.toast(this, "Tambahkan minimal satu Web Source yang tampil")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !askedNotif
        ) {
            askedNotif = true
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        startOverlay()
    }

    @Deprecated("Deprecated in Android API; dipakai agar tetap sederhana tanpa dependensi")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) {
            // Notifikasi hanya penanda foreground service; overlay tetap dijalankan walau ditolak.
            if (Settings.canDrawOverlays(this) && !OverlayService.running) startOverlay()
        }
    }

    private fun startOverlay() {
        saveNow()
        OverlayService.start(this, project.id)
        Ui.toast(this, "Overlay dijalankan. Buka aplikasi lain untuk melihatnya.")
        handler.postDelayed({
            updateOverlayButton()
            moveTaskToBack(true)
        }, 600)
    }

    private fun openAudio() {
        saveNow()
        audioPending = true
        startActivity(Intent(this, AudioActivity::class.java).putExtra(AudioActivity.EXTRA_PROJECT, project.id))
    }

    // ---------------------------------------------------------------- Lifecycle

    override fun onResume() {
        super.onResume()
        composition?.resumeAll()
        updateOverlayButton()
        if (audioPending) {
            audioPending = false
            store.load(project.id)?.let { project.audio = it.audio }
            composition?.applyAudio(project)
        }
    }

    override fun onPause() {
        super.onPause()
        saveNow()
        composition?.pauseAll()
    }

    override fun onStop() {
        super.onStop()
        // Lepaskan WebView preview saat editor tidak terlihat (hemat RAM, hentikan suara).
        if (previewOn) setPreview(false)
    }

    @Deprecated("Deprecated in Android API; dipakai agar tetap sederhana tanpa dependensi")
    override fun onBackPressed() {
        if (previewOn) {
            setPreview(false)
        } else {
            finishWithSave()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        composition?.release()
        composition = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROJECT = "project_id"
        const val EXTRA_ADD_SOURCE = "add_source"
        private const val REQ_NOTIF = 5001
    }
}
