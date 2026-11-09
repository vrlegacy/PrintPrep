package io.printprep.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.printprep.app.JobViewModel
import io.printprep.app.engine.PrintPlan
import io.printprep.app.model.Duplex
import io.printprep.app.print.PrintUi
import io.printprep.app.print.PrinterState

private val Green = Color(0xFF2E7D32)
private val Amber = Color(0xFFF9A825)
private val Red = Color(0xFFD32F2F)

/** Status line for the USB printer, usable anywhere. Tapping asks for USB access when needed. */
@Composable
fun PrinterStatusCard(vm: JobViewModel, modifier: Modifier = Modifier) {
    val state = vm.print.printer.state
    LaunchedEffect(Unit) { vm.print.printer.refresh() }
    val (dot, title, detail) = when (state) {
        PrinterState.Disconnected -> Triple(Red, "Printer not connected", "Plug the Canon G2010 into the phone with the OTG cable and switch it on.")
        is PrinterState.NeedsPermission -> Triple(Amber, "${state.name} found", "Allow PrintPrep to use it over USB.")
        is PrinterState.Ready -> {
            val st = state.status
            if (st != null && (st.paperEmpty || st.error)) Triple(Amber, state.name, st.summary)
            else Triple(Green, state.name, "Connected over USB · ready")
        }
    }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(dot, CircleShape))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Hint(detail)
            }
            when (state) {
                is PrinterState.NeedsPermission -> Button(onClick = { vm.print.printer.requestPermission() }) { Text("Allow") }
                else -> TextButton(onClick = { vm.print.printer.refresh() }) { Text("Refresh") }
            }
        }
    }
}

/** Confirm sheet → progress → (flip) → done. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintSheet(vm: JobViewModel, plan: PrintPlan, onDismiss: () -> Unit) {
    val ctl = vm.print
    val ui = ctl.ui
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) { if (ui is PrintUi.Done || ui is PrintUi.Failed) ctl.reset() }

    ModalBottomSheet(
        onDismissRequest = { if (!ctl.busy && ui !is PrintUi.WaitingFlip) { ctl.reset(); onDismiss() } },
        sheetState = sheetState,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (ui) {
                PrintUi.Idle -> Confirm(vm, plan)
                is PrintUi.Running -> Running(ui) { ctl.cancel() }
                is PrintUi.WaitingFlip -> Flip(ui, onContinue = { ctl.continueAfterFlip() }, onCancel = { ctl.cancel() })
                PrintUi.SetupQuestions -> SetupQuestions { right, upside -> vm.answerDuplexSetup(right, upside) }
                is PrintUi.Done -> Finished(ui.message, ok = true) { ctl.reset(); onDismiss() }
                is PrintUi.Failed -> Finished(ui.message, ok = false, onRetry = { ctl.reset() }) { ctl.reset(); onDismiss() }
            }
        }
    }
}

@Composable
private fun Confirm(vm: JobViewModel, plan: PrintPlan) {
    val s = vm.settings
    val ready = vm.print.printer.state is PrinterState.Ready
    Text("Print", style = MaterialTheme.typography.titleLarge)
    PrinterStatusCard(vm)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SummaryRow("Pages", "${plan.sheets.size}" + if (plan.twoSided != null) " on ${plan.paperCount} sheet(s), two-sided" else "")
            SummaryRow("Paper", "${s.paper.label} · ${s.paperType.label}")
            SummaryRow("Quality", s.quality.label)
            SummaryRow("Colour", s.color.label)
            SummaryRow("Copies", "${s.copies}${if (s.copies > 1 && s.collate) " (collated)" else ""}")
            if (plan.twoSided == null) SummaryRow("Order", if (s.lastPageFirst) "Last page first (page 1 ends on top)" else "Page 1 first")
        }
    }
    if (s.duplex != Duplex.OFF) {
        Hint("Two-sided: the fronts print first, then the app tells you how to reload the stack for the backs.")
        OutlinedButton(onClick = { vm.startDuplexSetupTest() }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
            Text("Run two-sided setup test (2 sheets)")
        }
    }
    Button(
        onClick = { vm.printNow() },
        enabled = ready && plan.sheets.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.White),
    ) { Text("Print ${plan.sheets.size} page(s)", fontWeight = FontWeight.SemiBold) }
    if (!ready) Hint("Connect the printer to enable printing. You can still save as PDF from the Quick tab.")
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(0.35f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.65f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Running(r: PrintUi.Running, onCancel: () -> Unit) {
    Text(r.label, style = MaterialTheme.typography.titleLarge)
    Text(
        if (r.page == 0) "Starting…" else "${r.stage} page ${r.page} of ${r.total}",
        style = MaterialTheme.typography.bodyLarge,
    )
    LinearProgressIndicator(progress = { r.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp))
    if (r.page > 0) Text("${r.done} done · ${r.remaining} left", style = MaterialTheme.typography.bodyMedium)
    r.notice?.let {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(it, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
    Hint("Keep the cable connected. You can close the app: printing carries on, with progress and Cancel in the notification panel.")
    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Red),
    ) { Text("Cancel") }
}

@Composable
private fun Flip(f: PrintUi.WaitingFlip, onContinue: () -> Unit, onCancel: () -> Unit) {
    Text("Now flip the stack", style = MaterialTheme.typography.titleLarge)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("1. Wait until the last front has come out.")
            Text("2. Take the printed stack out, keeping it in the same order.")
            Text("3. Turn it over so the printed side faces you, with the top edge still going in first.")
            Text("4. Put it back in the rear tray and tap Continue.")
            f.blankBackSheet?.let { Text("Put back every sheet, including sheet $it — it just goes through blank.", fontWeight = FontWeight.SemiBold) }
            if (f.isSetupTest) Hint("This is the setup test: just follow these steps; the app learns the right order from your answers next.")
        }
    }
    Button(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.White),
    ) { Text("Continue: print the backs", fontWeight = FontWeight.SemiBold) }
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Red)) {
        Text("Stop here")
    }
}

@Composable
private fun SetupQuestions(onAnswer: (backOnCorrectSheet: Boolean, backUpsideDown: Boolean) -> Unit) {
    var correct by remember { mutableStateOf<Boolean?>(null) }
    var upside by remember { mutableStateOf<Boolean?>(null) }
    Text("Check the two test sheets", style = MaterialTheme.typography.titleLarge)
    YesNo("Is \"SHEET 1 BACK\" printed on the back of \"SHEET 1 FRONT\"?", correct) { correct = it }
    YesNo("Is the back page upside down compared with its front (arrows pointing opposite ways)?", upside) { upside = it }
    Button(
        onClick = { onAnswer(correct == true, upside == true) },
        enabled = correct != null && upside != null,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.White),
    ) { Text("Save two-sided settings") }
}

@Composable
private fun YesNo(question: String, value: Boolean?, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(question, style = MaterialTheme.typography.bodyLarge)
        ChoiceChips<Boolean?>("", listOf(true, false), value, { if (it == true) "Yes" else "No" }) { onChange(it == true) }
        if (value == null) Hint("Choose Yes or No")
    }
}

@Composable
private fun Finished(message: String, ok: Boolean, onRetry: (() -> Unit)? = null, onClose: () -> Unit) {
    Text(if (ok) "Done" else "Couldn't print", style = MaterialTheme.typography.titleLarge, color = if (ok) Green else Red)
    Text(message, style = MaterialTheme.typography.bodyLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onRetry != null) OutlinedButton(onClick = onRetry) { Text("Back") }
        Button(onClick = onClose) { Text("Close") }
    }
}
