package io.printprep.app.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import io.printprep.app.model.PrintTone

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

    /**
     * Colour-match sheet (A4 at 150 DPI): the same picture six times, each with one [PrintTone]
     * already applied, labelled A–F. Printed with no extra tone, so the user can pick the panel that
     * looks most like the original. Uses [photo] if given, plus grey steps and colour patches.
     */
    fun colourMatch(photo: Bitmap?): Bitmap {
        val w = 1240; val h = 1754
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.WHITE)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = Typeface.DEFAULT_BOLD; textSize = 46f }
        c.drawText("PrintPrep colour match", 70f, 100f, text)
        text.typeface = Typeface.DEFAULT; text.textSize = 26f
        c.drawText("Pick the panel that looks most like the original on your phone, then choose that letter in Settings.", 70f, 145f, text)

        val tones = PrintTone.entries
        val cols = 2; val rows = 3
        val top = 180f; val gap = 30f; val side = 70f
        val pw = (w - side * 2 - gap) / cols
        val ph = (h - top - 60f - gap * (rows - 1)) / rows
        tones.forEachIndexed { i, t ->
            val x = side + (i % cols) * (pw + gap)
            val y = top + (i / cols) * (ph + gap)
            val panel = panel(photo, pw.toInt(), (ph - 56f).toInt())
            if (!t.isIdentity) {
                val px = IntArray(panel.width * panel.height)
                panel.getPixels(px, 0, panel.width, 0, 0, panel.width, panel.height)
                for (k in px.indices) px[k] = (0xFF shl 24) or t.apply(px[k])
                panel.setPixels(px, 0, panel.width, 0, 0, panel.width, panel.height)
            }
            c.drawBitmap(panel, x, y + 56f, null)
            panel.recycle()
            text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 40f
            c.drawText(t.letter, x, y + 42f, text)
            text.typeface = Typeface.DEFAULT; text.textSize = 26f
            c.drawText(t.label, x + 44f, y + 40f, text)
        }
        return b
    }

    /** One panel: the photo (centre-cropped) above grey steps and colour patches. */
    private fun panel(photo: Bitmap?, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.WHITE)
        val chartH = (h * 0.28f).toInt()
        val picH = h - chartH - 8
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (photo != null) {
            val scale = maxOf(w.toFloat() / photo.width, picH.toFloat() / photo.height)
            val sw = (w / scale).toInt(); val sh = (picH / scale).toInt()
            val sx = (photo.width - sw) / 2; val sy = (photo.height - sh) / 2
            c.drawBitmap(photo, Rect(sx, sy, sx + sw, sy + sh), Rect(0, 0, w, picH), p)
        } else {
            // No photo in the job: smooth colour gradients and a skin-tone ramp instead.
            val bh = picH / 2f
            for (xx in 0 until w) {
                val f = xx.toFloat() / (w - 1)
                val hue = f * 300f
                p.color = Color.HSVToColor(floatArrayOf(hue, 0.8f, 0.95f))
                c.drawRect(xx.toFloat(), 0f, xx + 1f, bh, p)
                val skin = Color.rgb((250 - 110 * f).toInt(), (215 - 135 * f).toInt(), (185 - 125 * f).toInt())
                p.color = skin
                c.drawRect(xx.toFloat(), bh, xx + 1f, picH.toFloat(), p)
            }
        }
        val y0 = picH + 8f
        val half = chartH / 2f - 2f
        val steps = 11
        val sw = w.toFloat() / steps
        for (k in 0 until steps) {
            val v = 255 - k * 255 / (steps - 1)
            p.color = Color.rgb(v, v, v)
            c.drawRect(RectF(k * sw, y0, (k + 1) * sw, y0 + half), p)
        }
        val patches = intArrayOf(
            Color.rgb(0, 160, 230), Color.rgb(230, 0, 125), Color.rgb(255, 230, 0), Color.rgb(200, 40, 40),
            Color.rgb(40, 150, 60), Color.rgb(40, 60, 170), Color.rgb(225, 175, 140), Color.rgb(120, 80, 50),
        )
        val pw = w.toFloat() / patches.size
        patches.forEachIndexed { k, col ->
            p.color = col
            c.drawRect(RectF(k * pw, y0 + half + 4f, (k + 1) * pw, y0 + chartH.toFloat()), p)
        }
        p.style = Paint.Style.STROKE; p.color = Color.LTGRAY; p.strokeWidth = 1f
        c.drawRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, p)
        return b
    }
}
