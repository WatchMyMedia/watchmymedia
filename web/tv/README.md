# WatchMyMedia for TVs

The TV app, one app for each make of TV that runs web apps: what every make
shares is here, and what one make gives the app is its platform, under
`platform/` (`platform/samsung/` for Samsung, `platform/lg/` for LG). Each
make has its own build, package and store listing.

## Samsung

The Samsung TV app: a packaged Tizen web app for 2024 and later Samsung TVs,
playing with Samsung's AVPlay. It is built for Chromium 108, the engine those
TVs shipped with; the first test TV, a 2024 QN90D, has since been updated to
Tizen 9.0 with Chrome 120. It matches the Android TV app's features, Live TV
included.
It needs a server at API level 32 or newer (WatchMyMedia 1.8.0). It has
been signed with a Samsung certificate and run on that TV (below). Its first
version, 1.0.0, went to Samsung's review on 2026-11-20. This code is 2.2.0
(`platform/samsung/public/config.xml`): 2.0.0 added several servers, 2.1.0 added watchlists,
which need a server at API level 33 (WatchMyMedia 2.1.0), and 2.2.0 redraws
the app for the couch, with people, Remove from Continue watching and one card
a title across servers, which need API level 34 (WatchMyMedia 2.2.0). It goes
to Samsung once 1.0.0 is approved.

### Where it stands

Built on 2026-11-19 and checked in the browser preview; the first TV session (below) checked sign-in, browsing and playback on a QN90D:

- The start: choosing a server (remembered servers, an address typed with
  the TV's keyboard, Not encrypted for plain http away from home),
  signing in with a six-character code and a QR code of the server's link
  page that renews as it runs out, or a name and password, and the problems
  (can't reach, not set up, too old for this TV, signed out from another
  device). The last sign-in comes back on launch once the server's ID is
  checked.
- The shell: the menu (Search, Home, TV Shows, Movies, Live TV when the
  server has it, Settings with the Inbox count, and who is signed in) on the
  top-level screens, sections on Home and pages on what opened them, each
  screen remembering its place, Return walking back and asking before
  leaving at Home, Hold OK as a shortcut, the remote's media, channel and
  number keys, and a dialog with Try again now when the server stops
  answering.
- Several servers, from 2.0.0: the rail's account button opens Servers (the servers
  this TV has, the current one marked, Add a server, Manage servers). OK on
  another one switches at once, with "Now on …" for a moment; one that
  doesn't answer leaves the app where it was, with Try again; one signed out
  here opens its sign-in. Settings › Account & devices › Servers has each
  server's page and Remove this server…. Search looks in every server this
  TV is signed in to, names the server under a title from another one, and
  switches to it when that title is opened. Up to 12 servers, kept on the
  TV only.
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

The screens:

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
are bundled locally. `platform/samsung/public/config.xml` carries the store identity, chosen
on 2026-11-20 and never to change: package id `cPFf0iV3Li`, application id
`cPFf0iV3Li.WatchMyMedia`, signed with the maintainers' Samsung author
certificate.

## LG

The LG TV app: a packaged webOS web app for 2024 and later LG TVs (webOS 24,
whose web engine is Chromium 108), the same app as Samsung's with LG's
half. It needs the same servers. Its store identity is `appinfo.json`'s id,
`media.watchmy.client` (`platform/lg/public/appinfo.json`), at 2.2.0 with
the Samsung app.

### Where it stands

Built on 2026-11-24 and checked in the browser preview; **not yet run on an
LG TV**, so everything below that a TV decides is a claim to check there.

- **Playing**: the TV's one video element. HLS goes through hls.js on Media
  Source Extensions rather than webOS's own HLS, which LG documents without
  the tags the server's playlists use; a resume starts loading where it
  resumes; the buffer is bounded at 60 MB. Files go to the video directly.
  Audio track changes are the server's, as a new stream. The profile claims
  MP4 and MKV, H.264 and HEVC (8 and 10 bits) to 4K, AAC, AC-3 and E-AC-3 to
  5.1, HDR10 only where the TV says it shows it, and no DTS, Dolby Vision or
  HDR10+ (`platform/lg/profile.ts`).
