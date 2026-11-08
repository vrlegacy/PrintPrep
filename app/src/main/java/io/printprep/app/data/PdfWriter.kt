package io.printprep.app.data

import android.graphics.Bitmap
import java.io.BufferedOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * A small PDF writer that keeps photos at their original quality:
 *  - JPEG files are embedded byte-for-byte (DCTDecode): no re-compression, no resizing.
 *  - Everything else is embedded at full resolution with lossless (Flate) compression.
 * Images used several times (copies, repeat-to-fill) are stored once.
 */
class PdfWriter(out: OutputStream) {

    /** An embedded image: PDF object id and its pixel size *after* EXIF orientation. */
    data class Image(val objectId: Int, val width: Int, val height: Int, val exifOrientation: Int)

    private val os = CountingStream(BufferedOutputStream(out, 1 shl 16))
    private val offsets = HashMap<Int, Long>()
    private val pageIds = mutableListOf<Int>()
    private var nextId = 3 // 1 = catalog, 2 = page tree

    init {
        os.write("%PDF-1.4\n".toByteArray(Charsets.US_ASCII))
        os.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
    }

    /** Embeds an original JPEG file unchanged. [info] comes from [jpegInfo]. */
    fun addJpeg(bytes: ByteArray, info: JpegInfo, exifOrientation: Int): Image {
        val id = newId()
        begin(id)
        val cs = if (info.components == 1) "/DeviceGray" else "/DeviceRGB"
        text("<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} /ColorSpace $cs " +
            "/BitsPerComponent 8 /Filter /DCTDecode /Length ${bytes.size} >>\nstream\n")
        os.write(bytes)
        text("\nendstream\nendobj\n")
        val turned = exifOrientation in 5..8
        return Image(id, if (turned) info.height else info.width, if (turned) info.width else info.height, exifOrientation)
    }

    /** Embeds a bitmap losslessly at its full size. Transparent areas become white. */
    fun addBitmap(bmp: Bitmap, gray: Boolean): Image {
        val id = newId()
        val lengthId = newId()
        val w = bmp.width
        val h = bmp.height
        begin(id)
        text("<< /Type /XObject /Subtype /Image /Width $w /Height $h /ColorSpace ${if (gray) "/DeviceGray" else "/DeviceRGB"} " +
            "/BitsPerComponent 8 /Filter /FlateDecode /Length $lengthId 0 R >>\nstream\n")
        val start = os.count
        val deflate = DeflaterOutputStream(os, Deflater(6), 1 shl 16)
        val row = IntArray(w)
        val bytes = ByteArray(if (gray) w else w * 3)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var o = 0
            for (x in 0 until w) {
                val p = row[x]
                val a = p ushr 24
                var r = (p shr 16) and 0xFF
                var g = (p shr 8) and 0xFF
                var b = p and 0xFF
                if (a < 255) { // blend onto white paper
                    r = (r * a + 255 * (255 - a)) / 255
                    g = (g * a + 255 * (255 - a)) / 255
                    b = (b * a + 255 * (255 - a)) / 255
                }
                if (gray) {
                    bytes[o++] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
                } else {
                    bytes[o++] = r.toByte(); bytes[o++] = g.toByte(); bytes[o++] = b.toByte()
                }
            }
            deflate.write(bytes)
        }
        deflate.finish()
        val length = os.count - start
        text("\nendstream\nendobj\n")
        begin(lengthId)
        text("$length\nendobj\n")
        return Image(id, w, h, 1)
    }

    /** Adds a page of [widthPt] × [heightPt] points with the given content stream. */
    fun addPage(widthPt: Double, heightPt: Double, content: String, images: Collection<Image>) {
        val bytes = content.toByteArray(Charsets.US_ASCII)
        val contentId = newId()
        begin(contentId)
        text("<< /Length ${bytes.size} >>\nstream\n")
        os.write(bytes)
        text("\nendstream\nendobj\n")

        val pageId = newId()
        begin(pageId)
        val xobjects = images.joinToString(" ") { "/Im${it.objectId} ${it.objectId} 0 R" }
        text("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(widthPt)} ${n(heightPt)}] " +
            "/Resources << /XObject << $xobjects >> /ProcSet [/PDF /ImageB /ImageC] >> /Contents $contentId 0 R >>\nendobj\n")
        pageIds += pageId
    }

    fun finish() {
        begin(2)
        text("<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pageIds.size} >>\nendobj\n")
        begin(1)
        text("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        val xref = os.count
        val sb = StringBuilder("xref\n0 $nextId\n0000000000 65535 f \n")
        for (i in 1 until nextId) sb.append(String.format(Locale.US, "%010d 00000 n \n", offsets[i] ?: 0L))
        sb.append("trailer\n<< /Size $nextId /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        text(sb.toString())
        os.flush()
    }

    private fun newId() = nextId++

    private fun begin(id: Int) {
        offsets[id] = os.count
        text("$id 0 obj\n")
    }

    private fun text(s: String) = os.write(s.toByteArray(Charsets.US_ASCII))

    private class CountingStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b); count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len); count += len
        }
    }

    companion object {
        fun n(v: Double): String = String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

        fun rotate(degrees: Double): Affine {
            val r = Math.toRadians(degrees)
            return Affine(cos(r), sin(r), -sin(r), cos(r), 0.0, 0.0)
        }

        /** Maps raw-image coordinates (0..1, y down) to upright coordinates for an EXIF orientation. */
        fun exif(orientation: Int): Affine = when (orientation) {
            2 -> Affine(-1.0, 0.0, 0.0, 1.0, 1.0, 0.0)
            3 -> Affine(-1.0, 0.0, 0.0, -1.0, 1.0, 1.0)
            4 -> Affine(1.0, 0.0, 0.0, -1.0, 0.0, 1.0)
            5 -> Affine(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
            6 -> Affine(0.0, 1.0, -1.0, 0.0, 1.0, 0.0)
            7 -> Affine(0.0, -1.0, -1.0, 0.0, 1.0, 1.0)
            8 -> Affine(0.0, -1.0, 1.0, 0.0, 0.0, 1.0)
            else -> Affine.IDENTITY
        }
    }
}

