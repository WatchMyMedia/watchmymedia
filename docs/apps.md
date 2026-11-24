# Apps, casting and downloads

The apps for phones, tablets and TVs, how they find and sign in to a server, casting to a TV,
and downloads for watching offline. The guide has the steps:
[watch on your TV, phone and other computers](https://watchmy.media/guide/watch-at-home) and
[download to watch offline](https://watchmy.media/guide/downloads).

## Getting the apps

- **iPhone and iPad** (iOS or iPadOS 18 or later) and **Apple TV** (tvOS 18 or later): the
  [App Store](https://apps.apple.com/app/watchmymedia/id6828975407). On the Apple TV, search
  the App Store for WatchMyMedia.
- **Android phones and tablets, Android TV and Google TV** (Android 8.0 or later):
  [Google Play](https://play.google.com/store/apps/details?id=media.watchmy.client).
- **Fire TV** and anything else without Play: the APK, below.
- **Any web browser**: the web app is part of the server. Open the server's address.

On an iPhone or iPad, the server must be reachable at an IP address or `.local` name over
http, or at an https address: iOS refuses plain http to other host names.

### Installing the APK on a TV

To sideload a build on a Google TV Streamer, a Fire TV or another Android TV instead of
installing from Play: each `v*` [release](https://github.com/WatchMyMedia/watchmymedia/releases)
has `WatchMyMedia-<version>.apk`, and CI runs that build the app keep a debug APK
(`android-debug-apk`). With `adb` (Android
SDK platform-tools, or `brew install android-platform-tools`):

1. On the TV, Settings → System → About, select "Android TV OS build" seven times, then
   turn on USB debugging under Settings → System → Developer options.
2. If Developer options lists Wireless debugging, turn it on, choose "Pair device with
   pairing code" and run `adb pair <tv-ip>:<pairing-port>` with the code, then
   `adb connect <tv-ip>:<port>` (the port on that screen). Otherwise
   `adb connect <tv-ip>:5555`. The TV asks once to allow the computer.
3. `adb install -r WatchMyMedia-<version>.apk` (`-r` updates an installed copy).

Without a computer at the TV, serve the APK on your network (`python3 -m http.server` in
its folder) and open `http://<computer-ip>:8000/WatchMyMedia-<version>.apk` in the
Downloader app from the Play Store, after allowing it under Settings → Apps → Security &
restrictions → Unknown sources. Fire TV works the same way (Developer options → ADB
debugging, then `adb connect <tv-ip>:5555`). The GitHub APK is signed with the upload key
and Play's copy with Google's app signing key, so Android will not update one with the
other: uninstall first when switching.

## Finding and signing in to a server

The native apps all use the same server:

- **Finding the server.** On the same network, apps list servers by name without an address.
  The server announces itself over mDNS (Bonjour). In Docker that needs `network_mode: host` on
  a Linux host (see `deploy/docker-compose.yml`); elsewhere people type the address once. Set
  the name under Settings → Server name. `network.discovery: false` turns it off.
- **Signing in a TV.** The TV shows a six-character code. Open `/link` in the web app (or scan
  the TV's QR code), check the device name and approve; the TV signs in as you. TVs and phones
  stay signed in for a year of disuse (`auth.device_session_ttl`).
- **Signed-in devices.** Settings lists every browser and app signed in to your account, with
  Sign out for each. Admins can see everyone's. Changing your password signs out everything
  except the browser you changed it from.
- **Several servers.** The Android, Apple and Samsung TV apps stay signed in to as many as 12
  servers, each with its own sign-in. Servers, from the account button (the last tab on Apple
  TV), switches between them without a password, and Add a server signs in to another. Search
  asks every server signed in, and a result from another server switches to it. The list stays
  on the device and the server has no part in it, so any server the apps work with can be one
  of them. A kept address is only where that server was last seen, so before an app sends the
  sign-in of a server that isn't the one in use, it asks the address which server it is
  (`GET /api/server`) and sends nothing unless the id is the one it remembers. Each app's
  README has the details: [Android](../android/README.md#several-servers),
  [Apple](../apple/README.md#several-servers), [Samsung TV](../web/tv/README.md).
- **One title across servers (Samsung TV).** With servers newer than 2.1.0 (API level 34), the
  Samsung TV app shows a title that several servers have once, matched by its TMDB id: in
  search, on Home (Continue watching, and the recently added, aired and released rows), and in
  Movies and TV Shows, which are filled from every server. A title only another server has
  opens in place, without switching servers. On a title's page the button after Play names the
  copy it will play and lists every server's copies, a server reached away from home last; the
  choice is remembered on the TV, Resume starts from wherever you got furthest, and what you
  watch is saved on the server it played from. Live TV, requests and Settings stay the current
  server's.

## Casting

Play on a TV and use the phone or browser as the remote:

- **Chromecast and Google TV** from Chrome or Edge on a computer or Android, and from the
  Android app. The TV runs our receiver (a web page Google's Cast devices load from
  `https://cast.watchmy.media`) and plays as the person who cast: it asks the server how to
  play with its own codecs, saves progress, skips intros and plays the next episode by itself,
  so the phone can lock or the tab close. Activity shows the TV as the device. A Google TV with
  the WatchMyMedia app installed plays in the app instead (direct play, surround passthrough).
- **AirPlay** from Safari on a Mac, iPhone or iPad: the player's AirPlay button sends the
  stream to an Apple TV or AirPlay TV. The iPhone and iPad app also supports AirPlay.

The sender hands the TV a cast token: it plays, reports progress and reads your playback
settings for 12 hours at a time, renewed while you stay signed in, and stops when the phone or
browser that cast signs out. Settings in `config.yaml` (`cast:`) or the environment:

| Setting | Env | Default | What |
|---|---|---|---|
| `cast.enabled` | `WATCHMYMEDIA_CAST_ENABLED` | `true` | Issue cast tokens and let receiver pages call the API |
| `cast.app_id` | `WATCHMYMEDIA_CAST_APP_ID` | `0A4D0B6A`, the WatchMyMedia receiver | The receiver browsers launch; empty hides the Cast button in browsers |
| `cast.url` | `WATCHMYMEDIA_CAST_URL` | none | An address TVs can reach, offered first: your reverse proxy's HTTPS domain |
| `cast.receiver_origins` | `WATCHMYMEDIA_CAST_RECEIVER_ORIGINS` | `https://cast.watchmy.media` | Where receiver pages come from (CORS) |

**Can the TV reach the server?** A Chromecast fetches the video itself, so it needs an address
it can reach. The server offers its LAN addresses (`http://192.168.x.x:27587`) and the address
you cast from, best first. A Chromecast is not on your tailnet, so a `ts.net` address never
works for it; behind a proxy, set `cast.url` to the HTTPS domain. Whether every Chromecast plays
from a plain `http://` LAN address is still being checked on real devices; if one will not,
`cast.url` with HTTPS is the fix. Browsers show the Cast button only on HTTPS pages or
`localhost` (Chrome's rule), so cast from the phone app or over Tailscale Serve or your domain.
The Casting card under Settings › General shows what a TV would be offered from where you are.

## Offline downloads

The Android app on phones and tablets and the iPhone and iPad app can download a movie or an
episode to watch with no connection, and its progress reaches the server when the device is
back online. A download belongs to the server it came from: removing that server from a device,
or signing out of it, deletes its downloads there.

**Who may.** Settings › Users › Who can download: no one, admins only (the default) or everyone
with an account. Turning it off for someone removes their downloads the next time their device
connects. Activity › Downloads lists every download, and Revoke removes one from its device the
same way; until then it still plays there.

**What is sent.** What the device would play at the quality picked (Original by default, or
1080p, 720p or 480p): the original file, fetched in pieces it can resume, when the device plays
it as it is; otherwise HLS from a transcode or remux the server makes as the device fetches
it. A transcoding download runs ffmpeg at low priority, at most
`transcode.max_download_sessions` at once (`WATCHMYMEDIA_TRANSCODE_MAX_DOWNLOAD_SESSIONS`,
default 1, within `max_sessions`), and gives its slot to anyone who starts watching; it carries
on when there is room. A download is not a play: it has no row in History, and watching it
offline changes only what is watched and where each person left off. The newest change wins by
when it happened, so a later play on the TV is not undone by an older one made offline.

**The limits.** This decides what the apps offer and what the download routes serve; it is not
copy protection. Anyone who can play a file can already save it, and a download on a rooted
phone can be copied off it. Downloads never expire: a withdrawn permission, a revoke or a
deleted account reaches a download only when its device next connects, and a device that never
connects again keeps playing what it holds. A file deleted or replaced on the server only marks
the downloads of it (*Removed from the server*, *A new version is available*). Each download
fetches with a token that opens that download alone, for six hours at a time, and stops working
when the device is signed out.
