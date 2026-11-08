package io.printprep.app.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

/** Simple generated pages for the two-sided setup test and the printer test page (A4 at 100 DPI). */
object TestPages {
    private const val W = 827
    private const val H = 1169

    /** Four pages = two sheets: "Sheet 1 FRONT", "Sheet 1 BACK", "Sheet 2 FRONT", "Sheet 2 BACK". */
    fun duplexTest(): List<Pair<String, Bitmap>> = listOf(
        "Sheet 1 FRONT" to labelled("SHEET 1", "FRONT"),
        "Sheet 1 BACK" to labelled("SHEET 1", "BACK"),
        "Sheet 2 FRONT" to labelled("SHEET 2", "FRONT"),
        "Sheet 2 BACK" to labelled("SHEET 2", "BACK"),
    )

    private fun labelled(top: String, bottom: String): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        // big arrow pointing to the top edge
        val arrow = Path().apply {
            moveTo(W / 2f, 120f); lineTo(W / 2f + 140f, 320f); lineTo(W / 2f + 50f, 320f)
            lineTo(W / 2f + 50f, 520f); lineTo(W / 2f - 50f, 520f); lineTo(W / 2f - 50f, 320f)
            lineTo(W / 2f - 140f, 320f); close()
        }
        c.drawPath(arrow, p)
        p.textSize = 110f
        c.drawText(top, W / 2f, 720f, p)
        p.textSize = 150f
        c.drawText(bottom, W / 2f, 900f, p)
        p.textSize = 34f
        p.typeface = Typeface.DEFAULT
        c.drawText("PrintPrep two-sided setup test · arrow = top of the page", W / 2f, 1080f, p)
        return b
    }

    /** Colour bars, grey steps and text, to check the printer works and colours look right. */
    fun printerTest(): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = Typeface.DEFAULT_BOLD; textSize = 64f }
        c.drawText("PrintPrep test page", 60f, 140f, p)
        p.typeface = Typeface.DEFAULT; p.textSize = 30f
        c.drawText("If you can read this, phone → USB → Canon G2010 printing works.", 60f, 200f, p)
        val colors = intArrayOf(Color.CYAN, Color.MAGENTA, Color.YELLOW, Color.BLACK, Color.RED, Color.GREEN, Color.BLUE)
        val bw = (W - 120f) / colors.size
        colors.forEachIndexed { i, col ->
            p.color = col
            c.drawRect(60f + i * bw, 260f, 60f + (i + 1) * bw - 8f, 460f, p)
        }
        for (i in 0 until 10) {
            p.color = Color.rgb(255 - i * 28, 255 - i * 28, 255 - i * 28)
            c.drawRect(60f + i * (W - 120f) / 10, 500f, 60f + (i + 1) * (W - 120f) / 10, 600f, p)
        }
        p.color = Color.BLACK; p.style = Paint.Style.STROKE; p.strokeWidth = 3f
        c.drawRect(20f, 20f, W - 20f, H - 20f, p)
        p.style = Paint.Style.FILL; p.textSize = 26f
        for (i in 0 until 12) c.drawText("Line ${i + 1}: The quick brown fox jumps over the lazy dog 0123456789", 60f, 680f + i * 36f, p)
        return b
    }
}
