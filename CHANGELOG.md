## 2026-11-23 — Source discovery and torrent startup

Show provider results as they arrive, remove normal registry refresh from the critical path, and preserve bounded read-ahead with startup header/index priorities. See SOURCE-STARTUP-UPDATE.md for complete changes and verification limits.

## 2026-11-22 — Trakker Watch Now source discovery

Resolve/cache missing show IMDb IDs, start selected-episode discovery alongside season loading, suppress duplicate searches and separate identifier failures from empty source results. UI and torrent transport unchanged. See TRAKKER-DISCOVERY-FIX.md for complete changes and validation limits.

## 2026-11-22 — Source heading cleanup and size order

Neutral source card headings, smallest-first size ordering, and removal of Real-Debrid playback and Settings controls. See SOURCE-ORDER-UPDATE.md for scope and validation.

## 2026-11-22 — Source details and loading improvements

Renamed the torrent section to Other Sources, added detailed source cards, retained provider descriptions, reused manifests/registry data, added cancellable HTTP discovery and bounded playback read-ahead. See SOURCE-DETAILS-UPDATE.md for complete changes and validation limits.

## 2026-11-22 — Opt-in add-on torrent sources

Added optional Torrentio discovery, general validated file-index selection, free-space checks, seek-piece priority, and torrent lifecycle cleanup for Next episode. Preserved existing screens, layouts, navigation, themes, D-pad handlers, Trakker and Real-Debrid paths. See TORRENTIO-INTEGRATION.md for the complete file-by-file changelog and installation steps. Build and JVM tests are blocked here before compilation by the unavailable Gradle download; Android Studio validation is required.

# CineSays 1.8.0 — Hybrid player
- Watch Now: one button on every movie and series page shows every way to watch: play in CineSays (legal sources), open your streaming service, or open in Stremio (when installed). Series pick a season and episode first, with watched marks, progress and "Mark watched".
- New CineSays player: HLS, DASH and MP4; Quality, Audio, Subtitles and Speed pickers that list what the stream really offers; remote control (Left/Right seek 10 s, Play/Pause, Back); clear error messages with Retry.
- Resume: CineSays remembers where you stopped and asks "Resume from 12:07" or "Start from beginning". Home gets a Continue watching row.
- Tracker: titles played in CineSays are marked watched once you pass 90% (adjustable); opening another app never marks anything.
- Settings › Playback & Streaming: preferred quality, audio and subtitle language, subtitle size, resume behaviour, what Watch Now opens, buffering, and playback diagnostics with HLS/DASH test streams.
- Stremio-compatible add-on support, limited to CineSays' curated list and approved stream hosts. CineSays doesn't play torrents.

# CineSays 1.7.0
- Add-ons: the Free tab is now built from add-ons — catalogues of legal sources you can turn on or off in Settings › Add-ons. New collections, all playing in CineSays: Silent era, Film noir, Sci-fi & horror classics, Cartoons & open movies (Sintel, Elephants Dream…), Classic TV and Vintage shorts (Prelinger Archives, off by default), alongside Classic feature films and Live news channels.
- Only legal sources: CineSays plays public-domain and openly licensed films from the Internet Archive and broadcasters' official live streams — nothing else. New add-ons can arrive from CineSays' own add-on list without an app update, but they can only use those sources.
- "Watch free in CineSays" on a movie's page now searches every film add-on you have on.
- Watch now: HBO Max opens the title's page instead of bouncing to the app's home screen, and partner listings such as "HBO (via Hulu)" no longer get picked for the wrong app.

# CineSays 1.6.0
- Watch now: "Watch on Netflix" (and every Where to watch logo, Free on Prime Video and the Roulette pick) now opens the exact movie or show in that service's app, not just the app's home screen. Falls back to opening the app when a title link isn't known.
- Title links come from Watchmode.com (free plan: add WATCHMODE_API_KEY to local.properties). Each title is looked up only when you press a button and remembered for 30 days.

