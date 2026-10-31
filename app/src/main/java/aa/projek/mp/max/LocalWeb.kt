package aa.projek.mp.max

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Web Source offline: halaman (HTML atau ZIP) disalin ke penyimpanan aplikasi dan dilayani
 * lewat alamat https://aa.local/<folder>/<file> tanpa internet. Hanya folder "local" milik
 * aplikasi yang bisa dibaca (dicek canonical path, anti path-traversal / zip-slip).
 */
object LocalWeb {
    const val HOST = "aa.local"
    private const val MAX_TOTAL = 60L * 1024 * 1024
    private const val MAX_ENTRIES = 3000

    class Imported(val url: String, val name: String)

    private fun root(ctx: Context) = File(ctx.filesDir, "local")

    fun isLocal(url: String) = url.startsWith("https://$HOST/")

    private fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0) ?: "halaman"
            }
        } catch (e: Exception) {
            // abaikan
        }
        return uri.lastPathSegment ?: "halaman"
    }

    private fun safeName(n: String): String =
        n.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.').ifBlank { "file" }

    /** Menyalin file/ZIP pilihan pengguna. Melempar IOException berisi pesan yang bisa ditampilkan. */
    fun import(ctx: Context, uri: Uri): Imported {
        val name = displayName(ctx, uri)
        val lower = name.lowercase()
        val dirName = UUID.randomUUID().toString().take(8)
        val dir = File(root(ctx), dirName)
        if (!dir.mkdirs()) throw IOException("Tidak bisa membuat folder penyimpanan")
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("File tidak bisa dibuka")
            input.use { ins ->
                when {
                    lower.endsWith(".zip") -> unzip(ins, dir)
                    lower.endsWith(".html") || lower.endsWith(".htm") -> {
                        val out = File(dir, "index.html")
                        out.outputStream().use { o -> copyLimited(ins, o, MAX_TOTAL) }
                    }
                    else -> throw IOException("Pilih file .html atau .zip (berisi index.html)")
                }
            }
            val entry = findEntry(dir) ?: throw IOException("index.html tidak ditemukan di dalam file")
            val rel = entry.relativeTo(dir).path.replace(File.separatorChar, '/')
            val show = name.substringBeforeLast('.')
            return Imported("https://$HOST/$dirName/" + rel.split('/').joinToString("/") { Uri.encode(it) }, show)
        } catch (e: Exception) {
            dir.deleteRecursively()
            if (e is IOException) throw e
            throw IOException("Gagal mengimpor: ${e.message}")
        }
    }

    private fun copyLimited(i: java.io.InputStream, o: java.io.OutputStream, limit: Long): Long {
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = i.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw IOException("File terlalu besar (maksimal 60 MB)")
            o.write(buf, 0, n)
        }
        return total
    }

    private fun unzip(ins: java.io.InputStream, dir: File) {
        val base = dir.canonicalFile
        var total = 0L
        var count = 0
        ZipInputStream(ins).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (++count > MAX_ENTRIES) throw IOException("ZIP berisi terlalu banyak file")
                val target = File(base, e.name).canonicalFile
                if (target.path != base.path && !target.path.startsWith(base.path + File.separator)) {
                    throw IOException("ZIP tidak aman (path di luar folder)")
                }
                if (e.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { o -> total += copyLimited(z, o, MAX_TOTAL - total) }
                }
                z.closeEntry()
            }
        }
    }

    private fun findEntry(dir: File): File? {
        var best: File? = null
        var bestDepth = Int.MAX_VALUE
        var anyHtml: File? = null
        dir.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            val depth = f.relativeTo(dir).path.count { it == File.separatorChar }
            if (f.name.equals("index.html", true) && depth < bestDepth) {
                best = f
                bestDepth = depth
            } else if (anyHtml == null && f.extension.lowercase() in setOf("html", "htm")) {
                anyHtml = f
            }
        }
        return best ?: anyHtml
    }

    private fun mime(f: File): String {
        val ext = f.extension.lowercase()
        return when (ext) {
            "js", "mjs" -> "text/javascript"
            "json" -> "application/json"
            "wasm" -> "application/wasm"
            "svg" -> "image/svg+xml"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    /** Dipanggil dari shouldInterceptRequest. Mengembalikan null bila bukan permintaan lokal. */
    fun serve(ctx: Context, uri: Uri): WebResourceResponse? {
        if (uri.host != HOST) return null
        val base = root(ctx).canonicalFile
        val rel = uri.path?.trimStart('/') ?: ""
        val f = try {
            File(base, rel).canonicalFile
        } catch (e: IOException) {
            null
        }
        val headers = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-cache")
        if (f == null || !f.path.startsWith(base.path + File.separator) || !f.isFile) {
            return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers, ByteArrayInputStream(ByteArray(0)))
        }
        val m = mime(f)
        val enc = if (m.startsWith("text/") || m == "application/json" || m == "image/svg+xml") "utf-8" else null
        return WebResourceResponse(m, enc, 200, "OK", headers, FileInputStream(f))
    }
}
