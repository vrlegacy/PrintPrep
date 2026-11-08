package io.printprep.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import io.printprep.app.model.ItemKind
import io.printprep.app.model.JobItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Copies shared files into the app's cache (share permissions expire), and decodes
 * images / PDF pages at the size each use needs, with a memory cache for the preview.
 */
class ImageStore(private val context: Context) {

    data class ImportResult(val items: List<JobItem>, val failed: List<String>)

    private val dir = File(context.cacheDir, "imports").apply { mkdirs() }
    private val nextId = AtomicLong(System.currentTimeMillis())
    private val pdfLock = Any()

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    suspend fun importUris(uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        val items = mutableListOf<JobItem>()
        val failed = mutableListOf<String>()
        for (uri in uris) {
            val name = displayName(uri)
            try {
                val id = nextId.incrementAndGet()
                val file = File(dir, "${id}_${name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)}")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                } ?: error("cannot open")
                if (isPdf(file)) items += pdfPages(file, name) else items += imageItem(file, name)
            } catch (e: Exception) {
                failed += name
            }
        }
        ImportResult(items, failed)
    }

    /** Saves a rendered bitmap (e.g. a web-page slice) as a new item. Call off the main thread if possible. */
    fun saveBitmapItem(bitmap: Bitmap, name: String, kind: ItemKind): JobItem {
        val id = nextId.incrementAndGet()
        val file = File(dir, "$id.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return JobItem(id, file.absolutePath, kind, 0, bitmap.width, bitmap.height, name)
    }

    /**
     * A bitmap for [item] whose long side is at least [minLongSidePx] (or as large as the source).
     * Cached bitmaps are shared: callers must not modify or recycle them.
     */
    fun decode(item: JobItem, minLongSidePx: Int, useCache: Boolean): Bitmap? {
        val bucket = Integer.highestOneBit(max(1, minLongSidePx - 1)) shl 1
        val key = "${item.id}@$bucket"
        if (useCache) cache.get(key)?.let { return it }
        val bmp = runCatching {
            when (item.kind) {
                ItemKind.PDF_PAGE -> renderPdfPage(File(item.file), item.pageIndex, bucket)
                ItemKind.IMAGE, ItemKind.WEB_PAGE -> decodeImage(File(item.file), bucket)
            }
        }.getOrNull() ?: return null
        if (useCache) cache.put(key, bmp)
        return bmp
    }

    fun clear() {
        cache.evictAll()
        dir.listFiles()?.forEach { it.delete() }
    }

    // ------------------------------------------------------------------

    private fun imageItem(file: File, name: String): JobItem {
        val probe = decodeImage(file, 256)
        return JobItem(nextId.incrementAndGet(), file.absolutePath, ItemKind.IMAGE, 0, probe.width, probe.height, name)
    }

    private fun pdfPages(file: File, name: String): List<JobItem> = synchronized(pdfLock) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                (0 until r.pageCount).map { i ->
                    r.openPage(i).use { p ->
                        JobItem(nextId.incrementAndGet(), file.absolutePath, ItemKind.PDF_PAGE, i, p.width, p.height, "$name · p${i + 1}")
                    }
                }
            }
        }
    }

    /** Decodes with EXIF rotation applied (ImageDecoder does this), subsampling by powers of two. */
    private fun decodeImage(file: File, minLongSidePx: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val long = max(info.size.width, info.size.height)
            var sample = 1
            while (long / (sample * 2) >= minLongSidePx) sample *= 2
            decoder.setTargetSampleSize(sample)
        }

    private fun renderPdfPage(file: File, index: Int, minLongSidePx: Int): Bitmap = synchronized(pdfLock) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                r.openPage(index).use { page ->
                    val k = minLongSidePx.toFloat() / max(page.width, page.height)
                    val w = max(1, (page.width * k).roundToInt())
                    val h = max(1, (page.height * k).roundToInt())
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    bmp
                }
            }
        }
    }

    private fun isPdf(file: File): Boolean = runCatching {
        file.inputStream().use { s ->
            val head = ByteArray(5)
            s.read(head) == 5 && String(head, Charsets.US_ASCII) == "%PDF-"
        }
    }.getOrDefault(false)

    private fun displayName(uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }
}
