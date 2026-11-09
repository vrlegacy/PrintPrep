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

/** What was actually sent, for the done message and Printer diagnostics. */
data class JobReport(
    val pages: Int,
    val bytes: Long,
    /** First bytes of page 1's printer data, as text where printable (should start with "<?xml" for the G2010). */
    val header: String,
    val statusAfter: PortStatus?,
    val dataFile: File?,
    /** Last thing the printer said on its back channel; null if it never answered. */
    val printerSaid: CanonStatus? = null,
    /** True when the printer itself reported each page finished; false when we only know it was sent. */
    val confirmedByPrinter: Boolean = false,
)

/** Progress of one pass: [page] is 1-based; [fraction] covers the whole pass. */
data class PrintProgress(
    val page: Int,
    val total: Int,
    val stage: String,
    val fraction: Float,
    /** Something the user should know or do right now (printer out of paper, jammed…); null when all is well. */
    val notice: String? = null,
    /** Pages the printer has confirmed as printed (or, without a back channel, fully sent). */
    val printed: Int = 0,
)

/**
 * Prints sheets on the USB printer: each sheet is drawn in horizontal strips (the same drawing
 * code as the preview and the PDF), turned into Canon printer data by Gutenprint, then sent over USB.
 * One printer job per page; after sending each page we follow the printer's own status until the
 * page is really printed, so progress, "done" and errors match what the printer is doing.
 */
class PrintJobRunner(private val context: Context, private val store: ImageStore, private val printer: UsbPrinter) {

