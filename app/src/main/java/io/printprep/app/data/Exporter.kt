package io.printprep.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import io.printprep.app.engine.Placement
import io.printprep.app.engine.PrintPlan
import io.printprep.app.engine.SheetRenderer
import io.printprep.app.model.ColorMode
import io.printprep.app.model.FitMode
import io.printprep.app.model.ItemKind
import io.printprep.app.model.JobItem
import io.printprep.app.model.PrintSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ExportResult(val uris: List<Uri>, val mime: String, val message: String)

/**
 * The Phase 1 "mock printer": renders every sheet in print order at full resolution and
 * saves it as a PDF (Downloads/PrintPrep) or as one image per sheet (Pictures/PrintPrep).
 * In the PDF, photos keep their original quality.
 */
class Exporter(private val context: Context, private val store: ImageStore) {

    private val loader = SheetRenderer.BitmapLoader { item, px -> store.decode(item, px, useCache = false) }

    /**
     * Writes the PDF with photos at their original quality (see [PdfWriter]).
     * Layout, crop, rotation and fit are applied as PDF transforms, so the pixels are never resampled.
     */
    suspend fun savePdf(plan: PrintPlan, s: PrintSettings, baseName: String, onProgress: (Int, Int) -> Unit): ExportResult =
        withContext(Dispatchers.IO) {
            val name = "$baseName.pdf"
            val uri = create(name, "application/pdf", MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), Environment.DIRECTORY_DOWNLOADS)
            var originals = 0
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    val pdf = PdfWriter(out)
                    val filter = SheetRenderer.colorFilter(s)
                    val gray = s.color != ColorMode.COLOR
                    val embedded = HashMap<Long, PdfWriter.Image?>()
                    val k = 72.0 / 25.4

