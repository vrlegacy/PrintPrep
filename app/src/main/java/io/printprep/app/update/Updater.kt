package io.printprep.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A newer build published on GitHub Releases. */
data class Release(val build: Int, val name: String, val notes: String, val apkUrl: String, val apkSize: Long)

/**
 * Checks GitHub Releases for a newer PrintPrep build, downloads it and hands it to Android's
 * installer. Android always shows its own "Update?" confirmation; the user can say no.
 */
object Updater {

    private const val LATEST = "https://api.github.com/repos/vrlegacy/PrintPrep/releases/latest"

    /** Build number of the running app (versionCode = 100 + build). */
    fun currentBuild(context: Context): Int = runCatching {
        (context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode - 100).toInt()
    }.getOrDefault(0)

    /** The latest release if it is newer than [currentBuild], else null. Throws on network errors. */
    fun checkForUpdate(context: Context): Release? {
        val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PrintPrep-Android")
        }
        try {
            when (conn.responseCode) {
                200 -> Unit
                404 -> throw IOException("No public releases found. The PrintPrep repository must be public for updates.")
                else -> throw IOException("GitHub answered ${conn.responseCode}.")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val build = json.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return null
            if (build <= currentBuild(context)) return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return Release(
                        build = build,
                        name = json.optString("name", "PrintPrep build $build"),
                        notes = json.optString("body").substringAfter(": ", json.optString("body")).lineSequence().firstOrNull().orEmpty(),
                        apkUrl = a.getString("browser_download_url"),
                        apkSize = a.optLong("size", 0L),
                    )
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the APK (following GitHub's redirect to its file storage). */
    fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "PrintPrep-${release.build}.apk")
        var url = release.apkUrl
        repeat(6) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "PrintPrep-Android")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    url = URL(URL(url), conn.getHeaderField("Location")).toString()
                    return@repeat
                }
                if (code != 200) throw IOException("Download failed (HTTP $code).")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                conn.inputStream.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                return out
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects.")
    }

    /** Whether the user has allowed PrintPrep to install updates ("Install unknown apps"). */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** Opens the system setting where the user allows PrintPrep to install updates. */
    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Hands the APK to Android's installer; Android then asks the user to confirm. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("PrintPrep.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = Intent(context, InstallResultReceiver::class.java)
            val pi = PendingIntent.getBroadcast(context, id, result, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(pi.intentSender)
        }
    }
}
