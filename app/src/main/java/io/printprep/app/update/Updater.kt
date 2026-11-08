package io.printprep.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A newer build published on GitHub Releases. */
data class Release(val build: Int, val name: String, val notes: String, val apkUrl: String, val apkSize: Long)

/** Checks GitHub Releases for a newer PrintPrep build and opens its download. */
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

    /**
     * Opens the new APK in the browser, which downloads it; the user then taps it to install.
     * (The app deliberately doesn't install APKs itself: that needs the "install other apps"
     * permission, which makes Google Play Protect flag the app as potentially harmful.)
     */
    fun openDownload(context: Context, release: Release) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
