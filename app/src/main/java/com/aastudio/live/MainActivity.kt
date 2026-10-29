package com.aastudio.live

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/** Layar utama: logo AA, status izin overlay, dan daftar proyek. */
class MainActivity : Activity() {

    private lateinit var store: ProjectStore
    private lateinit var list: LinearLayout
    private lateinit var permStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProjectStore(this)

        // Ikon ditekan: langsung tampilkan editor di atas aplikasi lain, tanpa membuka layar aplikasi.
        if (!intent.getBooleanExtra(EXTRA_NO_AUTO, false)) {
            if (Settings.canDrawOverlays(this)) {
                OverlayService.launchDefault(this)
            } else {
                startActivity(
                    Intent(this, OverlaySettingsActivity::class.java)
                        .putExtra(OverlaySettingsActivity.EXTRA_LAUNCH_AFTER, true)
                )
            }
            finish()
            return
        }

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.WHITE)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(this, 16)
        col.setPadding(pad, Ui.dp(this, 24), pad, pad)
        scroll.addView(col, Ui.lp(Ui.MATCH, Ui.WRAP))

        val logo = TextView(this)
        logo.text = "AA"
        logo.textSize = 40f
        logo.setTextColor(Ui.BLACK)
        logo.typeface = android.graphics.Typeface.DEFAULT_BOLD
        logo.gravity = Gravity.CENTER
        logo.background = Ui.box(this, Ui.WHITE, Ui.LINE, 12, 1)
        col.addView(logo, Ui.lp(Ui.dp(this, 84), Ui.dp(this, 84)))

        col.addView(
            Ui.text(this, "AA Mouse Pro", 24f, true),
            Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 10), 0, 0)
        )
        col.addView(
            Ui.text(this, "Editor overlay + Web Source", 13f, false, Ui.MUTED),
            Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, 0, 0, Ui.dp(this, 14))
        )

        permStatus = Ui.text(this, "", 13f, true)
        permStatus.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10))
        permStatus.setOnClickListener { startActivity(Intent(this, OverlaySettingsActivity::class.java)) }
        col.addView(permStatus, Ui.lp(Ui.MATCH, Ui.WRAP))

        col.addView(
            Ui.button(this, "+ Proyek baru", true) { showNewProjectDialog() },
            Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 14), 0, Ui.dp(this, 10))
        )
        col.addView(Ui.text(this, "Proyek", 15f, true))

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        col.addView(list, Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 8), 0, 0))

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val granted = Settings.canDrawOverlays(this)
        permStatus.text = if (granted) {
            "Izin overlay: AKTIF"
        } else {
            "Izin overlay: BELUM AKTIF (ketuk untuk mengatur)"
        }
        permStatus.setTextColor(if (granted) Ui.OK else Ui.BAD)
        permStatus.background = Ui.box(this, Ui.WHITE, if (granted) Ui.OK else Ui.BAD)

        list.removeAllViews()
        val projects = store.list()
        if (projects.isEmpty()) {
            list.addView(Ui.text(this, "Belum ada proyek. Buat proyek baru untuk mulai.", 13f, false, Ui.MUTED))
            return
        }
        for (p in projects) list.addView(row(p), Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, 0, 0, Ui.dp(this, 8)))
    }

    private fun row(p: Project): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10))
        r.background = Ui.box(this, Ui.WHITE, Ui.LINE)
        r.setOnClickListener { open(p) }

        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        val name = Ui.text(this, p.name, 15f, true)
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        info.addView(name)
        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.updated))
        info.addView(Ui.text(this, "${p.items.size} elemen · $date", 12f, false, Ui.MUTED))
        r.addView(info, Ui.lp(0, Ui.WRAP, 1f))

        val size = Ui.dp(this, 40)
        r.addView(Ui.iconBtn(this, "▶") { launchOverlay(p) }, Ui.lpM(size, size, 0f, Ui.dp(this, 4), 0, 0, 0))
        r.addView(Ui.iconBtn(this, "✎") { showRenameDialog(p) }, Ui.lpM(size, size, 0f, Ui.dp(this, 4), 0, 0, 0))
        r.addView(Ui.iconBtn(this, "🗑") { confirmDelete(p) }, Ui.lpM(size, size, 0f, Ui.dp(this, 4), 0, 0, 0))
        return r
    }

    private fun launchOverlay(p: Project) {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(this, OverlaySettingsActivity::class.java))
            return
        }
        OverlayService.start(this, p.id)
        moveTaskToBack(true)
    }

    private fun open(p: Project) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_PROJECT, p.id))
    }

    private fun showNewProjectDialog() {
        val f = Ui.field(this, "Nama proyek", "Proyek ${store.list().size + 1}")
        val box = FrameLayout(this)
        val pad = Ui.dp(this, 20)
        box.setPadding(pad, Ui.dp(this, 8), pad, 0)
        box.addView(f)
        AlertDialog.Builder(this)
            .setTitle("Proyek baru")
            .setView(box)
            .setPositiveButton("Buat") { _, _ ->
                val p = store.create(f.text.toString())
                startActivity(
                    Intent(this, EditorActivity::class.java)
                        .putExtra(EditorActivity.EXTRA_PROJECT, p.id)
                        .putExtra(EditorActivity.EXTRA_ADD_SOURCE, true)
                )
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showRenameDialog(p: Project) {
        val f = Ui.field(this, "Nama proyek", p.name)
        val box = FrameLayout(this)
        val pad = Ui.dp(this, 20)
        box.setPadding(pad, Ui.dp(this, 8), pad, 0)
        box.addView(f)
        AlertDialog.Builder(this)
            .setTitle("Ganti nama")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                val fresh = store.load(p.id) ?: return@setPositiveButton
                fresh.name = f.text.toString().trim().ifBlank { "Proyek" }
                store.save(fresh)
                refresh()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun confirmDelete(p: Project) {
        AlertDialog.Builder(this)
            .setTitle("Hapus proyek?")
            .setMessage("Proyek \"${p.name}\" beserta semua elemennya akan dihapus permanen.")
            .setPositiveButton("Hapus") { _, _ ->
                store.delete(p.id)
                refresh()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    companion object {
        const val EXTRA_NO_AUTO = "no_auto"
    }
}
