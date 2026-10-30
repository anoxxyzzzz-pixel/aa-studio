package aa.projek.mp.max

import android.content.Context
import android.util.Log
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

/** Penghubung ke ADB lokal (debug nirkabel / debugging USB-TCP) dan server injeksi sentuhan. */
object AdbHub {
    const val TAG = "AAOTG"

    @Volatile var connected = false
    @Volatile var serverReady = false
    @Volatile var message = "Belum tersambung"

    private var out: OutputStream? = null
    private val queue = LinkedBlockingQueue<String>()
    @Volatile private var writer: Thread? = null

    private fun mgr(ctx: Context) = AdbConnectionManager.getInstance(ctx.applicationContext)

    private fun async(name: String, cb: (Boolean, String) -> Unit, block: () -> String) {
        Thread({
            val r = try {
                true to block()
            } catch (t: Throwable) {
                Log.w(TAG, "$name gagal: $t")
                false to friendly(t)
            }
            message = r.second
            cb(r.first, r.second)
        }, "adb-$name").start()
    }

    private fun friendly(t: Throwable): String = when {
        t.javaClass.simpleName.contains("Pairing") -> "Perlu pairing dulu (Opsi pengembang > Debug nirkabel > Pasangkan dengan kode)."
        t is java.net.ConnectException || (t.message ?: "").contains("refused", true) -> "Ditolak/tidak ada layanan di port itu. Pastikan Debug nirkabel (atau adb tcpip) aktif dan port benar."
        else -> (t.message ?: t.javaClass.simpleName).take(160)
    }

    fun pair(ctx: Context, host: String, port: Int, code: String, cb: (Boolean, String) -> Unit) =
        async("pair", cb) {
            val ok = mgr(ctx).pair(host, port, code)
            if (!ok) throw RuntimeException("Pairing ditolak. Periksa kode dan port pairing.")
            "Pairing berhasil. Sekarang sambungkan memakai port koneksi."
        }

    fun connect(ctx: Context, host: String, port: Int, cb: (Boolean, String) -> Unit) =
        async("connect", cb) {
            val m = mgr(ctx)
            if (!m.isConnected && !m.connect(host, port)) throw RuntimeException("Gagal tersambung ke $host:$port")
            connected = true
            "Tersambung ke ADB ($host:$port)"
        }

    fun autoConnect(ctx: Context, cb: (Boolean, String) -> Unit) =
        async("auto", cb) {
            val m = mgr(ctx)
            if (!m.isConnected && !m.autoConnect(ctx.applicationContext, 12000)) throw RuntimeException("Tidak menemukan layanan debug nirkabel. Aktifkan Debug nirkabel dulu.")
            connected = true
            "Tersambung ke ADB (otomatis)"
        }

    /** Menjalankan Injector sebagai user shell lewat ADB. */
    fun startServer(ctx: Context, cb: (Boolean, String) -> Unit) =
        async("server", cb) {
            val m = mgr(ctx)
            if (!m.isConnected) throw RuntimeException("Belum tersambung ke ADB.")
            stopServerInternal()
            val apk = ctx.applicationInfo.sourceDir
            val stream = m.openStream("shell:CLASSPATH=$apk exec app_process /system/bin aa.projek.mp.max.Injector")
            out = stream.openOutputStream()
            val ins = stream.openInputStream()
            serverReady = false
            Thread({
                try {
                    ins.bufferedReader().forEachLine { l ->
                        Log.i(TAG, "SERVER $l")
                        if (l.startsWith("READY")) serverReady = true
                    }
                } catch (_: Throwable) {}
                serverReady = false
            }, "adb-server-read").start()
            val w = Thread({
                try {
                    while (true) {
                        val l = queue.take()
                        val o = out ?: break
                        o.write((l + "\n").toByteArray()); o.flush()
                    }
                } catch (_: Throwable) {}
            }, "adb-server-write")
            w.start(); writer = w
            var waited = 0
            while (!serverReady && waited < 8000) { Thread.sleep(100); waited += 100 }
            if (!serverReady) throw RuntimeException("Server injeksi tidak merespons. Coba sambungkan ulang.")
            "Server injeksi siap"
        }

    private fun stopServerInternal() {
        try { queue.clear(); queue.offer("q") } catch (_: Throwable) {}
        writer?.interrupt(); writer = null
        try { out?.close() } catch (_: Throwable) {}
        out = null; serverReady = false
    }

    fun stopServer() = stopServerInternal()

    fun send(line: String) {
        if (serverReady) queue.offer(line)
    }

    fun disconnect(ctx: Context) {
        stopServerInternal()
        Thread { try { mgr(ctx).disconnect() } catch (_: Throwable) {}; connected = false }.start()
    }

    fun isLive(ctx: Context): Boolean = try { connected && mgr(ctx).isConnected } catch (_: Throwable) { false }
}
