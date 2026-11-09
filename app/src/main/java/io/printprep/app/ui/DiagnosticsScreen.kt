package io.printprep.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.printprep.app.JobViewModel
import io.printprep.app.print.Gutenprint
import io.printprep.app.print.PrintUi
import io.printprep.app.print.PrinterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** For the first day with the cable: USB details, printer ID and status, driver check, test page, raw file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(vm: JobViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ctl = vm.print
    val printer = ctl.printer
    var details by remember { mutableStateOf("") }
    var driver by remember { mutableStateOf("Checking…") }
    val saveData = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val f = ctl.lastReport?.dataFile
        if (uri != null && f != null && f.exists()) scope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } } }
        }
    }
    val pickRaw = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) ctl.sendRawFile(uri) }

    fun reload() {
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                printer.refresh()
                buildString {
                    appendLine(printer.describe())
                    if (printer.state is PrinterState.Ready) {
                        appendLine()
                        appendLine("Device ID: ${runCatching { printer.readDeviceId() }.getOrNull() ?: "(not reported)"}")
                        val st = runCatching { printer.readStatus() }.getOrNull()
                        appendLine("Port status: ${st?.let { "0x%02X · %s".format(it.raw, it.summary) } ?: "(not reported)"}")
                    }
                }
            }
            details = text.trimEnd()
            driver = withContext(Dispatchers.IO) {
                Gutenprint.ensureReady(context) ?: "Gutenprint ${Gutenprint.version()} · driver ${Gutenprint.G2010_DRIVER} ready"
            }
        }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Printer diagnostics") },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PrinterStatusCard(vm)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Printer driver", style = MaterialTheme.typography.titleSmall)
                    Text(driver, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("USB details", style = MaterialTheme.typography.titleSmall)
                    Text(details.ifEmpty { "…" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
            OutlinedButton(onClick = { reload() }, modifier = Modifier.fillMaxWidth()) { Text("Refresh") }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Last print job", style = MaterialTheme.typography.titleSmall)
                    val r = ctl.lastReport
                    if (r == null) {
                        Text("Nothing sent yet in this session.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(
                            buildString {
                                appendLine("Pages sent: ${r.pages}")
                                appendLine("Bytes sent: ${r.bytes}")
                                appendLine("Data starts: ${r.header}")
                                appendLine("Port status after: ${r.statusAfter?.let { "0x%02X · %s".format(it.raw, it.summary) } ?: "(not reported)"}")
                                appendLine("Printer said: ${r.printerSaid?.let { "${it.status}${if (it.detail.isNotEmpty()) " / ${it.detail}" else ""}${if (it.code.isNotEmpty()) " / code ${it.code}" else ""}" } ?: "(no status replies)"}")
                                append("Pages confirmed by printer: ${if (r.confirmedByPrinter) "yes" else "no (only sent)"}")
                            },
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        )
                        if (!r.header.startsWith("<?xml")) {
                            Text(
                                "This data doesn't begin with the Canon job header the G2010 expects, so the printer may ignore it.",
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (r.dataFile != null) {
                            OutlinedButton(onClick = { saveData.launch("printprep-page1.prn") }, modifier = Modifier.fillMaxWidth()) {
                                Text("Save page 1 printer data (.prn)")
                            }
                        }
                    }
                }
            }

            val ready = printer.state is PrinterState.Ready && !ctl.busy
            Button(onClick = { ctl.printTestPage() }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Print test page") }
            OutlinedButton(onClick = { pickRaw.launch(arrayOf("*/*")) }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                Text("Send a raw .prn file (made on a PC)")
            }
            Hint("Test page: colour bars, grey steps and text through the Canon driver. Raw file: sent unchanged, e.g. a Windows \"print to file\" from Canon's own driver.")

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
