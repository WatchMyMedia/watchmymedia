# Samsung TV app

Read the root and `web/AGENTS.md` first. This is the Samsung TV client:
a packaged Tizen web app for Chromium 108 that plays with
Samsung's AVPlay, built to match the Android TV app's features, Live TV
included. It is its own entry, not the browser app or the Cast receiver.
[README.md](README.md) is for people: running it, building it, and the signing
and QN90D steps still to do.

## Rules

- **Screens follow the maintainers' designs.** Where a Samsung design
  isn't drawn yet, follow the Android TV one it adapts. Take colours,
  sizes and shapes from the kit's tokens
  (`ui/tokens.css`), never new hex values in a screen.
- **The API** goes through the web's own client and query options
  (`../src/api/client.ts`, `../src/api/queries.ts`), which the session points
  at its server (Data, below). No hand-typed `fetch` replies; a query that is
  missing is added to `../src/api/` for both apps.
- **No server text on screen.** `ApiError.message` carries the server's
  `detail`, which can name paths or tokens. Words come from
  `ui/problemWords.ts` (by kind and status) or the screen's own copy.
- **Never log or show** session tokens, pairing secrets, media tokens, full
  media URLs or certificates. Only a media token ever goes in an address, and
  only for the server's media routes on the signed-in server (`session/media.ts`).
