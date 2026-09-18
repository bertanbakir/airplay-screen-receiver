# AirPlay Screen Receiver

**An open-source screen mirroring receiver for Android TV, powered by UxPlay.**

AirPlay Screen Receiver lets an Android TV or Google TV receive screen-mirroring sessions from iPhone, iPad, and macOS over the local network. It focuses on a simple full-screen receiver experience, correct portrait and landscape rendering, and reliable audio/video startup on TV hardware.

> AirPlay Screen Receiver is an independent open-source project. It is not affiliated with or endorsed by Apple Inc. AirPlay is a trademark of Apple Inc.

## Highlights

- iPhone, iPad, and macOS screen mirroring
- H.264 and H.265 video decoding through Android `MediaCodec`
- Audio playback through Android `AudioTrack`
- mDNS / Bonjour discovery on the local network
- Portrait, landscape, and non-macroblock-aligned H.264 geometry support
- Android TV and Google TV Leanback launcher integration
- ARMv7 (`armeabi-v7a`) and ARM64 (`arm64-v8a`) builds
- No cloud service, analytics SDK, advertising SDK, root, or bootloader changes

DRM-protected or FairPlay-protected media playback is not a project goal.

## How to use

1. Install and open AirPlay Screen Receiver on the television.
2. Connect the television and Apple device to the same local network.
3. Open Control Center and choose **Screen Mirroring**.
4. Select the receiver name shown on the TV.

When a session starts, the waiting screen gives way to the mirrored video while preserving its visible aspect ratio.

## Requirements

| | |
|---|---|
| **Target device** | Android TV or Google TV with Leanback support |
| **Android version** | Android 8.0 / API 26 or newer |
| **Network** | Sender and receiver on the same LAN |
| **Build tools** | Android Studio or Gradle, Android SDK, NDK, CMake 3.22+ |

## Build

Clone with submodules:

```bash
git clone --recurse-submodules https://github.com/bertanbakir/airplay-screen-receiver.git
cd airplay-screen-receiver
./gradlew assembleDebug
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install over USB or network ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Android package remains `dev.aquiles.airplayandroidtv` for compatibility with existing test installations. A future package-ID migration, if any, will be handled as a separate release decision.

### Release signing

Release builds are unsigned unless all four signing environment variables are provided:

- `AIRPLAY_RECEIVER_RELEASE_STORE_FILE`
- `AIRPLAY_RECEIVER_RELEASE_STORE_PASSWORD`
- `AIRPLAY_RECEIVER_RELEASE_KEY_ALIAS`
- `AIRPLAY_RECEIVER_RELEASE_KEY_PASSWORD`

Signing keys and credentials must never be committed to the repository.

## Architecture

AirPlay Screen Receiver is a small Android TV shell around a native receiver stack:

- **[UxPlay](https://github.com/FDH2/UxPlay)** — RAOP / AirPlay-compatible receiver implementation in C
- **JNI bridge** — connects native receiver callbacks to Kotlin
- **Kotlin video layer** — parses stream geometry and feeds Android `MediaCodec`
- **Kotlin audio layer** — decodes and schedules audio through `AudioTrack`
- **Discovery layer** — advertises `_airplay._tcp` and `_raop._tcp` through JmDNS
- **TV UI** — a single full-screen activity with a `SurfaceView` and waiting overlay

Native sources are built with CMake from `app/src/main/cpp/`.

## Project provenance

AirPlay Screen Receiver has its own project identity and release history. Upstream source acknowledgements, copyright information, and third-party licensing details are preserved in [NOTICE.md](NOTICE.md) and the license files shipped with the native components.

## Licensing

AirPlay Screen Receiver as a combined work is distributed under the **GNU General Public License v3**; see [LICENSE](LICENSE). UxPlay is GPLv3-licensed and included as a pinned Git submodule. The distributed native library also incorporates components with their own compatible license terms, including libplist, llhttp, JmDNS, and OpenSSL-related code.

Because the APK combines GPL-covered native code with the Android application, binary releases must be accompanied by the exact corresponding source, submodule revisions, build files, modifications, and required license notices. Do not remove upstream copyright or attribution notices.

Application Kotlin code and Android resources inherited from the upstream project are described upstream as MIT-licensed where they are not derived from GPL-covered components. Public binary releases should be treated as GPLv3 distributions for compliance purposes.

## Current status

AirPlay Screen Receiver is under active development. It has been tested on a Xiaomi TV Box S 2nd Gen with Android 11 using iPhone and macOS screen mirroring, including portrait and landscape video with audio. Debug builds are for controlled testing; a signed public release has not yet been published.

## Contributing

Issues and pull requests are welcome once the repository is opened publicly. Keep protocol, decoder, UI, and release-compliance changes in focused commits.
