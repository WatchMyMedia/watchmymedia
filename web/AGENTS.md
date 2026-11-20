# web/AGENTS.md

The web app (`src/`) and the Google Cast receiver (`receiver/`). Read the root
[AGENTS.md](../AGENTS.md) first. The web app is embedded in the server binary;
the receiver is deployed on its own to cast.watchmy.media
([PLAN-CAST.md](../PLAN-CAST.md) §4).

## Commands

From `web/`. Node 24 and pnpm 9, as CI uses.

| What | Command |
|---|---|
| Install | `pnpm install --frozen-lockfile` |
| Dev server on :5173 (proxies `/api` to the Go server on :27587) | `pnpm dev`, or `make web-dev` from the root; start the server with `make dev` or a `server-*` launch config |
| Receiver dev server on :5174 | `pnpm dev:receiver` (launch config `receiver`) |
| Lint, types, tests and builds of everything here | `pnpm lint && pnpm typecheck && pnpm test && pnpm build && pnpm build:receiver && pnpm build:tizen` |
| As CI's `web` job does: the web app and the receiver, not the Samsung app | `pnpm lint:web && pnpm typecheck:web && pnpm test:web && pnpm build && pnpm build:receiver` |
| As CI's `samsung` job does: the Samsung app alone, and the check that its build loads on the TV | `pnpm lint:tizen && pnpm test:tizen && pnpm build:tizen && pnpm check:tizen` ([tizen/AGENTS.md](tizen/AGENTS.md)) |
| One test file | `pnpm vitest run src/lib/quality.test.ts` |
| Regenerate the API types | `make gen-api` from the root (never `pnpm gen:api` alone: it reads a copy of the document the server writes) |
| Embed in the server | `make web` from the root |

`pnpm deploy:receiver` deploys the receiver to production. Run it only when
the user asks in this conversation; see Authority in the root file.

## Layout

| Path | What |
|---|---|
| `src/api/` | `client.ts` (the typed `openapi-fetch` client and `unwrap`), `queries.ts` (TanStack Query options per endpoint), per-area helpers; `schema.d.ts` and `openapi.json` are generated |
| `src/router.tsx` | Every route (TanStack Router, lazy page components) |
| `src/pages/` | One component per route; `settings/` and `activity/` hold their sub-pages |
| `src/components/` | Shared pieces, the player's controls and menus, settings sections |
| `src/lib/` | Logic without React, each file with a `*.test.ts` beside it where it has rules: formats, quality, pairing, episode routes, the play rules in `play/` |
| `src/cast/` | The Cast sender and `protocol.ts`, the messages both sides speak |
| `receiver/` | The receiver: CAF and Shaka, its own `tsconfig.json`, `vite.receiver.config.ts`, `wrangler.receiver.jsonc`, CSP in `receiver/public/_headers` |
| `tizen/` | The Samsung TV app (Tizen, Chromium 108, AVPlay); its own [guidance](tizen/AGENTS.md), with its architecture, and [README](tizen/README.md), with `pnpm dev:tizen` (`/?fakes` for its fictional server) and `pnpm build:tizen`; it reuses `src/api/` and `src/lib/`, so a change there runs its checks too; CI's `samsung` job, not `web`, lints, tests and builds it; signing and TV tests are still to do |

## Rules

- **API calls** go through `api` from `src/api/client.ts` with the generated
  path types, and through query options in `src/api/queries.ts` so the cache
  keys stay in one place. Don't write `fetch` calls with hand-typed replies.
  The Samsung TV app uses the same options with its own bearer client
  (`setApiClient`; the web app never calls it), so they import nothing
  browser-only (no router, Tailwind, `EventSource` or `window` at load).
  What each server event refetches is one table both apps dispatch through,
  `src/api/eventInvalidation.ts`: a new event that changes cached data goes there.
- **Logic in `src/lib`**, tested with Vitest; components render. A rule that
  decides something (which quality, which episode, whether a sign-in may
  continue) belongs in a tested function, not inline in JSX.
- **Styling**: Tailwind v4 classes and the `@theme` tokens in
  `src/index.css`, no new hex values. Burgundy (`brand`) is only ever a fill,
  never text or a thin line (1.4:1 on the ground); gold (`accent`) marks
  progress, selection, focus and badges; orange (`warn`) is for warnings. The
  apps and the site use the same colours. Check every page at phone width:
  some header pieces hide below the `sm` breakpoint.
