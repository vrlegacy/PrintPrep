package io.printprep.app.engine

import io.printprep.app.model.Duplex
import io.printprep.app.model.FixedSizePreset
import io.printprep.app.model.GridPreset
import io.printprep.app.model.JobItem
import io.printprep.app.model.PageOrientation
import io.printprep.app.model.PageSet
import io.printprep.app.model.PrintSettings
import kotlin.math.max
import kotlin.math.min

/** A rectangle on the sheet, in millimetres from the top-left corner. */
data class MmRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/** One item drawn in one cell. [rotation] already includes auto-rotate. */
data class Placement(val item: JobItem, val cell: MmRect, val rotation: Int)

/** One printed side of paper. [number] is 1-based in print order. */
data class Sheet(val number: Int, val paperW: Float, val paperH: Float, val placements: List<Placement>)

/**
 * Manual two-sided printing: [pass1] and [pass2] are sheet numbers in the order they are sent.
 * [keepAside] is the physical sheet (1-based) that has no back when the page count is odd.
 */
data class TwoSidedPlan(
    val pass1: List<Int>,
    val pass2: List<Int>,
    val keepAside: Int?,
    val rotateBacks: Boolean,
)

data class PrintPlan(
    val sheets: List<Sheet>,
    val landscape: Boolean,
    val cellsPerSheet: Int,
    val selectedCount: Int,
    val rangeError: String?,
    val twoSided: TwoSidedPlan?,
) {
    /** Sheets of paper used. */
    val paperCount: Int get() = if (twoSided != null) (sheets.size + 1) / 2 else sheets.size
}

/** Pure layout maths: no Android drawing here, so it is easy to test. */
object LayoutEngine {

    fun plan(items: List<JobItem>, s: PrintSettings): PrintPlan {
        val (selected, rangeError) = select(items, s)
        val landscape = chooseLandscape(selected.ifEmpty { items }, s)
        val paperW = if (landscape) s.paper.heightMm else s.paper.widthMm
        val paperH = if (landscape) s.paper.widthMm else s.paper.heightMm
        val cells = cells(s, landscape)
        if (cells.isEmpty() || selected.isEmpty()) {
            return PrintPlan(emptyList(), landscape, cells.size, selected.size, rangeError, null)
        }

        // 1. each item repeated by its own copies
        var slots = selected.flatMap { item -> List(item.edit.copies.coerceAtLeast(1)) { item } }
        // 2. one photo repeated to fill the whole sheet
        if (s.repeatToFill && selected.size == 1) slots = List(cells.size) { selected[0] }

        // 3. into sheets
        val once = slots.chunked(cells.size).map { chunk ->
            chunk.mapIndexed { i, item -> Placement(item, cells[i], effectiveRotation(item, cells[i], s)) }
        }

        // 4. job copies, collated or not
        val copies = s.copies.coerceIn(1, 99)
        val ordered = when {
            copies == 1 -> once
            s.collate -> List(copies) { once }.flatten()
            else -> once.flatMap { sheet -> List(copies) { sheet } }
        }
        val sheets = ordered.mapIndexed { i, p -> Sheet(i + 1, paperW, paperH, p) }

        val twoSided = if (s.duplex == Duplex.OFF) null else twoSided(sheets.size, s)
        return PrintPlan(sheets, landscape, cells.size, selected.size, rangeError, twoSided)
    }

    fun twoSided(sheetCount: Int, s: PrintSettings): TwoSidedPlan {
        val fronts = (1..sheetCount step 2).toList()
        val backs = (2..sheetCount step 2).toList()
        return TwoSidedPlan(
            pass1 = fronts,
            pass2 = if (s.reverseBackOrder) backs.reversed() else backs,
            keepAside = if (sheetCount % 2 == 1) (sheetCount + 1) / 2 else null,
            rotateBacks = (s.duplex == Duplex.SHORT_EDGE) != s.rotateBack180,
        )
    }

    /** Filters items by the page set / custom range. Numbers refer to the order in the job. */
    fun select(items: List<JobItem>, s: PrintSettings): Pair<List<JobItem>, String?> = when (s.pageSet) {
        PageSet.ALL -> items to null
        PageSet.ODD -> items.filterIndexed { i, _ -> i % 2 == 0 } to null
        PageSet.EVEN -> items.filterIndexed { i, _ -> i % 2 == 1 } to null
        PageSet.CUSTOM -> {
            val r = parseRange(s.pageRange, items.size)
            r.fold(
                onSuccess = { set -> items.filterIndexed { i, _ -> (i + 1) in set } to null },
                onFailure = { e -> items to (e.message ?: "Invalid page range") },
            )
        }
    }

