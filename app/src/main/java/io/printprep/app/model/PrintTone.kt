package io.printprep.app.model

import android.content.Context
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Print-time tone matching. Ink on paper spreads ("dot gain"), so the same picture comes out darker
 * and punchier on paper than on screen, and the open-source driver doesn't compensate the way
 * Canon's own driver does. These steps lift the midtones (and optionally calm colours) only when
 * printing; the user picks the one that matches the original from a printed colour-match sheet.
 * [A] changes nothing. Previews, PDFs and saved images are never affected.
 */
enum class PrintTone(val letter: String, val label: String, val gamma: Float, val saturation: Float) {
    A("A", "No change", 1.00f, 1.00f),
    B("B", "Slightly lighter", 1.12f, 1.00f),
    C("C", "Lighter", 1.25f, 1.00f),
    D("D", "Much lighter", 1.40f, 1.00f),
    E("E", "Lighter, softer colour", 1.25f, 0.90f),
    F("F", "Much lighter, softer colour", 1.40f, 0.85f);

    val isIdentity: Boolean get() = gamma == 1f && saturation == 1f

    private val lut: IntArray by lazy {
        IntArray(256) { v -> (255.0 * (v / 255.0).pow(1.0 / gamma)).roundToInt().coerceIn(0, 255) }
    }
    private val satQ = (saturation * 256).roundToInt()

    /** Applies the tone to one ARGB pixel; returns RGB packed as 0xRRGGBB. */
    fun apply(argb: Int): Int {
        var r = (argb shr 16) and 0xFF
        var g = (argb shr 8) and 0xFF
        var b = argb and 0xFF
        if (satQ != 256) {
            val y = (r * 77 + g * 150 + b * 29) shr 8
            r = (y + (((r - y) * satQ) shr 8)).coerceIn(0, 255)
            g = (y + (((g - y) * satQ) shr 8)).coerceIn(0, 255)
            b = (y + (((b - y) * satQ) shr 8)).coerceIn(0, 255)
        }
        return (lut[r] shl 16) or (lut[g] shl 8) or lut[b]
    }

    companion object {
        private const val KEY = "printTone"

        fun saved(context: Context): PrintTone =
            context.getSharedPreferences("printprep", Context.MODE_PRIVATE).getString(KEY, null)
                ?.let { n -> entries.firstOrNull { it.name == n } } ?: A

        fun save(context: Context, t: PrintTone) {
            context.getSharedPreferences("printprep", Context.MODE_PRIVATE).edit().putString(KEY, t.name).apply()
        }
    }
}
