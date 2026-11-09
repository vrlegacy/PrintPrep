package io.printprep.app.print

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.printprep.app.data.ImageStore
import io.printprep.app.engine.LayoutEngine
import io.printprep.app.engine.PrintPlan
import io.printprep.app.engine.Sheet
import io.printprep.app.model.ColorMode
import io.printprep.app.model.Duplex
import io.printprep.app.model.FitMode
import io.printprep.app.model.ItemKind
import io.printprep.app.model.JobItem
import io.printprep.app.model.MarginPreset
import io.printprep.app.model.PrintSettings
import io.printprep.app.model.Quality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** What the print sheet shows. */
sealed interface PrintUi {
    data object Idle : PrintUi
    data class Running(
        val label: String,
        val page: Int,
        val total: Int,
        val stage: String,
        val fraction: Float,
        val notice: String? = null,
        /** Pages the printer has finished in this pass. */
        val done: Int = 0,
    ) : PrintUi {
        val remaining: Int get() = (total - done).coerceAtLeast(0)
    }
    /** Fronts are done: the user flips the stack, then continues. */
    data class WaitingFlip(val backs: List<Int>, val blankBackSheet: Int?, val isSetupTest: Boolean) : PrintUi
    /** The setup test has printed: ask the two questions. */
    data object SetupQuestions : PrintUi
    data class Done(val message: String) : PrintUi
    data class Failed(val message: String) : PrintUi
}

/**
 * Runs print jobs (including manual two-sided and the setup test) and exposes their state to the UI.
 * One per app process ([get]), not per screen, so a job keeps going after the app is closed; the
 * [PrintService] keeps the process alive and mirrors [ui] in a notification.
 */
class PrintController private constructor(private val app: Application, private val store: ImageStore) {

    companion object {
        @Volatile private var instance: PrintController? = null

        fun get(app: Application, store: ImageStore): PrintController =
            instance ?: synchronized(this) { instance ?: PrintController(app, store).also { instance = it } }

        /** The running instance, if the app has created one (used by the notification buttons). */
        fun current(): PrintController? = instance
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val printer = UsbPrinter(app)
    private val runner = PrintJobRunner(app, store, printer)
    private val cancelFlag = AtomicBoolean(false)

    var ui by mutableStateOf<PrintUi>(PrintUi.Idle)
        private set

    /** The most recent job that reached the printer (shown in Printer diagnostics). */
    var lastReport by mutableStateOf<JobReport?>(null)
        private set

    private var pendingBacks: Pending? = null

    private data class Pending(val sheets: List<Sheet>, val settings: PrintSettings, val rotate: Boolean, val isSetupTest: Boolean, val total: Int)

    val busy: Boolean get() = ui is PrintUi.Running

    fun reset() {
        if (!busy) ui = PrintUi.Idle
    }

    fun cancel() {
        cancelFlag.set(true)
        if (ui is PrintUi.WaitingFlip) {
            pendingBacks = null
            ui = PrintUi.Failed("Two-sided printing stopped after the fronts. The backs weren't printed.")
        }
    }

    /** Prints a plan with the given settings: one pass, or fronts then (after a flip) backs. */
    fun print(plan: PrintPlan, s: PrintSettings, isSetupTest: Boolean = false) {
        if (busy || plan.sheets.isEmpty()) return
        val two = plan.twoSided
        if (two == null) {
            val order = if (s.lastPageFirst) plan.sheets.reversed() else plan.sheets
            run(order, s, rotate = false, label = "Printing") { r ->
                ui = PrintUi.Done(sentMessage(r))
            }
            return
        }
        val byNumber = plan.sheets.associateBy { it.number }
        val fronts = two.pass1.mapNotNull { byNumber[it] }
        val template = plan.sheets.first()
        val backs = two.pass2.mapNotNull { n ->
            if (n == io.printprep.app.engine.TwoSidedPlan.BLANK_BACK) Sheet(0, template.paperW, template.paperH, emptyList()) else byNumber[n]
        }
        run(fronts, s, rotate = false, label = "Pass 1 of 2 · fronts") { r ->
            if (backs.isEmpty()) {
                ui = if (isSetupTest) PrintUi.SetupQuestions else PrintUi.Done(sentMessage(r))
            } else {
                pendingBacks = Pending(backs, s, two.rotateBacks, isSetupTest, plan.sheets.size)
                ui = PrintUi.WaitingFlip(two.pass2, two.blankBackSheet, isSetupTest)
            }
        }
    }

    /** After the user has flipped and reloaded the stack. */
    fun continueAfterFlip() {
        val p = pendingBacks ?: return
        pendingBacks = null
        run(p.sheets, p.settings, rotate = p.rotate, label = "Pass 2 of 2 · backs") { r ->
            ui = if (p.isSetupTest) PrintUi.SetupQuestions else PrintUi.Done(sentMessage(r) + " Both sides are done.")
        }
    }

    /** Prints the 4-page, 2-sheet test with the current two-sided settings, in black ink at low quality. */
    fun startSetupTest(current: PrintSettings) {
        if (busy) return
        scope.launch {
            val items = withContext(Dispatchers.Default) {
                TestPages.duplexTest().map { (name, bmp) -> store.saveBitmapItem(bmp, name, ItemKind.WEB_PAGE).also { bmp.recycle() } }
            }
            val s = PrintSettings(
                fit = FitMode.FIT, color = ColorMode.BLACK_ONLY, quality = Quality.INK_SAVER, margin = MarginPreset.DEFAULT,
                duplex = Duplex.LONG_EDGE, reverseBackOrder = current.reverseBackOrder, rotateBack180 = current.rotateBack180,
            )
            print(LayoutEngine.plan(items, s), s, isSetupTest = true)
        }
    }

    /** Turns the user's answers into corrected two-sided settings. */
    fun applySetupAnswers(current: PrintSettings, backOnCorrectSheet: Boolean, backUpsideDown: Boolean): PrintSettings {
        ui = PrintUi.Done("Two-sided printing is set up. These settings are saved for next time.")
        return current.copy(
            reverseBackOrder = if (backOnCorrectSheet) current.reverseBackOrder else !current.reverseBackOrder,
            rotateBack180 = if (backUpsideDown) !current.rotateBack180 else current.rotateBack180,
        )
    }

    /** One page with colour bars and text, at standard quality on plain paper. */
    fun printTestPage() {
        if (busy) return
        scope.launch {
            val item = withContext(Dispatchers.Default) {
                val bmp = TestPages.printerTest()
                store.saveBitmapItem(bmp, "Test page", ItemKind.WEB_PAGE).also { bmp.recycle() }
            }
            val s = PrintSettings(margin = MarginPreset.DEFAULT, quality = Quality.STANDARD, lastPageFirst = false)
            print(LayoutEngine.plan(listOf(item), s), s)
        }
    }

    /**
     * Prints the colour-match sheet (see [TestPages.colourMatch]) on the paper and quality the user
     * prints with, using [photo] from their job if there is one. The sheet is printed without tone.
     */
    fun printColourMatch(photo: JobItem?, quality: Quality, paperType: io.printprep.app.model.PaperType) {
        if (busy) return
        scope.launch {
            val item = withContext(Dispatchers.Default) {
                val pic = photo?.let { runCatching { store.decode(it, 900, useCache = false) }.getOrNull() }
                val bmp = TestPages.colourMatch(pic)
                pic?.recycle()
                store.saveBitmapItem(bmp, "Colour match", ItemKind.WEB_PAGE).also { bmp.recycle() }
            }
            val s = PrintSettings(
                margin = MarginPreset.DEFAULT, quality = quality, paperType = paperType,
                lastPageFirst = false, toneOverride = io.printprep.app.model.PrintTone.A,
            )
            print(LayoutEngine.plan(listOf(item), s), s)
        }
    }

    /** Sends a raw printer file (e.g. made on a PC) unchanged. For diagnostics. */
    fun sendRawFile(uri: Uri) {
        if (busy) return
        cancelFlag.set(false)
        ui = PrintUi.Running("Sending file", 1, 1, "Copying", 0f)
        PrintService.start(app)
        scope.launch {
            val tmp = File(app.cacheDir, "raw_upload.prn")
            try {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                        ?: error("Cannot open the file")
                    val size = tmp.length().coerceAtLeast(1)
                    printer.busy = true
                    try {
                        printer.send(
                            tmp,
                            onProgress = { sent -> ui = PrintUi.Running("Sending file", 1, 1, "Sending", sent.toFloat() / size) },
                            shouldContinue = { !cancelFlag.get() },
                        )
                    } finally {
                        printer.busy = false
                    }
                }
                ui = PrintUi.Done("File sent (${tmp.length()} bytes). Check the printer.")
            } catch (e: Exception) {
                ui = PrintUi.Failed(if (cancelFlag.get()) "Cancelled." else e.message ?: "Sending failed.")
            } finally {
                tmp.delete()
            }
        }
    }

