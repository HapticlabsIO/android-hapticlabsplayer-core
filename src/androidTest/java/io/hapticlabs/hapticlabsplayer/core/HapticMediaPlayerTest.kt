package io.hapticlabs.hapticlabsplayer.core

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HapticMediaPlayerTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val player = HapticMediaPlayer(context)
    private val audioPath = silentWav(context, "hapticMediaPlayerTest.wav", 1000).absolutePath

    /** @return Whether SoundPool loads the file at [path] in time */
    private fun preloads(path: String): Boolean = becomesTrueWithin(2000) { player.isPreloaded(path) }

    /**
     * @return The start uptime that playing [audio] and [oggs] from [startPosition] reported, or
     * null if it didn't start in time
     */
    private fun startUptimeOf(
        audio: LoadedAudio?,
        oggs: List<UncompressedOGGFile> = emptyList(),
        startPosition: Long = 0,
        audioDurationScale: Float = 1f
    ): Long? {
        val latch = CountDownLatch(1)
        var startUptime: Long? = null
        var stop: () -> Unit = {}
        instrumentation.runOnMainSync {
            stop = player.play(audio, oggs, startPosition, 1f, audioDurationScale) {
                startUptime = it
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        instrumentation.runOnMainSync { stop() }
        return startUptime
    }

    @Test
    fun play_withoutMedia_startsRightAway() {
        val before = SystemClock.uptimeMillis()

        val startUptime = requireNotNull(startUptimeOf(null))

        assertTrue("Started at $startUptime, playing from $before", startUptime in before..before + 100)
    }

    @Test
    fun play_fromAStartPosition_startsAsIfTheStartPlayedThatLongBefore() {
        val before = SystemClock.uptimeMillis()

        val startUptime = requireNotNull(startUptimeOf(null, startPosition = 1500))

        assertTrue("Started at $startUptime, playing from $before", startUptime in before - 1500..before - 1400)
    }

    @Test
    fun play_withAudio_starts() {
        assertNotNull(startUptimeOf(LoadedAudio(audioPath)))
    }

    @Test
    fun play_withAudioFromAStartPosition_starts() {
        assertNotNull(startUptimeOf(LoadedAudio(audioPath), startPosition = 500))
    }

    @Test
    fun play_withPreloadedAudio_starts() {
        instrumentation.runOnMainSync { player.preloadAudio(audioPath) }
        assertTrue(preloads(audioPath))

        assertNotNull(startUptimeOf(LoadedAudio(audioPath)))
    }

    @Test
    fun play_withStretchedAudioFromAStartPosition_starts() {
        assertNotNull(startUptimeOf(LoadedAudio(audioPath), startPosition = 500, audioDurationScale = 2f))
    }

    @Test
    fun play_withStretchedPreloadedAudio_starts() {
        instrumentation.runOnMainSync { player.preloadAudio(audioPath) }
        assertTrue(preloads(audioPath))

        assertNotNull(startUptimeOf(LoadedAudio(audioPath), audioDurationScale = 0.5f))
    }

    @Test
    fun play_withAudioDurationScaleOutOfRange_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            player.play(LoadedAudio(audioPath), emptyList(), 0, 1f, 4f)
        }
    }

    @Test
    fun play_withUnreadableMedia_startsWithoutIt() {
        val unreadablePath = File(context.cacheDir, "unreadable.ogg").apply { writeText("not media") }.absolutePath

        assertNotNull(startUptimeOf(LoadedAudio(unreadablePath), listOf(UncompressedOGGFile(unreadablePath, 0))))
    }

    @Test
    fun play_stoppedWhilePreparing_doesNotStart() {
        var isStarted = false

        instrumentation.runOnMainSync {
            val stop = player.play(LoadedAudio(audioPath), emptyList()) { isStarted = true }
            stop()
        }
        // Until it would have started
        Thread.sleep(500)

        var wasStarted = true
        instrumentation.runOnMainSync { wasStarted = isStarted }
        assertFalse(wasStarted)
    }

    @Test
    fun preloadAudio_shortAudio_loadsItIntoSoundPool() {
        instrumentation.runOnMainSync { player.preloadAudio(audioPath) }

        assertTrue(preloads(audioPath))
    }

    @Test
    fun preloadOGG_loadsItOnlyWhereHapticChannelsPlay() {
        // SoundPool loads it like an OGG
        val oggPath = silentWav(context, "hapticMediaPlayerTestOGG.wav", 1000).absolutePath

        instrumentation.runOnMainSync { player.preloadOGG(oggPath) }

        assertEquals(HapticDevice(context).capabilities.supportsAudioCoupled, preloads(oggPath))
    }

    @Test
    fun unload_preloadedAudio_unloadsIt() {
        instrumentation.runOnMainSync { player.preloadAudio(audioPath) }
        assertTrue(preloads(audioPath))
        var isLoaded = true

        instrumentation.runOnMainSync {
            player.unload(audioPath)
            isLoaded = player.isPreloaded(audioPath)
        }

        assertFalse(isLoaded)
    }

    @Test
    fun preloadAudio_afterUnloadAll_isNotLoadedBeforeItLoads() {
        instrumentation.runOnMainSync { player.preloadAudio(audioPath) }
        assertTrue(preloads(audioPath))
        var isLoadedRightAway = true

        // Loading completes on the main thread, so not before this returns
        instrumentation.runOnMainSync {
            player.unloadAll()
            player.preloadAudio(audioPath)
            isLoadedRightAway = player.isPreloaded(audioPath)
        }

        assertFalse(isLoadedRightAway)
    }

    @Test
    fun preloadAudio_missingFile_doesNotThrow() {
        player.preloadAudio(File(context.cacheDir, "missing.wav").absolutePath)
    }

    @Test
    fun durationMsOf_audio_isItsDuration() {
        assertEquals(1000L, player.durationMsOf(audioPath))
    }

    @Test
    fun durationMsOf_unreadableFile_isNull() {
        val unreadablePath = File(context.cacheDir, "unreadable.wav").apply { writeText("not audio") }.absolutePath

        assertNull(player.durationMsOf(unreadablePath))
    }
}