- **The remote**: Back is key 461 (`disableBackHistoryAPI`), the arrows, OK
  and the media keys of LG's conventional remotes; held keys are acted on
  every 100 ms. With nothing left to go back to, Back hands over to webOS
  (`platformBack`), which asks and goes to the TV's Home. LG gives apps no
  channel or number keys: Up and Down change channel in the live player, and
  Go to channel is a button there and in the guide, its pad taking focus.
- **The Magic Remote's pointer**: pointing focuses, a click presses, the
  wheel steps through a row or down a page, and while the pointer shows,
  rows have arrows at their ends, a focused card has a More button (its
  Hold OK actions), the player has a Back button and the live player CH ▲▼
  at the right edge.
- **The TV's own calls** (`platform/lg/webos.ts`), made through the system
  objects webOSTV.js wraps rather than a copy of LG's library: the model and
  webOS version for Settings › About and the devices list, whether it shows
  HDR10, coming forward when opened again (`handlesRelaunch`), and asking
  the TV not to start its screensaver while something plays, which LG
  doesn't document for web apps: where the TV refuses, full-screen video
  keeps it away by itself and a paused screen may dim.
- Words: "LG TV" and "Back" wherever Samsung's say "Samsung TV" and
  "Return". Storage is its own (`watchmymedia.webos.*`).

