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
