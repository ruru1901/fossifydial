package org.fossify.phone.engine.effects

import kotlin.math.*

// ─────────────────────────────────────────────────────────────────────────────
// VoiceEffect — base interface
// ─────────────────────────────────────────────────────────────────────────────

interface VoiceEffect {
    fun process(input: ShortArray): ShortArray
    val displayName: String
}

// ─────────────────────────────────────────────────────────────────────────────
// PassthroughEffect
// ─────────────────────────────────────────────────────────────────────────────

class PassthroughEffect : VoiceEffect {
    override val displayName = "Normal"
    override fun process(input: ShortArray): ShortArray = input
}

// ─────────────────────────────────────────────────────────────────────────────
// FormantShiftFilter — 2nd-order IIR biquad EQ
//
// WHY THIS EXISTS:
//   Pitch shifting alone = chipmunk / helium effect.
//   Real voices don't just shift pitch — the entire vocal tract resonance
//   (formants F1, F2, F3) changes too. A woman's vocal tract is physically
//   shorter than a man's, so her formants sit at higher frequencies.
//
//   This filter independently adjusts the formant region using peaking EQ
//   and shelf filters, making the transformation sound like a real different
//   person rather than a sped-up version of you.
// ─────────────────────────────────────────────────────────────────────────────

class FormantShiftFilter(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double
) {
    private var x1 = 0.0; private var x2 = 0.0
    private var y1 = 0.0; private var y2 = 0.0

    fun reset() { x1=0.0; x2=0.0; y1=0.0; y2=0.0 }

    fun processSample(x: Double): Double {
        val y = b0*x + b1*x1 + b2*x2 - a1*y1 - a2*y2
        x2=x1; x1=x; y2=y1; y1=y
        return y
    }

    companion object {
        /** Peaking EQ: boost/cut at freqHz with gainDb and Q bandwidth */
        fun peakingEq(freqHz: Double, gainDb: Double, q: Double, sr: Int): FormantShiftFilter {
            val w0 = 2.0 * PI * freqHz / sr
            val A  = 10.0.pow(gainDb / 40.0)
            val alpha = sin(w0) / (2.0 * q)
            val c = cos(w0)
            val a0 = 1.0 + alpha/A
            return FormantShiftFilter(
                (1.0+alpha*A)/a0, (-2.0*c)/a0, (1.0-alpha*A)/a0,
                (-2.0*c)/a0, (1.0-alpha/A)/a0
            )
        }

        /** High shelf: boost/cut everything above freqHz */
        fun highShelf(freqHz: Double, gainDb: Double, sr: Int): FormantShiftFilter {
            val w0 = 2.0 * PI * freqHz / sr
            val A  = 10.0.pow(gainDb / 40.0)
            val c  = cos(w0)
            val alpha = sin(w0)/2.0 * sqrt((A+1.0/A)*(1.0/0.9071-1.0)+2.0)
            val a0 = (A+1)-(A-1)*c+2*sqrt(A)*alpha
            return FormantShiftFilter(
                (A*((A+1)+(A-1)*c+2*sqrt(A)*alpha))/a0,
                (-2*A*((A-1)+(A+1)*c))/a0,
                (A*((A+1)+(A-1)*c-2*sqrt(A)*alpha))/a0,
                (2*((A-1)-(A+1)*c))/a0,
                ((A+1)-(A-1)*c-2*sqrt(A)*alpha)/a0
            )
        }

        /** Low shelf: boost/cut everything below freqHz */
        fun lowShelf(freqHz: Double, gainDb: Double, sr: Int): FormantShiftFilter {
            val w0 = 2.0 * PI * freqHz / sr
            val A  = 10.0.pow(gainDb / 40.0)
            val c  = cos(w0)
            val alpha = sin(w0)/2.0 * sqrt((A+1.0/A)*(1.0/0.9071-1.0)+2.0)
            val a0 = (A+1)+(A-1)*c+2*sqrt(A)*alpha
            return FormantShiftFilter(
                (A*((A+1)-(A-1)*c+2*sqrt(A)*alpha))/a0,
                (2*A*((A-1)-(A+1)*c))/a0,
                (A*((A+1)-(A-1)*c-2*sqrt(A)*alpha))/a0,
                (-2*((A-1)+(A+1)*c))/a0,
                ((A+1)+(A-1)*c-2*sqrt(A)*alpha)/a0
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PitchShiftEffect — TD-PSOLA + independent formant correction
//
// pitchFactor  : scales fundamental frequency (F0)
//                >1.0 = higher pitch, <1.0 = lower pitch
// formantShift : scales vocal tract resonances independently
//                >1.0 = shorter vocal tract (female/child)
//                <1.0 = longer vocal tract (deep male)
// ─────────────────────────────────────────────────────────────────────────────

class PitchShiftEffect(
    val pitchFactor: Float,
    val formantShift: Float = 1.0f,
    override val displayName: String = "Custom",
    private val sampleRate: Int = 16000
) : VoiceEffect {

    private val maxFrame  = 4096
    private val floatIn   = FloatArray(maxFrame)
    private val floatOut  = FloatArray(maxFrame * 2)
    private val outBuf    = ShortArray(maxFrame * 2)

    private val minPeriod = (sampleRate * 0.004f).toInt()
    private val maxPeriod = (sampleRate * 0.020f).toInt()
    private val defPeriod = (sampleRate * 0.008f).toInt()

    // Build formant EQ chain based on how much we're shifting the vocal tract
    private val formantFilters: List<FormantShiftFilter> by lazy {
        if (abs(formantShift - 1.0f) < 0.05f) return@lazy emptyList()
        val gain = if (formantShift > 1.0f) 5.5 else -4.5
        listOf(
            FormantShiftFilter.peakingEq((700.0  * formantShift).coerceIn(150.0, 4000.0), gain,      1.2, sampleRate),
            FormantShiftFilter.peakingEq((1200.0 * formantShift).coerceIn(400.0, 5500.0), gain,      1.0, sampleRate),
            FormantShiftFilter.peakingEq((2600.0 * formantShift).coerceIn(800.0, 7000.0), gain*0.6,  0.9, sampleRate),
        )
    }

    override fun process(input: ShortArray): ShortArray {
        val len = input.size.coerceAtMost(maxFrame)
        for (i in 0 until len) floatIn[i] = input[i] / 32768f

        val outLen = psola(floatIn, len, floatOut, pitchFactor)

        if (formantFilters.isNotEmpty()) {
            for (i in 0 until outLen) {
                var s = floatOut[i].toDouble()
                for (f in formantFilters) s = f.processSample(s)
                floatOut[i] = s.toFloat().coerceIn(-1f, 1f)
            }
        }

        val result = if (outLen <= outBuf.size) outBuf else ShortArray(outLen)
        for (i in 0 until outLen) {
            result[i] = (floatOut[i] * 32768f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return if (outLen == result.size) result else result.copyOf(outLen)
    }

    // TD-PSOLA re-synthesis
    private fun psola(input: FloatArray, inputLen: Int, output: FloatArray, factor: Float): Int {
        output.fill(0f, 0, (inputLen * 1.6f).toInt().coerceAtMost(output.size))
        var inPos = 0; var outPos = 0
        while (inPos < inputLen) {
            val period    = estimatePeriod(input, inPos, inputLen)
            val grainSize = (period * 2).coerceAtMost(maxPeriod * 2)
            val grain     = extractGrain(input, inPos, grainSize, inputLen)
            hannWindow(grain)
            val end = (outPos + grain.size).coerceAtMost(output.size)
            for (i in outPos until end) output[i] += grain[i - outPos]
            outPos += (period / factor).roundToInt().coerceAtLeast(1)
            inPos  += period
            if (outPos >= output.size) break
        }
        return outPos.coerceAtMost(output.size)
    }

    private fun estimatePeriod(s: FloatArray, start: Int, len: Int): Int {
        val aLen = (maxPeriod * 2).coerceAtMost(len - start)
        if (aLen < minPeriod * 2) return defPeriod
        var best = defPeriod; var bestC = Float.NEGATIVE_INFINITY
        for (lag in minPeriod..maxPeriod) {
            if (start + lag + aLen/2 >= len) break
            var c = 0f
            for (i in 0 until aLen/2) c += s[start+i] * s[start+i+lag]
            if (c > bestC) { bestC = c; best = lag }
        }
        return best
    }

    private fun extractGrain(s: FloatArray, start: Int, size: Int, len: Int): FloatArray {
        val n = size.coerceAtMost(len - start).coerceAtLeast(1)
        return FloatArray(n) { i -> s[start+i] }
    }

    private fun hannWindow(g: FloatArray) {
        val n = g.size; if (n <= 1) return
        for (i in g.indices) g[i] *= (0.5f*(1f - cos(2.0*PI*i/(n-1)).toFloat()))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RobotEffect — ring modulation at a carrier frequency
// Completely destroys vocal naturalness → sci-fi / automated voice
// ─────────────────────────────────────────────────────────────────────────────

class RobotEffect(
    private val carrierHz: Float = 120f,
    private val sampleRate: Int = 16000
) : VoiceEffect {
    override val displayName = "Robot"
    private var phase = 0.0
    private val phaseInc = 2.0 * PI * carrierHz / sampleRate

    override fun process(input: ShortArray): ShortArray {
        val out = ShortArray(input.size)
        for (i in input.indices) {
            val mod = sin(phase).toFloat()
            phase += phaseInc
            if (phase > 2.0*PI) phase -= 2.0*PI
            out[i] = ((input[i]/32768f * mod) * 32768f)
                .roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// WhisperEffect — removes fundamental, adds breath noise texture
// ─────────────────────────────────────────────────────────────────────────────

class WhisperEffect(private val sampleRate: Int = 16000) : VoiceEffect {
    override val displayName = "Whisper"
    private val lowCut    = FormantShiftFilter.lowShelf(500.0,  -14.0, sampleRate)
    private val midCut    = FormantShiftFilter.peakingEq(220.0, -10.0, 1.2, sampleRate)
    private val breathBoost = FormantShiftFilter.peakingEq(4000.0, 9.0, 0.8, sampleRate)

    override fun process(input: ShortArray): ShortArray {
        val out = ShortArray(input.size)
        for (i in input.indices) {
            var s = input[i] / 32768.0
            s = lowCut.processSample(s)
            s = midCut.processSample(s)
            val breath = (Math.random() * 2.0 - 1.0) * 0.045  // breath air texture
            s = breathBoost.processSample(s + breath)
            out[i] = (s * 32768.0).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ElderlyEffect — tremor (vibrato AM) + HF hearing loss curve + creak
// ─────────────────────────────────────────────────────────────────────────────

class ElderlyEffect(private val sampleRate: Int = 16000) : VoiceEffect {
    override val displayName = "Elderly"

    private val pitchStage = PitchShiftEffect(0.88f, 0.88f, "base", sampleRate)
    private val hfLoss     = FormantShiftFilter.highShelf(3200.0, -9.0,  sampleRate)
    private val creak      = FormantShiftFilter.peakingEq(170.0,  3.5,  2.0, sampleRate)

    private var tremPhase  = 0.0
    private val tremRate   = 5.5   // Hz — natural elderly tremor
    private val tremDepth  = 0.013f

    override fun process(input: ShortArray): ShortArray {
        val pitched = pitchStage.process(input)
        val out = ShortArray(pitched.size)
        for (i in pitched.indices) {
            // Amplitude modulation = tremor
            tremPhase += 2.0 * PI * tremRate / sampleRate
            val trem = 1.0f + tremDepth * sin(tremPhase).toFloat()
            var s = (pitched[i] * trem).toInt().coerceIn(-32768, 32767) / 32768.0
            s = hfLoss.processSample(s)
            s = creak.processSample(s)
            out[i] = (s * 32768.0).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GainEffect — compensate for energy change after pitch shift
// ─────────────────────────────────────────────────────────────────────────────

class GainEffect(private val gainDb: Float = 0f) : VoiceEffect {
    override val displayName = "Gain ${gainDb}dB"
    private val linear = 10.0.pow(gainDb / 20.0).toFloat()
    override fun process(input: ShortArray): ShortArray {
        if (abs(gainDb) < 0.1f) return input
        return ShortArray(input.size) { i ->
            (input[i] * linear).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ChainEffect — run effects in sequence
// ─────────────────────────────────────────────────────────────────────────────

class ChainEffect(private vararg val effects: VoiceEffect) : VoiceEffect {
    override val displayName = effects.joinToString(" + ") { it.displayName }
    override fun process(input: ShortArray): ShortArray {
        var buf = input
        for (e in effects) buf = e.process(buf)
        return buf
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// InlineEqEffect — helper to apply a set of biquad filters inline
// Used inside the ChainEffect calls in VoicePresets without creating
// a new named class every time
// ─────────────────────────────────────────────────────────────────────────────

class InlineEqEffect(
    override val displayName: String,
    private vararg val filters: FormantShiftFilter
) : VoiceEffect {
    override fun process(input: ShortArray): ShortArray {
        val out = ShortArray(input.size)
        for (i in input.indices) {
            var s = input[i] / 32768.0
            for (f in filters) s = f.processSample(s)
            out[i] = (s * 32768.0).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// VoicePresets
//
// Wire VoicePresets.all() to your VoicePickerSheet RecyclerView.
// Call service.setEffect(VoicePresets.WOMAN) on card tap.
//
// Realism breakdown:
//   WOMAN    pitch×1.25 + formant×1.20 + air shelf   → short vocal tract
//   GIRL     pitch×1.55 + formant×1.35 + no chest    → child vocal tract
//   BOY      pitch×1.10 + formant×1.05 + mid boost   → pre-pubescent male
//   MAN      pitch×0.78 + formant×0.88 + chest boost → adult male
//   DEEP_MAN pitch×0.62 + formant×0.78 + sub boost   → broadcaster / villain
//   ELDERLY  tremor + HF loss + slight pitch drop
//   WHISPER  no fundamental + breath noise
//   ROBOT    ring modulation at 120 Hz
// ─────────────────────────────────────────────────────────────────────────────

object VoicePresets {

    val NORMAL: VoiceEffect
        get() = PassthroughEffect()

    /**
     * WOMAN — adult female voice
     * Raises pitch ~25%, shortens vocal tract (raises formants 20%),
     * adds subtle air shelf above 6 kHz for breath naturalness.
     */
    val WOMAN: VoiceEffect
        get() = ChainEffect(
            PitchShiftEffect(1.25f, 1.20f, "Woman"),
            InlineEqEffect("Woman EQ",
                FormantShiftFilter.highShelf(6000.0, 2.5, 16000),     // air / breath
                FormantShiftFilter.lowShelf(200.0, -3.0, 16000)        // reduce chest
            )
        )

    /**
     * GIRL — child female voice
     * Much higher pitch, significantly shorter vocal tract,
     * cuts all chest resonance, adds brightness.
     */
    val GIRL: VoiceEffect
        get() = ChainEffect(
            PitchShiftEffect(1.55f, 1.35f, "Girl"),
            InlineEqEffect("Girl EQ",
                FormantShiftFilter.lowShelf(250.0, -10.0, 16000),     // kill chest
                FormantShiftFilter.highShelf(5000.0, 3.5, 16000),     // bright & airy
                FormantShiftFilter.peakingEq(1800.0, 2.0, 1.0, 16000) // clarity
            )
        )

    /**
     * BOY — young male, pre-pubescent
     * Slightly above neutral pitch, thin formants, retains some chest.
     */
    val BOY: VoiceEffect
        get() = ChainEffect(
            PitchShiftEffect(1.10f, 1.05f, "Boy"),
            InlineEqEffect("Boy EQ",
                FormantShiftFilter.lowShelf(200.0, -5.0, 16000),
                FormantShiftFilter.peakingEq(1800.0, 2.5, 1.0, 16000),
                FormantShiftFilter.highShelf(4500.0, 1.5, 16000)
            )
        )

    /**
     * MAN — standard adult male voice
     * Lowers pitch 22%, lowers formants (longer vocal tract),
     * boosts chest resonance, slightly cuts harsh mids.
     */
    val MAN: VoiceEffect
        get() = ChainEffect(
            PitchShiftEffect(0.78f, 0.88f, "Man"),
            InlineEqEffect("Man EQ",
                FormantShiftFilter.lowShelf(260.0, 4.5, 16000),        // chest
                FormantShiftFilter.peakingEq(3000.0, -3.0, 0.8, 16000) // less harsh
            )
        )

    /**
     * DEEP_MAN — very deep male voice (broadcaster / villain)
     * Maximum pitch drop, strong sub-bass boost, rolled-off highs.
     */
    val DEEP_MAN: VoiceEffect
        get() = ChainEffect(
            PitchShiftEffect(0.62f, 0.78f, "Deep Man"),
            GainEffect(2.5f),   // compensate energy lost at very low pitch
            InlineEqEffect("Deep EQ",
                FormantShiftFilter.lowShelf(180.0, 7.0, 16000),        // sub boom
                FormantShiftFilter.peakingEq(400.0, 3.0, 1.0, 16000),  // body
                FormantShiftFilter.highShelf(5000.0, -7.0, 16000)      // mellow top
            )
        )

    /**
     * ELDERLY — old person voice
     * Tremor (5.5 Hz AM), age-related HF loss, slight pitch drop and creak.
     */
    val ELDERLY: VoiceEffect
        get() = ElderlyEffect()

    /**
     * WHISPER — hushed, secretive voice
     * Removes voiced fundamental, adds realistic breath-noise texture.
     */
    val WHISPER: VoiceEffect
        get() = WhisperEffect()

    /**
     * ROBOT — sci-fi / machine voice
     * Ring modulation at 120 Hz carrier.
     */
    val ROBOT: VoiceEffect
        get() = RobotEffect()

    /**
     * All presets in UI display order.
     * Use this to populate VoicePickerSheet RecyclerView.
     */
    fun all(): List<VoiceEffect> = listOf(
        NORMAL, WOMAN, GIRL, BOY, MAN, DEEP_MAN, ELDERLY, WHISPER, ROBOT
    )
}