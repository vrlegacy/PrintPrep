package io.printprep.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.printprep.app.JobViewModel

/**
 * One long-lived in-app browser session, held by [JobViewModel] so it survives leaving the browser
 * screen (for the print editor, Home, etc.) and coming back — with no reload. Reopening any of the
 * Home launchers resumes this same session rather than reloading a start page.
 *
 * It owns a stack of WebView "layers": the root page, plus any page the site opened in a new window
 * (e.g. the ಪಹಣಿ land-records "next page" button). Only the top layer is on screen. [onBack] walks
 * the active layer's own history first; when that layer can go back no further it closes the top
 * layer to reveal the one beneath it, kept exactly as the user left it; only when the root layer has
 * nowhere left to go does it return false, letting the app move to the previous screen.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebSession(private val context: Context, private val vm: JobViewModel) {

    /** Open layers, root first; the last is the one shown. A state list so the UI recomposes on push/pop. */
    val layers = mutableStateListOf<WebView>()
    val active: WebView? get() = layers.lastOrNull()
    val isEmpty: Boolean get() = layers.isEmpty()

    // ---- state the browser UI observes
    var loading by mutableStateOf(true)
        private set
    var progress by mutableStateOf(0f)
        private set
    var currentUrl by mutableStateOf("")
        private set
    var pageTitle by mutableStateOf("")
        private set
    var desktop by mutableStateOf(false)
        private set
    var pickedImage by mutableStateOf<String?>(null)
    private var activeCanGoBack by mutableStateOf(false)
    private var activeCanGoForward by mutableStateOf(false)

    /** Back is possible while the page has history, or there is a layer to close beneath this one. */
    val canGoBack: Boolean get() = activeCanGoBack || layers.size > 1
    val canGoForward: Boolean get() = activeCanGoForward

    /** How many tabs (layers) are open; the top bar shows a switcher button once this is 2 or more. */
    val tabCount: Int get() = layers.size

    /** A snapshot of each layer for the tab switcher, captured when a layer is backgrounded or shown. */
    val thumbnails = mutableStateMapOf<WebView, Bitmap>()

    /** Snapshots [w] (must be the attached, on-screen layer — others have no size) into [thumbnails]. */
    fun captureThumb(w: WebView?) {
        w ?: return
        if (w.width <= 0 || w.height <= 0) return
        val maxW = 480
        val scale = if (w.width > maxW) maxW.toFloat() / w.width else 1f
        val bw = (w.width * scale).toInt().coerceAtLeast(1)
        val bh = (w.height * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.scale(scale, scale)
        c.translate(-w.scrollX.toFloat(), -w.scrollY.toFloat())
        w.draw(c)
        thumbnails[w] = bmp
    }

    /** Refreshes the on-screen tab's thumbnail; call just before showing the switcher. */
    fun refreshActiveThumb() = captureThumb(active)

    /** Brings [w] to the front so it becomes the shown tab, snapshotting the one we leave. */
    fun switchTo(w: WebView) {
        if (active === w || !layers.contains(w)) return
        captureThumb(active)
        layers.remove(w)
        layers.add(w)
        syncNav()
    }

    /** Closes tab [w] (never the last one) and reveals whatever is now on top. */
    fun closeTab(w: WebView) {
        if (layers.size <= 1 || !layers.contains(w)) return
        layers.remove(w)
        thumbnails.remove(w)
        destroyLater(w)
        syncNav()
    }

    /** Loads [input] as a fresh navigation in the active layer, creating the root layer if needed. */
    fun open(input: String) {
        val url = toUrl(input)
        val wv = active ?: newLayer().also { layers.add(it) }
        loading = true
        progress = 0f
        wv.loadUrl(url)
    }

    fun reload() = active?.reload() ?: Unit
    fun goForward() = active?.goForward() ?: Unit
    fun home() = open(WebShortcuts.KARNATAKA_LAND_RECORDS)

    fun toggleDesktop() {
        desktop = !desktop
        active?.let { setDesktop(it, desktop); it.reload() }
    }

    /** Handles a Back press. Returns true if the browser consumed it; false = let the app go back. */
    fun onBack(): Boolean {
        val top = active ?: return false
        if (top.canGoBack()) {
            top.goBack()
            return true
        }
        if (layers.size > 1) {
            val closed = layers.removeAt(layers.lastIndex)
            destroyLater(closed)
            syncNav()
            return true
        }
        return false
    }

    /** Re-reads nav/title/url from whatever layer is now on top (after a push or pop). */
    private fun syncNav() {
        val a = active
        activeCanGoBack = a?.canGoBack() == true
        activeCanGoForward = a?.canGoForward() == true
        a?.url?.let { currentUrl = it }
        pageTitle = a?.title.orEmpty()
        loading = false
    }

    fun dispose() {
        layers.toList().forEach { destroyLater(it) }
        layers.clear()
        thumbnails.clear()
    }

    /** Detaches a layer from the view tree now and destroys it after the current frame (safe teardown). */
    private fun destroyLater(w: WebView) {
        (w.parent as? ViewGroup)?.removeView(w)
        w.stopLoading()
        w.webChromeClient = null
        Handler(Looper.getMainLooper()).post { w.destroy() }
    }

    // ---- one shared pair of clients, used by every layer; they act on the layer passed as `view`.
    private val client = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (view !== active) return
            loading = true
            progress = 0f
            if (url != null) currentUrl = url
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (view !== active) return
            loading = false
            if (url != null) currentUrl = url
            pageTitle = view?.title.orEmpty()
            // On the Google Images / web home page, put the cursor in the search box.
            if (url != null && url.contains("google.") && (url.contains("imghp") || url.contains("webhp"))) {
                view?.requestFocus()
                view?.evaluateJavascript(
                    "(function(){var e=document.querySelector('textarea[name=q],input[name=q]');" +
                        "if(e){e.focus();e.click();}})();",
                    null,
                )
            }
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            if (view !== active) return
            activeCanGoBack = view?.canGoBack() == true
            activeCanGoForward = view?.canGoForward() == true
            if (url != null) currentUrl = url
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
    }

    private val chrome = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (view !== active) return
            progress = newProgress / 100f
            if (newProgress >= 100) loading = false
        }

        override fun onReceivedTitle(view: WebView?, t: String?) {
            if (view === active) pageTitle = t.orEmpty()
        }

        // A link / button that opens in a new window: push a new layer and show it. Back closes it.
        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            captureThumb(active) // snapshot the opener while it is still on screen
            val child = newLayer()
            layers.add(child)
            loading = true
            progress = 0f
            transport.webView = child
            resultMsg.sendToTarget()
            syncNav()
            return true
        }

        // The site called window.close(): drop that layer and reveal the one beneath it.
        override fun onCloseWindow(window: WebView?) {
            if (window == null || layers.size <= 1) return
            if (layers.remove(window)) {
                destroyLater(window)
                syncNav()
            }
        }
    }

    private fun newLayer(): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        if (desktop) setDesktop(this, true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        webViewClient = client
        webChromeClient = chrome
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
    }
}
