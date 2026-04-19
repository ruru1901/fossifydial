package org.fossify.phone.models

import androidx.annotation.StringRes
import org.fossify.phone.R

/**
 * Represents a selectable voice-processing effect shown in the call-screen card row.
 */
sealed class VoiceEffect(
    val id: Int,
    @StringRes val labelRes: Int,
) {
    /** No processing — raw mic audio. */
    object None : VoiceEffect(0, R.string.voice_effect_none)

    /** Pitch raised (e.g. chipmunk / high voice). */
    object PitchUp : VoiceEffect(1, R.string.voice_effect_pitch_up)

    /** Pitch lowered (e.g. deep / bass voice). */
    object PitchDown : VoiceEffect(2, R.string.voice_effect_pitch_down)

    /** Robot / vocoder effect. */
    object Robot : VoiceEffect(3, R.string.voice_effect_robot)

    /** Echo / reverb effect. */
    object Echo : VoiceEffect(4, R.string.voice_effect_echo)

    companion object {
        val ALL: List<VoiceEffect> = listOf(None, PitchUp, PitchDown, Robot, Echo)

        fun fromId(id: Int): VoiceEffect = ALL.firstOrNull { it.id == id } ?: None
    }
}
