package io.printprep.app.print

import android.content.Context
import java.io.File

/** Pulls RGB rows (3 bytes per pixel) for the page being printed. Return false to cancel. */
fun interface RowSource {
    fun getRow(row: Int, out: ByteArray): Boolean
}

/**
 * Thin wrapper around the native Gutenprint library (the open-source Canon driver).
 * The printer data files ship as app assets and are copied to private storage on first use.
 */
object Gutenprint {

    /** Gutenprint has no G2010 entry; the G2000 series driver is the one known to work with it. */
    const val G2010_DRIVER = "bjc-G2000-series"

    private var loaded: Boolean? = null
    @Volatile private var initialised = false

    /** True if this build contains the native driver (GitHub builds do). */
    val available: Boolean
        get() {
            loaded?.let { return it }
            val ok = runCatching { System.loadLibrary("printprep_native") }.isSuccess
            loaded = ok
            return ok
        }

    /** Loads the driver and its printer data. Returns an error message, or null when ready. */
    @Synchronized
    fun ensureReady(context: Context): String? {
        if (initialised) return null
        if (!available) return "This build doesn't include the printer driver. Install the APK from the GitHub build."
        val dir = File(context.filesDir, "gutenprint")
        val marker = File(dir, ".copied-${appVersion(context)}")
        if (!marker.exists()) {
            dir.deleteRecursively()
            val copied = copyAssets(context, "gutenprint", dir)
            if (copied == 0) return "Printer data is missing from this build."
            marker.createNewFile()
        }
        val xml = File(dir, "xml")
        if (!nativeInit(xml.absolutePath)) return "The printer driver failed to start."
        if (!nativeHasDriver(G2010_DRIVER)) return "The Canon G2000/G2010 driver wasn't found in the printer data."
        initialised = true
        return null
    }

    fun version(): String = if (available) runCatching { nativeVersion() }.getOrDefault("?") else "not included"

    /** [pageWidth, pageHeight, left, right, top, bottom] in points, measured from the top-left. */
    fun imageableArea(driver: String, options: List<Pair<String, String>>): DoubleArray? =
        nativeImageableArea(driver, options.flatten())

    /** Writes printer data for one page to [out]. Returns null on success, "cancelled", or an error. */
    fun printPage(
        driver: String,
        options: List<Pair<String, String>>,
        widthPx: Int,
        heightPx: Int,
        leftPt: Double,
        topPt: Double,
        widthPt: Double,
        heightPt: Double,
        source: RowSource,
        out: File,
    ): String? = nativePrintPage(driver, options.flatten(), widthPx, heightPx, leftPt, topPt, widthPt, heightPt, source, out.absolutePath)

    private fun List<Pair<String, String>>.flatten(): Array<String> = flatMap { listOf(it.first, it.second) }.toTypedArray()

    private fun appVersion(context: Context): Long =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L) xor
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)

    private fun copyAssets(context: Context, path: String, target: File): Int {
        val am = context.assets
        val children = am.list(path) ?: return 0
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            return runCatching {
                am.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
                1
            }.getOrDefault(0)
        }
        target.mkdirs()
        return children.sumOf { copyAssets(context, "$path/$it", File(target, it)) }
    }

    @JvmStatic private external fun nativeInit(dataPath: String): Boolean
    @JvmStatic private external fun nativeVersion(): String
    @JvmStatic private external fun nativeHasDriver(driver: String): Boolean
    @JvmStatic private external fun nativeImageableArea(driver: String, kv: Array<String>): DoubleArray?
    @JvmStatic private external fun nativePrintPage(
        driver: String, kv: Array<String>,
        widthPx: Int, heightPx: Int,
        left: Double, top: Double, width: Double, height: Double,
        source: RowSource, outPath: String,
    ): String?
}
