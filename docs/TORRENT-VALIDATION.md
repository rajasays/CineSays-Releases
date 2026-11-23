# Candidate validation

Work paused at the user’s request on 2026-11-23. Android TV playback has not been run in this workspace.

- JVM/socket regression suite: 78 passed in the latest full run; final native-inclusive run failed.
- Debug APK assembly: passed with JDK 17 / SDK 35, Gradle 9.3.0. No private API configuration or production signing keys were used.
- Optional native-engine selection test: FAILED. First seed-check issue was avoided by the portable POSIX backend and known-data seed mode; the next failure is a timeout waiting for piece 0. The latest diagnostic output is recorded below. Do not treat the candidate as validated.
- Android TV 5:16 / 13:00 / 14:00 playback and UI regression matrix: pending device validation.

The opt-in native test generates 32 MiB of deterministic owned data, builds a torrent, disables DHT/LSD/UPnP/NAT-PMP, and connects two local engine instances. It checks startup, distant resume, backward seek, tail data, byte integrity and obsolete piece priorities. It does not decode video, benchmark public swarms, exercise Android JNI, or replace device testing.

Use an official matching desktop native runtime JAR, such as jlibtorrent-linux-x86_64 2.0.12.9 from the configured FrostWire Maven repository, then run:

```
./gradlew :app:testDebugUnitTest -PtorrentNativeJar=/absolute/path/jlibtorrent-linux-x86_64-2.0.12.9.jar
```

Without this property the native test is explicitly skipped; other unit/socket tests still run. Desktop native binaries are never added to the Android application.

## Pause checkpoint / resume here

- Branch: `feature/torrent-seek-recovery-loading`, based on PR #4 head `9ca4753a644b965218161b3d558e20723d4c92bf`. PR #4 remains untouched; no release or merge.
- Changes: per-reader selections, priority cleanup, HTTP cancellation and bounded workers, aligned timeouts, manual torrent queue control, smaller large-piece window, title/poster loading, tests and investigation report.
- Next: diagnose the native loopback peer failure before claiming playback improvements. The diagnostic native test now logs listener port, peer errors and leecher status. Check peer connectivity, local-session restrictions, transient all-filtered state, and upload/auto-managed flags.
- Re-run all unit tests with the matching native JAR and assembleDebug after fixes. Android TV device testing remains mandatory. No device was attached (`adb devices` was empty).
- The earlier user log (`torrent-seek-release-test.log`) was read: 8 MiB pieces, required piece 0 never in partial queue, 12–45 advertising peers, unrelated queued pieces, 25-second HTTP-before-header timeout. The investigation documents this and the APK’s 512 KiB pre-verification virtual chunks.

Latest native diagnostic (synthetic owned data; local addresses only):

```text
java.lang.AssertionError: Timed out: seed listener
jlibtorrent: Trying to load jlibtorrent from classpath resource
jlibtorrent: Extracted and loading native library from classpath to: /tmp/jlibtorrent-665467544415585561.so
jlibtorrent: SUCCESS: Loaded jlibtorrent from classpath resource (version=2.0.12.9)
native_source file=owned-test-video.mp4 hash=0d4fde1435d40a852d11072e9c8fd7339b896c04
seed_alert owned-test-video.mp4: state changed to: finished
seed_alert owned-test-video.mp4: state changed to: seeding
seed_alert owned-test-video.mp4 checked

```
