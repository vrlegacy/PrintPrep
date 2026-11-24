package io.printprep.app.model

import android.content.Context

private const val PREFS = "printprep"

/**
 * Which Gutenprint printer model drives the Canon over USB. Gutenprint has no exact "G2010" entry,
 * so the app borrows a nearby one. They are all compiled into the native library and share the same
 * bundled data, so switching is a pure runtime choice — no rebuild, no extra assets.
 *
 * The colour tables differ between these models, so the one that gives the truest colour on a
 * particular G2010 is found by printing the driver-test sheet and comparing (see TestPages).
 */
enum class CanonDriver(val id: String, val label: String, val note: String) {
    /** The G3010 is a G2010 with Wi-Fi added — same print head and inks, so its profile should fit best. */
    G3010("bjc-PIXMA-G3010", "G3010 engine", "Same print engine as the G2010 — recommended"),
    G2000("bjc-PIXMA-G2000", "G2000", "Canon PIXMA G2000 profile"),
    G2002("bjc-PIXMA-G2002", "G2002", "Canon PIXMA G2002 profile"),
    SERIES_2000("bjc-G2000-series", "G2000 series (generic)", "The original default"),
    SERIES_3000("bjc-G3000-series", "G3000 series (generic)", "Generic G3000 line");

    companion object {
        /** Keep the long-standing default so prints are unchanged until the user calibrates. */
        val DEFAULT = SERIES_2000
        private const val KEY = "canonDriver"

        fun saved(context: Context): CanonDriver =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?.let { n -> entries.firstOrNull { it.name == n } } ?: DEFAULT

        fun save(context: Context, d: CanonDriver) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, d.name).apply()
        }
    }
}

/**
 * Gutenprint's RGB→CMYK colour-correction mode. The app never set this before, so it ran at the
 * driver's default; wrong hues are usually fixed here. [id] is the exact Gutenprint option value.
 */
enum class ColorCorrect(val id: String, val label: String) {
    STANDARD("None", "Standard"),
    ACCURATE("Accurate", "Accurate"),
    BRIGHT("Bright", "Bright / vivid"),
    HUE("Hue", "Hue only"),
    UNCORRECTED("Uncorrected", "Uncorrected");

    companion object {
        val DEFAULT = STANDARD
        private const val KEY = "colorCorrect"

        fun saved(context: Context): ColorCorrect =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?.let { n -> entries.firstOrNull { it.name == n } } ?: DEFAULT

        fun save(context: Context, c: ColorCorrect) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, c.name).apply()
        }
    }
}