- **Screens follow `design/mockups/web/`.** Update the web canvas first for a
  UI change (root file, How work goes).
- **`public/reminders-sw.js`** is the service worker that shows Live TV
  reminders and new episodes of followed shows (scope `/`). It handles no
  fetches, so nothing of the app is cached or served from it; keep it that
  way. Notifications, the worker and the Web Lock that lets one tab notify
  need a secure context: on plain `http://` LAN addresses reminders come as
  the in-app banner only, and new episodes not at all. New episodes'
  banner needs no permission, only a secure context; a hidden tab shows
  system notifications when they are allowed, and otherwise leaves the
  banner for when it is seen. A chosen
  notification opens only the paths `openablePath` in
  `src/lib/browserNotices.ts` lists. New episodes hold a lock of their own
  (a tab asking twice for one lock waits on itself); their check, cursor and
  words are `src/lib/newEpisodes.ts`, and when the holding tab checks is
  `src/lib/newEpisodeSchedule.ts`, outside React and with the last change in
  storage, so a redraw, a reload or another tab taking the lock still checks
  when the window ends (PLAN-NEW-EPISODES.md §6.1, §7.5). The layout draws
  the banners from one place in every page. A
  show's line is its one episode, the first to the last listed ("S3 E7 to
  E9"), or "From S3 E1" when more arrived than the feed lists, as on
  Android and Apple.
- **YouTube trailers** (`src/pages/Trailer.tsx`, PLAN-TRAILERS.md §6.1) are the
  app's one outside frame: a plain `<iframe>` of `www.youtube-nocookie.com`,
  never YouTube's IFrame API script, and nothing drawn over it. Keep its
  `referrerpolicy="strict-origin-when-cross-origin"`: the server's
  `Referrer-Policy: same-origin` sends no Referer, and YouTube then refuses to
  play (error 153). The player's `postMessage` events are undocumented; the
  plan's As built says what they were when checked.
- **Rows** (PLAN-ROWS.md): Home and each library's Overview draw the
  server's `rows` (`arranged=true`) by `style`, skipping styles they don't
  know and `live`; `src/lib/screenRows.ts`, `src/lib/rowLayout.ts` and `src/lib/mediaLists.ts`
  hold the rules. Reordering is Move up and Move down plus our own pointer
  hook (`src/components/rows/useRowDrag.ts`), not a drag library. Edit rows and
  Add a row live in the address (`?edit`, `&add`) so Make a list
  (`/lists/new`) can come back to them; the unsaved order waits in
  `src/components/rows/rowDrafts.ts` for the visit. A movie's and a show's
  page end with their related rows (PLAN-RELATED.md §7):
  `src/components/rows/RelatedRows.tsx` draws them with `ScreenRows`, never
  with See all, once the details have loaded. Their query keys start with
  `related`, apart from the details', so refetching a page's details doesn't
  work the rows out again.
- **Row cards** (PLAN-HOME.md §6): a card is a `div` of parts that are
  links of their own, drawn from `src/lib/cardLinks.ts` by
  `src/components/CardParts.tsx`: the title opens the title; a Recently
  added show card's "S03" opens the show on that season and its "E07" the
  episode's address, which puts it on top of the show's page
  (PLAN-SHOW-PAGE.md; an older link's `?at=` redirects there, as `?episode=`
  does); the
  picture is a pointer target for the most specific link, with `tabIndex={-1}`
  and `aria-hidden`, so a card has one tab stop per destination. Every link
  carries an `aria-label` naming the whole target. No quality badge on any
  card; quality is a fact on the hero and the detail pages. The apps keep
  PLAN-EPISODE-PAGE.md §10 (`addedEpisode` stays for them).
