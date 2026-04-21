package org.fossify.phone.helpers
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.sin

class CallToneTester {
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

    private val beepRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) {
                return
            }

            playBeep()
            handler.postDelayed(this, BEEP_INTERVAL_MS)
        }
    }

    fun start() {
        if (isRunning) {
            return
        }

        isRunning = true
        handler.post(beepRunnable)
    }

    fun stop() {
        if (!isRunning) {
            return
        }

        isRunning = false
        handler.removeCallbacks(beepRunnable)
    }

    private fun playBeep() {
        val sampleCount = SAMPLE_RATE * BEEP_DURATION_MS / 1000
        val pcmBuffer = ShortArray(sampleCount)

        for (i in pcmBuffer.indices) {
            val angle = 2.0 * Math.PI * i * BEEP_FREQUENCY_HZ / SAMPLE_RATE
            pcmBuffer[i] = (sin(angle) * BEEP_VOLUME).toInt().toShort()
        }

        val audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            pcmBuffer.size * 2,
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        try {
            audioTrack.write(pcmBuffer, 0, pcmBuffer.size)
            audioTrack.play()
            handler.postDelayed(
                { audioTrack.release() },
                (BEEP_DURATION_MS + TRACK_RELEASE_BUFFER_MS).toLong()
            )
        } catch (_: Exception) {
            audioTrack.release()
        }
    }

    companion object {
        private const val SAMPLE_RATE = 8000
        private const val BEEP_FREQUENCY_HZ = 1000.0
        private const val BEEP_DURATION_MS = 300
        private const val BEEP_INTERVAL_MS = 5000L
        private const val TRACK_RELEASE_BUFFER_MS = 100
        private const val BEEP_VOLUME = 20000
    }
}
