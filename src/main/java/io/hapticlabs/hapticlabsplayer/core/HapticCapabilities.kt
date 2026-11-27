package io.hapticlabs.hapticlabsplayer.core

import android.media.AudioManager
import android.os.Build
import android.os.Vibrator
import android.os.vibrator.VibratorEnvelopeEffectInfo
import android.os.vibrator.VibratorFrequencyProfile

data class HapticCapabilities(
    /**
     * Whether the device supports on / off vibrator control
     */
    val supportsOnOff: Boolean,
    /**
     * Whether the device supports vibration amplitude control
     */
    val supportsAmplitudeControl: Boolean,
    /**
     * Whether the device supports audio coupled haptics using OGG files
     */
    val supportsAudioCoupled: Boolean,
    /**
     * Whether the device supports envelope effects (Basic/WaveformEnvelopeBuilder)
     */
    val supportsEnvelopeEffects: Boolean,
    /**
     * The self-reported resonance frequency of the device's vibration actuator
     */
    val resonantFrequency: Float,
    /**
     * The self-reported frequency response of the device's vibration actuator
     *
     * See [android.os.vibrator.VibratorFrequencyProfile] for more information.
     */
    val frequencyResponse: VibratorFrequencyProfile?,
    /**
     * The self-reported q-factor of the device's vibration actuator
     */
    val qFactor: Float,
    /**
     * The self-reported envelope effect info of the device's vibration actuator.
     *
     * See [android.os.vibrator.VibratorEnvelopeEffectInfo] for more information.
     */
    val envelopeEffectInfo: VibratorEnvelopeEffectInfo?,
    /**
     * The level of haptic support of the device.
     * 0: No haptic support
     * 1: On/off haptic support
     * 2: Amplitude haptic support
     * 3: OGG haptic support
     * 4: PWLE haptic support
     */
    val hapticSupportLevel: Int
)

private val LEVEL_2_SAMSUNG_DEVICES = setOf(
    "SM-S721B",
    "SM-S721B/DS",
    "SM-S721U1",
    "SM-S721U",
    "SM-S721W",
    "SM-S7210",
    "SM-S721N",
    "SM-S921B",
    "SM-S921B/DS",
    "SM-S921U",
    "SM-S921U1",
    "SM-S921W",
    "SM-S921N",
    "SM-S9210",
    "SM-S921E",
    "SM-S921E/DS",
    "SM-S926B",
    "SM-S926B/DS",
    "SM-S926U",
    "SM-S926U1",
    "SM-S926W",
    "SM-S926N",
    "SM-S9260",
    "SM-S926E",
    "SM-S926E/DS",
    "SM-S928B",
    "SM-S928B/DS",
    "SM-S928U",
    "SM-S928U1",
    "SM-S928W",
    "SM-S928N",
    "SM-S9280",
    "SM-S928E",
    "SM-S928E/DS"
)

/**
 * @return Whether the device plays the haptic channels of audio, see
 * [HapticCapabilities.supportsAudioCoupled]
 */
internal fun supportsAudioCoupledHaptics(): Boolean {
    val isSamsungLevel2Device = Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
            LEVEL_2_SAMSUNG_DEVICES.contains(Build.MODEL)
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            AudioManager.isHapticPlaybackSupported() &&
            !isSamsungLevel2Device
}

/**
 * @return The capabilities of the device, whose vibrator is [vibrator]
 */
internal fun hapticCapabilitiesOf(vibrator: Vibrator): HapticCapabilities {
    val supportsOnOff = vibrator.hasVibrator()
    val supportsAmplitudeControl =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()
    val supportsAudioCoupled = supportsAudioCoupledHaptics()
    val supportsEnvelopeEffects =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && vibrator.areEnvelopeEffectsSupported()

    return HapticCapabilities(
        supportsOnOff,
        supportsAmplitudeControl,
        supportsAudioCoupled,
        supportsEnvelopeEffects,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) vibrator.resonantFrequency else Float.NaN,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) vibrator.frequencyProfile else null,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) vibrator.qFactor else Float.NaN,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) vibrator.envelopeEffectInfo else null,
        if (supportsOnOff) {
            if (supportsAmplitudeControl) {
                if (supportsEnvelopeEffects) {
                    4
                } else if (supportsAudioCoupled) {
                    3
                } else {
                    2
                }
            } else {
                1
            }
        } else {
            // Vibrator service not available
            0
        }
    )
}
