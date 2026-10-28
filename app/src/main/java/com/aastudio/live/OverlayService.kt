package com.aastudio.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

/**
 * Overlay melayang sungguhan: jendela TYPE_APPLICATION_OVERLAY lewat WindowManager.
 * Berjalan sebagai foreground service dengan notifikasi (ada tombol Hentikan).
 * Header jendela: geser untuk memindahkan, − / + ukuran, Suara (mute), Sentuh (izinkan/blokir
 * sentuhan ke halaman web), ↻ muat ulang, ✕ tutup.
 */
class OverlayService : Service() {

    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var header: LinearLayout? = null
    private var composition: CompositionView? = null
    private var lp: WindowManager.LayoutParams? = null

    private var widthPercent = 60
    private var touchEnabled = false
    private var muted = false
    private var dragX = 0f
    private var dragY = 0f
    private var startPX = 0
    private var startPY = 0

    private var muteBtn: TextView? = null
    private var touchBtn: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Wajib segera memanggil startForeground setelah startForegroundService.
        startAsForeground()

        val id = intent?.getStringExtra(EXTRA_PROJECT)
        if (id == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            Ui.toast(this, "Izin tampil di atas aplikasi lain belum aktif")
            stopSelf()
            return START_NOT_STICKY
        }
        val project = ProjectStore(this).load(id)
        if (project == null) {
            Ui.toast(this, "Proyek tidak ditemukan")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            if (root == null) {
                widthPercent = project.overlay.widthPercent
                touchEnabled = project.overlay.touchEnabled
                buildOverlay()
            }
            composition?.interactive = touchEnabled
            composition?.setProject(project)
            running = true
        } catch (e: Exception) {
            Ui.toast(this, "Gagal menampilkan overlay: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Overlay AA Studio", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notif), "Hentikan", stopIntent
        ).build()
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("AA Studio — overlay aktif")
            .setContentText("Overlay tampil di atas aplikasi lain")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(stopAction)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private fun headerButton(label: String, onClick: (TextView) -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 13f
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.minWidth = dp(38)
        t.setPadding(dp(6), 0, dp(6), 0)
        t.isClickable = true
        t.setOnClickListener { onClick(t) }
        return t
    }

    private fun buildOverlay() {
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager

        val hdr = LinearLayout(this)
        hdr.orientation = LinearLayout.HORIZONTAL
        hdr.gravity = Gravity.CENTER_VERTICAL
        hdr.setBackgroundColor(Color.parseColor("#E6111111"))

        val title = TextView(this)
        title.text = "AA Studio"
        title.textSize = 12f
        title.setTextColor(Color.WHITE)
        title.setPadding(dp(8), 0, dp(4), 0)
        title.maxLines = 1
        hdr.addView(title, LinearLayout.LayoutParams(0, Ui.MATCH, 1f))

        hdr.addView(headerButton("−") { changeWidth(-10) }, LinearLayout.LayoutParams(Ui.WRAP, Ui.MATCH))
        hdr.addView(headerButton("+") { changeWidth(10) }, LinearLayout.LayoutParams(Ui.WRAP, Ui.MATCH))
        val mb = headerButton(if (muted) "Mute" else "Suara") { toggleMute() }
        muteBtn = mb
        hdr.addView(mb, LinearLayout.LayoutParams(Ui.WRAP, Ui.MATCH))
        val tb = headerButton(if (touchEnabled) "Sentuh:ON" else "Sentuh:OFF") { toggleTouch() }
        touchBtn = tb
        hdr.addView(tb, LinearLayout.LayoutParams(Ui.WRAP, Ui.MATCH))
        hdr.addView(headerButton("↻") { composition?.reloadAll() }, LinearLayout.LayoutParams(Ui.WRAP, Ui.MATCH))
        hdr.addView(headerButton("✕") { stopSelf() }, LinearLayout.LayoutParams(Ui.WRAP, Ui.MATCH))
        header = hdr

        val comp = CompositionView(this)
        comp.setBackgroundColor(Color.argb(40, 0, 0, 0))
        comp.interactive = touchEnabled
        composition = comp

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.addView(hdr, LinearLayout.LayoutParams(Ui.MATCH, dp(36)))
        container.addView(comp, LinearLayout.LayoutParams(Ui.MATCH, 100))
        root = container

        val params = WindowManager.LayoutParams(
            Ui.WRAP, Ui.WRAP,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = dp(16)
        params.y = dp(80)
        lp = params

        hdr.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragX = ev.rawX
                    dragY = ev.rawY
                    startPX = params.x
                    startPY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dm = resources.displayMetrics
                    val maxX = max(0, dm.widthPixels - params.width)
                    val maxY = max(0, dm.heightPixels - params.height)
                    params.x = (startPX + (ev.rawX - dragX)).toInt().coerceIn(0, maxX)
                    params.y = (startPY + (ev.rawY - dragY)).toInt().coerceIn(0, maxY)
                    try {
                        manager.updateViewLayout(container, params)
                    } catch (e: IllegalArgumentException) {
                        // jendela sudah dilepas
                    }
                }
            }
            true
        }

        applySize(params)
        manager.addView(container, params)
    }

    /** Menghitung ukuran jendela dari persentase lebar layar; tinggi mengikuti rasio 16:9. */
    private fun applySize(params: WindowManager.LayoutParams) {
        val container = root ?: return
        val comp = composition ?: return
        val hdr = header ?: return
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val minW = dp(260)
        val wpx = (screenW * widthPercent / 100f).toInt().coerceIn(minW, max(minW, screenW))
        val compH = (wpx * CANVAS_H / CANVAS_W.toFloat()).toInt()
        hdr.layoutParams = LinearLayout.LayoutParams(wpx, dp(36))
        comp.layoutParams = LinearLayout.LayoutParams(wpx, compH)
        params.width = wpx
        params.height = dp(36) + compH
        params.x = params.x.coerceIn(0, max(0, screenW - wpx))
        params.y = params.y.coerceIn(0, max(0, dm.heightPixels - params.height))
        if (container.isAttachedToWindow) {
            try {
                wm?.updateViewLayout(container, params)
            } catch (e: IllegalArgumentException) {
                // abaikan
            }
        }
    }

    private fun changeWidth(delta: Int) {
        widthPercent = (widthPercent + delta).coerceIn(30, 100)
        lp?.let { applySize(it) }
    }

    private fun toggleMute() {
        muted = !muted
        composition?.setForceMute(muted)
        muteBtn?.text = if (muted) "Mute" else "Suara"
    }

    private fun toggleTouch() {
        touchEnabled = !touchEnabled
        composition?.interactive = touchEnabled
        touchBtn?.text = if (touchEnabled) "Sentuh:ON" else "Sentuh:OFF"
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        lp?.let { applySize(it) }
    }

    private fun removeOverlay() {
        composition?.release()
        composition = null
        val r = root
        if (r != null) {
            try {
                wm?.removeView(r)
            } catch (e: IllegalArgumentException) {
                // sudah dilepas
            }
        }
        root = null
        header = null
        muteBtn = null
        touchBtn = null
    }

    override fun onDestroy() {
        removeOverlay()
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.aastudio.live.START"
        const val ACTION_STOP = "com.aastudio.live.STOP"
        const val EXTRA_PROJECT = "project_id"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIF_ID = 4201

        @Volatile
        var running: Boolean = false

        fun start(ctx: Context, projectId: String) {
            val i = Intent(ctx, OverlayService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROJECT, projectId)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }
}
