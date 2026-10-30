package xtr.keymapper.server

import java.io.File

/**
 * Jejak diagnosis dari proses server (berjalan sebagai shell lewat Shizuku) ke aplikasi.
 * Ditulis ke folder file eksternal milik aplikasi, yang bisa ditulis shell dan dibaca aplikasi.
 */
object Diag {
    const val DIR = "/sdcard/Android/data/com.aamouse.pro/files"

    private fun write(name: String, text: String) {
        try {
            val d = File(DIR)
            d.mkdirs()
            File(d, name).appendText(text + "\n")
        } catch (_: Throwable) {
        }
    }

    fun mark(msg: String) = write("server_log.txt", "${System.currentTimeMillis() % 100000} $msg")

    fun error(t: Throwable) = write("server_log.txt", "ERROR " + android.util.Log.getStackTraceString(t).take(900))

    /** Dipanggil aplikasi sebelum menyambung, agar log lama tidak membingungkan. */
    fun clear(dir: File?) {
        try {
            File(dir ?: return, "server_log.txt").delete()
        } catch (_: Throwable) {
        }
    }

    fun read(dir: File?): String? = try {
        File(dir ?: return null, "server_log.txt").takeIf { it.exists() }?.readText()
    } catch (_: Throwable) {
        null
    }

    /** Pesan penyebab untuk ditampilkan ke pengguna. */
    fun explain(dir: File?): String {
        val log = read(dir)
        return when {
            log == null -> "Server tidak meninggalkan jejak (belum mulai, atau tidak bisa menulis log). Mulai ulang Shizuku lalu coba lagi."
            log.contains("ERROR") -> "Server mulai tapi gagal: " + log.substringAfter("ERROR").trim().lineSequence().take(3).joinToString(" ").take(260)
            log.contains("ready") -> "Server berjalan tapi tidak merespons ke aplikasi. Mulai ulang Shizuku lalu coba lagi."
            else -> "Server berhenti saat mulai: " + log.trim().take(200)
        }
    }
}
