package io.printprep.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.printprep.app.JobViewModel
import io.printprep.app.engine.LayoutEngine
import io.printprep.app.engine.PrintPlan
import io.printprep.app.engine.Sheet
import io.printprep.app.model.ColorMode
import io.printprep.app.model.Duplex
import io.printprep.app.model.FitMode
import io.printprep.app.model.FixedSizePreset
import io.printprep.app.model.JobItem
import io.printprep.app.model.MarginPreset
import io.printprep.app.model.PageOrientation
import io.printprep.app.model.PageSet
import io.printprep.app.model.PaperSize
import io.printprep.app.model.PaperType
import io.printprep.app.model.Presets
import io.printprep.app.model.PrintSettings
import io.printprep.app.model.Quality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val TABS = listOf("Quick", "Layout", "Fit", "Paper & quality", "Colour", "Pages")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: JobViewModel) {
    val items = vm.items.toList()
    val s = vm.settings
    val plan = remember(items, s) { LayoutEngine.plan(items, s) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showOutput by remember { mutableStateOf(false) }
    val addFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.import(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Print")
                        Text(summary(plan, items.size), style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { addFiles.launch(arrayOf("image/*", "application/pdf")) }) {
                        Icon(Icons.Filled.Add, "Add photos or PDFs")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = { showOutput = true },
                    enabled = plan.sheets.isNotEmpty() && !vm.importing,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                ) { Text("Save or print · ${plan.paperCount} sheet(s) of paper") }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            SheetPager(vm, plan, Modifier.weight(0.42f).fillMaxWidth())
            ItemStrip(vm, items)
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                TABS.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            Column(
                Modifier.weight(0.58f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (tab) {
                    0 -> QuickPanel(vm, s, plan)
                    1 -> LayoutPanel(vm, s)
                    2 -> FitPanel(vm, s)
                    3 -> PaperPanel(vm, s)
                    4 -> ColourPanel(vm, s)
                    else -> PagesPanel(vm, s, plan)
                }
            }
        }
    }
    if (showOutput) OutputSheet(vm, plan) { showOutput = false }
}

private fun summary(plan: PrintPlan, itemCount: Int): String {
    val pages = "${plan.sheets.size} page(s)"
    val paper = if (plan.twoSided != null) " on ${plan.paperCount} sheet(s), two-sided" else ""
    return "$itemCount item(s) · $pages$paper · ${if (plan.landscape) "landscape" else "portrait"}"
}

// ---------------------------------------------------------------- preview

@Composable
private fun SheetPager(vm: JobViewModel, plan: PrintPlan, modifier: Modifier) {
    if (plan.sheets.isEmpty()) {
        Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                when {
                    vm.importing -> "Opening files…"
                    vm.items.isEmpty() -> "Nothing to print yet. Tap + to add photos or PDFs."
                    else -> plan.rangeError ?: "No pages selected."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val pager = rememberPagerState { plan.sheets.size }
    Column(modifier) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 8.dp),
            pageSpacing = 16.dp,
        ) { page ->
            val sheet = plan.sheets.getOrNull(page) ?: return@HorizontalPager
            SheetPreview(vm, sheet, vm.settings) { vm.openItem(it) }
        }
        val current = (pager.currentPage + 1).coerceAtMost(plan.sheets.size)
        Text(
            "Page $current of ${plan.sheets.size} · tap a photo to edit it",
            Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun SheetPreview(vm: JobViewModel, sheet: Sheet, s: PrintSettings, onTapItem: (Long) -> Unit) {
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val bitmap by produceState<Bitmap?>(null, sheet, s, sizePx) {
        if (sizePx.width > 0) {
            value = runCatching {
                withContext(Dispatchers.Default) { vm.renderPreview(sheet, s, sizePx.width) }
            }.getOrNull()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .aspectRatio(sheet.paperW / sheet.paperH, matchHeightConstraintsFirst = true)
                .shadow(4.dp)
                .background(Color.White)
                .onSizeChanged { sizePx = it }
                .pointerInput(sheet) {
                    detectTapGestures { o ->
                        val k = size.width / sheet.paperW
                        sheet.placements.firstOrNull { it.cell.contains(o.x / k, o.y / k) }?.let { onTapItem(it.item.id) }
                    }
                },
        ) {
            val b = bitmap
            if (b != null) {
                Image(b.asImageBitmap(), contentDescription = "Page ${sheet.number}", modifier = Modifier.fillMaxSize())
            } else {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp))
            }
        }
    }
}

@Composable
private fun ItemStrip(vm: JobViewModel, jobItems: List<JobItem>) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(jobItems, key = { it.id }) { item ->
            Thumb(vm, item, jobItems.indexOf(item) + 1)
        }
    }
}