    fun close() = printer.close()

    /** After a cancel or failure: drop any half-sent page from the printer's USB buffer. */
    private suspend fun clearPrinter() = withContext(Dispatchers.IO) {
        printer.cancelOnPrinter()
        printer.refresh()
    }

    private fun sentMessage(r: JobReport): String =
        if (r.confirmedByPrinter) "Printed ${r.pages} page(s). The printer confirmed each one." else "Sent ${r.pages} page(s) to the printer (%.1f MB). It should start printing within a few seconds. ".format(r.bytes / 1_048_576.0) +
            "If nothing happens, open Printer diagnostics → Last print job."

    private fun run(sheets: List<Sheet>, s: PrintSettings, rotate: Boolean, label: String, onDone: (JobReport) -> Unit) {
        cancelFlag.set(false)
        ui = PrintUi.Running(label, 0, sheets.size, "Starting", 0f)
        PrintService.start(app)
        scope.launch {
            try {
                val report = runner.printSheets(sheets, s, rotate, { p ->
                    ui = PrintUi.Running(label, p.page, p.total, p.stage, p.fraction, p.notice, p.printed)
                }) { cancelFlag.get() }
                lastReport = report
                printer.refresh()
                onDone(report)
            } catch (e: PrinterReportedProblem) {
                lastReport = e.report
                pendingBacks = null
                printer.refresh()
                ui = PrintUi.Failed(e.message ?: "The printer reports a problem.")
            } catch (e: PrintCancelled) {
                pendingBacks = null
                clearPrinter()
                ui = PrintUi.Failed(
                    "Printing cancelled. If a sheet is still half-way in the printer, press the printer's Stop button to push it out " +
                        "before printing again.",
                )
            } catch (e: Throwable) {
                pendingBacks = null
                clearPrinter()
                ui = PrintUi.Failed(
                    (e.message ?: "Printing failed.") +
                        " If the printer's light is blinking or a sheet is stuck, remove it, press the Stop button and try again.",
                )
            }
        }
    }
}
