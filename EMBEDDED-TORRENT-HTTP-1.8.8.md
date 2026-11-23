# CineSays v1.8.8 — Embedded torrent-to-HTTP (development)

## Goal
Eliminate the requirement for TorrServer, Tailscale, external PCs and paid debrid APIs for local torrent playback. Reuse CineSays's existing approved-source libtorrent engine.

## Implementation
- `LocalTorrentHttp`: per-playback HTTP/1.1 server bound to `127.0.0.1` on an ephemeral port; random per-session URL; supports HEAD, GET, single byte Range and 416 responses; fixed-size read buffers; closes with torrent playback.
- `TorrentPlayback`: supplies the verified piece-aware DataSource to the loopback bridge and closes the bridge with the torrent session.
- `StreamingViewModel`: selects local loopback HTTP after successful torrent metadata preparation; falls back to the existing in-process piece-aware DataSource if binding fails.
- `HybridPlayer`: Media3 uses its HTTP DataSource for the loopback stream; old direct DataSource remains available.
- `cinesays_network_security.xml`: permits cleartext only for localhost/127.0.0.1 and retains HTTPS-only external sources.
- `LocalTorrentRangeTest`: validates byte-range boundaries and invalid inputs.

## Limitations and safety
- Source discovery and torrent authorization remain unchanged. The embedded HTTP URL is generated locally for a specific source; it is never saved to progress or the cloud.
- Playback still relies on peers and may buffer. This is not a zero-buffering guarantee.
- The server binds to loopback rather than the LAN; the URL uses an unguessable token, but other apps on the same device can theoretically connect to loopback.
- HTTP bridge starts only after a user-initiated authorized torrent selection, not as a background service.
- TorrServer remains an optional setting in this development version for compatibility; it is not required for local playback.
- The pre-existing 25-second torrent piece timeout remains. Failure causes a stream error rather than indefinite waiting.

## Required checks before a release
1. Run `gradlew.bat :app:testDebugUnitTest :app:assembleDebug`.
2. Verify Android TV playback on a lawful sample torrent (play/pause/seek, near-end MP4 index, next episode, exit and replay).
3. Verify no LAN port is exposed, no loopback URL is saved to Continue Watching, and other HTTPS sources still work.
4. Check Android 8–15 loopback cleartext policy on representative devices.
5. Verify release signing and version migration. Do not publish v1.8.8 until verified.

Published v1.8.7 and v1.8.6 remain untouched.

## Torrentio buffering improvements (follow-up)
- Adaptive read-ahead window targets approximately 8 MiB of upcoming torrent pieces (bounded to 4–16 pieces to limit requests on weak swarms), replacing the same four-piece window for every torrent.
- Nearby piece deadlines are spaced more closely (125 ms rather than 250 ms), while preserving bounded window and file-index checks.
- HTTP GET waits for the first verified torrent piece before sending a successful 200/206 response. If it times out before headers, it returns HTTP 503 rather than an incomplete successful response.
- Regression coverage added for small/large piece sizes, end-of-file and integer boundaries.
- These changes may improve playback smoothness when sources have enough peers; they do not change peer availability, codecs, rights checks or device storage requirements.
- Rebuild and compare Torrentio buffering with the previous v1.8.8 build; WebTorrent MP4 seek/play/pause and direct streaming are regression checks.

## Buffering diagnostic instrumentation
- Logcat tag `CineSaysTorrent` now emits `player_buffering`, `player_buffering_end` and `player_error` for torrent playback only.
- `piece_ready` logs piece waits >=500 ms; `piece_timeout` records 25-second failures. `peer_status` samples connected peers, download KiB/s, and piece size no more often than every five seconds while reads stall.
- The loopback bridge logs `http_first_piece` (>1 s), `http_transfer` (>3 s) and `http_failure`. It never logs stream URLs, auth tokens, peer IPs or torrent hashes.
- All diagnostics are on the existing v1.8.8 branch; no UI behavior, torrent permissions, or download timeouts were changed.
- Build: `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug`.
- Reproduce buffering; capture with `adb logcat -d -v time -s CineSaysTorrent:V > cinesays-torrent-diag.log`. To collect logs reliably, clear logcat immediately before playback, reproduce, and run the capture command while the affected device remains connected.

## 2026-11-23 Torrentio peer-ramp buffering follow-up

A diagnostic capture recorded an initial 9 KiB/s across 11 peers, rising to 2.6–4.35 MiB/s and 20–29 peers, with first-piece wait 5.62 s and an independent piece-1024 seek. This is evidence of a slow startup ramp, not a confirmed local-network problem.

