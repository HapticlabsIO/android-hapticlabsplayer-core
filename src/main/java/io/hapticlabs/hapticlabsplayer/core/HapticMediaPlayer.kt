package io.hapticlabs.hapticlabsplayer.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioManager.GET_DEVICES_OUTPUTS
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import java.io.IOException
import java.util.Vector
import kotlin.math.roundToLong

/**
 * An audio file, ready to play from the start of a playback of [HapticMediaPlayer].
 * [HapticMediaPlayer.preloadAudio] lowers the latency of playing it.
 */
data class LoadedAudio(
    /** The path of the audio file in the filesystem */
    val path: String
)

/** An OGG file with haptic channels, in the filesystem at [uncompressedPath], ready to play. */
data class UncompressedOGGFile(
    val uncompressedPath: String,
    override val startOffset: Long
) : HasOffset

/** What [HapticMediaPlayer.play] can multiply the duration of audio with */
val AUDIO_DURATION_SCALES = 0.5f..2f

/** A media file to play at [startOffset] */
private data class MediaFile(
    val path: String,
    /** Whether the file has haptic channels, which play on the built-in speaker's vibrator */
    val hasHapticChannels: Boolean,
    override val startOffset: Long,
    /** What the duration of the file is multiplied with, keeping its pitch */
    val durationScale: Float
) : HasOffset {
    /**
     * @return Where in the file a playback from [startPosition] starts it, in milliseconds since
     * the start of the file
     */
    fun positionAt(startPosition: Long): Long = (maxOf(0, startPosition - startOffset) / durationScale).roundToLong()
}

/** A sound loaded into SoundPool, or loading */
private data class PooledSound(
    val soundId: Int,
    /** In milliseconds */
    val duration: Long,
)

/**
 * Plays audio, and OGGs whose haptic channels play on the built-in speaker's vibrator, together
 * and in sync with what plays along with them, such as vibration effects.
 *
 * Plays what [preloadAudio] and [preloadOGG] loaded with SoundPool, which starts with little
 * latency, and everything else with MediaPlayers, which take about 0.1 s to be heard. [play] starts
 * what plays along once they are.
 *
 * Several playbacks play together. SoundPool plays up to 8 sounds at a time, after which a new one
 * stops the oldest.
 *
 * @param context A `Context` of the app, for system services
 */
