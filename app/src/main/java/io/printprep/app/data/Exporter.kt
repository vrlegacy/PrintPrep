package io.printprep.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import io.printprep.app.engine.PrintPlan
import io.printprep.app.engine.SheetRenderer
import io.printprep.app.model.PrintSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.roundToInt

data class ExportResult(val uris: List<Uri>, val mime: String, val message: String)

/**
 * The Phase 1 "mock printer": renders every sheet in print order at full resolution and
 * saves it as a PDF (Downloads/PrintPrep) or as one image per sheet (Pictures/PrintPrep).
 */
class Exporter(private val context: Context, private val store: ImageStore) {

    private val loader = SheetRenderer.BitmapLoader { item, px -> store.decode(item, px, useCache = false) }

    suspend fun savePdf(plan: PrintPlan, s: PrintSettings, baseName: String, onProgress: (Int, Int) -> Unit): ExportResult =
        withContext(Dispatchers.IO) {
            val doc = PdfDocument()
            try {
                plan.sheets.forEachIndexed { i, sheet ->
                    val wPt = (sheet.paperW / 25.4f * 72f).roundToInt()
                    val hPt = (sheet.paperH / 25.4f * 72f).roundToInt()
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(wPt, hPt, i + 1).create())
                    SheetRenderer.drawSheet(page.canvas, sheet, s, 72f / 25.4f, PRINT_DPI, loader)
                    doc.finishPage(page)
                    onProgress(i + 1, plan.sheets.size)
                }
                val name = "$baseName.pdf"
                val uri = create(name, "application/pdf", MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), Environment.DIRECTORY_DOWNLOADS)
                context.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) } ?: throw IOException("Cannot write $name")
                finish(uri)
                ExportResult(listOf(uri), "application/pdf", "Saved $name to Downloads/PrintPrep (${plan.sheets.size} pages).")
            } finally {
                doc.close()
            }
        }

    suspend fun saveImages(
        plan: PrintPlan,
        s: PrintSettings,
        baseName: String,
        dpi: Int,
        png: Boolean,
        onProgress: (Int, Int) -> Unit,
    ): ExportResult = withContext(Dispatchers.IO) {
        val mime = if (png) "image/png" else "image/jpeg"
        val ext = if (png) "png" else "jpg"
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uris = plan.sheets.mapIndexed { i, sheet ->
            val k = dpi / 25.4f
            val bmp = Bitmap.createBitmap((sheet.paperW * k).roundToInt(), (sheet.paperH * k).roundToInt(), Bitmap.Config.ARGB_8888)
            try {
                SheetRenderer.drawSheet(Canvas(bmp), sheet, s, k, dpi, loader)
                val name = "%s_%02d.%s".format(baseName, i + 1, ext)
                val uri = create(name, mime, collection, Environment.DIRECTORY_PICTURES)
                context.contentResolver.openOutputStream(uri)?.use {
                    bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it)
                } ?: throw IOException("Cannot write $name")
                finish(uri)
                onProgress(i + 1, plan.sheets.size)
                uri
            } finally {
                bmp.recycle()
            }
        }
        ExportResult(uris, mime, "Saved ${uris.size} image(s) to Pictures/PrintPrep at $dpi DPI.")
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
    }
}
