package io.printprep.app.data

import android.util.Base64
import android.webkit.CookieManager
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Downloads an image or file the user picked inside the in-app browser, using the page's own
 * cookies and browser identity, so files behind a session (e.g. after filling a form) still download.
 */
object WebFetch {

    data class Fetched(val file: File, val mime: String?)

    fun fetch(url: String, userAgent: String?, referer: String?, dir: File, baseName: String): Fetched {
        dir.mkdirs()
        if (url.startsWith("data:")) return fromDataUrl(url, dir, baseName)
        if (url.startsWith("blob:")) throw IOException("This image can't be downloaded directly. Use Capture screen instead.")

        var target = url
        repeat(5) { // follow up to 5 redirects
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                CookieManager.getInstance().getCookie(target)?.let { setRequestProperty("Cookie", it) }
                userAgent?.let { setRequestProperty("User-Agent", it) }
                referer?.let { setRequestProperty("Referer", it) }
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    target = URL(URL(target), conn.getHeaderField("Location") ?: throw IOException("Bad redirect")).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IOException("The website answered $code.")
                val mime = conn.contentType?.substringBefore(';')?.trim()
                val file = File(dir, baseName + extensionFor(mime, target))
                conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                if (file.length() == 0L) throw IOException("The download was empty.")
                return Fetched(file, mime)
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects.")
    }

    private fun fromDataUrl(url: String, dir: File, baseName: String): Fetched {
        val comma = url.indexOf(',')
        if (comma < 0) throw IOException("Unreadable image data.")
        val meta = url.substring(5, comma)
        val mime = meta.substringBefore(';').ifBlank { null }
        val payload = url.substring(comma + 1)
        val bytes = if (meta.endsWith(";base64")) Base64.decode(payload, Base64.DEFAULT)
        else URLDecoder.decode(payload, "UTF-8").toByteArray()
        val file = File(dir, baseName + extensionFor(mime, ""))
        file.writeBytes(bytes)
        return Fetched(file, mime)
    }

    private fun extensionFor(mime: String?, url: String): String = when {
        mime == "application/pdf" || url.lowercase().substringBefore('?').endsWith(".pdf") -> ".pdf"
        mime == "image/png" -> ".png"
        mime == "image/webp" -> ".webp"
        mime == "image/gif" -> ".gif"
        else -> ".jpg"
    }
}
