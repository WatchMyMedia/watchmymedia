# TV app

Read the root and `web/AGENTS.md` first. This is the TV client for makes of
TV that run packaged web apps: one app, built once for each make. Today
that is Samsung: a packaged Tizen web app for Chromium 108 that plays with
Samsung's AVPlay, built to match the Android TV app's features, Live TV
included. It is its own entry, not the browser app or the Cast receiver.
[README.md](README.md) is for people: running it, building it, and the signing
and QN90D steps still to do.

## Makes of TV

What one make gives the app is its platform: `platform/<make>/index.ts`
exports `platform`, a `TvPlatform` (`platform/platform.ts`): its ids and
words (the TV's name, the back key's name in hints), the names it keeps in
the TV's storage, its key codes, its own APIs (start, leaving, the
screensaver, the model), its player and its device profiles. Shared code
imports `platform` from `platform/current.ts` and never from a make's
folder; each build swaps that module for its make's (`tvPlatform` in
`../vite.tv.config.ts`), so a bundle carries no other make's code. Type
checks and tests see Samsung's.

- **A difference between TVs goes into the platform object**, not into an
  `if` in a screen. A new word, key or call that differs is a new field on
  `TvPlatform`, filled in by every make.
- **Samsung's ids and storage names never change**: `id` `tizen`, the store
  identity in `platform/samsung/public/config.xml`, and the storage names in
  `platform/samsung/index.ts`, which hold every Samsung TV's servers and
  sign-ins (`platform/samsung/samsung.test.ts` pins them).