Changes:
- A tail-of-file metadata probe within the final three pieces no longer resets the active startup playback window when the playback cursor is within the first nine pieces. It still requests the required tail piece urgently, preserving legitimate MP4/MKV index reads.
- Initial playback waits up to 1.2 seconds for the second piece after the first piece has already become readable (the wait is skipped if that piece is already ready or the file has only one piece).
- HTTP SocketException after sending headers is now logged as `http_client_closed` at info level, since Media3 may cancel range requests during seek/probing. Other HTTP errors remain warnings.
- JVM regression tests cover near-tail priority detection, distant seeks and boundary cases.
- Existing UI, source discovery, permissions, playback timeout, Trakker, Friends, and watchlist are untouched.

Build and device verification remain required after these commits. In Android Studio Terminal:
`git switch feature/embedded-torrent-http-1.8.8`
`git pull origin feature/embedded-torrent-http-1.8.8`
`.\\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`

Compare one permitted Torrentio source before and after: playback startup, buffering interruptions in first two minutes, seeking, then WebTorrent MP4 playback/seek. Capture `adb logcat -d -v time -s CineSaysTorrent:V` if issues persist.

## Stable multi-reader piece scheduling (follow-up)

Latest device capture observed ~4 MiB/s aggregate download but individual pieces waiting 19–21 seconds, suggesting that overlapping HTTP range requests and container probes were replacing important near-playback deadlines. The following scheduler change is limited to the existing embedded torrent player:

- The first active HTTP file reader owns the forward playback priority window. Other readers may request one piece at a time, but cannot reset that primary window.
- A reader leaving or being cancelled transfers ownership to the next active reader (important for seeking); a waiting reader rechecks ownership every 500 ms so promotion does not require a new HTTP request.
- Last-three-piece container tail probes retain their urgent deadline behavior without taking over playback priorities.
- `peer_status` now includes `readyAheadPieces` while stalled. `ready_ahead`, `auxiliary_piece` and `priority_handoff` markers help diagnose scheduling decisions without logging source URLs or peer addresses.
- JUnit regression tests verify overlapping readers and ownership transfers. The active WebTorrent path, source selection, allowed torrent hashes, and all UI features are unchanged.

**Verification pending:** these commits have not yet been compiled or installed on a test device. Run:
```powershell
cd D:\Projects\CineSays
git switch feature/embedded-torrent-http-1.8.8
git pull origin feature/embedded-torrent-http-1.8.8
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```
Then compare the same authorized Torrentio source, including repeated seeking, pause/replay, and the previously working WebTorrent MP4. Collect:
```powershell
adb logcat -c
# Reproduce playback and seeking
adb logcat -d -v time -s CineSaysTorrent:V > D:\Projects\CineSays\torrentio-scheduler-test.log
```

## Targeted metadata owner / stalled piece correction

The scheduler test on 2026-11-23 showed initial piece 0 in 3.5 s but two buffering interruptions of 49 s and 32 s. Libtorrent piece 118 and piece 209 each timed out twice at 25 s despite initially high aggregate peer throughput. A tail metadata reader (piece 1024) inherited ownership immediately after a playback reader closed.

- Final-three-piece metadata-only HTTP range readers are excluded from playback priority ownership. They can still fetch their requested piece as auxiliary readers. Next genuine video range reader takes ownership when the original playback reader closes; seek support is retained.
- A missing torrent piece gets its deadline refreshed at most once every 5 s of continuous waiting without resetting the playback window. This is a best-effort response to starvation, not a guarantee if the swarm lacks the data.
- Added `metadata_reader` and `piece_reprioritized` to the `CineSaysTorrent` log vocabulary.
- Added pure JVM tests covering tail-range classification, priority handoff, and metadata-only sessions.
- No UI, provider registry, remote endpoints, source authorization rules, or timeout changes.

The new code has **not yet been built or tested**. Keep the PR in draft pending compile, unit tests, WebTorrent playback and seek regression, and side-by-side Torrentio test. Capture `adb logcat -d -v time -s CineSaysTorrent:V` after testing. If repeated piece timeouts continue, compare another authorized torrent with a different swarm before modifying the scheduler again.

## Piece availability and partial-block diagnostic pass

The latest captured playback log showed piece 0 timing out twice and piece 210 repeatedly timing out even when 68–139 peers were connected and download throughput exceeded 5 MiB/s. The scheduler changes alone did not solve it. This commit adds **observability only**, not another timeout or scheduling modification.

