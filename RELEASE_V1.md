# CineSays 1.0 — source release candidate

Built on CineSays v0.7.1. Package `com.cineglass.tv` is unchanged, so existing app data can be preserved by installing an update signed with the same certificate.

## Implemented changes
- Tracker UI styled after the approved Calendar & Releases reference: dark navy glass cards, blue focus/selection, release calendar week controls, release timeline, today/this-week, missed episodes and progress highlights.
- TMDB series search under Tracker → My Shows, follow from results, and direct **Add to Tracker** from any television-series details page.
- TV Time original and prepared ZIP import flows inherited from v0.7.1. Import is local; account exports are not bundled.
- Individual episode watched/unwatched toggle; mark loaded season watched; follow/unfollow; manual match choice for imported series; automatic exact-title TMDB matching.
- Actual watch streak calculated from consecutive calendar days present in saved watch timestamps. Records without parseable ISO dates are excluded from this metric.
- Release retrieval enlarged from just latest/next to known episodes of nearby seasons for matched followed shows, within approximately -30/+35 days. TMDB schedule metadata is incomplete for some productions; the app must not invent missing episode times.
- Best-effort release notifications: Android WorkManager periodic network checks every 12 hours. On Android 13+, notification permission is requested. Notifications require app installation, a functioning network and background-work permission; they are not exact-airtime alarms.
- Back from a Tracker episode guide returns to Tracker. Back from a Tracker subtab returns to its Calendar; Back from Tracker returns to Home. At Home, twice pressing Back within 2.2 seconds presents Exit/Cancel.
- D-pad focus outline applied to Tracker buttons, My Shows cards, episode tiles, and calendar days; automatic Home focus on Back navigation.

## Build and migration
1. Keep existing `local.properties` (TMDB, OMDb, Firebase configuration) out of version control; copy it into the new project root.
2. Sync Gradle and run `./gradlew assembleDebug` on a machine with Android SDK 35 and Java 17 (Windows: `gradlew.bat assembleDebug` if Gradle wrapper is present; otherwise use Android Studio's Gradle tasks).
3. Install using `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
4. Do **not** clear app storage: SQLite tracking data and anonymous Firebase identity are local to the current installation.
5. Test remote Up/Down/Left/Right/Select, Back, and the two-press Exit dialog on a real TV.

## Important release gates (NOT yet verified)
- This environment does not have Android SDK/Gradle/TV access. **The project has NOT been compiled or tested on a device here.** Treat this as a 1.0 candidate, not a certified production build.
- Pixel-for-pixel match to the mockup cannot be asserted without a real-device screenshot comparison, particularly on smaller 720p screens and TVs with overscan.
- Production deployment needs end-to-end tests for Firestore rules, friends/recommendations, backups, authentication persistence, and title-specific provider deep links. Anonymous Firebase identity can be lost after data clearing/reinstall.
- TMDB episode schedules are not guaranteed complete, and WorkManager cannot guarantee exact notification delivery time.
- Firestore-synced tracking across devices and full TV Time proprietary behavior are not implemented. All Tracker data is presently local.
- A production build must be signed with a persistent release signing key, and must pass functional tests, privacy review, and license/API compliance checks.

## Sanity checks
- Import original TV Time GDPR ZIP; verify duplicate import leaves counts unchanged and watched dates survive app restart.
- Search and follow a show from My Shows; open same show through Search → Details and check "In Tracker".
- Select season; toggle episode watched and unwatched; mark season watched; test Back from selected show to Tracker.
- Select calendar week and date, verify release items reflect that date; test empty release state when none known.
- Enable/disable notifications; verify permission request on Android 13+ and inspect Worker status if not receiving alerts.
- Test two TV installations independently with same Firebase project while keeping VPN/Firestore connectivity verified.