- **Settings** (PLAN-ADMIN-SETTINGS.md): each tab opens on a hub, and each
  task with more than one step is a flow at its own address under the tab.
  `src/lib/settingsRoutes.ts` lists every address and whether it is a hub, an
  item page or a flow (flows take the whole window, without the app bar or the
  sidebar); a tab's component reads it with `useSettingsPage()` and keeps its
  screens in `src/pages/settings/<tab>/`. Build them from
  `src/components/settings/` (`HubPage`, `FlowFrame`, `ConfirmDialog`,
  `SaveBar`, `LeaveCheck`, …) and the step rules in `src/lib/flowSteps.ts`.
  A switch or a choice saves at once and says so in its row; typed fields are
  drafts with the save bar. A button whose request asks for the password away
  from home is `guarded`, so it carries a lock there, and its call goes
  through `withConfirm`. Removes use `ConfirmDialog`, never `window.confirm`.
- **Wording by install** (PLAN-LINUX.md §4): how the server was installed
  changes what Settings says. `UpdateStatus.install` (admins) picks the
  update steps (`src/lib/updates.ts`) and the restore steps
  (`src/lib/backupSettings.ts`); `ServerInfo.install` and `.host` pick the
  folder check's permissions advice and the transcoder's software note
  (`src/lib/packageInstall.ts`, `src/lib/transcoder.ts`). A new kind, or new
  advice that names Docker, a container or a service, goes through those, not
  inline in a page. Commands people copy are drawn with `CommandLine`.
- **Server features by level**: the web app is served by the same server it
  talks to, so it can rely on the current API. The receiver can't: it is
  deployed separately and loads against any server version, so it reads the
  server's `apiLevel` and keeps working with older servers.

## The Cast receiver

- `src/cast/protocol.ts` is mirrored by `android/core/cast` (`CastProtocol`).
  Change both together, additively; each side ignores what it doesn't know.
- It runs on TVs whose Media Source Extensions differ from desktop Chrome.
  Codec checks use `MediaSource.isTypeSupported` in the form the stream really
  has (muxed video and audio, or audio alone with separate audio). Test with
  HEVC and E-AC-3, not only the fixture's H.264.
- It can be driven without a TV: serve it on 127.0.0.1:5174, run a scratch
  server with `WATCHMYMEDIA_CAST_RECEIVER_ORIGINS=http://127.0.0.1:5174`, get a
  cast token from a signed-in web tab (`POST /api/auth/cast-token`), and call
  the receiver's `PlayerManager.load` with that `customData`. Under Shaka a
  rebuffer fires only CAF's `BUFFERING` event; desktop CAF doesn't paint video
  into screenshots, so read the player state instead.
- For a Live TV cast, `go run ./cmd/livetvfixture -receiver-origin
  http://127.0.0.1:5174` (from `server/`) is a fake tuner and guide with
  casting on; the LOAD's `customData` carries `live: { channelId, quality }`
  instead of `fileId`. The receiver's own pictures load with
  `crossorigin="anonymous"`: plain `<img>` requests to the server's art routes
  were refused by Chromium's opaque-response blocking.
- The sender's remote views can be checked without a TV in `pnpm dev`:
  `window.__wmmCast.set(...)` and `.get()` drive the sender store (status,
  `receiver` state messages), and a stand-in `window.cast` session lets
  `castChannel`/`castFile` send a real LOAD to record. Import the sender in
  the page from the URL the app loaded it at (Vite adds `?t=` after an
  edit), or you get a second store.
- CAF keeps one Shaka player across loads, so Shaka settings carry over:
  the playback-info handler sets `abr` and `restrictions` on every load (a
  family's caps left behind made the next single output fail with Shaka
  4032). CAF zeroes `getCurrentTimeSec()` before `MEDIA_FINISHED(ERROR)`; use
  the play's remembered position. A `SEEK` interceptor must return the
  request rather than call `PlayerManager.seek`, which re-enters it and hangs
  the receiver. `getStats().height` names the variant being fetched, ahead of
  the picture; report the video element's `videoHeight`.
- A transcode's encoder restart leaves a ~30 ms hole in the buffer. The
  browser fires `waiting` there with plenty buffered past it: measure and
  count stalls with `bufferedAhead` and `waitingAtJoint` (`src/lib/play/rules.ts`),
  or Automatic sees stalls and a short buffer that block its recovery.
- `deploy/fixture/adaptive_caf.py` is a visible local page for A2 adaptive
  checks on the development receiver ([deploy/fixture/streaming.md](../deploy/fixture/streaming.md)).
- None of that proves a real Chromecast or TV behaves the same. Say so when a
  change wasn't checked on one, and never cast to a TV in the house without the
  user's go-ahead.
