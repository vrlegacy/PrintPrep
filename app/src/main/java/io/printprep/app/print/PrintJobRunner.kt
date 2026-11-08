package io.printprep.app.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import io.printprep.app.data.ImageStore
import io.printprep.app.engine.Sheet
import io.printprep.app.engine.SheetRenderer
import io.printprep.app.model.JobItem
import io.printprep.app.model.PrintSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt

class PrintCancelled : IOException("Cancelled")

/** Progress of one pass: [page] is 1-based; [fraction] covers the whole pass. */
data class PrintProgress(val page: Int, val total: Int, val stage: String, val fraction: Float)

/**
 * Prints sheets on the USB printer: each sheet is drawn in horizontal strips (the same drawing
 * code as the preview and the PDF), turned into Canon printer data by Gutenprint, then sent over USB.
 * One printer job per page, which is how the G2010 is known to work with Gutenprint.
 */
class PrintJobRunner(private val context: Context, private val store: ImageStore, private val printer: UsbPrinter) {

    suspend fun printSheets(
        sheets: List<Sheet>,
        s: PrintSettings,
        rotate180: Boolean,
        onProgress: (PrintProgress) -> Unit,
        isCancelled: () -> Boolean,
    ) = withContext(Dispatchers.IO) {
        Gutenprint.ensureReady(context)?.let { throw IOException(it) }
        if (printer.state !is PrinterState.Ready) throw IOException("The printer isn't ready. Plug it in, switch it on and allow USB access.")
        val options = DriverSettings.options(s)
        val area = Gutenprint.imageableArea(Gutenprint.G2010_DRIVER, options)
            ?: throw IOException("The printer driver couldn't work out the page size.")
        val pageWpt = area[0]; val pageHpt = area[1]
        val leftPt = area[2]; val rightPt = area[3]; val topPt = area[4]; val bottomPt = area[5]
        val dpi = DriverSettings.imageDpi(s)
        val areaWpt = rightPt - leftPt
        val areaHpt = bottomPt - topPt
        val wPx = (areaWpt / 72.0 * dpi).roundToInt()
        val hPx = (areaHpt / 72.0 * dpi).roundToInt()
        val cache = JobBitmaps(store, SheetRenderer.colorFilter(s))
        val total = sheets.size

        printer.busy = true
        try {
            sheets.forEachIndexed { i, sheet ->
                if (isCancelled()) throw PrintCancelled()
                runCatching { printer.readStatus() }.getOrNull()?.let {
                    if (it.paperEmpty) throw IOException("Out of paper. Load paper in the rear tray, then print again.")
                }
                val base = i.toFloat() / total
                val step = 1f / total
                val geometry = PageGeometry(pageWpt, pageHpt, leftPt, topPt, areaWpt, areaHpt, dpi, DriverSettings.borderless(s), rotate180)
                val source = BandRenderer(sheet, s, cache, geometry, wPx, isCancelled) { row ->
                    onProgress(PrintProgress(i + 1, total, "Preparing", base + step * 0.6f * row / hPx))
                }
                val file = File(context.cacheDir, "print_page_${i + 1}.prn")
                try {
                    val err = Gutenprint.printPage(Gutenprint.G2010_DRIVER, options, wPx, hPx, leftPt, topPt, areaWpt, areaHpt, source, file)
                    if (err == "cancelled" || isCancelled()) throw PrintCancelled()
                    if (err != null) throw IOException(err)
                    val size = file.length().coerceAtLeast(1)
                    printer.send(file, { sent ->
                        onProgress(PrintProgress(i + 1, total, "Printing", base + step * (0.6f + 0.4f * sent / size)))
                    }) { !isCancelled() }
                } catch (e: UsbPrinter.CancelledException) {
                    throw PrintCancelled()
                } finally {
                    source.release()
                    file.delete()
                }
            }
            onProgress(PrintProgress(total, total, "Done", 1f))
        } finally {
            printer.busy = false
            cache.clear()
        }
    }
}

/** Where the drawn sheet goes on the physical page, in points and pixels. */
data class PageGeometry(
    val pageWpt: Double, val pageHpt: Double,
    val areaLeftPt: Double, val areaTopPt: Double, val areaWpt: Double, val areaHpt: Double,
    val dpi: Int,
    val borderless: Boolean,
    val rotate180: Boolean,
)

