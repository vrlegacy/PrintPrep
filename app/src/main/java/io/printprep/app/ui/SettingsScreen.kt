package io.printprep.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.printprep.app.JobViewModel
import io.printprep.app.print.PrintUi
import io.printprep.app.print.PrinterState
import io.printprep.app.model.ItemKind
import io.printprep.app.model.PrintTone
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import io.printprep.app.UpdateState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: JobViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("App updates", style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Installed: build ${vm.currentBuild}", style = MaterialTheme.typography.bodyMedium)
                    SwitchRow(
                        "Check for updates when the app opens",
                        vm.checkUpdatesOnOpen,
                        "Shows a banner on the Home screen when a new version is out",
                    ) { vm.changeCheckUpdatesOnOpen(it) }
                    UpdateStatus(vm)
                    OutlinedButton(
                        onClick = { vm.checkForUpdate(fromUser = true) },
                        enabled = vm.update !is UpdateState.Checking,
                    ) { Text("Check now") }
                    Hint("Update opens the new version in your browser; tap the finished download to install it.")
                }
            }
            Text("Printer", style = MaterialTheme.typography.titleMedium)
            PrinterStatusCard(vm)
            OutlinedButton(onClick = { vm.openDiagnostics() }, modifier = Modifier.fillMaxWidth()) { Text("Printer diagnostics & test page") }

            Text("Print colour", style = MaterialTheme.typography.titleMedium)
            PrintColourCard(vm)
        }
    }
}

/**
 * Tone matching for printing only. "A · No change" sends the picture exactly as it is; B–F lift
 * midtones / calm colours to undo ink spreading on paper. Chosen from a printed A–F sheet.
 */
@Composable
private fun PrintColourCard(vm: JobViewModel) {
    val context = LocalContext.current
    val ctl = vm.print
    var tone by remember { mutableStateOf(PrintTone.saved(context)) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Prints come out a little darker and stronger than on screen because ink spreads into paper. " +
                    "Print the colour match sheet, find the panel closest to your original, and pick its letter.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ChoiceChips("Tone used for printing", PrintTone.entries, tone, { it.letter }) { t ->
                tone = t
                PrintTone.save(context, t)
            }
            Text("${tone.letter} · ${tone.label}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            val ready = ctl.printer.state is PrinterState.Ready && !ctl.busy
            Button(
                onClick = {
                    ctl.printColourMatch(
                        vm.items.firstOrNull { it.kind == ItemKind.IMAGE },
                        vm.settings.quality,
                        vm.settings.paperType,
                    )
                },
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Print colour match sheet") }
            Hint(
                "Uses the first photo in your current job (or a built-in chart) and your current paper and quality, " +
                    "so print it with the settings you normally use. Only printing changes: previews, PDFs and saved images stay exactly as they are.",
            )
            when (val ui = ctl.ui) {
                is PrintUi.Running -> {
                    Text("${ui.label}: ${ui.stage} ${(ui.fraction * 100).toInt()}%")
                    ui.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    LinearProgressIndicator(progress = { ui.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { ctl.cancel() }) { Text("Cancel") }
                }
                is PrintUi.Done -> Text(ui.message, color = MaterialTheme.colorScheme.primary)
                is PrintUi.Failed -> Text(ui.message, color = MaterialTheme.colorScheme.error)
                else -> {}
            }
        }
    }
}

/** One-line status plus the Update button where relevant. Shared by Settings and the Home banner. */
@Composable
fun UpdateStatus(vm: JobViewModel) {
    when (val u = vm.update) {
        UpdateState.Idle -> {}
        UpdateState.Checking -> {
            Text("Checking for updates…", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        UpdateState.UpToDate -> Text("You have the latest version.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        is UpdateState.Available -> {
            Text("${u.release.name} is available.", style = MaterialTheme.typography.bodyMedium)
            if (u.release.notes.isNotBlank()) Hint(u.release.notes)
            Button(onClick = { vm.downloadAndInstall() }) { Text("Download update") }
        }
        is UpdateState.Failed -> Text(u.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

/** Banner for the Home screen: new version available, with Update and Not now. */
@Composable
fun UpdateBanner(vm: JobViewModel) {
    if (!vm.showUpdateBanner) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Update available", style = MaterialTheme.typography.titleSmall)
            when (val u = vm.update) {
                is UpdateState.Available -> {
                    Text("${u.release.name}. You're on build ${vm.currentBuild}.", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.downloadAndInstall() }) { Text("Update") }
                        TextButton(onClick = { vm.dismissUpdate() }) { Text("Not now") }
                    }
                }
                else -> UpdateStatus(vm)
            }
        }
    }
}