While a requested video piece is stalled for at least two seconds, at most every five seconds per torrent session, `CineSaysTorrent` now reports:
- `peer_status` (existing peers, total KiB/s and ready-ahead verified piece count).
- `piece_diagnostic` with the actual waiting piece index; `state=verified|partial|not_in_partial_queue`; `advertisedPeers` (connected peers advertising this piece per libtorrent); `finishedBlocks`, `writingBlocks`, `requestedBlocks`, `totalBlocks`, and queue entry count.
- `-1` for block fields means there was no entry for that piece in libtorrent's partial download queue; it does **not** mean that no peer has the piece or that no block exists.
- `advertisedPeers=0` means none of the queried connected peers reported the piece in that snapshot; it does not prove it is unavailable across the global swarm.
- Diagnostic native queries use `TorrentHandle.pieceAvailability()` and `getDownloadQueue()` from the project's jlibtorrent 2.0.12.9 dependency and run on the existing blocking torrent read worker, not the UI thread. The operation is rate limited and errors are caught.

To validate:
```powershell
cd D:\Projects\CineSays
git switch feature/embedded-torrent-http-1.8.8
git pull origin feature/embedded-torrent-http-1.8.8
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
adb logcat -c
# Play a torrent the user is authorized to use and reproduce buffering.
adb logcat -d -v time -s CineSaysTorrent:V > D:\Projects\CineSays\torrent-piece-diagnostics.log
```
Compare the same source and another **independently seeded, authorized torrent**. Do not release until compile and device regressions pass. No content authorization, source-discovery, UI, playback deadlines, or player behavior changed in this diagnostic pass.

## Source health and non-destructive fallback (2026-11-23)

Following three Torrentio tests (one repeatedly stalled, another played, Big Buck Bunny remained playable), this update adapts the **existing CineSays player warning**, not the overall UI or source registry.

- Torrent playback shows a source-specific warning after **15 consecutive seconds buffering**, whether at startup or later after seeking.
- The warning displays a read-only snapshot of libtorrent peer count, connected-peer availability for the current requested piece and verified consecutive pieces ahead. Distinguishes no connected peers, no connected peers advertising the piece, scarce peers and slow piece delivery without claiming any guaranteed source quality.
- The existing **Other sources** action is available directly in the warning. It already returns to Watch Now through the main activity, keeping the title/episode selection and its discovered source list intact. There is **no automatic torrent switching** or unexpected provider calls.
- The old HTTP-specific slow-start warning is preserved for regular HLS/MP4/DASH streams.
- All source-health polling occurs on Dispatchers.IO and is limited to once every five seconds while stalled; no additional torrent prioritization, deadlines or permissions are changed.
- Pure JVM tests cover the classification cases; no UI screenshot or layout redesign was introduced.

### Verify
```powershell
cd D:\Projects\CineSays
git switch feature/embedded-torrent-http-1.8.8
git pull origin feature/embedded-torrent-http-1.8.8
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```
Install the new debug APK. Confirm the problematic source exposes a helpful warning and **Other sources** button after 15s; that returning to Watch Now retains the selected episode, available choices and D-pad focus; test a playable Torrentio source, WebTorrent Bunny and regular HLS/MP4 streams for regressions. Run `adb logcat -c` before testing and `adb logcat -d -v time -s CineSaysTorrent:V > D:\Projects\CineSays\torrent-health-test.log` afterward.

**This change is not yet locally compiled or device tested.** Preserve the draft PR until tests pass.

## Focused scheduler configuration trial — deadline window vs global sequential (2026-11-23)

Latest health log contains repeated incomplete piece-0 reads even with connected peers advertising piece 0, including sessions where it was absent from libtorrent's active partial queue. Code review showed a potentially competing strategy: `TorrentFlags.SEQUENTIAL_DOWNLOAD` enabled at the same time as explicit per-piece playback deadlines, seeking, and resettable priority windows.

**One reversible configuration change:** explicitly disable `SEQUENTIAL_DOWNLOAD` after selecting and prioritizing the approved video file, leaving `setPieceDeadline`, selected-file priorities, 25-second piece timeout, source authorization and UI unchanged. New startup log: `scheduler_mode deadline_window sequential=false`. `piece_diagnostic` now includes the effective `priority` returned by `TorrentHandle.piecePriority(piece)`, allowing a future trace to differentiate normal/ignored file pieces from scheduling stalls.

This is a **hypothesis test, not a verified performance fix**. Global sequential mode is useful for some torrents, but conflicts with the app's desire to jump to arbitrary seek positions. An individual source with poor peers may still fail. Avoid making other scheduling changes until a controlled test compares outcomes.

### Validation
```powershell
cd D:\Projects\CineSays
git switch feature/embedded-torrent-http-1.8.8
git pull origin feature/embedded-torrent-http-1.8.8
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
adb logcat -c
# Install, play the same authorized title, try a known-good torrent and Big Buck Bunny; test seeking.
adb logcat -d -v time -s CineSaysTorrent:V > D:\Projects\CineSays\torrent-deadline-mode-test.log
```
Confirm `scheduler_mode deadline_window sequential=false` appears, compare piece-0 completion and request block movement and flag any `priority=IGNORE` for the selected video. Keep PR #4 draft until successful build and on-device regressions.
