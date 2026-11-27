# hapticlabsplayer-core

`hapticlabsplayer-core` is what Hapticlabs' Android players share, apart from file formats: the device's haptic capabilities and effects, and playback of audio and of OGG files with haptic channels, in sync with what plays along. [`hapticlabsplayer`](https://github.com/HapticlabsIO/androidplayer) and [`habplayer`](https://github.com/HapticlabsIO/android-habplayer) build on it and bring it along, so apps rarely add it themselves.

## Library Installation

```kotlin
dependencies {
  implementation("io.hapticlabs:hapticlabsplayer-core:0.1.0")
}
```

Your app needs to compile against API level 36 or later, with a `compileSdk` of 36 or more. Its classes are in `io.hapticlabs.hapticlabsplayer.core`.

## `HapticDevice(context: Context)`

The device's vibrator.

- `val capabilities: HapticCapabilities`: what the device supports: on/off, amplitude control, audio-coupled haptics and envelope effects, the actuator's resonant frequency, frequency response and quality factor, and the resulting `hapticSupportLevel` from 0 to 4.
- `fun loadPrimitives(primitives: List<HapticPrimitive>): List<LoadedEffect>`: vibration effects for primitives such as `"click"`, `"tick"` or `"lowTick"`, falling back to amplitude waveforms where the device doesn't support them.
- `fun playBuiltIn(name: String)`: plays one of Android's built-in effects, `"Click"`, `"Double Click"`, `"Heavy Click"` or `"Tick"`, from Android 10 on.

## `HapticMediaPlayer(context: Context)`

Plays audio files, and OGG files whose haptic channels play on the device's vibrator, in sync with what plays along with them. Several playbacks play together.

- `fun play(audio: LoadedAudio?, oggs: List<UncompressedOGGFile>, startPosition: Long = 0, audioVolume: Float = 1f, audioDurationScale: Float = 1f, startCallback: (startUptimeMillis: Long) -> Unit = {}): () -> Unit`: plays the audio from its start and the OGGs at their offsets, from `startPosition` on, in milliseconds.
  - `audioVolume`, from 0 to 1, sets the volume of the audio and of the OGGs' audio channels, leaving their haptic channels as they are.
  - `audioDurationScale`, from 0.5 to 2, stretches the audio, keeping its pitch. OGGs play as they are.
  - Media that isn't preloaded, starts part-way through or is stretched plays with `MediaPlayer`s, which take a moment to be heard. The playback starts once they are, and calls `startCallback` with the `SystemClock.uptimeMillis()` at which its start played, or would have, had it started at `startPosition`. Start what plays along in that callback, such as vibration effects, to keep it in sync.
  - Returns a callback that stops the playback. Otherwise, each file stops at its end.
- `fun preloadAudio(path: String)`, `fun preloadOGG(path: String)`: load a file in the filesystem into memory for low-latency playback from its start, if its uncompressed size is less than 1 MB, such as 5 s of 48 kHz stereo audio. OGGs only load where `HapticCapabilities.supportsAudioCoupled`. Preloaded files play together up to 8 at a time, after which a new one stops the oldest.
- `fun unload(path: String)`, `fun unloadAll()`: release what was preloaded.
- `fun durationMsOf(path: String): Long?`: how long a media file lasts.

## Development

This repository is a standalone Gradle build. It uses React Native's Android Gradle plugin version, like `hapticlabsplayer` and `habplayer`, as builds that include it need the same one.

```sh
./gradlew build
```

The tests that matter play audio and vibrate, so they run on a device, with `./gradlew connectedDebugAndroidTest`.

`hapticlabsplayer` and `habplayer` build against a local checkout of this library instead of the published one with `-PhapticlabsAndroidPackageDir=<HapticlabsAndroidPackage checkout>`, whose `hapticlabsplayer-core` submodule this repository is.

### Releasing

Bump `version` in [`gradle.properties`](gradle.properties) (and the version in the installation instructions above) and merge to `main`. The [release workflow](.github/workflows/release.yml) then publishes the new version to Maven Central, tags it as `v<version>` and creates a GitHub release. It is disabled until its job's `false &&` condition is removed.

The workflows need these repository secrets:

| Secret | Description |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` | Central Portal user token for the `io.hapticlabs` namespace |
| `GPG_PUBLIC_KEY`, `GPG_SECRET_KEY`, `GPG_PASSPHRASE` | Armored signing key pair and its passphrase |
