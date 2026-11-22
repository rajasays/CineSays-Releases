# CineSays add-on torrent integration — 22 November 2026

## Install this update

This update is based on your uploaded `CineSays(1).zip`, not the earlier project.
Work was isolated on branch `cinesays-addon-torrents`. Baseline snapshot: `1ea102d`.
The uploaded ZIP and original working project were not modified.

The update ZIP contains only new/changed files at their project-relative paths.
1. Back up your existing project or create a Git branch in Android Studio.
2. Close the running app. Extract the update ZIP into your project root (the directory containing `settings.gradle.kts`), allowing the listed files to replace their counterparts.
3. Sync Gradle, then run these commands in Android Studio's terminal:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

4. Install/run the resulting debug APK on your phone using Android Studio.
5. Open Settings → Add-ons and turn **Torrentio** on. It is off by default.
6. Open a title you are authorized to access, choose Watch Now, and select a result under **Play via torrent**. For a show, select the season and episode first.
7. Use Search again to retry discovery if necessary.

No GitHub registry publication is required for this build: Torrentio is also a built-in entry. The accompanying addons.json has the same entry for future registry publication, while preserving the uploaded entries. No remote files have been published.

A complete source ZIP is also supplied. If opening that copy instead of applying the update, copy your existing local.properties and any local signing files into the new copy. Source packages intentionally exclude build output, caches, local credentials, and keystores. Do not install the old APK from your original uploaded ZIP as if it contained these changes.

## Behavior

Manifest: https://torrentio.strem.fun/manifest.json

The existing TMDB/IMDb mapping requests movie sources automatically, and requests series sources with IMDb ID plus season and episode. Each provider remains isolated, with the existing timeouts and manual retry. No movie-by-movie JSON entries are required for a provider configured with `torrentStreams: true`.

` torrentStreams ` is an explicit registry capability, not proof of a content licence. Enable/use a source only for content you have permission to access. Direct torrent playback uses device storage and can upload to peers while active. The supplied Torrentio entry does not configure Real-Debrid. Existing Real-Debrid configuration and approved-hash sources remain available separately.

The existing source buttons and player are retained. The provider's name/description distinguishes otherwise identical quality results. Torrent selection remains explicit; the existing automatic-play preference still automatically chooses direct HTTP media only.

File selection uses a valid zero-based `fileIdx` across all metadata files. Without an index, a unique filename match is used, or a single video if no filename was supplied. Ambiguous multi-video packs are rejected rather than choosing an arbitrary episode. This is intentionally stricter than Stremio's largest-file fallback.

The old 350 MB ceiling is replaced with a check for the entire selected file plus 128 MiB and boundary-piece headroom. This is temporary disk downloading, not a rolling bounded-memory buffer. Payload download begins after file and space checks. Reads prioritize the requested piece for seeking and MP4 end-of-file metadata. Normal player exit stops the session and removes its temporary directory. Existing 60-second metadata and 25-second piece waits remain.

## Changelog

- Addons.kt: optional `torrentStreams` capability; disabled-by-default built-in Torrentio entry; allow torrent-only providers without approved HTTP hosts; preserve old hash-list authorization; add shared playback permission check.
- addons.json: append the same optional Torrentio entry, preserving existing entries.
- StremioProtocol.kt: parse validated 40-character hashes and optional/null zero-based indexes for explicitly enabled torrent providers; preserve old hash filtering; isolate malformed stream entries; expose provider descriptions for torrent choices.
- StreamModels.kt: separate direct-torrent and Real-Debrid capabilities, with backward-compatible constructor defaults.
- StreamSources.kt: pass the provider capabilities to the parser; enforce the existing 2 MB response limit even without Content-Length.
- PlaybackRules.kt: only offer Real-Debrid where the source enables it; keep existing ordering and automatic-play behavior.
- CatalogViewModel.kt: invalidate source cache when any Stremio provider is toggled.
- StreamingViewModel.kt: recheck the enabled provider at playback; stop the previous torrent on Next episode so it cannot block the next source.
- TorrentRules.kt: testable video selection, storage arithmetic, path validation, and legacy-source compatibility.
- TorrentPlayback.kt: honor file index/filename; preserve the verified Big Buck Bunny descriptor and legacy AVI filename correction; replace size ceiling with free-space checks; validate metadata paths; prioritize requested pieces for seeking; keep cancellation, piece-gated reads, and cleanup.
- MainActivity.kt: update only Add-ons explanatory text.
- HybridPlayer.kt: update only streaming-source explanatory text. No layout, modifier, control, animation, or D-pad handler changes.
- TorrentSourcesTest.kt: 10 regression tests covering the supplied 1.51 GB response, opt-in, URL restrictions, invalid inputs, null index, selection ambiguity, large files, paths, and episode requests.
- TORRENTIO-INTEGRATION.md and INTEGRATION-AUDIT.md: setup, architecture review, scope, validation and limitations.

## Validation status

- JSON parsed successfully; existing registry entries preserved.
- Git whitespace checks passed with CRLF recognized as the existing line ending.
- Static review confirmed all pre-existing source/resource files outside the explicit change list remain byte-identical to the uploaded snapshot. Existing UI layout and event-handler code remains unchanged except the torrent lifecycle fix described above.
- Verified the newly called jlibtorrent APIs against the dependency in the APK included in your upload: six-argument SessionManager.download, setPieceDeadline(int,int), resetPieceDeadline(int).
- Attempted Gradle assembleDebug and testDebugUnitTest. Both are **blocked before compilation**: this environment cannot reach services.gradle.org to download Gradle 9.3.0. No Kotlin compiler, Android SDK, or cached dependencies are available here.
- Therefore the new unit tests are supplied but **not executed**, and no newly built APK is supplied. Build success, live Torrentio playback, and device/D-pad behavior are not claimed as verified.

## Device checks before release

- Run all JVM tests and assembleDebug using the command above.
- Retest both existing Big Buck Bunny choices and Internet Archive playback.
- Test authorized movie and episode sources, including a multi-file torrent with a nonzero fileIdx.
- Test seek forward/back, resume, Back while gathering metadata, player exit, Next episode, and an insufficient-space condition.
- Confirm progress and watched-state updates in Trakker, and TV D-pad behavior on your TV/streamer.
- Confirm switching Torrentio off removes it from the next source search.

Peer availability, supported device codecs, and provider uptime determine whether a particular result plays. The current implementation still relies on DHT for magnet peer discovery; optional add-on `sources` tracker hints are not consumed. Direct URLs needing new host permissions, custom headers, or redirects remain restricted by the existing HTTP policy. This update does not add arbitrary URL entry, subtitle add-on discovery, automatic torrent binge selection, or a Real-Debrid subscription bypass.
