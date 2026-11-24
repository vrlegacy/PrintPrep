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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.printprep.app.JobViewModel
import io.printprep.app.R

@Composable
fun HomeScreen(vm: JobViewModel) {
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        vm.import(uris)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.import(uris)
    }
    var askImage by remember { mutableStateOf(false) }
    var askWeb by remember { mutableStateOf(false) }

    Scaffold(bottomBar = { AppBottomNav(vm) }) { pad ->
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
            UpdateBanner(vm)
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
            HomeAction("Pick photos", R.drawable.ic_photo, Color(0xFF2E7D6B)) {
                photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            HomeAction("Pick PDFs or files", R.drawable.ic_file, Color(0xFF3F51B5)) {
                files.launch(arrayOf("application/pdf", "image/*", "image/webp"))
            }
            HomeAction("ಪಹಣಿ RTC", R.drawable.ic_pahani, Color(0xFFF3EDE4), content = Color(0xFF3A2E1E), tint = false) {
                vm.openWeb(WebShortcuts.KARNATAKA_LAND_RECORDS)
            }
            HomeAction("Image search", R.drawable.ic_search, Color(0xFF7B4FA3)) { askImage = true }
            HomeAction("Web search", R.drawable.ic_public, Color(0xFF1565C0)) { askWeb = true }
            if (vm.items.isNotEmpty()) {
                OutlinedButton(onClick = { vm.newJob() }, modifier = Modifier.fillMaxWidth()) { Text("Start a new job") }
            }

            PrinterStatusCard(vm)
        }
    }

    if (askImage) SearchDialog(
        title = "Image search",
        label = "Search Google Images or paste a URL",
        onDismiss = { askImage = false },
    ) { query ->
        askImage = false
        vm.openWeb(toImageSearchUrl(query))
    }
    if (askWeb) SearchDialog(
        title = "Web search",
        label = "Search Google or paste a URL",
        onDismiss = { askWeb = false },
    ) { query ->
        askWeb = false
        vm.openWeb(toUrl(query))
    }
}

/**
 * Full-width coloured action with a leading icon, used for the Home entry points. [tint] true draws
 * the monochrome icons in the button's text colour; false shows the artwork in its own colours (the
 * ಪಹಣಿ emblem).
 */
@Composable
private fun HomeAction(
    label: String,
    iconRes: Int,
    container: Color,
    content: Color = Color.White,
    tint: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        if (tint) {
            Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(22.dp))
        } else {
            Image(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
    }
}

@Composable
private fun SearchDialog(title: String, label: String, onDismiss: () -> Unit, onSearch: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(label) },
                placeholder = { Text("What are you looking for?") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
        },
        confirmButton = { TextButton(onClick = { onSearch(text.trim()) }, enabled = text.isNotBlank()) { Text("Search") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
