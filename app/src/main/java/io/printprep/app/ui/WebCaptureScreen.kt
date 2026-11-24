package io.printprep.app.ui

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import android.net.Uri
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
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

    /** Image search opens Google Images; its search box is auto-focused on load. */
    const val GOOGLE_IMAGES = "https://www.google.com/imghp?hl=en&authuser=0&ogbl"

    /** Web search opens the Google home page; its search box is auto-focused on load. */
    const val GOOGLE = "https://www.google.com/webhp?hl=en&authuser=0&ogbl"
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
 * In-app browser. It renders the one live [io.printprep.app.ui.WebSession] held by the view model, so
 * leaving for the print editor and coming Back resumes the exact page with no reload. Bottom: a
 * compact navigation bar (back, forward, home, address/search, refresh, desktop site) above the
 * capture/print bar. A thin loading line shows while pages or downloads load.
 */
@Composable
fun WebCaptureScreen(vm: JobViewModel, startUrl: String) {
    val context = LocalContext.current
    val session = remember { vm.ensureWebSession(context) }
    var editing by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var showTabs by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    // First time (empty session) load the launcher's URL; a link shared into the app forces its URL.
    LaunchedEffect(Unit) { if (session.isEmpty && vm.webForceLoadUrl == null) session.open(startUrl) }
    LaunchedEffect(vm.webForceLoadUrl) {
        vm.webForceLoadUrl?.let { session.open(it); vm.webForceLoadUrl = null }
    }
    DisposableEffect(Unit) { onDispose { vm.webNotice = null } }

    val currentUrl = session.currentUrl
    val title = session.pageTitle.ifBlank { Uri.parse(currentUrl).host ?: "Web page" }

    fun go(input: String) {
        focus.clearFocus()
        session.open(input)
    }

    // Back closes the tab switcher first, then dismisses editing, then walks the page/layer history;
    // when disabled here the app's own Back handler moves to the previous screen.
    BackHandler(enabled = showTabs || editing || session.canGoBack) {
        when {
            showTabs -> showTabs = false
            editing -> focus.clearFocus()
            else -> session.onBack()
        }
    }

    Scaffold(
        topBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                    // ---- title strip with a close button (the system Back key goes back inside the site)
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        NavIcon(Icons.Filled.Close, "Close browser", true) { vm.back() }
                        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        // A tab switcher appears only once the site has opened a second page in a new tab.
                        if (session.tabCount >= 2) {
                            TabCountButton(session.tabCount) {
                                session.refreshActiveThumb()
                                showTabs = true
                            }
                        }
                    }
                    // ---- compact navigation bar + address field
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NavIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", session.canGoBack) { session.onBack() }
                        NavIcon(Icons.AutoMirrored.Filled.ArrowForward, "Forward", session.canGoForward) { session.goForward() }
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
                            editing = editing,
                            onClear = { field = TextFieldValue("") },
                            modifier = Modifier.weight(1f),
                        )
                        if (editing) {
                            NavIcon(Icons.Filled.Close, "Cancel", true) { focus.clearFocus() }
                        } else {
                            NavIcon(Icons.Filled.Refresh, "Reload", session.active != null) { session.reload() }
                            NavIcon(DesktopIcon, if (session.desktop) "Phone site" else "Desktop site", true, selected = session.desktop) {
                                session.toggleDesktop()
                            }
                        }
                    }
                    // ---- loading line (page or download)
                    Box(Modifier.fillMaxWidth().height(3.dp)) {
                        when {
                            vm.webBusy || (session.loading && session.progress < 0.05f) ->
                                LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                            session.loading ->
                                LinearProgressIndicator(progress = { session.progress }, modifier = Modifier.fillMaxWidth().height(3.dp))
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                // Above the system navigation bar; the keyboard (for the address field at the top) covers this.
                Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
                    HorizontalDivider()
                    // ---- capture / print bar
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        vm.webNotice?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        val canCapture = session.active != null && !session.loading
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { session.active?.let { vm.captureVisible(it, title) } },
                                enabled = canCapture,
                                modifier = Modifier.weight(1f).height(44.dp),
                            ) { Text("Capture screen", maxLines = 1) }
                            OutlinedButton(
                                onClick = { session.active?.let { vm.captureWebPage(it, title) } },
                                enabled = canCapture,
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
        Box(Modifier.padding(pad).fillMaxSize()) {
            // Show the layer on top of the session. key() swaps the view when a new-window layer is
            // pushed or closed; the retained WebView is detached from any old parent before re-attaching.
            val active = session.active
            if (active != null) {
                key(active) {
                    AndroidView(
                        factory = {
                            (active.parent as? android.view.ViewGroup)?.removeView(active)
                            active
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            // Our own loader: a simple ring in the middle over a 70% veil while the page loads.
            if (session.loading || vm.webBusy) PageLoader(if (session.loading && session.progress >= 0.05f) session.progress else null)
        }
    }

    if (showTabs) {
        TabSwitcher(
            session = session,
            onDismiss = { showTabs = false },
            onPick = { session.switchTo(it); showTabs = false },
            onClose = {
                session.closeTab(it)
                if (session.tabCount < 2) showTabs = false
            },
        )
    }

    session.pickedImage?.let { url ->
        val wv: WebView? = session.active
        AlertDialog(
            onDismissRequest = { session.pickedImage = null },
            title = { Text("Image") },
            text = { Text("Add this image to your print job, or save it to the gallery?") },
            confirmButton = {
                TextButton(onClick = {
                    session.pickedImage = null
                    vm.fetchFromWeb(url, wv?.settings?.userAgentString, wv?.url, toGallery = false)
                }) { Text("Add to print") }
            },
            dismissButton = {
                TextButton(onClick = {
                    session.pickedImage = null
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

/** A small square in the top bar showing how many tabs are open; tapping it opens the switcher. */
@Composable
private fun TabCountButton(count: Int, onClick: () -> Unit) {
    val tint = LocalContentColor.current
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Box(
            Modifier.size(22.dp).border(1.5.dp, tint, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (count > 9) "9+" else "$count", style = MaterialTheme.typography.labelMedium, color = tint)
        }
    }
}

/** Full-screen overlay: a grid of thumbnail snapshots of the open tabs. Tap to switch, × to close. */
@Composable
private fun TabSwitcher(
    session: WebSession,
    onDismiss: () -> Unit,
    onPick: (WebView) -> Unit,
    onClose: (WebView) -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tabs (${session.tabCount})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            Spacer(Modifier.height(8.dp))
            val tabs = session.layers.toList()
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(tabs) { wv ->
                    TabCard(
                        title = wv.title?.takeIf { it.isNotBlank() } ?: (wv.url ?: "Tab"),
                        thumb = session.thumbnails[wv],
                        current = wv === session.active,
                        onPick = { onPick(wv) },
                        onClose = { onClose(wv) },
                    )
                }
            }
        }
    }
}

/** One tab in the switcher: its snapshot (or a blank panel), a close button, and the page title. */
@Composable
private fun TabCard(
    title: String,
    thumb: android.graphics.Bitmap?,
    current: Boolean,
    onPick: () -> Unit,
    onClose: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onPick() },
        border = if (current) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(150.dp)) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(30.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), RoundedCornerShape(15.dp)),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close tab", modifier = Modifier.size(18.dp))
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Page-loading overlay: the page dimmed by a 70% veil, with a ring in the middle showing the load
 * percentage ([fraction] null = still waiting for the first bytes, so the ring spins). Touches are
 * blocked while it shows.
 */
@Composable
private fun PageLoader(fraction: Float?) {
    val ring = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val spin by rememberInfiniteTransition(label = "loader").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin",
    )
    val shown by animateFloatAsState(fraction ?: 0.25f, label = "progress")
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        contentAlignment = Alignment.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(64.dp)) {
                val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                drawArc(track, 0f, 360f, useCenter = false, style = stroke)
                drawArc(ring, (if (fraction == null) spin else 0f) - 90f, 360f * shown, useCenter = false, style = stroke)
            }
            if (fraction != null) {
                Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Small rounded address/search field (much shorter than a standard text field). */
@Composable
private fun AddressField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onFocus: (Boolean) -> Unit,
    onGo: () -> Unit,
    editing: Boolean,
    onClear: () -> Unit,
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
            Row(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp))
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.text.isEmpty()) {
                        Text("Search or type address", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    inner()
                }
                // Clear button while editing: wipes the field so you can type a fresh address.
                if (editing && value.text.isNotEmpty()) {
                    IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                    }
                }
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
fun setDesktop(w: WebView, on: Boolean) {
    val s = w.settings
    s.userAgentString = if (on) {
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    } else null
    s.useWideViewPort = true
    s.loadWithOverviewMode = true
}
