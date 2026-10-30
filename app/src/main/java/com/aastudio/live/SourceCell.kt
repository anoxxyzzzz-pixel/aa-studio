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
            // Halaman dengan media yang terus memuat (audio loop/stream) bisa tidak pernah memanggil
            // onPageFinished; pengaman waktu memastikan pengatur suara tetap terpasang.
            removeCallbacks(safetyInject)
            postDelayed(safetyInject, 2000)
            postDelayed(safetyInject, 6000)
            progress.progress = 0
            progress.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            Log.i("AAStudio", "page finished url=$url")
            progress.visibility = View.GONE
            injectAudio()
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

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val u = request?.url ?: return null
            return LocalWeb.serve(context, u)
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val scheme = request?.url?.scheme?.lowercase()
            return !(scheme == "http" || scheme == "https")
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
            Log.i("AAStudio-js", m?.message() ?: "")
            return true
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            progress.progress = newProgress
            if (newProgress >= 100) progress.visibility = View.GONE
            if (newProgress >= 70) injectAudio()
        }
    }

    private fun injectAudio() {
        if (released) return
        web.evaluateJavascript(AUDIO_JS, null)
        pushAudio()
    }

    private val safetyInject = Runnable {
        if (!released) {
            progress.visibility = View.GONE
            injectAudio()
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
        // Tanpa internet: pakai cache halaman yang pernah dibuka. Online: perilaku normal.
        web.settings.cacheMode = if (online()) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
        web.loadUrl(r.url)
    }

    private fun online(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        true
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
        val g = masterAudio.webGain.coerceIn(1, 4)
        web.evaluateJavascript("window.__aaApply&&window.__aaApply($muted,$v,$g)", null)
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
        removeCallbacks(safetyInject)
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
         * Mengatur mute/volume/penguat semua elemen <audio>/<video>, termasuk objek Audio() yang
         * tidak ada di DOM (lewat pembungkus play()). Penguat >1 memakai Web Audio GainNode.
         * Upaya terbaik: audio dari Web Audio API milik situs tidak terjangkau.
         */
        private const val AUDIO_JS =
            "(function(){if(window.__aaAudio){return;}" +
                "var A=window.__aaAudio={m:false,v:1,g:1,list:[],ctx:null,gn:null,t:null};" +
                "function hook(el){if(el.__aaHooked)return;try{" +
                "if(!A.ctx){var C=window.AudioContext||window.webkitAudioContext;A.ctx=new C();" +
                "A.gn=A.ctx.createGain();A.gn.connect(A.ctx.destination);}" +
                "A.ctx.createMediaElementSource(el).connect(A.gn);el.__aaHooked=true;}catch(e){}}" +
                "function one(el){try{el.muted=A.m;el.volume=Math.min(1,A.v);if(A.g>1)hook(el);}catch(e){}}" +
                "function all(){var els=document.querySelectorAll('audio,video');" +
                "for(var i=0;i<els.length;i++)one(els[i]);" +
                "for(var j=0;j<A.list.length;j++)one(A.list[j]);" +
                "if(A.gn){A.gn.gain.value=A.g;if(A.ctx.state==='suspended'){A.ctx.resume();}}}" +
                "var op=HTMLMediaElement.prototype.play;" +
                "HTMLMediaElement.prototype.play=function(){if(A.list.indexOf(this)<0&&A.list.length<50)A.list.push(this);" +
                "one(this);if(A.gn&&A.ctx.state==='suspended'){A.ctx.resume();}return op.apply(this,arguments);};" +
                "window.__aaApply=function(m,v,g){A.m=m;A.v=v;A.g=g;all();" +
                "var need=(m||v<1||g>1);if(need&&!A.t){A.t=setInterval(all,1500);}" +
                "if(!need&&A.t){clearInterval(A.t);A.t=null;}};" +
                "})();"
    }
}
