# Source discovery and torrent startup — 23 November 2026

Based on the published v1.8.5 source. No screens, themes, navigation, D-pad handlers, or player settings changed.

## Changes

- Addons.kt: load the saved registry once; refresh it in an application IO scope during normal discovery. Serialize refreshes to avoid duplicate registry requests. Explicit refresh still waits for current configuration.
- StreamSources.kt: emit source results as providers finish instead of waiting for the slowest provider. Include enabled add-on configuration in result-cache keys. Retain final aggregation, timeout handling and explicit refresh.
- ProgressiveResults.kt: structured concurrent provider execution with serial partial snapshots in configured provider order; cancellation stops children.
- StreamingViewModel.kt: route partial results into existing source cards; suppress premature unavailable messages. Automatic destination selection still waits for complete results to preserve ranking behavior.
- TorrentPlayback.kt: prioritize up to four opening and two final pieces once metadata and file checks pass. This can overlap header and end-index retrieval. Maintain up to four pieces of moving read-ahead even when the current piece is already present; expire prior window deadlines after seeks. Keep existing piece timeout, selected-file checks, storage checks, cleanup and sequential downloading.
- TorrentRules.kt: bounded startup and read-ahead piece selection.
- ProgressiveResultsTest.kt and TorrentStartupTest.kt: regression coverage for early result emission, stable ordering, cancellation, selected-file boundaries, short files and integer limits.

## Limits and validation

No device timing measurement or guaranteed speedup. Peer discovery, seed availability, connection speed, file bitrate and container format still affect playback. This update does not bypass metadata retrieval or download an entire movie before playing. Normal searches can use the last saved registry until background refresh completes; explicit refresh applies new registry data immediately.

Whitespace checks passed. Attempted `bash gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon`; Gradle 9.3.0 download failed with `Network is unreachable` before compilation. New tests are provided but have not run here. No APK is built or released.

## Android Studio validation

Run `./gradlew :app:testDebugUnitTest :app:assembleDebug`. Compare the same authorized source on the same device/network: time to first source card, selection to first video frame, and seeking near the end. Verify a slow provider does not hide a fast result, cancellation/episode switching, explicit refresh, source toggles, automatic destination behavior, MP4 with a trailing index, short files, low-peer failure and resume.
