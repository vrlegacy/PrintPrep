package io.printprep.app.print

import io.printprep.app.model.ColorMode
import io.printprep.app.model.MarginPreset
import io.printprep.app.model.PaperSize
import io.printprep.app.model.PaperType
import io.printprep.app.model.PrintSettings
import io.printprep.app.model.Quality

/**
 * Maps the app's settings onto the Gutenprint "bjc-G2000-series" options. The option names and
 * values come from probing the driver in the GitHub build (see native/probe/probe.c).
 */
object DriverSettings {

    fun options(s: PrintSettings): List<Pair<String, String>> {
        val photoPaper = s.paperType != PaperType.PLAIN
        val blackInk = s.color == ColorMode.BLACK_ONLY || s.color == ColorMode.BW_DOCUMENT
        val media = when (s.paperType) {
            PaperType.PLAIN -> "Plain"
            PaperType.GLOSSY -> "PhotoPlusGloss2"
            PaperType.MATTE -> "PhotopaperMatte"
            PaperType.OTHER_PHOTO -> "PhotopaperOther"
        }
        val resolution = when (s.quality) {
            Quality.INK_SAVER -> "300x300dpi"
            Quality.STANDARD -> "600x600dpi"
            Quality.HIGH -> "600x600dpi_high"
            Quality.PHOTO -> if (photoPaper) "600x600dpi_photo" else "600x600dpi_high"
            Quality.BEST_PHOTO -> when {
                s.paperType == PaperType.GLOSSY -> "600x600dpi_photohigh2"
                photoPaper -> "600x600dpi_photohigh"
                else -> "600x600dpi_high"
            }
        }
        return buildList {
            add("PageSize" to pageSize(s.paper))
            add("MediaType" to media)
            add("InputSlot" to "Rear")
            add("Quality" to "None") // "Manual control", so our Resolution choice is used
            add("Resolution" to resolution)
            add("PrintingMode" to if (blackInk) "BW" else "Color")
            add("InkType" to if (blackInk) "Gray" else "CMYK")
            add("InputImageType" to "RGB")
            add("ChannelBitDepth" to "8")
            add("JobMode" to "Page")
            add("FullBleed" to if (borderless(s)) "true" else "false")
        }
    }

    /** Borderless only on photo paper with no margins; plain paper keeps the printer's minimum edge. */
    fun borderless(s: PrintSettings) = s.margin == MarginPreset.NONE && s.paperType != PaperType.PLAIN

    fun pageSize(p: PaperSize) = when (p) {
        PaperSize.A4 -> "A4"
        PaperSize.PHOTO_4X6 -> "w288h432"
    }

    /** Pixels per inch of the page image we hand the driver. */
    fun imageDpi(s: PrintSettings) = if (s.quality == Quality.INK_SAVER) 300 else 600
}
