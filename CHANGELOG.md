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
