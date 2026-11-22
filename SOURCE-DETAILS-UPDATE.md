# Source details and playback update — 22 November 2026

Based on the Torrentio integration delivered previously (3295df4). Changes isolated on branch cinesays-source-details.

## Install

Extract CineSays-Source-Details-Update.zip into your existing updated CineSays project root, replacing the included files. Keep your local.properties and signing setup. Sync Android Studio and run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Install/run the newly built APK. Open Watch Now and choose a source under **Other Sources**. Restarting the app clears older in-memory source responses; Search again also refreshes discovery.

## Complete changelog

- HybridPlayer.kt: rename Play via torrent to Other Sources. Replace its short source buttons with the existing styled, focusable SourceCard. Show a wrapping detail summary, multiline provider description, and filename where provided. Preserve existing HTTP and Real-Debrid cards, player controls and focus modifiers.
- SourceDetails.kt: new display-only formatter for reported quality, file size, container, codec, audio, HDR and language hints. Structured byte size takes precedence. Explicit language names and unambiguous language flag conventions are displayed. Missing fields say Not reported. Filename/description hints are not treated as verified media properties. Original text remains visible so provider/seed information and unrecognized languages are not discarded.
- StremioProtocol.kt: retain up to 2,000 characters of torrent description instead of 200; keep the heading short and put details in the card body. Existing HTTP-only providers retain their previous limit.
- StreamSources.kt: reuse provider objects so the existing 24-hour manifest cache actually survives between title searches. Retain only enabled provider configurations. Make HTTP calls asynchronous and cancellable so switching away or timing out cancels the outstanding call. Keep per-provider timeouts and the response size cap. Limit the result cache to 64 titles. Search again invalidates result/provider caches and forces a registry fetch.
- Addons.kt: reuse the loaded registry for 60 seconds rather than fetching GitHub on each uncached title search. Explicit retry bypasses this interval. Failed refreshes preserve the existing registry.
- TorrentPlayback.kt: prioritize a bounded four-piece read-ahead window when waiting for data. The window ends at the selected video boundary; deadlines are cleared after the wait, including failure, and are rebuilt at a new seek position. Existing storage, file validation, cleanup and metadata timeouts are unchanged.
- SourceDetailsTest.kt: five regression tests for metadata presentation, long descriptions, unknown fields, structured size/filename and input limits.
- CHANGELOG.md and this document: installation, scope and verification.

## Verification and limits

Git whitespace review passed with existing CRLF endings recognized. Patch application and ZIP integrity were checked against the previously delivered integration. The build/test command was attempted, but Gradle 9.3.0 cannot download in this environment (Network is unreachable), so no compilation or JUnit execution is claimed. No new APK is included.

The performance changes remove repeated requests and add bounded read-ahead; latency improvement has not been measured on a device. Peer count, connection speed and file availability still control torrent startup and buffering. Discovery still waits for the parallel providers to finish or time out. Language/codec information depends on the provider description and is labelled as source-reported, not verified audio-track metadata. Actual tracks remain available through the existing player's audio selector.

Before release, test source scrolling/focus on TV, a movie with multiple source variants, cancellation while searching, Search again, seek/resume, and existing direct HTTP and Real-Debrid playback.