    suspend fun printSheets(
        sheets: List<Sheet>,
        s: PrintSettings,
        rotate180: Boolean,
        onProgress: (PrintProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): JobReport = withContext(Dispatchers.IO) {
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

        var totalBytes = 0L
        var header = ""
        val keep = File(context.cacheDir, "last_job_page1.prn").also { it.delete() }
        val geometry = PageGeometry(pageWpt, pageHpt, leftPt, topPt, areaWpt, areaHpt, dpi, DriverSettings.borderless(s), rotate180)
        var lastStatus: CanonStatus? = null
        var confirmed = 0
        var noBackChannel = false

        // Page i's share of the bar: 0–15 % drawing, 15–35 % sending, 35–100 % the printer printing it.
        fun frac(i: Int, sub: Float) = (i + sub.coerceIn(0f, 1f)) / total

        /** Draws page [i] and turns it into Canon printer data. */
        fun render(i: Int): File {
            val sheet = sheets[i]
            val source = BandRenderer(sheet, s, cache, geometry, wPx, isCancelled) { row ->
                if (i == confirmed) onProgress(PrintProgress(i + 1, total, "Preparing", frac(i, 0.15f * row / hPx), printed = confirmed))
            }
            val file = File(context.cacheDir, "print_page_${i + 1}.prn")
            try {
                val err = Gutenprint.printPage(Gutenprint.G2010_DRIVER, options, wPx, hPx, leftPt, topPt, areaWpt, areaHpt, source, file)
                if (err == "cancelled" || isCancelled()) throw PrintCancelled()
                if (err != null) throw IOException(err)
            } finally {
                source.release()
            }
            if (file.length() == 0L) throw IOException("The printer driver produced no data for page ${i + 1}.")
            enableBackChannel(file)
            return file
        }

        printer.busy = true
        try {
            runCatching { printer.readStatus() }.getOrNull()?.let {
                if (it.paperEmpty) throw IOException("Out of paper. Load paper in the rear tray, then print again.")
            }
            printer.session { session ->
                var next: File? = render(0)
                for (i in 0 until total) {
                    if (isCancelled()) throw PrintCancelled()
                    val file = next!!
                    next = null
                    try {
                        if (i == 0) {
                            header = readHeader(file)
                            file.copyTo(keep, overwrite = true)
                        }
                        totalBytes += file.length()
                        val size = file.length().coerceAtLeast(1)
                        var sendFrac = 0.15f
                        session.send(
                            file,
                            onProgress = { sent ->
                                sendFrac = 0.15f + 0.2f * sent / size
                                onProgress(PrintProgress(i + 1, total, "Sending", frac(i, sendFrac), printed = confirmed))
                            },
                            shouldContinue = { !isCancelled() },
                            onStall = { st, port, secs ->
                                if (st != null) lastStatus = st
                                onProgress(PrintProgress(i + 1, total, "Sending", frac(i, sendFrac), stallNotice(st, port, secs), printed = confirmed))
                            },
                        )
                    } catch (e: UsbPrinter.CancelledException) {
                        throw PrintCancelled()
                    } finally {
                        file.delete()
                    }

                    // While the printer prints this page, draw the next one so it's ready right after.
                    onProgress(PrintProgress(i + 1, total, "Printing", frac(i, 0.35f), null, printed = confirmed))
                    if (i + 1 < total) next = render(i + 1)

                    val result = if (noBackChannel) UsbPrinter.WaitResult.NoBackChannel else try {
                        session.waitUntilPrinted({ !isCancelled() }) { st, secs ->
                            if (st != null) lastStatus = st
                            // No page counter from the printer, so creep towards the end of this page's share.
                            val creep = 0.35f + 0.6f * (1f - 1f / (1f + secs / 20f))
                            onProgress(PrintProgress(i + 1, total, "Printing", frac(i, creep), st?.problem, printed = confirmed))
                        }
                    } catch (e: UsbPrinter.CancelledException) {
                        throw PrintCancelled()
                    }
                    when (result) {
                        UsbPrinter.WaitResult.StoppedOnPrinter -> throw IOException(
                            "Printing was stopped on the printer after page ${i + 1} of $total. Pages ${i + 2}–$total weren't printed.".let {
                                if (i + 1 >= total) "Printing was stopped on the printer." else it
                            },
                        )
                        UsbPrinter.WaitResult.NoBackChannel -> noBackChannel = true
                        else -> {}
                    }
                    confirmed = i + 1
                    onProgress(PrintProgress(minOf(i + 2, total), total, "Printing", frac(i, 1f), printed = confirmed))
                }
                if (noBackChannel) {
                    // The printer never answered: we only know the data was sent.
                    sleepQuietly(3_000)
                }
                Unit
            }
            onProgress(PrintProgress(total, total, "Checking printer", 1f, printed = confirmed))
        } finally {
            printer.busy = false
            cache.clear()
        }
        val after = runCatching { printer.readStatus() }.getOrNull()
        val report = JobReport(total, totalBytes, header, after, keep.takeIf { it.exists() }, lastStatus, confirmedByPrinter = !noBackChannel)
        if (after != null && (after.paperEmpty || after.error)) {
            throw PrinterReportedProblem(report, "The data was sent, but the printer reports: ${after.summary}.")
        }
        report
    }

    private fun sleepQuietly(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}

    /**
     * Gutenprint's Canon job header says <ivec:bidi>0</ivec:bidi>, which turns off the printer's
     * status replies for the job. Flip it to 1 in place (same length) so we can follow the printer.
     */
    private fun enableBackChannel(f: File) {
        val off = "<ivec:bidi>0</ivec:bidi>".toByteArray()
        java.io.RandomAccessFile(f, "rw").use { raf ->
            val n = minOf(4096L, raf.length()).toInt()
            val head = ByteArray(n)
            raf.readFully(head)
            var i = 0
            while (i <= n - off.size) {
                if ((off.indices).all { head[i + it] == off[it] }) {
                    raf.seek((i + off.size - "</ivec:bidi>".length - 1).toLong())
                    raf.write('1'.code)
                    i += off.size
                } else i++
            }
        }
    }

    /** What to tell the user while the printer isn't taking data. Short pauses are normal (it's printing). */
    private fun stallNotice(canon: CanonStatus?, st: PortStatus?, secs: Int): String? = when {
        canon?.problem != null -> canon.problem
        st == null && secs == 0 -> null
        st?.paperEmpty == true -> "The printer is out of paper. Load paper in the rear tray, then press the printer's Resume/Stop button — printing carries on."
        st?.error == true -> "The printer reports a problem (paper jam, cover open or ink). Fix it, then press the printer's Resume/Stop button — printing carries on."
        secs >= 45 -> "The printer hasn't taken data for $secs s. If paper is stuck, pull it out gently and press the Resume/Stop button. Or tap Cancel."
        else -> null
    }

    private fun readHeader(f: File): String {
        val b = ByteArray(24)
        val n = f.inputStream().use { it.read(b) }.coerceAtLeast(0)
        return (0 until n).joinToString("") { i ->
            val c = b[i].toInt() and 0xFF
            if (c in 32..126) c.toChar().toString() else "\\x%02x".format(c)
        }
    }
}

/** The job was sent but the printer's own status says something is wrong. */
class PrinterReportedProblem(val report: JobReport, message: String) : IOException(message)

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
