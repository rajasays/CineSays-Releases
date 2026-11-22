# Trakker Watch Now discovery fix — 22 November 2026

Based on your latest CineSays(3).zip. Separate branch: cinesays-trakker-discovery. Uploaded baseline: 5dcfec3.

## Install

Extract the update ZIP into your existing project root and replace the included files. Sync Android Studio, then run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Install the new APK. Keep Torrentio enabled in Settings → Add-ons. Open Trakker, select an episode, and choose Watch Now. Results appear in the existing Other Sources cards, with the current details and size ordering. If an earlier result is still open, close and reopen Watch Now or use Search again.

## Root cause

Trakker's episodeRef provides a TMDB show ID and season/episode numbers but no IMDb ID. The previous Watch Now implementation relied on the title details screen supplying IMDb metadata. Without it, StreamProvider.streamsFor returned an empty list without making an add-on request. The router then displayed the misleading no-source message.

## Complete changes

- MediaIdResolver.kt: resolve missing/invalid IMDb IDs using the existing TMDB external_ids API, with a bounded 128-title in-memory cache keyed by movie/tv plus TMDB ID. Known IDs skip network access and seed the cache. Failed or missing lookups remain retryable. Preserve the episode numbers and progress key. A mutex prevents duplicate concurrent lookups.
- StreamingViewModel.kt: start ID resolution alongside season loading; start discovery immediately for a preselected Trakker episode; update selected/base refs with the resolved ID; pass the resolved ref to discovery and routing. Suppress the duplicate search when season loading selects the same episode. Cancel old metadata, season, search and preparation jobs when opening a different Watch Now request. Show a specific title-lookup failure if metadata cannot be resolved, while preserving available Archive/service results. Retry can resolve failed lookups again.
- StreamSources.kt: include the Stremio video ID in the result cache key so an empty result produced with an unresolved ID cannot mask results after resolution.
- MediaIdResolverTest.kt: five regression tests covering Trakker ID resolution and episode request URL, show-level reuse, supplied IDs, retry after a missing ID, and movie/TV cache separation.
- CHANGELOG.md and this document: setup, changelog and verification.

## Preservation and performance

No Compose/UI files, layouts, themes, focus handlers, navigation, source cards, source sorting, player controls, Trakker storage, or torrent transport were changed. Existing source and playback improvements in your upload are preserved.

The speed improvements remove avoidable waits before discovery: metadata and season loading overlap; selected episode discovery need not wait for the episode list; later episodes reuse the show ID; the same episode is not searched twice. Torrent buffering/read-ahead stays as in your latest working project. No measured speedup or peer-dependent startup improvement is claimed.

## Validation

Git whitespace review passed with existing CRLF endings recognized. Patch application and ZIP integrity were verified against the uploaded baseline. Existing UI files are unchanged. Attempted `:app:testDebugUnitTest :app:assembleDebug`, but Gradle 9.3.0 could not download (`Network is unreachable`). Compilation and JVM tests did not run here; no new APK is included.

Test in Android Studio and on your phone: Trakker → episode → Watch Now, switch episode, retry a failed lookup, close during discovery, and reopen a different show. Confirm the original movie flow, Other Sources details/size ordering, resume and watched-state updates. Some titles can still lack an IMDb mapping or available sources; a missing mapping is now reported separately from an empty provider result.
