package io.printprep.app.data

import android.graphics.Bitmap
import io.printprep.app.model.ItemKind
import io.printprep.app.model.JobItem
import java.io.File

/** Formats "Save as images" can write. */
enum class ImageFormat(val label: String, val mime: String, val ext: String) {
    JPEG("JPEG", "image/jpeg", "jpg"),
    PNG("PNG", "image/png", "png"),
    WEBP("WebP", "image/webp", "webp");

    val compress: Bitmap.CompressFormat
        get() = when (this) {
            JPEG -> Bitmap.CompressFormat.JPEG
            PNG -> Bitmap.CompressFormat.PNG
            WEBP -> Bitmap.CompressFormat.WEBP_LOSSY
        }

    companion object {
        /**
         * Picks the output format from what was added: the most common source format among the photos
         * (HEIC and GIF become JPEG and PNG, which every phone can open). Only documents and web pages
         * → PNG, which keeps text sharp.
         */
        fun auto(items: List<JobItem>): ImageFormat {
            val photos = items.filter { it.kind == ItemKind.IMAGE }
            if (photos.isEmpty()) return PNG
            return photos.groupingBy { sourceFormat(File(it.file)) }.eachCount().maxByOrNull { it.value }?.key ?: JPEG
        }

        /** Reads the file's first bytes to tell its real format (file names and types from other apps can be wrong). */
        fun sourceFormat(file: File): ImageFormat = runCatching {
            val h = ByteArray(16)
            val n = file.inputStream().use { it.read(h) }
            when {
                n >= 3 && h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() -> JPEG
                n >= 8 && h[0] == 0x89.toByte() && h[1] == 'P'.code.toByte() && h[2] == 'N'.code.toByte() -> PNG
                n >= 12 && String(h, 0, 4, Charsets.US_ASCII) == "RIFF" && String(h, 8, 4, Charsets.US_ASCII) == "WEBP" -> WEBP
                n >= 6 && String(h, 0, 3, Charsets.US_ASCII) == "GIF" -> PNG
                else -> JPEG // HEIC/HEIF and anything else
            }
        }.getOrDefault(JPEG)
    }
}
