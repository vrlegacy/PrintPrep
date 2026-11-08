package io.printprep.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Receives Android's answers while an update installs; shows its confirmation screen when asked. */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android's "Do you want to update this app?" screen
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)?.let {
                    context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> lastResult = "Updated."
            PackageInstaller.STATUS_FAILURE_ABORTED -> lastResult = "Update cancelled."
            else -> lastResult = when (status) {
                PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                    "The update doesn't match the installed app (it was signed with a different key). Uninstall PrintPrep once, then install the new version."
                PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage for the update."
                PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the update. Allow PrintPrep to install apps in Settings."
                else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The update couldn't be installed."
            }
        }
    }

    companion object {
        /** Last outcome, shown on the Settings screen and the update banner. */
        var lastResult by mutableStateOf<String?>(null)
    }
}
