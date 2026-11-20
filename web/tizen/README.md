# WatchMyMedia for Samsung TV

The Samsung TV app: a packaged Tizen web app for 2024 and later Samsung TVs
(Tizen 8, Chromium 108), playing with Samsung's AVPlay. It is being built to
match the Android TV app's features, Live TV included (PLAN-SAMSUNG.md), as
the screens on the "WatchMyMedia — Samsung TV" canvas. It needs a server at
API level 32 or newer (WatchMyMedia 1.8), which lets an app on a `file://`
page pair and send bearer requests. **Signing and testing on the QN90D are
still to do. No signed `.wgt` exists.**

## Where it stands

Built, 2026-11-19, and checked in the browser preview only:

- The start: choosing a server (remembered servers with Remove, an address
  typed with the TV's keyboard, Not encrypted for plain http away from home),
  signing in with a six-character code and a QR code of the server's link
  page that renews as it runs out, or a name and password, and the problems
  (can't reach, not set up, too old for this TV, signed out from another
  device). The last sign-in comes back on launch once the server's ID is
  checked.
- The shell: the section rail (Search, Home, TV Shows, Movies, Live TV when
  the server has it, Settings with the Inbox count), sections on Home and
  pages on what opened them, each screen remembering its place, Return
  walking back and asking before leaving at Home, Hold OK, the remote's media,
  channel and number keys, and the offline banner.
- The data layer: the web app's own queries and event table over a bearer
  client per session, a fresh cache per server and account, media tokens
  for pictures, and live updates over `GET /api/events`.
- The kit the screens are built from, in plain CSS for Chromium 108.
- The player engine in [`player/`](player/README.md): the AVPlay adapter
  with tracks, live streams and backgrounding; one conservative QN90D-class
  profile; the file, recording and trailer player (heartbeats, directives,
  Automatic, skip, the next episode, Still watching, held seeking with
  thumbnails, WebVTT cues, OpenSubtitles, recovery and token renewal); and
  the Live TV player on the shared tuner controller. It has unit tests with
  fake players and servers only.

The screens, each to its board on the canvas:

- Home, one row a screen with the hero and Continue watching, Edit rows and
  the totals; Movies and TV Shows with their rows, sort, filters, chips, the
  eight-poster grid and the A–Z strip; See all and list pages.
- The movie page (versions, tracks, cast, the collection and More like this,
  Report a problem) and the one show page (the episode on top, season tabs,
  missing episodes with requests, Notify me, Delete episode for admins); YouTube
  trailers inside the app.
- The player for files, recordings and trailer files: the controls, held
  seeking with thumbnails, Skip, the Next episode card, Still watching, the
  Audio, Subtitles and Quality panels, Episodes, the administrator's stop.
- Live TV: home, the guide with favourites, reminders and recording, the Live
  TV player with CH ▲▼ and Go to channel, Recordings, Reminders and the
  reminder banner.
- Search with the on-screen keyboard and the TV's own, On Live TV and requests;
  Your requests and the Inbox; Settings in groups, About listing the last
  playback errors.

The development preview labels nothing as AVPlay: its HTML video and Hls.js
adapter and its fictional server are left out of the TV build. All app assets
are bundled locally. The package ids in `public/config.xml` are still the
development ones (`WMMProto01`): the store's package id, application id and
author certificate are the maintainers' decision, made before the first
store submission.

## Run the browser preview

On the fictional server, from `web/`:

```sh
pnpm install --frozen-lockfile
pnpm dev:tizen
```

Open <http://127.0.0.1:5186/?fakes>. Its server, Lakeside, is at the
prefilled address; its code signs in by itself after a few seconds, and the
password form takes Sam and `watching`. `?fakes=away` adds a remembered
server away from home over http; `?fakes=old`, `?fakes=setup` and
`?fakes=expire` show the start problems. `?fakes=member` signs in a member
instead of an admin; `?fakes=empty`, `scanning` and `unreadable` give Home's
empty states; `?fakes=player` makes the player step down in Automatic, stop
one play for the administrator, fail another and ask Still watching early;
`?fakes=nolistings` and `?fakes=notv` give Live TV without listings and
without access; `?fakes=savefail` refuses every change to the account's
settings, `?fakes=nosettings` doesn't answer for them, and `?fakes=seerr`
carries requests out through Seerr. Nothing on the network is asked: every
other address fails as an unreachable server would. There is no media to play,
so the player shows its screens over a simulated stream.

Against the synthetic fixture server, from the repository or worktree root,
with ffmpeg, Go, Node 24 and pnpm 9 available:

```sh
python3 deploy/samsung/fixture.py
make build
WATCHMYMEDIA_DATA_DIR="$PWD/data/samsung-prototype" \
  WATCHMYMEDIA_HOST=127.0.0.1 WATCHMYMEDIA_PORT=27686 \
  WATCHMYMEDIA_DISCOVERY=false WATCHMYMEDIA_TMDB_API_KEY='' \
  bin/watchmymedia serve
```

This is a dedicated fixture on port 27686. Check that the port is free first.
In another terminal at the same root, after the scan finds the three samples:

```sh
deploy/fixture/fixture.sh accounts http://127.0.0.1:27686
python3 deploy/samsung/fixture.py --seed
cd web
pnpm install --frozen-lockfile
pnpm dev:tizen
```

Open <http://127.0.0.1:5186>. Keep its prefilled address: Vite proxies `/api` to
this dedicated fixture. The sign-in screen names this page's own address, which
serves no link page: open the server's at <http://127.0.0.1:27686/link> in
another tab instead, sign in as the fictional viewer from
`deploy/fixture/fixture.sh`, and approve the code shown.

Use the arrows and Enter, or a mouse. Escape or Backspace is the remote's
Return, PageUp and PageDown are CH ▲▼, and the digits are the number keys.
Enter on a field types in it; Enter, Escape or ▲▼ finish. Sign out from
Settings to revoke the TV's session. Stop both temporary servers with Ctrl+C
afterwards. Never point automated tests at a personal server.

The local proxy is a development convenience. It cannot establish packaged-app
CORS, Fetch Metadata, HTTP/HTTPS or native media-request behavior.

## Build and validation

From `web/`:

```sh
pnpm typecheck
pnpm lint
pnpm test
pnpm build:tizen
pnpm check:tizen
pnpm build:tizen-core
pnpm build
pnpm build:receiver
WMM_TIZEN_TEST_SERVER=http://127.0.0.1:27686 pnpm vitest run tizen/live.test.ts
```

`build:tizen` writes the unsigned app (`index.html`, assets, icon and manifest)
to ignored `web/dist-tizen/`, as one classic deferred script with no
`crossorigin` attributes. The package loads from `file://`, where module scripts
can be refused: in desktop Chrome the earlier module build ran nothing from
`file://`, while this one starts. `pnpm dev:tizen` still serves modules. `pnpm check:tizen`
then fails a build that Chromium 108 can't load: a missing file, a module script,
or JS or CSS newer than Chrome 108 that the build leaves as written
([deploy/samsung/bundle.py](../../deploy/samsung/bundle.py)). CI's `samsung` job lints,
tests, builds and checks the app, and keeps the unsigned build as an artifact; the
core build and the live test stay local. The optional core build writes to ignored
`build/samsung/core/`; it cannot be installed as an app. The live test requires
loopback port 27686 and the dedicated server name before any sign-in or writes.
It revokes its test device and approver sessions afterwards.

From the root:

```sh
make agents-check
python3 -I -m unittest discover -s deploy/samsung -p '*_test.py'
```

Current verification, 2026-11-19: the TV app's 141 unit tests (the shell,
session, kit and player engine), types, lint, `build:tizen`, `check:tizen`,
the core build, the web and receiver builds and `make agents-check` pass. In
the browser preview on the fakes, at 1280×720: a typed address, the code
signing in by itself, the password form with a wrong and a right password,
Home's rows (card by card, the run of ◀ stopping at the first card, a single
◀ opening the rail), each section from the rail and Return to Home with
focus where it was, the player route, the leave dialog, Sign out, and the
too old, unreachable (with Not encrypted) and signed-out problems. The
fixture server and the live test were not run for this checkpoint. A real
remote, the TV's keyboard, screensaver, suspend and wake, AVPlay and the
packaged app's networking remain unverified on a TV.

