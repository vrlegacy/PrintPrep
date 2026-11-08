package io.printprep.app.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.webkit.WebView
import kotlin.math.ceil
import kotlin.math.min

/**
 * Turns a loaded WebView into page-shaped images. Requires WebView.enableSlowWholeDocumentDraw()
 * to have been called before the first WebView was created (done in MainActivity).
 * Must run on the main thread.
 */
object WebPageCapture {

    /** [pageAspect] = height / width of one printed page's usable area. Returns the number of slices. */
    fun capture(web: WebView, pageAspect: Float, maxPages: Int = 30, onSlice: (Bitmap, Int) -> Unit): Int {
        val width = web.width
        if (width <= 0) return 0
        val oldHeight = web.height

        web.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val fullHeight = web.measuredHeight.coerceAtLeast(1)
        web.layout(web.left, web.top, web.left + width, web.top + fullHeight)

        val sliceHeight = (width * pageAspect).toInt().coerceAtLeast(1)
        val pages = min(maxPages, ceil(fullHeight / sliceHeight.toFloat()).toInt())
        try {
            for (i in 0 until pages) {
                val bmp = Bitmap.createBitmap(width, sliceHeight, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.drawColor(Color.WHITE)
                c.translate(0f, -(i * sliceHeight).toFloat())
                web.draw(c)
                onSlice(bmp, i)
                bmp.recycle()
            }
        } finally {
            web.layout(web.left, web.top, web.left + width, web.top + oldHeight)
            web.requestLayout()
        }
        return pages
    }
}
