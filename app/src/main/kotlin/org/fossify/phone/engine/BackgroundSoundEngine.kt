package org.fossify.phone.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.*
import kotlin.math.*
import org.fossify.phone.debug.BugReporter

// ─────────────────────────────────────────────────────────────────────────────
//  BackgroundSound — all available background ambiences
// ─────────────────────────────────────────────────────────────────────────────
enum class BackgroundSound(val displayName: String) {
    NONE("None"),
    RAIN("Rain"),
    CAFE("Café Noise"),
    TRAFFIC("Traffic"),
    OFFICE("Office"),
    CROWD("Crowd"),
    NATURE("Nature"),
    WIND("Wind"),
    WHITE_NOISE("White Noise"),
    FAN("Fan")
}

// ─────────────────────────────────────────────────────────────────────────────
//  BackgroundSoundEngine — synthesises all sounds in real-time, no files needed
//  Usage:
//      val engine = BackgroundSoundEngine()
//      engine.setSound(BackgroundSound.RAIN, volumeDb = -12f)
//      engine.start(context)
//      ...
//      engine.stop()
//      engine.release()
// ─────────────────────────────────────────────────────────────────────────────
class BackgroundSoundEngine {

    private val sampleRate = 44100
    private val bufferFrames = 4096
    private val bufferBytes = bufferFrames * 2           // PCM_16BIT = 2 bytes/sample

    private var audioTrack: AudioTrack? = null
    private var renderJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var currentSound: BackgroundSound = BackgroundSound.NONE
    private var volumeLinear: Float = 0.5f               // 0.0 – 1.0 linear amplitude

    // per-synth state (phase accumulators, filter states, RNG)
    private val state = SynthState()

    // ── Public API ────────────────────────────────────────────────────────────

    fun setSound(sound: BackgroundSound, volumeDb: Float = -12f) {
        currentSound = sound
        volumeLinear = dbToLinear(volumeDb)
        state.reset()
    }

    fun setVolume(volumeDb: Float) {
        volumeLinear = dbToLinear(volumeDb)
    }

    fun start(context: android.content.Context) {
        if (renderJob?.isActive == true) return
        buildAudioTrack()
        audioTrack?.play()
        val ctx = context
        renderJob = scope.launch {
            try {
                val buf = ShortArray(bufferFrames)
                while (isActive) {
                    if (currentSound == BackgroundSound.NONE) {
                        buf.fill(0)
                    } else {
                        synthesise(currentSound, buf, bufferFrames, state, volumeLinear)
                    }
                    val written = audioTrack?.write(buf, 0, bufferFrames) ?: -1
                    if (written < 0) {
                        BugReporter.report(ctx, "AudioTrack", "Write failed with code $written")
                    }
                }
            } catch (e: Exception) {
                BugReporter.report(ctx, "BackgroundSoundEngine", e.message ?: "Coroutine crash")
            }
        }
    }

    fun stop() {
        renderJob?.cancel()
        renderJob = null
        audioTrack?.pause()
        audioTrack?.flush()
    }

