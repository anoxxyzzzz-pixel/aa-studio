package xtr.keymapper.server

import android.os.Looper
import android.provider.Settings

/**
 * Uji kecil yang dijalankan CI lewat app_process sebagai user shell: memastikan context server
 * boleh memanggil sistem (Settings provider) tanpa SecurityException. Tidak dipakai saat aplikasi berjalan.
 */
object SelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        val ctx = RemoteServiceShell.getContext()
        fun probe(): String = try {
            Settings.Global.getString(ctx.contentResolver, "adb_enabled_selftest_" + System.nanoTime())
            "OK"
        } catch (t: Throwable) {
            t.javaClass.simpleName + ": " + t.message
        }
        println("SELFTEST_BEFORE " + probe())
        if (args.contains("--fix")) {
            RemoteService.fixShellPackage(ctx)
            println("SELFTEST_AFTER " + probe())
        }
    }
}
