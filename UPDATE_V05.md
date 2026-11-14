# CineSays v0.5 — Cinema Studio / Selected Genres & Roulette

This is an update to the existing project, not a second application. Package remains `com.cineglass.tv`; your stored watchlist and regions remain on the device when installed with `adb install -r`.

- Genres: user-selected first gallery layout, individual focusable artwork cards, movie/TV/both selection, multi-region quick filter, live TMDB discovery results below the grid.
- Roulette: user-selected last large wheel layout, genuine spinning animated wheel with stationary pointer, genre/provider/region/content-type selection, selected title card, watchlist and details/trailer actions. A minimum ~2.2 second spin is enforced while fetching results.
- Home: wider sidebar with full labels, TV-sized compact navigation, less washed-out and taller dynamic hero.

**Installation:** Back up your current project. Open extracted CineSays project, copy your `local.properties` API keys, Gradle sync, rebuild, and install. This code has not been compiled or device-tested here.

**Important limitations:** The roulette result uses TMDB and may not match a region in an unfiltered trending row; selecting a provider uses region-based provider filtering. Trailer button opens details, from which the existing YouTube trailer action works. IMDb/Rotten Tomatoes are loaded for detail pages, not preloaded into roulette cards. Live cinematic backdrops differ from the static reference because they come from TMDB.
