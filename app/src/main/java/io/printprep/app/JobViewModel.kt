package io.printprep.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.printprep.app.data.ExportResult
import io.printprep.app.data.Exporter
import io.printprep.app.data.ImageStore
import io.printprep.app.data.WebPageCapture
import io.printprep.app.engine.LayoutEngine
import io.printprep.app.engine.PrintPlan
import io.printprep.app.engine.Sheet
import io.printprep.app.engine.SheetRenderer
import io.printprep.app.model.FitMode
import io.printprep.app.model.ItemEdit
import io.printprep.app.model.ItemKind
import io.printprep.app.model.JobItem
import io.printprep.app.model.Presets
import io.printprep.app.model.PrintSettings
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

sealed interface Screen {
    data object Home : Screen
    data object Editor : Screen
    data class EditItem(val itemId: Long) : Screen
    data class Web(val url: String) : Screen
    data object Diagnostics : Screen
}

class JobViewModel(app: Application) : AndroidViewModel(app) {

    val store = ImageStore(app)
    private val exporter = Exporter(app, store)
    private val prefs = app.getSharedPreferences("printprep", android.content.Context.MODE_PRIVATE)

    /** USB printer and print jobs. */
    val print = io.printprep.app.print.PrintController(app, store, viewModelScope)

    val items = mutableStateListOf<JobItem>()

    var settings by mutableStateOf(
        PrintSettings(
            reverseBackOrder = prefs.getBoolean("reverseBackOrder", false),
            rotateBack180 = prefs.getBoolean("rotateBack180", false),
            lastPageFirst = prefs.getBoolean("lastPageFirst", true),
        )
    )
        private set
    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    var importing by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var exportProgress by mutableStateOf<String?>(null)
        private set
    var lastExport by mutableStateOf<ExportResult?>(null)
        private set

    /** Set after a successful save; the UI opens the share sheet for it and clears it. */
    var pendingShare by mutableStateOf<ExportResult?>(null)

    val exporting: Boolean get() = exportProgress != null

    fun plan(): PrintPlan = LayoutEngine.plan(items.toList(), settings)

    // ---------------- import ----------------

    /** [openEditor] false keeps the current screen (used by the in-app browser). */
    fun import(uris: List<Uri>, openEditor: Boolean = true, onAdded: (Int) -> Unit = {}) {
        if (uris.isEmpty()) return
        if (openEditor) screen = Screen.Editor
        importing = true
        viewModelScope.launch {
            val wasEmpty = items.isEmpty()
            val result = store.importUris(uris)
            items.addAll(result.items)
            if (wasEmpty) applyDefaultsFor(result.items)
            importing = false
            if (result.failed.isNotEmpty()) message = "Couldn't open: ${result.failed.joinToString()}"
            if (openEditor && items.isEmpty()) screen = Screen.Home
            onAdded(result.items.size)
        }
    }

    // ---------------- in-app browser ----------------

    /** Short status line shown at the bottom of the browser ("Added 2 pages to print"). */
    var webNotice by mutableStateOf<String?>(null)

    /** True while the browser is downloading a picked image or a site download. */
    var webBusy by mutableStateOf(false)
        private set

    /** The part of the page currently on screen, as one image. Main thread. */
    fun captureVisible(web: WebView, title: String) {
        if (web.width <= 0 || web.height <= 0) return
        val bmp = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(android.graphics.Color.WHITE)
        c.translate(-web.scrollX.toFloat(), -web.scrollY.toFloat())
        web.draw(c)
        val wasEmpty = items.isEmpty()
        val item = store.saveBitmapItem(bmp, "$title · screen", ItemKind.IMAGE)
        bmp.recycle()
        items.add(item)
        if (wasEmpty) settings = settings.copy(layout = Presets.single, fit = FitMode.FIT)
        webNotice = "Added the screen to print · ${items.size} item(s) in the job"
    }

