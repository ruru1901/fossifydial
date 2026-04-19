package org.fossify.phone.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.fossify.phone.R
import org.fossify.phone.engine.BackgroundSound
import org.fossify.phone.engine.BackgroundSoundEngine
import org.fossify.phone.models.VoiceEffect
import org.fossify.phone.debug.BugReporter

/**
 * A bound + started foreground service that owns the real-time voice-processing
 * pipeline.  Consumers interact with it in two ways:
 *
 *  1. **Lifecycle** — via the static [start] / [stop] helpers (called from
 *     [CallService] when a call becomes ACTIVE or is removed/disconnected).
 *
 *  2. **Effect selection** — via [setEffect] on the [LocalBinder] instance that
 *     [CallActivity] receives after binding.
 */
class VoiceProcessingService : Service() {

    // ── Binder ──────────────────────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        fun getService(): VoiceProcessingService = this@VoiceProcessingService
    }

    private val binder = LocalBinder()

    // ── State ────────────────────────────────────────────────────────────────

    @Volatile private var currentEffect: VoiceEffect = VoiceEffect.None
    private lateinit var bgEngine: BackgroundSoundEngine

    // ── Service lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        BugReporter.ensureChannel(this)
        bgEngine = BackgroundSoundEngine()
        Log.d(TAG, "onCreate")
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                stopProcessing()
                stopSelf()
            }
            else -> {
                // normal start - begin audio processing
                startProcessing()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        stopProcessing()
        bgEngine.release()
        Log.d(TAG, "onDestroy")
    }

    // ── Pipeline lifecycle ────────────────────────────────────────────────────

    /**
     * Start audio capture and background mixer. Called from [CallService]
     * when call becomes [Call.STATE_ACTIVE].
     */
    fun startProcessing() {
        try {
            startAudioCapture()
            bgEngine.start(this)
            Log.d(TAG, "startProcessing")
        } catch (e: Exception) {
            BugReporter.report(this, "VoiceProcessingService.startProcessing", e.message ?: "Unknown error")
        }
    }

    /**
     * Stop audio capture and background mixer. Called when call ends.
     */
    fun stopProcessing() {
        try {
            bgEngine.stop()
            stopAudioCapture()
            Log.d(TAG, "stopProcessing")
        } catch (e: Exception) {
            BugReporter.report(this, "VoiceProcessingService.stopProcessing", e.message ?: "Unknown error")
        }
    }

    // ── Public API (called on the bound instance from CallActivity) ──────────

    /**
     * Apply the chosen [effect] to the live audio pipeline.
     * Safe to call from any thread.
     */
    fun setEffect(effect: VoiceEffect) {
        if (currentEffect == effect) return
        Log.d(TAG, "setEffect ${currentEffect::class.simpleName} → ${effect::class.simpleName}")
        currentEffect = effect
        applyEffect(effect)
        updateNotification()
    }

    fun getCurrentEffect(): VoiceEffect = currentEffect

    /**
     * Change background ambience sound.
     */
    fun setBackground(sound: BackgroundSound) {
        bgEngine.setSound(sound, -15f)
    }

    /**
     * Adjust background ambience volume.
     */
    fun setBackgroundVolume(volumeDb: Float) {
        bgEngine.setVolume(volumeDb)
    }

    // ── Pipeline (stub — replace with AudioRecord/AudioTrack + DSP logic) ───

    private fun applyEffect(effect: VoiceEffect) {
        // TODO: forward effect to real-time DSP / voice-processing pipeline
        Log.d(TAG, "applyEffect: $effect")
    }

    private fun startAudioCapture() {
        // TODO: initialize AudioRecord with proper audio source and start capture thread
        Log.d(TAG, "startAudioCapture")
    }

    private fun stopAudioCapture() {
        // TODO: stop AudioRecord, join capture thread, release resources
        Log.d(TAG, "stopAudioCapture")
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        ensureChannel()
        val effectLabel = getString(currentEffect.labelRes)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phone_vector)
            .setContentTitle(getString(R.string.voice_processing_notification_title))
            .setContentText(getString(R.string.voice_processing_notification_text, effectLabel))
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.voice_processing_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
                nm.createNotificationChannel(channel)
            }
        }
    }

    // ── Companion — static start / stop helpers ──────────────────────────────

    companion object {
        private const val TAG = "VoiceProcessingService"
        private const val CHANNEL_ID = "voice_processing"
        private const val NOTIFICATION_ID = 9821
        private const val ACTION_STOP = "org.fossify.phone.ACTION_VOICE_PROCESSING_STOP"

        /**
         * Start the service as a foreground service.
         * Called from [CallService] when the call state becomes [Call.STATE_ACTIVE].
         */
        fun start(context: Context) {
            val intent = Intent(context, VoiceProcessingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the service.
         * Called from [CallService] on [Call.STATE_DISCONNECTED] or [onCallRemoved].
         */
        fun stop(context: Context) {
            val intent = Intent(context, VoiceProcessingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /** Build a bind [Intent] for use in [Context.bindService]. */
        fun bindIntent(context: Context) = Intent(context, VoiceProcessingService::class.java)
    }
}