    /** Parses "1-5, 8, 11-13". Fails with a readable message. */
    fun parseRange(text: String, max: Int): Result<Set<Int>> = runCatching {
        if (text.isBlank()) error("Enter pages, e.g. 1-5, 8")
        val part = Regex("""^(\d+)\s*(?:-\s*(\d+))?$""")
        text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { p ->
            val m = part.matchEntire(p) ?: error("\"$p\" isn't a page or a range")
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].ifEmpty { null }?.toInt() ?: a
            if (a < 1 || b < a) error("\"$p\" isn't a valid range")
            if (a > max) error("Page $a doesn't exist (there are $max)")
            (a..min(b, max)).toList()
        }.toSet()
    }

    /** Cell rectangles for one sheet. */
    fun cells(s: PrintSettings, landscape: Boolean): List<MmRect> {
        val pw = if (landscape) s.paper.heightMm else s.paper.widthMm
        val ph = if (landscape) s.paper.widthMm else s.paper.heightMm
        val m = s.marginMm.coerceIn(0f, min(pw, ph) / 4f)
        val uw = pw - 2 * m
        val uh = ph - 2 * m

        return when (val l = s.layout) {
            is GridPreset -> {
                val cols = if (landscape) l.rows else l.cols
                val rows = if (landscape) l.cols else l.rows
                val gap = if (l.perSheet == 1) 0f else s.gapMm.coerceAtLeast(0f)
                val cw = (uw - gap * (cols - 1)) / cols
                val ch = (uh - gap * (rows - 1)) / rows
                if (cw <= 1f || ch <= 1f) return emptyList()
                buildList {
                    for (r in 0 until rows) for (c in 0 until cols) {
                        add(MmRect(m + c * (cw + gap), m + r * (ch + gap), cw, ch))
                    }
                }
            }
            is FixedSizePreset -> {
                val gap = s.gapMm.coerceAtLeast(0f)
                val cw = min(l.cellWidthMm, uw)
                val ch = min(l.cellHeightMm, uh)
                val cols = max(1, ((uw + gap) / (cw + gap)).toInt())
                val rows = max(1, ((uh + gap) / (ch + gap)).toInt())
                val ox = m + (uw - (cols * cw + (cols - 1) * gap)) / 2f
                val oy = m + (uh - (rows * ch + (rows - 1) * gap)) / 2f
                buildList {
                    for (r in 0 until rows) for (c in 0 until cols) {
                        add(MmRect(ox + c * (cw + gap), oy + r * (ch + gap), cw, ch))
                    }
                }
            }
        }
    }

    fun chooseLandscape(items: List<JobItem>, s: PrintSettings): Boolean = when (s.orientation) {
        PageOrientation.PORTRAIT -> false
        PageOrientation.LANDSCAPE -> true
        PageOrientation.AUTO -> when {
            s.layout is FixedSizePreset -> cells(s, true).size > cells(s, false).size
            items.isEmpty() -> false
            else -> {
                val sample = items.take(64)
                score(sample, s, true) > score(sample, s, false) + 0.01f
            }
        }
    }

    /** Average share of each cell covered by its item when fitted: higher is better. */
    private fun score(items: List<JobItem>, s: PrintSettings, landscape: Boolean): Float {
        val cell = cells(s, landscape).firstOrNull() ?: return 0f
        return items.map { coverage(it, cell, effectiveRotation(it, cell, s)) }.average().toFloat()
    }

    private fun coverage(item: JobItem, cell: MmRect, rotation: Int): Float {
        val (w, h) = rotatedSize(item, rotation)
        if (w <= 0f || h <= 0f) return 0f
        val k = min(cell.width / w, cell.height / h)
        return (w * k * h * k) / (cell.width * cell.height)
    }

    fun rotatedSize(item: JobItem, rotation: Int): Pair<Float, Float> =
        if (rotation % 180 != 0) item.heightPx.toFloat() to item.widthPx.toFloat()
        else item.widthPx.toFloat() to item.heightPx.toFloat()

    /** User rotation, plus 90° when auto-rotate is on and the item's shape doesn't match the cell's. */
    fun effectiveRotation(item: JobItem, cell: MmRect, s: PrintSettings): Int {
        var r = ((item.edit.rotation % 360) + 360) % 360
        if (s.autoRotate) {
            val (w, h) = rotatedSize(item, r)
            val itemLandscape = w > h * 1.02f
            val itemPortrait = h > w * 1.02f
            val cellLandscape = cell.width > cell.height * 1.02f
            val cellPortrait = cell.height > cell.width * 1.02f
            if ((itemLandscape && cellPortrait) || (itemPortrait && cellLandscape)) r = (r + 90) % 360
        }
        return r
    }
}