    fun release() {
        stop()
        scope.cancel()
        audioTrack?.release()
        audioTrack = null
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun buildAudioTrack() {
        audioTrack?.release()
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    // ── Master synthesiser dispatcher ─────────────────────────────────────────

    private fun synthesise(
        sound: BackgroundSound,
        buf: ShortArray,
        frames: Int,
        s: SynthState,
        vol: Float
    ) {
        when (sound) {
            BackgroundSound.RAIN       -> synthRain(buf, frames, s, vol)
            BackgroundSound.CAFE       -> synthCafe(buf, frames, s, vol)
            BackgroundSound.TRAFFIC    -> synthTraffic(buf, frames, s, vol)
            BackgroundSound.OFFICE     -> synthOffice(buf, frames, s, vol)
            BackgroundSound.CROWD      -> synthCrowd(buf, frames, s, vol)
            BackgroundSound.NATURE     -> synthNature(buf, frames, s, vol)
            BackgroundSound.WIND       -> synthWind(buf, frames, s, vol)
            BackgroundSound.WHITE_NOISE-> synthWhiteNoise(buf, frames, s, vol)
            BackgroundSound.FAN        -> synthFan(buf, frames, s, vol)
            BackgroundSound.NONE       -> buf.fill(0)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  RAIN — filtered white noise with occasional drip transients
    // ─────────────────────────────────────────────────────────────────────────
    private fun synthRain(buf: ShortArray, frames: Int, s: SynthState, vol: Float) {
        // 2-pole low-pass at ~4 kHz (rain hiss), plus gentle high-pass to remove DC
        val fc = 4000.0 / sampleRate
        val q  = 0.6
        val k  = tan(PI * fc)
        val norm = 1.0 / (1.0 + k / q + k * k)
        val a0 = k * k * norm; val a1 = 2 * a0
        val b1 = 2 * (k * k - 1) * norm; val b2 = (1 - k / q + k * k) * norm

        for (i in 0 until frames) {
            val noise = s.rng.nextFloat() * 2f - 1f          // white noise
            val filtered = (a0 * noise + a1 * s.rainX1 + a0 * s.rainX2
                    - b1 * s.rainY1 - b2 * s.rainY2).toFloat()
            s.rainX2 = s.rainX1; s.rainX1 = noise.toDouble()
            s.rainY2 = s.rainY1; s.rainY1 = filtered.toDouble()

            // occasional drip — short high-freq burst
            if (s.rng.nextFloat() < 0.0003f) s.dripEnv = 0.6f
            val drip = s.dripEnv * (s.rng.nextFloat() * 2f - 1f)
            s.dripEnv *= 0.85f

            val out = (filtered * 0.7f + drip * 0.3f) * vol
            buf[i] = clamp16(out)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CAFÉ — layered murmur: band-pass filtered noise at speech formant range
    //  + slow amplitude modulation to simulate overlapping voices
    // ─────────────────────────────────────────────────────────────────────────
    private fun synthCafe(buf: ShortArray, frames: Int, s: SynthState, vol: Float) {
        val lfoInc = (2.0 * PI * 0.8 / sampleRate).toFloat() // 0.8 Hz crowd swell
        for (i in 0 until frames) {
            val noise = s.rng.nextFloat() * 2f - 1f

            // band-pass 300 Hz – 3 kHz (speech band)
            val bp = bpFilter(noise, 1200.0, 0.5, sampleRate, s.cafeBpState)

            // slow LFO modulation
            s.cafeLfo += lfoInc
            val lfo = (sin(s.cafeLfo.toDouble()) * 0.3 + 0.7).toFloat()

            // add occasional "laugh" burst
            if (s.rng.nextFloat() < 0.0001f) s.cafeburstEnv = 0.8f
            val burst = s.cafeburstEnv * bpFilter(
                s.rng.nextFloat() * 2f - 1f, 900.0, 2.0, sampleRate, s.cafeBurstState
            )
            s.cafeburstEnv *= 0.92f

            val out = (bp * lfo * 0.6f + burst * 0.3f) * vol
            buf[i] = clamp16(out)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TRAFFIC — low rumble + occasional horn + tyre hiss
    // ─────────────────────────────────────────────────────────────────────────
    private fun synthTraffic(buf: ShortArray, frames: Int, s: SynthState, vol: Float) {
        val rumbleInc = (2.0 * PI * 80.0 / sampleRate)
        val hissInc   = (2.0 * PI * 3500.0 / sampleRate)
        for (i in 0 until frames) {
            s.trafficPhase += rumbleInc
            val rumble = sin(s.trafficPhase) * 0.3 +
                    sin(s.trafficPhase * 1.5 + 0.3) * 0.15 +
                    (s.rng.nextFloat() * 2f - 1f) * 0.25          // add noise

            // tyre hiss — high-freq shaped noise
            val hiss = (s.rng.nextFloat() * 2f - 1f) * 0.12f *
                    (sin(s.trafficPhase * 44.0f).toFloat().coerceIn(-1f, 1f).absoluteValue)

            // horn event (rare)
            if (s.rng.nextFloat() < 0.00005f) { s.hornEnv = 1.0f; s.hornPhase = 0.0 }
            var horn = 0f
            if (s.hornEnv > 0.001f) {
                s.hornPhase += 2.0 * PI * 440.0 / sampleRate
                horn = (sin(s.hornPhase) * s.hornEnv).toFloat()
                s.hornEnv *= 0.9998f
            }

            val out = (rumble.toFloat() * 0.5f + hiss + horn * 0.4f) * vol
            buf[i] = clamp16(out)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  OFFICE — keyboard clicks + HVAC hum + distant printer
    // ─────────────────────────────────────────────────────────────────────────
    private fun synthOffice(buf: ShortArray, frames: Int, s: SynthState, vol: Float) {
        val hvacInc = 2.0 * PI * 200.0 / sampleRate
        for (i in 0 until frames) {
            s.officePhase += hvacInc
            val hvac = (sin(s.officePhase) * 0.08 +
                    (s.rng.nextFloat() * 2.0 - 1.0) * 0.06).toFloat()   // HVAC + fan noise

            // keyboard click
            if (s.rng.nextFloat() < 0.0008f) s.clickEnv = 0.9f
            val click = s.clickEnv * (s.rng.nextFloat() * 2f - 1f) * 0.4f
            s.clickEnv *= 0.60f

            val out = (hvac + click) * vol
            buf[i] = clamp16(out)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CROWD — dense modulated band-pass noise, slower swells
    // ─────────────────────────────────────────────────────────────────────────
    private fun synthCrowd(buf: ShortArray, frames: Int, s: SynthState, vol: Float) {
        val swellInc = (2.0 * PI * 0.3 / sampleRate).toFloat()
        for (i in 0 until frames) {
            val noise = s.rng.nextFloat() * 2f - 1f
            val bp = bpFilter(noise, 800.0, 0.4, sampleRate, s.crowdBpState)
            s.crowdLfo += swellInc
            val swell = (sin(s.crowdLfo.toDouble()) * 0.4 + 0.6).toFloat()
            val out = bp * swell * vol * 1.2f
            buf[i] = clamp16(out)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  NATURE — birds (sine chirps) + breeze (band-pass noise) + crickets
    // ─────────────────────────────────────────────────────────────────────────
    private fun synthNature(buf: ShortArray, frames: Int, s: SynthState, vol: Float) {
        for (i in 0 until frames) {
            // breeze
            val breeze = bpFilter(
                s.rng.nextFloat() * 2f - 1f, 600.0, 0.8, sampleRate, s.natureBreezeState
            ) * 0.3f

            // cricket oscillator
            s.cricketPhase += 2.0 * PI * 4000.0 / sampleRate
            val cricket = (sin(s.cricketPhase) * s.cricketEnv * 0.15).toFloat()
            if (s.rng.nextFloat() < 0.001f) s.cricketEnv = if (s.cricketEnv > 0.05) 0.0f else 0.5f

            // bird chirp (rare short sine sweep)
            if (s.rng.nextFloat() < 0.00008f) { s.birdEnv = 1.0f; s.birdFreq = 2000.0 }
            var bird = 0f
            if (s.birdEnv > 0.001f) {
                s.birdPhase += 2.0 * PI * s.birdFreq / sampleRate
                s.birdFreq += 8.0          // upward sweep
                bird = (sin(s.birdPhase) * s.birdEnv).toFloat()
                s.birdEnv *= 0.9985f
            }

            val out = (breeze + cricket + bird * 0.3f) * vol
            buf[i] = clamp16(out)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  WIND — slow modulated high-pass noise
    // ─────────────────────────────────────────────────────────────────────────
    private fun synthWind(buf: ShortArray, frames: Int, s: SynthState, vol: Float) {
        val gustInc = (2.0 * PI * 0.15 / sampleRate).toFloat()
        for (i in 0 until frames) {
            val noise = s.rng.nextFloat() * 2f - 1f
            val hp = hpFilter(noise, 800.0, sampleRate, s.windHpState)
            s.windLfo += gustInc
            val gust = (sin(s.windLfo.toDouble()) * 0.45 + 0.55).toFloat()
            val out = hp * gust * vol * 1.3f
            buf[i] = clamp16(out)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  WHITE NOISE — flat spectrum, useful for speech privacy masking
    // ─────────────────────────────────────────────────────────────────────────
    private fun synthWhiteNoise(buf: ShortArray, frames: Int, s: SynthState, vol: Float) {
        for (i in 0 until frames) {
            buf[i] = clamp16((s.rng.nextFloat() * 2f - 1f) * vol)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  FAN — narrow band noise at fan blade frequency (125 Hz) + harmonics
    // ─────────────────────────────────────────────────────────────────────────
    private fun synthFan(buf: ShortArray, frames: Int, s: SynthState, vol: Float) {
        val fundInc = 2.0 * PI * 125.0 / sampleRate
        for (i in 0 until frames) {
            s.fanPhase += fundInc
            val tone = (sin(s.fanPhase) * 0.4 +
                    sin(s.fanPhase * 2.0) * 0.2 +
                    sin(s.fanPhase * 3.0) * 0.1 +
                    (s.rng.nextFloat() * 2.0 - 1.0) * 0.3).toFloat()
            buf[i] = clamp16(tone * vol * 0.8f)
        }
    }

    // ── DSP helpers ───────────────────────────────────────────────────────────

    /** Transposed direct-form II biquad band-pass */
    private fun bpFilter(
        x: Float, centerHz: Double, q: Double, sr: Int, st: DoubleArray
    ): Float {
        val w0 = 2.0 * PI * centerHz / sr
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)
        val b0 = alpha; val b1 = 0.0; val b2 = -alpha
        val a0 = 1.0 + alpha; val a1 = -2.0 * cosW0; val a2 = 1.0 - alpha
        val y = (b0 / a0 * x + st[0]).toFloat()
        st[0] = b1 / a0 * x - a1 / a0 * y + st[1]
        st[1] = b2 / a0 * x - a2 / a0 * y
        return y
    }

    /** Simple 1-pole high-pass */
    private fun hpFilter(x: Float, cutHz: Double, sr: Int, st: DoubleArray): Float {
        val rc = 1.0 / (2.0 * PI * cutHz)
        val dt = 1.0 / sr
        val alpha = rc / (rc + dt)
        val y = (alpha * (st[0] + x - st[1])).toFloat()
        st[1] = x.toDouble(); st[0] = y.toDouble()
        return y
    }

    private fun dbToLinear(db: Float) = 10f.pow(db / 20f)

    private fun clamp16(f: Float): Short =
        (f * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
}

// ─────────────────────────────────────────────────────────────────────────────
//  SynthState — all per-instance mutable DSP state, reset between sound changes
// ─────────────────────────────────────────────────────────────────────────────
class SynthState {
    val rng = java.util.Random()

    // rain
    var rainX1 = 0.0; var rainX2 = 0.0; var rainY1 = 0.0; var rainY2 = 0.0
    var dripEnv = 0f

    // cafe
    val cafeBpState = DoubleArray(2); val cafeBurstState = DoubleArray(2)
    var cafeLfo = 0f; var cafeburstEnv = 0f

    // traffic
    var trafficPhase = 0.0; var hornEnv = 0f; var hornPhase = 0.0

    // office
    var officePhase = 0.0; var clickEnv = 0f

    // crowd
    val crowdBpState = DoubleArray(2); var crowdLfo = 0f

    // nature
    val natureBreezeState = DoubleArray(2)
    var cricketPhase = 0.0; var cricketEnv = 0f
    var birdEnv = 0f; var birdPhase = 0.0; var birdFreq = 2000.0

    // wind
    val windHpState = DoubleArray(2); var windLfo = 0f

    // fan
    var fanPhase = 0.0

    fun reset() {
        rainX1=0.0; rainX2=0.0; rainY1=0.0; rainY2=0.0; dripEnv=0f
        cafeBpState.fill(0.0); cafeBurstState.fill(0.0); cafeLfo=0f; cafeburstEnv=0f
        trafficPhase=0.0; hornEnv=0f; hornPhase=0.0
        officePhase=0.0; clickEnv=0f
        crowdBpState.fill(0.0); crowdLfo=0f
        natureBreezeState.fill(0.0); cricketPhase=0.0; cricketEnv=0f
        birdEnv=0f; birdPhase=0.0; birdFreq=2000.0
        windHpState.fill(0.0); windLfo=0f
        fanPhase=0.0
    }
}