                    plan.sheets.forEachIndexed { i, sheet ->
                        val pageW = sheet.paperW * k
                        val pageH = sheet.paperH * k
                        val content = StringBuilder()
                        val used = LinkedHashMap<Int, PdfWriter.Image>()

                        for (p in sheet.placements) {
                            val img = embedded.getOrPut(p.item.id) {
                                embed(pdf, p.item, filter, gray) { originals++ }
                            } ?: continue
                            used[img.objectId] = img
                            val cl = p.cell.left * k
                            val ct = p.cell.top * k
                            val cw = p.cell.width * k
                            val ch = p.cell.height * k
                            content.append("q ")
                                .append("${PdfWriter.n(cl)} ${PdfWriter.n(pageH - ct - ch)} ${PdfWriter.n(cw)} ${PdfWriter.n(ch)} re W n ")
                            val (outer, inner, cropW, cropH) = placementMatrices(p, s, img, cl, ct, cw, ch, pageH)
                            content.append(outer.toPdf())
                                .append(" 0 0 ${PdfWriter.n(cropW)} ${PdfWriter.n(cropH)} re W n ")
                                .append(inner.toPdf())
                                .append(" /Im${img.objectId} Do Q\n")
                        }
                        if (s.border || s.cutLines) {
                            val lw = 0.2 * k
                            content.append("q ${PdfWriter.n(lw)} w ")
                            content.append(if (s.cutLines && !s.border) "0.67 G [${PdfWriter.n(2 * k)} ${PdfWriter.n(1.5 * k)}] 0 d " else "0.35 G ")
                            for (p in sheet.placements) {
                                content.append("${PdfWriter.n(p.cell.left * k)} ${PdfWriter.n(pageH - p.cell.bottom * k)} ")
                                    .append("${PdfWriter.n(p.cell.width * k)} ${PdfWriter.n(p.cell.height * k)} re S ")
                            }
                            content.append("Q\n")
                        }
                        pdf.addPage(pageW, pageH, content.toString(), used.values)
                        onProgress(i + 1, plan.sheets.size)
                    }
                    pdf.finish()
                } ?: throw IOException("Cannot write $name")
                finish(uri)
            } catch (e: Throwable) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw e
            }
            val note = if (originals > 0) " $originals photo(s) embedded unchanged from the original file." else ""
            ExportResult(listOf(uri), "application/pdf", "Saved $name to Downloads/PrintPrep (${plan.sheets.size} pages).$note")
        }

    private fun isOriginal(item: JobItem, filter: android.graphics.ColorMatrixColorFilter?) =
        filter == null && item.kind == ItemKind.IMAGE

    /**
     * Original JPEG bytes when possible (no colour changes, plain JPEG); otherwise the full-resolution
     * pixels, colour-adjusted, embedded losslessly. PDF pages are rendered at about 400 DPI.
     */
    private fun embed(
        pdf: PdfWriter,
        item: JobItem,
        filter: android.graphics.ColorMatrixColorFilter?,
        gray: Boolean,
        onOriginalJpeg: () -> Unit,
    ): PdfWriter.Image? {
        if (isOriginal(item, filter)) {
            val bytes = runCatching { File(item.file).readBytes() }.getOrNull()
            val info = bytes?.let { jpegInfo(it) }
            if (bytes != null && info != null) {
                onOriginalJpeg()
                return pdf.addJpeg(bytes, info, store.exifOrientation(item))
            }
        }
        val src = when (item.kind) {
            ItemKind.PDF_PAGE -> store.decode(item, PDF_PAGE_LONG_SIDE_PX, useCache = false)
            ItemKind.IMAGE, ItemKind.WEB_PAGE -> store.decodeFull(item)
        } ?: return null
        val bmp = if (filter != null) SheetRenderer.applyFilter(src, filter) else src
        return pdf.addBitmap(bmp, gray)
    }

    private data class Matrices(val outer: Affine, val inner: Affine, val cropW: Double, val cropH: Double)

    /**
     * Same maths as SheetRenderer.drawItem, as two PDF matrices:
     * [Matrices.outer] maps the cropped image (pixels, y down) onto the page, where we clip to the crop;
     * [Matrices.inner] places the whole original image so that the crop lines up. Pixels are never resampled.
     */
    private fun placementMatrices(
        p: Placement, s: PrintSettings, img: PdfWriter.Image,
        cl: Double, ct: Double, cw: Double, ch: Double, pageH: Double,
    ): Matrices {
        val e = p.item.edit
        val fullW = img.width.toDouble()
        val fullH = img.height.toDouble()
        val w = fullW * e.cropWidth
        val h = fullH * e.cropHeight
        val (rw, rh) = if (p.rotation % 180 != 0) h to w else w to h
        val base = when (s.fit) {
            FitMode.FIT -> min(cw / rw, ch / rh)
            FitMode.FILL -> max(cw / rw, ch / rh)
        }
        val scale = base * e.zoom * (s.scalePercent / 100.0)

        // In "canvas" space (y down, points): exactly what the preview does.
        var m = Affine.translate(cl + cw / 2 + e.offsetX * cw, ct + ch / 2 + e.offsetY * ch) * PdfWriter.rotate(p.rotation + e.angle.toDouble())
        if (e.flip) m = m * Affine.scale(-1.0, 1.0)
        m = m * Affine.scale(scale, scale) * Affine.translate(-w / 2, -h / 2)

        val toPdfPage = Affine(1.0, 0.0, 0.0, -1.0, 0.0, pageH)          // y down → y up
        val unitToRaw = Affine(1.0, 0.0, 0.0, -1.0, 0.0, 1.0)            // PDF unit square → raw image (y down)
        val inner = Affine.translate(-e.cropLeft * fullW, -e.cropTop * fullH) *
            Affine.scale(fullW, fullH) * PdfWriter.exif(img.exifOrientation) * unitToRaw
        return Matrices(toPdfPage * m, inner, w, h)
    }

    suspend fun saveImages(
        plan: PrintPlan,
        s: PrintSettings,
        baseName: String,
        dpi: Int,
        format: ImageFormat,
        onProgress: (Int, Int) -> Unit,
    ): ExportResult = withContext(Dispatchers.IO) {
        val mime = format.mime
        val ext = format.ext
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uris = plan.sheets.mapIndexed { i, sheet ->
            val k = dpi / 25.4f
            val bmp = Bitmap.createBitmap((sheet.paperW * k).roundToInt(), (sheet.paperH * k).roundToInt(), Bitmap.Config.ARGB_8888)
            try {
                SheetRenderer.drawSheet(Canvas(bmp), sheet, s, k, dpi, loader)
                val name = "%s_%02d.%s".format(baseName, i + 1, ext)
                val uri = create(name, mime, collection, Environment.DIRECTORY_PICTURES)
                context.contentResolver.openOutputStream(uri)?.use {
                    bmp.compress(format.compress, 92, it)
                } ?: throw IOException("Cannot write $name")
                finish(uri)
                onProgress(i + 1, plan.sheets.size)
                uri
            } finally {
                bmp.recycle()
            }
        }
        ExportResult(uris, mime, "Saved ${uris.size} ${format.label} image(s) to Pictures/PrintPrep at $dpi DPI.")
    }

    private fun create(displayName: String, mime: String, collection: Uri, rootDir: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$rootDir/PrintPrep")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(collection, values) ?: throw IOException("Cannot create $displayName")
    }

    private fun finish(uri: Uri) {
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    companion object {
        const val PRINT_DPI = 300
        /** Imported PDF pages are rasterised at this size (≈ 400 DPI on A4). */
        const val PDF_PAGE_LONG_SIDE_PX = 4700
    }
}
