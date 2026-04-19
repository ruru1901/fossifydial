package org.antigravity.phone.engine

import android.content.Context
import android.util.Log
import org.antigravity.phone.services.BackgroundSound
import java.io.InputStream

/**
 * BackgroundMixer
 *
 * Loads a looping background WAV from raw resources and mixes it
 * sample-by-sample with the processed voice audio before it is
 * written to STREAM_VOICE_CALL.
 *
 * Add these files to res/raw/:
 *   bg_traffic.wav  bg_rain.wav  bg_office.wav  bg_concert.wav
 * All must be: 16-bit PCM, mono, 16000 Hz (matches the pipeline sample rate).
 */
class BackgroundMixer(
    private val context: Context,
    private val sampleRate: Int
) {
    companion object {
        private const val TAG = "BackgroundMixer"
        private const val WAV_HEADER_BYTES = 44
    }

    @Volatile private var currentSound: BackgroundSound = BackgroundSound.NONE
    @Volatile private var volume: Float = 0.4f
    @Volatile private var running = false

    private var bgSamples: ShortArray = ShortArray(0)
    private var bgPos: Int = 0

    fun start() {
        running = true
        loadSound(currentSound)
    }

    fun stop() {
        running = false
        bgSamples = ShortArray(0)
        bgPos = 0
    }

    fun setSound(sound: BackgroundSound) {
        currentSound = sound
        loadSound(sound)
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
    }

    /**
     * Mix background into [voice] in-place.
     * Returns the same array (modified) for zero allocation in hot path.
     */
    fun mix(voice: ShortArray): ShortArray {
        if (!running || bgSamples.isEmpty() || currentSound == BackgroundSound.NONE) return voice
        for (i in voice.indices) {
            val bg = (bgSamples[bgPos] * volume).toInt()
            val mixed = (voice[i].toInt() + bg).coerceIn(-32768, 32767)
            voice[i] = mixed.toShort()
            bgPos = (bgPos + 1) % bgSamples.size // loop
        }
        return voice
    }

    private fun loadSound(sound: BackgroundSound) {
        bgPos = 0
        val resId = sound.rawResId
        if (resId == null) {
            bgSamples = ShortArray(0)
            return
        }
        try {
            val stream: InputStream = context.resources.openRawResource(resId)
            val bytes = stream.readBytes()
            stream.close()
            // Skip 44-byte WAV header, read remaining as 16-bit PCM shorts
            val pcmBytes = bytes.copyOfRange(WAV_HEADER_BYTES, bytes.size)
            bgSamples = ShortArray(pcmBytes.size / 2) { i ->
                ((pcmBytes[i * 2 + 1].toInt() shl 8) or (pcmBytes[i * 2].toInt() and 0xFF)).toShort()
            }
            Log.d(TAG, "Loaded background: ${sound.name} (${bgSamples.size} samples)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load background sound ${sound.name}", e)
            bgSamples = ShortArray(0)
        }
    }
}