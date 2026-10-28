package com.aastudio.live

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Halaman izin "Tampil di atas aplikasi lain" (SYSTEM_ALERT_WINDOW).
 * Status dibaca langsung dari Settings.canDrawOverlays() dan diperiksa ulang setiap kembali ke halaman ini.
 */
class OverlaySettingsActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var help: TextView
    private lateinit var stopBtn: android.widget.Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.WHITE)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 24))
        scroll.addView(col, Ui.lp(Ui.MATCH, Ui.WRAP))

        col.addView(Ui.text(this, "Izin Overlay", 22f, true))
        col.addView(
            Ui.text(
                this,
                "Izin \"Tampil di atas aplikasi lain\" dipakai agar komposisi Web Source Anda bisa " +
                    "muncul sebagai jendela melayang di atas aplikasi lain. Izin ini hanya dipakai " +
                    "saat Anda menekan Jalankan overlay, dan bisa Anda cabut kapan saja di Pengaturan Android.",
                14f
            ),
            Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 8), 0, Ui.dp(this, 14))
        )

        status = Ui.text(this, "", 16f, true)
        status.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12))
        col.addView(status, Ui.lp(Ui.MATCH, Ui.WRAP))

        help = Ui.text(this, "", 13f, false, Ui.MUTED)
        col.addView(help, Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 10), 0, Ui.dp(this, 10)))

        col.addView(Ui.button(this, "Aktifkan Izin Overlay", true) { openPermissionScreen() }, Ui.lp(Ui.MATCH, Ui.WRAP))

        stopBtn = Ui.button(this, "Hentikan overlay yang sedang berjalan") {
            OverlayService.stop(this)
            Ui.toast(this, "Overlay dihentikan")
            refresh()
        }
        col.addView(stopBtn, Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 8), 0, 0))

        col.addView(
            Ui.text(
                this,
                "Tanpa izin ini, Editor dan Web Source tetap bisa dipakai seperti biasa " +
                    "(termasuk Preview di dalam aplikasi). Hanya jendela melayang yang tidak tersedia.",
                12f, false, Ui.MUTED
            ),
            Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 14), 0, Ui.dp(this, 14))
        )
        col.addView(Ui.button(this, "Kembali") { finish() }, Ui.lp(Ui.MATCH, Ui.WRAP))

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val granted = Settings.canDrawOverlays(this)
        if (granted) {
            status.text = "Status: AKTIF"
            status.setTextColor(Ui.OK)
            status.background = Ui.box(this, Ui.WHITE, Ui.OK)
            help.text = "Izin sudah diberikan. Anda bisa menjalankan overlay dari Editor."
        } else {
            status.text = "Status: BELUM AKTIF"
            status.setTextColor(Ui.BAD)
            status.background = Ui.box(this, Ui.WHITE, Ui.BAD)
            help.text = "Cara mengaktifkan: tekan tombol di bawah, cari \"AA Studio\" bila diminta, " +
                "lalu nyalakan \"Izinkan tampil di atas aplikasi lain\". Setelah itu kembali ke aplikasi ini; " +
                "status akan diperiksa ulang otomatis."
        }
        stopBtn.visibility = if (OverlayService.running) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun openPermissionScreen() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            help.text = "Halaman pengaturan ini tidak tersedia di perangkat Anda. Buka manual: " +
                "Pengaturan > Aplikasi > AA Studio > Tampil di atas aplikasi lain (nama menu bisa berbeda " +
                "di tiap merek HP). Editor dan Web Source tetap bisa dipakai tanpa izin ini."
        }
    }
}
