package com.aastudio.live

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.util.Log
import android.webkit.SslErrorHandler
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale

/**
 * Satu Web Source = satu WebView berukuran "logis" (w x h di koordinat kanvas),
 * lalu diskalakan oleh [CompositionView]. Konfigurasi WebView dibuat seaman mungkin:
 * tanpa akses file, tanpa JavaScript bridge, mixed content diblokir.
 */
@SuppressLint("SetJavaScriptEnabled")
class SourceCell(context: Context, var item: SourceItem) : FrameLayout(context) {

    private val web = WebView(context)
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val errorBox = LinearLayout(context)
    private val errorText = TextView(context)
    private val retry = Button(context)

    private var uiScale = 1f
    private var released = false

    /** URL terakhir yang diminta untuk dimuat. */
    var loadedUrl: String = ""
        private set

    private val client = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            hideError()
            progress.progress = 0
            progress.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            Log.i("AAStudio", "page finished url=$url")
            progress.visibility = View.GONE
            view?.evaluateJavascript(AUDIO_JS, null)
            pushAudio()
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) {
                Log.w("AAStudio", "load error ${error?.errorCode} ${error?.description} url=${request.url}")
                showError(friendly(error?.description?.toString() ?: "tidak diketahui"), true)
            }
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?
        ) {
            if (request?.isForMainFrame == true) {
                Log.w("AAStudio", "http error ${errorResponse?.statusCode} url=${request.url}")
                showError("Situs menjawab dengan kode HTTP ${errorResponse?.statusCode ?: 0}", true)
            }
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            // Tetap aman: sertifikat bermasalah selalu ditolak, tidak pernah diabaikan.
            Log.w("AAStudio", "ssl error ${error?.primaryError} url=${error?.url}")
            handler?.cancel()
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val scheme = request?.url?.scheme?.lowercase()
            return !(scheme == "http" || scheme == "https")
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            progress.progress = newProgress
        }
    }

    private var masterAudio = AudioSettings()
    private var forceMute = false

    init {
        val st = web.settings
        st.javaScriptEnabled = true
        st.domStorageEnabled = true
        st.allowFileAccess = false
        st.allowContentAccess = false
        st.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        st.setSupportZoom(false)
        web.setBackgroundColor(Color.TRANSPARENT)
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.webViewClient = client
        web.webChromeClient = chrome
        addView(web, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        progress.max = 100
        progress.visibility = View.GONE
        addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, 6, Gravity.TOP))

        errorBox.orientation = LinearLayout.VERTICAL
        errorBox.gravity = Gravity.CENTER
        errorBox.setBackgroundColor(Color.argb(230, 20, 20, 20))
        errorBox.visibility = View.GONE
        errorText.setTextColor(Color.WHITE)
        errorText.gravity = Gravity.CENTER
        retry.text = "Coba lagi"
        retry.isAllCaps = false
        retry.setOnClickListener { load() }
        errorBox.addView(errorText, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        errorBox.addView(retry, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(errorBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        applyUiScale(1f)
    }

    /** Ukuran teks/indikator mengikuti skala komposisi agar tetap terbaca setelah diperkecil. */
    fun applyUiScale(s: Float) {
        uiScale = if (s <= 0.0001f) 1f else s
        val d = resources.displayMetrics.density
        val px = 14f * d / uiScale
        errorText.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        retry.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        val pad = (12f * d / uiScale).toInt()
        errorBox.setPadding(pad, pad, pad, pad)
        retry.setPadding(pad, pad / 2, pad, pad / 2)
        progress.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            (4f * d / uiScale).toInt().coerceAtLeast(2),
            Gravity.TOP
        )
    }

    fun load() {
        if (released) return
        val r = UrlUtil.normalize(item.url)
        if (r.url == null) {
            loadedUrl = item.url
            showError(r.error ?: "URL tidak valid", false)
            return
        }
        loadedUrl = item.url
        hideError()
        web.loadUrl(r.url)
    }

    fun applyAudio(master: AudioSettings, force: Boolean) {
        masterAudio = master
        forceMute = force
        web.settings.mediaPlaybackRequiresUserGesture = !master.autoplay
        pushAudio()
    }

    private fun pushAudio() {
        if (released) return
        val muted = masterAudio.muted || item.muted || forceMute
        val vol = (masterAudio.volume / 100f) * (item.volume / 100f)
        val v = String.format(Locale.US, "%.2f", vol)
        web.evaluateJavascript("window.__aaApply&&window.__aaApply($muted,$v)", null)
    }

    fun pauseWeb() {
        if (!released) web.onPause()
    }

    fun resumeWeb() {
        if (!released) web.onResume()
    }

    private fun showError(msg: String, canRetry: Boolean) {
        progress.visibility = View.GONE
        errorText.text = msg
        retry.visibility = if (canRetry) View.VISIBLE else View.GONE
        errorBox.visibility = View.VISIBLE
    }

    private fun hideError() {
        errorBox.visibility = View.GONE
    }

    private fun friendly(desc: String): String = when {
        desc.contains("CLEARTEXT", true) -> "Alamat HTTP diblokir demi keamanan. Gunakan HTTPS."
        desc.contains("NAME_NOT_RESOLVED", true) -> "Situs tidak ditemukan. Periksa alamat atau internet."
        desc.contains("INTERNET_DISCONNECTED", true) -> "Tidak ada koneksi internet."
        desc.contains("TIMED_OUT", true) -> "Koneksi habis waktu."
        desc.contains("SSL", true) || desc.contains("CERT", true) -> "Sertifikat keamanan situs bermasalah."
        else -> "Gagal memuat halaman ($desc)"
    }

    /** Membersihkan WebView sepenuhnya. Wajib dipanggil saat sel tidak dipakai lagi. */
    fun release() {
        if (released) return
        released = true
        web.stopLoading()
        web.webViewClient = WebViewClient()
        web.webChromeClient = null
        web.loadUrl("about:blank")
        web.clearHistory()
        removeAllViews()
        web.destroy()
    }

    companion object {
        /**
         * Mengatur mute/volume semua elemen <audio>/<video> di halaman. Ini upaya terbaik:
         * audio yang dibuat lewat Web Audio API tidak terjangkau oleh skrip ini.
         */
        private const val AUDIO_JS =
            "(function(){if(window.__aaAudio){return;}" +
                "window.__aaAudio={m:false,v:1,e:false};" +
                "window.__aaApply=function(m,v){var a=window.__aaAudio;a.m=m;a.v=v;a.e=(m||v<1);" +
                "var els=document.querySelectorAll('audio,video');" +
                "for(var i=0;i<els.length;i++){els[i].muted=m;els[i].volume=v;}};" +
                "setInterval(function(){var a=window.__aaAudio;if(a.e){window.__aaApply(a.m,a.v);}},1000);" +
                "})();"
    }
}
