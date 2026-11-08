package io.printprep.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.printprep.app.JobViewModel

@Composable
fun HomeScreen(vm: JobViewModel) {
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        vm.import(uris)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.import(uris)
    }
    var askUrl by remember { mutableStateOf(false) }

    Scaffold { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("PrintPrep", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Share photos, PDFs or links from any app — or pick them here. Lay them out, then save as PDF or images.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            if (vm.items.isNotEmpty()) {
                Button(onClick = { vm.goToEditor() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Continue current job (${vm.items.size} items)")
                }
            }
            FilledTonalButton(
                onClick = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Pick photos") }
            FilledTonalButton(
                onClick = { files.launch(arrayOf("application/pdf", "image/*")) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Pick PDFs or files") }
            FilledTonalButton(onClick = { askUrl = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Print a web page")
            }
            if (vm.items.isNotEmpty()) {
                OutlinedButton(onClick = { vm.newJob() }, modifier = Modifier.fillMaxWidth()) { Text("Start a new job") }
            }

            PrinterStatusCard(vm)
            OutlinedButton(onClick = { vm.openDiagnostics() }, modifier = Modifier.fillMaxWidth()) { Text("Printer diagnostics & test page") }
        }
    }

    if (askUrl) UrlDialog(onDismiss = { askUrl = false }) { url ->
        askUrl = false
        vm.openWeb(url)
    }
}

@Composable
private fun UrlDialog(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var text by remember { mutableStateOf("https://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Web page") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Address") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        },
        confirmButton = { TextButton(onClick = { onOpen(text.trim()) }, enabled = text.trim().length > 8) { Text("Open") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
