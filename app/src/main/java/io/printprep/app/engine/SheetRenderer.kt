package io.printprep.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import io.printprep.app.model.ColorMode
import io.printprep.app.model.FitMode
import io.printprep.app.model.ItemEdit
import io.printprep.app.model.JobItem
import io.printprep.app.model.PrintSettings
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Draws sheets onto any Canvas: the on-screen preview, a PDF page or a full-resolution bitmap.
 * The same code feeds the printer in Phase 2, so what you see is what prints.
 */
object SheetRenderer {

    /** Returns a bitmap for [item] whose longer side is at least [minLongSidePx] (or the original size). */
    fun interface BitmapLoader {
        fun load(item: JobItem, minLongSidePx: Int): Bitmap?
    }

    fun colorFilter(s: PrintSettings): ColorMatrixColorFilter? {
        val gray = s.color != ColorMode.COLOR
        var c = s.contrast.coerceIn(0.3f, 3f)
        if (s.color == ColorMode.BW_DOCUMENT) c *= 2.5f
        val b = s.brightness.coerceIn(-1f, 1f) * 255f
        if (!gray && abs(c - 1f) < 0.01f && abs(b) < 0.5f) return null
        val m = ColorMatrix()
        if (gray) m.setSaturation(0f)
        val t = (1f - c) * 128f + b
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        return ColorMatrixColorFilter(m)
    }

    /**
     * Draws one sheet. [pxPerMm] maps millimetres to canvas units; [dpi] decides how many
     * image pixels to load per cell (300 for PDF and printing, lower for the preview).
     */
    fun drawSheet(
        canvas: Canvas,
        sheet: Sheet,
        s: PrintSettings,
        pxPerMm: Float,
        dpi: Int,
        loader: BitmapLoader,
        preFiltered: Boolean = false,
    ) {
        canvas.drawColor(Color.WHITE)
        val filter = if (preFiltered) null else colorFilter(s)
        val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        for (p in sheet.placements) {
            val cell = p.cell.toPx(pxPerMm)
            val needPx = neededPixels(p, s, dpi)
            val src = loader.load(p.item, needPx) ?: continue
            // Bake the colour filter into the bitmap so it also works on PDF canvases.
            val bmp = if (filter != null) applyFilter(src, filter) else src
            drawItem(canvas, bmp, cell, p.item.edit, s.fit, s.scalePercent, p.rotation, imagePaint)
            if (s.border) {
                canvas.drawRect(cell, strokePaint(pxPerMm, Color.rgb(90, 90, 90), dashed = false))
            }
        }
        if (s.cutLines) {
            val dash = strokePaint(pxPerMm, Color.rgb(170, 170, 170), dashed = true)
            for (p in sheet.placements) canvas.drawRect(p.cell.toPx(pxPerMm), dash)
        }
    }

    /** Draws a bitmap into a cell with fit/fill, user zoom/pan, rotation and flip; clipped to the cell. */
    fun drawItem(
        canvas: Canvas,
        bmp: Bitmap,
        cell: RectF,
        edit: ItemEdit,
        fit: FitMode,
        scalePercent: Int,
        rotation: Int,
        paint: Paint,
    ) {
        if (bmp.width <= 0 || bmp.height <= 0 || cell.width() <= 0f || cell.height() <= 0f) return
        val src = Rect(
            (edit.cropLeft * bmp.width).toInt().coerceIn(0, bmp.width - 1),
            (edit.cropTop * bmp.height).toInt().coerceIn(0, bmp.height - 1),
            0, 0,
        )
        src.right = (edit.cropRight * bmp.width).toInt().coerceIn(src.left + 1, bmp.width)
        src.bottom = (edit.cropBottom * bmp.height).toInt().coerceIn(src.top + 1, bmp.height)
        val w = src.width().toFloat()
        val h = src.height().toFloat()
        val (rw, rh) = if (rotation % 180 != 0) h to w else w to h
        val base = when (fit) {
            FitMode.FIT -> min(cell.width() / rw, cell.height() / rh)
            FitMode.FILL -> max(cell.width() / rw, cell.height() / rh)
        }
        val scale = base * edit.zoom * (scalePercent / 100f)

        canvas.save()
        canvas.clipRect(cell)
        canvas.translate(
            cell.centerX() + edit.offsetX * cell.width(),
            cell.centerY() + edit.offsetY * cell.height(),
        )
        canvas.rotate(rotation.toFloat())
        if (edit.flip) canvas.scale(-1f, 1f)
        canvas.scale(scale, scale)
        canvas.drawBitmap(bmp, src, RectF(-w / 2f, -h / 2f, w / 2f, h / 2f), paint)
        canvas.restore()
    }

    private fun neededPixels(p: Placement, s: PrintSettings, dpi: Int): Int {
        val longMm = max(p.cell.width, p.cell.height)
        val extra = max(1f, p.item.edit.zoom) / min(p.item.edit.cropWidth, p.item.edit.cropHeight) *
            (if (s.fit == FitMode.FILL) 1.5f else 1f) *
            max(1f, s.scalePercent / 100f)
        return ceil(longMm / 25.4f * dpi * extra).toInt().coerceIn(64, 4000)
    }

    fun applyFilter(src: Bitmap, filter: ColorMatrixColorFilter): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = filter })
        return out
    }

    private fun strokePaint(pxPerMm: Float, color: Int, dashed: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.color = color
        strokeWidth = max(0.5f, 0.2f * pxPerMm)
        if (dashed) pathEffect = DashPathEffect(floatArrayOf(2f * pxPerMm, 1.5f * pxPerMm), 0f)
    }

    private fun MmRect.toPx(k: Float) = RectF(left * k, top * k, right * k, bottom * k)
}
