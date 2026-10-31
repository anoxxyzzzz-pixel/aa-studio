package aa.projek.mp.max

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.InputDevice
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var devices: TextView
    private lateinit var body: LinearLayout
    private val BG = Color.parseColor("#0F1115")
    private val CARD = Color.parseColor("#1A1E26")
    private val ACC = Color.parseColor("#4F8CFF")
    private val OK = Color.parseColor("#3DDC84")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun card(title: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(14).toFloat() }
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        c.addView(TextView(this).apply { text = title; setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD })
        body.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        return c
    }

    private fun note(parent: LinearLayout, t: String) = parent.addView(TextView(this).apply {
        text = t; setTextColor(Color.parseColor("#9AA3B2")); textSize = 12.5f; setPadding(0, dp(6), 0, dp(4))
    })

    private fun field(parent: LinearLayout, hint: String, text: String = "", number: Boolean = true): EditText {
        val e = EditText(this).apply {
            this.hint = hint; setText(text); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); textSize = 14f
            if (number) inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        parent.addView(e, LinearLayout.LayoutParams(-1, -2))
        return e
    }

    private fun btn(parent: LinearLayout, t: String, primary: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(this).apply {
            text = t; isAllCaps = false; setTextColor(Color.WHITE); textSize = 14f
            background = GradientDrawable().apply { setColor(if (primary) ACC else Color.parseColor("#2A3140")); cornerRadius = dp(10).toFloat() }
            setOnClickListener { onClick() }
        }
        parent.addView(b, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG; window.navigationBarColor = BG
        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(28), dp(16), dp(28)) }
        scroll.addView(body)
        setContentView(scroll)

        body.addView(TextView(this).apply { text = "AA Mouse Pro"; setTextColor(Color.WHITE); textSize = 26f; typeface = Typeface.DEFAULT_BOLD })
        body.addView(TextView(this).apply { text = "Keyboard & mouse OTG untuk game sentuh. Berjalan offline."; setTextColor(Color.parseColor("#9AA3B2")); textSize = 13f })

        val st = card("Status")
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13.5f; setPadding(0, dp(8), 0, 0) }
        st.addView(status)
        devices = TextView(this).apply { setTextColor(Color.parseColor("#C5CCDA")); textSize = 12.5f; setPadding(0, dp(8), 0, 0) }
        st.addView(devices)
        btn(st, "Izinkan tampil di atas aplikasi lain") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        val n = card("1. Aktivasi: Pairing lewat notifikasi (disarankan)")
        note(n, "Hubungkan HP ke Wi-Fi. Tekan tombol di bawah, lalu buka Opsi pengembang > Debug nirkabel > Pasangkan perangkat dengan kode pairing. Tarik notifikasi AA Mouse Pro, ketuk 'Isi kode', ketik 6 digit kode. Selesai: ADB tersambung dan server siap otomatis.")
        btn(n, "Mulai pairing lewat notifikasi", true) {
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
                toast("Izinkan notifikasi, lalu tekan tombol lagi")
            } else {
                startForegroundService(Intent(this, PairingService::class.java))
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }
        }

        val w = card("1. Aktivasi manual: Debug nirkabel (Android 11+)")
        note(w, "Buka Opsi pengembang > Debug nirkabel > Pasangkan perangkat dengan kode. Isi port & kode pairing, lalu port koneksi (port di layar utama Debug nirkabel).")
        val pPort = field(w, "Port pairing (mis. 37123)")
        val pCode = field(w, "Kode pairing 6 digit")
        btn(w, "Pasangkan") {
            val port = pPort.text.toString().toIntOrNull(); val code = pCode.text.toString().trim()
            if (port == null || code.length < 6) toast("Isi port dan kode 6 digit") else AdbHub.pair(this, "127.0.0.1", port, code) { _, m -> post(m) }
        }
        val cPort = field(w, "Port koneksi (mis. 41234)")
        btn(w, "Sambung (port di atas)", true) {
            val port = cPort.text.toString().toIntOrNull()
            if (port == null) toast("Isi port koneksi") else AdbHub.connect(this, "127.0.0.1", port) { ok, m -> post(m); if (ok) startServer() }
        }
        btn(w, "Cari & sambung otomatis") { AdbHub.autoConnect(this) { ok, m -> post(m); if (ok) startServer() } }

        val u = card("1. Aktivasi: Debugging USB (lewat komputer, sekali tiap restart)")
        note(u, "Colok HP ke komputer, aktifkan Debugging USB, lalu jalankan di komputer:  adb tcpip 5555  . Cabut kabel, lalu tekan tombol di bawah.")
        val tPort = field(u, "Port", "5555")
        btn(u, "Sambung ke 127.0.0.1", true) {
            val port = tPort.text.toString().toIntOrNull() ?: 5555
            AdbHub.connect(this, "127.0.0.1", port) { ok, m -> post(m); if (ok) startServer() }
        }

        val g = card("2. Main")
        note(g, "Buka game, tekan Mulai. F8 = jeda/lanjut pemetaan. Mouse disembunyikan saat aktif.")
        btn(g, "Mulai pemetaan", true) {
            when {
                !Settings.canDrawOverlays(this) -> toast("Izinkan tampil di atas aplikasi lain dulu")
                !AdbHub.serverReady -> toast("Sambungkan ADB dulu (langkah 1)")
                else -> startForegroundService(Intent(this, CaptureService::class.java))
            }
        }
        btn(g, "Hentikan") { startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)) }
        btn(g, "Layar uji sentuhan") { startActivity(Intent(this, TestPadActivity::class.java)) }
        btn(g, "Putuskan ADB") { AdbHub.disconnect(this); post("Diputus") }

        (getSystemService(INPUT_SERVICE) as InputManager).registerInputDeviceListener(object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(id: Int) = refresh()
            override fun onInputDeviceRemoved(id: Int) = refresh()
            override fun onInputDeviceChanged(id: Int) = refresh()
        }, ui)
        refresh()
    }

    private fun startServer() = AdbHub.startServer(this) { _, m -> post(m) }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun post(m: String) = ui.post { toast(m); refresh() }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val ov = Settings.canDrawOverlays(this)
        fun mark(ok: Boolean) = if (ok) "✔" else "✖"
        status.text = "${mark(ov)} Izin tampil di atas aplikasi lain\n" +
            "${mark(AdbHub.isLive(this))} ADB tersambung\n" +
            "${mark(AdbHub.serverReady)} Server injeksi sentuhan\n" +
            "${mark(CaptureService.running)} Pemetaan aktif\n\n" + AdbHub.message
        val kb = ArrayList<String>(); val ms = ArrayList<String>()
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if (d.isVirtual) continue
            val s = d.sources
            if (s and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE) ms.add(d.name)
            if (s and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD && d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) kb.add(d.name)
        }
        devices.text = "Keyboard: " + (if (kb.isEmpty()) "tidak terdeteksi" else kb.joinToString(", ")) +
            "\nMouse: " + (if (ms.isEmpty()) "tidak terdeteksi" else ms.joinToString(", "))
    }
}
