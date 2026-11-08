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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.printprep.app.JobViewModel

/** Quick links that open straight in the in-app browser. */
object WebShortcuts {
    const val KARNATAKA_LAND_RECORDS = "https://landrecords.karnataka.gov.in/Service2/"
}

private fun normalize(url: String): String {
    val t = url.trim()
    return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
}

/**
 * In-app browser: open any site (e.g. Karnataka land records), then
 *  - Capture screen: what's visible now, as one image
 *  - Full page: the whole page, split into printable pages
 *  - long-press an image: add it to print, or save it to the gallery
 *  - files the site downloads (PDFs, images) are added to the print job automatically
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebCaptureScreen(vm: JobViewModel, startUrl: String) {
    var address by remember { mutableStateOf(normalize(startUrl)) }
    var loading by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf(0f) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var desktop by remember { mutableStateOf(false) }
    var pickedImage by remember { mutableStateOf<String?>(null) }
    val title = web?.title?.takeIf { it.isNotBlank() } ?: Uri.parse(address).host ?: "Web page"

    DisposableEffect(Unit) { onDispose { vm.webNotice = null } }
    // Back goes back inside the site first, then leaves the browser.
    BackHandler(enabled = canGoBack) { web?.goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close browser") } },
                actions = { IconButton(onClick = { web?.reload() }) { Icon(Icons.Filled.Refresh, "Reload") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    vm.webNotice?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { web?.let { vm.captureVisible(it, title) } },
                            enabled = web != null && !loading,
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text("Capture screen") }
                        OutlinedButton(
                            onClick = { web?.let { vm.captureWebPage(it, title) } },
                            enabled = web != null && !loading,
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text("Full page") }
                        Button(
                            onClick = { vm.goToEditor() },
                            enabled = vm.items.isNotEmpty(),
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text("Print (${vm.items.size})") }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    singleLine = true,
                    label = { Text("Address") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { web?.loadUrl(normalize(address)) }),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = desktop,
                    onClick = {
                        desktop = !desktop
                        web?.let { setDesktop(it, desktop); it.reload() }
                    },
                    label = { Text("Desktop site") },
                )
                Hint("Long-press an image to add or save it")
            }
            if (loading) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
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
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                if (url != null) address = url
                                canGoBack = view?.canGoBack() == true
                            }

                            // stay inside the app for normal links
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress / 100f
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
                                    // linked image: ask WebView for the image's own address
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
                        loadUrl(normalize(startUrl))
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

/** Switches the site between phone and desktop layout (some government sites only work well in desktop mode). */
private fun setDesktop(w: WebView, on: Boolean) {
    val s = w.settings
    s.userAgentString = if (on) {
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    } else null
    s.useWideViewPort = true
    s.loadWithOverviewMode = true
}
