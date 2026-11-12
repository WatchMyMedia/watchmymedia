# Samsung QN90D prototype

S0 development checkpoint, 2026-11-01. The prototype screens are implemented
against [repo-only boards](../../design/mockups/samsung-tv/prototype.html), as
approved by the user. They are not on a design canvas. The browser preview works
against an isolated synthetic server, and the app builds for Chromium 108.
**Signing and testing on the QN90D are deferred. No signed `.wgt` exists.**

## What is implemented

- Server address entry, remembered-session restoration and readable recovery.
- Code pairing, countdown/expiry, cancel and new code; the claim secret is never
  shown. API calls use bearer sessions without browser cookies. Remembered tokens
  are checked against server identity before use.
- A file picker with library selection, six-file pages, empty/loading/error states,
  refresh, compatibility options and sign-out. Card illustrations are local
  decorative artwork; this is not the production poster library.
- Pause/resume, ±10-second seek, Stop/Return, progress and resume, administrator
  stop, file-focus restoration and explicit resume after interruption. Controls
  stay visible in S0. Native operations use the Samsung AVPlay adapter.
- Directional navigation, Enter, Return, media keys, focus-trapped dialogs and exit
  confirmation. Paused/stopped playback re-enables the native screensaver; that
  API behavior still needs device verification.
- Explicit H.264/AAC, HEVC, E-AC-3 and 480p conversion experiments, with VOD/event
  playlist selection. These are test inputs, not certified capability claims or
  the public client's Automatic quality policy. AVPlay turns on 4K mode
  (`SET_MODE_4K`) before prepare when the stream is larger than 1080p.
- Three 60-second synthetic clips and an opt-in live contract test for direct
  MP4, HLS remux, audio/video conversion delivery, resume and token revocation.

The development preview labels itself **Browser preview · not AVPlay**. Its
HTML video/Hls.js adapter is excluded from the production TV bundle. The TV app
fails clearly when Samsung APIs are unavailable; it never silently substitutes
browser playback. All app assets are bundled locally. Existing web and Cast
entries do not import the prototype; server CORS/CSRF and generated APIs are
unchanged.

## Run the browser preview

From the repository/worktree root, with ffmpeg, Go, Node 24 and pnpm 9 available:

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
this dedicated fixture. The pairing screen points to the actual server's link
page at <http://127.0.0.1:27686/link>. Open that in another tab, sign in as the
fictional viewer from `deploy/fixture/fixture.sh`, and approve the displayed code.
Keep that approver signed in until the prototype reaches the file picker.

Use arrows and Enter, or a mouse. Escape represents the remote's Return button;
Tab also works. Left/right edit the URL while that field has focus. Return leaves
that field first, then navigates back. Playback options explain each experiment.
Sign out through the prototype to revoke its session. Stop both temporary servers
with Ctrl+C afterwards. Never point automated tests at a personal server.

The local proxy is a development convenience. It cannot establish packaged-app
CORS, Fetch Metadata, HTTP/HTTPS or native media-request behavior.

## Build and validation

From `web/`:

```sh
pnpm typecheck
pnpm lint
pnpm test
pnpm build:tizen
pnpm build:tizen-core
pnpm build
pnpm build:receiver
WMM_TIZEN_TEST_SERVER=http://127.0.0.1:27686 pnpm vitest run tizen/live.test.ts
```

`build:tizen` writes the unsigned app (`index.html`, assets, icon and manifest)
to ignored `web/dist-tizen/`, as one classic deferred script with no
`crossorigin` attributes. The package loads from `file://`, where module scripts
can be refused: in desktop Chrome the earlier module build ran nothing from
`file://`, while this one starts. `pnpm dev:tizen` still serves modules. The optional core build writes to ignored
`build/samsung/core/`; it cannot be installed as an app. The live test requires
loopback port 27686 and the dedicated server name before any sign-in or writes.
It revokes its test device and approver sessions afterwards.

From the root:

```sh
make agents-check
python3 -I -m unittest discover -s deploy/samsung -p '*_test.py'
```

Current verification, 2026-11-01: 144 unit tests (34 Samsung), four packaging/fixture guard
tests, the live synthetic-server test, types/lint, all four builds, and
`make agents-check` pass. The tests include mocked session/navigation/lifecycle
failures. Browser checks cover pairing/retry, stored-session
restoration, direct playback, HLS remux (including event seek while paused), HEVC
conversion, progress/resume, modal focus, Return-to-file behavior, sign-out and
error recovery. Layouts are checked at 1920×1080, 1280×720 and phone width.
Native remote input, screensaver, suspend/wake, AVPlay decoding and packaging
acceptance remain unverified on a TV. No signing or store operation was performed.

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
   `build/samsung/watchmymedia-qn90d-prototype.wgt`. Those checks do not establish
   Samsung trust or TV acceptance; actual installation remains required.
4. Connect only to the authorized QN90D, using the synthetic server. Check the
   two things most likely to break first: that the app starts (the classic
   script and `$WEBAPIS/webapis/webapis.js` load), and that pairing gets through.
   The server log's status for `POST /api/auth/pair` shows whether the origin
   check refused it (403); [PLAN-SAMSUNG.md](../../PLAN-SAMSUNG.md) §3 predicts
   that. To see the `Origin` and `Sec-Fetch-*` values, point the app at a small
   local server that prints only those headers. Then record
   firmware/Tizen/AVPlay versions, safe Origin/Fetch Metadata observations, direct
   and HLS playback, audio, seek/resume, Stop/Return, suspend/wake and screensaver
   restoration. Expose the fixture to the LAN only for that authorized test, keep
   discovery disabled, and stop it afterwards. Never record tokens or media URLs.

Keep server security policy unchanged until TV evidence identifies any necessary
S1 fix. Do not broadly trust `Origin: null`. Production browsing, QR pairing,
subtitles, HDR, full Automatic quality, token renewal, certification and additional
model groups remain in [PLAN-SAMSUNG.md](../../PLAN-SAMSUNG.md).

Samsung references: [certificate setup](https://developer.samsung.com/smarttv/develop/getting-started/setting-up-sdk/creating-certificates.html),
[TV testing](https://developer.samsung.com/smarttv/develop/getting-started/using-sdk/tv-device.html),
[remote keys](https://developer.samsung.com/smarttv/develop/guides/user-interaction/remote-control.html),
[screensaver](https://developer.samsung.com/smarttv/develop/guides/fundamentals/setting-screensaver.html),
[AVPlay](https://developer.samsung.com/smarttv/develop/api-references/samsung-product-api-references/avplay-api.html).
