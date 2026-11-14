# CineSays for Android TV

A movie & TV companion for Android TV / Google TV: see what's new on the services you pay for,
find where to watch anything across several countries, track episodes, spin a mood-based roulette
when you can't decide, and swap picks with friends.

**[⬇ Download the latest version](https://github.com/rajasays/CineSays/releases/latest)**

## Install on your TV

CineSays isn't on the Play Store, so you install it with the free **Downloader** app.

1. On your TV, open the **Play Store**, search for **Downloader** (by AFTVnews) and install it.
2. Open Downloader and type this address into its URL box:
   ```
   https://github.com/rajasays/CineSays/releases/latest/download/CineSays.apk
   ```
3. When asked, allow **Install unknown apps** for Downloader, then choose **Install**.
4. Open **CineSays** from your apps row. You can delete the downloaded file in Downloader afterwards.

Requires Android TV 8.0 or newer. Because the app comes from outside the Play Store, Google Play
Protect may ask you to confirm the install.

### Updates
CineSays checks this page for new versions. When one is out, you'll see **⇩ Update** in the top bar:
open **Settings › App updates › Download & install**. The first time, Android asks you to allow
CineSays to install apps. Your watchlist, services and Tracker history are kept.

## What's inside
- **Home:** trending titles, "Up next" from your Tracker, and what's new on your services.
- **Discover:** movies and series by type, service, genre and sort, combined across the regions you pick.
- **Title details:** ratings (TMDB, IMDb, Rotten Tomatoes), trailer, cast, and *where to watch* in each region,
  with a button that opens the streaming app.
- **Tracker:** follow shows, mark episodes watched, see what's airing; imports your TV Time history (GDPR export ZIP).
- **Roulette:** pick a mood and length and spin; it shows where to watch the result.
- **Friends:** add friends with a code and recommend titles to each other.

Streaming availability comes from TMDB / JustWatch and may not match every account or subscription.
Provider buttons open the installed streaming app; they can't jump straight to a specific title.

---

## For developers

Kotlin + Jetpack Compose, single-activity Android TV app (`com.cineglass.tv`, minSdk 26, targetSdk 35).

### Build
1. Copy `local.properties.example` to `local.properties` and fill in your own keys:
   `TMDB_API_KEY` (required), `OMDB_API_KEY` (optional ratings), `FIREBASE_*` (optional Friends),
   `UPDATE_REPO` (optional in-app updates).
2. Open in Android Studio, sync Gradle, run on an Android TV device or emulator.

`local.properties` and the `keystore/` folder are git-ignored. Never commit them.

### Friends backend
Friends uses Firebase Anonymous Auth + Cloud Firestore. Publish the rules in [`firestore.rules`](firestore.rules)
(Firebase console › Firestore › Rules) before letting other people use it. See [SOCIAL_SETUP.md](SOCIAL_SETUP.md).

### Releasing
See [RELEASING.md](RELEASING.md). In short: bump `versionCode`/`versionName`, run `gradlew assembleRelease`,
and publish a GitHub release tagged `vX.Y.Z` with the APK attached as **`CineSays.apk`**.

This product uses the TMDB API but is not endorsed or certified by TMDB.