@Composable
private fun Thumb(vm: JobViewModel, item: JobItem, number: Int) {
    val bmp by produceState<Bitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) { runCatching { vm.store.decode(item, 256, useCache = true) }.getOrNull() }
    }
    Box(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { vm.openItem(item.id) },
    ) {
        bmp?.let {
            Image(it.asImageBitmap(), contentDescription = item.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Text(
            if (item.edit.copies > 1) "$number ×${item.edit.copies}" else "$number",
            Modifier
                .align(Alignment.BottomStart)
                .background(Color(0x99000000), RoundedCornerShape(topEnd = 6.dp))
                .padding(horizontal = 4.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}


// ---------------------------------------------------------------- quick tab (default)

/** The everyday settings in one place, with the two save buttons. */
@Composable
private fun QuickPanel(vm: JobViewModel, s: PrintSettings, plan: PrintPlan) {
    val colorChoice = if (s.color == ColorMode.COLOR) ColorMode.COLOR else ColorMode.GRAYSCALE
    ChoiceChips("Colour", listOf(ColorMode.COLOR, ColorMode.GRAYSCALE), colorChoice, { it.label }) { c -> vm.setColor(c) }
    ChoiceChips("Photos / pages per sheet", Presets.all, s.layout, { it.label }) { preset ->
        vm.update { it.copy(layout = preset, fit = if (preset is FixedSizePreset) FitMode.FILL else it.fit) }
    }
    ChoiceChips("Orientation", PageOrientation.entries, s.orientation, { it.label }) { o -> vm.update { it.copy(orientation = o) } }
    ChoiceChips("Fit", FitMode.entries, s.fit, { it.label }) { f -> vm.update { it.copy(fit = f) } }
    ChoiceChips("Margins", MarginPreset.entries, s.margin, { it.label }) { m -> vm.update { it.copy(margin = m) } }
    if (s.margin == MarginPreset.CUSTOM) {
        SliderRow("Custom margin", s.customMarginMm, 0f..25f, "${s.customMarginMm.roundToInt()} mm", steps = 24) { v ->
            vm.update { it.copy(customMarginMm = v.roundToInt().toFloat()) }
        }
    }
    ChoiceChips("Print quality", Quality.entries, s.quality, { it.label }) { q -> vm.update { it.copy(quality = q) } }
    Hint("Follows colour: Standard for colour, Ink saver for grayscale. You can change it.")
    Stepper("Copies", s.copies, 1, 99) { v -> vm.update { it.copy(copies = v) } }

    val canSave = plan.sheets.isNotEmpty() && !vm.exporting && !vm.importing
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { vm.exportPdf() }, enabled = canSave, modifier = Modifier.weight(1f)) { Text("Save as PDF") }
        Button(onClick = { vm.exportImages(300, png = false) }, enabled = canSave, modifier = Modifier.weight(1f)) { Text("Save as images") }
    }
    vm.exportProgress?.let {
        Text(it, style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    vm.lastExport?.let { Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    Hint("After saving, the share menu opens so you can send it straight to WhatsApp, Drive, etc.")
}

// ---------------------------------------------------------------- panels

@Composable
private fun LayoutPanel(vm: JobViewModel, s: PrintSettings) {
    ChoiceChips("Photos / pages per sheet", Presets.all, s.layout, { it.label }) { preset ->
        vm.update { it.copy(layout = preset, fit = if (preset is FixedSizePreset) FitMode.FILL else it.fit) }
    }
    ChoiceChips("Orientation", PageOrientation.entries, s.orientation, { it.label }) { o ->
        vm.update { it.copy(orientation = o) }
    }
    SwitchRow("Repeat one photo to fill the sheet", s.repeatToFill, "For passport photos or stickers: share one photo, get a full sheet") { v ->
        vm.update { it.copy(repeatToFill = v) }
    }
    SliderRow("Gap between photos", s.gapMm, 0f..10f, "${s.gapMm.roundToInt()} mm", steps = 9) { v ->
        vm.update { it.copy(gapMm = v.roundToInt().toFloat()) }
    }
    SwitchRow("Border around each item", s.border) { v -> vm.update { it.copy(border = v) } }
    SwitchRow("Cut lines", s.cutLines, "Light dashed lines to cut along") { v -> vm.update { it.copy(cutLines = v) } }
}

@Composable
private fun FitPanel(vm: JobViewModel, s: PrintSettings) {
    ChoiceChips("Fit", FitMode.entries, s.fit, { it.label }) { f -> vm.update { it.copy(fit = f) } }
    Hint("Fit shows the whole photo. Fill covers the frame and crops the edges — tap a photo to choose which part shows.")
    SwitchRow("Auto-rotate to match the frame", s.autoRotate, "Turns landscape photos sideways in portrait frames, and the reverse") { v ->
        vm.update { it.copy(autoRotate = v) }
    }
    SliderRow("Scale", s.scalePercent.toFloat(), 25f..200f, "${s.scalePercent}%") { v ->
        vm.update { it.copy(scalePercent = (v / 5f).roundToInt() * 5) }
    }
    ChoiceChips("Margins", MarginPreset.entries, s.margin, { it.label }) { m -> vm.update { it.copy(margin = m) } }
    if (s.margin == MarginPreset.CUSTOM) {
        SliderRow("Custom margin", s.customMarginMm, 0f..25f, "${s.customMarginMm.roundToInt()} mm", steps = 24) { v ->
            vm.update { it.copy(customMarginMm = v.roundToInt().toFloat()) }
        }
    }
    if (s.margin == MarginPreset.NONE) Hint("Borderless needs photo paper; most printers still leave a tiny edge on plain paper.")
}

@Composable
private fun PaperPanel(vm: JobViewModel, s: PrintSettings) {
    ChoiceChips("Paper size", PaperSize.entries, s.paper, { it.label }) { p -> vm.update { it.copy(paper = p) } }
    ChoiceChips("Paper type", PaperType.entries, s.paperType, { it.label }) { t ->
        vm.update {
            it.copy(
                paperType = t,
                quality = when {
                    t != PaperType.PLAIN && it.quality.ordinal < Quality.PHOTO.ordinal -> Quality.PHOTO
                    t == PaperType.PLAIN && it.quality.ordinal >= Quality.PHOTO.ordinal -> Quality.STANDARD
                    else -> it.quality
                },
            )
        }
    }
    ChoiceChips("Quality", Quality.entries, s.quality, { it.label }) { q -> vm.update { it.copy(quality = q) } }
    Hint("Ink use: ${s.quality.inkHint}. Paper type and quality are sent to the printer in Phase 2; saved PDFs are always full quality.")
}

@Composable
private fun ColourPanel(vm: JobViewModel, s: PrintSettings) {
    ChoiceChips("Colour", ColorMode.entries, s.color, { it.label }) { c -> vm.setColor(c) }
    if (s.color == ColorMode.BLACK_ONLY) Hint("Previews in grayscale. When printing (Phase 2) only the black ink is used.")
    SliderRow("Brightness", s.brightness, -0.5f..0.5f, "${(s.brightness * 100).roundToInt()}") { v ->
        vm.update { it.copy(brightness = v) }
    }
    SliderRow("Contrast", s.contrast, 0.5f..2f, "%.2f×".format(s.contrast)) { v -> vm.update { it.copy(contrast = v) } }
    TextButton(onClick = { vm.update { it.copy(brightness = 0f, contrast = 1f) } }) { Text("Reset brightness & contrast") }
}

@Composable
private fun PagesPanel(vm: JobViewModel, s: PrintSettings, plan: PrintPlan) {
    ChoiceChips("Pages", PageSet.entries, s.pageSet, { it.label }) { p -> vm.update { it.copy(pageSet = p) } }
    if (s.pageSet == PageSet.CUSTOM) {
        OutlinedTextField(
            value = s.pageRange,
            onValueChange = { v -> vm.update { it.copy(pageRange = v) } },
            label = { Text("Pages, e.g. 1-5, 8, 11-13") },
            isError = plan.rangeError != null,
            supportingText = { Text(plan.rangeError ?: "Numbers are the items in the strip above") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Stepper("Copies", s.copies, 1, 99) { v -> vm.update { it.copy(copies = v) } }
    SwitchRow("Collate", s.collate, "On: 1-2-3, 1-2-3. Off: 1-1, 2-2, 3-3", enabled = s.copies > 1) { v ->
        vm.update { it.copy(collate = v) }
    }
    ChoiceChips("Two-sided", Duplex.entries, s.duplex, { it.label }) { d -> vm.update { it.copy(duplex = d) } }
    if (s.duplex != Duplex.OFF) {
        SwitchRow("Reverse order of back sides", s.reverseBackOrder, "Set by the two-sided setup test in Phase 2") { v ->
            vm.update { it.copy(reverseBackOrder = v) }
        }
        SwitchRow("Rotate back sides 180°", s.rotateBack180) { v -> vm.update { it.copy(rotateBack180 = v) } }
        plan.twoSided?.let { TwoSidedCard(it) }
    }
}

@Composable
fun TwoSidedCard(t: io.printprep.app.engine.TwoSidedPlan) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("How two-sided printing will run", style = MaterialTheme.typography.titleSmall)
            Text("Pass 1 (fronts): pages ${t.pass1.joinToString(", ")}")
            Text("Then flip the stack and reload it in the rear tray.")
            if (t.pass2.isEmpty()) Text("Pass 2: nothing (only one page).")
            else Text("Pass 2 (backs): pages ${t.pass2.joinToString(", ")}${if (t.rotateBacks) " — rotated 180°" else ""}")
            t.keepAside?.let { Text("Odd page count: keep sheet $it aside before pass 2 — it has no back.") }
        }
    }
}
