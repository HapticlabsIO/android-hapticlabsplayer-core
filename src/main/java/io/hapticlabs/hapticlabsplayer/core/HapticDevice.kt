package io.hapticlabs.hapticlabsplayer.core

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.util.Log
import androidx.annotation.RequiresApi
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.round

/**
 * The device's vibrator: what it supports, and effects to play on it.
 */
class HapticDevice(context: Context) {
    private val vibrator = getVibrator(context)

    /**
     * Describes the haptic capabilities of the device.
     */
    val capabilities: HapticCapabilities = hapticCapabilitiesOf(vibrator)

    /**
     * Load primitives as vibration effects, falling back to amplitude waveforms where the device
     * or SDK version doesn't support them.
     *
     * @return The effects of the primitives that could be loaded
     */
    fun loadPrimitives(primitives: List<HapticPrimitive>): List<LoadedEffect> {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Primitives are not supported on this SDK version, falling back.")
            primitives.mapNotNull {
                fallbackOf(it)?.let { vibrationEffect ->
                    LoadedEffect(vibrationEffect, it.startOffset)
                }
            }
        } else {
            primitives.mapNotNull {
                // Map the string to the primitive id
                val primitiveId = when (it.name) {
                    "click" -> VibrationEffect.Composition.PRIMITIVE_CLICK
                    "thud" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        VibrationEffect.Composition.PRIMITIVE_THUD
                    } else {
                        null
                    }

                    "spin" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        VibrationEffect.Composition.PRIMITIVE_SPIN
                    } else {
                        null
                    }

                    "quickRise" -> VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
                    "slowRise" -> VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
                    "quickFall" -> VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
                    "tick" -> VibrationEffect.Composition.PRIMITIVE_TICK
                    "lowTick" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        VibrationEffect.Composition.PRIMITIVE_LOW_TICK
                    } else {
                        null
                    }

                    else -> null
                }

                (primitiveId?.let { primitiveId ->
                    if (vibrator.arePrimitivesSupported(primitiveId)[0]) {
                        VibrationEffect.startComposition().addPrimitive(
                            primitiveId, it.scale
                        ).compose()
                    } else {
                        Log.w(
                            TAG,
                            "Primitive ${it.name} is not supported on this device, falling back."
                        )
                        fallbackOf(it)
                    }
                } ?: run {
                    Log.w(
                        TAG,
                        "Primitive ${it.name} is not known in this SDK version, falling back."
                    )
                    fallbackOf(it)
                })?.let { vibrationEffect ->
                    LoadedEffect(vibrationEffect, it.startOffset)
                }
            }
        }
    }

    /**
     * Play a built-in haptic effect. Plays nothing before Android 10, or for other names.
     *
     * @param name The name of the built-in effect. Must be one of the following:
     *  - "Click"
     *  - "Double Click"
     *  - "Heavy Click"
     *  - "Tick"
     */
    fun playBuiltIn(name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effect = when (name) {
                "Click" -> VibrationEffect.EFFECT_CLICK
                "Double Click" -> VibrationEffect.EFFECT_DOUBLE_CLICK
                "Heavy Click" -> VibrationEffect.EFFECT_HEAVY_CLICK
                "Tick" -> VibrationEffect.EFFECT_TICK
                else -> null
            }

            effect?.let {
                vibrator.vibrate(VibrationEffect.createPredefined(it))
            }
        }
    }

    private companion object {
        const val TAG = "HapticDevice"
    }
}

/**
 * Amplitudes, from 0 to 255, each lasting its timing in milliseconds.
 */
private class AmplitudeSteps(val timings: LongArray, val amplitudes: IntArray) {
    operator fun plus(other: AmplitudeSteps) =
        AmplitudeSteps(timings + other.timings, amplitudes + other.amplitudes)

    @RequiresApi(Build.VERSION_CODES.O)
    fun toEffect(): VibrationEffect = VibrationEffect.createWaveform(timings, amplitudes, -1)
}

/**
 * @return A fade over [durationMs] from [startAmplitude] to [endAmplitude], each from 0 to 1
 */
private fun fade(
    durationMs: Long,
    startAmplitude: Float,
    endAmplitude: Float,
): AmplitudeSteps {
    val pointCount =
        min(durationMs.toInt(), abs(endAmplitude * 255 - startAmplitude * 255).toInt())
    val timings = LongArray(pointCount)
    val amplitudes = IntArray(pointCount)

    var passedDuration = 0L
    for (i in 0 until pointCount) {
        timings[i] =
            round((i + 1).toDouble() * durationMs / pointCount).toLong() - passedDuration
        passedDuration += timings[i]
        amplitudes[i] =
            round(((i + 0.5) / pointCount * (endAmplitude - startAmplitude) + startAmplitude) * 255).toInt()
    }

    return AmplitudeSteps(timings, amplitudes)
}

/**
 * @return An amplitude waveform resembling [primitive], or null if there is none or the SDK
 * version has no amplitude waveforms
 */
private fun fallbackOf(primitive: HapticPrimitive): VibrationEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        return null
    }
    return when (primitive.name) {
        // 12 ms max amplitude
        "click" -> AmplitudeSteps(longArrayOf(12), intArrayOf((255 * primitive.scale).toInt()))

        // 300 ms fading amplitude from 1 to 0
        "thud" -> fade(300, 1f * primitive.scale, 0f)

        // 75 ms fade from 0.5 to 1, then 75 ms back to 0.5
        "spin" -> fade(75, 0.5f * primitive.scale, 1f * primitive.scale) +
                fade(75, 1f * primitive.scale, 0.5f * primitive.scale)

        // 150 ms from 0 to 1
        "quickRise" -> fade(150, 0f, 1f * primitive.scale)

        // 500 ms from 0 to 1
        "slowRise" -> fade(500, 0f, 1f * primitive.scale)

        // 50 ms fade up from 0.5 to 1, then 50 ms down to 0
        "quickFall" -> fade(50, 0.5f * primitive.scale, 1f * primitive.scale) +
                fade(50, 1f * primitive.scale, 0f)

        // 5 ms half amplitude
        "tick" -> AmplitudeSteps(longArrayOf(5), intArrayOf((255 * primitive.scale / 2).toInt()))

        // 12 ms quarter amplitude
        "lowTick" -> AmplitudeSteps(longArrayOf(12), intArrayOf((255 * primitive.scale / 4).toInt()))

        else -> null
    }?.toEffect()
}
