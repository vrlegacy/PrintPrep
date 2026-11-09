package io.printprep.app.print

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.printprep.app.MainActivity
import io.printprep.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while a print job runs (so closing the app doesn't stop printing) and shows
 * the job in the notification panel: pages done / left, printer notices, Cancel, and the
 * "flip the stack → Continue" step of manual two-sided printing.
 */
class PrintService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ctl = PrintController.current()
        when (intent?.action) {
            ACTION_CANCEL -> ctl?.cancel()
            ACTION_CONTINUE -> ctl?.continueAfterFlip()
        }
        if (ctl == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannels()
        // Must go foreground straight away, whatever the state.
        val ok = runCatching {
            ServiceCompat.startForeground(
                this, ONGOING_ID, build(ctl.ui) ?: progress("Printing", "Starting…", 0, 0, null),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.isSuccess
        if (!ok) {
            // Printing still works while the app is open; it just can't carry on in the background.
            stopSelf()
            return START_NOT_STICKY
        }
        if (watcher == null) {
            watcher = scope.launch {
                snapshotFlow { ctl.ui }.collect { ui -> show(ui) }
            }
        }
        return START_NOT_STICKY
    }

    private var lastKey = ""
    private var lastAt = 0L

    private fun show(ui: PrintUi) {
        val nm = getSystemService(NotificationManager::class.java)
        when (ui) {
            is PrintUi.Running, is PrintUi.WaitingFlip -> {
                // Progress changes on every USB chunk; Android drops floods of updates, so send a few per second at most.
                val key = if (ui is PrintUi.Running) "${ui.label}|${ui.page}|${ui.stage}|${ui.notice}" else "flip"
                val now = System.currentTimeMillis()
                if (key == lastKey && now - lastAt < 700) return
                lastKey = key; lastAt = now
                build(ui)?.let { nm.notify(ONGOING_ID, it) }
            }
            is PrintUi.Done, is PrintUi.Failed, PrintUi.SetupQuestions -> {
                if (canNotify()) nm.notify(RESULT_ID, result(ui))
                finish()
            }
            PrintUi.Idle -> finish()
        }
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notifications

    private fun build(ui: PrintUi): Notification? = when (ui) {
        is PrintUi.Running -> {
            val line = when {
                ui.page == 0 -> "Starting…"
                else -> "Page ${ui.page} of ${ui.total} · ${ui.done} done, ${ui.remaining} left"
            }
            progress(ui.label, line, (ui.fraction * 100).toInt(), 100, ui.notice)
        }
        is PrintUi.WaitingFlip -> {
            val text = "Fronts are printed. Take the whole stack out, turn it over (top edge in first) and put it back in the rear tray" +
                (ui.blankBackSheet?.let { ", including sheet $it" } ?: "") + ". Then tap Continue."
            NotificationCompat.Builder(this, CH_JOB)
                .setSmallIcon(R.drawable.ic_print)
                .setContentTitle("Flip the stack for the back sides")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openApp())
                .addAction(0, "Continue", action(ACTION_CONTINUE))
                .addAction(0, "Cancel", action(ACTION_CANCEL))
                .build()
        }
        else -> null
    }

    private fun progress(title: String, line: String, value: Int, max: Int, notice: String?): Notification {
        val b = NotificationCompat.Builder(this, CH_JOB)
            .setSmallIcon(R.drawable.ic_print)
            .setContentTitle(if (notice != null) "Printer needs attention" else title)
            .setContentText(notice ?: line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (notice != null) "$line\n$notice" else line))
            .setProgress(max, value, max == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp())
            .addAction(0, "Cancel", action(ACTION_CANCEL))
        return b.build()
    }

    private fun result(ui: PrintUi): Notification {
        val (title, text) = when (ui) {
            is PrintUi.Done -> "Printing finished" to ui.message
            is PrintUi.Failed -> "Printing stopped" to ui.message
            else -> "Two-sided test printed" to "Open PrintPrep to answer two quick questions about the test sheets."
        }
        return NotificationCompat.Builder(this, CH_RESULT)
            .setSmallIcon(R.drawable.ic_print)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(a: String): PendingIntent = PendingIntent.getService(
        this, a.hashCode(), Intent(this, PrintService::class.java).setAction(a),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun canNotify() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_JOB, "Print progress", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Pages printed, printer problems and the flip step for two-sided printing"
                setSound(null, null)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RESULT, "Print finished", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a print job finishes or stops"
            },
        )
    }

    companion object {
        private const val CH_JOB = "print_job"
        private const val CH_RESULT = "print_result"
        private const val ONGOING_ID = 1
        private const val RESULT_ID = 2
        private const val ACTION_CANCEL = "io.printprep.app.print.CANCEL"
        private const val ACTION_CONTINUE = "io.printprep.app.print.CONTINUE"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, PrintService::class.java)) }
        }
    }
}
