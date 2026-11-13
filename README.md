# HopPlay

Folder-first Android media player (Kotlin, Jetpack Compose, minSdk 26) for local, SFTP and SMB/Windows shares, with automatic next-file playback.

## Features
- **Sources:** local folders (Storage Access Framework), SFTP (sshj), SMB/Windows shares (smbj). Saved connections are stored in Keystore-backed `EncryptedSharedPreferences`.
- **Streaming:** `RemoteFs` (`list`, `stat`, `open` → random-access `RangeFile`) feeds a custom Media3 `DataSource` that reads byte ranges, so remote files stream and seek without a full download.
- **Queue:** the current folder is loaded as a playlist in natural sort order (`ep2` < `ep10`); the next playable file starts automatically. Playback position is remembered per file.
- **Player:** speed 0.25x–4x, audio-track and subtitle pickers (embedded tracks plus sidecar `.srt/.ass/.ssa/.vtt` named `<video>.*`), gestures: horizontal swipe = seek, vertical swipe right = volume, left = brightness, double-tap = ±10s, single tap = controls.
- **Engine:** Media3 ExoPlayer (mp4, mkv, webm, ts, avi, flv, mp3, flac, ogg, opus, wav, …). Chosen over libmpv because it builds reliably with plain Gradle and keeps the APK small. For AC3/EAC3/DTS/TrueHD etc., build with the optional FFmpeg decoder: `gradle assembleDebug -Pffmpeg=true` (uses `org.jellyfin.media3:media3-ffmpeg-decoder`; check the version exists on Maven Central).
- **Size:** arm64-v8a-only ABI split, R8 + resource shrinking in release, no icon packs.

## Build
Requires JDK 17 and the Android SDK (platform 34).

```
gradle assembleDebug        # app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
gradle assembleRelease      # minified, debug-signed
```
CI (`.github/workflows/android.yml`) builds the debug APK and uploads it as an artifact. No Gradle wrapper is committed; run `gradle wrapper` once locally if you want one.

## Status / known limits
- Written without a local Android toolchain: the first CI run may surface compile fixes.
- SFTP host keys are accepted without verification (TODO: trust-on-first-use); SFTP is password-only (key auth planned).
- Remote reads are sequential per open; SFTP throughput is bounded by round-trip time.
- Planned: WebDAV/FTP backends, background playback service/MediaSession, external subtitle picker, landscape lock.
