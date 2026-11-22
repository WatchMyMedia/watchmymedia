# Development

How to build WatchMyMedia from source, run it while you work on it, and test it. To install
a release instead, see [watchmy.media/download](https://watchmy.media/download) and
[install.md](install.md).

[CONTRIBUTING.md](../CONTRIBUTING.md) says how to send a change. [AGENTS.md](../AGENTS.md) and
the `AGENTS.md` in each folder hold the rules the code follows: which files are generated and
how to regenerate them, how the server stays compatible with older apps, and which checks to
run for what you changed. Each folder with code has its own README too. Tagging and shipping a
version is in [releasing.md](releasing.md).

## Layout

- `server/` — Go server (chi + huma API, SQLite via sqlc/goose, ffmpeg orchestration, embedded web UI)
- `web/` — React + Vite web client, API types generated from the server's OpenAPI; the Cast receiver in `web/receiver`, and the Samsung TV app in `web/tizen` ([web/tizen/README.md](../web/tizen/README.md))
- `android/` — Android app for phones, tablets, Google TV and Android TV ([android/README.md](../android/README.md))
- `apple/` — iPhone, iPad and Apple TV apps ([apple/README.md](../apple/README.md))
- `macos/` — the Mac menu bar app that runs the server
- `windows/` — the Windows app and installer that run the server ([windows/README.md](../windows/README.md))
- `api/` — the OpenAPI spec the apps generate their clients from
- `deploy/` — Dockerfile, compose examples, config example, release and fixture scripts
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
and live scan events at `/api/events` (SSE).

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
media afterwards. [The harness guide](../deploy/fixture/streaming.md) explains
individual scenarios, the simulated player buffer and the measurements this
baseline can and cannot establish.

## Test and lint

```bash
make test
make lint
```

`make bench` seeds 250,000 plays and fails if any history, search or statistics query
misses its time budget at the 95th percentile; `go test` runs
the same check with 50,000 plays and five times the budgets. It also seeds a library the
size of a large household's and holds Home, the library pages and library search to
budgets of their own, alone and with ten requests at once.

`make server-deps` reports the server's Go modules with newer releases, the newest Go
patch release, the tools CI pins and what govulncheck finds. `make server-deps UPDATE=patch`
(or `UPDATE=minor`) takes those releases, moves `go.mod` to the newest Go patch and runs
the build, vet, lint, tests and govulncheck; it says when the OpenAPI documents changed.

## Building the apps

### Android

One APK for phones, tablets and Android TV / Google TV, in `android/` (modules and
conventions in [android/README.md](../android/README.md)). It needs a JDK 17 or newer
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

### iPhone, iPad and Apple TV

The iPhone, iPad and Apple TV apps are in `apple/` (layout and conventions in
[apple/README.md](../apple/README.md)). They
need Xcode with an iOS simulator runtime and XcodeGen 2.45.4 or newer (`brew
install xcodegen`):

```bash
make apple-test       # the shared Swift package's unit tests, on this Mac
make apple-build      # generate the Xcode project and build for the simulator
make apple-test-sim   # build and run every test on a simulator
make apple-tv-test-sim  # the same for the Apple TV app
```

`make apple-gen` writes `apple/WatchMyMedia.xcodeproj` for opening in Xcode.
The launch argument `-fakes` runs it on a made-up library without a server;
against a real one, `make fixture-serve` and type `localhost:27588` in the
simulator's server picker. On a phone, the server must be reachable at an IP
address or `.local` name over http, or at an https address: iOS refuses plain
http to other host names.

### Mac app

`make macos-app` builds the app for this Mac from the Swift package in `macos/`;
`make macos-dmg` builds the universal DMG the release job ships.
`swift test --disable-keychain --package-path macos` checks the startup supervisor with
disposable data; it does not launch the installed app or open a browser.

### Windows app

`make windows-app VERSION=development` builds the tray app and its bundled server into
`build/windows-app/` (copy the folder to a supported PC and open `WatchMyMedia.exe`), and
`make windows-installer` builds an unsigned development installer;
[windows/README.md](../windows/README.md) covers building and testing them.

### Linux packages

`make linux-packages` builds the same four packages, unsigned, into
`build/linux-packages/`; install one with `sudo apt install ./watchmymedia_<version>_<arch>.deb`
or `sudo dnf install ./watchmymedia-<version>-1.<arch>.rpm` (Fedora 45's `rpm -i` refuses a
package that is not signed; `dnf` installs it with a warning). They carry the repository
entry, as the released ones do; `make linux-packages LINUX_REPOSITORY_KEY=` builds them
without it.
[deploy/linux](../deploy/linux) holds the one [nfpm](https://nfpm.goreleaser.com/)
file both formats are built from, what it packs and the make targets. `make
linux-smoke-packages` builds an older and a current set, signed by a throwaway key (it needs
`gpg`; the real signing key is only ever in a release), and
`make linux-packages-smoke IMAGE=debian:13` installs, updates, removes and purges them in a
container that runs systemd (it needs Docker; `IMAGE` is any of `debian:12`, `debian:13`,
`ubuntu:24.04`, `ubuntu:26.04`, `fedora:44` and `fedora:45`). CI does the same on all six, on
amd64 and arm64.

### Cast receiver

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

## Test servers

### Live TV

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

The [DVR fixture](../server/cmd/livetvfixture/README.md) covers recordings without a tuner.

### Plex

[The Plex import fixture](../server/cmd/plexsyncfixture/README.md) is for isolated checks of
the import from Plex, without a real Plex server.

### Radarr, Sonarr and Seerr

`deploy/arr/compose.yaml` runs a throwaway Radarr and Sonarr for testing. For disposable
integration tests, `deploy/arr/seerr.compose.yaml` runs Seerr and an empty Jellyfin.
`deploy/arr/seerr-setup.sh` initializes that stack; it must only target throwaway services.
The opt-in client test is documented in `server/internal/integrations/seerr/live_test.go`.
