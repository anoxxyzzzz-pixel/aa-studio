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
import android.graphics.Point
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Overlay sungguhan (TYPE_APPLICATION_OVERLAY) lewat WindowManager, berjalan sebagai foreground service.
 *
 * - Mode EDITOR: satu jendela layar penuh ([EditLayer]) tempat kotak Web Source diatur langsung
 *   di atas aplikasi lain. Tidak ada WebView yang dimuat (hemat RAM).
 * - Mode STREAM: satu jendela per elemen yang tampil (WebView). Sentuhan menembus ke aplikasi di
 *   bawahnya kecuali pengguna menyalakan "Sentuh". Bubble "AA" kecil menyediakan Editor / Mute / Sentuh / Stop.
 */
class OverlayService : Service(), EditLayer.Host {

    private enum class Mode { EDIT, RUN }

    private class Stream(val cell: SourceCell, val lp: WindowManager.LayoutParams)

    private lateinit var wm: WindowManager
    private lateinit var store: ProjectStore
    private val handler = Handler(Looper.getMainLooper())

    private var project: Project? = null
    private var mode = Mode.EDIT

    private var editLayer: EditLayer? = null
    private var editLp: WindowManager.LayoutParams? = null
    private val streams = ArrayList<Stream>()
    private var bubble: LinearLayout? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var bubbleMenu: LinearLayout? = null
    private var muteLabel: TextView? = null
    private var touchLabel: TextView? = null

    private var muted = false
    private var touchEnabled = false