    /**
     * Downloads an image or file from the page (long-press, or a download link on the site) with the
     * page's cookies. [toGallery] saves it to Pictures/PrintPrep instead of adding it to the job.
     */
    fun fetchFromWeb(url: String, userAgent: String?, referer: String?, toGallery: Boolean) {
        webNotice = "Downloading…"
        webBusy = true
        viewModelScope.launch {
          try {
            val dir = java.io.File(getApplication<Application>().cacheDir, "web")
            val result = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    io.printprep.app.data.WebFetch.fetch(url, userAgent, referer, dir, "web_${System.currentTimeMillis()}")
                }
            }
            val fetched = result.getOrElse {
                webNotice = null
                message = "Couldn't download: ${it.message}"
                return@launch
            }
            if (toGallery) {
                webNotice = runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { exporter.saveToGallery(fetched.file, fetched.mime) }
                }.fold({ "Saved to Pictures/PrintPrep" }, { "Couldn't save: ${it.message}" })
                fetched.file.delete()
            } else {
                import(listOf(Uri.fromFile(fetched.file)), openEditor = false) { n ->
                    webNotice = if (n > 0) "Added to print · ${items.size} item(s) in the job" else "That file couldn't be opened."
                }
            }
          } finally {
            webBusy = false
          }
        }
    }

    /** Documents start as 1 page per sheet, fitted; photos keep the current layout. */
    private fun applyDefaultsFor(added: List<JobItem>) {
        if (added.isNotEmpty() && added.all { it.kind != ItemKind.IMAGE }) {
            settings = settings.copy(layout = Presets.single, fit = FitMode.FIT)
        }
    }

    fun openWeb(url: String) {
        screen = Screen.Web(url)
    }

    /** Called on the main thread from the web screen once the page has loaded. */
    fun captureWebPage(web: WebView, title: String) {
        val s = settings
        val usableAspect = (s.paper.heightMm - 2 * s.marginMm) / (s.paper.widthMm - 2 * s.marginMm)
        val wasEmpty = items.isEmpty()
        val added = mutableListOf<JobItem>()
        val pages = runCatching {
            WebPageCapture.capture(web, usableAspect) { bmp, i ->
                added += store.saveBitmapItem(bmp, "$title · p${i + 1}", ItemKind.WEB_PAGE)
            }
        }.getOrElse {
            message = "Couldn't capture the page: ${it.message}"
            0
        }
        if (pages == 0 && added.isEmpty()) {
            if (message == null) message = "The page is empty or hasn't finished loading."
            return
        }
        items.addAll(added)
        if (wasEmpty) applyDefaultsFor(added)
        webNotice = "Added ${added.size} page(s) to print · ${items.size} item(s) in the job"
    }

    // ---------------- navigation ----------------

    fun openItem(id: Long) {
        screen = Screen.EditItem(id)
    }

    fun goToEditor() {
        screen = if (items.isEmpty()) Screen.Home else Screen.Editor
    }

    fun back() {
        screen = when (screen) {
            is Screen.EditItem -> Screen.Editor
            is Screen.Web -> if (items.isEmpty()) Screen.Home else Screen.Editor
            Screen.Diagnostics -> if (items.isEmpty()) Screen.Home else Screen.Editor
            else -> Screen.Home
        }
    }

    // ---------------- editing ----------------

    fun update(change: (PrintSettings) -> PrintSettings) {
        settings = change(settings)
        savePrinterPrefs()
    }

    /** Two-sided calibration and stacking order belong to the printer, so they're remembered. */
    private fun savePrinterPrefs() {
        prefs.edit()
            .putBoolean("reverseBackOrder", settings.reverseBackOrder)
            .putBoolean("rotateBack180", settings.rotateBack180)
            .putBoolean("lastPageFirst", settings.lastPageFirst)
            .apply()
    }

    // ---------------- printing ----------------

    fun printNow() = print.print(plan(), settings)

    fun startDuplexSetupTest() = print.startSetupTest(settings)

    fun answerDuplexSetup(backOnCorrectSheet: Boolean, backUpsideDown: Boolean) {
        settings = print.applySetupAnswers(settings, backOnCorrectSheet, backUpsideDown)
        savePrinterPrefs()
    }

    fun openDiagnostics() {
        screen = Screen.Diagnostics
    }

    override fun onCleared() {
        print.close()
        super.onCleared()
    }

    /** Changes colour mode; print quality follows (Ink saver for grayscale) and can still be changed after. */
    fun setColor(c: io.printprep.app.model.ColorMode) {
        settings = settings.withColor(c)
    }

    fun updateEdit(id: Long, edit: ItemEdit) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = items[i].copy(edit = edit)
    }

    fun move(id: Long, delta: Int) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        val j = (i + delta).coerceIn(0, items.lastIndex)
        if (i == j) return
        val item = items.removeAt(i)
        items.add(j, item)
    }

    fun remove(id: Long) {
        items.removeAll { it.id == id }
        if (screen is Screen.EditItem) screen = if (items.isEmpty()) Screen.Home else Screen.Editor
    }

    fun newJob() {
        items.clear()
        settings = PrintSettings(
            reverseBackOrder = settings.reverseBackOrder,
            rotateBack180 = settings.rotateBack180,
            lastPageFirst = settings.lastPageFirst,
        )
        lastExport = null
        store.clear()
        screen = Screen.Home
    }

    // ---------------- preview & export ----------------

    /** Renders a sheet for the on-screen preview. Call off the main thread. */
    fun renderPreview(sheet: Sheet, s: PrintSettings, widthPx: Int): Bitmap {
        val w = widthPx.coerceIn(100, 1400)
        val k = w / sheet.paperW
        val h = max(1, (sheet.paperH * k).roundToInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val dpi = (k * 25.4f).roundToInt().coerceAtLeast(30)
        SheetRenderer.drawSheet(Canvas(bmp), sheet, s, k, dpi, loader = { item, px -> store.decode(item, px, useCache = true) })
        return bmp
    }

    fun exportPdf() = export("PDF") { plan, name, progress -> exporter.savePdf(plan, settings, name, progress) }

    /** Format chosen automatically from the added media (see [ImageFormat.auto]). */
    fun autoImageFormat(): io.printprep.app.data.ImageFormat = io.printprep.app.data.ImageFormat.auto(items.toList())

    /** [format] null = automatic. */
    fun exportImages(dpi: Int, format: io.printprep.app.data.ImageFormat? = null) {
        val f = format ?: autoImageFormat()
        export("images") { plan, name, progress -> exporter.saveImages(plan, settings, name, dpi, f, progress) }
    }

    private fun export(what: String, job: suspend (PrintPlan, String, (Int, Int) -> Unit) -> ExportResult) {
        if (exporting) return
        val plan = plan()
        if (plan.sheets.isEmpty()) {
            message = "Nothing to save: no pages are selected."
            return
        }
        val name = "PrintPrep_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        exportProgress = "Preparing $what…"
        lastExport = null
        viewModelScope.launch {
            try {
                val result = job(plan, name) { done, total -> exportProgress = "Rendering sheet $done of $total…" }
                lastExport = result
                pendingShare = result
            } catch (e: Throwable) {
                message = "Saving failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                exportProgress = null
            }
        }
    }
}