/** 2-D affine transform: (x, y) → (a·x + c·y + e, b·x + d·y + f), the same layout as PDF's "cm". */
data class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    /** this ∘ o: applies [o] first, then this. */
    operator fun times(o: Affine) = Affine(
        a * o.a + c * o.b, b * o.a + d * o.b,
        a * o.c + c * o.d, b * o.c + d * o.d,
        a * o.e + c * o.f + e, b * o.e + d * o.f + f,
    )

    fun toPdf(): String = listOf(a, b, c, d, e, f).joinToString(" ") { PdfWriter.n(it) } + " cm"

    companion object {
        val IDENTITY = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        fun translate(x: Double, y: Double) = Affine(1.0, 0.0, 0.0, 1.0, x, y)
        fun scale(sx: Double, sy: Double) = Affine(sx, 0.0, 0.0, sy, 0.0, 0.0)
    }
}

data class JpegInfo(val width: Int, val height: Int, val components: Int)

/** Reads size and colour components from a baseline/progressive 8-bit JPEG, or null if unsupported. */
fun jpegInfo(b: ByteArray): JpegInfo? {
    if (b.size < 4 || (b[0].toInt() and 0xFF) != 0xFF || (b[1].toInt() and 0xFF) != 0xD8) return null
    var i = 2
    while (i + 9 < b.size) {
        if ((b[i].toInt() and 0xFF) != 0xFF) { i++; continue }
        val marker = b[i + 1].toInt() and 0xFF
        when {
            marker == 0xFF -> { i++; continue }
            marker == 0x01 || marker in 0xD0..0xD8 -> { i += 2; continue }
            marker == 0xD9 || marker == 0xDA -> return null
        }
        val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
        // SOF0/1/2 = baseline, extended, progressive Huffman: the ones every PDF reader supports.
        if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {
            val precision = b[i + 4].toInt() and 0xFF
            val h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
            val w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
            val comps = b[i + 9].toInt() and 0xFF
            return if (precision == 8 && w > 0 && h > 0 && (comps == 1 || comps == 3)) JpegInfo(w, h, comps) else null
        }
        if (marker in 0xC3..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) return null // other SOF types
        i += 2 + len
    }
    return null
}
