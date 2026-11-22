# CineSays Real-Debrid integration

## Codebase audit

- TMDB supplies title and episode metadata, IMDb IDs and regional provider offers. Watchmode supplies optional exact-title links for the existing streaming service buttons.
- Watch Now uses `StreamingViewModel`, `StreamRepository`, `PlaybackRouter` and the source cards in `HybridPlayer.kt`. The existing Stremio adapter already understands the movie and series stream resource, and the existing router handles Media3 playback, official service apps and the Stremio app.
- Settings already has Playback & Streaming and Add-ons sections. The shared Compose controls in `DesignKit.kt` provide the TV focus styling. The player retains its current controls, seek behavior and track selection.
- CineSays calls its tracking feature Tracker. `PlaybackStore` records progress. `MainActivity` forwards completion events from `StreamingViewModel` to `TrackerViewModel`. The Real-Debrid path uses this same flow.

## Changelog

- Added opt-in handling for Torrentio-style Stremio stream responses with approved hexadecimal BTIH hashes, including bare hashes and standard hexadecimal magnet links. Ordinary Stremio entries retain their existing handling.
- Added an explicit Real-Debrid source choice in Watch Now, after the existing native, service and Stremio options. Automatic destination preferences never initiate a Real-Debrid job.
- Added personal API token setup and removal under Playback & Streaming. The controls appear when an authorized Real-Debrid add-on is enabled. The token is encrypted using Android Keystore and sent only to the official Real-Debrid REST API.
- Added documented magnet submission, torrent status and file selection, and link unrestriction. For multi-file torrents, the add-on must identify the exact file by filename or path. An ambiguous match is rejected.
- Handed the generated HTTPS URL to the existing Media3 player. Saved position and Tracker completion still use the shared playback path. Generated Real-Debrid URLs are kept out of the progress store. Continue watching reopens Watch Now so the viewer can select a fresh link and resume at the saved position.
- Added unit tests for opt-in routing, missing authorization, malformed hashes and indices, and ambiguous file selection. No new dependencies, Gradle configuration, app version changes or database migrations.

## Configure an authorized source

The app continues to use its existing curated add-on registry. Add a new entry to that registry for an endpoint you are authorized to use. The example values below are placeholders. Populate the hash list only with content approved for distribution or access. The endpoint must implement the Stremio manifest and stream resources. For episode packs, supply an exact `behaviorHints.filename` for each episode.

```json
{
  "id": "example.authorized.streaming",
  "name": "Authorized video catalog",
  "description": "Approved open or licensed media",
  "engine": "stremio",
  "manifestUrl": "https://addon.example.org/manifest.json",
  "allowedHosts": ["media.example.org"],
  "defaultOn": false,
  "realDebrid": true,
  "authorizedTorrentHashes": ["0123456789abcdef0123456789abcdef01234567"]
}
```

After the registry entry is available, enable it in Settings › Add-ons. Open Playback & Streaming › Real-Debrid and save a personal token from [Real-Debrid API tokens](https://real-debrid.com/apitoken). Open an authorized title or episode and select the corresponding Real-Debrid source in Watch Now. Slow downloads can remain in the Real-Debrid account after the app stops waiting or the viewer presses Back. A new selection can create a new account job.

No production registry entry was published. Live playback requires an authorized endpoint and an active Real-Debrid account that can resolve the source.

## Verification

- Baseline and modified project: attempted `bash gradlew assembleDebug testDebugUnitTest`. The wrapper could not download Gradle 9.3.0 because network access to the distribution host failed. Offline mode also stopped at the wrapper because the distribution was missing.
- Build, unit test execution, live service playback and Google TV D-pad regression testing remain unverified.
- For Google TV verification, test an existing direct source, service launch, trailer, Free tab playback, Settings navigation, Tracker completion and resume with the feature disabled. With it enabled, repeat with an authorized movie, a series episode and a rejected token, and check that Back from preparation returns to the source list.