class HapticMediaPlayer(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val supportsHapticChannels = supportsAudioCoupledHaptics()

    private var soundPool: SoundPool = createSoundPool()
    private val pooledSounds = HashMap<String, PooledSound>()
    private val loadedSoundIds = HashSet<Int>()
    private var isBuiltInSpeakerSelected = true

    init {
        // Haptic channels play on the built-in speaker's vibrator, so where audio plays elsewhere,
        // they're routed to the built-in speaker explicitly
        handler.post {
            val mediaRouter = MediaRouter.getInstance(context)
            val selector = MediaRouteSelector.Builder()
                .addControlCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO)
                .build()

            val mediaRouterCallback = object : MediaRouter.Callback() {
                override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo) {
                    isBuiltInSpeakerSelected = router.selectedRoute.isDeviceSpeaker
                }

                override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo) {
                    isBuiltInSpeakerSelected = router.selectedRoute.isDeviceSpeaker
                }

                override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
                    isBuiltInSpeakerSelected = router.selectedRoute.isDeviceSpeaker
                }
            }

            mediaRouter.addCallback(selector, mediaRouterCallback)
        }
    }

    private fun createSoundPool(): SoundPool {
        val audioAttributes =
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType((AudioAttributes.CONTENT_TYPE_SONIFICATION))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            audioAttributes.setHapticChannelsMuted(false)
        }
        // So that sounds play together, like from MediaPlayers
        val pool = SoundPool.Builder()
            .setAudioAttributes(audioAttributes.build())
            .setMaxStreams(MAX_SOUND_POOL_STREAMS)
            .build()

        pool.setOnLoadCompleteListener { loadingPool, soundId, status ->
            // A released pool's sound IDs are reused by the next pool
            if (status == 0 && loadingPool === soundPool) {
                loadedSoundIds.add(soundId)
            }
        }
        return pool
    }

    protected fun finalize() {
        soundPool.release()
    }

    /**
     * Preload an audio file for lower latency playback with [play].
     *
     * The audio file can be preloaded only if its uncompressed size is less than 1 MB, such as 5 s
     * of 48 kHz stereo audio. In that case, it starts playing from its start, unstretched, with
     * substantially less latency. Call [unload] to release the resources.
     *
     * @param path The path of the audio file in the filesystem
     */
    fun preloadAudio(path: String) {
        loadToSoundPool(path)
    }

    /**
     * Preload an OGG file with haptic channels for lower latency playback with [play], like
     * [preloadAudio]. Only where [HapticCapabilities.supportsAudioCoupled].
     *
     * @param path The path of the OGG file in the filesystem
     */
    fun preloadOGG(path: String) {
        if (supportsHapticChannels) {
            loadToSoundPool(path)
        }
    }

    /**
     * Unload a file preloaded with [preloadAudio] or [preloadOGG].
     *
     * @param path The path the file was preloaded with
     */
    fun unload(path: String) {
        pooledSounds.remove(path)?.let {
            soundPool.unload(it.soundId)
            loadedSoundIds.remove(it.soundId)
        }
    }

    /**
     * Unload all files preloaded with [preloadAudio] or [preloadOGG].
     */
    fun unloadAll() {
        soundPool.release()
        soundPool = createSoundPool()
        // The new SoundPool reuses the sound IDs
        pooledSounds.clear()
        loadedSoundIds.clear()
    }

    /**
     * @return Whether SoundPool loaded the file at [path], so that it plays with little latency
     */
    internal fun isPreloaded(path: String): Boolean = loadedSoundOf(path) != null

    /**
     * @return How long the media file at [path] in the filesystem lasts, in milliseconds, or null if
     * it can't be read or doesn't state it
     */
    fun durationMsOf(path: String): Long? =
        pooledSounds[path]?.duration ?: audioFormatOf(path)?.let { durationOf(it) }

    /**
     * Play audio from its start and OGGs at their offsets, from [startPosition] on.
     *
     * Audio and OGGs that start before [startPosition] start part-way through. Those that aren't
     * preloaded, are too long to preload, start part-way through or are stretched play with
     * MediaPlayers, which take a while to prepare and to be heard. The playback then starts once
     * those that start right away are heard, so that what plays along with it, started from
     * [startCallback], plays in sync.
     *
     * Media that can't be read is left out.
     *
     * @param audio Audio to play from the start of the playback
     * @param oggs OGGs to play, each at its offset since the start of the playback
     * @param startPosition Where to start playing, in milliseconds since the start of the playback
     * @param audioVolume The volume of the audio, and of the audio channels of the OGGs, from 0 for
     * silence to 1 for the audio as it is. The haptic channels of the OGGs play as they are
     * @param audioDurationScale What to multiply the duration of [audio] with, keeping its pitch,
     * such as 2 to play it half as fast. In [AUDIO_DURATION_SCALES]. OGGs play as they are
     * @param startCallback Called on the main thread when [startPosition] plays, unless the
     * playback is stopped before, with the [SystemClock.uptimeMillis] at which the start of the
     * playback played, or would have, had it started there. Start what plays along with the
     * playback here, to keep it in sync
     * @return A callback that when called stops the playback, or keeps it from starting. Otherwise,
     * each file stops at its end
     * @throws IllegalArgumentException if [audioDurationScale] isn't in [AUDIO_DURATION_SCALES]
     */
    @JvmOverloads
    fun play(
        audio: LoadedAudio?,
        oggs: List<UncompressedOGGFile>,
        startPosition: Long = 0,
        audioVolume: Float = 1f,
        audioDurationScale: Float = 1f,
        startCallback: (startUptimeMillis: Long) -> Unit = {}
    ): () -> Unit {
        require(audioDurationScale in AUDIO_DURATION_SCALES) {
            "audioDurationScale must be in $AUDIO_DURATION_SCALES: $audioDurationScale"
        }
        var isStopped = false
        val playbacks = Vector<MediaPlayback>()
        val mediaFiles = listOfNotNull(audio?.let { MediaFile(it.path, false, 0, audioDurationScale) }) +
                oggs.map { MediaFile(it.uncompressedPath, true, it.startOffset, 1f) }

        // On the main thread, so that MediaPlayers call their listeners there
        handler.post {
            preparePlaybacks(mediaFiles, startPosition, audioVolume) { prepared ->
                playbacks.addAll(prepared.map { it.second })
                if (isStopped) {
                    prepared.forEach { it.second.stop() }
                    return@preparePlaybacks
                }

                // MediaPlayers that start right away set when everything else starts
                val pacingPlaybacks = prepared
                    .filter { (file, _) -> file.startOffset <= startPosition }
                    .map { it.second }
                    .filterIsInstance<MediaPlayerPlayback>()
                pacingPlaybacks.forEach { it.start() }

                whenHeard(pacingPlaybacks) {
                    if (isStopped) {
                        return@whenHeard
                    }
                    // When the start would have played, so that it plays from startPosition now
                    val startUptime = SystemClock.uptimeMillis() - startPosition
                    startCallback(startUptime)

                    for ((file, playback) in prepared.filter { it.second !in pacingPlaybacks }) {
                        handler.postAtTime({
                            if (!isStopped) {
                                playback.start()
                            }
                        }, startUptime + maxOf(file.startOffset, startPosition))
                    }
                }
            }
        }

        return {
            isStopped = true
            // Of a copy, as preparing may add more
            playbacks.toList().forEach { it.stop() }
        }
    }

    /**
     * Prepares a playback of each of [mediaFiles] from where a playback from [startPosition] starts
     * it.
     *
     * @param volume The volume of the audio, from 0 for silence to 1 for the audio as it is
     * @param onPrepared Called once all are prepared, with each file that can play and its
     * playback, in the order of [mediaFiles]
     */
    private fun preparePlaybacks(
        mediaFiles: List<MediaFile>,
        startPosition: Long,
        volume: Float,
        onPrepared: (List<Pair<MediaFile, MediaPlayback>>) -> Unit
    ) {
        if (mediaFiles.isEmpty()) {
            onPrepared(emptyList())
            return
        }
        val playbacks = arrayOfNulls<MediaPlayback>(mediaFiles.size)
        var unpreparedCount = mediaFiles.size
        mediaFiles.forEachIndexed { index, file ->
            preparePlayback(file, file.positionAt(startPosition), volume) { playback ->
                playbacks[index] = playback
                unpreparedCount--
                if (unpreparedCount == 0) {
                    onPrepared(mediaFiles.zip(playbacks).mapNotNull { (file, playback) ->
                        playback?.let { file to it }
                    })
                }
            }
        }
    }

    /**
     * Prepares a playback of [file] from [position], in milliseconds since its start. Plays sounds
     * that SoundPool loaded from it with SoundPool, and others with MediaPlayers.
     *
     * @param volume The volume of the audio, from 0 for silence to 1 for the audio as it is. The
     * haptic channels play as they are
     * @param onPrepared Called with the playback once it's prepared, or with null if it can't play
     */
    private fun preparePlayback(
        file: MediaFile,
        position: Long,
        volume: Float,
        onPrepared: (MediaPlayback?) -> Unit
    ) {
        val pooledSound = loadedSoundOf(file.path)
        // SoundPool can't start part-way through, stretch keeping the pitch, nor route haptic
        // channels apart from the audio
        if (pooledSound != null && position == 0L && file.durationScale == 1f &&
            (isBuiltInSpeakerSelected || !file.hasHapticChannels)
        ) {
            onPrepared(SoundPoolPlayback(soundPool, pooledSound.soundId, volume))
            return
        }

        val players = try {
            createMediaPlayers(file, volume)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open media file: ${file.path}", e)
            onPrepared(null)
            return
        }
        MediaPlayerPlayback.prepare(players, position, file.durationScale, onPrepared)
    }

    /**
     * Creates the MediaPlayers that play [file] together. Haptic channels play on the built-in
     * speaker's vibrator, so where audio plays elsewhere, one player plays them there and another
     * the audio.
     *
     * @param volume The volume of the audio, from 0 for silence to 1 for the audio as it is. The
     * haptic channels play as they are
     * @throws IOException if [file] can't be opened
     */
    private fun createMediaPlayers(file: MediaFile, volume: Float): List<MediaPlayer> {
        val players = mutableListOf<MediaPlayer>()
        try {
            val mainPlayer = MediaPlayer().also { players += it }
            val mainPlayerAudioAttributes =
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType((AudioAttributes.CONTENT_TYPE_SONIFICATION))
            if (file.hasHapticChannels && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mainPlayerAudioAttributes.setHapticChannelsMuted(false)
            }
            mainPlayer.setAudioAttributes(mainPlayerAudioAttributes.build())
            mainPlayer.setDataSource(file.path)
            mainPlayer.setVolume(volume, volume)

            if (!file.hasHapticChannels || isBuiltInSpeakerSelected) {
                return players
            }

            val builtInSpeaker = context.getSystemService(AudioManager::class.java)
                ?.getDevices(GET_DEVICES_OUTPUTS)
                ?.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?: return players

            // Only the haptics play on the built-in speaker
            mainPlayer.setVolume(0f, 0f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                mainPlayer.setPreferredDevice(builtInSpeaker)
            }

            val audioPlayer = MediaPlayer().also { players += it }
            val audioPlayerAttributesBuilder =
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType((AudioAttributes.CONTENT_TYPE_SONIFICATION))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                audioPlayerAttributesBuilder.setHapticChannelsMuted(true)
            }
            audioPlayer.setAudioAttributes(audioPlayerAttributesBuilder.build())
            audioPlayer.setDataSource(file.path)
            audioPlayer.setVolume(volume, volume)
            return players
        } catch (e: IOException) {
            players.forEach { it.release() }
            throw e
        }
    }

    /**
     * Calls [onHeard] on the main thread once all of the started [playbacks] are heard, or after
     * [MAX_HEARD_WAIT_MS] if they aren't by then.
     */
    private fun whenHeard(playbacks: List<MediaPlayerPlayback>, onHeard: () -> Unit) {
        val deadline = SystemClock.uptimeMillis() + MAX_HEARD_WAIT_MS
        fun check() {
            if (playbacks.all { it.isHeard }) {
                onHeard()
            } else if (SystemClock.uptimeMillis() >= deadline) {
                Log.w(TAG, "Media wasn't heard within $MAX_HEARD_WAIT_MS ms, playing the rest anyway")
                onHeard()
            } else {
                handler.postDelayed({ check() }, HEARD_CHECK_INTERVAL_MS)
            }
        }
        check()
    }

    /**
     * @return The sound SoundPool loaded from [path], unless it isn't loaded or still loading
     */
    private fun loadedSoundOf(path: String): PooledSound? =
        pooledSounds[path]?.takeIf { loadedSoundIds.contains(it.soundId) }

    /**
     * Loads the file at [path] into SoundPool, unless it's loading or loaded already, or too large
     * for SoundPool.
     */
    private fun loadToSoundPool(path: String) {
        if (pooledSounds.containsKey(path)) {
            return
        }

        val format = audioFormatOf(path) ?: return
        // Truly, we can't tell its size. So to be safe, we need to assume we can't load it
        val durationMs = durationOf(format) ?: return
        val decodedBytes = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).toLong() *
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) *
                SOUNDPOOL_BYTES_PER_SAMPLE * durationMs / MILLISECONDS_PER_SECOND
        if (decodedBytes < SOUNDPOOL_MAX_BYTES) {
            pooledSounds[path] = PooledSound(soundPool.load(path, 1), durationMs)
        }
    }

    /**
     * @return The format of the first audio track of the file at [path], or null if it has none or
     * can't be read
     */
    private fun audioFormatOf(path: String): MediaFormat? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read the audio format of: $path", e)
            null
        } finally {
            extractor.release()
        }
    }

    /**
     * @return The duration of the audio in [format] in milliseconds, or null if it doesn't state it
     */
    private fun durationOf(format: MediaFormat): Long? =
        if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION) / MICROSECONDS_PER_MILLISECOND
        } else {
            null
        }

    private companion object {
        const val TAG = "HapticMediaPlayer"

        const val MICROSECONDS_PER_MILLISECOND = 1000
        const val MILLISECONDS_PER_SECOND = 1000

        const val SOUNDPOOL_BYTES_PER_SAMPLE = 2

        // SoundPool fails to load sounds that decode to more
        const val SOUNDPOOL_MAX_BYTES = 1_000_000

        // How many sounds SoundPool plays at once. Beyond that, a new one stops the oldest
        const val MAX_SOUND_POOL_STREAMS = 8

        // How often to check whether media playing with MediaPlayers is heard yet
        const val HEARD_CHECK_INTERVAL_MS = 5L

        // How long to wait at most for media playing with MediaPlayers to be heard
        const val MAX_HEARD_WAIT_MS = 1000L
    }
}
