package com.aastudio.live

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private val executor = Executors.newSingleThreadExecutor()
    private var pendingSaveText: String? = null
    private var pendingSaveName: String = "project.html"
    private val openFileRequest = 4101
    private val saveFileRequest = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.loadsImagesAutomatically = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(Bridge(), "AAAndroid")
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    inner class Bridge {
        @JavascriptInterface fun saveFile(name: String, text: String) {
            runOnUiThread {
                pendingSaveText = text
                pendingSaveName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "project.txt" }
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, pendingSaveName)
                }
                startActivityForResult(intent, saveFileRequest)
            }
        }

        @JavascriptInterface fun openFile() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(intent, openFileRequest)
            }
        }

        @JavascriptInterface fun fetchSource(address: String) {
            executor.execute {
                var result = ""
                var error = ""
                try {
                    val uri = Uri.parse(address)
                    if (uri.scheme != "https" && uri.scheme != "http") throw Exception("Gunakan URL http atau https")
                    val connection = URL(address).openConnection() as HttpURLConnection
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    connection.setRequestProperty("User-Agent", "AAStudio/1.0")
                    connection.instanceFollowRedirects = true
                    connection.connect()
                    if (connection.responseCode !in 200..299) throw Exception("HTTP ${connection.responseCode}")
                    val stream = connection.inputStream
                    val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                    val builder = StringBuilder()
                    val buffer = CharArray(4096)
                    var total = 0
                    while (true) {
                        val count = reader.read(buffer)
                        if (count <= 0 || total >= 1_000_000) break
                        builder.append(buffer, 0, count)
                        total += count
                    }
                    reader.close()
                    connection.disconnect()
                    result = builder.toString()
                } catch (e: Exception) { error = e.message ?: "Gagal mengambil source" }
                val payload = JSONObject().put("source", result).put("error", error).toString()
                runOnUiThread { webView.evaluateJavascript("window.onSourceFetched && window.onSourceFetched(${JSONObject.quote(payload)})", null) }
            }
        }

        @JavascriptInterface fun toast(message: String) {
            runOnUiThread { Toast.makeText(this@MainActivity, message.take(160), Toast.LENGTH_SHORT).show() }
        }
    }

    @Deprecated("Deprecated in Android API; retained for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val uri = data.data!!
        try {
            when (requestCode) {
                openFileRequest -> {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    webView.evaluateJavascript("window.onFileOpened && window.onFileOpened(${JSONObject.quote(uri.lastPathSegment ?: "file")}, ${JSONObject.quote(text)})", null)
                }
                saveFileRequest -> {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingSaveText ?: "") }
                    Toast.makeText(this, "File berhasil disimpan", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) { Toast.makeText(this, "Operasi file gagal: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    override fun onBackPressed() {
        webView.evaluateJavascript("window.handleAndroidBack && window.handleAndroidBack()") { handled ->
            if (handled != "true") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("AAAndroid")
        webView.destroy()
        executor.shutdownNow()
        super.onDestroy()
    }
}
