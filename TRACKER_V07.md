# CineSays v0.7 — Episode Tracker (source update)

This update is based on the previously delivered CineSays v0.6 project. It retains the existing application ID `com.cineglass.tv`, so it can update an existing installation while preserving its local application data.

## Added
- Tracker navigation entry, themed Calendar & Releases dashboard, seven-day calendar, timeline, follow-show list, episode detail and watched toggles.
- Local SQLite tracker database (`TrackerStore.kt`), with episode-level unique keys preventing duplicate imports.
- Android TV ZIP picker and a two-step inspect/confirm import (`TVTimeImporter.kt`).
- Supports either the original GDPR export `gdpr-data.zip` or `CineSays-TVTime-Import-Prepared.zip`.
- Only reads six tracking CSV names. It **does not read or upload** `access_token.csv`, user emails, device identifiers, IPs, sessions, or other GDPR personal files.
- Import preview counts series, unique watched episodes and movie records. Original dates are preserved as text.
- Optional exact-title TMDB matching (`My Shows` → `Auto-match...`); unmatched titles remain unmatched instead of silently assigning the wrong show.
- Once matched to TMDB, a show can load its season episodes and mark individual episodes watched.
- Timeline checks TMDB next and last episode release data for up to 30 followed and matched series; it is **not a complete all-show release-calendar feed**.

## Build and install
1. Back up your current `D:\Projects\CineSays` folder.
2. Unzip and open the included `CineSays` project with Android Studio. Keep your **existing** local.properties; do not overwrite it with the example file.
3. Gradle Sync, Build > Rebuild Project, then deploy to TV.
4. Open **Tracker**. Select **Import tracking from TVTime ZIP** and use the system document picker to select the GDPR ZIP or prepared ZIP.
5. Review the preview counts, confirm the import, open **My Shows**, then select **Auto-match imported shows to TMDB**.
6. For uncertain titles, auto-matching will refuse to guess. Select a series and use **Match with TMDB** to retry exact matching. Manual result selection UI remains future work.
7. Select a successfully matched show, choose a season, and mark episodes watched or unwatched.

## Explicit limitations
- Code has not been compiled against the Android SDK or tested on a physical TV in this environment.
- TV Time has no officially integrated account-sync API in this implementation; importing its GDPR ZIP is one-way and local.
- The exact visual mockup remains a design goal; this is a native adaptive Compose implementation of its major layout sections, not pixel-identical artwork.
- The **Watch Streak** shows a placeholder until the raw TV Time timestamps can be normalized reliably; no invented streak values.
- **Reminders** saves a setting but does not schedule Android system notifications yet.
- Calendar dates come from TMDB and may not include every episode or local airing time. TMDB identifiers are separate from TV Time identifiers.
- Tracker history is local to this Android TV installation; Firebase cross-device syncing of imported history is not yet implemented.
- The importer works when the TV has a system document picker. On TVs without a picker, transfer the ZIP to the device or use an alternate document-provider app.
- For privacy, the example GDPR ZIP and your processed watch-history files are **not bundled** into this app source archive.
