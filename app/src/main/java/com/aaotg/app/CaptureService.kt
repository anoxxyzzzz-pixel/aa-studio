package com.aaotg.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager

class CaptureService : Service(), CaptureView.Sink {
    private lateinit var wm: WindowManager
    private var view: CaptureView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startFg()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (view == null) {
            val v = CaptureView(this, this)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            wm.addView(v, lp)
            view = v
            v.requestFocus()
        }
        running = true
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("cap", "AA Mouse OTG", NotificationManager.IMPORTANCE_LOW))
        val stop = android.app.PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, "cap")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("AA Mouse OTG aktif")
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notif), "Hentikan", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(7, n)
    }

    override fun onDestroy() {
        running = false
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
        super.onDestroy()
    }

    // Sink (tahap berikutnya: dipetakan ke sentuhan)
    override fun onMouseMove(dx: Float, dy: Float) {}
    override fun onMouseButton(button: Int, down: Boolean) {}
    override fun onScroll(v: Float) {}
    override fun onKey(code: Int, down: Boolean, repeat: Boolean): Boolean = false

    companion object {
        const val ACTION_STOP = "com.aaotg.app.STOP"
        @Volatile var running = false
    }
}
