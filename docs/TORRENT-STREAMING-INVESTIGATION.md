# Torrent startup and seek investigation — 2026-11-23

Status: implementation candidate; Android TV validation is a release gate.
Base: `feature/embedded-torrent-http-1.8.8` at `9ca4753a644b965218161b3d558e20723d4c92bf` (draft PR #4).
Work branch: `feature/torrent-seek-recovery-loading`. The existing branch/PR is not replaced.

## Evidence and limits

The supplied `Stremio.apk` identifies as `com.stremio.one`, version **2.5.1**, code **20500001**, minimum SDK 32, target SDK 36.

- APK SHA-256: `e83aacdf1d210c73ebacaf007dfa9210b51b69093beaadfb3293c6b16b59d274`.
- `assets/server.js`: 4,759,280 bytes; SHA-256 `c9e6d6c2faf68d6bbadc2ce6f318b94a59b3c4a8f3faf73da89df8776f8d1652`.
- Binary manifest decoded with Androguard. It declares INTERNET, ACCESS_NETWORK_STATE, WAKE_LOCK, foreground service/media playback permissions, cleartext traffic, `largeHeap`, `com.stremio.common.players.MediaPlaybackService`, and non-exported `com.stremio.android.ServerService` in `:stremio_server_process`.
- Three DEX files include Media3 ExoPlayer and LibVLC components. `StremioServer` declares native `startNodeWithArguments`; its static initializer calls `System.loadLibrary`, including `node` and `stremio-server`.
- **No native `.so` entries are present in this supplied APK.** The manifest declares `com.android.vending.splits.required=true`. This is not a complete installed split set. Native runtime internals, codecs, and the exact Android TV build used in the comparison cannot be established from this APK alone. The supplied package's minimum SDK also differs from many older Android TVs.
- The JavaScript bundle identifies `Stremio/torrent-stream` commit `4d9eaff84e3b7a1008314daa007d5feb6fa26388`. That repository was not publicly readable during this investigation. The bundled implementation itself supplies the selection/timeout evidence below; a third-party “Stremio native” project is not treated as the official engine.
- No Stremio binaries, decompiled source, bundled JavaScript, artwork, or branding have been copied into CineSays. Implementation changes are independently written against libtorrent/Media3 APIs.

To reproduce inspection: inspect ZIP entries, decode AndroidManifest.xml, inspect the two named service/server classes' DEX instructions, and format `assets/server.js` for reading. Locate `FileStream`, `engine.select`, `engine.deselect`, `engine.critical`, `onhotswap`, `enginefs.getDefaults`, and `pump(handle.createReadStream(...), res)`. The formatted bundle's line numbers depend on formatter settings, so these symbols and the bundle hash are the evidence anchors.

## Architectural comparison

| Area | Verified in supplied Stremio artifacts | CineSays at base commit | Change / implication |
| --- | --- | --- | --- |
| Engine | Node server using a modified torrent-stream engine; native entry point runs in a separate service process | Fresh jlibtorrent session and temporary directory per playback; loopback HTTP bridge | Retain libtorrent and the working UI. Persistent service/DHT reuse remains future work; no claim of matching cold metadata discovery time. |
| Read granularity / verification | `virtual:true` splits suitable pieces above 512 KiB into 512 KiB virtual chunks. The download callback sets the virtual bitfield and notifies readers even when full-piece `store.verify` returns null; FileStream reads this bitfield. Full SHA-1 verification waits for the whole original piece. | `havePiece` gates every disk read on whole native torrent pieces | Important architectural difference for the earlier 8 MiB-piece source: Stremio can make a smaller chunk readable earlier. CineSays retains whole-piece verification. Timing impact is plausible but not measured; equivalent pre-verification streaming would require a different engine/data contract. |
| Active selections | FileStream owns a selection; EOF/close deselects it. Reads update a bounded selection in memory-cache mode. HTTP uses `pump` lifecycle cleanup. | One primary deadline-window owner; other readers get one urgent piece; whole selected file stays NORMAL | Every active range gets its own bounded selection; simultaneous header/index/resume reads can proceed. |
| Cancellation | Closing the stream removes its selection; HTTP response lifecycle is tied to stream cleanup | Disconnect is usually discovered on the next socket write, after a missing-piece wait; three workers with an unbounded queue | Detect disconnect while waiting; cancellation is polled by the piece reader; six bounded workers, no pending task queue; close sockets on session shutdown. |
| Obsolete priorities | Selection removal stops demand for that range | `setPieceDeadline` promotes a missing piece; `resetPieceDeadline` removes time-critical status but does **not** undo its piece priority | Removed pieces have both deadline reset and priority set to IGNORE. Initial priorities become IGNORE only after the selected file's storage-priority operation completes. |
| Seeking | FileStream derives its piece selection from the requested byte interval | Ownership handoff requires a 16-piece jump; final three pieces are classified as metadata | Selection is based on actual read lifecycle, not a guessed distinction between metadata and video. Near seeks and final-file playback also receive windows. |
| Stalled blocks | `onhotswap` can release slow reservations for a faster wire; bundle fallback request timeout is 4,000 ms | Native engine defaults plus changing deadlines; no custom block reassignment | Rely on libtorrent's built-in time-critical duplicate-request recovery. Stop periodic deadline refreshing and avoid downloading outside live selections. No aggressive peer eviction or blanket 4-second timeout was added. |
| Player HTTP timeout | Exact active client setting not proven by static bundle inspection | HTTP reader timeout 40 seconds, piece wait may extend to 60 seconds | Local torrent HTTP reader timeout 70 seconds; ordinary HTTP streams retain 15 seconds. |
| Startup gate | Current FileStream waits for its requested data and marks critical pieces | Adds up to 1.2 seconds of next-piece warmup before returning the first request | Remove the extra blocking warmup. Media3 still controls decoded playback buffering. |
| Loading presentation | Package contains player/loading components; exact runtime layout not benchmarked | Generic preparation spinner; player spinner | CineSays title and available poster shown during preparation and torrent buffering, using existing artwork data. Existing remote controls, warnings, retry and other-sources actions remain. |

Libtorrent's own time-critical algorithm excludes unusable/choked/snubbed peers and can duplicate stalled block requests adaptively. A connected peer advertising a piece is **not** proof that it is currently willing or able to upload it. Likewise, absence from the partial download queue alone does not prove a scheduling bug.

## Findings versus hypotheses

**Verified code defects/constraints:** obsolete priority promotion survives deadline removal; abandoned HTTP reads have no disconnect signal during piece waits; HTTP and piece-wait timeout budgets disagree; owner handoff/tail classification restrict concurrent demand; startup adds an explicit warmup delay.

**Verified granularity difference:** with `virtual:true`, the bundled server can notify a reader after a 512 KiB chunk is received, before the original 8 MiB piece is complete and its hash can be checked. CineSays's existing libtorrent contract waits for the entire verified piece. On this source that is a 16:1 difference in the unit that must become readable; it is not a measured 16-times speed improvement. Reproducing speculative reads is outside this candidate. The full-piece integrity gate remains in place.

**Plausible contributors requiring device A/B measurement:** wasted bandwidth from background selected-file download; stale HTTP workers delaying a new seek; Stremio's faster reservation recovery; fresh DHT bootstrap versus a persistent server. These explain mechanisms, not proven causes of the reported 5:16 or 13–14-minute failures. Those positions are media timestamps, not fixed piece indexes. The earlier `torrent-seek-release-test.log` was also reviewed: 8 MiB pieces, piece 0 missing from the partial queue while 12–45 peers advertised it, 5–6 other queued pieces, and a 25-second timeout before HTTP headers. This verifies starvation of the requested region in that trace, but not why particular peers did not serve it. The candidate logs queue piece indexes and aggregate unchoked/interested/snubbed peer counts to distinguish demand errors from peers that cannot presently upload. Read-ahead for large pieces is reduced from a four-piece minimum to two (16 MiB rather than 32 MiB for this trace).

**Not established:** that every earlier failure is fixed; exact Stremio Android TV native settings; a universal startup-time improvement; reliability on unavailable/choking swarms. Media3 container indexing and codec support can still differ from LibVLC. Playback checks must use the same torrent hash, file index, device/network and comparable cache state.

## Source references

- Official Stremio core, inspected at `d3e05c2a5f6680651836125152e10eb90ae8f8e2`: https://github.com/Stremio/stremio-core/blob/d3e05c2a5f6680651836125152e10eb90ae8f8e2/src/types/streaming_server/settings.rs — server settings expose connections, handshake/request timeouts, cache size and stability parameters; this does not reveal their active runtime values.
- Exact jlibtorrent API used by this project: https://github.com/frostwire/frostwire-jlibtorrent/tree/release/2.0.12.9 — TorrentHandle priority/deadline APIs, FilePrioAlert and SessionManager behavior.
- Native streaming algorithm: https://www.libtorrent.org/streaming.html — urgent-piece scheduling and adaptive duplicate requests; current documentation describes 2.1, so avoid assuming every default matches bundled 2.0.12.
- API semantics: https://www.libtorrent.org/reference-Torrent_Handle.html — file-priority completion, piece priorities, deadlines and reset semantics.
- Settings behavior: https://www.libtorrent.org/reference-Settings.html — request and peer timeout semantics. Short global request timeouts can waste bandwidth; the APK's 4-second fallback was not copied blindly.

## Validation and Android TV procedure

Automated results for this revision are recorded in `TORRENT-VALIDATION.md`.

1. Check out the new branch, retaining your local `local.properties`, Firebase/TMDB configuration and signing files. Do not discard local changes. Use JDK 17 and an installed SDK 35 / Build Tools 34.0.0. Run `./gradlew :app:testDebugUnitTest :app:assembleDebug` (Windows: `gradlew.bat`).
2. Install `app/build/outputs/apk/debug/app-debug.apk` on a test device with `adb install -r ...`. A locally signed debug APK cannot replace a release-signed installation; use a test device or an installation with the same signing key. Do not uninstall a production installation merely to bypass a signing mismatch, since that removes its local data.
3. Clear logcat, start the app, and choose the approved WebTorrent Big Buck Bunny source. Check title/poster preparation, initial playback, pause/replay, forward/backward seeks and Other Sources/Back. The artwork may fail to load offline; it must not prevent playback.
4. With content you are authorized to access, reproduce the original Title 1 resume at **14:00**, seek to **5:16**, and play from beginning; Title 2 resume at **13:00**. Keep exact hash/file index constant. Bunny alone is shorter than some requested resume positions, so it cannot validate all these scenarios.
5. Stress repeated seeks before the previous seek resolves, a seek near the final minute, background/Home then return, and close/reopen playback. Verify there is no stale-range backlog. On a weak/unavailable swarm, the app must stop waiting within its bounded policy and retain Retry/Other Sources/Close. Reopening a failed range may trigger Media3's normal bounded retries, so total error time can exceed one piece budget.
6. Regress direct MP4/HLS/DASH, subtitle/audio choices, Settings buffering choice, Trakker, Friends, Recommendations, Watchlist, continue watching/resume and remote focus/navigation. No data schema, source discovery, social, tracker or settings persistence code changed.
7. Capture `adb logcat -d -v time -s CineSaysTorrent:V > torrent-live-demand-test.log`. Inspect `scheduler_mode live_read_demands`, `read_demand`, `piece_diagnostic`, `piece_ready`, `http_client_closed`, `piece_timeout` and player buffering/error entries. Logs intentionally omit stream URLs, auth tokens and peer identities.
8. Record cold and warm time-to-first-frame, seek-to-first-frame, timeout count and source identity for both base and candidate builds. Repeat each scenario at least three times. Do not infer performance from peer count alone.

Keep both PRs draft and do not merge, tag, publish a release or distribute as production until build, native tests, on-device scenarios and regression checks pass. The build here has no private local API configuration or production signing material.
