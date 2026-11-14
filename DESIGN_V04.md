# CineSays v0.4 — Cinema Studio replica

Visual target: `cinesays_cinematic_tv_dashboard.png` (generated concept image).

## Changes
- Completely redesigned main Home screen: compact icon-based side navigation, deep dark background, blue focus glow, cinematic wide hero, light translucent hero text panel, service logos, clickable genre artwork, roulette art and live TMDB poster rows.
- Uses cropped imagery from the generated reference artwork, not screenshots from third-party websites.
- All genre cards open real TMDB discovery filters; service cards open discovery with that service filter; roulette opens existing working roulette.
- Hero and poster data remain live from TMDB, so title imagery will differ from the *illustrative* concept.
- Existing details, trailers, IMDb/Rotten Tomatoes ratings, multi-region availability, watchlist, and D-pad focus retained.

## Limitations
The hero illustration and decorative tiles are from the selected visual reference; the hero title/ratings update dynamically from TMDB. It is therefore a faithful layout/style implementation, not a pixel-for-pixel static copy of example film titles. UI not built/tested in this container; open in Android Studio and validate on TV.

Copy `local.properties` from your existing project (TMDB_API_KEY and OMDB_API_KEY) before sync/build. Application ID remains `com.cineglass.tv`.