- **Chromium 108 is the floor**: no `crypto.randomUUID` (use `getRandomValues`,
  as `session/device.ts` does, or `player/clock.ts`'s `randomId`), `toSorted`,
  `Promise.withResolvers`, `AbortSignal.any` or other later APIs; no CSS
  `color-mix`, `oklch`, nesting or `:has`. Tailwind v4's CSS is beyond it, so
  this app is plain CSS. `pnpm check:tizen` catches some of this, not all.
- **Logic outside React**, tested with Vitest: rules that decide something go
  in a `.ts` file with a test beside it, as the web's `../src/lib/` does.
  Reuse `../src/lib/` (clock, images, screenRows, browseOptions, liveTV, the
  play rules) rather than copying; split a browser-bound module's pure part
  out when the TV needs it.
- **Server security stays as it is** for the preview: the Vite proxy is a
  development convenience, not evidence of packaged TV networking. API level
  32 is what lets a `file://` page pair and send bearer requests; this app
  needs it (`REQUIRED_API_LEVEL` in `session/session.ts`) and keeps no
  branches for older servers. A feature from a later level turns on by the
  server's `apiLevel` and asks an older server nothing: watchlists, from 33
  (`useWatchlistsOn()` in `screens/watchlists/`), so the screens that use
  them call no watchlist query below it.
- **The store identity** in `public/config.xml` is permanent: the widget id,
  package id `cPFf0iV3Li` and application id `cPFf0iV3Li.WatchMyMedia` never
  change, and every update is signed with the same Samsung author certificate.
  Raise `version` there and `TV_APP_VERSION` together for each store update.

The player engine is `player/` ([README](player/README.md)): screens import
`player/index.ts` and nothing else from it. It reuses the web's rules in
`../src/lib/`, takes its API client, media URLs, clock and Player as a
context, and imports nothing from the shell's folders.

## Architecture

| Folder | What |
|---|---|
| `app/` | The shell: `main.tsx` makes the session, the input and the one Player, then `app/App.tsx` shows the start screens or the signed-in `app/Shell.tsx`. Routes, the screens stack, focus, keys and the platform calls |
| `session/` | Everything about servers and accounts: typed addresses, remembered servers, pairing and password sign-in, the signed-in session (client, cache, media token, events), switching between servers, and asking a server other than the current one (`peers.ts`) |
| `ui/` | The kit: components, `tokens.css` and `kit.css`, and their pure helpers (`problemWords`, `qr`, `railBadge`, `tones`) |
| `screens/` | One folder per feature: `start/` (server, sign-in, problems), `servers/` (the Servers panel, Settings' Servers pages, the note after a switch), `browse/` (Home, libraries, See all, lists), `details/` (movie and show pages, YouTube trailers), `player/`, `live/`, `search/`, `requests/`, `settings/` and `watchlists/` (the title pages' button, Save to watchlist, a card's actions, the Watchlists screen and a watchlist's page) |
| `player/` | The player engine (above) |
| `fakes/` | The fictional server for the browser preview and tests |

### Routes and screens

`app/routes.ts` is the one place routes live: the `Route` union (a name and
its parameters), the `PlayRequest` the player route takes, and `ROUTES`, which
maps each name to its screen component, its rail section (for the sections)
and `chrome: 'full'` for screens without the rail. A feature adds a variant to
`Route`, an entry to `ROUTES` and its screen in its own `screens/` folder.
Screens are `({ route }: ScreenProps<'movie'>) => …` and take everything else
from hooks.

The stack (`app/nav.ts`): Home is at the bottom, sections sit on Home (the
rail makes it [Home, section], going back to a section still open), pages
stack on what opened them, and the section in gold is the nearest below.
Only the top screen is drawn; coming back draws it again from the cache and
puts focus back where it was. From a screen, `useNav()` gives `push`,
`replace`, `openSection`, `back` and `play(request)` (the player route).

Return goes, in order: an open modal (Dialog, SidePanel) closes; an overlay
(a Banner's dismiss, the open rail) takes it; the screen's own `useBack`
handler (newest first); then the stack pops; at Home, "Leave WatchMyMedia?"
(Stay focused). The start screens cancel what is under way, then ask before
leaving.

### Focus and keys

Focus is the remote's, kept by `app/focus.ts` from markup:

- `data-focus="<id>"` on everything focusable (a `button`, or a field): an
  id stable across redraws, unique on the screen. Cards in a row or grid use
  `<group>/<item key>`; the kit does this.
- `data-scope` splits the page: `rail`, `content` and `modal` (focus stays
  inside the newest modal). Only ◀ with nothing left of it in the content
  opens the rail; ▶ from the rail goes back where focus was.
- `data-group="<id>"` remembers its last focused child: focus coming in from
  outside goes back to it (rows remember their card). `useGroupMemory(id)`
  reads it, for drawing around it on the way back.
- `data-row` makes ◀ ▶ go card by card in order; a quick run of ◀ stops at
  the first card (the row gives, the rail pulses), a single ◀ there opens the
  rail. `data-clip` on a row's viewport keeps cards scrolled out of view from
  being targets.
- `data-autofocus` marks where a screen starts without a memory.

Each stack entry keeps a `FocusMemory` (the last item, and each group's
child); the shell restores it when the screen is drawn again.

Keys are named actions from one table (`app/keys.ts`): `up`, `down`,
`left`, `right`, `ok`, `hold`, `back`, `playPause`, `play`, `pause`,
`stop`, `rewind`, `fastForward`, `previous`, `next`, `channelUp`,
`channelDown`, `previousChannel`, `digit` (with `digit`), `minus`, `info`,
`guide`. The media, channel and number keys are registered with Tizen at
start. The preview maps Escape and Backspace to Return, PageUp and PageDown
to CH ▲▼, and the digit keys. OK counts on keyup; Hold OK fires once after
500 ms (Tizen repeats keydown) and never also clicks.

A screen takes keys with `useKeys(handler)` (return true to keep the key)
or `useBack(handler)`; inside a Dialog or SidePanel they are the modal's.
What nobody takes moves focus (arrows), clicks the focused element (OK) or
sends it Hold OK, which kit parts take as `onHold` (`useHold(ref, fn)` for
your own). A field typed with the TV's keyboard is `ui/TextField.tsx`: OK
opens the keyboard, and the field keeps its own keys while typing.

### Data

On sign-in, `session/active.ts` makes an `ActiveSession` and attaches it:
the web's `api` becomes its bearer client (`setApiClient`, `credentials:
'omit'`), the auth scope moves on, and it brings a fresh `QueryClient`, so no
cache crosses servers or accounts. Screens then use TanStack Query with the
web's options: `useQuery(homeQuery)`, `useQuery(movieQuery(id))`, mutations
from `../src/api/*.ts`. `useSession()` gives the session (`server`,
`account`, `isAdmin`, `api`, `client`, `queryClient`, `onServerEvent`).

Server events (`GET /api/events`) arrive through `session/events.ts`, a
fetch reader with the bearer header that reconnects with backoff, and
invalidate queries through the table the web shares
(`../src/api/eventInvalidation.ts`); a new event that changes cached data is
added there, for both apps. `session.onServerEvent(name, fn)` is for
anything else (a reminder banner). A 401 anywhere ends the session: the app
shows "This TV was signed out" and forgets the token.

Sign-in, servers and the start problems are `session/session.ts`
(`AppSession`), drawn by `screens/start/`. `useApp()` gives its commands to
the signed-in app too: `switchTo(id)`, `addServer()`, `remove(id)` and
`signOut()`; `useAppState()` redraws a screen as they change things.

### Several servers

This TV keeps up to 12 servers by server ID (`session/servers.ts`), each
with its own sign-in, and the app is on one of them. Nothing ties them
together but this list, which stays on the TV.

- **One session takes over from another.** `switchTo` asks the other
  server which one it is (5 s at most), checks its kept sign-in, readies a
  new `ActiveSession` (`ready()`), and only then attaches it and detaches
  the old one. Until that moment nothing has changed, so a server that
  doesn't answer, or isn't the one remembered at its address, leaves the
  app where it was: `switching` in the signed-in state says why, and
  Servers draws it with Try again. Keep that order in anything new: attach
  the new session before the old one is detached, never the reverse
  (`detach()` leaves the queries' client alone once another session has it).
- **Flows that start from the signed-in app keep its session alive**
  underneath: Add a server, and the sign-in of a server signed out here.
  Return walks back to the app (`cancel()`), on the screen it was on: the
  shell keeps each session's stack. A server being added is remembered only
  once it is signed in to, and the address of a server this TV already has
  is refused there (`AlreadyAddedError`): it is switched to from Servers.
- **`arrival`** in the signed-in state is how the app came to a session:
  `switched` shows "Now on …" for 4 s (a note that takes no focus), `added`
  the last step of Add a server, and `open` builds the stack a search
  result asked for (Home, Search with the same words, the title). A session
  says it once.
- **A sign-in only ever goes to its own server.** Search asks every other
  signed-in server through a `PeerServer` (`session/peers.ts`): a bearer
  client of its own for that server's address, never the session's `api`,
  and only after the server has said, without a token, that it is the one
  remembered. Its pictures use its own media token. Anything new that
  reaches another server goes through a `PeerServer` too.
- **Search across servers** is `screens/search/crossSearch.ts`:
  `CrossSearch` asks the others (4 s each, 30 s before one that failed is
  asked again, a keystroke cancels the calls out) and `mergeResults` orders
  what is drawn: the current server's titles, then each other server's in
  Servers' order, added only when every server before it has answered or
  failed, so a card on screen never moves. With other servers to list, the
  current one gets the same 4 s (its late answer is dropped). `searchLines`
  gives the lines under the results: a server that didn't answer has Try
  again; one this TV is signed out of, or one too old for this app, says so
  and isn't asked (`serversLeftOut`); when none answers it is the plain
  "Search didn't work". Only library titles cross servers; suggestions,
  Live TV and requests stay the current server's.
- **Words and rows** for all of it are `screens/servers/serverRows.ts` and
  `screens/search/searchResults.ts`, tested beside them. A server is called
  by its name, never "the server". The Android and Apple apps say the same
  things in the same words: change one, change all three.
- **Focus.** The rail's account button opens Servers and gets focus back
  when Return closes it (`Modal`'s `returnFocus`). The focus geometry
  prefers an aligned card to a wide line nearer by, so a full-width line
  among cards (Search's Try again) needs its own ▲ ▼ rule; don't rely on
  geometry to reach one.

### The kit

`ui/` components, all keyed by `id` (their `data-focus`):

- `Rail` (the shell draws it; its account button at the foot opens Servers),
  `Button` (`primary`, `ghost`, `danger`; `icon`, `busy`, `onHold`),
  `KeyHints`, `Brand`, `Icon`, `PlayGlyph`.
- `Card` in `poster`, `wide`, `episode`, `continue` and `live`, with
  `progress`, `badges`, `image`, `onPress`, `onHold`, and `server` for a
  title on another server (a line under the title).
- `Row` (title, counter, `items`, `itemKey`, `variant`, `renderItem(item,
  focusId)`): moved with a transform, the card before the focused one in
  view, only the cards near the view drawn. `Grid`: rows of 8 posters or 5
  wide cards, only the rows near the focus drawn, `onLoadMore` near the end.
  `VScroll`: a vertical list moved with a transform (`align="start"` for one
  row a screen). `Chips`.
- `Dialog` (centred, focus on the `safe` action, which Return also takes),
  `SidePanel` with `Option` rows, `Banner` (takes no focus: Return dismisses,
  Hold OK acts), `Skeleton`, `SkeletonText`, `SkeletonRow`, `ProblemScreen`,
  `TextField`, `QrCode`.
- `problemWords(error)` and `signInWords(error)` for failures.

Sizes are rem at 16px for a 1920px-wide screen (`1rem = 100vw / 120`), so a
board's pixels divide by 16. Keep pictures cheap: `Card` decodes
asynchronously and lazily at fixed sizes; draw only what is near the view.

### Images and media

`useMediaUrls()` gives `imageUrl(tmdbPath, size)` (sizes from
`../src/lib/images.ts`: w342 for posters, w780 for wide cards) and
`mediaUrl(path)` for the server's own media paths (Live TV art, trickplay
sheets). Both add the session's media token, kept fresh by
`session/media.ts`, and only for the media routes of the signed-in server;
an address is reused until shortly before its token runs out, so a new token
doesn't fetch every picture again. The player engine signs its own streams
with the decision's token through `session.playerMediaUrl`. A picture from
another server (a search result's) comes from that server's `PeerServer`,
whose `media` holds its own media token.

### The player

`usePlayer()` is the app's one `Player` (AVPlay on the TV, the browser's
video only in the preview, none in a desktop browser). The player screens
make the engine's `FilePlayback` and `LivePlayback` for the session (one
each, released when the session ends) with `session.api`,
`session.playerMediaUrl` and `session.client`, and draw their snapshots;
`app/platform.ts`'s `holdScreenSaver()` keeps the screensaver away while
something plays. The player route takes a `PlayRequest`; `nav.play(...)`
opens it.

### Fakes

`pnpm dev:tizen`, then open `/?fakes`: `fakes/index.ts` puts a fictional
server (`lakeside.local`) in front of `fetch`, and refuses every other
address, so the preview reaches nothing on the network. It pairs by itself
after 8 s, signs in Sam with the password `watching`, and serves every
screen. `?fakes=away` adds a remembered server away from home over http;
`?fakes=servers` is a TV with four servers (`fakes/others.ts`): Lakeside,
signed in and current; Cabin, signed in, with a Home and titles of its own;
Den, signed in, which never answers; and Attic, signed out here. `old`,
`setup` and `expire` give the start problems; the README lists the other
modes. The first registration of a route answers, so the order in
`createFakeServer` matters where two areas serve the same route. The TV build leaves the fakes out (`main.tsx` imports
them behind `import.meta.env.DEV`). A feature adds its routes in its own
`fakes/<area>.ts` (`server.route('GET', '/api/movies/:id', handler)`) with
its data, and registers it in `createFakeServer`. Every title and name in
them is made up, and there are no pictures.

## Commands

From `web/`: `pnpm dev:tizen` previews the app on port 5186, proxying only API
requests to the isolated fixture on port 27686 (open `/?fakes` instead for the
fictional server). `pnpm build:tizen` builds unsigned app contents for Chromium
108, as one classic deferred script without `crossorigin`: the package loads
from `file://`, where module scripts can be refused. Keep it that way, and keep
code free of `import.meta` beyond `import.meta.env`, which the build replaces.
`pnpm build:tizen-core` bundles the core separately. `pnpm typecheck`,
`pnpm lint` and `pnpm test` include this entry. Run both existing web and
receiver builds when shared code changes.

CI checks this entry in its own `samsung` job, and the `web` job leaves it out
(`pnpm lint:web`, `typecheck:web`, `test:web`). The job runs `pnpm lint:tizen`,
`pnpm test:tizen`, `pnpm build:tizen` (types first) and `pnpm check:tizen`, and
keeps the unsigned build as the `samsung-tv-unsigned` artifact for a week. It
runs for this folder, `../vite.tizen.config.ts`, `../vite.tizen-core.config.ts`,
`deploy/samsung/`, the `../src/lib/` and `../src/api/` code this entry may
import, the web's dependencies and configs, and the packed icon (`samsung` in
`deploy/ci/changes.py`). A new file outside this folder that only this entry
reads goes in `SAMSUNG` there, or the web job starts for it and this one doesn't.

`pnpm check:tizen` (`deploy/samsung/bundle.py`) reads `../dist-tizen/`: one
classic script and one stylesheet, `config.xml` and `icon.png`, no module script
or `crossorigin`, and no JS or CSS newer than Chrome 108 that the build leaves
as written (`toSorted` and the other copying array methods, `Object.groupBy`,
`Promise.withResolvers`, `Array.fromAsync`, the new `Set` methods,
`color-mix()`, `oklch()`, `light-dark()`, nested rules). The build lowers
syntax, never the browser's methods, so a library update can break this too.
Add an API to its lists when one newer than Chrome 108 turns up.

Signing and real TV testing are separate steps the user takes (README); don't
attempt either as part of browser checks.
