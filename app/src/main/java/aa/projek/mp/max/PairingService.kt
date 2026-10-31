package aa.projek.mp.max

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import io.github.muntashirakon.adb.android.AdbMdns

/**
 * Pairing debug nirkabel lewat notifikasi: layanan mencari port pairing (mDNS), lalu menampilkan
 * notifikasi dengan kolom isian kode. Pengguna tidak perlu meninggalkan dialog pairing Android
 * (kode pairing cepat hangus kalau dialognya ditutup).
 */
class PairingService : Service() {
    private var mdns: AdbMdns? = null
    private var port = -1
    private val ui = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val n = build("Mencari layanan pairing…", "Buka Opsi pengembang > Debug nirkabel > Pasangkan dengan kode pairing, lalu tunggu di sini.", false)
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
        if (mdns == null) {
            mdns = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _, p ->
                Log.i(AdbHub.TAG, "PAIRING port=$p")
                ui.post {
                    port = p
                    if (p > 0) update(build("Kode pairing siap diisi", "Ketuk 'Isi kode' lalu ketik 6 digit kode dari dialog pairing.", true))
                    else update(build("Mencari layanan pairing…", "Buka dialog 'Pasangkan dengan kode pairing'.", false))
                }
            }
            mdns!!.start()
        }
        return START_NOT_STICKY
    }

    private fun nm() = getSystemService(NotificationManager::class.java)
    private fun update(n: Notification) = nm().notify(ID, n)

    private fun build(title: String, text: String, withInput: Boolean): Notification {
        nm().createNotificationChannel(NotificationChannel(CH, "Pairing AA Mouse Pro", NotificationManager.IMPORTANCE_HIGH))
        val b = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notif).setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setOnlyAlertOnce(true).setOngoing(true)
        if (withInput) {
            val ri = RemoteInput.Builder(KEY).setLabel("Kode pairing 6 digit").build()
            val pi = PendingIntent.getBroadcast(this, 3, Intent(this, ReplyReceiver::class.java).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notif), "Isi kode", pi).addRemoteInput(ri).build())
        }
        val stop = PendingIntent.getService(this, 4, Intent(this, PairingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notif), "Batal", stop).build())
        return b.build()
    }

    private fun result(title: String, text: String) {
        update(Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_notif).setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build())
    }

    private fun onCode(code: String) {
        val p = port
        if (p <= 0) { result("Belum ada port pairing", "Buka dialog 'Pasangkan dengan kode pairing' dulu."); return }
        update(build("Memasangkan…", "Mohon tunggu.", false))
        AdbHub.pair(this, "127.0.0.1", p, code.trim()) { ok, msg ->
            if (!ok) { ui.post { result("Pairing gagal", msg + " Buka dialog pairing lagi untuk kode baru."); stopSelf() }; return@pair }
            AdbHub.autoConnect(this) { ok2, msg2 ->
                if (!ok2) { ui.post { result("Terpasang, belum tersambung", msg2); stopSelf() }; return@autoConnect }
                AdbHub.startServer(this) { ok3, msg3 ->
                    ui.post { result(if (ok3) "Siap dipakai" else "Server gagal", msg3); stopSelf() }
                }
            }
        }
    }

    override fun onDestroy() {
        try { mdns?.stop() } catch (_: Throwable) {}
        mdns = null
        stopForeground(STOP_FOREGROUND_DETACH)
        instance = null
        super.onDestroy()
    }

    init { instance = this }

    class ReplyReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY)?.toString() ?: return
            Log.i(AdbHub.TAG, "PAIRING code received (${code.length} digit)")
            instance?.onCode(code)
        }
    }

    companion object {
        const val ACTION_STOP = "aa.projek.mp.max.PAIR_STOP"
        private const val CH = "pair"
        private const val ID = 21
        private const val KEY = "code"
        @Volatile var instance: PairingService? = null
    }
}