- The player engine (`player/`) takes the make's player and profiles from
  the shell, through its context; it imports no platform.

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
  them call no watchlist query below it; and level 34's (`session/features.ts`,
  `useLevel34()`): a cast member's titles (the `person` route), Remove from
  Continue watching (a card's Hold OK), the libraries' counts, combined
  filters and Rated (`gridQuery` sends one filter and no rating below it),
  and recordings' New, watched and time left. These use the web's own
  calls (`personQuery` and `itemsQuery` in `../src/api/queries.ts`,
  `hideFromContinueWatching` in `../src/api/watch.ts`); another server's grid
  pages go through `fetchItems` with that server's client.
- **Samsung's store identity** in `platform/samsung/public/config.xml` is permanent: the widget id,
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
| `app/` | The shell: `main.tsx` starts the platform and makes the session, the input and the one Player, then `app/App.tsx` shows the start screens or the signed-in `app/Shell.tsx`. Routes, the screens stack, focus and keys |
| `platform/` | Each make's platform (above): `samsung/` has Tizen's calls (`tizen.ts`), its key codes, the AVPlay adapter, its device profile and `platform/samsung/public/config.xml` |
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
- `aria-disabled="true"` dims an item and keeps it focusable, as Samsung
  asks, so a press can say why (`Button`'s `why` shows a note that takes no
  focus). `data-skip` is for the few things the arrows pass over (an A–Z
  letter with no titles); `disabled` takes an item out entirely.
- While a screen has nothing focusable yet (it is loading), focus parks on
  the rail's current section with the rail closed (`data-parked`), and moves
  to the screen once it draws something; ▶ goes to the screen, any other
  arrow opens the rail, Return goes on as if the rail weren't there.
- The input marks the focused element `tv-pressed` while OK is held: the
  pressed state.

Each stack entry keeps a `FocusMemory` (the last item, and each group's
child); the shell restores it when the screen is drawn again.

Keys are named actions from one table (`app/keys.ts`): `up`, `down`,
`left`, `right`, `ok`, `hold`, `back`, `playPause`, `play`, `pause`,
`stop`, `rewind`, `fastForward`, `previous`, `next`, `channelUp`,
`channelDown`, `previousChannel`, `digit` (with `digit`), `minus`, `info`,
`guide`. The codes the remote sends are the platform's (`keyCodes`), and
its `start()` asks the TV for the keys it must (Tizen: the media, channel
and number keys). Hints name the back key with `BACK`, never a word of
their own. The preview maps Escape and Backspace to Return, PageUp and PageDown
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
- **A sign-in only ever goes to its own server.** Every call to another
  signed-in server goes through its `PeerServer` (`session/peers.ts`): a
  bearer client of its own for that server's address, never the session's
  `api`, whose every request first waits for the server to say, without a
  token, that it is the one remembered (`bearerClient`'s `beforeSignIn`).
  Its pictures use its own media token. The session keeps one `PeerServer`
  a server (`session/peerRegistry.ts`, `usePeers()`), so each is asked once
  however many screens use it. Anything new that reaches another server
  goes through a `PeerServer` too.
- **One card across servers** (API level 34 on the current server; a
  server below it takes no part): a title several servers have is one
  card, matched by TMDB id (an episode by its show's, season and number),
  and the server is chosen when it plays. Search merges its rows
  (`mergeResults`); Home's Continue watching and the rows each server
  builds the same way come from every server, one card a title, rows on
  screen never moving (`screens/browse/oneCardHome.ts`); Movies and TV
  Shows merge each server's grid a page at a time
  (`screens/browse/mergedGrid.ts`, `useMergedGrid.ts`); a title's page asks
  the other servers for its copies (`screens/details/useCopies.ts`) and the
  button after Play opens them grouped by server (`copies.ts`,
  `showCopies.ts`, `CopiesPanel.tsx`), the choice kept per title on the TV
  (`session/chosenCopies.ts`), Resume from the latest place on any server.
  A title only another server has opens in place: routes and plays carry
  `server`, the page reads through `useSource()` and the `SourceContext`
  (`useMediaUrls`, `routeOn`, `playOn`) and `session/sourceQueries.ts`, and
  a play uses that server's client so its progress stays there. Every
  server gets 4 s and one that doesn't answer is left out, said only where
  the boards draw it. Requests, reports, follows, watchlists and the
  season and show marks stay the current server's.
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
  Live TV and requests stay the current server's. A result from another
  server opens in place, without switching the app.
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

- `Rail` (the shell draws it on the top-level screens: icons inside the safe
  area, the current section marked by a gold bar; with focus a drawer with
  labels and the account row, which opens Servers), `Button` (76px pills:
  `primary` burgundy, `ghost` grey, `glass` grey over a picture, `danger`;
  `icon`, `busy`, `onHold`, `disabled` with `why`), `KeyHints`, `Brand` (the
  reel), `Icon`, `PlayGlyph`, `Clock` (top right).
- `Card` in `poster`, `wide`, `episode`, `continue` and `live`, with
  `progress`, `badges` (New, in words), `done` (the white watched tick),
  `selected` (a gold bar above it), `image`, `onPress`, `onHold`, and
  `server` for a title on another server (a line under the title). The
  `.tv-person` styles draw a cast member's round card.
- `Row` (title, counter, `items`, `itemKey`, `variant`, `renderItem(item,
  focusId)`, `dimPast`): moved with a transform, the card before the focused
  one in view, only the cards near the view drawn; four 16:9 cards or six
  posters fit beside the rail, the next peeking. `Grid`: rows of 6 posters
  or 4 wide cards, only the rows near the focus drawn, `onLoadMore` near the
  end. `VScroll`: a vertical list moved with a transform (`align="start"`
  for one row a screen). `Chips`: chosen is a gold underline.
- `Dialog` (centred, 960px, `narrow` 820; focus on the `safe` action, which
  Return also takes), `SidePanel` (760px from the right, `wide` 960, `side`
  `left` for Servers; a `sub` line and a `foot`) with `Option` rows (a radio
  for a choice, `next` for a chevron, `end` for words at the end) and
  `PanelLine`, `Banner` (takes no focus: Return dismisses, Hold OK acts),
  `Skeleton`, `SkeletonText`, `SkeletonRow`, `ProblemScreen` (a title, why,
  the first action focused, a `foot` line), `TextField`, `QrCode`.
- `showNote(text)` (`ui/notes.ts`): a short note top right that takes no
  focus, drawn once by the app (`Notes`).
- `leftWords`, `endsAtWords` (`ui/timeWords.ts`): "29 min left", "Ends at
  8:41 PM", the same on Home, the title pages and the player.
- `problemWords(error)` and `signInWords(error)` for failures.

The rules every screen follows, in `ui/tokens.css`: text and controls stay
inside the safe area (96px at the sides, 54px top and bottom; pictures run
to the edges); six type sizes (`--t-display` 68 to `--t-small` 22) and
nothing under 22px; one focus treatment, a 5px gold ring 5px off the item
with growth (`--grow-card` 1.08, `--grow-button` 1.05, `--grow-row` 1.025;
guide cells don't grow) and a shadow; selected is never a ring. Burgundy
fills the main button and the avatar, gold is focus, progress, selection
and badges, orange a warning; the Recording and Live marks stay red. A title's page
and the players use the full width without the rail (`chrome: 'full'`).

What every app does the same way: a movie's first screen says its picture
and sound in one line in words (`titleQuality`, from the web's `qualityLine`),
never as tags, and no card or row panel names 4K or HDR; Delete is last in
More, in plain text, and only its dialog's own button is marked; the guide
opens on Favourites when there are some, otherwise All channels
(`openingFilter` in `screens/live/guideModel.ts`).

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

`usePlayer()` is the app's one `Player` (the platform's on the TV, AVPlay on
a Samsung TV; the browser's video only in the preview; none in a desktop
browser). The player screens
make the engine's `FilePlayback` and `LivePlayback` for the session (one
each, released when the session ends) with `session.api`,
`session.playerMediaUrl`, `session.client` and the platform's `profiles`,
and draw their snapshots; `platform.holdScreenSaver()` keeps the
screensaver away while something plays. The player route takes a `PlayRequest`; `nav.play(...)`
opens it.

### Fakes

`pnpm dev:tizen`, then open `/?fakes`: `fakes/index.ts` puts a fictional
server (`lakeside.local`) in front of `fetch`, and refuses every other
address, so the preview reaches nothing on the network. It pairs by itself
after 8 s, signs in Sam with the password `watching`, and serves every
screen. `?fakes=away` adds a remembered server away from home over http;
`?fakes=servers` is a TV with four servers (`fakes/others.ts`): Lakeside,
signed in and current; Cabin, signed in, with a Home and titles of its own;
Den, signed in, which never answers; and Attic, signed out here.
`?fakes=onecard` (`fakes/onecard.ts`) is one card across servers: Lakeside,
Cabin away from home sharing some of Lakeside's titles (Dead Reckoning Bay
further along there, Harbor Lights with two episodes only it has), and Den,
which takes each request and never answers. `old`,
`setup` and `expire` give the start problems; the README lists the other
modes. The first registration of a route answers, so the order in
`createFakeServer` matters where two areas serve the same route. The TV build leaves the fakes out (`main.tsx` imports
them behind `import.meta.env.DEV`). A feature adds its routes in its own
`fakes/<area>.ts` (`server.route('GET', '/api/movies/:id', handler)`) with
its data, and registers it in `createFakeServer`. Every title and name in
them is made up, and there are no pictures.

## Commands

From `web/`: `pnpm dev:tizen` previews the Samsung app on port 5186, proxying only API
requests to the isolated fixture on port 27686 (open `/?fakes` instead for the
fictional server). `pnpm build:tizen` builds unsigned app contents for Chromium
108, as one classic deferred script without `crossorigin`: the package loads
from `file://`, where module scripts can be refused. Keep it that way, and keep
code free of `import.meta` beyond `import.meta.env`, which the build replaces.
Two real servers in the preview (one card across servers) need a Vite proxy
each: the server answers CORS only for the packaged app's own origin, so a
`localhost` page can't reach a second server directly. A scratch config that
imports `../vite.tv.config.ts` (with `mode: 'samsung'`) and overrides `server.port` and
`server.proxy` puts one in front of each throwaway server, and the second is
added by its proxy's address. This is how the preview reaches them, not how a
TV does. `pnpm build:tizen-core` bundles the core separately. `pnpm typecheck`,
`pnpm lint` and `pnpm test` include this entry. Run both existing web and
receiver builds when shared code changes.

CI checks this entry in its own `samsung` job, and the `web` job leaves it out
(`pnpm lint:web`, `typecheck:web`, `test:web`). The job runs `pnpm lint:tv`,
`pnpm test:tv`, `pnpm build:tizen` (types first) and `pnpm check:tizen`, and
keeps the unsigned build as the `samsung-tv-unsigned` artifact for a week. It
runs for this folder, `../vite.tv.config.ts`, `../vite.tizen-core.config.ts`,
`deploy/samsung/`, the `../src/lib/` and `../src/api/` code this entry may
import, the web's dependencies and configs, and the packed icon (`samsung` in
`deploy/ci/changes.py`). A new file outside this folder that only this entry
reads goes in `TV` there, or the web job starts for it and this one doesn't.

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
