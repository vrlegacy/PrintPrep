package io.printprep.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import io.printprep.app.ui.AppTheme
import io.printprep.app.ui.EditorScreen
import io.printprep.app.ui.HomeScreen
import io.printprep.app.ui.ItemEditScreen
import io.printprep.app.ui.WebCaptureScreen

class MainActivity : ComponentActivity() {

    private val vm: JobViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Lets us draw whole web pages (not just the visible part) for printing.
        WebView.enableSlowWholeDocumentDraw()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleIntent(intent)
            vm.onAppOpened()
        }
        setContent { AppTheme { App(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.print.printer.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Anything shared or "opened with" this app. New shares are added to the current job. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            vm.print.printer.refresh()
            return
        }
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::add)
                if (uris.isEmpty()) {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    val url = text?.let { URL_REGEX.find(it)?.value }
                    if (url != null) {
                        vm.openWeb(url)
                        return
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::addAll)
            Intent.ACTION_VIEW -> intent.data?.let(uris::add)
            else -> return
        }
        if (uris.isEmpty()) {
            intent.clipData?.let { cd -> for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let(uris::add) }
        }
        if (uris.isNotEmpty()) vm.import(uris) else vm.message = "Nothing printable was shared."
    }

    companion object {
        private val URL_REGEX = Regex("""https?://\S+""")
    }
}

@Composable
private fun App(vm: JobViewModel) {
    val screen = vm.screen
    BackHandler(enabled = screen !is Screen.Home) { vm.back() }
    when (screen) {
        Screen.Home -> HomeScreen(vm)
        Screen.Editor -> EditorScreen(vm)
        is Screen.EditItem -> ItemEditScreen(vm, screen.itemId)
        is Screen.Web -> WebCaptureScreen(vm, screen.url)
        Screen.Diagnostics -> io.printprep.app.ui.DiagnosticsScreen(vm)
        Screen.Settings -> io.printprep.app.ui.SettingsScreen(vm)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(vm.pendingShare) {
        vm.pendingShare?.let { result ->
            vm.pendingShare = null
            io.printprep.app.ui.shareExport(context, result)
        }
    }
    vm.message?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.message = null },
            confirmButton = { TextButton(onClick = { vm.message = null }) { Text("OK") } },
            text = { Text(msg) },
        )
    }
}
