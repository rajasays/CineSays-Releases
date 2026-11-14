# Publishing CineSays updates on GitHub

Installed copies of CineSays check `https://api.github.com/repos/rajasays/CineSays-Releases/releases/latest`
(set by `UPDATE_REPO` in `local.properties`). If the release tag is newer than the installed version,
**Settings › App updates** downloads the attached `.apk`, checks its SHA-256 against GitHub's digest,
and opens Android's installer.

## Every release
1. In `app/build.gradle.kts` raise `versionCode` (e.g. 12 → 13) and `versionName` (e.g. `1.2.0` → `1.3.0`).
2. Add the changes to the top of `CHANGELOG.md`.
3. Build the signed release:
   ```
   gradlew.bat assembleRelease
   ```
   Output: `app/build/outputs/apk/release/app-release.apk`
4. Rename it to **`CineSays.apk`**. The README's Downloader link
   (`/releases/latest/download/CineSays.apk`) depends on this exact name.
5. GitHub › **Releases › Draft a new release**:
   - Tag: `v1.3.0`, matching `versionName`.
   - Title: `CineSays 1.3.0`.
   - Notes: that version's CHANGELOG section. The app shows up to 600 characters of it.
   - Attach `CineSays.apk`, then **Publish release**. Drafts and pre-releases are not offered as updates.
6. Commit and push the version bump.

## Signing key (critical)
Release builds are signed with `keystore/cinesays-release.jks`. Its passwords are in `local.properties`
(`RELEASE_*`). Neither is in git.

- **Back up both files together** (USB drive, password manager or cloud vault). If the key is lost,
  no installed copy can ever be updated again; everyone would have to uninstall and lose their data.
- Never publish the keystore or its passwords.
- An APK signed with a different key (for example a debug build from Android Studio's Run button) can't
  update a release install. Android refuses and the user would have to uninstall first.
