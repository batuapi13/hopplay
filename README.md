# HopPlay

**Folder-first Android media player.** Stream your videos and music directly from SSH/SFTP without installing a media server. Local folders and SMB shares are also supported.

## Features
- **Direct SFTP:** SSHJ with password authentication and trust-on-first-use host-key pinning. A host-key viewer and explicit reset are included.
- **Streaming:** random-access byte-range reads support playback and seeks without downloading the entire file.
- **Folders and queues:** natural sorting, automatic next-file playback and per-file resume.
- **Player:** audio/subtitle track selection, playback speed, swipe seek/volume/brightness, double-tap skip, Auto/Land/Port orientation.
- **Appearance:** HopPlay adaptive launcher icon and dark navy/teal Material3 UI.
- **Engine:** Media3 ExoPlayer with decoder-init fallback. The player is recreated between separate playback sessions to avoid leaking hardware decoder state.

## Android build
Requires JDK 17 and Android SDK 35. Minimum Android API 26.

```bash
gradle assembleDebug
gradle assembleDebug -Pffmpeg=true   # adds the FFmpeg AUDIO decoder extension
gradle testDebugUnitTest
```

The GitHub Actions PR build runs tests and assembles the FFmpeg-enabled debug APK as the `hopplay-ffmpeg-debug-apk` artifact.

## Codec support: verification matrix
HopPlay accepts extensions including MKV, MP4/M4V, MOV, WebM, MPEG-TS, AVI, FLV, MP3, FLAC, OGG, Opus and WAV. **Recognizing a file extension does not guarantee its video/audio tracks can be decoded.**

| Category | Representative tracks to test | Expected route | Current verification |
| --- | --- | --- | --- |
| Video | H.264/AVC, H.265/HEVC | Android MediaCodec | Prior Snapdragon decoder failure reported; reinitialization fix needs device retest |
| Video | VP8, VP9, AV1 | Android MediaCodec where available | Device test needed |
| Video | MPEG-2, MPEG-4 Part 2 | Android decoder if available | Device test needed |
| Audio | AAC, MP3, FLAC, Opus, Vorbis, PCM | Platform / Media3 | Device test needed |
| Audio | AC-3, E-AC-3, DTS variants, TrueHD | FFmpeg audio extension where supported | FFmpeg variant must build and be tested |
| Subtitle | embedded and matching sidecar SRT/VTT/ASS/SSA | Media3 text renderer | Device test needed |

**Important:** the Jellyfin Media3 FFmpeg extension is for *audio*, not a general software video-decoding fallback. To cover unsupported or broken video codecs independently of the phone's MediaCodec, HopPlay would need an embedded software video decoder such as libmpv/FFmpeg, with a native Android build and device tests. Broad playback compatibility is the product goal, **not yet a verified claim**.

## Known limitations
- No SFTP private-key authentication yet; password authentication works.
- Hardware video decoder/runtime failures still need verification against the reported play A → close → switch folders → play B scenario.
- No dedicated background playback service / MediaSession.
- No external subtitle picker or video software-decoding fallback yet.
- Release signing and release minification are not yet production-ready.
