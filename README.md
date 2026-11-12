# WatchMyMedia

Self-hosted media server: point it at a Movies folder and a TV folder, it builds
libraries with metadata and streams to a web client and to the TV and phone apps.

## Layout

- `server/` — Go server (chi + huma API, SQLite via sqlc/goose, ffmpeg orchestration, embedded web UI)
- `web/` — React + Vite web client, API types generated from the server's OpenAPI
- `deploy/` — Dockerfile, compose example, config example
- `Makefile` — `make help` lists every target

## Prerequisites

Go 1.27+, Node 24 + pnpm 9, ffmpeg/ffprobe, Docker. Dev tools: `brew install go sqlc golangci-lint air`.

## Develop

```bash
cp deploy/config.example.yaml data/config.yaml   # set your library paths and TMDB API key
make dev        # Go server on :27587 with hot reload
make web-dev    # Vite on :5173, proxies /api to the Go server
```

API docs are served at <http://localhost:27587/api/docs>, the spec at `/api/openapi.json`,
and live scan events at `/api/events` (SSE). Libraries are scanned at startup, when files change
on disk (just the files that changed), every 15 minutes for anything the watcher missed (Settings ›
Libraries), and on demand from the UI.

Metadata and artwork come from [TMDB](https://www.themoviedb.org/). Create a free account,
request an API key under Settings → API, then add it in WatchMyMedia’s Settings › General.
`WATCHMYMEDIA_TMDB_API_KEY` and `tmdb.api_key` in config.yaml also work; a key saved
in Settings wins over both. Without a key the server still scans and lists files
but cannot match them. Setup and Home explain how to add the key later.

Subtitles come from the file itself (embedded text and image tracks, and `.srt`/`.ass`/`.vtt`
files named after the video) and from [OpenSubtitles.com](https://www.opensubtitles.com),
listed first. Players search in the background once a video is playing and download the
result someone picks; downloads are kept in `<data>/subtitles-downloaded` and backed up.
Release builds carry WatchMyMedia's OpenSubtitles API key (the `OPENSUBTITLES_API_KEY`
repository secret, passed to `make` and the Docker build). Sign in with an OpenSubtitles
account under Settings › Audio & subtitles for 20 downloads a day (1000 on VIP); without one
the server gets 5. Development builds have no key unless `WATCHMYMEDIA_OPENSUBTITLES_API_KEY`
is set. In the browser and the apps, Settings › Audio & subtitles keeps each person’s audio
languages and subtitle languages in order, and how subtitles start: Off, Forced only, When the audio isn’t
in my languages, or On. A video starts on the best audio track in the first audio language it
has (never a commentary or audio description), otherwise on the file’s default; someone who
adds audio languages without having chosen how subtitles start gets subtitles for audio they
don’t follow. Automatic download is off until that person enables it with On or When the
audio isn’t in my languages, and a subtitle language; it looks for an exact-file match after
video starts. A player choice, including Off, always wins. Without online subtitles or allowance, video continues
and local tracks stay available. The browser’s shared provider account and download
permissions are in the admin-only Server subtitle service section of that page.

After changing SQL in `server/internal/db/queries` or any API types: `make gen`. That also
rewrites `api/openapi.json` and `api/openapi-3.0.json`, which the TV and phone apps generate
their clients from; CI fails if they are stale.

`make fixture` renders a small library of synthetic films and episodes into `data/fixture`
(about a minute and a half of ffmpeg), and `make fixture-serve` runs the server on it at :27588 with
test accounts listed in `deploy/fixture/fixture.sh`. Use it for app simulators, emulators and
demos instead of real media.

`make streaming-check` creates its own temporary fictional file/Live TV server
on an unused loopback port, characterizes simultaneous viewers and changing
bandwidth, and writes `build/streaming/report.json`. It cleans up its server and
media afterwards. [The harness guide](deploy/fixture/streaming.md) explains
individual scenarios, the simulated player buffer and the measurements this
baseline can and cannot establish.

Adaptive HLS for web and Cast is experimental and disabled by default. On a
qualified test server, `transcode.adaptive_hls: true` (or
`WATCHMYMEDIA_TRANSCODE_ADAPTIVE_HLS=true`) lets Automatic request an admitted
software H.264/SDR family; Live TV also needs shared sources enabled. Unsupported
media and failed admission use the normal playback path. Fixed quality choices
keep their current behavior. The receiver requires server API level 27; its
production deployment and broader hardware qualification are separate rollout
gates.

Admins can import watched status from an owned Plex server in **Settings › Plex**.
Sign in on Plex’s own page, choose the server address, then choose which people
and movie/TV libraries to link. Managed Home profiles without a PIN are included;
unsupported profiles can be left out while the others continue. Choose **Check
what will change**, then **Import once** or **Import and keep up to date** (every
15 minutes). Connecting alone imports nothing. Current WatchMyMedia progress,
rewatches and explicit unwatched choices always stay; nothing is written to Plex.

Saved import results explain what was applied, kept or skipped. Pause, cancellation
and retry keep completed changes; **Undo imported watch marks** removes only marks
that still belong to that import and leaves later local choices alone. Earlier
viewings from Plex have their own page under Activity and remain separate from
WatchMyMedia playback totals. History follows each person’s recording setting and
the server’s retention limit; clearing or deleting it prevents the old events
returning. Missing Plex history never invents plays or durations. PIN-protected
profiles, arbitrary episode ordering and complete lifetime history are not promised.
[The fixture guide](server/cmd/plexsyncfixture/README.md) is for isolated development
checks. Backups exclude Plex grants, so a restored server must reconnect.

## Install

**Docker (recommended on Linux/NAS).** Copy `deploy/.env.example` to `deploy/.env` and
set your library folders and `PUID`/`PGID` there, then `make docker-up` (or use the
published `ghcr.io/taimoorq/watchmymedia` image). Git ignores `deploy/.env`, so pulling
updates never conflicts with your settings. The container reads `/movies` and `/tv`
(on its first start each becomes a library if it holds anything; after that, libraries are
added and removed in setup and Settings › Libraries, and the folders aren't added again),
keeps its database, artwork and backups in `/config`, and writes HLS segments to
`/transcode`. Files it writes get the permissions `UMASK` allows (default `022`).

The image's ffmpeg is [jellyfin-ffmpeg](https://github.com/jellyfin/jellyfin-ffmpeg), the
same build the Mac and Windows releases ship, with the Intel, AMD and Vulkan drivers inside
it. For hardware transcoding on Linux, pass the GPU to the container: an Intel or AMD GPU is
`devices: ["/dev/dri:/dev/dri"]` (the server runs as `PUID` and is given the group that owns
the device, whatever the host calls it); an NVIDIA GPU needs the
[NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html),
`runtime: nvidia` and `NVIDIA_VISIBLE_DEVICES=all` (both are commented in the compose file).
Settings › Transcoding shows which encoder was found; `docker exec watchmymedia vainfo`
lists what the VA-API driver can do. Docker Desktop on macOS cannot reach the GPU: use the
Mac app there.

**Unraid.** The template in [deploy/unraid](deploy/unraid/README.md) runs the same image with
Host networking, read-only Movies and TV shares, appdata on your cache pool, an optional
`/dev/dri` device and the NVIDIA variables; the guide is at
[watchmy.media/guide/unraid](https://watchmy.media/guide/unraid). Until the app is in Community
Applications, copy the template to `/boot/config/plugins/dockerMan/templates-user/` and pick it
in Docker › Add Container; Settings › General then says to update from Unraid's Docker page.

Both `MOVIES_DIR` and `TV_DIR` must point to existing folders. If you have only
movies, point `TV_DIR` at an existing empty folder (or the reverse for TV only);
an empty folder creates no library. Changing `PUID`/`PGID` also updates ownership
of existing `/config` and `/transcode` contents on the next start.

**Native binary (recommended on macOS).** Download `watchmymedia-<version>-<os>-<arch>` from
a release (or `make release`), unpack it, and run
`WATCHMYMEDIA_DATA_DIR=~/watchmymedia ./watchmymedia`. The macOS tarballs and Windows x64 ZIP include
FFmpeg and FFprobe ([jellyfin-ffmpeg](https://github.com/jellyfin/jellyfin-ffmpeg),
GPL; see `FFMPEG-LICENSE.md` inside), which the server finds next to its own binary.
The Mac build includes VideoToolbox, libass and zimg. Linux binaries need FFmpeg on
`PATH`, or `ffmpeg.ffmpeg` / `WATCHMYMEDIA_FFMPEG_PATH` pointing at one.

**macOS app.** Download the DMG from <https://watchmy.media/download> and drag the app to
Applications. It is a menu bar item that runs the server from inside the app bundle with
the bundled ffmpeg, keeps its data in `~/Library/Application Support/WatchMyMedia`, logs
to `~/Library/Logs/WatchMyMedia`, keeps the Mac awake while something is streaming, can
start at login, and stops the server when it quits. First launch opens setup once;
**Finish setup…** stays in the menu until a library or tuner is saved. If another
app uses the server’s port, choose **Use another port…** to pick and remember a
free port, or close the other app and choose **Restart Server**. Menu links always
open this app’s own server; browser links stay disabled while another app holds
the port. Releases built without a Developer ID
certificate are signed ad hoc, so macOS blocks the first launch: open System Settings →
Privacy & Security, find the message about WatchMyMedia near the bottom and click Open
Anyway (on macOS 14, right-clicking the app and choosing Open is enough). Developers: `make macos-app` builds the app for this Mac from the Swift
package in `macos/`; `make macos-dmg` builds the universal DMG the release job ships.
`swift test --disable-keychain --package-path macos` checks the startup supervisor
with disposable data; it does not launch the installed app or open a browser.

**Windows desktop app (development).** The tray app and its bundled server build with
`make windows-app VERSION=development` into `build/windows-app/`. Copy the folder to a
PC running Windows 11 25H2 or later on native x64 and open `WatchMyMedia.exe`; setup opens in your browser. Data stays in
`%LOCALAPPDATA%\WatchMyMedia`. The tray offers status, logs, restart, Start at sign-in
and Quit. The development NSIS installer builds with `make windows-installer`. Releases
sign the installer, but Windows downloads and automatic updates are not published yet;
[windows/README.md](windows/README.md) covers building and testing it.

### Cutting a release

The server and every app take their version from one git tag, `vMAJOR.MINOR.PATCH`, with
`-alpha.N`, `-beta.N` or `-rc.N` for a pre-release.
Add the release's section to [CHANGELOG.md](CHANGELOG.md) on `main`, then run
`make release-tag VERSION=v1.2.3`. It tags `origin/main`, not this checkout, once it has
checked that the version is valid and newer than every tag so far and that the changelog
has its notes, and it asks before pushing. Only repository admins can push a `v*` tag.
The tag starts `.github/workflows/release.yml`, which creates the
GitHub release with those notes (as a pre-release for a suffixed tag); each job then adds
its files: the server archives, the DMG, the APK and the Windows installer. It also pushes
the Docker image and uploads the iPhone app to TestFlight. The Mac app's build number,
which Sparkle compares, is the number the iPhone and
Android builds use (`apple/scripts/version.sh`: 1.2.3 is 10200399).

The workflow runs on GitHub's runners and restores nothing from a cache. Its signing keys
and API keys are secrets of the `release` environment (`windows-signing` for the Windows
job), which admit only `v*` tags; a build without them still runs, unsigned, and says so.

A GitHub release is not yet what people are offered. The website, the servers' Check for
updates and the Mac and Windows apps' updaters read <https://downloads.watchmy.media>,
which the maintainers publish to from the release's files once it is built.

### Releasing the Mac app

The `macos` job in `.github/workflows/release.yml` builds a universal DMG on every `v*`
tag. It signs, notarises and staples the app and the DMG when these `release` environment
secrets exist, and otherwise uploads an ad-hoc-signed DMG:

- `MACOS_CERTIFICATE_P12` and `MACOS_CERTIFICATE_PASSWORD`: a **Developer ID Application**
  certificate (developer.apple.com → Certificates; not Apple Distribution, which is for
  the App Store) exported from Keychain Access as a `.p12`, then `base64 -i cert.p12`.
- `NOTARY_KEY_ID`, `NOTARY_ISSUER_ID`, `NOTARY_KEY_P8`: an App Store Connect API key
  (Users and Access → Integrations → Team Keys, Developer role); the `.p8` file as
  base64. `xcrun notarytool` uses it; no Apple ID password is involved.
- The repository variables `SPARKLE_PUBLIC_KEY` and `SPARKLE_FEED_URL`
  (`https://downloads.watchmy.media/mac/appcast.xml`): the public half of the key from
  Sparkle's `generate_keys`, and where the app looks for updates. With them the app checks
  for updates itself; without them it has no update item. The private half signs the feed
  and is not kept in this repository.

Locally, `make macos-dmg` does the same: it signs with the first Developer ID in your
keychain (or `CODESIGN_IDENTITY`), and notarises when `NOTARY_KEY_ID`, `NOTARY_ISSUER_ID`
and `NOTARY_KEY_PATH` are set, or `NOTARY_KEYCHAIN_PROFILE` names a profile saved with
`xcrun notarytool store-credentials`.

## TV and phone apps

The native apps (Android below; the iPhone app below that) use the same server:

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

## Android app

One APK for phones, tablets and Android TV / Google TV, in `android/` (modules and
conventions in [android/README.md](android/README.md)). It needs a JDK 17 or newer
(Android Studio's works) and the Android SDK:

```bash
cd android
./gradlew assembleDebug                                   # app/build/outputs/apk/debug/app-debug.apk
./gradlew bundleRelease assembleRelease -PappVersion=v1.2.3  # what a release builds
```

Releases take the version from the tag: `versionName` is the tag without the `v`, and
`versionCode` is major × 10⁷ + minor × 10⁵ + patch × 100 + 99, so every later tag gets a
larger code. Pre-releases sort below their release: `-alpha.N`, `-beta.N` and `-rc.N` end
in N, 30 + N and 60 + N. Other tag shapes fail the build.

**Installing on a Google TV Streamer.** The app is on
[Google Play](https://play.google.com/store/apps/details?id=media.watchmy.client) for
phones, tablets, Google TV and Android TV. To sideload a build instead: each `v*` release
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
restrictions → Unknown sources. The TV cannot fetch the APK from the release page itself,
because the repository is private. Fire TV works the same way (Developer options → ADB
debugging, then `adb connect <tv-ip>:5555`). The GitHub APK is signed with the upload key
and Play's copy with Google's app signing key, so Android will not update one with the
other: uninstall first when switching.

### Releasing the Android app

The `android` job in `.github/workflows/release.yml` builds the bundle and the APK on every
`v*` tag, attaches the APK to the GitHub release and keeps the bundle as the run's
`android-bundle` artifact. Without the signing secrets both are unsigned (the job summary
says so); with them they are signed, and with `PLAY_SERVICE_ACCOUNT_JSON` as well the bundle
goes to Play's internal testing track. Secrets of the `release` environment:

- `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
  `ANDROID_KEY_PASSWORD`: the upload key. Play App Signing keeps the key that signs what
  Play delivers, so this one only proves uploads are yours, and Play Console can reset it if
  it is lost. Make it once, keep the file and password in a password manager, never commit
  it:

  ```bash
  keytool -genkeypair -keystore watchmymedia-upload.jks -storetype PKCS12 \
    -alias upload -keyalg RSA -keysize 4096 -validity 10000
  base64 -i watchmymedia-upload.jks | gh secret set --env release ANDROID_KEYSTORE_BASE64
  gh secret set --env release ANDROID_KEYSTORE_PASSWORD   # prompts
  gh secret set --env release ANDROID_KEY_ALIAS --body upload
  ```

  `ANDROID_KEY_PASSWORD` can stay unset: a PKCS12 keystore uses the store password for the
  key. Locally, the same variables with `ANDROID_KEYSTORE_FILE` (the `.jks` path) sign
  `./gradlew bundleRelease`. The upload key made for 0.1.0 lives in `android/secrets/`
  (gitignored): `watchmymedia-upload.jks` and `upload.env`, which holds its password and
  alias. A signed build from it:

  ```bash
  cd android
  set -a; . secrets/upload.env; set +a
  ANDROID_KEYSTORE_FILE="$PWD/secrets/watchmymedia-upload.jks" ./gradlew bundleRelease assembleRelease -PappVersion=v0.1.0
  ```

  Release builds are minified by R8 (shrunk, optimized and obfuscated), with the app's keep
  rules in `android/app/proguard-rules.pro`. The bundle carries the R8 mapping file, and Play
  uses it to deobfuscate crash reports, so nothing else needs uploading.
- `PLAY_SERVICE_ACCOUNT_JSON`: in Google Cloud, enable the Google Play Android Developer
  API in a project, create a service account there and download a JSON key for it
  (`gh secret set --env release PLAY_SERVICE_ACCOUNT_JSON < key.json`). In Play Console → Users and
  permissions, invite the service account's email and give it "Release to testing tracks"
  for WatchMyMedia.

Play's API cannot create an app or make its first upload: create the app in Play Console
(Play App Signing is on by default) and upload the first bundle by hand from the
`android-bundle` artifact. Until Play has published the app, it only accepts draft
releases from the API; set the repository variable `PLAY_RELEASE_STATUS` to `draft` until
then and roll each release out in Play Console.

## iPhone app

The app is on the [App Store](https://apps.apple.com/app/watchmymedia/id6828975407)
for iPhone and iPad with iOS or iPadOS 18 or later; the Apple TV app is not out yet.
The iPhone and iPad app is in `apple/` (layout and conventions in
[apple/README.md](apple/README.md)). It
needs Xcode with an iOS simulator runtime and XcodeGen 2.45.4 or newer (`brew
install xcodegen`):

```bash
make apple-test       # the shared Swift package's unit tests, on this Mac
make apple-build      # generate the Xcode project and build for the simulator
make apple-test-sim   # build and run every test on a simulator
```

`make apple-gen` writes `apple/WatchMyMedia.xcodeproj` for opening in Xcode.
The launch argument `-fakes` runs it on a made-up library without a server;
against a real one, `make fixture-serve` and type `localhost:27588` in the
simulator's server picker. On a phone, the server must be reachable at an IP
address or `.local` name over http, or at an https address: iOS refuses plain
http to other host names.

### Releasing the iPhone app

The `ios` job in `.github/workflows/release.yml` archives the app on every `v*`
tag and uploads it to TestFlight. `apple/scripts/version.sh` turns the tag into
the version, and the build number follows the Android version code, so
pre-releases sort before their release (`v1.2.3-rc.1` is build 10200361, `v1.2.3`
is build 10200399). The App Store's first release was 1.0, and App Store Connect
takes only a higher version than the last approved one, so
`CFBundleShortVersionString` stays on 1.x: `v0.5.2` was 1.5.2, `v1.0.0` was
1.6.0, and since 1.1 and 1.2 shipped no App Store build, from `v1.3.0` the minor
version is four ahead of the tag's (`v1.3.0` is 1.7.0, `v1.4.0` is 1.8.0) on
iPhone, iPad and Apple TV, while the server, the Mac app and Android say
the tag's own version.

Once, by hand, in the Eldwick Software account (team `6R8GZD78G3`):

1. Register the bundle ID `media.watchmy.client` (Certificates, Identifiers &
   Profiles), with no extra capabilities, and create the app in App Store
   Connect with it. The Apple TV app will join the same record (universal
   purchase).
2. Make an App Store Connect API key (Users and Access → Integrations → Team
   Keys) whose role may manage certificates and profiles (Admin, or App Manager
   with access to Certificates, Identifiers & Profiles), so `xcodebuild
   -allowProvisioningUpdates` can sign in the cloud. Set the `release` environment's secrets
   `APP_STORE_CONNECT_KEY_ID`, `APP_STORE_CONNECT_ISSUER_ID` and
   `APP_STORE_CONNECT_KEY_P8` (the `.p8` file as base64). The notarisation key
   from the Mac release works if its role allows this.

Without the secrets the job builds the app for the simulator and warns. Locally,
`make apple-upload VERSION=v1.2.3` does the same with the Xcode accounts signed in
on this Mac, or with `ASC_KEY_ID`, `ASC_ISSUER_ID` and `ASC_KEY_PATH` set. To archive
in Xcode instead, run `make apple-gen VERSION=v1.2.3` first: otherwise the archive is
the project's placeholder version, 1.0 (1). The Apple TV app uploads with
`make apple-tv-upload VERSION=v1.2.3`: it is archived unsigned and signed for the
App Store at export, since Xcode's own Archive needs an Apple TV registered to the
team.

## Remote access with Tailscale

The safest way to watch away from home is to keep the server private and reach it over
[Tailscale](https://tailscale.com). Only devices signed in to your tailnet can connect,
nothing is opened on your router, and video flows directly between your devices at full
speed. Tailscale runs on macOS, Windows, Linux, iOS, Android, Apple TV, Android TV and
Fire TV.

1. Install Tailscale on the server machine and on each device you watch from, signed in
   to the same account. In the admin console, turn on MagicDNS and HTTPS certificates
   (DNS page).
2. Put the server behind Tailscale Serve, which adds HTTPS with a real certificate:

   ```bash
   tailscale serve --bg 27587
   ```

   It prints the address, for example `https://mac-mini.your-tailnet.ts.net`. On macOS,
   turn on the command-line tool in the Tailscale app's settings first.
3. In `deploy/.env`, make the server reachable only through Serve and mark the session
   cookie HTTPS-only, then recreate the container:

   ```bash
   WATCHMYMEDIA_BIND=127.0.0.1
   WATCHMYMEDIA_AUTH_SECURE_COOKIES=true
   ```

   ```bash
   docker compose -f deploy/docker-compose.yml up -d
   ```

   From now on, sign in at the `ts.net` address on every device, including at home.
   With secure cookies on, a plain `http://` address no longer keeps you signed in.
   For the native binary, set the same `WATCHMYMEDIA_AUTH_SECURE_COOKIES=true` and
   `WATCHMYMEDIA_HOST=127.0.0.1` in its environment, and also
   `WATCHMYMEDIA_TRUSTED_PROXIES=127.0.0.1,::1`. Serve then connects from this machine
   and passes on each device's tailnet address; without that line the server cannot
   tell who Serve forwards for, so it treats them as internet clients and refuses admin
   settings.
4. To share with family, invite them to your tailnet or share just this machine from
   the admin console. They still need their own WatchMyMedia account.

`tailscale serve status` shows what is being served, and `tailscale serve reset` stops it.

**Funnel.** Tailscale Funnel would put the server on the open internet, but it relays
all traffic through Tailscale's servers with bandwidth limits you cannot change, which
suits video poorly. For public access, use a reverse proxy instead (next section).

## Public access with a reverse proxy

For viewers or devices that cannot run Tailscale (Roku, smart TVs, casting, friends),
put Caddy in front of the server with a domain name that follows your home IP. Caddy
gets the HTTPS certificate, and the server sees each client's real address, so admin
settings stay limited to your home network and tailnet.

**Before you start.** Check that your connection has a real public IP: the WAN address
on your router's status page must match what <https://ifconfig.me> shows. If they
differ, your ISP uses carrier-grade NAT and port forwarding cannot work; use Tailscale
or a tunnel through a small cloud server instead.

1. **Point a name at your home.** Pick a name such as `home.watchmy.media`. The `ddns`
   service updates its DNS record whenever your home IP changes. It supports
   Cloudflare, Porkbun, Namecheap, DuckDNS, Route 53 and many more; see the
   [ddns-updater docs](https://github.com/qdm12/ddns-updater#configuration) for each
   provider's fields. Create its config, readable only by the updater:

   ```bash
   mkdir -p data/ddns
   cp deploy/ddns-config.example.json data/ddns/config.json
   sudo chown -R 1000 data/ddns && sudo chmod 700 data/ddns && sudo chmod 400 data/ddns/config.json
   ```

   Edit `data/ddns/config.json` for your provider first. Use an API token that can
   edit only this zone's DNS.
2. **Forward ports 80 and 443** on your router to this machine, and nothing else. Port
   80 is only for certificate checks and the redirect to HTTPS. Turn off UPnP on the
   router so nothing opens ports by itself.
3. **Set the domain** in `deploy/.env`, and keep the server's own port off the network
   so every client comes through Caddy:

   ```bash
   WATCHMYMEDIA_DOMAIN=home.watchmy.media
   WATCHMYMEDIA_BIND=127.0.0.1
   ```

4. **Start everything** with the proxy file added:

   ```bash
   docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.proxy.yml up -d
   ```

   If the stack was already running, run `down` first so the network is recreated
   with its fixed subnet, `10.87.250.0/24`. The proxy file turns on secure cookies
   and trusts `X-Forwarded-For` only from Caddy's fixed address on that network. If
   Docker reports that the pool overlaps, or your LAN or a VPN uses `10.87.250.x`,
   set `WATCHMYMEDIA_PROXY_SUBNET` and `WATCHMYMEDIA_PROXY_IP` in `deploy/.env`.

Open `https://home.watchmy.media`. Inside your home, that address only works if your
router supports NAT loopback (hairpin NAT); otherwise use Tailscale or the LAN address.

**Keeping the home network safe.** Only Caddy faces the internet. The server runs as
a regular user with read-only media mounts. Admin settings, user management and
first-run setup refuse internet clients, so finish setup before you forward ports.
Use strong passwords and pull updates regularly. Consider turning on Cloudflare
Turnstile for the sign-in page (see [Sign-in protection](#sign-in-protection)). For extra isolation, put the server
on its own VLAN or guest network so a compromised server cannot reach your other
devices.

### On macOS

Docker Desktop shows every connection to a published port as coming from its own
gateway, so a Caddy container would make internet clients look local and pass the
admin check. On a Mac, run Caddy from Homebrew instead and keep only the server and
the DNS updater in Docker:

1. Do steps 1 and 2 above. Forward ports 80 and 443 to the Mac. Never forward a port
   straight to 27587.
2. Install Caddy (`brew install caddy`) and replace `$(brew --prefix)/etc/Caddyfile`
   with:

   ```
   home.watchmy.media {
   	reverse_proxy 127.0.0.1:27587 {
   		flush_interval -1
   	}
   }
   ```

   Then start it with `brew services start caddy`.
3. Start the server with the macOS proxy file. It listens only on 127.0.0.1, turns on
   secure cookies, and trusts `X-Forwarded-For` only from connections made on the Mac
   itself (Caddy and Tailscale Serve):

   ```bash
   docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.proxy-macos.yml up -d
   ```

   Run `down` first if the stack was already running. If Docker reports that the
   pool overlaps, set `WATCHMYMEDIA_PROXY_SUBNET` and `WATCHMYMEDIA_PROXY_GATEWAY`
   (its `.1` address) in `deploy/.env`.

### With SWAG

If you already run [SWAG](https://docs.linuxserver.io/general/swag/) (LinuxServer's
nginx with certificates and fail2ban), use it instead of Caddy and the `ddns` service.
SWAG already holds ports 80 and 443 and your certificate. The steps below use
`watchmymedia.example.org`; any subdomain works, such as `wmm.example.org`, as long as
the DNS record, SWAG's certificate and the conf's `server_name` all use the same name.

1. **DNS and certificate.** Point the name at your home IP; a CNAME to the name SWAG
   already uses is enough. Make sure SWAG's certificate covers it: add the subdomain
   to SWAG's `SUBDOMAINS` (for example `SUBDOMAINS=www,watchmymedia`), or use a
   wildcard. For a name on another domain, such as `home.watchmy.media`, add it to
   `EXTRA_DOMAINS`. SWAG requests the new certificate when it restarts, and
   `docker logs swag` shows whether that worked.
2. **Proxy conf.** Find SWAG's config folder on the host, the folder mounted at
   `/config`:

   ```bash
   docker inspect swag --format '{{range .Mounts}}{{if eq .Destination "/config"}}{{.Source}}{{end}}{{end}}'
   ```

   Copy `deploy/swag/watchmymedia.subdomain.conf.sample` there as
   `nginx/proxy-confs/watchmymedia.subdomain.conf`. If your subdomain is not
   `watchmymedia`, change `server_name watchmymedia.*;` to match it, for example
   `server_name wmm.*;`. Leave `set $upstream_app watchmymedia;` as it is: that is the
   server's container name, not the domain. The conf turns off nginx buffering so video
   and live events stream straight through. Check it and restart SWAG:

   ```bash
   docker exec swag nginx -t
   ```

   ```bash
   docker restart swag
   ```

3. **Shared network.** Find the Docker network SWAG is on and its address:

   ```bash
   docker inspect swag --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}'
   ```

   ```bash
   docker inspect swag --format '{{range $name, $net := .NetworkSettings.Networks}}{{$name}} {{$net.IPAddress}}{{println}}{{end}}'
   ```

   Put both in `deploy/.env`:

   ```bash
   WATCHMYMEDIA_SWAG_NETWORK=<network>
   WATCHMYMEDIA_SWAG_PROXY_IP=<SWAG-address-on-that-network>
   ```

4. **Start** with the SWAG file. It joins that network, listens only on 127.0.0.1,
   turns on secure cookies, and trusts `X-Forwarded-For` only from SWAG's address.
   Give SWAG a stable address on that network. When upgrading an existing setup,
   replace `WATCHMYMEDIA_SWAG_SUBNET` with `WATCHMYMEDIA_SWAG_PROXY_IP`; trusting
   the whole subnet would let other containers impersonate forwarded clients.

   ```bash
   docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.swag.yml up -d
   ```

**Server outside Docker.** When the server runs natively on the same machine as SWAG
(the binary or the menu bar app), set `$upstream_app host.docker.internal;` in the conf
in step 2 and skip steps 3 and 4. The address the native server sees depends on Docker
and the host: it may be loopback or a bridge address. Trust only the actual SWAG peer
named in the server's untrusted-proxy warning. For a loopback peer, for example, use
this in `config.yaml` in the data directory
(`~/Library/Application Support/WatchMyMedia` for the menu bar app):

```yaml
auth:
  secure_cookies: true
network:
  trusted_proxies: [127.0.0.1, "::1"]
  admin_networks: [local, "!192.168.65.0/24"]
```

or as `WATCHMYMEDIA_TRUSTED_PROXIES=127.0.0.1,::1` and
`WATCHMYMEDIA_ADMIN_NETWORKS=local,!192.168.65.0/24` in its environment. The
exclusion is for Docker Desktop (next paragraph). Until the server trusts SWAG, it
counts everyone SWAG forwards as an internet client and logs a warning that names
the address to add.

Keep the native server's port off the public internet; expose SWAG's HTTPS port
only. Run the server as a dedicated unprivileged account with read-only access to
the media it needs and write access only to its data/cache directories. SWAG's
container does not isolate the native server from the host filesystem or LAN.

**Checking each hop.** Run these on the server machine, in order, with your own name in
place of `watchmymedia.example.org`. The first one that fails shows where the problem
is. Each should print JSON with `"status":"ok"`. Use plain GET requests: `curl -I`
sends HEAD, which the server answers with 404.

1. The server is up:

   ```bash
   curl -s http://localhost:27587/api/health
   ```

   If this fails, check `docker compose -f deploy/docker-compose.yml logs watchmymedia`.
2. SWAG reaches the server over the shared network:

   ```bash
   docker exec swag curl -s http://watchmymedia:27587/api/health
   ```

   "Could not resolve host" means the two containers do not share a network (setup step 3).
3. SWAG sends your name to the server:

   ```bash
   curl -sk --resolve watchmymedia.example.org:443:127.0.0.1 https://watchmymedia.example.org/api/health
   ```

   SWAG's HTML welcome page instead of JSON means nginx is not using the conf: the
   `server_name` does not match the name, the file is not in `nginx/proxy-confs` or
   does not end in `.subdomain.conf`, or SWAG was not restarted. This lists the files
   nginx loaded and their names:

   ```bash
   docker exec swag nginx -T 2>/dev/null | grep -n -e "# configuration file" -e "server_name"
   ```

4. The internet reaches SWAG: on a phone with Wi-Fi off, open
   `https://watchmymedia.example.org`. If step 3 works but this does not, check that
   the name resolves to your public IP (`dig +short watchmymedia.example.org` against
   <https://ifconfig.me>), that ports 80 and 443 are forwarded to this machine, and
   that your ISP does not use carrier-grade NAT (see "Before you start"). A
   certificate warning means SWAG's certificate does not cover the name yet (setup step 1).
   From inside your home, the public name works only if your router supports NAT
   loopback.

**SWAG in Docker Desktop (macOS, Windows).** Docker Desktop shows every client of a
published port as `192.168.65.1`, so SWAG cannot tell the internet from your LAN. The
SWAG file therefore sets admin networks to `local,!192.168.65.0/24`: anyone who comes
through SWAG, at home or not, needs HTTPS and password confirmation for sensitive admin
actions. The exclusion keeps first-run setup and password-free admin off SWAG. Use
Tailscale or the Mac itself (`http://localhost:27587`) to administer as a trusted client. For the same reason, a
fail2ban ban in SWAG on Docker Desktop bans that one address, which is everyone; watch
for it if sign-ins start failing for all users. If you changed Docker Desktop's subnet
(Settings, Resources, Network), set `WATCHMYMEDIA_ADMIN_NETWORKS` to match.

## Backups

A backup is a `.tar.gz` of the database (libraries, matches, users, watch history) and
`config.yaml`; artwork and subtitle caches are rebuilt on demand, and seek thumbnails are
made again. Backups run nightly
into `<data_dir>/backups` (keep count in Settings), can be made on demand from Settings
or downloaded, and from the command line:

```bash
watchmymedia backup                 # writes into <data_dir>/backups
watchmymedia backup /path/to/file.tar.gz
```

To restore: stop the server, unpack the archive into the data directory (replacing
`watchmymedia.db`), and start it again.

A connected Plex server's sign-in (`<data_dir>/plex-credentials`) is never in a
backup, so a restored server has to connect to Plex again.

## Updates

Settings → General has **Check for updates**. When an admin presses it, the server reads the
list of releases at `updates.url` (`https://downloads.watchmy.media/releases.json`), sending
only its version, and says whether a newer release is out. For a newer one it shows the notes
and how to update: the DMG for the menu bar app (which can also update itself from its menu),
the installer for the Windows app (only releases that have one), `docker compose pull` for
Docker, and the archive for this platform otherwise. The apps and the image say which they are
with `WATCHMYMEDIA_INSTALL` (`macos-app`, `windows-app`, `docker`). A pre-release
server is offered pre-releases too, and a development build is never offered an update.
Nothing is checked in the background; `updates.enabled: false`
(`WATCHMYMEDIA_UPDATES_ENABLED=false`) hides the button.

## Web UI

`/` home (hero, recently added and aired rows), `/tv` and `/movies` (rows plus a filterable,
sortable grid), `/search`, `/shows/:id` with season pages, `/movies/:id`, and `/play/:fileId`
for the player. `/activity` is Now playing, with History, Stats and Users tabs (see
Activity below). `/settings` is Settings, a tab per page: Playback and Account & devices for
everyone, then General, Libraries, Users, Watch history, Transcoding, Security and
Backups for admins (`/admin` redirects there), and
`/libraries/:id/files` is the diagnostics view with scan/match controls and the fix-match
dialog. `/setup` is the first-run wizard.

The Unmatched view of TV Shows and Movies groups files by the one decision that fixes them.
When a show is right but TMDB numbers its seasons differently from your files (a later season
listed as a show of its own, a show split into parts, numbers that run past a season's end),
**Line up seasons** shows a row per season with where the server thinks it starts on TMDB and
why; an admin matches the whole card at once, or uses **Line up…** to choose the show, season
and episode the first file is and see what every file becomes first. The choice is remembered
for the folder, so later episodes of that season match on their own. Matching is done on the
web; the apps have no screen for it.

"Recently added" rows, the added sort and the NEW badge use each file's modified date
from when a scan first found it, so the first scan of an existing collection keeps the
order the files arrived in. A file that changes later keeps its place. Files copied
without keeping their dates (for example plain `cp`) take the copy
time; `rsync -a` and `cp -p` keep the originals.

## Accounts

On first run the web UI asks you to create the admin account. Every API route needs a
session cookie after that, or an `Authorization: Bearer` token. Admins add users under
Settings. Watch progress, resume and continue-watching are per user.

Admins can reset another person's password under **Settings › Users › Reset password**
over HTTPS away from home, or from an allowed admin network. Enter and confirm a new password (at least 8 characters),
then share it with the person. Resetting signs out all their browsers and devices and
cancels pending device approvals; their watch history and settings stay.
To change your own password, use **Settings › Account & devices**, which asks for your
current password. If no admin can sign in, there is no recovery command yet.

Players that cannot send a cookie or header (TV apps, casting) call
`POST /api/auth/media-token` and add `?access_token=<token>` to media URLs: streams,
HLS playlists, subtitles and artwork. A media token works only on those routes, lasts
six hours, and stops working when the session that made it signs out. HLS playlists
fetched with one pass it on to their segments. Session tokens are never accepted in URLs.

Admin settings and user management work from anywhere an admin signs in over HTTPS.
`network.admin_networks` decides the trusted networks (your LAN and tailnet by default):
first-run setup works only there, and admin settings need neither HTTPS nor a second
password there. Away from those networks, sensitive actions ask for the password again
(see below). Turn off remote admin to restrict settings to the trusted networks.

A request from an untrusted peer carrying `X-Forwarded-For`, `Forwarded` or `X-Real-IP`
may have come through a reverse proxy. Until that proxy is in `network.trusted_proxies`,
its client is unknown: no first-run setup, no password-free admin, and no client address in
watch history. When the proxy itself is on a trusted network (one on the server's own
machine is) and sends `X-Forwarded-Proto: https`, an admin behind it still gets admin
settings as anyone away from home does, with the password asked again; a proxy anywhere
else has to be listed first. A trusted proxy must name the client in `X-Forwarded-For` (Caddy and SWAG's
nginx do by default). A request from its address with a header that does not end in an
address could have come from anyone the proxy serves, so its client is unknown in the same
way. A request from its address with no header at all counts as that machine's own, as the
menu bar app's are, so a trusted proxy that never sends the header would make every client
it forwards look local: check yours does.

### Admin settings away from home

Admins can open every Server page over HTTPS. Away from the trusted networks,
WatchMyMedia asks for the admin's password again before adding an admin, resetting a
password or deleting an account; making or downloading a backup; changing sign-in
protection; adding a library or moving its folder; signing in to Plex or connecting or
disconnecting a Plex server; adding, testing or moving a tuner; and connecting, changing,
testing or removing Radarr, Sonarr or Seerr.

Confirmation lasts ten minutes for that sign-in alone. A restart forgets it, and several
wrong passwords trigger the sign-in throttle. Over plain HTTP away from home, admins can
still read Activity, but server settings remain closed. First-run setup always needs a
trusted network. A server used only within a private LAN can keep using plain HTTP;
this feature requires neither internet exposure nor HTTPS for that LAN.

Under Settings › Security, turn off **Let admins change server settings from anywhere,
over HTTPS** to restrict admin to the home network or tailnet. Turning it back on needs a
trusted network. `network.remote_admin: false` (or `WATCHMYMEDIA_REMOTE_ADMIN=false`)
forces it off; `true` forces it on. Either config value wins over the switch. Unset uses
the saved switch, which starts on.

The server logs every sensitive action away from home, its result, the admin and client
address; password confirmations (including failures and throttling); and changes to the
switch. Logs contain no passwords, API keys or tokens. Read the terminal/service output,
`docker compose logs`, `journalctl`, or `~/Library/Logs/WatchMyMedia/server.log` for the
Mac app.

<a id="sign-in-protection"></a>

### Security

- **Throttling.** After five failed sign-ins for an account from one address, further
  attempts from that address wait 30 seconds, doubling up to 15 minutes. An address
  gets 20 failures across all names before the same waits, and an account 50 failures
  an hour from everywhere together. Someone guessing from one place cannot lock the
  owner out from another. Unknown names are throttled like real ones, and a wrong name
  and a wrong password get the same answer in the same time. The address is the real
  client's, so set `network.trusted_proxies` when a proxy sits in front. Without it,
  the limits per address count the client the proxy's headers name, which nobody
  vouches for: one visitor cannot lock out the others, but one that forges the header
  escapes the address limits, leaving only the account's own. (A proxy that names no
  client gets no address limit for sign-ins, and its visitors share one allowance of
  TV pairings.) Signed-in devices then show no address, as watch history does.
  At most two password checks run at once, and only one sign-in for a given
  account/address pair; the bot check runs before a sign-in takes one. Excess attempts
  receive 429 with `Retry-After` rather than accumulating password-hashing work in
  memory.
- **Passwords** are stored with argon2id. New passwords need at least 8 characters;
  older, shorter ones still work.
- **Cookies** are HttpOnly and SameSite=Lax. They are also Secure with
  `auth.secure_cookies` or whenever the request came over HTTPS (directly, or through a
  trusted proxy's `X-Forwarded-Proto`). Each sign-in gets a new session token.
- **Revocation.** Pending device approvals require a still-valid approving
  session. Changing a password cancels all pending approvals for that account,
  including ones approved from the session that stays signed in. Event streams
  recheck the session before each event and at every keep-alive.
- **Deleted accounts** never lend their IDs to new accounts. Deletion removes
  their watch history, statistics and active playback tracking.
- **Cross-site requests.** POST, PUT, PATCH and DELETE requests that a browser sends
  for another site are refused, including a sibling subdomain. Apps and scripts,
  which send no `Origin` or `Sec-Fetch-Site`, are not affected.
- **Errors** from the database or the filesystem reach clients as a bare 500. The
  detail goes to the server log with the request ID.

**Cloudflare Turnstile** (optional, off by default) puts Cloudflare's check that the
person is not a bot on the sign-in and first-run setup pages. The check runs before the
password is tried. It is free:

1. In the Cloudflare dashboard, open Turnstile and add a widget. List every host name
   you open the server at (for example `watchmymedia.example.com`; add `localhost` if
   you sign in on the server itself). Managed mode is fine.
2. In WatchMyMedia, open Settings → Security, paste the site key and secret
   key, and choose Turn on. The server checks the secret with Cloudflare before saving
   it.
3. Reload the page and choose Try it. If the widget fails, the site key or its host
   names are wrong. Fix them before you sign out, or nobody can sign in.

Keys can also come from `auth.turnstile` in `config.yaml` or from
`WATCHMYMEDIA_AUTH_TURNSTILE_SITE_KEY` and `WATCHMYMEDIA_AUTH_TURNSTILE_SECRET_KEY`.
These override the keys saved in Settings, which then cannot change them. While
Turnstile is on, the page's Content-Security-Policy also allows Cloudflare's script and
frame (`https://challenges.cloudflare.com`). The server sends Cloudflare each client's
real address.

- **If Cloudflare is unreachable**, sign-in and setup fail with a 503 until it is back.
  Without Cloudflare's answer the check cannot pass, and letting sign-ins through
  would let bots in whenever the check is down. People already signed in, and signed-in
  TVs and phones, keep working.
- **Locked out** (a deleted widget, a new host name): set
  `WATCHMYMEDIA_AUTH_TURNSTILE_DISABLED=true` (or `auth.turnstile.disabled: true`) and
  restart. Turnstile stays off until you remove it.
- **TV and phone apps** cannot show the widget, so password sign-in from an app is
  refused while Turnstile is on. `GET /api/server` reports `turnstile: true`, and apps
  sign in by linking instead: the app shows a code, and you approve it at `/link` in a
  browser.

## Playback

The server picks the cheapest mode per client and file: direct play of the original file
(HTTP ranges), an HLS remux (`-c copy`) when only the container is unsupported, an
audio-only transcode, or a full transcode as the last resort: H.264, or HEVC when the
browser decodes it and the hardware has an HEVC encoder (HDR is kept as 10-bit HEVC for a
browser on an HDR screen, otherwise tone mapped to SDR). Hardware encoders are
detected at startup (`transcode.hwaccel: auto` prefers VideoToolbox on macOS, then NVENC,
QSV, VAAPI); `GET /api/playback/capabilities` shows what was found. Segments are written
to a `watchmymedia-sessions` folder under `transcode.dir` and removed when a session goes
idle; at startup the server clears that folder and leaves the rest of `transcode.dir` alone.

Event HLS prepares about 32 seconds at a time and resumes near the prepared edge as
segments are delivered. It keeps about 32 seconds of already viewed segments for
brief retries; pausing a viewer also pauses further preparation. VOD uses its own
seekable preparation window. Live TV keeps a rolling one-minute playlist. The
server accounts for temporary file and Live TV output together, with default
limits of 4 GiB total, 512 MiB per file session and 1 GiB free space. Adjust
`transcode.max_cache_bytes`, `transcode.max_session_bytes`,
`transcode.min_free_bytes`, `transcode.event_ahead` and
`transcode.event_keep_behind` in `config.yaml` (or the matching
`WATCHMYMEDIA_TRANSCODE_*` variables). The monitor checks every 250 ms and may
stop an active session with a storage error when a limit is crossed. A segment
being written can briefly exceed a byte limit before the next check and process exit.

The web player asks for seekable VOD HLS for a completed file and uses the
bounded event fallback when the server lacks its duration or keyframes.
Growing recordings keep event HLS. The web player's HLS buffer targets about
30 seconds forward and retains about 30 seconds behind; it may exceed those
hints by a segment and browser decoder overhead. Direct play remains the
original file over HTTP ranges when the browser supports it.

Live TV control requests in the web app and Cast receiver use a ten-second
AbortController deadline, including on TV browsers without `AbortSignal.timeout`.
The server prepares at least six seconds of complete Live TV media before
reporting an output ready, normally three two-second segments. This adds a
bounded startup cushion on each new output while the tuner and storage limits
remain in force. The Cast receiver targets 30 seconds forward for negotiated
Automatic playback, with three seconds before starting/resuming and history
targets of 30 seconds for files or 10 seconds for Live TV. These are player
buffer targets; the live edge, decoder and segment size affect actual retention.
For Live TV recovery, the server requires two output target durations buffered,
with a four-second minimum; a longer copied GOP therefore requires more buffer.
An unknown target duration keeps the conservative 20-second requirement. File
recovery keeps its 20-second minimum. Both retain the minute of fresh evidence,
three-minute cooldown, bandwidth headroom and resource admission checks.

For remote viewers, `network.remote_upload_bps` in `config.yaml` (or
`WATCHMYMEDIA_REMOTE_UPLOAD_BPS`) sets an optional aggregate media upload
ceiling in bits per second; `0` means unlimited. The default
`network.enforce_upload: false` observes delivery without pacing or refusing
starts. To enforce a measured ceiling, set `network.enforce_upload: true` (or
`WATCHMYMEDIA_ENFORCE_UPLOAD=true`) and restart the server. Admins can also
change `upload.bps` and `upload.enforce` with `PUT /api/settings`; that saved
pair takes effect at once and wins over the config and environment on restart.
Settings → Transcoding now offers the same controls in decimal Mbps. It shows
reserved capacity and recent delivery separately, and refuses to overwrite a
change made by another admin until the page uses the latest values. The
temporary playback storage limits remain in `transcode.*` config or the
matching environment variables; that page shows their current values and disk
headroom. A remote admin confirms their password before changing upload limits.
The budget covers
direct file ranges, HLS segments, Live TV, artwork and offline downloads;
LAN delivery is excluded. Downloads yield capacity to active viewing.
Remote fixed-quality starts that cannot fit receive HTTP 429 with a lower
quality or stop-another-stream suggestion; Automatic tries a lower rung.
The admin Settings and Activity screens report configured, reserved and recently
delivered rates separately. Activity also shows current temporary storage and
capacity warnings, and marks a server-paced play. A configured value is a
server policy, not a measurement of the internet connection. Existing fixed streams retain their
quality when the operator lowers the budget, so choose a sustainable value.

For a machine with several GPUs, set an explicit backend and `transcode.device` (or
`WATCHMYMEDIA_TRANSCODE_DEVICE`). NVENC uses a CUDA index; on Linux, QSV and VAAPI use
the render node's number (`0` is `/dev/dri/renderD128`, `1` is `renderD129`); on
Windows, QSV and AMF use a DirectX adapter index. These are different namespaces; an
index cannot be paired with `auto`. Omit the device or use `-1` for the backend default
(on Linux, the first render node that exists). See the
[FFmpeg device options](https://ffmpeg.org/ffmpeg.html#Advanced-options).

On Linux, VAAPI (AMD, and Intel GPUs QSV does not cover) keeps frames on the GPU through
`scale_vaapi` and, where the driver implements it (Intel's does, Mesa's for AMD does not),
`tonemap_vaapi`; otherwise HDR is tone mapped on the CPU and the frames uploaded to the
encoder. Detection proves each step on the render node before using it, and Settings ›
Transcoding's diagnostics say when no render node is visible or the server's user may
not open it.
In Settings → Transcoding, choose the backend, then enter its device index and choose
**Apply GPU**. Leave the field blank to restore the backend default. **Check again** reruns
the probes; **Probe details** shows their results and failures. New plays use the saved
choice; current file streams retain their existing GPU.
Stored settings take precedence over config/environment. The admin settings API accepts
`{"hwaccel":"nvenc","hwDevice":1}`; `hwDevice:-1` resets to the backend default, and
changing backend without a device clears the old index. This requires API level 16.
Device selection also applies to software-filtered subtitle burn-in and Live TV encoding;
active file/download sessions retain their original selection across VOD restarts.

API level 17 adds optional, admin-only `hardwareAdapters` in Settings. Windows discovery
keeps CUDA and DirectX ordinals separate, omits software renderers without renumbering,
and refreshes on hardware checks. Inventory names do not establish encoder support; the
existing synthetic probes still decide that. Other platforms and unavailable drivers retain
manual/default selection. Settings shows named choices when discovery is available,
with a backend default and a manual entry for unknown/custom indices. Apply binds
the backend and index together; discovery errors do not overwrite the saved choice.

`watchmymedia gpu-adapters` (Windows app bundle: `watchmymedia-server.exe gpu-adapters`)
prints a local inventory without FFmpeg, configuration, library data or network services.
Each namespace runs in its own managed worker with a five-second deadline, bounded output
and at most two outstanding workers. A failed namespace does not erase healthy results.
Driver calls stay out of HTTP handlers. The internal `--native` mode is only for workers.

Admin `GET /api/settings` includes bounded `hardwareDiagnostics` explaining which probes
worked or failed. These details are excluded from ordinary playback capabilities. For a
standalone report, run `watchmymedia probe-transcoder` (Windows app bundle:
`watchmymedia-server.exe probe-transcoder`), with optional `-hwaccel nvenc -device 1` and
`-ffmpeg <path>`. It tests generated media in temporary storage and prints JSON; it does
not open your library, database, configuration or network services. No usable H.264
encoder produces a failing exit status. Hosted Windows CI retains the software report
and native adapter inventory as private artifacts for seven days. The probe report includes
the same inventory on Windows. Test results do not establish physical Windows,
GPU-vendor, HDR quality or hybrid-laptop acceptance. `transcode.max_sessions` remains an
operator-set cap; one pipeline measurement cannot establish a concurrent-stream limit.

For repeatable Windows machine results, use the [local hardware evidence collector](windows/README.md#collect-hardware-test-evidence).
It records packaged binary hashes, driver and platform metadata, native indices and
per-device probe outcomes, while keeping real desktop/GPU/signed-update acceptance open.

4K files play as they are (or with only the audio converted) in browsers that decode
them at 4K, which covers HEVC in Chrome, Edge and Safari on most recent machines.
Transcodes are capped at 1080p unless a synthetic 4K HEVC Main 10 HDR-to-SDR
decode/filter/encode run reaches 48 fps. This includes the selected decoder and filters,
using a moving encoded test pattern. The run is timed from its first converted frame to
its last, at the priority playback runs at, so the time a busy machine takes to start
ffmpeg and its hardware sessions doesn't count against it; `maxHeight` and `speed4k` in
the capabilities show the result. Missing codecs, failed probes and timeouts keep the 1080p cap. For viewers on a slow connection, the player's quality menu picks
4K, 1080p, 720p, 480p or 360p, and the server transcodes anything above that; the choice is
remembered on each device. On a Mac, transcode with the native binary rather than
Docker: Docker Desktop cannot reach VideoToolbox, so its transcodes run on the CPU and
a 4K HDR source barely keeps up. Natively, decoding, scaling, HDR tone mapping and
encoding all stay on the media engine, so a 4K HDR transcode costs a few percent of one
core; subtitle burn-in is the exception and needs an ffmpeg with libass, which the
bundled one has and Homebrew's lacks.

**HDR and Dolby Vision**. HDR video plays as it
is only on a screen that shows that kind of HDR (HDR10, HLG); anywhere else it is transcoded
and tone mapped to SDR, or kept as 10-bit HEVC HDR for a device that takes it when the
hardware has an HEVC encoder. Dolby Vision plays as it is on apps that say their device and
TV play that profile, from the apps' next releases: profiles 5 and 8 by direct play or a
remux that keeps the Dolby Vision for Apple TV, Android and Cast to find, profile 7 (UHD
Blu-ray remuxes) by direct play only. Elsewhere profiles 7 and 8 send their HDR10, HLG or SDR
base layer with the Dolby Vision taken out, at no transcoding cost, and profile 5, whose
base layer no plain decoder shows right, is transcoded to SDR. Its colours come out right
only where ffmpeg can apply the Dolby Vision metadata: the jellyfin-ffmpeg the macOS and
Windows releases bundle can, while the Docker image's (Debian's) and most Linux
distributions' cannot yet, so there profile 5 transcodes play with the wrong colours.
Settings › Transcoding shows which this server has (`doviToneMap` in the capabilities).
Older apps keep getting Dolby Vision as they did, on HDR screens only. The web player says
what the browser's screen shows: HDR10 and HLG on an HDR screen, and Dolby Vision where the
browser also decodes it. The Cast receiver says what the TV reports, once the receiver with
this is deployed.

**Automatic quality**. The server can also
pick the quality for the viewer. A play at Automatic starts as the original on the home
network, and elsewhere at the step the device last measured from that address (the original
when it has measured nothing). When the player stalls, starts too slowly or its buffer drains,
it is told to drop to the highest step its connection carries (4K · 20, 1080p · 8, 720p · 4,
480p · 2 or 360p · 1 Mbps) and reloads where it was. Existing players only go down within a
play; the next play starts from the newest measurement. From API level 24, a player can opt in
to cautious recovery using the age of its own completed media transfers. The server asks for
at least a minute of fresh evidence, a healthy buffer, three minutes since the last switch,
and spare upload capacity before proposing a higher step or the original. It keeps the old
stream available through an upward replacement until the new first frame is acknowledged;
failed upgrades back off and stale switch generations are rejected. The web player and Cast
receiver opt in when they can measure completed HLS segment transfers and the server supports
API level 24. They keep Automatic selected while quality changes and show whether the server
lowered or raised it. Android and Apple adopt this in the U2–U3 phases of the streaming plan.
When the transcoder is what falls behind, it
drops a step too, and Activity says so. Plays at a fixed quality are measured the same way, so
Activity shows what Automatic would have done before anyone picks it. The web player, the
Cast receiver and the Android and Apple apps offer Automatic first in the quality menu, and use it
until a viewer picks another quality; the apps from their next releases.

**Skip Intro and Next Episode.** The player offers Skip Intro, Skip Recap and Skip Credits,
and a Next Episode card at the end credits. Segments come from chapter names in the file
("Intro", "Opening", "Recap", "Credits", "ED" and so on) and, for episodes without them,
from matching audio across each season in the background: a few minutes after a scan
finishes, the server compares the first and last minutes of each episode with its
neighbours to find the theme song and the end credits. That costs about a second of
CPU per episode, at low priority, and runs once per file; turn it off with
`scan.detect_segments: false` (or `WATCHMYMEDIA_SCAN_DETECT_SEGMENTS=false`).

**Seek thumbnails**. While someone scrubs or seeks,
the players can show a small picture of the video at that point; the server makes them in the
background for every file a minute or longer, newest first, a few minutes after a scan. Each is
320 pixels wide, one every 10 seconds, taken from keyframes only, a hundred to a JPEG sheet,
with HDR tone mapped as transcodes are: Dolby Vision profile 5's colours come out right where its
transcodes' do (the macOS and Windows releases' ffmpeg), and thumbnails made without that are
made again once the server's ffmpeg can. The job runs at low priority for the CPU and the disk
(`nice`, and `ionice` on Linux or `taskpolicy` on macOS), starts no sheet while anything is playing and goes on 2 minutes after the last play
stops, and waits while the data folder's disk has under 5 GB free. They take about 2 MB per
hour of video, so about 35–45 GB for a library of 17,000 hours, in `<data_dir>/trickplay`,
which backups leave out. The first pass reads every file once, which on a NAS is days of
background reading for a large library; after that only new and changed files. Settings →
Transcoding shows how far it has got and what it takes on disk, turns it off (what is made is
kept, and still shown), and deletes them all. Thumbnails of a file missing for 30 days are
deleted. The web player shows them while the pointer hovers over or drags its seek bar, the
Android app while scrubbing or seeking, and the iPhone and iPad apps while scrubbing, from their
next releases. The Apple TV app uses the system player, which draws them from an HLS I-frame
playlist: the server encodes a file's thumbnails into a small H.264 track the first time an HLS
play of it asks (a tenth of a second for a 2-hour film; about as much again on disk, beside the
sheets), on servers whose ffmpeg has libx264.

**Trailers**. The server keeps up to three trailers for
each movie from TMDB, which lists YouTube videos, not files: they come with a movie's details
when it is matched, and a background job asks again for movies matched before, then weekly for
films released in the last six months or not yet out and monthly for the rest, 250 movies at a
time, with the same TMDB key and pace as matching. They play in YouTube's own player or app, not
through the server, so they don't cast. **Trailers from YouTube**, under the key in Settings ›
General › Metadata (TMDB), is on by default; off, movies list no YouTube trailers, the web app
may not show YouTube's player, and the job asks TMDB nothing. The Trailer button on movie pages
comes with later releases of the web app and the apps.

Movie libraries also read trailer files: `<movie file name>-trailer.mkv` (or `.trailer`,
`_trailer`, ` trailer`) next to the movie, `trailer.mkv` or `movie-trailer.mkv` in a folder that
holds one movie, and the videos in a `Trailers` folder inside the movie's folder. A movie lists
them before its YouTube trailers, whatever the setting. They play through the server like any
file, so they cast, but keep no progress or history, stay out of the library's counts, and are
deleted with their movie.

## Live TV

The web app can watch unprotected antenna channels from an HDHomeRun using its
HTTP interface. In **Settings → Live TV**, enter the tuner's local address,
test it, then add it. Run the initial channel scan in the HDHomeRun app; use
**Refresh channels** in WatchMyMedia after changing that lineup. The server
must be able to reach the tuner on HTTP ports 80 and 5004. A server with only a
tuner can finish setup without adding a movie or TV folder.

Admins can choose who may watch, hide channels, and disable or remove a tuner.
Live TV starts with admin-only access. Protected channels are excluded;
channels missing from a refreshed lineup stay marked unavailable. Settings
retain the last good lineup if a refresh fails.

### Listings

Programme listings come from SiliconDust's guide, the one the HDHomeRun app
uses, and are off until an admin turns them on in **Settings → Live TV**.
Then the server fetches them about once a day: two days ahead, or fourteen
with SiliconDust's DVR subscription. Only the server contacts SiliconDust,
and nothing about who watches what is sent. The fetch proves the tuner with
the `DeviceAuth` it reads from the tuner each time; tuner firmware too old to
have one (such as `20170815`) needs updating in the HDHomeRun app, or the
email of an HDHomeRun DVR account entered in Settings. The server matches
listings to channels by number, flags a channel whose names disagree, and
lets an admin hide one channel's listings. Channels the guide doesn't list
show "No listings" and still play, and listings over 36 hours old are marked
out of date. **Refresh now** works four times a day. Pictures are fetched
from SiliconDust's picture host on first view and kept under
`<data_dir>/live-tv-art` (at most 256 MB); turning listings off deletes them
and the listings. With TMDB configured, and unless an admin turns it off,
the server adds TMDB's episode still or movie backdrop, and an overview
where the guide's description is missing or repeated, only for a programme
it matches strictly to the same episode or film; the web app and the apps
credit them to TMDB. News, sport and shopping are never matched. The web
app and the apps show listings from API level 13.

Each viewer uses one slot from the server's shared transcoder limit. By
default each viewer uses one tuner, except that viewers of a channel being
recorded share its recording tuner. The staged `live_tv.share_sources: true`
setting (`WATCHMYMEDIA_LIVE_TV_SHARE_SOURCES=true`) lets viewers of the same
channel share one physical tuner, including a recording that joins them. Each
viewer still has a separate session, output buffer and access check. A
recording needing another channel warns every viewer in the newest viewer's
source group and releases that group before tuning. Other applications may
also occupy the tuner's slots. The web player offers fixed quality choices up
to 1080p, channel switching, a mini guide and Go live. It converts the broadcast
to progressive H.264 with stereo AAC. It keeps about a minute in a rolling
buffer, releases the tuner after five minutes paused, and asks whether you are
still watching after four hours without input. Closing the player releases the
tuner; an abandoned connection expires within a minute.

API-level-24 clients may send a live HLS profile at session start. The server
inspects bounded bytes from that same tuner source and copies compatible
MPEG-TS video and audio, remuxes into fMP4, or converts only audio when that is
enough. Missing source or device facts keep the existing H.264/AAC conversion;
clients without a profile still receive that conversion. The session reports
the selected mode and reason. The web player and Cast receiver send a
conservative H.264/AAC MPEG-TS profile; native Live TV profiles follow.

API-level-25 clients can also opt into Live TV Automatic when shared sources
are enabled. A client starts with `quality: "auto"`, `autoRecovery: true` and
its live profile, then sends fresh transfer speed, buffer and stall readings
on heartbeats. The server prepares a lower or higher output and returns a
generation and URL; the client acknowledges that generation after its first
frame. The viewer's session ID and physical tuner stay the same. If the
server is out of spare output capacity, an upgrade waits while the current
picture keeps playing. Fixed-quality clients continue with their previous
session and heartbeat behavior. The web player and Cast receiver offer
Automatic when the server advertises it; native apps follow in their later
phases of the streaming plan. The Cast receiver also requires its transfer
measurement capability before it opts in.

Live TV appears separately in **Activity → Now playing**. It does not create
watch history or resume progress. Broadcast captions and alternate audio
selection remain deferred. Casting Live TV is available on supported senders.
The HDHR4-2US on firmware `20170815` has been checked with representative
MPEG-2/AC-3 and H.264/AC-3 broadcasts, Chrome, and macOS VideoToolbox, including
1080p output, quality/channel changes, pause/Go live and tuner release. This
does not establish all-channel reception, long-duration performance, other
server hardware or Safari compatibility.

For an isolated browser check, run this from the repository root in two terminals:

```sh
cd server && go run ./cmd/livetvfixture -port 28197
```

```sh
cd web && WMM_DEV_SERVER=http://127.0.0.1:28197 pnpm dev --host 127.0.0.1 --port 25197 --strictPort
```

Open `http://127.0.0.1:25197`, create a disposable fixture admin, and add
`tuner.test`. This command generates fictional broadcasts with ffmpeg, routes
all tuner requests into its own test device, serves fictional listings and
pictures from its own fake SiliconDust (listings are on, and fetched when the
lineup is read), uses a fresh temporary database,
and removes it on Ctrl-C. It never contacts a physical tuner. Without
`WMM_DEV_SERVER`, Vite continues to proxy the normal server on port 27587.

### Recordings (DVR)

An admin enables recording under **Settings → Live TV → Recordings**, picks an
existing TV Shows library and optionally a Movies library, and chooses who may
record. Select a Movies library before recording films. Recording is off until configured; access defaults to Admins only.
Record a programme or series from the guide, or choose **Live TV → Recordings →
New recording** for a channel and time, once, daily, weekdays or weekly. TV apps
can record from the guide; manual schedules are made on the web or a phone.
Series follow the listings, skip episodes already recorded or in the library,
and can keep all recordings or the latest 1–10 by air time, watched or not.

Recordings keep the original MPEG-TS broadcast without converting it, about
3–7 GB per HD hour. Defaults are one minute early, two minutes late, 50 GB kept
free, and no tuners reserved for watching. A recording has priority over a live
viewer: the newest viewer is warned two minutes before its tuner is needed.
Conflicting recordings are shown when scheduled; **Record this instead** changes
which upcoming one wins. Back-to-back recordings on the same channel share their
overlapping padding, and live viewers share a recording's tuner without retuning
when it finishes.

**Watch from the start** plays a growing recording through the ordinary player,
without a second tuner. **Go to now** stays within the part written. The server
keeps captured bytes after a stop, signal loss or disk limit and labels missing
parts. A restart resumes a recording whose end is still ahead. Finished files
join their library with recording provenance, so existing resume, download and
Cast flows apply. Casting a recording that is still growing is not offered.

Deleting from **Recorded** permanently removes only the file WatchMyMedia made,
under the existing Who can delete permission. **Don't record it again** starts
checked. Removing a series rule keeps its files. Keep recording folders writable
only by trusted users. Back up the database before upgrading; use the pre-upgrade
backup when rolling back across a schema migration, while keeping recordings as
ordinary media files.

**Find ad breaks** is experimental and off by default until real-recording
precision is measured. It analyzes black frames and silence at low priority,
yielding to plays and recordings. High-confidence breaks offer **Skip Ad**; each
person's Playback → Ads choice controls skipping, with a button as the default.

The server computer must remain awake for future recordings. The Mac app keeps
it awake while recording, but scheduling a system wake requires privileges it
does not request. For reproducible checks without a tuner, see the
[DVR fixture](server/cmd/livetvfixture/README.md). Physical tuner reception,
long-running broadcasts, device remotes and real ad-break accuracy still need
separate hardware checks.

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

**The receiver.** It is built from `web/` (`pnpm build:receiver` writes `web/dist-receiver/`)
and served by Cloudflare as a Worker's static assets (`web/wrangler.receiver.jsonc`) on the
custom domain `cast.watchmy.media`; deploying creates the domain's DNS record and certificate.
It does not deploy itself on push: after changing `web/receiver/`, run `pnpm deploy:receiver`
in `web/` with `wrangler` signed in to the Cloudflare account that holds `watchmy.media`.
Its App ID, `0A4D0B6A`, is a Custom Receiver registered in the Google Cast SDK Developer
Console under Eldwick Software, with the Android TV package `media.watchmy.client` for Cast
Connect, published so it runs on any Cast device. A second, unpublished App ID for development
runs only on Cast devices whose serial numbers are added in the console. To work on the receiver, run
`pnpm dev:receiver` (it listens on the LAN at :5174), point the development App ID at
`http://<this Mac's address>:5174/`, add that origin to `cast.receiver_origins`, and set
`cast.app_id` to the development ID. Chrome's `chrome://inspect` debugs the receiver on a
registered device.

## Activity

The server records every play, as Tautulli does for Plex but from the inside: the player
reports each state change and a heartbeat (every 10 seconds while playing, 30 while paused),
bytes are counted where they are sent, and ffmpeg reports its own speed. Admins see Activity
in the top bar from anywhere they sign in; stopping a play, deleting history and revoking a
download also work over HTTPS while remote admin is on, or from `network.admin_networks`:

- **Now playing**: who is watching what, on which device and network, the position, how it
  streams (direct play, direct stream or transcode, with the reasons), bandwidth and the
  transcoder's lead, the speed the player measures and its stalls, and Automatic quality's
  changes (or, at a fixed quality, the step down it would have taken). Negotiated switches
  show proposed, applied, failed or canceled outcomes. An admin can stop a play
  with a message, which the viewer sees.

- **History**: every play, newest first, with resumed plays of one item grouped into a row.
  The search box matches any part of a title, `S02E03`, people, devices and the start of
  an IP address, and answers in milliseconds even over a quarter of a million plays.
  Rows expand to each play's stream detail; admins can delete rows and export CSV.
- **Stats**: plays or time per day or month by type and by stream, a weekday-by-hour
  heatmap, most watched and most popular shows and movies, top people and devices, and the
  most streams at once.
- **Users**: each person's last play, watch time, devices and addresses.
- **Downloads**: what is downloading to phones and tablets now, and every download kept, with
  Revoke (see Offline downloads).

The Activity API also reports complete server-prepared ranges and lead for HLS
plays, even when a short ffmpeg run ends before its first progress update.
These ranges describe media ready on the server, not the viewer's player buffer.
The admin Activity screen includes temporary playback bytes and free-space
headroom alongside remote upload reservations and recent delivery.

Everyone else gets "My history" in the account menu: their own plays and statistics only.
Movie and show pages show who watched them. Under Settings → Activity: the shortest play to
keep (two minutes, unless it reached the credits), grouping, how long to keep history,
and whether everyone may see what is playing now. Each user has a "Record history" switch.
History stays on the server.

## Offline downloads

The Android app on phones and tablets can download a movie or an episode to watch with no
connection, and its progress reaches the server when the device is back online
(the server and web side are built, the app follows).

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

## Radarr and Sonarr

Admins connect Radarr (5 or later) and Sonarr (4.0.6 or later) under Settings → Integrations:

- **Path mappings** say where the server finds the folders the app names, when they differ
  (an app in a container sees `/tv` where the server sees `/Volumes/Media/TV`). The page
  suggests them and warns about root folders outside every library.
- **Connect webhook** creates the app's webhook pointed at the address you pick. From then on
  what the app imports, upgrades, renames or deletes is scanned at once, and files in its
  folders match the movie or series it has them as, whatever they are called.
- **Requests**: pick where each library's requests go (a root folder and quality profile).
  People ask for a movie from search, or for seasons from a show's Show missing view; Sonarr
  only downloads the episodes it lacks. Admins' requests go straight through, others wait in
  Settings → Inbox unless *Everyone's requests go straight through* is on. Everyone follows
  theirs under Settings → Your requests.
- **Show missing** on a show page lists the episodes not on disk with why: downloading (with
  progress), requested, monitored, not monitored or not aired yet. Admins can have Sonarr
  search for or monitor a season.
- **Report a problem** on a movie or episode lands in the Inbox with the file's name, codecs
  and resolution.
- **Delete** in the web app's movie, show, season or episode menu lets admins remove files
  managed by Radarr or Sonarr. The confirmation lists the files, extra folder contents and
  the app's recycling-bin behavior. Movies and shows default to removal from the managing
  app without an import exclusion; they can instead be kept unmonitored. WatchMyMedia never
  deletes files itself, and files no connected app manages stay untouched.
- **Replace file** asks the managing app to blocklist the previous grab when it can identify
  it, delete the file and search again. Playback is unavailable until a replacement arrives.

The keys are kept in the server's database, so they are in its backups too.
`deploy/arr/compose.yaml` runs a throwaway Radarr and Sonarr for testing.

### Requests through Seerr

If you already run Seerr, connect it under Settings → Integrations, then link each person's
Seerr account under Settings → Users. While Seerr is connected, requests go through the
linked account with its permissions, quotas and approval rules; people without a link cannot
request. Seerr still needs its own Plex, Jellyfin or Emby connection to finish setup.

Search and Your requests show Seerr's request state, while *In your library* comes from
WatchMyMedia's own files. Admins open Seerr from the Inbox to approve its requests. Requests
made before Seerr was connected remain in the local Inbox and use their original Radarr or
Sonarr targets. A problem report becomes a Seerr issue when the linked account can report
against its media record; otherwise it stays in the local Inbox.

Deleting closes the item's local requests and clears its Seerr record. For a season or
episode, this clears the show's Seerr request history and runs Seerr's Sonarr sync to rebuild
availability. The confirmation explains this before deletion. If Seerr cannot be updated,
the completed deletion stays completed and the result reports the Seerr error.

For disposable integration tests, `deploy/arr/seerr.compose.yaml` runs Seerr and an empty
Jellyfin. `deploy/arr/seerr-setup.sh` initializes that stack; it must only target throwaway
services. The opt-in client test is documented in `server/internal/integrations/seerr/live_test.go`.

## Test and lint

```bash
make test
make lint
```

`make bench` seeds 250,000 plays and fails if any history, search or statistics query
misses its time budget at the 95th percentile; `go test` runs
the same check with 50,000 plays and five times the budgets.

`make server-deps` reports the server's Go modules with newer releases, the newest Go
patch release, the tools CI pins and what govulncheck finds. `make server-deps UPDATE=patch`
(or `UPDATE=minor`) takes those releases, moves `go.mod` to the newest Go patch and runs
the build, vet, lint, tests and govulncheck; it says when the OpenAPI documents changed.

### Account and playback security

The native apps keep saved credentials tied to the selected scheme, hostname,
port and proxy path. Moving a saved server to another address requires sign-in
again, even if it reports the same server ID. Apple apps use the selected address
for AirPlay; choose and sign in at a LAN address explicitly if the AirPlay device
cannot reach the public address. Android tries HTTPS for an unqualified public
hostname; LAN addresses and explicitly entered HTTP addresses remain supported.

Requests and playback reports retain the account/server that started them.
Android TV removes Watch Next entries when the account changes and ignores stale
launcher links. Older unscoped launcher entries are removed on first launch.
Cast status and shared artwork omit credentials; older senders can still load
and control playback, but shared artwork may be absent.

Subtitle conversions are bounded to two simultaneous or pending jobs, and
unknown embedded subtitle indices are rejected before starting ffmpeg. Busy
requests receive a retryable response. Streaming refuses files outside the
configured library root, and scans omit symlinked media and subtitle sidecars.
Keep library directories writable only by trusted users.

The account identity migration preserves existing account and session IDs and
prevents IDs from being reused after deletion. Back up the database before an
upgrade. Restore the pre-upgrade backup when rolling back to an older server;
do not run an older binary against the migrated database. See [SECURITY.md](SECURITY.md)
for vulnerability reporting and deployment boundaries.

## Licence

WatchMyMedia is free software under the GNU Affero General Public License,
version 3 ([LICENSE](LICENSE)). The bundled ffmpeg has its own licence
([deploy/macos/FFMPEG-LICENSE.md](deploy/macos/FFMPEG-LICENSE.md)).
[CONTRIBUTING.md](CONTRIBUTING.md) says how to send changes.
