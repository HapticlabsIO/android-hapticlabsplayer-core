package io.hapticlabs.hapticlabsplayer.core

import android.os.VibrationEffect

interface HasOffset {
    /** Offset from the start of playback in milliseconds */
    val startOffset: Long
}

/** A haptic primitive, such as a click, to play at [startOffset]. */
data class HapticPrimitive(
    /** The primitive's name, such as "click", "tick" or "lowTick" */
    val name: String,
    /** The primitive's intensity, from 0 to 1 */
    val scale: Float,
    override val startOffset: Long
) : HasOffset

/** A vibration effect, ready to play. */
data class LoadedEffect(
    val effect: VibrationEffect,
    override val startOffset: Long
) : HasOffset