/**
 * Supplies page rows to the driver, drawing [BAND] rows at a time. Landscape sheets are turned to
 * fit the portrait page; on plain paper the whole sheet is shrunk slightly into the printable area
 * so nothing at the edges gets cut off.
 */
class BandRenderer(
    private val sheet: Sheet,
    private val s: PrintSettings,
    private val bitmaps: JobBitmaps,
    private val g: PageGeometry,
    private val width: Int,
    private val isCancelled: () -> Boolean,
    private val onRow: (Int) -> Unit,
) : RowSource {

    private val band = Bitmap.createBitmap(width, BAND, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(width)
    private var bandStart = -1

    private val pxPerPt = g.dpi / 72.0
    private val pagePxW = g.pageWpt * pxPerPt
    private val pagePxH = g.pageHpt * pxPerPt
    private val leftPx = g.areaLeftPt * pxPerPt
    private val topPx = g.areaTopPt * pxPerPt
    private val areaPxW = g.areaWpt * pxPerPt
    private val areaPxH = g.areaHpt * pxPerPt
    private val landscape = sheet.paperW > sheet.paperH

    override fun getRow(row: Int, out: ByteArray): Boolean {
        if (isCancelled()) return false
        if (bandStart < 0 || row < bandStart || row >= bandStart + BAND) render(row - row % BAND)
        band.getPixels(pixels, 0, width, 0, row - bandStart, width, 1)
        var o = 0
        for (p in pixels) {
            out[o++] = (p shr 16).toByte()
            out[o++] = (p shr 8).toByte()
            out[o++] = p.toByte()
        }
        if (row % 64 == 0) onRow(row)
        return true
    }

    fun release() {
        band.recycle()
    }

    private fun render(start: Int) {
        bandStart = start
        val c = Canvas(band)
        c.drawColor(Color.WHITE)
        // raster origin = top-left of the printable area; this band starts `start` rows down
        c.translate((-leftPx).toFloat(), (-(topPx + start)).toFloat())
        // full page → where it lands (whole page when borderless; shrunk into the printable area otherwise)
        val k = if (g.borderless) 1.0 else min(areaPxW / pagePxW, areaPxH / pagePxH)
        val ox = if (g.borderless) 0.0 else leftPx + (areaPxW - pagePxW * k) / 2
        val oy = if (g.borderless) 0.0 else topPx + (areaPxH - pagePxH * k) / 2
        c.translate(ox.toFloat(), oy.toFloat())
        c.scale(k.toFloat(), k.toFloat())
        if (g.rotate180) {
            c.translate(pagePxW.toFloat(), pagePxH.toFloat())
            c.rotate(180f)
        }
        if (landscape) {
            c.translate(pagePxW.toFloat(), 0f)
            c.rotate(90f)
        }
        // our sheet is in millimetres; the physical page is the same paper size
        val pxPerMm = (g.dpi / 25.4).toFloat()
        SheetRenderer.drawSheet(c, sheet, s, pxPerMm, g.dpi, bitmaps.loader, preFiltered = true)
    }

    companion object {
        const val BAND = 128
    }
}

/** Decoded, colour-adjusted bitmaps for the current job, kept while pages repeat them (copies). */
class JobBitmaps(private val store: ImageStore, private val filter: ColorMatrixColorFilter?) {
    private val maxBytes = (Runtime.getRuntime().maxMemory() / 3).coerceAtLeast(64L * 1024 * 1024)
    private val map = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {}
    private var bytes = 0L

    val loader = SheetRenderer.BitmapLoader { item, px -> get(item, px) }

    @Synchronized
    private fun get(item: JobItem, px: Int): Bitmap? {
        val bucket = Integer.highestOneBit((px - 1).coerceAtLeast(1)) shl 1
        val key = "${item.id}@$bucket"
        map[key]?.let { return it }
        val src = store.decode(item, bucket, useCache = false) ?: return null
        val bmp = if (filter != null) SheetRenderer.applyFilter(src, filter).also { if (it !== src) src.recycle() } else src
        map[key] = bmp
        bytes += bmp.byteCount
        val it = map.entries.iterator()
        while (bytes > maxBytes && map.size > 1 && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            bytes -= e.value.byteCount
            it.remove()
        }
        return bmp
    }

    @Synchronized
    fun clear() {
        map.clear()
        bytes = 0
    }
}
