package org.fossify.phone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.phone.debug.BugReporter

class BugReporterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BugReporter.ACTION_COPY_LOGS -> {
                BugReporter.copyLogsToClipboard(context)
            }
        }
    }
}