# Source card cleanup — 22 November 2026

Apply this update over the previously delivered Source Details update (b793406). Extract the update ZIP into your project root and replace the included files. Sync Android Studio, then run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Install the resulting APK.

## Changes

- Other Sources cards use neutral headings such as `1080p source` or `Media source`. The repeated provider heading is removed from the displayed description. Filename, language and other descriptive information remains available.
- Other Sources are ordered by file size, smallest first. Structured byte sizes take precedence; otherwise the provider's reported KB/MB/GB or KiB/MiB/GiB value is used. Decimal commas are accepted. Unknown/invalid sizes go last. Equal-size results keep their original order.
- Removed Real-Debrid actions and section from Watch Now, its Settings controls, and its playback dispatch/resolution path from StreamingViewModel. This build cannot initiate Real-Debrid playback through the app UI.
- Kept legacy registry fields and helper classes for compatibility with the existing approved-source configuration and tests. They no longer expose a Real-Debrid user flow. Existing saved credentials are not erased.
- Updated prior routing tests for the removed feature and added three tests for neutral headings and numerical size ordering.
- Existing direct playback, source details, UI styling, D-pad focus modifiers, progress and Trakker integration retained.

## Verification

Git whitespace checks passed. Update patch and ZIP integrity checked against b793406. Gradle tests/build were attempted but stopped before compilation while downloading Gradle 9.3.0 (`Network is unreachable`). Unit tests and on-device playback/focus checks still need to run in Android Studio. No newly built APK is included.
