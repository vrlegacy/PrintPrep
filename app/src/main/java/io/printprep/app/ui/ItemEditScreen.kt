package io.printprep.app.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.printprep.app.JobViewModel
import io.printprep.app.engine.LayoutEngine
import io.printprep.app.engine.SheetRenderer
import io.printprep.app.model.ItemEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ItemEditScreen(vm: JobViewModel, itemId: Long) {
    val item = vm.items.firstOrNull { it.id == itemId }
    if (item == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { TextButton(onClick = { vm.back() }) { Text("Done") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().navigationBarsPadding()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .aspectRatio(cellAspect, matchHeightConstraintsFirst = true)
                        .background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outline)
                        .pointerInput(itemId) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                edit {
                                    it.copy(
                                        zoom = (it.zoom * zoom).coerceIn(0.2f, 6f),
                                        offsetX = (it.offsetX + pan.x / size.width).coerceIn(-1f, 1f),
                                        offsetY = (it.offsetY + pan.y / size.height).coerceIn(-1f, 1f),
                                    )
                                }
                            }
                        },
                ) {
                    val b = src
                    if (b == null) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    } else {
                        Canvas(Modifier.fillMaxSize()) {
                            drawIntoCanvas { c ->
                                SheetRenderer.drawItem(
                                    c.nativeCanvas, b, RectF(0f, 0f, size.width, size.height),
                                    item.edit, s.fit, s.scalePercent, rotation, paint,
                                )
                            }
                        }
                    }
                }
            }
            Text(
                "Drag to move · pinch to zoom · this frame is the photo's place on the sheet",
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { edit { it.copy(rotation = (it.rotation + 270) % 360) } }) { Text("⟲ Rotate left") }
                    OutlinedButton(onClick = { edit { it.copy(rotation = (it.rotation + 90) % 360) } }) { Text("⟳ Rotate right") }
                    OutlinedButton(onClick = { edit { it.copy(flip = !it.flip) } }) { Text("Flip") }
                    OutlinedButton(onClick = { edit { ItemEdit(copies = it.copies) } }) { Text("Reset") }
                }
                Stepper("Copies of this item", item.edit.copies, 1, 99) { v -> edit { it.copy(copies = v) } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.move(itemId, -1) }, enabled = index > 0) { Text("Move earlier") }
                    OutlinedButton(onClick = { vm.move(itemId, 1) }, enabled = index < all.lastIndex) { Text("Move later") }
                    OutlinedButton(
                        onClick = { vm.remove(itemId) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Remove") }
                }
                Hint("Item ${index + 1} of ${all.size}. Fit / fill and colour are set for all items in the Print screen.")
            }
        }
    }
}
