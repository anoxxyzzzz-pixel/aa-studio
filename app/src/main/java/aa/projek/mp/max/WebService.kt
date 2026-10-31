package aa.projek.mp.max

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Menampilkan semua Web Source sebagai panel melayang di atas aplikasi/game.
 * Tiap panel: bilah atas (geser), tombol suara/muat ulang/tutup, WebView (bersuara), pegangan ubah ukuran.
 */
class WebService : Service() {
    private class Panel(val item: SourceItem, val root: FrameLayout, val cell: SourceCell, val lp: WindowManager.LayoutParams)

    private lateinit var wm: WindowManager
    private val panels = ArrayList<Panel>()
    private var sw = 0
    private var sh = 0
    private val audio = AudioSettings()

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        fg()
        if (!Settings.canDrawOverlays(this)) { Toast.makeText(this, "Izin tampil di atas aplikasi lain belum aktif", Toast.LENGTH_LONG).show(); stopSelf(); return START_NOT_STICKY }
        val b = wm.currentWindowMetrics.bounds
        sw = b.width(); sh = b.height()
        rebuild()
        running = true
        return START_NOT_STICKY
    }

    private fun fg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("web", "Web Source", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 5, Intent(this, WebService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, "web").setSmallIcon(R.drawable.ic_notif).setContentTitle("Web Source tampil")
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notif), "Tutup semua", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(8, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(8, n)
    }

    private fun clear() {
        for (p in panels) { try { wm.removeView(p.root) } catch (_: Exception) {}; p.cell.release() }
        panels.clear()
    }

    private fun rebuild() {
        clear()
        val items = WebStore.load(this).filter { it.visible && it.url.isNotBlank() }
        if (items.isEmpty()) Toast.makeText(this, "Belum ada Web Source. Tambahkan di aplikasi.", Toast.LENGTH_LONG).show()
        for (it in items) addPanel(it)
    }

    private fun addPanel(item: SourceItem) {
        val bar = dp(34)
        val root = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(Color.parseColor("#CC101418")); cornerRadius = dp(8).toFloat(); setStroke(dp(1), Color.parseColor("#4F8CFF")) }
        }
        val cell = SourceCell(this, item.copy())
        cell.applyUiScale(1f)
        cell.applyAudio(audio, false)
        root.addView(cell, FrameLayout.LayoutParams(-1, -1).apply { topMargin = bar })

        val title = TextView(this).apply { text = item.name; setTextColor(Color.WHITE); textSize = 12f; maxLines = 1; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, 0, 0) }
        fun tool(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER; setOnClickListener { onClick() }
        }
        val mute = tool(if (item.muted) "🔇" else "🔊") {}
        mute.setOnClickListener {
            item.muted = !item.muted
            (it as TextView).text = if (item.muted) "🔇" else "🔊"
            cell.item = item.copy(); cell.applyAudio(audio, false)
            save(item)
        }
        val reload = tool("⟳") { cell.load() }
        val close = tool("✕") { item.visible = false; save(item); panels.firstOrNull { it.item === item }?.let { remove(it) } }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.parseColor("#1A1E26"))
            addView(title, LinearLayout.LayoutParams(0, -1, 1f))
            for (t in listOf(mute, reload, close)) addView(t, LinearLayout.LayoutParams(dp(40), -1))
        }
        root.addView(top, FrameLayout.LayoutParams(-1, bar))
        val grip = View(this).apply { background = GradientDrawable().apply { setColor(Color.parseColor("#4F8CFF")); cornerRadius = dp(4).toFloat() } }
        root.addView(grip, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.BOTTOM or Gravity.END))

        val lp = WindowManager.LayoutParams(
            (item.w * sw).toInt(), (item.h * sh).toInt(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (item.x * sw).toInt(); y = (item.y * sh).toInt() }

        // geser dari bilah atas
        var dx = 0f; var dy = 0f; var ox = 0; var oy = 0
        title.setOnTouchListener { _, e ->
            android.util.Log.i("AAOTG", "PANEL_TOUCH ${e.actionMasked} raw=${e.rawX.toInt()},${e.rawY.toInt()} lp=${lp.x},${lp.y}")
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; ox = lp.x; oy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (ox + e.rawX - dx).toInt().coerceIn(0, (sw - dp(60)).coerceAtLeast(0))
                    lp.y = (oy + e.rawY - dy).toInt().coerceIn(0, (sh - dp(60)).coerceAtLeast(0))
                    wm.updateViewLayout(root, lp)
                }
                MotionEvent.ACTION_UP -> { item.x = lp.x / sw.toFloat(); item.y = lp.y / sh.toFloat(); save(item) }
            }
            true
        }
        // ubah ukuran dari pegangan kanan-bawah
        var rw = 0; var rh = 0
        grip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; rw = lp.width; rh = lp.height }
                MotionEvent.ACTION_MOVE -> {
                    lp.width = (rw + e.rawX - dx).toInt().coerceIn(dp(140), sw)
                    lp.height = (rh + e.rawY - dy).toInt().coerceIn(dp(120), sh)
                    wm.updateViewLayout(root, lp)
                }
                MotionEvent.ACTION_UP -> { item.w = lp.width / sw.toFloat(); item.h = lp.height / sh.toFloat(); save(item) }
            }
            true
        }
        wm.addView(root, lp)
        android.util.Log.i("AAOTG", "PANEL_ADDED lp=${lp.x},${lp.y} size=${lp.width}x${lp.height} screen=${sw}x${sh}")
        cell.load()
        panels.add(Panel(item, root, cell, lp))
    }

    private fun remove(p: Panel) {
        try { wm.removeView(p.root) } catch (_: Exception) {}
        p.cell.release(); panels.remove(p)
        if (panels.isEmpty()) stopSelf()
    }

    private fun save(changed: SourceItem) {
        val all = WebStore.load(this)
        val i = all.indexOfFirst { it.id == changed.id }
        if (i >= 0) all[i] = changed.copy() else all.add(changed.copy())
        WebStore.save(this, all)
    }

    override fun onDestroy() { clear(); running = false; super.onDestroy() }

    companion object {
        const val ACTION_STOP = "aa.projek.mp.max.WEB_STOP"
        @Volatile var running = false
    }
}