# CineSays 1.5.0
- Free tab: new "Free on Prime Video" row — the free movies and shows IMDb links to (Amazon's free-with-ads catalogue, formerly Freevee). They open in the Prime Video app; an Amazon sign-in is needed, not a Prime subscription. Available in the US, UK and India.
- Details: "Free on Prime Video" button when a title is free there, marked with its region (e.g. "(US)") when it isn't free in all of yours. Hidden if you already subscribe to Prime Video.
- Details: new "IMDb ↗" button opens the title on IMDb.
- Discover: new "Prime Video (free)" service filter; the "Free (any)" filter and Home "Free to watch" row now include it too.

# CineSays 1.4.0
- New Free tab: live news TV (Al Jazeera, DW, France 24, Sky News, ABC News, Bloomberg, euronews, NASA) and public-domain classic films that play right inside CineSays, plus free-with-ads picks from Tubi, Pluto TV and Plex.
- Watch free in CineSays: public-domain titles (e.g. Night of the Living Dead) get a play button on their details page.
- Trailers now play inside the app.
- Home: new "Free to watch" row. Discover: new "Free (any)" service filter.
- Notifications: tapping a new-episode reminder opens that show.
- Tracker › Manage data: removing an import now removes it completely, and the removal syncs to your other devices. New "Remove all imported history" and "Clear all tracker data" options.
- Settings: new Khanpur Boys logo, "Imagined by Khanpur Boys".

# CineSays 1.3.1
- Tracker › Manage imports: remove a TV Time or BingeBoxd import entirely (its shows, episodes and movies). Episodes and movies you marked yourself are kept. Every record now remembers its source; existing data is tagged on upgrade.
- Tracker › Up Next: Continue watching sits in its own scrolling box (Coming soon matches its height), so the screen stays even however many shows you're watching.
- Actor pages: select anyone in a title's cast to see their photo, details, bio and all their movies and shows (major roles first; talk-show and reality guest spots left out), with All / Movies / TV filters.
- Phones: Tracker header, tabs, import buttons and week strip no longer wrap or get cut off; rating labels stay on one line.
- Settings: "Developed by Khanpur Boys" credit.

# CineSays 1.3.0
- Phones: CineSays now installs and runs on Android phones and tablets, with stacked layouts on narrow screens.
- Account & sync (Settings): create an account or sign in to back up your tracker, watched movies, watchlist, likes, services and regions, and keep your friends on any device. Backs up automatically when you leave the app.
- Import from BingeBoxd (as well as TV Time): shows, episodes and movies, matched exactly by TMDB id.
- Tracker: A–Z jump in My Shows; Continue watching now includes every show you're partway through (not just recent releases); new Movies tab; new "Add shows" tab to build your tracker without an import (Watching / Watched it / Skip).
- Movies: "Mark watched" on any movie.
- Home: "Recommended for you", based on what you like (new ♥ Like button), your watchlist and what you watch. Up next cards now open the series in Tracker; a separate ✓ button marks the episode watched.
- Remote navigation: Back first jumps to the tab bar, then Home.
- Fixed: a show's "furthest episode" mixed seasons up (e.g. S2E10 + S3E1 read as S3E10).

# CineSays 1.2.0 — redesign
- New look across the app: top tab bar (Home, Discover, Tracker, Watchlist, Roulette, Friends) replaces the 11-item sidebar.
- Home: rotating hero, 'Up next from your Tracker' row, 'New on your services' first.
- Title details: poster + info layout, 'Open in <your service>' button, region x Stream/Free/Rent/Buy grid, cast, More like this.
- Discover: Movies, TV Shows and Genres merged, with Type/Service/Genre/Sort filters and a poster grid.
- Roulette: moods, length and source (My services / watchlist / anything); result shows where to watch with a direct Open button.
- Tracker: new Up Next tab (one card per show, one-press Watched, Coming soon); now checks every followed show, not just the first 30.
- First-run My Services screen.

# CineSays 1.2.0
- New services: Zee5, JioHotstar, Sony LIV, MUBI, and free-with-ads Tubi, Pluto TV, Plex, YouTube (free). IDs verified against TMDB.
- Details: new 'Free with ads' group; free services count as 'Included with your services'. Missing apps open in the Play Store.
- New: Settings > App updates checks GitHub Releases (UPDATE_REPO in local.properties), downloads, verifies SHA-256 and installs. See RELEASING.md.

# CineSays 1.1.0
- Fixed: Back from Tracker could exit the app without the confirm dialog.
- Fixed: Search and Watchlist shared one list; TV Shows tab showed Apple TV movie row; hidden provider filter on Genres; Roulette 'no titles' on small result sets; Tracker button not updating; stale search/genre/detail responses overwriting newer ones; errors stuck on every screen.
- Faster: Home rows load in parallel; details use one TMDB request; 20 MB HTTP cache; smaller posters; Tracker releases cached 30 min and watched checks moved off the main thread.
- New: My Services (Settings) with a 'New on your services' Home row, a 'My services' filter in Genres and Roulette, and 'Included with your services' on title details.
- New: rotating Home hero, real-content grids for Search/Watchlist/Genres, title runtime/genres/tagline, wheel eases to a stop, Disney+ in filters.

# CineSays 0.3.0
- New Cinema Studio / Concept 2 layout with left sidebar, wide hero and quick discovery controls.
- Multi-select streaming regions saved to DataStore; previous single-region setting migrated.
- Discovery unions titles across all selected region queries and deduplicates them.
- Details group streaming providers independently by region.
- Updated UI copy and version number.

Files changed: `MainActivity.kt`, `CatalogViewModel.kt`, `app/build.gradle.kts`, `README.md`.
