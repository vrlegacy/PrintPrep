package io.printprep.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.printprep.app.JobViewModel

/** Quick links that open straight in the in-app browser. */
object WebShortcuts {
    /** The browser's Home button goes here. */
    const val KARNATAKA_LAND_RECORDS = "https://landrecords.karnataka.gov.in/Service2/"
}

/** Turns what was typed into an address: a URL stays a URL, anything else becomes a web search. */
fun toUrl(input: String): String {
    val t = input.trim()
    return when {
        t.startsWith("http://") || t.startsWith("https://") -> t
        !t.contains(' ') && t.contains('.') -> "https://$t"
        else -> "https://www.google.com/search?q=" + Uri.encode(t)
    }
}

/**
 * In-app browser. Bottom: a compact navigation bar (back, forward, home, address/search, refresh,
 * desktop site) above the capture/print bar. A thin loading line shows while pages or downloads load.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebCaptureScreen(vm: JobViewModel, startUrl: String) {
    var currentUrl by remember { mutableStateOf(toUrl(startUrl)) }
    var field by remember { mutableStateOf(TextFieldValue(toUrl(startUrl))) }
    var editing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf(0f) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var desktop by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("") }
    var pickedImage by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val title = pageTitle.ifBlank { Uri.parse(currentUrl).host ?: "Web page" }

    fun go(input: String) {
        val url = toUrl(input)
        focus.clearFocus()
        web?.loadUrl(url)
    }

    DisposableEffect(Unit) { onDispose { vm.webNotice = null } }
    BackHandler(enabled = editing || canGoBack) {
        if (editing) focus.clearFocus() else web?.goBack()
    }

    Scaffold(
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                // Sits above the keyboard when it's open (the page area shrinks to make room),
                // and above the system navigation bar otherwise.
                Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
                    // ---- loading line (page or download)
                    Box(Modifier.fillMaxWidth().height(3.dp)) {
                        when {
                            vm.webBusy || (loading && progress < 0.05f) ->
                                LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                            loading ->
                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp))
                        }
                    }
                    // ---- compact navigation bar
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NavIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", canGoBack) { web?.goBack() }
                        NavIcon(Icons.AutoMirrored.Filled.ArrowForward, "Forward", canGoForward) { web?.goForward() }
                        NavIcon(Icons.Filled.Home, "Home (land records)", true) { go(WebShortcuts.KARNATAKA_LAND_RECORDS) }
                        AddressField(
                            value = if (editing) field else TextFieldValue(Uri.parse(currentUrl).host ?: currentUrl),
                            onValueChange = { field = it },
                            onFocus = { focused ->
                                editing = focused
                                // show the full address, all selected, so typing replaces it
                                if (focused) field = TextFieldValue(currentUrl, TextRange(0, currentUrl.length))
                            },
                            onGo = { go(field.text) },
                            modifier = Modifier.weight(1f),
                        )
                        if (editing) {
                            NavIcon(Icons.Filled.Close, "Cancel", true) { focus.clearFocus() }
                        } else {
                            NavIcon(Icons.Filled.Refresh, "Reload", web != null) { web?.reload() }
                            NavIcon(DesktopIcon, if (desktop) "Phone site" else "Desktop site", true, selected = desktop) {
                                desktop = !desktop
                                web?.let { setDesktop(it, desktop); it.reload() }
                            }
                        }
                    }
                    HorizontalDivider()
                    // ---- capture / print bar
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        vm.webNotice?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { web?.let { vm.captureVisible(it, title) } },
                                enabled = web != null && !loading,
                                modifier = Modifier.weight(1f).height(44.dp),
                            ) { Text("Capture screen", maxLines = 1) }
                            OutlinedButton(
                                onClick = { web?.let { vm.captureWebPage(it, title) } },
                                enabled = web != null && !loading,
                                modifier = Modifier.weight(1f).height(44.dp),
                            ) { Text("Full page", maxLines = 1) }
                            Button(
                                onClick = { vm.goToEditor() },
                                enabled = vm.items.isNotEmpty(),
                                modifier = Modifier.weight(1f).height(44.dp),
                            ) { Text("Print (${vm.items.size})", maxLines = 1) }
                        }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // slim title strip with a close button (the system Back key goes back inside the site)
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                NavIcon(Icons.Filled.Close, "Close browser", true) { vm.back() }
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.setSupportMultipleWindows(false)
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                                progress = 0f
                                if (url != null) currentUrl = url
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                if (url != null) currentUrl = url
                                pageTitle = view?.title.orEmpty()
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                                if (url != null) currentUrl = url
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress / 100f
                                if (newProgress >= 100) loading = false
                            }

                            override fun onReceivedTitle(view: WebView?, t: String?) {
                                pageTitle = t.orEmpty()
                            }
                        }
                        // Files the site downloads (e.g. an RTC as PDF or image) go straight into the print job.
                        setDownloadListener { url, userAgent, _, _, _ ->
                            vm.fetchFromWeb(url, userAgent, this.url, toGallery = false)
                        }
                        // Long-press on an image: ask what to do with it.
                        setOnLongClickListener {
                            val hit = hitTestResult
                            when (hit.type) {
                                WebView.HitTestResult.IMAGE_TYPE -> {
                                    pickedImage = hit.extra
                                    true
                                }
                                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                                    val msg = Handler(Looper.getMainLooper()) { m: Message ->
                                        pickedImage = m.data.getString("url") ?: hit.extra
                                        true
                                    }.obtainMessage()
                                    requestImageRef(msg)
                                    true
                                }
                                else -> false
                            }
                        }
                        loadUrl(toUrl(startUrl))
                        web = this
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }

    pickedImage?.let { url ->
        val wv = web
        AlertDialog(
            onDismissRequest = { pickedImage = null },
            title = { Text("Image") },
            text = { Text("Add this image to your print job, or save it to the gallery?") },
            confirmButton = {
                TextButton(onClick = {
                    pickedImage = null
                    vm.fetchFromWeb(url, wv?.settings?.userAgentString, wv?.url, toGallery = false)
                }) { Text("Add to print") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pickedImage = null
                    vm.fetchFromWeb(url, wv?.settings?.userAgentString, wv?.url, toGallery = true)
                }) { Text("Save to gallery") }
            },
        )
    }
}

@Composable
private fun NavIcon(icon: ImageVector, label: String, enabled: Boolean, selected: Boolean = false, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
        colors = if (selected) IconButtonDefaults.filledTonalIconButtonColors() else IconButtonDefaults.iconButtonColors(),
    ) { Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp)) }
}

/** Small rounded address/search field (much shorter than a standard text field). */
@Composable
private fun AddressField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onFocus: (Boolean) -> Unit,
    onGo: () -> Unit,
    modifier: Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        modifier = modifier
            .padding(horizontal = 4.dp)
            .height(36.dp)
            .onFocusChanged { onFocus(it.isFocused) },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.text.isEmpty()) {
                    Text("Search or type address", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                inner()
            }
        },
    )
}

/** Simple monitor icon for "Desktop site" (the standard icon set doesn't include one). */
private val DesktopIcon: ImageVector by lazy {
    ImageVector.Builder("Desktop", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(androidx.compose.ui.graphics.Color.Black), pathFillType = androidx.compose.ui.graphics.PathFillType.EvenOdd) {
            // screen frame
            moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 16f); lineTo(3f, 16f); close()
            moveTo(5f, 6f); lineTo(5f, 14f); lineTo(19f, 14f); lineTo(19f, 6f); close()
            // stand
            moveTo(10f, 16f); lineTo(14f, 16f); lineTo(14f, 18f); lineTo(10f, 18f); close()
            moveTo(7f, 18f); lineTo(17f, 18f); lineTo(17f, 20f); lineTo(7f, 20f); close()
        }
    }.build()
}

/** Switches the site between phone and desktop layout (some government sites only work well in desktop mode). */
private fun setDesktop(w: WebView, on: Boolean) {
    val s = w.settings
    s.userAgentString = if (on) {
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    } else null
    s.useWideViewPort = true
    s.loadWithOverviewMode = true
}
