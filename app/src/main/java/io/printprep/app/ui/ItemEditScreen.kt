package io.printprep.app.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.printprep.app.JobViewModel
import io.printprep.app.engine.LayoutEngine
import io.printprep.app.engine.SheetRenderer
import io.printprep.app.model.FitMode
import io.printprep.app.model.ItemEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private val CancelRed = Color(0xFFD32F2F)
private val ResetBlue = Color(0xFF1E6FD9)
private val DoneGreen = Color(0xFF2E7D32)

private enum class EditMode(val label: String) { POSITION("Position"), CROP("Crop") }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ItemEditScreen(vm: JobViewModel, itemId: Long) {
    val item = vm.items.firstOrNull { it.id == itemId }
    if (item == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    // The edit as it was when the editor opened: Cancel restores it.
    val initialEdit = remember(itemId) { item.edit }
    var mode by rememberSaveable { mutableStateOf(EditMode.POSITION) }

    val s = vm.settings
    val all = vm.items.toList()
    val plan = remember(all, s) { LayoutEngine.plan(all, s) }
    val placement = plan.sheets.firstNotNullOfOrNull { sh -> sh.placements.firstOrNull { it.item.id == itemId } }
    val cell = placement?.cell
    val cellAspect = if (cell != null) cell.width / cell.height else 0.7f
    val rotation = placement?.rotation ?: item.edit.rotation
    val index = all.indexOfFirst { it.id == itemId }

    val src by produceState<Bitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) { runCatching { vm.store.decode(item, 1600, useCache = true) }.getOrNull() }
    }
    val paint = remember(s.color, s.brightness, s.contrast) {
        Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { colorFilter = SheetRenderer.colorFilter(s) }
    }

    fun edit(change: (ItemEdit) -> ItemEdit) {
        val current = vm.items.firstOrNull { it.id == itemId }?.edit ?: return
        vm.updateEdit(itemId, change(current))
    }

    // Store the snapped angle once the editor closes, so the sheet, PDF and print match what was shown.
    androidx.compose.runtime.DisposableEffect(itemId) {
        onDispose {
            vm.items.firstOrNull { it.id == itemId }?.edit?.let { e ->
                val snapped = snapAngle(e.angle)
                if (snapped != e.angle) vm.updateEdit(itemId, e.copy(angle = snapped))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ColorButton("Cancel", CancelRed, Modifier.weight(1f)) {
                        vm.updateEdit(itemId, initialEdit)
                        vm.back()
                    }
                    ColorButton("Reset", ResetBlue, Modifier.weight(1f)) {
                        edit { ItemEdit(copies = it.copies) }
                    }
                    ColorButton("Done", DoneGreen, Modifier.weight(1f)) { vm.back() }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // ---------------- picture area
            Box(Modifier.weight(1f).fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                val b = src
                when {
                    b == null -> CircularProgressIndicator()
                    mode == EditMode.CROP -> CropView(b, item.edit, rotation, paint) { newEdit -> vm.updateEdit(itemId, newEdit) }
                    else -> PositionView(b, item.edit, cellAspect, rotation, s.fit, s.scalePercent, paint, itemId) { change -> edit(change) }
                }
            }
            Text(
                if (mode == EditMode.CROP) "Drag the corners or edges to crop · drag inside to move the crop"
                else "Drag to move · pinch to zoom · twist to turn 90° · double-tap to reset",
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )

            // ---------------- tools (compact)
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // mode + crop reset on one line
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditMode.entries.forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) })
                    }
                    Spacer(Modifier.weight(1f))
                    if (item.edit.isCropped) {
                        TextButton(onClick = { edit { it.copy(cropLeft = 0f, cropTop = 0f, cropRight = 1f, cropBottom = 1f) } }) {
                            Text("Clear crop")
                        }
                    }
                }
                // rotation: four positions only
                // the angle as it appears (including any auto-rotate), so each stop turns the photo exactly that way
                val quarter = ((rotation % 360 + 360) % 360) / 90
                SliderRow("Rotate", quarter.toFloat(), 0f..3f, "${quarter * 90}°", steps = 2) { v ->
                    val q = Math.round(v).coerceIn(0, 3)
                    if (q != quarter) edit { it.copy(rotation = q * 90, manualRotation = true, angle = 0f, offsetX = 0f, offsetY = 0f) }
                }
                // copies + delete on one line
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        Stepper("Copies", item.edit.copies, 1, 99) { v -> edit { it.copy(copies = v) } }
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = { vm.remove(itemId) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete") }
                }
                Hint("Item ${index + 1} of ${all.size} · Cancel undoes changes made here · Reset clears rotation, crop, zoom and position")
            }
        }
    }
}