To check on the TV: which headers its requests send (Samsung's sent no
`Origin`), HLS through hls.js at 4K and with 5.1 sound, HDR10, seeking,
Home and back mid-play, the pointer and a held arrow's pace, Back at Home,
the screensaver call, a YouTube trailer (YouTube refused another LG app's
framed trailers for three months in 2025), launch time and memory. Its `requiredACG` (the
Luna access groups its calls need, which LG's store requires from webOS 27)
is to be set from what the TV allows.

## Run the browser preview

On the fictional server, from `web/`:

```sh
pnpm install --frozen-lockfile
pnpm dev:tizen
```

`pnpm dev:webos` previews the LG build the same way, where the mouse stands
in for the Magic Remote's pointer. Open <http://127.0.0.1:5186/?fakes>. Its server, Lakeside, is at the
prefilled address; its code signs in by itself after a few seconds, and the
password form takes Sam and `watching`. `?fakes=away` adds a remembered
server away from home over http; `?fakes=servers` opens signed in to
Lakeside on a TV that has three more servers (Cabin, with titles of its
own; Den, which never answers; Attic, signed out here), for Servers, a
switch and Search across servers; `?fakes=onecard` opens on Lakeside with
Cabin, away from home and sharing some of its titles, and Den, which never
answers, for one card across servers (search, Home, Movies and TV Shows, a
title's versions on each server); `?fakes=old`, `?fakes=setup` and
`?fakes=expire` show the start problems. `?fakes=member` signs in a member
instead of an admin; `?fakes=empty`, `scanning` and `unreadable` give Home's
empty states; `?fakes=player` makes the player step down in Automatic, stop
one play for the administrator, fail another and ask Still watching early;
`?fakes=nolistings` and `?fakes=notv` give Live TV without listings and
without access, and `?fakes=notuner` one whose channels can't play; `?fakes=savefail` refuses every change to the account's
settings, `?fakes=nosettings` doesn't answer for them, and `?fakes=seerr`
carries requests out through Seerr. Sam has three watchlists (Watchlist,
Halloween and With the kids); `?fakes=nowatchlists` is a server too old to
keep them, where the app offers none. The fake server answers API level 35
(level 34's cast members' titles, Remove from Continue watching, the
libraries' counts and combined filters, recordings' New and watched; and
level 35's Boost dialogue, Reduce loud sounds, the person's subtitle size
and subtitle timing, with 0.8 s later set for everyone on every file's SDH
track); `?fakes=level34` is a server without level 35's, and
`?fakes=level33` one from before level 34, where those screens behave as
they did. `?fakes=soundfail` refuses a sound setting's change and
`?fakes=timingfail` a subtitle timing's. Nothing on the network is asked: every
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
WMM_TIZEN_TEST_SERVER=http://127.0.0.1:27686 pnpm vitest run tv/live.test.ts
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

Verification, 2026-11-19: the TV app's 141 unit tests (the shell,
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

Several servers, 2026-11-20: 368 unit tests pass. In the browser preview
at 1280×720, on `?fakes=servers` and against two throwaway servers with
different libraries (the second reached through a small local proxy that
drops `Origin`, `Sec-Fetch-*` and cookies, as the TV's requests arrive):
Servers from the account button and Return back to it, a switch both ways,
a server that doesn't answer and Try again, a sign-in on a server signed
out here and Cancel, Add a server to its last step, Remove, Sign out moving
to another server, an address already added being refused, and Search
finding a word on both servers, opening the other server's title, playing
it (in that server's Activity only), searching again with one server
stopped, the lines for a server signed out here and for the current server
answering late, and Try again. Not on a TV: a real remote, the
packaged app reaching two servers, and AVPlay after a switch.

## Packaging and testing on an LG TV

From `web/`, with LG's CLI (`npm install -g @webos-tools/cli`, 3.2.6 or
later, Node 20 or later):

```sh
pnpm build:webos
pnpm check:webos
pnpm package:webos
```

`build:webos` writes the app (`index.html`, the one classic script and
stylesheet, `appinfo.json`, `icon.png` at 80 px and `largeIcon.png` at
130 px) to ignored `web/dist-webos/`. `check:webos`
([deploy/lg/bundle.py](../../deploy/lg/bundle.py)) runs Samsung's Chromium
108 checks on it and then LG's own. `package:webos`
([deploy/lg/package.py](../../deploy/lg/package.py)) has `ares-package`
check the app, writes `build/lg/media.watchmy.client_<version>_all.ipk` and
reads it back. The `.ipk` is unsigned: Developer Mode installs it as it is,
and LG signs what its store accepts. CI's `lg` job does the same and keeps
the `.ipk` as an artifact.

On a TV, each step with the maintainers' go-ahead: install LG's Developer
Mode app from the TV's store and sign in with the LG developer account; in
it, switch Developer Mode on and the key server on, and note the TV's
address and passphrase. Then `ares-setup-device` adds the TV (port 9922,
user `prisoner`), `ares-novacom --device <name> --getkey` fetches its key
with the passphrase, `ares-install --device <name> build/lg/<the .ipk>`
installs it, `ares-launch --device <name> media.watchmy.client` opens it and
`ares-inspect --device <name> --app media.watchmy.client --open` opens
Chrome's inspector on it. A Developer Mode session lasts up to 1000 hours;
when it runs out, apps installed in it are removed.

## Signing and testing on a Samsung TV

Done on 2026-11-20 with a 2024 QN90D. In order:

1. **Developer Mode on the TV.** On 2024 and later TVs the code goes in the
   Apps screen's own settings (its gear), not the Apps screen itself: open
   Apps, then its settings, and enter 1 2 3 4 5 with the remote's 123 number
   pad, or SmartThings' remote with its number pad on. Switch Developer mode
   on, enter this computer's address as the Host PC IP, and restart the TV
   fully (hold the power button until the logo shows). Port 26101 opens about
   a minute after the TV starts.
2. **A Samsung certificate profile.** Only Tizen Studio's Certificate Manager
   with Samsung's certificate extension makes one: install the IDE
   (`web-ide_Tizen_Studio_*_macos-64.dmg` from download.tizen.org; the
   command-line-only SDK has no Certificate Manager, and VS Code's Tizen TV
   extension makes only Tizen certificates, which retail TVs refuse), add
   **Samsung Certificate Extension** from its Package Manager, then in
   Certificate Manager: **+**, Samsung, TV, a profile name, a new author
   certificate (the maintainers sign in with their Samsung account and keep
   the password), and a new distributor certificate with the Public privilege
   and the TV's DUID (`sdb shell 0 getduid`, or the TV's Support menu). The
   files land in `~/SamsungCertificate/<profile>/`; back them up, since every
   store update must be signed with the same author certificate. They expire
   after a year.
3. **Sign** the built app, naming that SDK's `tizen`:

   ```sh
   python3 deploy/samsung/package.py --profile PROFILE --app web/dist-tizen --tizen ~/path/to/tizen-sdk/tools/ide/bin/tizen
   ```

   It refuses incomplete apps, symlinks and signing material, and writes
   `build/samsung/watchmymedia-samsung-tv-<version>.wgt`.
4. **Install and launch** with that SDK's tools: `sdb connect <TV address>`,
   `tizen install -n <the .wgt> -s <TV address>:26101`, then
   `sdb -s <TV address>:26101 shell 0 debug cPFf0iV3Li.WatchMyMedia`, which
   prints a port for Chrome's inspector (`sdb forward tcp:<local> tcp:<port>`,
   then `http://127.0.0.1:<local>/json`). If `sdb connect` keeps failing while
   port 26101 answers, `sdb kill-server` and connect again.
5. **A server at API level 32**, 1.8.0 or newer: a release, or one built from
   `main` running the synthetic fixture above with `WATCHMYMEDIA_HOST=0.0.0.0`,
   on the home network only for the test, discovery off, and stopped afterwards.

What the first session on the QN90D found:

- The signed package installs and starts in about 2.3 s to first content, with
  about 10 MB of JavaScript memory.
- The app's requests carry **no `Origin` and no `Sec-Fetch-*` headers** and no
  cookies, only the bearer token. Pairing, sign-in, browsing, playback and
  progress all returned 200. (API level 32's exemptions are not what let this
  TV through; they cover a runtime that does send those headers.)
- AVPlay played MKV with HEVC and E-AC-3, and MP4 and MKV with H.264 and AAC,
  directly from the file, picture and sound confirmed on the TV; and a 480p
  conversion, as HLS with HEVC video, picking up where the direct play left
  off. Seeking (applied on key-up), WebVTT subtitles in time, resume, Continue
  watching and watched marks, and the remote's keys all worked.
- A second session found that Tizen loads YouTube's player in an iframe only
  where `<tizen:allow-navigation>` names YouTube; without it the frame stayed
  on `about:blank` and the trailer screen said it couldn't reach YouTube.
- The second session also tried Home and back. Samsung's `restore()` brought a
  direct play back unprepared at 0:00, so a play sent to the background is
  let go and opened again where it was, paused, as an HLS play always was.
  AVPlay lands a seek in a file on the keyframe *after* the frame asked for
  (9 s late in a test clip with 10.4 s between keyframes, and ⏪ from just
  past a keyframe landed on it again), so the engine seeks once more, one
  usual keyframe gap back, whenever it lands past. Home
  and back then came back paused 3 s before where it left, and a resume
  landed before the saved position with one seek, playing 3 s after the
  server's answer. Each seek before the first frame took about 3 s there.
- Not yet tried there: image subtitles.

Do not broadly trust `Origin: null` on the server. HDR, certification and
more model groups are still to do.

Samsung references: [certificate setup](https://developer.samsung.com/smarttv/develop/getting-started/setting-up-sdk/creating-certificates.html),
[TV testing](https://developer.samsung.com/smarttv/develop/getting-started/using-sdk/tv-device.html),
[remote keys](https://developer.samsung.com/smarttv/develop/guides/user-interaction/remote-control.html),
[screensaver](https://developer.samsung.com/smarttv/develop/guides/fundamentals/setting-screensaver.html),
[AVPlay](https://developer.samsung.com/smarttv/develop/api-references/samsung-product-api-references/avplay-api.html).
