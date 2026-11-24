package io.printprep.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.printprep.app.JobViewModel
import io.printprep.app.print.PrintUi
import io.printprep.app.print.PrinterState

/**
 * Printer operations: in-app maintenance (nozzle-check pattern, test page, clear a stuck job) plus
 * guides for the printer's own built-in operations (cleaning, deep cleaning, alignment), which the
 * G2010 runs from its own buttons or Canon's desktop tool rather than over this USB print channel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterOpsScreen(vm: JobViewModel) {
    val ctl = vm.print
    val ready = ctl.printer.state is PrinterState.Ready && !ctl.busy

    Scaffold(
        topBar = { TopAppBar(title = { Text("Printer") }) },
        bottomBar = { AppBottomNav(vm) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PrinterStatusCard(vm)

            // ---- in-app maintenance you can run right now
            Text("Maintenance", style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Print a nozzle-check pattern and look for white gaps or faint bands. " +
                            "If you see them, run a head cleaning (below), then check again.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { ctl.printNozzleCheck() }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                        Text("Print nozzle-check pattern")
                    }
                    OutlinedButton(onClick = { ctl.printTestPage() }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                        Text("Print test page")
                    }
                    OutlinedButton(onClick = { ctl.clearStuckJob() }, enabled = !ctl.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Clear a stuck job (stop & reset)")
                    }
                    OpStatus(ctl.ui)
                }
            }

            // ---- the printer's own built-in operations
            Text("Built-in printer operations", style = MaterialTheme.typography.titleMedium)
            Hint(
                "The Canon G2010 has no screen, so these run from the printer's own buttons or from " +
                    "Canon's \"IJ Printer Assistant Tool\" on a Windows/Mac computer over USB — not through " +
                    "this app. Exact button-hold counts are printed in your model's manual.",
            )
            GuideCard(
                "Head cleaning",
                "Pushes ink through the nozzles to clear light clogging. Use it when the nozzle-check " +
                    "pattern has small gaps. Uses a little ink.",
                listOf(
                    "Load A4 plain paper in the rear tray.",
                    "On a computer: open Canon IJ Printer Assistant Tool → Cleaning.",
                    "Or on the printer: hold the Stop/Reset button for the number of flashes your manual lists for Cleaning, then release.",
                    "Print the nozzle-check pattern again to confirm the gaps are gone.",
                ),
            )
            GuideCard(
                "Deep cleaning",
                "A stronger clean for heavy clogging that a normal cleaning didn't fix. Uses more ink, " +
                    "so only run it if a head cleaning wasn't enough.",
                listOf(
                    "Run a normal head cleaning first and re-check the pattern.",
                    "If gaps remain, open Canon IJ Printer Assistant Tool → Deep Cleaning.",
                    "Leave the printer to rest for a few hours if deep cleaning still doesn't clear it, then try once more.",
                ),
            )
            GuideCard(
                "Print head alignment",
                "Lines up the head so lines and photos are sharp, not doubled or grainy. Do it after " +
                    "moving the printer or replacing the head.",
                listOf(
                    "Load A4 plain paper.",
                    "Open Canon IJ Printer Assistant Tool → Print Head Alignment and follow the on-screen steps.",
                ),
            )
            GuideCard(
                "Ink / nozzle gaps that won't clear",
                "For a tank printer, make sure there is actually ink in the tanks and that the orange " +
                    "shipping caps and valve were removed when the printer was first set up.",
                listOf(
                    "Check each tank is above the lower line; top up if low.",
                    "Confirm the tank valve/lever is in the printing position (see the setup guide).",
                    "If a colour is still missing after cleaning, the head may need replacing.",
                ),
            )

            OutlinedButton(onClick = { vm.openDiagnostics() }, modifier = Modifier.fillMaxWidth()) {
                Text("Advanced diagnostics (USB, driver, raw file)")
            }
        }
    }
}

/** Collapsible card: a title, a short "what it does", and expandable step-by-step. */
@Composable
private fun GuideCard(title: String, summary: String, steps: List<String>) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (!open) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (open) "Collapse" else "Expand",
                )
            }
            AnimatedVisibility(open) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                    steps.forEachIndexed { i, step ->
                        Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** Shared Running/Done/Failed line for the maintenance actions. */
@Composable
private fun OpStatus(ui: PrintUi) {
    when (ui) {
        is PrintUi.Running -> {
            Text("${ui.label}: ${ui.stage} ${(ui.fraction * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            ui.notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            LinearProgressIndicator(progress = { ui.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        is PrintUi.Done -> Text(ui.message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        is PrintUi.Failed -> Text(ui.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        else -> {}
    }
}
