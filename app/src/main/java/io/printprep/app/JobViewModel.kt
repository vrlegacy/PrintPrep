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
import io.printprep.app.model.ColorMode
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
    data object Printer : Screen
    data object Diagnostics : Screen
    data object Settings : Screen
}

/** App update status, shown in the Home banner and on the Settings screen. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: io.printprep.app.update.Release) : UpdateState
    data class Failed(val message: String) : UpdateState
}

class JobViewModel(app: Application) : AndroidViewModel(app) {

    val store = ImageStore(app)
    private val exporter = Exporter(app, store)
    private val prefs = app.getSharedPreferences("printprep", android.content.Context.MODE_PRIVATE)

    /** USB printer and print jobs. */
    val print = io.printprep.app.print.PrintController.get(app, store)

    val items = mutableStateListOf<JobItem>()

    var settings by mutableStateOf(
        // New jobs default to Black and white (ink saver follows, as elsewhere); switchable per job.
        PrintSettings(
            reverseBackOrder = prefs.getBoolean("reverseBackOrder", false),
            rotateBack180 = prefs.getBoolean("rotateBack180", false),
            lastPageFirst = prefs.getBoolean("lastPageFirst", true),
        ).withColor(ColorMode.GRAYSCALE)
    )
        private set
    var screen by mutableStateOf<Screen>(Screen.Home)
        private set

    /**
     * Screens below the current one, oldest first. [navigateTo] pushes the current screen here on
     * the way to a new one, and [back] pops it, so Back returns to wherever the user actually came
     * from (e.g. browser → print editor → Back → browser), not a fixed Home screen.
     */
    private val backStack = mutableListOf<Screen>()

    /** Go to [target], remembering the current screen so Back comes back to it. */
    private fun navigateTo(target: Screen) {
        if (target == screen) return
        backStack.add(screen)
        screen = target
    }

    /** Go to [target] as a fresh root, clearing the Back history (used by Home resets). */
    private fun resetTo(target: Screen) {
        backStack.clear()
        screen = target
    }

    /** True when Back from the current screen would return to the in-app browser (web / image / pahani). */
    val previousIsWeb: Boolean get() = backStack.lastOrNull() is Screen.Web

    /** The four bottom-navigation destinations, in order. */
    val tabs = listOf(Screen.Home, Screen.Editor, Screen.Printer, Screen.Settings)

    /** True on the top-level destinations, where the bottom navigation bar is shown. */
    val onTopLevel: Boolean get() = screen in tabs

    /**
     * Switches bottom-navigation tab. Home is the start destination, so Back from any other tab
     * returns to Home (and from Home it leaves the app), like a standard bottom-nav app.
     */
    fun selectTab(target: Screen) {
        if (target == screen) return
        backStack.clear()
        if (target != Screen.Home) backStack.add(Screen.Home)
        screen = target
    }
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
        if (openEditor) navigateTo(Screen.Editor)
        importing = true
        viewModelScope.launch {
            val wasEmpty = items.isEmpty()
            val result = store.importUris(uris)
            items.addAll(result.items)
            if (wasEmpty) applyDefaultsFor(result.items)
            importing = false
            if (result.failed.isNotEmpty()) message = "Couldn't open: ${result.failed.joinToString()}"
            if (openEditor && items.isEmpty()) resetTo(Screen.Home)
            onAdded(result.items.size)
        }
    }

    // ---------------- in-app browser ----------------

    /** Short status line shown at the bottom of the browser ("Added 2 pages to print"). */
    var webNotice by mutableStateOf<String?>(null)

    /** True while the browser is downloading a picked image or a site download. */
    var webBusy by mutableStateOf(false)
        private set

    /**
     * The one live browser session. Held here (not in the screen) so it survives leaving the browser
     * for the editor/Home and coming back, with no reload. Created lazily by the web screen.
     */
    private var webSession: io.printprep.app.ui.WebSession? = null

    /** Lazily creates, then reuses, the single live browser session. */
    fun ensureWebSession(context: android.content.Context): io.printprep.app.ui.WebSession =
        webSession ?: io.printprep.app.ui.WebSession(context, this).also { webSession = it }

    /** Set when a specific URL must load into the live session (a shared link), rather than resuming it. */
    var webForceLoadUrl by mutableStateOf<String?>(null)

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

    /**
     * Opens the in-app browser. By default it resumes the live session if one exists (reopening a
     * launcher keeps you where you were); [forceLoad] makes it load [url] instead — used for a link
     * shared into the app, which should go to that page.
     */
    fun openWeb(url: String, forceLoad: Boolean = false) {
        if (forceLoad || webSession?.isEmpty != false) webForceLoadUrl = url
        navigateTo(Screen.Web(url))
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
        navigateTo(Screen.EditItem(id))
    }

    fun goToEditor() {
        navigateTo(if (items.isEmpty()) Screen.Home else Screen.Editor)
    }

    /**
     * Returns to the previous screen. Entries that no longer make sense are skipped — the print
     * editor with an empty job, or an item page whose item has since been removed — falling back to
     * Home when the history runs out.
     */
    fun back() {
        while (backStack.isNotEmpty()) {
            val previous = backStack.removeAt(backStack.lastIndex)
            val stale = (previous is Screen.Editor && items.isEmpty()) ||
                (previous is Screen.EditItem && items.none { it.id == previous.itemId })
            if (!stale) {
                screen = previous
                return
            }
        }
        screen = Screen.Home
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

    fun printNow() = print.print(plan(), settings, isUserJob = true)

    fun startDuplexSetupTest() = print.startSetupTest(settings)

    fun answerDuplexSetup(backOnCorrectSheet: Boolean, backUpsideDown: Boolean) {
        settings = print.applySetupAnswers(settings, backOnCorrectSheet, backUpsideDown)
        savePrinterPrefs()
    }

    fun openDiagnostics() {
        navigateTo(Screen.Diagnostics)
    }

    fun openSettings() {
        navigateTo(Screen.Settings)
    }

    // ---------------- app updates ----------------

    var update by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    /** Check GitHub for a new version each time the app opens. */
    var checkUpdatesOnOpen by mutableStateOf(prefs.getBoolean("checkUpdatesOnOpen", true))
        private set

    /** The build the user said "Not now" to; its banner stays hidden. */
    private var dismissedBuild = prefs.getInt("dismissedBuild", 0)

    val currentBuild: Int get() = io.printprep.app.update.Updater.currentBuild(getApplication())

    fun changeCheckUpdatesOnOpen(on: Boolean) {
        checkUpdatesOnOpen = on
        prefs.edit().putBoolean("checkUpdatesOnOpen", on).apply()
    }

    /** Called once per app start. */
    fun onAppOpened() {
        if (checkUpdatesOnOpen) checkForUpdate(fromUser = false)
    }

    /** Whether the Home banner should show for the current state. */
    val showUpdateBanner: Boolean
        get() = when (val u = update) {
            is UpdateState.Available -> u.release.build != dismissedBuild
            else -> false
        }

    fun checkForUpdate(fromUser: Boolean) {
        if (update is UpdateState.Checking) return
        update = UpdateState.Checking
        viewModelScope.launch {
            val r = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    io.printprep.app.update.Updater.checkForUpdate(getApplication())
                }
            }
            update = r.fold(
                onSuccess = { rel -> if (rel == null) UpdateState.UpToDate else UpdateState.Available(rel) },
                onFailure = { e -> if (fromUser) UpdateState.Failed(e.message ?: "Couldn't check for updates.") else UpdateState.Idle },
            )
        }
    }

    /** "Not now": hide the banner for this version (a newer one will show again). */
    fun dismissUpdate() {
        (update as? UpdateState.Available)?.let {
            dismissedBuild = it.release.build
            prefs.edit().putInt("dismissedBuild", dismissedBuild).apply()
        }
        update = UpdateState.Idle
    }

    /** Opens the new version's APK in the browser; the user installs it from the download. */
    fun downloadAndInstall() {
        val release = (update as? UpdateState.Available)?.release ?: return
        io.printprep.app.update.Updater.openDownload(getApplication(), release)
        message = "Your browser is downloading PrintPrep build ${release.build}. When it finishes, tap the download and choose Install (or Update)."
    }

    override fun onCleared() {
        // The print controller outlives this screen on purpose: a running job carries on in the background.
        webSession?.dispose()
        webSession = null
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
        if (screen is Screen.EditItem) back()
    }

    fun newJob() {
        items.clear()
        settings = PrintSettings(
            reverseBackOrder = settings.reverseBackOrder,
            rotateBack180 = settings.rotateBack180,
            lastPageFirst = settings.lastPageFirst,
        ).withColor(ColorMode.GRAYSCALE)
        lastExport = null
        store.clear()
        resetTo(Screen.Home)
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
