# CineSays free torrent playback phone test

- Added an explicit **Play via torrent** action in **Watch Now** for the approved Big Buck Bunny 720p test hash and filename. Regular URLs and Real-Debrid still use their original actions.
- Added FrostWire jlibtorrent and Android native binaries. The existing Media3 player reads verified pieces from a temporary file while libtorrent downloads sequentially. Seeking into a range that has not yet arrived may wait or time out. Source preparation and media reads have bounded waits.
- Playback uses the existing resume and progress machinery. Continue Watching opens the source picker again so a new torrent session can be started. Closing the player or dismissing the resume prompt stops the session and schedules cache cleanup.
- The registry still needs the Big Buck Bunny manifest, stream JSON and allowlist entry documented previously. Enable **Big Buck Bunny test** under Settings › Add-ons, then select **Big Buck Bunny 720p** under **Play via torrent**. BitTorrent requires peers and can expose the device's public IP address to them. The Big Buck Bunny film is by Blender Foundation and licensed CC BY 3.0.
- Rebuild the debug APK after applying this update. A JDK, Android SDK and access to the FrostWire Maven repository are required. Test starting playback, seeking within and beyond the buffer, backing out, and resuming on the phone.
- This workspace could not download Gradle 9.3.0. Compilation, unit test execution, native loading and live playback remain unverified.
