# Integration audit

Baseline: the uploaded CineSays(1).zip, snapshotted at 1ea102d on isolated branch cinesays-addon-torrents.

## Architecture and preserved boundaries

| Area | Existing implementation | Integration impact |
|---|---|---|
| Discovery/catalog | Api.kt, CatalogViewModel.kt: TMDB titles, IMDb mapping, providers/regions, search, discover, roulette | Only Stremio cache invalidation changes |
| Registry | Addons.kt: built-ins merged with cached/refreshed GitHub registry; saved On/Off choices | One optional Torrentio entry and explicit torrent capability |
| Stream protocol | StremioProtocol.kt, StreamModels.kt, StreamSources.kt | Validated torrent results flow to existing router; HTTPS host limits preserved |
| Routing | PlaybackRules.kt, WatchNow.kt, StreamingViewModel.kt | Explicit local torrent and optional debrid actions; current service/Stremio links preserved |
| Hybrid player | HybridPlayer.kt: Media3, HLS/DASH/progressive sources, audio/subtitle choices, remote control, resume | Native player/layout unchanged; Settings wording updated |
| Torrent transport | TorrentPlayback.kt: jlibtorrent, piece-aware Media3 DataSource, temporary directory | General file selection, space checks, requested-piece priority |
| Real-Debrid | RealDebrid.kt, RealDebridSettings.kt: REST API, encrypted credentials, explicit selection | Files untouched; original approved-hash path preserved |
| Progress | PlaybackStore.kt, StreamingViewModel.kt, ProgressRules | Storage/completion rules unchanged |
| Trakker | TrackerStore.kt, TrackerViewModel.kt, TrackerPage.kt, TrackerAddShows.kt, TrackerReminderWorker.kt, TVTimeImporter.kt | Files untouched; MainActivity's completed-event bridge retained |
| UI/navigation | MainActivity.kt, Screens.kt, DesignKit.kt, FreeWatch.kt | Settings description only; navigation, theme, modifiers, and focus handlers unchanged |
| Social | SocialPages.kt, SocialRepository.kt, Firebase rules | Untouched |
| Releases | AppUpdater.kt, Gradle scripts, Android manifest/resources | Untouched; no version/signing/dependency changes |

The review traced discovery → parser → router → preparation → Media3 → saved progress → Trakker completion, as well as Add-ons settings and retry. Full-project file comparison protects unrelated code/assets. Static review does not substitute for on-device UI or playback testing.

## Test evidence

See TORRENTIO-INTEGRATION.md for the complete changelog and validation status. The Gradle attempt failed before compilation with `java.net.SocketException: Network is unreachable` while fetching Gradle 9.3.0. Existing and added JVM tests require execution in Android Studio. The APK included in the upload was used only to check dependency method signatures, not presented as a new build.
