# Direct-first source discovery (development branch)

Baseline: `release/cinesays-1.8.6`. All changes remain on `feature/direct-stream-discovery`.

## Configured behavior

- Source discovery runs when **Watch Now** opens for a movie, or when an episode is selected. Existing progressive source search is retained.
- `preferredSource = direct`: playable HTTP HLS/DASH/progressive sources appear before authorized torrent choices in the source router.
- Internet Archive direct MP4 remains the existing configured provider; no duplicate Archive integration was added.
- Progressive torrent playback continues to use the existing piece-aware Media3 DataSource, bounded reads and prioritized pieces; it **does not promise zero buffering**.
- `authorizedTorrentToHttpPlayback = true` checks an explicitly configured `AuthorizedTorrentHttp.Gateway` first when a torrent source is selected; without one, it falls back to local progressive torrent playback.
- The gateway has **no default implementation**. The preference enables the capability, not a paid service, token resolver or new catalog.
- Existing service links, UI pages, Trakker, watch history and signed release remain unchanged.

## What is deliberately not included

- No Movies.do or LoadShare private API scraping, session cookies, signed URL harvesting or attempts to bypass service restrictions.
- No Plex AVOD partner credentials or licensed catalog, because partner approval is required.
- No Real-Debrid re-enablement or paid provider.
- No new APK or release version.

## Next engineering tasks

1. Add a vetted, licensed direct streaming provider using the existing `StreamProvider` interface and declared host allowlists.
2. Configure `AuthorizedTorrentHttp.Gateway` only for a provider that permits API access and media playback; handle account authorization and expiring URLs outside the public code.
3. Test `./gradlew :app:testDebugUnitTest :app:assembleDebug` locally, followed by device tests for source ordering, focus, subtitles, seek, playback resume, and torrent fallback.

No Android build, unit tests, or device playback were executed by this GitHub-only change.

## Premiumize gateway adapter

`PremiumizeGateway.kt` implements the documented `POST /api/transfer/directdl`
using a magnet URI for an approved source, a caller-supplied Bearer credential,
and strict matching of the returned video filename. Ambiguous multi-video
results are rejected instead of playing the wrong episode. An approved HTTPS
link is returned for the existing Media3 player; the normal piece-aware torrent
engine remains the fallback for cache misses, API errors, unsupported file
formats, and absent credentials.

**Activation still required:** no Premiumize account is connected, no API key or
OAuth token has been provided, and `AuthorizedTorrentHttp.configure(...)` has
not been wired into account sign-in/settings. Integrate device-code OAuth or
secure user-specific credential storage before enabling for end users.
The gateway may incur account usage, and immediate playback is not guaranteed.
The app should not place a developer's single token in the public APK.

The current code is an adapter and not a tested live gateway connection.

## Free home gateway — TorrServer (implemented, not deployed)

The app now has a concrete `TorrServerGateway.kt` adapter and a URL field in
**Settings → Playback & Streaming**. This replaces the need for a paid gateway
when the viewer has a compatible, user-controlled TorrServer instance. The source
registry, source screens and local torrent playback are preserved.

### Windows host setup (example)

1. Download the **official** Windows 64-bit build from
   https://github.com/YouROK/TorrServer/releases .
2. Start TorrServer bound to the local interface only:

   ```powershell
   .\TorrServer-windows-amd64.exe --port 8090 --ip 127.0.0.1
   ```

   If the downloaded binary has another name, use that filename.
3. Install Tailscale on the host and on the Android TV/phone. Join both devices
   to the same tailnet. In Tailscale settings, enable MagicDNS and HTTPS certificates.
4. On the host, run:

   ```powershell
   tailscale serve --bg 8090
   tailscale serve status
   ```

   The output gives a private `https://<machine>.<tailnet>.ts.net` HTTPS hostname.
   **Use Serve, never Funnel**, and do not forward port 8090 on the router.
5. In CineSays Settings → Playback & Streaming, enter the HTTPS hostname,
   enable **Authorized torrent-to-HTTP**, and leave **Progressive torrent**
   enabled for fallback.
6. Select an authorized torrent through the existing Watch Now source list.
   CineSays first asks TorrServer to resolve the exact video file. If it is not
   ready or cannot be selected unambiguously, the existing local torrent
   engine remains available.

CineSays accepts only HTTPS `.ts.net` URLs for this adapter, and the host
must have a working, normally trusted TLS certificate. No passwords are placed
into the APK. Tailnet access rules restrict which enrolled devices can reach
the home gateway. Do not use this for an open/public TorrServer instance.

### Technical details

- Requests: `POST /torrents` (`action=add`, `action=get`),
  then `GET /play/{infohash}/{fileId}` through Media3.
- Uses the existing approved hash and source checks. Only matching filenames
  or a single video file can be selected; no guessing among multiple episodes.
- Remote torrent streaming still relies on active peers. It changes **where**
  torrent buffering happens, not whether buffering can happen.
- Current version uses an HTTPS tailnet service; HTTP-only LAN gateways are
  deliberately not accepted, avoiding disabling Android cleartext restrictions.
- A user must run the Windows/Linux/macOS server; adding this Kotlin adapter
  does not install TorrServer or Tailscale on their devices.
- No build, unit tests, emulator test, live server session, or APK release has
  been completed in this workspace.

### Test checklist

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Check: Android TV remote focus and settings field; correct Tailscale HTTPS URL;
Big Buck Bunny authorized test stream; correct selected file; server unavailable
fallback; seeking and range requests; subtitles; resume and Trakker; no
regressions to Internet Archive or paid-provider-independent direct playback.
