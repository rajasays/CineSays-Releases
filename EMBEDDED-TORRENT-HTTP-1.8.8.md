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
