package org.fossify.phone.debug

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.fossify.phone.BuildConfig
import org.fossify.phone.R
import org.fossify.phone.receivers.BugReporterReceiver
import java.io.BufferedReader
import java.io.InputStreamReader

object BugReporter {
    private const val LOGCAT_CMD = "logcat -d -t 200 -s VoiceProcessingService:* BackgroundSoundEngine:* AudioTrack:* AudioRecord:*"
    private const val CHANNEL_ID = "antigravity_debug"
    private const val NOTIFICATION_ID = 9999
    private var cachedLogs: String = ""

    fun report(context: Context, component: String, error: String) {
        if (!BuildConfig.DEBUG) {
            return
        }

        val deviceInfo = "${Build.MODEL} (API ${Build.VERSION.SDK_INT})"
        cachedLogs = captureLogs()

        ensureChannel(context)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_MUTABLE)

        val copyIntent = Intent(context, BugReporterReceiver::class.java).apply {
            action = ACTION_COPY_LOGS
        }
        val copyPendingIntent = PendingIntent.getBroadcast(context, 0, copyIntent, PendingIntent.FLAG_MUTABLE)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phone_vector)
            .setContentTitle("$component failed")
            .setContentText("$error\n$deviceInfo")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$error\n$deviceInfo\n\n$cachedLogs"))
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_copy_vector, "Copy Full Log", copyPendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun captureLogs(): String {
        return try {
            val process = Runtime.getRuntime().exec(LOGCAT_CMD)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val stringBuilder = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                stringBuilder.appendLine(line)
            }
            reader.close()
            stringBuilder.toString()
        } catch (e: Exception) {
            "Failed to capture logs: ${e.message}"
        }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "Debug", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Debug notifications for bug reporting"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun copyLogsToClipboard(context: Context) {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = android.content.ClipData.newPlainText("Bug Report Logs", cachedLogs)
        clipboardManager.setPrimaryClip(clip)
    }

    fun getCapturedLogs(): String = cachedLogs

    const val ACTION_COPY_LOGS = "org.fossify.phone.ACTION_COPY_LOGS"
}