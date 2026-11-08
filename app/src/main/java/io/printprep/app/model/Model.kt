package io.printprep.app.model

/** Paper sizes the app can lay out on. Sizes are portrait, in millimetres. */
enum class PaperSize(val label: String, val widthMm: Float, val heightMm: Float) {
    A4("A4", 210f, 297f),
    PHOTO_4X6("4×6 in (10×15 cm)", 101.6f, 152.4f),
}

enum class PaperType(val label: String) {
    PLAIN("Plain"),
    GLOSSY("Glossy photo"),
    MATTE("Matte photo"),
    OTHER_PHOTO("Other photo"),
}

/** Print quality. Stored now; mapped to printer-driver settings in Phase 2. */
enum class Quality(val label: String, val inkHint: String) {
    INK_SAVER("Ink saver", "Low"),
    STANDARD("Standard", "Normal"),
    HIGH("High", "High"),
    PHOTO("Photo", "High"),
    BEST_PHOTO("Best photo", "Highest"),
}

enum class ColorMode(val label: String) {
    COLOR("Colour"),
    GRAYSCALE("Grayscale"),
    BLACK_ONLY("Black ink only"),
    BW_DOCUMENT("B&W document"),
}

enum class FitMode(val label: String) {
    FIT("Fit to frame"),
    FILL("Fill frame (crop)"),
}

enum class PageOrientation(val label: String) {
    AUTO("Auto"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
}

enum class MarginPreset(val label: String, val mm: Float) {
    DEFAULT("Default (5 mm)", 5f),
    MINIMUM("Minimum (3 mm)", 3f),
    NONE("None (borderless)", 0f),
    CUSTOM("Custom", 5f),
}

enum class Duplex(val label: String) {
    OFF("Off"),
    LONG_EDGE("Manual – flip on long edge"),
    SHORT_EDGE("Manual – flip on short edge"),
}

enum class PageSet(val label: String) {
    ALL("All"),
    ODD("Odd only"),
    EVEN("Even only"),
    CUSTOM("Custom"),
}

/** How items are arranged on a sheet. */
sealed interface LayoutPreset {
    val label: String
}

/** A grid. [cols] × [rows] are for a portrait sheet; landscape swaps them. */
data class GridPreset(val perSheet: Int, val cols: Int, val rows: Int) : LayoutPreset {
    override val label: String get() = perSheet.toString()
}

/** Fixed-size cells (e.g. passport photos), as many as fit on the sheet. */
data class FixedSizePreset(val name: String, val cellWidthMm: Float, val cellHeightMm: Float) : LayoutPreset {
    override val label: String get() = name
}

object Presets {
    val all: List<LayoutPreset> = listOf(
        GridPreset(1, 1, 1),
        GridPreset(2, 1, 2),
        GridPreset(4, 2, 2),
        GridPreset(6, 2, 3),
        GridPreset(8, 2, 4),
        GridPreset(9, 3, 3),
        GridPreset(12, 3, 4),
        GridPreset(16, 4, 4),
        GridPreset(20, 4, 5),
        GridPreset(32, 4, 8),
        FixedSizePreset("Passport 35×45 mm", 35f, 45f),
        FixedSizePreset("Stamp 25×30 mm", 25f, 30f),
    )
    val single: LayoutPreset get() = all.first()
}

data class PrintSettings(
    // Layout
    val layout: LayoutPreset = Presets.single,
    val orientation: PageOrientation = PageOrientation.AUTO,
    val repeatToFill: Boolean = false,
    val gapMm: Float = 3f,
    val border: Boolean = false,
    val cutLines: Boolean = false,
    // Fit
    val fit: FitMode = FitMode.FIT,
    val autoRotate: Boolean = true,
    val scalePercent: Int = 100,
    val margin: MarginPreset = MarginPreset.NONE,
    val customMarginMm: Float = 5f,
    // Paper & quality
    val paper: PaperSize = PaperSize.A4,
    val paperType: PaperType = PaperType.PLAIN,
    val quality: Quality = Quality.STANDARD,
    // Colour
    val color: ColorMode = ColorMode.COLOR,
    val brightness: Float = 0f, // -0.5 .. 0.5
    val contrast: Float = 1f,   // 0.5 .. 2
    // Pages
    val pageSet: PageSet = PageSet.ALL,
    val pageRange: String = "",
    val copies: Int = 1,
    val collate: Boolean = true,
    /** The G2010 stacks pages face up, so printing the last page first leaves page 1 on top. */
    val lastPageFirst: Boolean = true,
    val duplex: Duplex = Duplex.OFF,
    val reverseBackOrder: Boolean = false,
    val rotateBack180: Boolean = false,
) {
    val marginMm: Float get() = if (margin == MarginPreset.CUSTOM) customMarginMm else margin.mm

    /** Colour mode with print quality following it: Ink saver for grayscale, Standard (or Photo on photo paper) for colour. */
    fun withColor(c: ColorMode): PrintSettings = copy(
        color = c,
        quality = when {
            c != ColorMode.COLOR -> Quality.INK_SAVER
            paperType != PaperType.PLAIN -> Quality.PHOTO
            else -> Quality.STANDARD
        },
    )
}

enum class ItemKind { IMAGE, PDF_PAGE, WEB_PAGE }

/**
 * Per-item edits made in the item editor. Offsets are fractions of the cell size.
 * The crop rectangle is in the upright image's own coordinates (0..1, before rotation).
 */
data class ItemEdit(
    val rotation: Int = 0,
    val flip: Boolean = false,
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val copies: Int = 1,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    /** Free rotation by hand, in degrees (-180..180), on top of the 90° steps in [rotation]. */
    val angle: Float = 0f,
) {
    val cropWidth: Float get() = (cropRight - cropLeft).coerceIn(0.02f, 1f)
    val cropHeight: Float get() = (cropBottom - cropTop).coerceIn(0.02f, 1f)
    val isCropped: Boolean get() = cropLeft > 0.001f || cropTop > 0.001f || cropRight < 0.999f || cropBottom < 0.999f
}

/** One printable thing: a photo, one PDF page or one slice of a web page. */
data class JobItem(
    val id: Long,
    val file: String,
    val kind: ItemKind,
    val pageIndex: Int,
    val widthPx: Int,
    val heightPx: Int,
    val name: String,
    val edit: ItemEdit = ItemEdit(),
)