## Resume signing and QN90D testing later

1. Finish Samsung certificate setup. On this Mac, Tizen SDK 10 CLI is installed at
   `~/tizen-studio` (CLI 2.5.25, sdb 4.2.36), with Rosetta for its Intel utilities
   and the official VS Code Tizen TV extension. The SDK reported no profiles.
   Samsung Certificate Extension 2.0.73 was downloaded and registered as a local
   package source, but package-manager installation stopped with “Network
   disconnected.” Samsung signing is not ready.
2. When the user is ready, enable Developer Mode, authorize this Mac, identify
   the QN90D IP/DUID and create/select a **Samsung TV** signing profile with that
   DUID in its distributor certificate. Account login and certificate custody
   stay with the user. Keep the future store author-identity decision separate.
3. After `pnpm build:tizen`, from the root, the prepared helper can sign the app:

   ```sh
   python3 deploy/samsung/package.py --profile PROFILE_NAME --app web/dist-tizen
   ```

   It refuses incomplete apps, symlinks and signing material, then checks the
   SDK archive's signature entries. Output will be
   `build/samsung/watchmymedia-qn90d-prototype.wgt` (the name follows the
   development package id). Those checks do not establish
   Samsung trust or TV acceptance; actual installation remains required.
4. Connect only to the authorized QN90D, using the synthetic server. Check
   first that the app starts (the classic script and
   `$WEBAPIS/webapis/webapis.js` load) and that pairing gets through: the
   server at API level 32 lets `file://` pages pair and send bearer requests,
   and answers CORS for them, so a 403 or a CORS failure there is the thing to
   look into. Then record firmware, Tizen and AVPlay versions, the remote's
   keys and Hold OK, the TV's keyboard in the address and password fields,
   direct and HLS playback, audio, seek and resume, Stop and Return,
   suspend and wake, and the screensaver. Expose the fixture to the LAN only
   for that authorized test, keep discovery disabled, and stop it afterwards.
   Never record tokens or media URLs.

Do not broadly trust `Origin: null` on the server. HDR, certification and
more model groups remain in [PLAN-SAMSUNG.md](../../PLAN-SAMSUNG.md).

Samsung references: [certificate setup](https://developer.samsung.com/smarttv/develop/getting-started/setting-up-sdk/creating-certificates.html),
[TV testing](https://developer.samsung.com/smarttv/develop/getting-started/using-sdk/tv-device.html),
[remote keys](https://developer.samsung.com/smarttv/develop/guides/user-interaction/remote-control.html),
[screensaver](https://developer.samsung.com/smarttv/develop/guides/fundamentals/setting-screensaver.html),
[AVPlay](https://developer.samsung.com/smarttv/develop/api-references/samsung-product-api-references/avplay-api.html).