@Composable
private fun ColorButton(text: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

/** The item inside its frame, as it will print: drag to move, pinch to zoom. */
@Composable
private fun PositionView(
    bmp: Bitmap,
    edit: ItemEdit,
    cellAspect: Float,
    rotation: Int,
    fit: FitMode,
    scalePercent: Int,
    paint: Paint,
    itemId: Long,
    onChange: ((ItemEdit) -> ItemEdit) -> Unit,
) {
    Box(
        Modifier
            .aspectRatio(cellAspect, matchHeightConstraintsFirst = true)
            .background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .pointerInput(itemId) {
                // one finger: move · two fingers: pinch to zoom; a twist past 45° turns the photo a quarter turn
                var twisted = 0f
                detectTransformGestures { _, pan, zoom, twist ->
                    twisted += twist
                    val step = when {
                        twisted >= 45f -> 90
                        twisted <= -45f -> 270
                        else -> 0
                    }
                    if (step != 0) twisted = 0f
                    onChange {
                        it.copy(
                            zoom = (it.zoom * zoom).coerceIn(0.2f, 6f),
                            offsetX = if (step != 0) 0f else (it.offsetX + pan.x / size.width).coerceIn(-1f, 1f),
                            offsetY = if (step != 0) 0f else (it.offsetY + pan.y / size.height).coerceIn(-1f, 1f),
                            rotation = if (step != 0) (rotation + step) % 360 else it.rotation,
                            manualRotation = it.manualRotation || step != 0,
                            angle = 0f,
                        )
                    }
                }
            }
            .pointerInput(itemId) {
                // double-tap: back to the default size, position and angle
                detectTapGestures(onDoubleTap = {
                    onChange { it.copy(zoom = 1f, offsetX = 0f, offsetY = 0f, angle = 0f) }
                })
            },
    ) {
        val shown = edit.copy(angle = snapAngle(edit.angle))
        Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { c ->
                SheetRenderer.drawItem(c.nativeCanvas, bmp, RectF(0f, 0f, size.width, size.height), shown, fit, scalePercent, rotation, paint)
            }
        }
        if (edit.zoom != 1f) {
            Text(
                "%.0f%%".format(edit.zoom * 100),
                Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * Keeps a hand-rotated angle in -180..180 and snaps it to straight (0°, ±90°, 180°)
 * when it's within 3°, so photos are easy to get perfectly level.
 */
fun snapAngle(a: Float): Float {
    var x = ((a % 360f) + 540f) % 360f - 180f
    val nearest = Math.round(x / 90f) * 90f
    if (abs(x - nearest) < 3f) x = nearest
    return if (x == -180f) 180f else x
}

private enum class Handle { TL, TR, BL, BR, LEFT, RIGHT, TOP, BOTTOM, MOVE }

/** Normalised rectangle (0..1). */
private data class NRect(val l: Float, val t: Float, val r: Float, val b: Float)

/**
 * Whole photo (with the user's rotation) and a crop box. The box is shown in screen orientation and
 * stored in the upright image's own coordinates, so the crop survives later rotations.
 */
@Composable
private fun CropView(bmp: Bitmap, edit: ItemEdit, rotation: Int, paint: Paint, onChange: (ItemEdit) -> Unit) {
    val rot = ((rotation % 360) + 360) % 360
    val turned = rot % 180 != 0
    val aspect = if (turned) bmp.height.toFloat() / bmp.width else bmp.width.toFloat() / bmp.height
    val current by rememberUpdatedState(edit)
    var handle by remember { mutableStateOf(Handle.MOVE) }

    Box(
        Modifier
            .aspectRatio(aspect, matchHeightConstraintsFirst = true)
            .background(Color.Black)
            .pointerInput(rot) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val r = toDisplay(current, rot)
                        handle = hitHandle(pos, r, size.width.toFloat(), size.height.toFloat(), 28.dp.toPx())
                    },
                ) { change, drag ->
                    change.consume()
                    val dx = drag.x / size.width
                    val dy = drag.y / size.height
                    val moved = moveRect(toDisplay(current, rot), handle, dx, dy)
                    onChange(fromDisplay(current, moved, rot))
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // whole photo, uncropped, with the user's rotation (shown unflipped so the box maps 1:1)
            val full = edit.copy(cropLeft = 0f, cropTop = 0f, cropRight = 1f, cropBottom = 1f, zoom = 1f, offsetX = 0f, offsetY = 0f, flip = false)
            drawIntoCanvas { c ->
                SheetRenderer.drawItem(c.nativeCanvas, bmp, RectF(0f, 0f, size.width, size.height), full, FitMode.FIT, 100, rot, paint)
            }
            val r = toDisplay(edit, rot)
            val l = r.l * size.width
            val t = r.t * size.height
            val rr = r.r * size.width
            val bb = r.b * size.height
            val shade = Color(0x99000000)
            drawRect(shade, Offset(0f, 0f), Size(size.width, t))
            drawRect(shade, Offset(0f, bb), Size(size.width, size.height - bb))
            drawRect(shade, Offset(0f, t), Size(l, bb - t))
            drawRect(shade, Offset(rr, t), Size(size.width - rr, bb - t))
            drawRect(Color.White, Offset(l, t), Size(rr - l, bb - t), style = Stroke(2.dp.toPx()))
            // rule-of-thirds guides
            for (i in 1..2) {
                val x = l + (rr - l) * i / 3f
                val y = t + (bb - t) * i / 3f
                drawLine(Color(0x88FFFFFF), Offset(x, t), Offset(x, bb), 1.dp.toPx())
                drawLine(Color(0x88FFFFFF), Offset(l, y), Offset(rr, y), 1.dp.toPx())
            }
            val hs = 10.dp.toPx()
            for (p in listOf(Offset(l, t), Offset(rr, t), Offset(l, bb), Offset(rr, bb))) {
                drawRect(Color.White, Offset(p.x - hs / 2, p.y - hs / 2), Size(hs, hs))
            }
        }
    }
}

private const val MIN_CROP = 0.05f

private fun hitHandle(pos: Offset, r: NRect, w: Float, h: Float, slop: Float): Handle {
    val l = r.l * w; val t = r.t * h; val rr = r.r * w; val b = r.b * h
    val corners = listOf(Handle.TL to Offset(l, t), Handle.TR to Offset(rr, t), Handle.BL to Offset(l, b), Handle.BR to Offset(rr, b))
    corners.minByOrNull { (_, p) -> hypot(p.x - pos.x, p.y - pos.y) }?.let { (hd, p) ->
        if (hypot(p.x - pos.x, p.y - pos.y) <= slop * 1.6f) return hd
    }
    val insideY = pos.y in t..b
    val insideX = pos.x in l..rr
    return when {
        insideY && abs(pos.x - l) <= slop -> Handle.LEFT
        insideY && abs(pos.x - rr) <= slop -> Handle.RIGHT
        insideX && abs(pos.y - t) <= slop -> Handle.TOP
        insideX && abs(pos.y - b) <= slop -> Handle.BOTTOM
        else -> Handle.MOVE
    }
}

private fun moveRect(r: NRect, h: Handle, dx: Float, dy: Float): NRect {
    var (l, t, rr, b) = r
    when (h) {
        Handle.TL -> { l += dx; t += dy }
        Handle.TR -> { rr += dx; t += dy }
        Handle.BL -> { l += dx; b += dy }
        Handle.BR -> { rr += dx; b += dy }
        Handle.LEFT -> l += dx
        Handle.RIGHT -> rr += dx
        Handle.TOP -> t += dy
        Handle.BOTTOM -> b += dy
        Handle.MOVE -> {
            val mx = dx.coerceIn(-l, 1f - rr)
            val my = dy.coerceIn(-t, 1f - b)
            return NRect(l + mx, t + my, rr + mx, b + my)
        }
    }
    l = l.coerceIn(0f, rr - MIN_CROP)
    t = t.coerceIn(0f, b - MIN_CROP)
    rr = rr.coerceIn(l + MIN_CROP, 1f)
    b = b.coerceIn(t + MIN_CROP, 1f)
    return NRect(l, t, rr, b)
}

// Image (upright, unrotated) ↔ display (rotated by the user's rotation, clockwise).
private fun imageToDisplay(x: Float, y: Float, rot: Int): Pair<Float, Float> = when (rot) {
    90 -> (1f - y) to x
    180 -> (1f - x) to (1f - y)
    270 -> y to (1f - x)
    else -> x to y
}

private fun displayToImage(x: Float, y: Float, rot: Int): Pair<Float, Float> = when (rot) {
    90 -> y to (1f - x)
    180 -> (1f - x) to (1f - y)
    270 -> (1f - y) to x
    else -> x to y
}

private fun toDisplay(e: ItemEdit, rot: Int): NRect {
    val a = imageToDisplay(e.cropLeft, e.cropTop, rot)
    val b = imageToDisplay(e.cropRight, e.cropBottom, rot)
    return NRect(min(a.first, b.first), min(a.second, b.second), max(a.first, b.first), max(a.second, b.second))
}

private fun fromDisplay(e: ItemEdit, r: NRect, rot: Int): ItemEdit {
    val a = displayToImage(r.l, r.t, rot)
    val b = displayToImage(r.r, r.b, rot)
    return e.copy(
        cropLeft = min(a.first, b.first), cropTop = min(a.second, b.second),
        cropRight = max(a.first, b.first), cropBottom = max(a.second, b.second),
    )
}