    private val saveRunnable = Runnable { project?.let { store.save(it) } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        store = ProjectStore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Wajib segera memanggil startForeground setelah startForegroundService.
        startAsForeground()

        if (!Settings.canDrawOverlays(this)) {
            Ui.toast(this, "Izin tampil di atas aplikasi lain belum aktif")
            stopSelf()
            return START_NOT_STICKY
        }

        val id = intent?.getStringExtra(EXTRA_PROJECT)
        if (id != null && id != project?.id) {
            flushSave()
            clearWindows()
            project = store.load(id)
        }
        val p = project
        if (p == null) {
            Ui.toast(this, "Proyek tidak ditemukan")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            if (running && action == ACTION_RELOAD) {
                project = store.load(p.id) ?: p
            }
            touchEnabled = project?.overlay?.touchEnabled ?: false
            if (action == ACTION_ADD_LOCAL) addLocalItem(intent.getStringExtra(EXTRA_URL), intent.getStringExtra(EXTRA_NAME))
            showMode(if (action == ACTION_RUN) Mode.RUN else Mode.EDIT)
            running = true
        } catch (e: Exception) {
            Ui.toast(this, "Gagal menampilkan overlay: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ notifikasi

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Overlay AA Studio", NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), flags
        )
        val editIntent = PendingIntent.getForegroundService(
            this, 3, Intent(this, OverlayService::class.java).setAction(ACTION_EDIT), flags
        )
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_NO_AUTO, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )
        val editAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notif), "Editor", editIntent
        ).build()
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notif), "Hentikan", stopIntent
        ).build()
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("AA Studio — overlay aktif")
            .setContentText("Ketuk untuk daftar proyek")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(editAction)
            .addAction(stopAction)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ------------------------------------------------------------------ mode

    private fun screenSize(): Point {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Point(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun baseFlags(): Int =
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun newParams(w: Int, h: Int, flags: Int): WindowManager.LayoutParams {
        // Akselerasi hardware hanya untuk jendela WebView (hemat memori GPU pada editor & bubble).
        val p = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return p
    }

    private fun showMode(m: Mode) {
        val p = project ?: return
        clearWindows()
        mode = m
        val size = screenSize()
        if (m == Mode.EDIT) {
            val lp = newParams(size.x, size.y, baseFlags())
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            val layer = EditLayer(this, p, size.x, size.y, this)
            editLayer = layer
            editLp = lp
            wm.addView(layer, lp)
            layer.requestFocus()
        } else {
            val sx = size.x / CANVAS_W.toFloat()
            val sy = size.y / CANVAS_H.toFloat()
            for (item in p.items) {
                if (!item.visible || item.url.isBlank()) continue
                val w = (item.w * sx).toInt().coerceAtLeast(Ui.dp(this, 20))
                val h = (item.h * sy).toInt().coerceAtLeast(Ui.dp(this, 20))
                var flags = baseFlags() or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                if (!touchEnabled) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                val lp = newParams(w, h, flags)
                lp.x = (item.x * sx).toInt()
                lp.y = (item.y * sy).toInt()
                val cell = SourceCell(this, item.copy())
                cell.applyUiScale(1f)
                cell.applyAudio(p.audio, muted)
                wm.addView(cell, lp)
                cell.load()
                streams.add(Stream(cell, lp))
            }
            if (streams.isEmpty()) {
                Ui.toast(this, "Belum ada Web Source yang tampil. Buka Editor untuk menambah.")
            }
            addBubble(size)
        }
    }

    private fun clearWindows() {
        handler.removeCallbacks(saveRunnable)
        editLayer?.let {
            try {
                wm.removeView(it)
            } catch (e: IllegalArgumentException) {
                // sudah dilepas
            }
        }
        editLayer = null
        editLp = null
        for (s in streams) {
            try {
                wm.removeView(s.cell)
            } catch (e: IllegalArgumentException) {
                // sudah dilepas
            }
            s.cell.release()
        }
        streams.clear()
        bubble?.let {
            try {
                wm.removeView(it)
            } catch (e: IllegalArgumentException) {
                // sudah dilepas
            }
        }
        bubble = null
        bubbleLp = null
        bubbleMenu = null
        muteLabel = null
        touchLabel = null
    }

    // ------------------------------------------------------------------ bubble kontrol (mode Stream)

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private fun menuItem(label: String, onClick: (TextView) -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 13f
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.typeface = android.graphics.Typeface.DEFAULT_BOLD
        t.setPadding(dp(12), 0, dp(12), 0)
        val g = GradientDrawable()
        g.cornerRadius = dp(18).toFloat()
        g.setColor(Color.parseColor("#E6111111"))
        t.background = g
        t.setOnClickListener { onClick(t) }
        return t
    }

    private fun addBubble(size: Point) {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.START

        val dot = TextView(this)
        dot.text = "AA"
        dot.textSize = 15f
        dot.gravity = Gravity.CENTER
        dot.setTextColor(Color.BLACK)
        dot.typeface = android.graphics.Typeface.DEFAULT_BOLD
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(Color.WHITE)
        g.setStroke(dp(2), Color.BLACK)
        dot.background = g
        root.addView(dot, LinearLayout.LayoutParams(dp(46), dp(46)))

        // Menu menumpuk ke bawah (bukan menyamping) agar tidak melebar keluar layar.
        val menu = LinearLayout(this)
        menu.orientation = LinearLayout.VERTICAL
        menu.visibility = View.GONE
        val itemLp = { LinearLayout.LayoutParams(Ui.MATCH, dp(36)).also { it.topMargin = dp(6) } }
        menu.addView(menuItem("Editor") { showMode(Mode.EDIT) }, itemLp())
        val mt = menuItem(if (muted) "Mute" else "Suara") { toggleMute() }
        muteLabel = mt
        menu.addView(mt, itemLp())
        val tt = menuItem(if (touchEnabled) "Sentuh:ON" else "Sentuh:OFF") { toggleTouch() }
        touchLabel = tt
        menu.addView(tt, itemLp())
        menu.addView(menuItem("Vol+") { volumeUp() }, itemLp())
        menu.addView(menuItem("Stop") { stopSelf() }, itemLp())
        bubbleMenu = menu
        root.addView(menu, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))

        val lp = newParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            baseFlags() or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )
        lp.x = dp(8)
        lp.y = (size.y * 0.25f).toInt()
        bubbleLp = lp
        bubble = root

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        dot.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (moved || abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        moved = true
                        val s = screenSize()
                        val bw = if (root.width > 0) root.width else dp(46)
                        val bh = if (root.height > 0) root.height else dp(46)
                        lp.x = (startX + dx).toInt().coerceIn(0, (s.x - bw).coerceAtLeast(0))
                        lp.y = (startY + dy).toInt().coerceIn(0, (s.y - bh).coerceAtLeast(0))
                        try {
                            wm.updateViewLayout(root, lp)
                        } catch (e: IllegalArgumentException) {
                            // jendela sudah dilepas
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                        // Geser bubble bila menu yang terbuka akan keluar dari layar.
                        root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                        val s = screenSize()
                        lp.x = lp.x.coerceIn(0, (s.x - root.measuredWidth).coerceAtLeast(0))
                        lp.y = lp.y.coerceIn(0, (s.y - root.measuredHeight).coerceAtLeast(0))
                        try {
                            wm.updateViewLayout(root, lp)
                        } catch (e: IllegalArgumentException) {
                            // jendela sudah dilepas
                        }
                    }
                }
            }
            true
        }
        wm.addView(root, lp)
    }

    /** Menaikkan volume media perangkat (penyebab utama suara terasa kecil) dan menampilkan panel volume. */
    private fun volumeUp() {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        am.adjustStreamVolume(
            android.media.AudioManager.STREAM_MUSIC,
            android.media.AudioManager.ADJUST_RAISE,
            android.media.AudioManager.FLAG_SHOW_UI
        )
    }

    private fun toggleMute() {
        muted = !muted
        val p = project ?: return
        for (s in streams) s.cell.applyAudio(p.audio, muted)
        muteLabel?.text = if (muted) "Mute" else "Suara"
    }

    private fun toggleTouch() {
        touchEnabled = !touchEnabled
        project?.overlay?.touchEnabled = touchEnabled
        scheduleSave()
        for (s in streams) {
            s.lp.flags = if (touchEnabled) {
                s.lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                s.lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            try {
                wm.updateViewLayout(s.cell, s.lp)
            } catch (e: IllegalArgumentException) {
                // jendela sudah dilepas
            }
        }
        touchLabel?.text = if (touchEnabled) "Sentuh:ON" else "Sentuh:OFF"
    }

    // ------------------------------------------------------------------ EditLayer.Host

    override fun onChanged() {
        scheduleSave()
    }

    override fun onRequestImport() {
        handler.post {
            if (project != null && mode == Mode.EDIT) {
                flushSave()
                clearWindows()
                try {
                    startActivity(Intent(this, ImportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Ui.toast(this, "Tidak bisa membuka pemilih file")
                    showMode(Mode.EDIT)
                }
            }
        }
    }

    private fun addLocalItem(url: String?, name: String?) {
        val p = project ?: return
        if (url.isNullOrBlank()) return
        val n = p.items.size % 5
        val w = 1152
        val h = 648
        val it = SourceItem(name = name?.ifBlank { null } ?: "Offline", url = url, w = w, h = h)
        it.x = (CANVAS_W - w) / 2 + n * 20
        it.y = (CANVAS_H - h) / 2 + n * 20
        p.items.add(it)
        flushSave()
        Ui.toast(this, "Web Source offline ditambahkan")
    }

    override fun onRequestStream() {
        // Ditunda agar tidak melepas jendela di tengah dispatch sentuhan/tombol miliknya sendiri.
        handler.post {
            val p = project
            if (p != null && mode == Mode.EDIT) {
                flushSave()
                showMode(Mode.RUN)
            }
        }
    }

    private fun scheduleSave() {
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 600)
    }

    private fun flushSave() {
        handler.removeCallbacks(saveRunnable)
        project?.let { store.save(it) }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (project != null && running) {
            flushSave()
            showMode(mode)
        }
    }

    override fun onDestroy() {
        flushSave()
        clearWindows()
        running = false
        project = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.aastudio.live.START"
        const val ACTION_EDIT = "com.aastudio.live.EDIT"
        const val ACTION_RUN = "com.aastudio.live.RUN"
        const val ACTION_RELOAD = "com.aastudio.live.RELOAD"
        const val ACTION_STOP = "com.aastudio.live.STOP"
        const val EXTRA_PROJECT = "project_id"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val ACTION_ADD_LOCAL = "com.aastudio.live.ADD_LOCAL"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIF_ID = 4201

        @Volatile
        var running: Boolean = false

        /** Menjalankan overlay untuk satu proyek. Default: mode Editor di atas aplikasi lain. */
        fun start(ctx: Context, projectId: String, run: Boolean = false) {
            val i = Intent(ctx, OverlayService::class.java)
                .setAction(if (run) ACTION_RUN else ACTION_EDIT)
                .putExtra(EXTRA_PROJECT, projectId)
            ctx.startForegroundService(i)
        }

        /** Dipakai saat ikon aplikasi ditekan: proyek terbaru, atau buat "Proyek 1" bila belum ada. */
        fun launchDefault(ctx: Context) {
            val store = ProjectStore(ctx)
            val p = store.list().firstOrNull() ?: store.create("Proyek 1")
            start(ctx, p.id)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }
}
