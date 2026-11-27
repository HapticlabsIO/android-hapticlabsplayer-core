package io.hapticlabs.hapticlabsplayer.core

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HapticDeviceTest {

    private val device = HapticDevice(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun loadPrimitives_knownPrimitives_loadsEachAtItsOffset() {
        // Primitives fall back to amplitude waveforms, which need Android 8
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        val primitives = listOf(
            HapticPrimitive("click", 1f, 0),
            HapticPrimitive("thud", 0.5f, 100),
            HapticPrimitive("spin", 0.5f, 200),
            HapticPrimitive("quickRise", 0.5f, 300),
            HapticPrimitive("slowRise", 0.5f, 400),
            HapticPrimitive("quickFall", 0.5f, 500),
            HapticPrimitive("tick", 1f, 600),
            HapticPrimitive("lowTick", 1f, 700)
        )

        val effects = device.loadPrimitives(primitives)

        assertEquals(primitives.map { it.startOffset }, effects.map { it.startOffset })
    }

    @Test
    fun loadPrimitives_unknownPrimitive_leavesItOut() {
        assertEquals(emptyList<LoadedEffect>(), device.loadPrimitives(listOf(HapticPrimitive("unknown", 1f, 0))))
    }

    @Test
    fun playBuiltIn_knownAndUnknownNames_doesNotThrow() {
        listOf("Click", "Double Click", "Heavy Click", "Tick", "Unknown").forEach { device.playBuiltIn(it) }
    }

    @Test
    fun capabilities_level_matchesWhatTheDeviceSupports() {
        val capabilities = device.capabilities
        val expectedLevel = when {
            !capabilities.supportsOnOff -> 0
            !capabilities.supportsAmplitudeControl -> 1
            capabilities.supportsEnvelopeEffects -> 4
            capabilities.supportsAudioCoupled -> 3
            else -> 2
        }

        assertEquals(expectedLevel, capabilities.hapticSupportLevel)
    }
}
