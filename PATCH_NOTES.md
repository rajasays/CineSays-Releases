# CineGlass TV — Focus and ratings update

## Fixes
- Movie/TV details now open in a full-screen Compose `Dialog`, preventing D-pad focus and scrolling in the background.
- Initial remote focus moves to the details Back button. The remote Back action closes the dialog.
- Details show TMDB, IMDb and Rotten Tomatoes ratings. IMDb and Rotten Tomatoes use optional OMDb data, matched via the TMDB external IMDb ID.
- Missing scores display `N/A` instead of invented ratings.

## Setup
In the project root `local.properties` (do not share or commit keys):

```properties
TMDB_API_KEY=your_existing_tmdb_v3_key
OMDB_API_KEY=your_omdb_key
```

OMDb keys: https://www.omdbapi.com/apikey.aspx . OMDb availability, quotas and usage rights apply; review its terms for your distribution model. Sync Gradle, rebuild and install APK.

## Installation
These are replacement sources based on the original CineGlassTV project archive and the previously corrected CatalogViewModel. Back up your current project first if you have made other changes.

## Validation
Not compiled in this environment. Test the detail panel D-pad navigation and Back behavior on the target Google TV. Check IMDb and Rotten Tomatoes scores on several titles; `N/A` is expected when source data is absent.
