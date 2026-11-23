# Direct-first source discovery (development branch)

Baseline: `release/cinesays-1.8.6`. All changes remain on `feature/direct-stream-discovery`.

## Configured behavior

- Source discovery runs when **Watch Now** opens for a movie, or when an episode is selected. Existing progressive source search is retained.
- `preferredSource = direct`: playable HTTP HLS/DASH/progressive sources appear before authorized torrent choices in the source router.
- Internet Archive direct MP4 remains the existing configured provider; no duplicate Archive integration was added.
- Progressive torrent playback continues to use the existing piece-aware Media3 DataSource, bounded reads and prioritized pieces; it **does not promise zero buffering**.
- `authorizedTorrentToHttpPlayback = true` checks an explicitly configured `AuthorizedTorrentHttp.Gateway` first when a torrent source is selected; without one, it falls back to local progressive torrent playback.
- The gateway has **no default implementation**. The preference enables the capability, not a paid service, token resolver or new catalog.
- Existing service links, UI pages, Trakker, watch history and signed release remain unchanged.

## What is deliberately not included

- No Movies.do or LoadShare private API scraping, session cookies, signed URL harvesting or attempts to bypass service restrictions.
- No Plex AVOD partner credentials or licensed catalog, because partner approval is required.
- No Real-Debrid re-enablement or paid provider.
- No new APK or release version.

## Next engineering tasks

1. Add a vetted, licensed direct streaming provider using the existing `StreamProvider` interface and declared host allowlists.
2. Configure `AuthorizedTorrentHttp.Gateway` only for a provider that permits API access and media playback; handle account authorization and expiring URLs outside the public code.
3. Test `./gradlew :app:testDebugUnitTest :app:assembleDebug` locally, followed by device tests for source ordering, focus, subtitles, seek, playback resume, and torrent fallback.

No Android build, unit tests, or device playback were executed by this GitHub-only change.
