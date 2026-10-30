package com.aastudio.live

import android.net.Uri

object UrlUtil {
    class Result(val url: String?, val error: String?)

    /** Validasi + normalisasi URL. Tanpa skema otomatis memakai https://. */
    fun normalize(raw: String): Result {
        val t = raw.trim()
        if (t.isEmpty()) return Result(null, "URL kosong")
        if (t.any { it.isWhitespace() }) return Result(null, "URL tidak boleh mengandung spasi")
        val withScheme = if (t.contains("://")) t else "https://$t"
        val uri = try {
            Uri.parse(withScheme)
        } catch (e: Exception) {
            return Result(null, "URL tidak valid")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Result(null, "Hanya http/https yang didukung")
        val host = uri.host
        if (host.isNullOrBlank()) return Result(null, "Alamat situs tidak valid")
        if (!host.contains('.') && host != "localhost") {
            return Result(null, "Alamat situs tidak valid (contoh: example.com)")
        }
        return Result(withScheme, null)
    }

    fun hostOf(url: String): String = try {
        Uri.parse(url).host ?: url
    } catch (e: Exception) {
        url
    }
}
