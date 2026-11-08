package io.printprep.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.printprep.app.JobViewModel
import io.printprep.app.data.ExportResult
import io.printprep.app.engine.PrintPlan

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutputSheet(vm: JobViewModel, plan: PrintPlan, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var dpi by remember { mutableIntStateOf(300) }
    var png by remember { mutableStateOf(false) }
    val s = vm.settings

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Save or print", style = MaterialTheme.typography.titleLarge)
            Text(
                buildString {
                    append("${plan.sheets.size} page(s) of ${s.paper.label}, ${if (plan.landscape) "landscape" else "portrait"}")
                    append(" · ${plan.cellsPerSheet} per sheet")
                    if (s.copies > 1) append(" · ${s.copies} copies${if (s.collate) " (collated)" else ""}")
                    append("\n${s.paperType.label} · ${s.quality.label} · ${s.color.label}")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            plan.twoSided?.let { TwoSidedCard(it) }

            Button(onClick = { vm.exportPdf() }, enabled = !vm.exporting, modifier = Modifier.fillMaxWidth()) {
                Text("Save as PDF (original photo quality)")
            }
            Hint("JPEG photos are placed in the PDF unchanged. Other formats and colour-adjusted photos are kept at full resolution without lossy compression.")
            HorizontalDivider()
            ChoiceChips("Image resolution", listOf(150, 300), dpi, { "$it DPI" }) { dpi = it }
            ChoiceChips("Image format", listOf(false, true), png, { if (it) "PNG" else "JPEG" }) { png = it }
            OutlinedButton(onClick = { vm.exportImages(dpi, png) }, enabled = !vm.exporting, modifier = Modifier.fillMaxWidth()) {
                Text("Save as images (one per page)")
            }

            vm.exportProgress?.let {
                Text(it)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            vm.lastExport?.let { result ->
                Text(result.message, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { share(context, result) }) { Text("Share") }
                    OutlinedButton(onClick = { open(context, result) }) { Text("Open") }
                }
            }

            HorizontalDivider()
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text("Print on Canon G2010 (USB) — Phase 2")
            }
            Hint("Two-sided and paper/quality settings take effect when printing. Saved files contain every page in print order.")
        }
    }
}

private fun share(context: Context, r: ExportResult) {
    val intent = if (r.uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, r.uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(r.uris))
    }.apply {
        type = r.mime
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share"))
}

private fun open(context: Context, r: ExportResult) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(r.uris.first(), r.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        share(context, r)
    }
}
