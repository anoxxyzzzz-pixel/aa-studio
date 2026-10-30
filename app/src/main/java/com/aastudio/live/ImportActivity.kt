package com.aastudio.live

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.io.IOException

/**
 * Pemilih file untuk Web Source offline. Overlay disembunyikan oleh service selama pemilihan
 * (jendela overlay penuh layar akan menutupi pemilih), lalu editor ditampilkan kembali.
 */
class ImportActivity : Activity() {
    private var sent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/html", "application/zip", "application/x-zip-compressed", "application/octet-stream"))
            startActivityForResult(i, 7)
        } catch (e: Exception) {
            Ui.toast(this, "Pemilih file tidak tersedia")
            back(null, null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            back(null, null)
            return
        }
        Thread {
            var url: String? = null
            var name: String? = null
            var msg: String? = null
            try {
                val r = LocalWeb.import(this, uri)
                url = r.url
                name = r.name
            } catch (e: IOException) {
                msg = e.message
            } catch (e: Exception) {
                msg = "Gagal mengimpor: ${e.message}"
            }
            runOnUiThread {
                if (msg != null) Ui.toast(this, msg)
                back(url, name)
            }
        }.start()
    }

    private fun back(url: String?, name: String?) {
        if (sent) return
        sent = true
        val i = Intent(this, OverlayService::class.java)
            .setAction(OverlayService.ACTION_ADD_LOCAL)
        if (url != null) i.putExtra(OverlayService.EXTRA_URL, url).putExtra(OverlayService.EXTRA_NAME, name)
        startForegroundService(i)
        finish()
    }

    override fun onDestroy() {
        // Jika activity dimatikan sistem sebelum selesai, pastikan editor kembali tampil.
        if (!sent && isFinishing) back(null, null)
        super.onDestroy()
    }
}
