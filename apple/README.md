# WatchMyMedia for iPhone, iPad and Apple TV

The iOS app, the Apple TV app, and the Swift package both build on. The
screens follow the maintainers' designs.

## Build and run

Needs Xcode (26 or newer, with an iOS simulator runtime) and XcodeGen 2.45.4
or newer (`brew install xcodegen`). From the repository root:

```bash
make apple-test       # the Kit's unit tests, on this Mac (seconds, no simulator)
make apple-build      # generate the project and build the app for the simulator
make apple-test-sim   # build and run every test on the iPhone 17 Pro simulator, or the newest iPhone Pro (SIM=… for another)
make apple-build-for-testing     # what CI runs: build the app and its tests for that simulator, booting nothing
make apple-test-without-building # then run the tests it built; CI builds Release between the two
make apple-api        # after `make gen-api`: regenerate Kit/Sources/WMMAPI/Generated; commit it
make apple-e2e        # the app end to end against a server (default: the fixture server at localhost:27588)
make apple-tv-build   # build the Apple TV app for the simulator
make apple-tv-test-sim # build it and run every test on the Apple TV 4K simulator (TV_SIM=… for another)
make apple-tv-test-sim-ci # what CI runs, in one go: one build, then the Kit's tests and the UI tests apart; CI's two jobs pass TV_UI_SHARD=1/2 and 2/2 (TV_KIT_TESTS=no) to share the UI test classes
make apple-tv-build-for-testing     # its first half, booting nothing
make apple-tv-test-without-building # its second half; CI builds Release between the two
make apple-build-release    # the iPhone app in Release for devices, unsigned, then check-release-binary.sh (CI runs it)
make apple-tv-build-release # the same for the Apple TV app
make apple-perf       # launch and scrolling measures on SIM and TV_SIM, five runs each (not in CI)
```

The Apple TV app needs the tvOS simulator runtime too
(`xcodebuild -downloadPlatform tvOS`; CI downloads it the first time).

CI runs the Kit's tests on the simulators, inside the `apple` and `apple-tv`
jobs, not on the Mac (`make apple-test` is the quick local check). The
`apple` job runs for `App/` and `UITests/`, the `apple-tv` job for `TV/` and
`TVUITests/`, and both for the Kit, `project.yml`, `Tools/`, `scripts/` and
the API. Their results are in the `apple-test-results` and
`apple-tv-test-results` artifacts, without code coverage.

`make apple-e2e` installs the app fresh on the simulator and runs
`UITests/EndToEndTests.swift`: it types the server's address, signs in,
browses Home and Movies, plays a film and leaves the player, and finds the
phone under Signed-in devices, with a screenshot per step in
`build/E2E.xcresult` (`xcrun xcresulttool export attachments`). Point it at
another server with `E2E_SERVER=… E2E_USER=… E2E_PASSWORD=…`. Simulator builds
are signed to run locally (ad hoc): unsigned, the Keychain refuses the app and
nothing it signs in to survives a relaunch.

`UITests/ServersEndToEndTests.swift` does the same for several servers,
against two throwaway servers that each have their own id, name and library
(a copy of one database counts as one server) and a title the same words
find. It signs in to the first, adds the second from Servers, switches back
without a password, searches both, opens the second's result and plays it
there. Run it by itself, from a fresh install (`xcrun simctl uninstall` and
`simctl keychain <udid> reset`, as `make apple-e2e` does):

```bash
TEST_RUNNER_WMM_E2E_SERVER=localhost:<port> TEST_RUNNER_WMM_E2E_USER=… TEST_RUNNER_WMM_E2E_PASSWORD=… \
TEST_RUNNER_WMM_E2E_SERVER_2=localhost:<port> TEST_RUNNER_WMM_E2E_USER_2=… TEST_RUNNER_WMM_E2E_PASSWORD_2=… \
xcodebuild -project apple/WatchMyMedia.xcodeproj -scheme WatchMyMediaE2E -destination 'platform=iOS Simulator,id=<udid>' \
  -derivedDataPath apple/build/DerivedData CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= \
  -only-testing:WatchMyMediaUITests/ServersEndToEndTests test
```

Two more runs follow that one, without reinstalling. Stop the second server
and add `TEST_RUNNER_WMM_E2E_SERVER_2_STOPPED=1`: the app moves to the first
server, choosing the second in Servers says it couldn't be reached, and
search says it didn't answer. Or end the simulator's sign-in on the second
server from somewhere else (DELETE /api/auth/sessions/{id} as the same
person) and add `TEST_RUNNER_WMM_E2E_SERVER_2_SIGNED_OUT=1`: the app shows
that server's sign-in, Change server opens the first without a password, and
Servers says the second is signed out here. `WMM_E2E_SEARCH` gives the words
to search for ("harbor" unless set).

`make apple-gen` writes `WatchMyMedia.xcodeproj` from `project.yml`; open it
in Xcode to run on a simulator or a device. The project and `App/Info.plist`
are generated and not committed: change `project.yml` instead. `App/`, `TV/`,
`UITests/` and `TVUITests/` are synced folders, so Xcode builds whatever files
they hold: after a pull that adds or removes files the open project needs
nothing, and only a change to `project.yml` needs `make apple-gen` again.

The project's version is a placeholder, 1.0 (1). Before archiving in Xcode
(Product → Archive), generate it for the release, `make apple-gen
VERSION=v1.2.3`, which writes that tag's App Store version (the tag's own from
`v2.0.0`; before that it stayed on 1.x, since the App Store's first release was 1.0:
`v1.0.0` was 1.6.0 and `v1.8.0` was 1.11.0)
and its build number (`scripts/version.sh`) into the project; a plain `make apple-gen` puts the
placeholder back. `make apple-archive VERSION=v1.2.3` sets them itself.
The Apple TV app (the WatchMyMedia TV scheme) can't be archived in Xcode until
an Apple TV is registered to the team, because Xcode's archive step signs with a
tvOS development profile. `make apple-tv-archive VERSION=v1.2.3` archives it
unsigned instead and signs the app and its Top Shelf extension ad hoc with their
entitlements (the App Group), and `make apple-tv-upload VERSION=v1.2.3` signs it
for the App Store when exporting and uploads it to TestFlight. The iPhone app's
`make apple-archive` and `make apple-upload` work the same way, without a
development certificate ("Releasing the iPhone app" in the root README).

Nothing can tap a simulator from a script, so two more launch arguments
reach any screen for a screenshot: `-tab tvShows` (or `home`, `movies`,
`search`, `settings`) and `-open <link>` with a `watchmymedia://` link
(`shows/100` opens the show's page with its next episode on top,
`shows/100?season=3` with that season's first unfinished one and
`shows/100?season=3&episode=100037` with that episode,
`movies/500`, `play/9101`, `link?code=K7M4PQ`, and `requests` and `inbox`
for Settings' Your requests and Inbox). Launch arguments are the app's own,
so `play/` and `live-tv/<channel>` act as they read; the same links from
outside the app (Safari, a QR code, another app) open the title's page or
the guide instead, and only a link the app published for the server signed
in (a reminder's notification) starts playback or takes a tuner
(`DeepLink.opening`):

```bash
xcrun simctl launch <udid> media.watchmy.client -fakes -tab movies
xcrun simctl io <udid> screenshot /tmp/movies.png
```

The launch argument `-fakes` runs the app on the fakes in `WMMFakes` (the
mockups' fictional library, no server): any name signs in with the password
`password`. The fakes are in Debug builds only: a Release build ignores every
`-fakes…` argument and doesn't link `WMMFakes` (see Release builds below). The fakes' posters are seeded gradients, but every show, movie,
episode and Continue watching item carries one of six fictional backdrops
drawn in code (`FakeBackdrops`), so Home's row screens on iPad and Apple TV
can be judged on a simulator. `-fakesSignedOut` starts at sign-in, `-fakesNoServer` at the
server picker. The scheme's Run action lists `-fakes`, off by default.
`-fakesLargeLibrary` gives the fakes' Movies library about 1,200 titles (the
sample's, then made-up ones), for measuring the grid.
`-search harbor` searches at launch. The fake player has seek thumbnails drawn in
code, so `-open watchmymedia://play/9101 -playerKeepControls -playerScrub 0.44`
holds the scrubber at 44 % with its tile up.
`-playerPanel episodes|audio|subtitles|quality` opens that panel beside the
picture, or its tab under the fold, on the iPhone Duo's inner screen.

The fakes' server is at API level 6, so requests, missing episodes
and deleting show: Harbor Lights' season 2
(`-open 'watchmymedia://shows/100?season=2'`) misses three episodes, each for
another reason; searching "harbo" finds Not in your library; and the Inbox
holds two requests and two problems. `-fakes` signs in as Sam, an admin;
`-fakesMember` as Alex, a member whose requests wait for approval.
Add `-fakesSeerr` to either mode for Seerr requests, a Seerr-only admin Inbox
with legacy local requests, and reports accepted as Seerr issues. Add
`-fakesSeerrUnlinked` too for an account that needs linking: Your requests
is empty and reports fall back to the local Inbox.
Sam can delete episodes: Sonarr holds Harbor Lights and Copperline, so
theirs confirm and go (until the app is relaunched), and any other show's
says WatchMyMedia can't delete it. Alex can't, as with Who can delete set to
Admins only.

At wide iPad widths, Inbox shows its request or problem list beside the selected
item, keeping approval, decline and resolve actions available.

A show is one page on every Apple device: the episode on top, the season
tabs over the season's episodes in a row, then About the show. `ShowPageModel`
holds it and `ShowPageRule` says which episode goes on top. Picking a tab or a
card changes the page in place, so Back leaves the show. On Apple TV, focus
moving along the row changes the episode on top, and Select on a card takes
focus back up to Play.

Your requests remains available on API level 5 servers after requesting is
disabled. With Seerr, the admin Inbox keeps earlier local requests alongside
Seerr’s approval page; reports confirm whether Seerr accepted an issue or the
server kept a local report.

`WatchMyMediaE2E` also runs the fake request-screen checks. The opt-in Seerr
fixture flow uses `TEST_RUNNER_WMM_ARR_SERVER` and
`TEST_RUNNER_WMM_ARR_MOVIE` (a missing movie assigned only to that test); it
requests and cancels that movie as the fixture viewer. The Apple TV suite can
use the same server variable with an `http://127.0.0.1:<port>` address to pair
as the fixture admin and read the Inbox without changing shared requests.
`TEST_RUNNER_WMM_ARR_PAD_RESIZE=1` runs the regular-to-compact Inbox check on
a disposable iPad simulator with windowed apps enabled.

On an iPad the app has layouts of its own wherever the width is regular:
full screen in either
orientation, or a window wider than half the screen. A compact width (every
iPhone, Slide Over, a 50/50 split) shows the iPhone screens, so check both:
run on the iPad Pro 11-inch simulator, where `-fakes` signs in as Sam’s iPad,
and resize its window from its corner.

At 1000 pt, Home puts Continue watching beside the hero, Library shows its
filter panel, and Show/Movie details and Search use two panes. Title-sorted
libraries offer an A–Z index when the server supplies it. In portrait the
player shows the season below the video; in landscape it fills the screen.
Audio, Subtitles and Quality open a side panel and keep the video playing.
Settings keeps Add a server's steps inside its split view.

The Apple TV app takes the same `-fakes`, `-fakesMember`, `-fakesSignedOut`,
`-fakesNoServer`, `-tab` and `-open` arguments (`watchmymedia://link` is the
phone's and is ignored); on the fakes it runs as "Living room Apple TV", and
its pairing code is approved by itself after a few seconds:

```bash
xcrun simctl launch <udid> media.watchmy.client -fakes -open 'watchmymedia://shows/100?season=3&episode=100037'
```

Its player takes these too, on the fakes and in debug builds
(`TV/Screens/Player/PlayerLaunchArguments.swift`): `-playerSeek 2970000`
(the Next Episode card), `-playerSubtitle s4`, `-playerStillWatching`,
`-playerStopped` and `-playerError`, after `-open watchmymedia://play/9101`.
The fakes have no video, so the system player runs empty over the backdrop:
Skip, the card and the prompts show, but not the transport bar or its
Episodes, Audio, Subtitles and Quality tabs, which need a server;
`-playerPanel episodes` shows the Episodes tab over the video instead.

The info panel's Episodes tab
comes after Info for an episode, never a film or a recording: the
show's seasons as buttons (focus on one chooses it) above the chosen
season's episodes on disk, focus starting on the one playing, which is
marked Now playing; the one after it is Next. Select or Play/Pause on
another plays it in place, from where it was left. `TVPlayerEpisodesModel`
decides it. It asks the server nothing to know whether the tab shows (the
request carries the show and season, or the next episode is found in a
season already loaded, or looked up once); opening the tab the first time
asks for the show and the playing season side by side, each other season
once when chosen, and the playing season again after another episode
started.

The Apple TV simulator takes the Mac's keyboard as the Siri Remote: the
arrows move focus, Return selects, Escape is Menu, and a long press is
Return held down. `apple/TVUITests` drives the remote in XCUITest on the
fakes (where focus lands, the long-press menus, Menu going back, and the
player's Next Episode card, Still watching and stop and can't-play states)
and runs with the rest in `make apple-tv-test-sim`. Its waits are in
`TVUITests/Waiting.swift`: XCTest's own look a second late, and they add up
over a test of many presses. XCUI does not see AVKit's contextual actions,
so Skip is checked by hand.

Settings on Apple TV (`TV/Screens/Settings/`)
shows one group at a time: the web's groups in
its order at the left (Playback, Audio & subtitles, Home rows, Account &
devices, Your requests, Server, About), each with how things stand under its
name; the chosen group's page beside them, which scrolls in its own column;
and at the right a panel that says what the focused setting does. Moving
along the list chooses a group; Right or Select moves into its page on the
row it had last, Menu comes back to the list, and Menu again goes to the top
bar. A choice is a row that opens a menu (Quality, each part of Skipping,
Remind me), each choice with its line; it saves when it is made and the row
says Saving, then Saved and what it did, or why it couldn't and what it still
is. What takes the screen has the path across its top and what the remote
does along its bottom: Name this Apple TV (room names, and Type another name
for the keyboard), Add a language (every language by name in a grid that
scrolls, with Search languages above it for the keyboard or the
microphone), the codes to scan for Change password and Server settings, and
Add a server's three steps, which `RootView` keeps above the tabs because a
server added rebuilds them, and a registered server's page. Sign-out questions open on the button that keeps
things, and Menu is that button. The groups and their lines
(`tvSettingsGroups`), the words, each row's save (`saveState(_:)`,
`qualitySave`, `deviceNameSave`, `LiveExtrasStore.leadSave`), the dialogs,
the steps and Add a server's sign-in (`pairsByDefault`, starting on the
code) are the Kit's; the panel's own text is
`TV/Screens/Settings/SettingsPanelWords.swift`. `-fakesSaveFails recap`
(the Kit's, with `:422`, a list, `languages` or `all`) makes a save fail,
to see a row say why.
`-settingsGroup playback|languages|homeRows|account|requests|server|about`
shows a group with focus on it at launch; `-renameDevice`, `-changePassword`,
`-serverSettingsCode`, `-signOutQuestion`, `-addServer`,
`-serverPage <server id>`, `-subtitlesSettings` (with `-addLanguage audio|subtitles`) and
`-homeRowsSettings` open the rest, in debug builds.
`TVUITests/SettingsTests.swift` drives them with the remote.

Sorted by title, the Apple TV grid has a letter index down its right edge:
right from the last column
enters it at the poster's letter, the grid follows the letters up and down,
Select or left lands on the letter's first title, and Menu goes back to the
poster. It is built from the `letters` the server already sends with a
title-sorted page (each letter's first offset in the whole filtered list);
`LetterIndex` (WMMFeatures) turns them into the 27 places with their counts,
and `LibraryModel(letterJump: .extend)` loads everything up to a letter
beyond the titles held, so the grid keeps its start and its rows. Page Up and
Page Down (`onPageKeys`, on remotes that have them) move two rows in any sort
(`GridPaging`). `-librarySort title` starts the grids sorted by title in a
debug build; `-fakesLargeLibrary -tab movies -librarySort title` gives the
index most letters (`TVUITests/LetterIndexTests.swift`). A list's page takes
Page Up and Page Down the same way (`TVUITests/ListPageKeysTests.swift`).

### Downloads

From API level 10 an iPhone or iPad downloads movies and episodes to watch
offline: Download on a movie's or an
episode's page, Settings › Downloads for the list and the settings, and Home
shows what is downloaded when the server can't be reached. The server's Who
can download (Settings › Users on the web) is Admins only by default.

On the fakes Sam can download, and holds Dead Reckoning Bay, an episode of
Harbor Lights still coming and one of Saltmarsh the server deleted; a new
download takes a few seconds. `-open watchmymedia://downloads` opens Settings ›
Downloads. The Apple TV app has none (`UnavailableDownloads`).

A download keeps going in the background through the system's sessions, so
`AppDelegate` hands their events to `DownloadServices.handleEvents`. When one
finishes while the app is away, the session's completion handler waits (at
most 20 seconds, under a background task) until the next download has been
handed to the system, since an HLS download is made ready by the app first
(`HandoverHold`). Wi-Fi only also waits on a personal hotspot and in Low Data
Mode, which the system marks expensive and constrained. Its files
are in `Library/Application Support/Downloads` (`state.json`, `engine.json` and
a folder per original file) and, for HLS, the package the system made under
`Library/com.apple.UserManagedAssets…`; read them from the simulator's app
container (`xcrun simctl get_app_container <udid> media.watchmy.client data`).
With the simulator there is no airplane mode: stop the server to be offline,
and start it again to watch the sync.

The show page's season ⋯, beside Mark season watched, offers **Download season**. Its confirmation
totals the new available episodes at the chosen quality, leaving at least 1 GB
free and accounting for pending transfers. Episodes already held or queued and
episodes without a file are excluded. A partial failure keeps the files already
queued; Retry adds only those still missing. Downloaded episode pages now work
offline, with the season's other held episodes, Play/Resume and queued watched
marks. A local copy offers Audio/Subtitles rather than streaming quality.
`-fakes -fakesOffline -open 'watchmymedia://shows/102?season=1&episode=102019'`
previews the offline page.

## Trailers

A movie page's Trailer plays the first trailer the server lists
(servers from API level 20 list them). A trailer file plays in our player.
A YouTube trailer opens YouTube's own player full screen on iPhone and iPad: a
`WKWebView` (`App/Screens/Detail/TrailerPlayerScreen.swift`) loading a small page
with the base URL `https://media.watchmy.client`, the bundle ID, which YouTube
needs as the Referer (without one it refuses with error 153). On Apple TV it
opens `youtube://www.youtube.com/watch?v=<key>` when the YouTube app is there
(`youtube` is in the TV app's `LSApplicationQueriesSchemes`), else a code to
scan. Simulators have no YouTube app, so they show the code; the hand-off and
the YouTube app on iPhone need real devices.

On the fakes, Dead Reckoning Bay (`movies/500`) has a YouTube trailer (Big
Buck Bunny, Creative Commons) and The Paper Kite (`movies/501`) a trailer file.
`-fakesTrailerKeys a,b` gives them other YouTube videos, and in a debug build
`-trailerError 150` makes the player report that error once it is ready, which
reaches the "can't play here" screen: no video is known to refuse embedding
reliably. `UITests/TrailerTests.swift` runs both paths:

```bash
xcodebuild -project apple/WatchMyMedia.xcodeproj -scheme WatchMyMediaE2E -destination "platform=iOS Simulator,name=WMM <yours>" \
  -derivedDataPath apple/build/DerivedData CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= \
  -only-testing:WatchMyMediaUITests/TrailerTests test
```

## Rows

From API level 22 the server arranges Home and each library's Movies or TV
screen. Home
asks for `arranged=true` and draws `HomeData.rows` in the person's order by
style (`ArrangedRow`, `TVArrangedRow`), the app's own Live TV row in the
`live` slot, and skips a style it doesn't know. On iPad Continue watching sits
beside the hero only while it is the first row drawn. Movies and TV Shows put
the library's rows above the grid (`getLibraryRows`), then Edit rows and the
grid as All movies or All shows; a title search hides them, and a library
row's See all sorts and filters the grid below. On iPhone and iPad every row
with a link ends in a See all card the size of its cards ("See all TV shows",
"By last aired"), and has See all in its heading too.
From Home it opens the TV Shows or Movies tab at its grid, sorted the row's
way with no filter: `Navigator.open(_:home:)` keeps the row's link for that
tab and the tab's `LibraryModel` takes it once (`takeBrowseLink(for:)`), so
selecting the tab again later leaves the grid as the person has it. Continue
watching and Live TV have no See all, nor do Home's rows that mix two
libraries of one type, which the server sends without a link. On iPad's Home
screens the card never leads its row. On Apple TV the card is the list
card's size and look, with the library over the sort; with it focused the
panel stays on the row's last card, Select opens the tab with focus on the
grid's first poster, and Menu from there brings Home back to the row with
focus on See all (`Navigator.exitsToHome`: selecting the tab scrolls Home to
its top). Older servers keep today's fixed rows, and their library screens
have none.

Home rows (Edit Home at the end of Home, Settings › Home rows, and Edit rows
on a library) is one sheet on iPhone and iPad: drag handles, Hide and Show in
place, Add a row (the hidden rows by kind, and the line that lists are made on
the web), Reset to default; Done saves the whole order. On Apple TV it is a
full-screen sheet from Settings › Home rows (and Edit rows on a library) with
Move up, Move down and Hide, an Add a row menu and Reset, saving each change.
`RowEditorModel` holds the rules. A list row's See all opens the list's page
(`MediaListModel`; `watchmymedia://lists/{id}`): every title in rank order,
missing ones dimmed with Request through the request sheet, Requested or
Downloading, and Not found for an imported title TMDB couldn't find. `rows.changed` and `lists.changed` refetch
Home, the library screens and the list page. There is no list builder: lists
are made on the web.

`-fakes -fakesRows` puts the fakes' server at level 22: Home has two list rows
and Trending this week waits in Add a row; Movies has Best of 2026, whose last
MDBList fetch failed. On iPad and Apple TV an arranged Home is drawn one row a
screen (`-fakesRows` is needed to see it). `-editRows` opens Home rows at launch and `-addRow` its
Add a row; `-open watchmymedia://lists/9001` opens Top rated science fiction
(with titles to request) and `lists/9002` Rainy Sunday films (an import with a
title not found). On Apple TV, `-tab settings -homeRowsSettings` opens Home
rows. Add `-fakesMember` for a member, who can't delete or refresh the
household's lists, and `-fakesTwoMovieLibraries` for a server with two movie
libraries, whose Home movie rows have no See all.

## Several servers

A device stays signed in to up to 12 servers, one person on each, and one of
them is in use: Home, the libraries, Live TV, requests, the Inbox and
Settings' account pages are that server's. The list lives on the device (the
servers and when each was last used in UserDefaults, a sign-in each in the
Keychain); nothing about it is sent anywhere, so a server is added once on
each device.

- **Servers** is what the account button opens: the registered servers (the
  ones signed in to and not removed, the one in use first, then by last
  use), Add a server and Manage servers. Choosing a server asks it which
  server it is (GET /api/server at its kept address, 5 s at most) and makes
  it the one in use when it answers as itself: no password, no steps. The
  app closes the player, rebuilds on Home and says "Now on Cabin server" for
  4 s. One that doesn't answer leaves the app where it was, with Try again
  and, on a device holding downloads from it, Watch its downloads, which
  switches anyway to the offline Home. A server whose sign-in ended says so
  and opens its sign-in. `ServersModel` (`WMMFeatures/Account`) is the one
  model for all of it, shared through the environment, since Settings,
  Search, Downloads and links switch too; only it, sign-in's picker and Add
  a server call `ServerRepository.select`.
- **Add a server** is a task of three steps, Choose a server, Sign in, Added
  (`AddServerModel`). Servers already registered aren't offered, and an
  address that reaches one is refused. Cancel, or a failure at any step,
  leaves the app on the server it was on.
- **Settings › Server** lists Your servers; each opens its page (address,
  version, who is signed in, what is downloaded from it) with Switch to this
  server and Remove this server. Removing signs this device out of it,
  deletes its downloads here and takes it off the list, after asking, and
  changes nothing on the server. When it was the one in use, or after Sign
  out, the app moves to the server used most recently that is still signed
  in.
- **A sign-out is sent only to the server itself.** Removing a server, or
  signing out of one, first asks the address kept for it which server it
  is, with no sign-in, once for all it sends. When that server answers,
  its downloads here report themselves removed and the session is ended
  there. When nothing answers, or another server does, nothing is sent and
  nothing is kept to send later: the server and its downloads leave this
  device at once all the same, and the session stays on that server until
  it runs out or someone ends it from that server's list of devices.
- **A sign-in goes only to the server it is for.** The address kept for a
  server is where it was last seen, and on another network that can be
  someone else's machine. So before the sign-in of a server that is not,
  or is no longer, the one in use goes to its kept address, that address
  is asked which server it is, with no sign-in (GET /api/server), and the
  sign-in goes only when it answers as the one remembered. It is asked in
  the one place a sign-in is put on a request (`RequestSigner`), so it
  holds for everything: search across servers and its pictures, a
  download's calls to the server it came from (its sync, its tokens, the
  fetch of an original file and its subtitles), a sign-out, and whatever
  was still sending to one server when another came into use. Search
  keeps the answer for its visit, a sign-out or a removal for all it
  sends, and background work asks each time; an answer holds only on the
  network it was given on. When nothing, or another server, answers, the
  call fails as one that couldn't reach its server does, and a download
  tries again later. The server in use is never asked. Not asked either:
  an HLS download's media requests, which carry a short-lived offline
  token in the address, and the transfer of an original file once the
  system has it (it is asked when the transfer is created, not when the
  system resumes it by itself).
- **Search looks in every server signed in to.** Each is asked at once, to
  its own address with its own sign-in (`LibraryRepository.search(_:serverID:)`,
  through an API built for that server alone). First each is asked which
  server it is, with no sign-in (GET /api/server), and its sign-in is sent
  only when it answers as the one remembered: the address kept for a server
  is where it was last seen, and on another network that can be someone
  else's machine. One question covers a visit to Search, not each
  keystroke, and shares the server's 4 s: a keystroke cancels the search,
  not the question, so the next search waits for the same answer. A visit
  begins each time Search comes on screen (the first time, back from a
  title or another tab, back from the background); on the way back the
  servers whose titles are listed are asked again at once, so their
  pictures go on loading, and nothing is searched again. An answer stands
  only on the network it was given on, and a search there that fails
  forgets it, so Try again asks first. Another server at the address, or
  none, is a server that didn't answer, and is sent nothing. The answer
  also keeps the server's name, version and API level up to date. The server in use's results
  come first in each group and the others' follow in the Servers list's
  order, each naming its server; the same title on two servers is listed
  twice. A server gets 4 s; one still out after 1 s is named at the end of
  the list, and one that doesn't answer gets a line with Try again and is
  left alone for 30 s on that visit, so typing doesn't queue timeouts. A
  keystroke cancels the calls still out. Pictures come from the result's own
  server, signed for it (`CachedImageLoader`). Opening a result on another
  server switches to it and shows Search with the same words and the title
  there. Not in your library, On Live TV and marking watched stay the server
  in use's. What is typed goes to each server signed in to and nowhere else.
- **A link or a notification names its server.** One for another registered
  server that is signed in switches to it first, then opens
  (`DeepLink.opening(_:signedInServer:others:)`, `ServersModel.open`); one
  for a server that isn't registered opens nothing.
- **Kept for each server:** the sign-in, where the person was, recent
  searches, the new-episode place and the downloads with their sync. The
  Downloads list shows the server in use's, with a line for each other
  server's ("3 more from Cabin server") that switches to it. Live TV
  reminders and the Top Shelf follow the server in use.

On iPhone and iPad (`App/Screens/Servers/ServersView.swift`) the account
button is the person's initial at the right of Home's and Live TV's bars. It
opens Servers as a sheet on a compact width and as a popover under the button
on a regular one. With two or more servers, the name of the one in use is
under Home's title on a compact width and beside the initial on a regular one
(where the bar has room for it whole). Where Live TV has taken Settings' tab,
Servers also has a Settings row, since the initial used to be the way there.
"Now on Cabin server" is a pill at the top that goes by itself. A switch
started from a server's page or the Downloads list that fails opens Servers
there, as a sheet. `UITests/ServersTests.swift` runs all of it on the fakes,
on an iPhone and an iPad, and `UITests/TabletAddServerTests.swift` adds a
server on an iPad across a rotation.

On Apple TV the account is the tab bar's last tab (`TV/Shell/MainTabs.swift`):
the person's name, or the server in use when there are several. Right from
Settings reaches it and its page is Servers (`TV/Screens/Servers/`), a panel
at the right; Down lands on the first server that can be chosen, the foot of
the panel says what Select does, and Menu closes it for Home. It is a tab
because nothing beside the system tab bar takes focus from it. After a switch
a banner at the top right says "Now on Cabin server", and the Top Shelf is
published again for that server, whichever screen the switch lands on.
Settings › Server lists Your servers; a server's page takes the screen
(`TV/Screens/Settings/ServerPage.swift`), with Switch to this server, or
Sign in again, and Remove this server, whose question opens on the button
that keeps it. Search's cards from another server name it under the title,
and the servers still being asked or not listed are tiles at the end of the
last row. A title opened on another server takes focus, so Menu goes back to
the results. Apple TV keeps no downloads, so a server that doesn't answer
offers only Try again. `TVUITests/ServersTests.swift` drives it with the
remote; `-servers` opens Servers at launch, `-serversChoose <server id>`
chooses a server in it, and `-serverNoteSeconds <n>` keeps the banner up.

On the fakes, `-fakesServers` signs Sam in to three servers: Living room
server (in use, the sample library), Cabin server, with a small library of
its own (`FakeCabin`: its ids are its own, and Harbor Lights is on both, so
searching "harbor" lists it twice), and Den server, which doesn't answer and
holds three downloads. Add a server finds the Office Mac mini.
`-fakesSlowServer` makes Cabin server take 2.5 s to answer a search, to see
Search say it is still asking. Without `-fakesServers` the fakes are signed in
to one server, signed out of a second and remember a third that is too old.

## Settings

Settings follows the web's: the same groups, names
and words, a root that is a hub, and one rule for saving. What the views draw
comes from the Kit (`WMMFeatures/Account/`), so the iPhone, iPad and Apple TV
apps word and decide alike:

- **The hub.** `settingsHub(model.hubFacts(…))` gives the iPhone and iPad
  root's groups in the web's order (You, This iPhone, Server, About) with each
  row's value; rows the server or account doesn't offer are left out.
  `tvSettingsGroups(_:)` is Apple TV's list beside the page, with the line
  under each group and its panel. `settingsNextStep(_:)` picks at most one
  Next step (a language to watch in, then notifications off while reminders
  are set, which the app passes in); `banner` says the server can't be reached.
- **Saving.** A switch or a choice kept on the server saves at once and says
  so in its own row: `SettingsModel.saveState(.skip(.credits))` (and
  `LiveExtrasStore.leadSave` for Remind me) is rest, saving, saved with what
  the change did, or failed with the control back at the saved value and
  named. Saved goes back to rest after four seconds through the sleep the
  model was given. Anything typed (Rename, Change password) is a sheet with
  its own model (`RenameModel`, `ChangePasswordModel`).
- **Words.** `SettingsWords` holds every page's names, ledes, Saved lines,
  dialogs (`ConfirmWords`, with the person and the numbers) and the pages that
  aren't offered (`KeptOutWords`, from `keptOutReason(_:server:…)`). Skip's
  modes are the web's (`SkipMode.label`, in the web's order).
- **Languages by name.** `LanguageCatalog` (`WMMModel`) is every language the
  system has locale data for, with the ISO 639-1 ones and pt-BR, pt-PT,
  zh-Hans, zh-Hant and es-419; `LanguagePicker` searches it, marks what a list
  holds and says why Add is off at 16. No code is typed.
- **Tasks with steps.** `LinkTVModel` and `AddServerModel` give each step
  (`flowStep`: number, name, next), each button's title and why it is off,
  failures that end in what was or wasn't done (`problem`, `chooseProblem`,
  `signInProblem`), and the finished screen (`finished`).
- **Server settings** are the web app's, for admins only:
  `SettingsModel.loadAdminAccess()` asks GET /api/auth/status and
  `serverSettingsRow` says whether they open from here. **Change password**
  calls PUT /api/auth/password; Apple TV shows `changePasswordURL(_:)` as a code.
- **Refusals on the fakes.** In a debug build `-fakesSaveFails recap` has the
  fake preferences refuse that save every time, as an unanswered server would
  (`recap:422` as a newer server would; also a list such as `recap,autoplay`,
  `languages` or one of its parts, or `all`), and `-fakesPasswordFails 429`
  (or `401`, `offline`) the next Change password. `AppServices.fake` reads them
  (`applyFakeRefusals`), so both apps take them.

On iPhone and iPad (`App/Screens/Settings/`):

- **The root is the hub** (`App/Screens/Settings/SettingsScreen.swift`): the
  account opens Account & devices, then the Kit's `settingsHub` groups, each
  row with how things stand at its right; one Next step card under the account
  when there is something to do (Use English, or Open iOS Settings for
  reminders), and a banner with Try again when the server can't be reached. On
  iPad it is the sidebar of a split view and the page stands beside it, in two
  columns where the page is 680 pt or wider; a compact width shows the
  iPhone's. There is no Sign out on the root.
- **Pages** share `Components/Account/SettingsParts.swift`: a line under the
  title, groups with a note, rows whose own line says how a change went (the
  Kit's `SaveState` as a `RowLine`: Saving, the Saved line, or why it failed,
  said to VoiceOver with the system's success or error tap), choices as menus
  from their row (each choice a `Toggle`, so the menu ticks the chosen one and
  shows its line), and a red row apart at the bottom for what signs out.
  A row keeps its value whole at its right and puts it under the title when it
  can't share the line, and always at the accessibility text sizes.
- **Playback** holds Quality (this device's), Skipping (a menu per part), Next
  episode and Live TV reminders, which were pages of their own. **Account &
  devices** holds this device's name (Rename is a sheet), Change password (a
  sheet with the system's password fields, naming the devices it signs out),
  Signed-in devices, Link a TV and Sign out of this iPhone. **Server** lists
  Your servers, each opening its page, with Add a server under them, and
  offers Server settings to admins only, as the server says they stand here. **Audio & subtitles'** Add language is a sheet of every language
  by name (the Kit's `LanguagePicker`), to search.
- **Tasks are sheets** (`Components/Account/TaskSheet.swift`): Cancel, the
  task's name, the steps bar ("Step 2 of 3"), one filled button at the bottom
  (Return presses it on a hardware keyboard, Escape is Cancel) and a last step
  that says what changed and what happens next, closed with Done. Link a TV and
  Add a server are the two; on iPad Add a server stays in the detail column.
  Their models live in `AppFlow` (`Shell/RootView.swift`), as the sign-in form
  does, so a window resized mid-task keeps its step. A sheet with something
  typed asks before a swipe throws it away (`asksBeforeDismissing`).
- **Removes ask first** in a dialog from the button that asked (a popover on
  iPad): signing out another device names it and whose it is, signing out of
  this one names the downloads it deletes, and Cancel request and Remove all
  downloads ask too.
- **A page that isn't offered says why** (`KeptOutPage`): Your requests,
  Downloads and Audio & subtitles opened by a link or `-settingsPage` when the
  server or the account keeps them out.

`-settingsPage` takes `playback`, `subtitles`, `notifications`, `account`, `requests`,
`downloads`, `server`, `inbox` and `about` (and the old `skip`, `liveTV`,
`thisDevice` and `devices`, which open the page they moved into);
`-open 'watchmymedia://link?code=K7M4PQ'` opens Link a TV at its second step,
`-linkTVApprove` at its last; `-settingsPage server -addServer` starts
Add a server (`-addServerAddress <address>` connects to an address at once),
whose Sign in offers a code from a signed-in device in place of the password
(the fakes approve it after a few seconds); `-servers` opens Servers. The refusals
above reach a row's failed line and Change password's errors.
`UITests/SettingsTasksTests.swift` runs both tasks through every step on the
fakes, with Back and Cancel, a row that saves and one that fails, Rename,
Change password and its errors, and both sign-outs; on an iPad, a task
across a window resize and the pages' two columns in landscape.

## New episodes

From API level 29 a person follows a show and hears when a new episode of it
reaches the library.
The words are the Kit's (`NewEpisodeWords`), the rules too:

- **Notify me** is first in About the show on every Apple device
  (`FollowsStore`, shared by every screen): it follows at once and goes back
  if the server refuses, with the line under the actions saying why. On
  iPhone and iPad the first Notify me on the device, while iOS hasn't asked,
  opens the explainer (once, with its own mark) before iOS's question; Not
  now keeps the follow. On Apple TV, while the bell has focus, the line under
  the buttons says the phone or tablet notifies.
- **Settings › Notifications**, in the You group: this iPhone's or iPad's
  switch (kept on the device) and iOS's states, then the shows followed with
  Stop notifying; on Apple TV the shows alone, Select offering Go to show and
  Stop notifying.
- **The check** (`NewEpisodeChecker`, `App/Shell/NewEpisodeNotifier.swift`)
  runs in the background refresh after the downloads' sync, when the app
  opens or comes back, three minutes after the last library change while it
  is open, and once iOS allows notifications or a show is followed. It asks
  the server's feed whenever this device could notify (signed in at level
  29, the switch on, iOS allowing), whatever this device last saw of the
  follows, since a show followed on another device notifies here too. It
  keeps the server's cursor for each server this device is signed in to and
  the person signed in there (`NewEpisodeMemory`, in UserDefaults, keyed by
  their ids), so coming back to a server announces what arrived there
  meanwhile. A server's cursor goes when it is removed or signed out of, or
  when someone else signs in there, and every cursor goes when the switch
  goes off (not on an Add a server that was cancelled). Only the server in
  use is checked. A first check only keeps the cursor; an unreachable
  server, or a 404 from a proxy in front of a restarting one, keeps it too.
  A check asked for while one runs runs once more after it, and the
  background refresh's expiry cancels it at once. Each check posts
  one notification a show at once, in the `new-episode` category, threaded
  by show, and past five shows one more that opens Home, which iOS lists after
  the five (`UITests/NewEpisodesTests.swift` checks). A tap opens the show
  at its earliest new episode, only for the server it came from.
- **Home's New from shows you follow** is the server's built-in `followed`
  row, drawn by its `episode` style like Recently aired: nothing in the apps
  knows its id.

`-fakesFollows` puts the fakes at level 29: Sam follows Harbor Lights,
Northfield Precinct, Orbital Decay and The Tidewater Array, which has left
the library; Home has the row; a first check keeps the cursor and the next
finds Harbor Lights' three episodes and one of Northfield Precinct's
(`-fakesManyNewEpisodes`: seven shows). `-newEpisodesReset` (debug builds)
forgets the device's cursor, switch and explainer mark at launch. On a
device, the background task can be started from the debugger while the app
is paused in the background:

```
e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"media.watchmy.client.sync"]
```

The iOS 27.2 Simulator doesn't keep the request (the app logs its submit,
then "No task request with identifier media.watchmy.client.sync has been
scheduled"), so on a simulator bring the app forward instead.

`UITests/NewEpisodesTests.swift` and `TVUITests/NewEpisodesTests.swift` run
the bell, the explainer, the notifications and Settings on the fakes.
`UITests/NewEpisodesServerTests.swift` runs them against a real server, one
step at a time with the server's side done between steps: on a throwaway
server (its own port and data folder, `WATCHMYMEDIA_UPDATES_ENABLED=false`)
with a TV library and a small fake TMDB on loopback
(`WATCHMYMEDIA_TMDB_BASE_URL`), sign in and follow (`test1`), add an
episode file and scan, wait out the server's two minutes, then open the app
(`test2`, with `WMM_NE_TITLE`, `WMM_NE_BODY` and `WMM_NE_CODE` saying what
to expect); `test3` reads Notification Center, `test4` turns the switch
(`WMM_NE_SWITCH`), `test5` expects nothing (`WMM_NE_ABSENT`) and `test6`
signs out. Pass the variables to xcodebuild as `TEST_RUNNER_…`, build once
with `build-for-testing` and run each with `-only-testing` and
`test-without-building`, on a fresh simulator (the first step expects iOS
not to have asked for notifications).

## Related titles

From API level 30 a movie's page ends with the films of its TMDB collection
that the library has, in release order, then More like this, and a show's
page with More like this. The server works the rows out and sends
them as `ScreenRow`s (`getMovieRelated`, `getShowRelated`), so they are
drawn with Home's poster rows and cards, without See all.

- **When they are asked for.** `RelatedRows` (WMMFeatures), held by
  `MovieModel` and `ShowPageModel`, asks once the page's details are in,
  beside the movie's plans or the show's season, so the page never waits;
  an older server is asked nothing. The reply is kept with the page:
  reloads (a mark, the player's progress) ask again only after pull to
  refresh or a mark on a title in the rows. A failed reply changes
  nothing, and a page read from a download has none.
- **iPhone and iPad** (`RelatedSection`): after Details on a movie, at the
  end of the iPad's right-hand column, and after About the show. Both
  pages keep the tab bar on iPhone, as the boards draw them. A card opens
  its page over this one, and Back comes back to the row.
- **Apple TV** (`RelatedShelves`): the movie page's first screen ends with
  the shelves' names along its bottom edge (`PageFold` from `TabPage`)
  and the shelves wait below it; Down from the versions reaches them. On a
  show, Down from About the show reaches More like this. Up leaves them,
  and Menu from a card's page comes back to the card.

`-fakesRelated` puts the fakes' server at level 30 (`FakeRelated`): Cold
Harbour Road, Low Tide at Carrow and Dead Reckoning Bay (`movies/500`) are
the Carrow Coast Collection, every other movie and show has More like this,
and Tin Sky (`movies/507`) and Glass Country (`shows/108`) have nothing.
`UITests/RelatedTitlesTests.swift` and `TVUITests/RelatedTitlesTests.swift`
drive them.

## Watchlists

From API level 33 a person saves movies and shows to watchlists they name.
A watchlist is one of their lists (`source.kind` `watchlist`), so its rows,
its place in Edit rows and its page are the lists' own; the server fills
the rows (only movies on a Movies screen, only shows on a TV screen, and on
Home without what Continue watching shows).

- **One store** (`WatchlistStore`, WMMFeatures), one for each app in the
  environment, read at launch, on foreground and on rows.changed and
  lists.changed: the watchlists and their count, and which watchlists hold
  each title changed here, over what a card's `watchlisted` or a page's
  `watchlists` said. With one watchlist or none the button and the menus
  toggle (the first save makes Watchlist); with two or more they open Save
  to watchlist (`WatchlistPicker`), and a 409 from a count gone stale opens
  it too. Every change shows at once and goes back when refused. Its words
  are `WatchlistWords` (WMMModel).
- **iPhone and iPad**: the Watchlist button (`WatchlistButton`) beside
  Trailer on a movie and beside Notify me on a show; Save to watchlist as a
  popover from the button on a regular width, a sheet on a compact one and
  from a long press; "Saved to Watchlist" or "Removed from Watchlist" with
  Undo over the tab on screen, a removal held back until the message goes.
  Servers has Watchlists in a group of its own; Watchlists lists them, and
  a watchlist's page (`WatchlistPageView`) has the chips, the sort and ⋯
  (Rename, Remove titles once watched, Delete), with the watchlists beside
  it from `PadLayout.sideBySide`.
- **Apple TV** changes at once and says nothing: the button's words change.
  Save to watchlist takes the screen (`TVWatchlistPicker`), Menu closes it,
  and New watchlist… names one with the keyboard. Watchlists is under
  Manage servers in Servers and opens on Home's stack; a watchlist's page
  has Rename, Delete and the switch at its top, and Remove in a card's menu.

`-fakesWatchlists` puts the fakes at level 33 (`FakeWatchlistsRepository`):
Watchlist (with Amberline to request, The Ninth Bell requested and Winter
Harbor downloading), Halloween, which keeps watched titles, and With the
kids; `-fakesOneWatchlist` keeps only Watchlist, so the button toggles.
`-watchlistBannerSeconds <n>` (debug builds) keeps the message up longer.
`UITests/WatchlistsTests.swift` and `TVUITests/WatchlistsTests.swift` drive
the button and the picker.

## First run, audio languages and subtitles

Empty Home explains whether the server has no libraries, is scanning, could not
read a library, or finished a scan without finding media. Admins get **Open the
web page** on iPhone/iPad and its address on Apple TV; members get instructions
to ask the server owner. Scan transitions refresh it, and Check again remains
available. `-fakes -fakesFirstRun noLibraries` (or `scanning`, `failed`, `empty`)
previews the states; add `-fakesMember` for the member wording.

Settings › Audio & subtitles is one page
on iPhone, iPad (two columns where the page is wide) and Apple TV (a sheet in
three columns): Audio languages, Starting subtitles, Subtitle languages and
Online subtitles. Its rules are in `SettingsModel` (`WMMFeatures`). From API
level 21 it has the audio list and four ways to start subtitles; "When the
audio isn't in my languages" is saved as mode `forced` with `foreignAudio`.
From level 18 to 20 the page is Subtitles with three choices and no audio list.
Each change saves at once and sends only what changed: the server gives
someone who saves their first audio language without ever choosing how
subtitles start the foreign-audio choice, and the page then says so once. An
empty list offers the device's language; it is never saved without choosing
it. Add language opens every language the system can name, to search; a list
holds 16 and says so when full. A language's Move up, Move down and Remove are in a dialog from its row
(Apple TV: buttons on its card). Automatic download needs On or the
foreign-audio choice and a subtitle language; when no local track matches, it
can download one exact-file hash match in the first subtitle language, subject
to the server's permissions and OpenSubtitles quota. Starting video never
waits for the provider; choosing a track or Off cancels a pending search, and
delayed results cannot override the choice. Automatic provider downloads run
during local Apple playback; a Cast token cannot spend the account's subtitle
quota.

The profile says `selectsAudioTrack` to a level-21 server, so a track other
than the file's default stays direct play: `AVMediaPlayer` selects the
decision's audio option by its position before the first frame. If the
file's audible options don't line up with the decision's tracks, the player
asks again naming the track and without the flag, and the server remuxes. An
offline play chooses its audio and subtitles on the device, from the
preferences loaded, with `SubtitleSelection.swift` (the server's Go rules and
test tables). The download sheet preselects the track the server would put in
a converted copy.

`-fakes -fakesSubtitlePreferences -tab settings -settingsPage subtitles`
previews the level-18 page; `-fakesAudioLanguages` makes the fakes level 21,
and `-fakesAudioLanguages es,en` starts with those audio languages saved. Apple
TV uses `-tab settings -subtitlesSettings`. On a throwaway server with the
fixture film Copper Valley (`deploy/fixture/fixture.sh`), an opt-in Kit test
checks the real AVPlayer starts on its Spanish track:
`WMM_LANGUAGES_SERVER=http://127.0.0.1:<port> WMM_LANGUAGES_FILE=<file id>
swift test --filter AudioLanguageAVPlayerIntegrationTests` in `apple/Kit`. It
signs in as the fixture's viewer and saves their audio languages.

## Automatic quality recovery

With a server at API level 24 or newer, Automatic file playback can recover
after the link improves. AVPlayer reports a measured HLS transfer with its age;
an old estimate cannot request an upgrade. A quality directive reloads at the
current file position with the same logical play. The previous session remains
available until the new item renders a video frame and a progress report
acknowledges that generation. If deciding or decoding fails, the player restores
the previous stream and reports the failed generation. A compatible original
file can return to direct play. On older servers, Automatic retains its existing
step-down behavior. Live TV offers its own Automatic choice only when an API-level-26
server advertises shared-source switching. The live player reports its observed
output, including Original, and acknowledges a prepared replacement after AVPlayer
decodes a frame. A failed replacement restores the previous live URL. Mobile-data
or display-profile changes retune only after the old session releases its tuner.

The existing iPhone, iPad and Apple TV Auto chip, quality picker and short note
display these transitions. The Cast remote offers Live TV Automatic only when
the receiver advertises `live-auto` and its server is capable; a handoff to an
older receiver uses fixed 1080p. On debug fakes,
`-open watchmymedia://play/9101 -playerAutomatic slowed|recovered|failed`
previews each state. A real AirPlay target and device decoder still need
hardware verification.

## Google Cast

On a compatible server with casting enabled, the iPhone/iPad Cast button opens
Google's device chooser. Discovery starts after the first tap. A connected TV
plays new file selections; a file already downloaded here keeps playing locally.
The player's Cast button transfers its position, tracks and quality only once the
receiver acknowledges that it started. A failed transfer leaves local playback
available. The mini controller opens the full remote, with pause, seek, Skip,
Next Episode, tracks, quality, volume, Stop and Disconnect. Disconnect leaves
the TV playing; **Continue on this device** deliberately stops the TV and resumes
the same position/tracks/quality without changing this device's quality default.
It is available only for files this account loaded, never another sender's file.

Live TV casting requires server API level 14 and a receiver advertising `live`.
The supported receiver ID is `0A4D0B6A`; custom/dev receiver IDs need a matching
app configuration. The iOS target links official GoogleCastDynamic 4.8.6 through
SPM; the Kit and Apple TV target have no Google SDK dependency. SDK analytics
are disabled, bearer credentials never enter the SDK, and shared metadata has no
authenticated artwork. Account/server changes clear the Cast binding.
`-fakes -fakesCast -open watchmymedia://play/9101` previews the remote without
discovery or a TV; add `-fakesCastError` for failed LOAD/local fallback.

The web receiver source in this change also adopts API 18 subtitle defaults and
preserves explicit Off across reloads. That behavior requires a separate deployment
of the receiver; opening or merging this PR does not deploy it. Older receivers and
Cast Connect retain their existing subtitle behavior.

The new screens follow approved boards.
Simulator/fake transport checks do not prove
the real chooser, Chromecast/Cast Connect, network permissions or hardware.

Against a real server: `make fixture` once, then `make fixture-serve` from the
repository root (port 27588, accounts at the top of
`deploy/fixture/fixture.sh`). The simulator reaches the Mac at `localhost`,
so type `localhost:27588` in the server picker, or let Bonjour find it.

## Live TV

On servers at API level 11 or later, viewers with Live TV access get a Live TV
channel list and a channel row on Home. On iPhone and iPad, Live TV takes Settings'
place in the tab bar; the profile picture opens Settings. Older servers keep
all their existing tabs. Tuner setup and Activity remain in the server's web
app. On Apple TV, Live TV sits between Movies and Settings in the tab bar, with a
focusable channel grid and a channel row on Home. Recording requires API level 19
and the server’s recording capability and permission; iPhone/iPad casting is
described above under Google Cast.

On servers at API level 13 or later whose capabilities say `guide`, Live TV
shows programme listings.
The guide is a grid of channels against an hour (iPhone) or two (iPad,
Apple TV) from the half hour, with the days listed, Now, earlier and later,
category chips and Go to channel. On iPhone the selected programme's card sits
above the grid and its details open in a sheet; a wide iPad shows them in a
panel beside the grid and switches between Guide and Channels; on Apple TV the
hero follows focus and Select watches the channel. Home's On now row, the
watch screen's banner and On now list, and the players' banners and channel
panels show what is on with pictures. Pictures load through the server's
`/api/live-tv/art/` with the usual sign-in, never from the listings provider;
a programme without one shows the channel's logo. `LiveGuideStore` fetches
6-hour windows, prefetches the next and forgets them when the server or
sign-in changes; now and next move on at a programme's end, and the channel
list is asked again only then. `LiveGuideModel` keeps its rows, chips and
selection until something they read changes (observation says when), and each
guide cell is a view of its own compared by what it shows, so a focus move on
Apple TV redraws the cells it moves between; on iPad the Live TV tab holds the
guide's model, so Guide → Channels → Guide keeps the time, the selection and
the listings. Without listings every screen stays as it was.

On servers at API level 15 with listings, each channel in the guide
has a star (its own 44 pt button on iPhone and iPad; on Apple TV the channel
column takes focus and a long press stars it), and Favourites leads the chips
once there is one: it shows the favourite channels, with or without listings,
in channel order. A later programme offers Remind me (a long press on Apple
TV) and then says its reminder is set, moved or no longer in the listings,
with Remove; its cell has a bell. The guide's search button and bell open
Search listings and Reminders (pushed on iPhone, in the panel beside a wide
iPad's grid, chips on Apple TV), and Search has On Live TV after the
library's groups. `LiveExtrasStore` (in `WMMFeatures`, one per app) keeps the
stars and reminders: a change shows at once and goes back if the server
refuses it, and the server's `live.favourites.changed` and
`live.reminders.changed` bring other devices' changes. Settings › Live TV
sets the lead time, kept on the server.

On iPhone and iPad a reminder is a local notification (`LiveReminderNotifier`,
handed its taps by `NotificationRouter`, the app's one notification delegate):
the soonest 64, scheduled again at launch, on foreground, on the event and
after every change. The first Remind me explains before iOS asks; Not now
keeps the reminder on the server. A tap opens the channel's player. tvOS
notifications carry only badges, so on Apple TV a reminder is a banner at the
top right for ten seconds while the app is open, which never takes focus:
over the tabs it says to open Live TV (the guide is then on that programme),
and in the Live TV player to swipe down (Channels puts it first).

Live playback uses its own session and never writes file progress, watch history,
or Continue watching. Changing channel or quality releases the previous tuner
before starting another session. Stop, leaving the player, signing out, or
changing server releases it too. If the previous sign-in can no longer confirm
cleanup, the player asks you to retry after its one-minute session lease before
acquiring another tuner. A brief pause resumes from its current position while that remains in the
rolling buffer. Go live advances to the live edge. An expired position resumes
at live with a notice. After five minutes
paused, the tuner is released and Watch live starts a new session. Four hours
without input asks whether you are still watching and gives one minute to answer.
On iPhone and iPad the channel shows on the lock screen and in Control Center
as a live stream, with Play and Pause (no skipping); a call pauses it and,
when the call ends, it carries on (at live once the pause passed the buffer),
and unplugged headphones pause it. A stream that refuses the media token gets
a new one on the same tuner, as films do, before it reports a lost signal.

On iPhone and iPad the film and Live TV players share one shell
(`App/Screens/Player/PlayerShell.swift`): the status
bar and home indicator hidden, the scene phase and Picture in Picture (2 s for
it to start by itself in the background, `PlayerChromeModel.pictureInPictureGraceMs`),
the safe area for the camera housing, and controls that hide 3 s after the last
touch while playing (`ControlsAutoHide`, tested in the Kit; up for VoiceOver,
and for Channels and the Quality menu on Live TV). `-playerKeepControls` keeps
either player's controls up for screenshots and UI tests.

**Fit and fill**
is the shell's too, for both players on iPhone and iPad: a button beside Close
(arrows out while fitted; gold, with arrows in, while filled), and a pinch on
the picture (out fills, in fits; taps and double-tap seeks keep working). Fit
keeps the whole picture inside the safe area, clear of the Dynamic Island; Fill
covers the screen, the island too, and crops, never stretches
(`resizeAspectFill`). A note under the top bar says which and how much is
cropped ("Fill · 18% of the picture is cropped at the edges", from the
picture's own size against the screen or, on a tall iPad or the portrait Live
TV player, the 16:9 box). The choice is kept on the device for every video
after it and for both players. The rule is `VideoFitModel` (WMMFeatures,
tested in `VideoFitTests`); the views are `App/Screens/Player/PlayerFitFill.swift`.
`-player.videoFit fit|fill` sets the choice for a launch (UI tests,
screenshots) and `-playerFitNoteMs 8000` keeps the note up longer;
`UITests/PlayerFitFillTests.swift` taps, pinches and double taps on the fakes
(run it with the WatchMyMediaE2E scheme and `-only-testing`, as TrailerTests). The fakes draw a backdrop and no video, so how the picture
crops shows only against a server.

Quality is fixed at 1080p, 720p, 480p or 360p. Mobile data caps Live TV at 720p,
including when the connection changes during playback. AirPlay and Picture in
Picture use the live player; backgrounding without either stops the live session.
The AirPlay receiver must be able to reach the selected server address; live
playback does not perform the file player's LAN-address handoff. Simulator playback
does not certify physical AirPlay, PiP, cellular networks or Apple TV remotes.

Apple TV uses AVKit's native Play/Pause and a linear live transport.
Last channel (once there is one), Channel down,
Channel up and Go to channel are transport bar buttons, before Stop and the
system's own, each naming where it goes; as contextual actions they took
Select from the bar. Go live stays a
contextual action. The first time the bar comes up a note says Select
pauses and the buttons are a swipe up. Page Up and Page Down change channel
with a note naming it; the 123 key (tvOS 18.1) or the button opens Go to
channel, a keypad that lists the channels whose number starts with what was
typed and tunes the first two seconds after the last key, or the one chosen;
digit keys type into it and Back closes it. `LiveChannelStep` (the list's
order, skipping channels that can't be watched, wrapping) and
`GoToChannelModel` are the rules, shared with the iPhone's channel keys.
Swipe down opens Channels and Quality while video continues. Back closes an open native
panel first, then stops and returns to the channel browser. The still-watching
prompt takes focus; its first Back keeps watching, and the next Back leaves.

For channel-only fakes, use `-fakes -fakesLiveTV -tab liveTV`, or
`-fakes -fakesLiveTV -open watchmymedia://live-tv/live-1` to open a channel.
`-fakesLiveGuide` (instead of `-fakesLiveTV`) runs Live TV with the guide
boards' fictional channels and listings, anchored so that what is on now is
what the boards show at 8:12 PM, with title cards and logos drawn in code
(`watchmymedia://live-tv/ch-7.1` opens Valley Bake-Off). Add
`-fakesLiveGuideStale`, `-fakesLiveGuideTMDB` (matched stills in the wide
panel and the TV hero) or `-fakesLiveGuideNoPictures` for the boards' Tweaks.
The fakes' server is at level 15 there, with the extras boards' five
favourites and reminders; `-fakesLiveReminder set|moved|gone` and
`-fakesLiveNoFavourites` are those boards' Tweaks, `-fakesLiveGuideNoExtras`
keeps the server at level 13, and `-fakesLiveReminderSoon` (optionally
followed by a number of seconds, 20 by default) replaces the canned
reminders with one whose notification or banner comes that long after launch.
On Apple TV, `-liveBannerSeconds N` (debug builds) keeps a reminder's banner
up N seconds rather than ten, so a UI test on a busy simulator can read it.
Debug fake states add `-liveStillWatching`, `-livePauseExpired`, `-liveBusy`,
`-liveTakeover` (a recording needs the tuner in 2 minutes) or `-liveTakenOver`
(stopped for a recording).
`-fakesLiveAutomatic` uses an API-level-26 fictional server and shows Live TV
Automatic; the fake controller can step between Original and 480p without media.
On Apple TV, `-livePanel channels` or `-livePanel quality` previews the same
panel content for fakes, which have no AVPlayerItem to open native info tabs.
`watchmymedia://live-tv` opens the channel list; a channel's opaque ID after it
opens that channel only if the current account can watch it.

From the repository root, `python3 -I apple/scripts/live-tv-fixture.py --port 28281`
starts and configures the existing isolated synthetic tuner; add `--listings` to
turn on listings from the fixture's own fake SiliconDust and picture host. Add
`--share-sources` for the separate Live TV Automatic integration test; pass its
loopback URL as `TEST_RUNNER_WMM_LIVE_AUTO_SERVER` to `make apple-test-sim` or
`make apple-tv-test-sim`. That test starts one Auto session, drives a lower
output with synthetic player measurements, waits for AVPlayer to decode both
outputs and acknowledges the new generation. The fixture refuses an occupied
port, waits for its own child to report ready, and uses a temporary database and a
fictional MPEG-2/AC-3 broadcast, never a discovered tuner. Sign in as `admin` with
`fixture-admin-1`, or `viewer` with `fixture-viewer-1`. It adds `tuner.test` and
allows both accounts to watch. Ctrl-C stops only its own process group and lets
the fixture remove its temporary database; `--smoke` verifies setup and exits.
Use a fresh install on a separate named simulator and pass it as `SIM=`. The opt-in
Live TV UI checks in `UITests/LiveTVTests.swift` use
`TEST_RUNNER_WMM_LIVE_SERVER=http://127.0.0.1:28281`; they verify the fixture version
before signing in. `TEST_RUNNER_WMM_LIVE_PAUSE_EXPIRY=1` adds the full five-minute
pause check. They check advancing video, channel changes, tuner release, exact
app-sign-in revocation and reconnect. The same server variable enables the native
AVPlayer live-edge integration test in `make apple-test-sim`. With `--listings`,
`UITests/LiveExtrasFixtureTests.swift` and `TVUITests/LiveExtrasFixtureTests.swift`
check that stars, reminders and the lead time reach the fixture, and on
iPhone that another sign-in's changes come back through the server's events. Ordinary file
end-to-end checks still use
`make apple-e2e SIM=<name> E2E_SERVER=localhost:<isolated-file-server-port>`.

For Apple TV, use `TV_SIM=<owned-name>` with `make apple-tv-test-sim`. The opt-in
`TVUITests/LiveTVSyntheticTests.swift` tests require a fresh owned app install.
`TEST_RUNNER_WMM_LIVE_SERVER` enables synthetic pairing, moving video, native
panel actions and tuner cleanup. `TEST_RUNNER_WMM_TV_FILE_SERVER` enables the
ordinary-file check against an isolated server named `Apple Live File Regression`.
Both refuse non-loopback servers and verify the expected fixture identity before
sign-in. Run actual UI checks serially against a fixture with no other test
sessions; the native AVPlayer integration can be selected separately. The TV
recording reports which native AVKit panels/actions the directional test remote
could reach; physical touch-surface, remote, AirPlay and PiP checks remain separate.

`TEST_RUNNER_WMM_TV_REMOTE_PAUSE=1` additionally enables the strict live and file
remote-pause checks, with the corresponding fixture URL. These require a sustained
paused session and frozen video before checking resume; a failed pause remains a
test failure. On the owned tvOS 27.2 simulator, XCUIRemote Play/Pause failed this
check for both Live TV and the unchanged file player. Native Select also failed
the live check. Programmatic AVPlayer pause and resume work, but hardware remote
pause and Go live still need a real-device check.

### Live TV recordings

On API level 19 servers with recording enabled, people allowed to record get
Record beside the reminder controls and Recordings in Live TV. A programme of
a series (one with the listing's series key) offers just this episode or every
new episode; Options set new-only, channel, retention and minute padding. A film
or special records at once. When the tuners are booked, the clash shows at once
(a sheet on iPhone and iPad, full screen on Apple TV) and asks which recording,
not yet recording, gives way; Keep leaves the new one under Won’t record, whose
rows offer the same choice. The guide marks a programme that will record (a red
dot, ringed for a series), REC while recording and a hollow orange dot for a
clash; anything else offers Record again with its reason's words. Marks follow
`live.recordings.changed`: the span on screen is asked for again in one request. iPhone and iPad also make manual recordings by channel and time,
including daily, weekday and weekly repeats in the device’s time zone. Apple TV
uses a full-screen record choice and keeps manual scheduling on the phone or web.
Admins set folders and recording access in the server’s web app.

Recordings shows Recording now, Won’t record, Upcoming, Series and Recorded,
hiding empty sections; on iPhone and iPad a swipe left skips, stops or doesn't
record one. Each section loads 20 at a time with Load more; a
`live.recordings.changed` event refreshes the sections on screen (a screen
says which with `LiveRecordingsStore.show(_:)`; one that has left is
forgotten and loads again on return) and the guide's span while
`showGuide(from:to:)` holds it, with no recording-status polling. The echo of
a change made on this device asks for nothing more. Only the
setter or an admin can cancel upcoming recordings, stop active ones or edit a
series. Recording-now progress comes from the clock. Watch from the start reuses
the ordinary player with a Recording pill, an extending duration and Go to now
(shown more than 10 s behind). The length grows from the server's recorded
length on the device's monotonic clock; at the planned end, or when the stream
ends early, the pill and Go to now go and the play carries on. It sends
progress against the recording until the server links its library file.
Growing recordings stay on this device; finished files use ordinary downloads,
Cast, AirPlay, resume and watched behavior.

A live viewer sees the server’s warning before a recording takes their tuner,
with OK and, for the setter and admins, Don’t record it. On Apple TV the warning
never takes focus; a swipe down puts Don’t record it first in Channels. Stopped
for a recording offers Watch it from the start. Recorded files show their channel and start time on
episode and movie pages. Delete appears only when the server’s existing delete
policy permits it and confirms permanent file removal, with Don’t record it
again if it’s shown again ticked by default (sent as `recordAgain`, also from an
episode's page); deleting a rule keeps its finished files. Ad-break
segments use the existing Skip Ad controls and the person’s saved Ads choice;
the default offers the button, and an explicit Never stays Never. The server’s
ad detection is a separate opt-in until its real-broadcast accuracy is verified.

`-fakes -fakesLiveRecordings -open watchmymedia://live-tv/recordings` previews the
fictional DVR household on all Apple clients; add `-fakesMember` for ownership
restrictions. The unit tests exercise permission and old-server gates, pagination,
event invalidation, account-switch fencing, manual validation, growing clocks,
playback/progress routes and local playback while recording. The opt-in native
AVPlayer test uses `WMM_DVR_SERVER` with `swift test --filter
DVRAVPlayerIntegrationTests` in `apple/Kit` (or
`TEST_RUNNER_WMM_DVR_SERVER` for a simulator test run). It refuses writes unless a
loopback fixture identifies itself as `dvr-fixture-v1` before sign-in. Start one
with `cd server && go run ./cmd/livetvfixture -dvr -port 28298` from the
repository root. It uses disposable storage and fictional broadcasts; stop
only the fixture process you started.

The Mac menu shows Recording and keeps the computer awake while a recording is
listed by the loopback status endpoint. It does not schedule a wake: macOS’s
`IOPMSchedulePowerEvent` requires root, as documented by `IOPMLib.h` in the
installed SDK. Keep the server computer awake for future recordings. Simulator
and fictional broadcasts do not certify a physical tuner, remote, AirPlay,
Picture in Picture or real-broadcast ad detection.

## Keyboard, pointer, quick actions and coming back

What the iPhone and iPad apps take from the platform without a screen of
their own:

- **A hardware keyboard.** In either player, space plays or pauses (or
  answers Still watching) and Escape closes the picker or Channels, then
  leaves; in the film player left and right seek 10 s, in Live TV up and
  down change channel. The rules are `PlayerChromeModel.handle(_:)` and
  `LivePlayerModel.handle(_:)`; the apps have no volume or mute of their
  own, so there is no M. Escape is a key command on the player's view
  controller (`PlayerShell.swift`). ⌘F opens Search with the cursor in its
  field (`Navigator.findInSearch()`). `UITests/KeyboardTests.swift` presses
  space, the arrows and ⌘F on an iPad simulator; XCUITest's Escape never
  reached the app there, so Escape needs a real keyboard.
- **A pointer on iPad.** Cards (`CardButtonStyle`) lift and Search's and
  Settings' rows highlight under the pointer, as the system draws it.
- **Settings' tasks** take Return for their filled button and Escape for
  Cancel (`keyboardShortcut(.defaultAction)` and `.cancelAction`).
- **Quick actions and App Shortcuts** (`App/Shell/QuickActions.swift`):
  Continue watching (Home at its top), Search, Live TV when the server offers
  it and Downloads when they are on. Each opens a link published for the
  server signed in (`watchmymedia://home`, `search`, `live-tv`, `downloads`
  with `source=` and `server=`), so another server's opens nothing; signed
  out there are none and a shortcut opens the app as it is.
- **Coming back to where you were** (`App/Shell/PlaceKeeping.swift`,
  `SavedPlace` in the Kit): the tab and each tab's pages are saved per
  account and server whenever they change and restored once a launch; never the player or Settings, and a page a later version added
  ends its tab's pages there. A launch with `-open` or `-tab` goes where it
  says; on the fakes nothing is kept unless `-fakesRestorePlace` is given.
- **Recent searches** are kept between launches per account and server
  (`RecentSearches`); they show on iPad, as before.
- **A Live TV reminder's notification** offers Watch and Remind again,
  which notifies once more five minutes later.
- **Text size.** `PrimaryButtonStyle`, `GhostButtonStyle` and `Chip` scale
  their text and height with Dynamic Type and look as before at the default
  size; `UITests/TextSizeTests.swift` runs a movie's page and the
  Movies filters at the largest accessibility size. The guide and the
  player keep fixed sizes (Later).
- **Tapping the selected tab** goes back to its first page
  (`UITests/TabReselectTests.swift`).
- **Haptics** on taking the scrubber's thumb and on double-tap seeks.
- **Reduce Motion** turns the player's slides into fades and stills its
  spinner.
- **Pull to refresh** on Downloads syncs them.
- **A background refresh** (`App/Shell/BackgroundSync.swift`) sends what was
  watched offline, then checks for new episodes of the shows the person
  follows (New episodes, above). `project.yml` gives the app
  `BGTaskSchedulerPermittedIdentifiers: [media.watchmy.client.sync]` and
  `UIBackgroundModes: [fetch]`; without them it does nothing.

## Top Shelf

With the app in the top row of the Apple TV's Home Screen, the Top Shelf
shows Continue watching (16:9 cards with the system's progress bar) and
Recently added (posters, movies and shows in turn) as two sections the
system draws. Select
opens the page; Play/Pause plays, as on the card in the app: a Continue
watching item resumes, a movie plays its first version, and a show, which has
no single episode to play, opens. Signed out, or with nothing to show, the
system shows the static Top Shelf image from `TV/Resources`.

- **The extension only reads.** `TopShelf/` is the target "WatchMyMedia
  TopShelf" (`media.watchmy.client.topshelf`), embedded in the Apple TV app.
  It links only `WMMTopShelf` (Foundation and TVServices) and reads the
  snapshot the app wrote into their App Group, `group.media.watchmy.client`
  (`TV/WatchMyMediaTV.entitlements`, `TopShelf/TopShelf.entitlements`). It
  has no sign-in and makes no request.
- **The app writes it.** Home publishes whenever its rows change
  (`.publishesTopShelf` in `TV/Screens/Home/HomeScreen.swift`, then
  `TopShelfPublisher` in WMMFeatures): the rows the person shows on Home (a
  row hidden from Home isn't on the shelf), ten Continue watching and twelve
  Recently added, each picture fetched with the app's sign-in and saved as a
  file in the group, then `TVTopShelfContentProvider.topShelfContentDidChange()`.
  Signing out, or another person or server signing in, clears it
  (`.clearsTopShelfOnAccountChange` in `TV/Shell/WatchMyMediaTVApp.swift`);
  so does a launch that finds someone else's snapshot.
- **What it may hold** (`Kit/Sources/WMMTopShelf`, `TopShelfRules`): titles,
  the app's own `watchmymedia:` links published for the server signed in
  (`source=top-shelf&server=`) and bare picture file
  names. Never a server address, an image's web address, a token or the
  person's ID (the owner is a hash). Links with other query items and
  pictures that aren't bare names are dropped on both sides.
  `Kit/Tests/WMMFeaturesTests/Browse/TopShelfPublisherTests.swift` checks
  that nothing written names the address, port or token.
- **Staleness**: the extension shows nothing from a snapshot older than two
  weeks (`TopShelfRules.maxAge`) or of another format; a shelf left on
  screen is written again after a day.
- **Links**: `watchmymedia://movies/{id}?play=1` plays a movie, and only
  when the app published it for the server signed in; from anywhere else it
  opens the movie's page.

On a simulator, run the app once on the fakes (`-fakes`) so it writes a
snapshot, then go to the Home Screen with the app's icon focused in the top
row to see the shelf (apple/AGENTS.md, Simulators). The
fakes have backdrops but no posters, so Recently added draws blank cards there.
Signing for a device needs the App Group and the extension's App ID in the
developer account; simulator builds are signed ad hoc and need neither.

## Release builds, privacy and measuring

**Release builds.** `make apple-build-release` and `make apple-tv-build-release`
build the Release configuration for devices without signing (what an archive
compiles), and CI's `apple` and `apple-tv` jobs run them between building the
tests and running them, so a
break that only shows in Release (an `#if DEBUG`, optimisation, a missing
import) shows before a tag. The tests run whether or not it passed. `scripts/check-release-binary.sh` then fails if the
app has no privacy manifest, holds any `WMMFakes` symbol or still reads
`-fakesSignedOut`, or if an extension in its `PlugIns` (the Top Shelf) has no
privacy manifest of its own or holds code from `WMMModel`, `WMMData`,
`WMMAPI`, `WMMPlayback`, `WMMFeatures` or `WMMFakes`.

**The fakes are debug-only**: every
`import WMMFakes` in `App/` and `TV/`, the fakes' branch of each
`ServicesFactory` and the `#Preview`s that use them sit under `#if DEBUG`.
`project.yml` keeps `WMMFakes` as a dependency (so it builds and the import
resolves) with `link: false`, and only Debug's `OTHER_LDFLAGS` links its
object file: Xcode links a package product's whole object file, so without
that a Release binary carried all of the fakes. The UI tests, `make apple-perf`
and the App Store screenshot recipe all run Debug builds.

**Privacy manifests**: `App/Resources/PrivacyInfo.xcprivacy`,
`TV/Resources/PrivacyInfo.xcprivacy` and the Top Shelf's
`TopShelf/PrivacyInfo.xcprivacy`, which declares no API: it reads files and
no file dates, and the snapshot's age is a date inside it. No tracking and no collected data; the
required-reason APIs with Apple's reasons: UserDefaults `CA92.1`
(`DeviceStorage`), system boot time `35F9.1` (`ProcessInfo.systemUptime` in the
players and `PlayerChromeModel`), and on iPhone and iPad disk space `85F4.1` and
`E174.1` (Downloads shows the free space and refuses a download that won't
fit). The Apple TV app has no downloads, so it doesn't declare disk space. When
code starts calling another API on [Apple's list](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api),
add it to the manifest of each app that links that code.

**Signposts**: `PerformanceSignposts.shared`
(WMMModel) marks the intervals Instruments and `XCTOSSignpostMetric` measure,
under subsystem `media.watchmy.client` and category `Performance`: Launch to
Home, Play to first frame, and its two halves Play to decision and Decision to
first frame. Its calls are safe in any order: an end without its begin does
nothing, and a new Play ends an open one as cancelled.

**Measuring**. `make apple-perf` runs
`UITests/PerformanceTests.swift` (a signed-in launch, flinging Home and the
Movies grid of `-fakesLargeLibrary`'s 1,200 titles) on `SIM` and `TVUITests/PerformanceTests.swift` (a launch) on
`TV_SIM`, five runs each, into `build/Perf.xcresult` and `build/TVPerf.xcresult`.
They skip unless `WMM_PERF=1`, which the target sets, so the normal test runs
and CI leave them out. They run on the fakes, so in Debug builds: compare runs
with each other on one quiet Mac, not with a release on a device. On a
simulator the scrolling measure reports only the fling's duration (hitches
need a device), and on the tvOS simulator the launch metric reports nothing,
so the Apple TV test also times `launch()` with a clock.

**Diagnostics**.
`Diagnostics.start()` (WMMData) subscribes to MetricKit on iPhone
and iPad and keeps the last launch, hang and crash summaries, and the last
day's count of exits in the background other than normal ones ("1 for
memory"), in Application Support/Diagnostics, excluded from backups; MetricKit
isn't on tvOS, so on Apple TV it does nothing. `Diagnostics.text(server:servers:names:)`
builds what a Copy diagnostics row copies: the app version and build, the
hardware model and OS, the server's version and API level, the summaries and
this run's log lines from the app's own subsystem (a process can read only its
own log). Nothing leaves the device and no SDK is added. The rule for what may
be in it:

- Never a token, password, server address, or a person's, server's, device's or
  title's name. The server appears only as its version and API level; the
  summaries keep a crash's type, signal and top frames as binary and offset,
  never an exception's message.
- Every line passes `DiagnosticsRedactor`: known names (the remembered
  servers' names, hosts and accounts, plus any the caller passes) become
  `<name>` as whole words, taking a host's port with them; quoted text `"<text>"`; URLs, e-mail, IP addresses and host names
  `<address>`; file paths `<path>`; the value after `token`, `password`,
  `authorization` and the like, UUIDs and long opaque strings `<redacted>`.
  `Kit/Tests/WMMModelTests/DiagnosticsTests.swift` holds the cases.
- No disk space or system uptime: their required reasons don't allow them in
  a report without a reason the manifests don't declare.

On iPhone and iPad, Settings › About has a Diagnostics row under Help, with how
many reports are kept, and its page (`App/Screens/Settings/DiagnosticsSettingsPage.swift`)
shows what Copy puts on the clipboard, in groups: the app, this device and the
server; iOS's last reports; and the last 200 log lines. Every value on the page
went through the same redactor as the copy (`DiagnosticsPage` in WMMModel, made
once as the page opens), which also knows this device's name, the account's,
the servers found on the network and the titles downloaded here. Copy turns
into Copied and a short Copied note shows (`DiagnosticsModel`); there is no
Share. `DiagnosticsPageTests` and `DiagnosticsModelTests` hold the page's
rules, including that nothing on it or in the copy names the server, the
account or a title. `-diagnosticsReports` keeps a fictional hang, launch and
exits report (the simulator gets none from MetricKit), `-diagnosticsNoReports`
forgets them, and `-diagnosticsLog` logs a line naming the fake server, which
the page must show redacted (`UITests/DiagnosticsTests.swift`, run with the
WatchMyMediaE2E scheme and `-only-testing`). Log lines carry the device's own
time, as the reports do.

On Apple TV, which has no clipboard to paste from, Settings › About's Help
group has a Diagnostics row: it shows the redacted
text as a QR code made on the Apple TV, beside a summary of what it holds,
and Show as text pages through all of it (Down and Up turn the pages).
`DiagnosticsCode` (WMMFeatures) decides what the code holds: at most
`DiagnosticsCode.maxBytes` (997 bytes, a version 25 code at error correction
M, 117 modules a side, which a phone reads from a sofa), leaving out the
oldest log lines first and saying how many. `Kit/Tests/WMMUITests/DiagnosticsCodeSizeTests.swift`
pins that size with CoreImage. `-diagnostics` with `-tab settings` opens the
code at launch in a debug build, and `-diagnosticsLog` adds sixty made-up log
lines, enough to fill the code and turn pages (`TVUITests/DiagnosticsTests.swift`).

## Layout

| Path | What |
|---|---|
| `Kit/` | Swift package `WatchMyMediaKit`; builds for iOS, tvOS and macOS, so `swift test` runs on the Mac |
| `Kit/Sources/WMMModel` | Domain types and the protocols every screen builds against (`ServerRepository`, `AuthRepository`, `LibraryRepository`, `RowsRepository`, `PreferencesRepository`, `PlaybackController`, `PlaybackGateway`, `AppServices`), errors, what a server offers at its API level (`ServerFeatures.swift`, the one table of levels), and the shared wording (`Formats.swift`, `TrackLabels.swift`, `Watching.swift`). Mirrors `android/core/model` |
| `Kit/Sources/WMMAPI` | The client generated from `../api/openapi.json` (only the operations an app makes, see the config); committed. Generation opens response enums while keeping request-only enums strict |
| `Kit/Sources/WMMData` | The real repositories. `Network/ServerAPI.swift` wraps the generated client: bearer token, user agent, and every failure as `APIError` or `SignedOutError`. Every request that carries a sign-in is built by its `RequestSigner` (the token only on the issuing server's addresses), and every 401 is read by `ServerAPI.revokes`, which signs out through the `ServerAPI` that `LocalStore.api(serverID:…)` made |
| `Kit/Sources/WMMPlayback` | The `PlaybackController` on `AVPlayer` (`RealPlaybackController` split by part into `RealPlaybackController+*.swift`) and the Live TV controller (`LivePlaybackEngine`), with what both share: `PlayerCore` (an item, its first frame, the access log, the metadata, the audio session, the failure classifier), `TokenKeeper`, `SwitchGenerations`, `HeartbeatPump`, `Ticker`; the device profile, WebVTT cues, Now Playing |
| `Kit/Sources/WMMDownloads` | Offline downloads on iPhone and iPad: the system's background sessions (`SystemDownloadEngine`), the offline store, sync, and `OfflinePlaybackGateway`, which plays a download from the device. Mirrors `android/core/downloads`; the Apple TV app doesn't link it |
| `Kit/Sources/WMMCast` | SDK-independent Cast protocol, session state, transfers and fakes; the Google SDK adapter lives only in `App/Cast/` |
| `Kit/Sources/WMMFakes` | In-memory fakes of every protocol, with the mockups' library and a pretend player (`AppServices.fake()`) |
| `Kit/Sources/WMMUI` | Brand tokens (`Palette`), `Artwork`, `Badge`, `Tag`, `ProgressStrip`, `WatchedCheck`, `BrandMark`, button styles, `Chip`, `LoadableView` and the empty and error states |
| `Kit/Sources/WMMTopShelf` | The Apple TV Top Shelf's snapshot in the App Group: its format, what it may hold (`TopShelfRules`), the store, and the sections the system draws. Foundation and TVServices only, so the extension links nothing else |
| `Kit/Sources/WMMFeatures` | Screen models and navigation, shared with the Apple TV app: `Navigation/` (`Navigator`, `Route`, `AppTab`, `DeepLink`), `Browse/` (Home, Library, Show, Movie, Search), `Account/` (server picker, sign-in and pairing, Settings with its hub, words and saving states, Link a TV, Servers and Add a server, Change password), `Player/` (the controls' timing, double-tap seek, the scrubber, the picker's wording, and `TVPlayerModel` for the Apple TV's system player) |
| `App/` | The iPhone and iPad app: `Shell/` (app, root flow, tabs, `ServicesFactory`), `Screens/<feature>/` (SwiftUI views; a screen lays itself out for a regular width with `PadLayout` from `Components/Layout.swift`), `Components/Browse` and `Components/Account` (views those screens share) |
| `TV/` | The Apple TV app, target "WatchMyMedia TV" (tvOS 18+, the same bundle ID for the universal purchase): `Shell/` (app, root flow, the tab bar, `TabPage`, its own `ServicesFactory` with the TV profile), `Screens/<feature>/`, `Components/` (focus, buttons, chips, cards, rows), `Resources/` (the layered icon and Top Shelf images from `brand/`) |
| `TopShelf/` | The Apple TV app's Top Shelf extension, target "WatchMyMedia TopShelf": `ContentProvider` reads the snapshot (see Top Shelf), its privacy manifest and entitlements |
| `UITests/` | The end-to-end test against a real server (`make apple-e2e`), the iPhone and iPad UI tests on the fakes, and the performance tests (`make apple-perf`) |
| `TVUITests/` | The Apple TV app on the fakes, driven with the Siri Remote, and its launch measure (`make apple-perf`) |
| `Tools/` | The OpenAPI generator, kept out of the app's dependencies |

Why `TV/` is a folder of its own beside `App/` rather than one `App/` with
views that switch on the platform: the 10-foot screens share no layout with
the touch ones (focus instead of touch, the tvOS tab bar, rows that
remember their focus, a system player), so a shared view would be two views
behind an `#if`. What both apps need lives in the Kit instead: the screen
models and wording in `WMMFeatures` (with the Apple TV variants as options,
such as `SignInModel(pairsByDefault:)`), the
brand views and the QR code in `WMMUI`. The two targets share no files.

## Conventions

- **Views are thin.** A screen's state and actions live in an
  `@Observable @MainActor` model in `WMMFeatures` (`HomeModel`,
  `ShowPageModel`…) built only on `WMMModel` protocols, never on `WMMData` or
  `WMMPlayback`; the view in `App/Screens` renders it. Models are tested in
  `Kit/Tests/WMMFeaturesTests` against the fakes with
  `FakeLatency.duration = .zero`.
- **Services and navigation come from the environment**:
  `@Environment(AppServices.self)` and `@Environment(Navigator.self)`. A
  screen opens another with `navigator.open(.show(id:))` and plays with
  `navigator.play(request)`; it never builds a `NavigationLink` destination
  itself. The player is a full-screen cover over the tabs.
- **Errors**: repositories throw `APIError` (the server's message, ready to
  show), `ServerTooOldError` or `SignedOutError`. Screens show
  `error.displayMessage`; `SignedOutError` clears the sign-in and the root
  shows sign-in again by itself.
- **Wording is shared**: dates, durations, sizes, track and version labels
  come from `WMMModel` helpers, worded as the Android app words them, so the
  apps read alike. Add a helper there (with a test) rather than formatting
  in a view.
- **Images**: `Artwork(url: services.library.imageURL(path, size: .w342), seed: title)`;
  the loader in the environment adds the sign-in, and the gradient shows
  until the image arrives. Posters `w342`, backdrops `w1280`, stills `w300`.
  `maxPixel` is the longer side in pixels as drawn (points times the screen's
  scale); `ImagePipeline` fetches and decodes off the main actor, shares a
  load under way, and keeps decodes by address and size step in a byte budget
  (128 MB, 192 MB on Apple TV), so a small decode never stands in for a large one.
- **Look**: dark only. `Palette` for colours (gold for progress, selection
  and badges; burgundy for primary fills; orange for warnings), the system
  font with Dynamic Type, 44 pt touch targets, real `Button`s with
  accessibility labels on icon-only ones.
- **Changes come from the server's events**:
  `LibraryRepository.libraryChanges()` says what changed (`LibraryChange`:
  which movies or episodes were marked, which library changed, a scan). A
  screen model follows them through `ScreenChanges`, which keeps listening
  while the screen is away, so coming back loads nothing unless something
  changed meanwhile or the screen is five minutes old. A mark made here
  reloads every screen through its own notice: mark through `markWatched`
  (`WMMFeatures/Browse/MarkWatched.swift`), which may draw the mark at once
  or keep it with a download, and never reloads after it. The server's echo
  of it is dropped. A screen's own loads go through `LoadController`: the
  first shows a spinner, a later quiet one keeps the screen and says why it
  failed as a message, and the newest wins.
- **Concurrency**: Swift 6 language mode. Repositories, models and the
  controller are `@MainActor`; network work suspends off it.
- **Apple TV**: every page is a `TabPage` (a scroll view from the screen's
  edges, the artboards' 90 pt gutter, the lockup beside the tab bar, whose
  last tab is the account). Buttons, cards and rows draw the focus themselves from
  `isFocused` (`TVButtonStyle`, `TVChipStyle`, `focusRim`): the system's
  lift and shadow plus the gold rim. A row of cards is a `ShelfRow`, a
  focus section that remembers its focused card. Every row of cards shows
  where it starts (`RowStartScrolling`, as Android TV's rows do): moving
  back, the card before the focused one stays whole in view, so at the
  start focus itself moves into the first place; cards scrolled past fade
  at the screen's edge; and Left on the first card, which has nowhere to
  go, gives the row a small bump, with the panel's hint offering only ▶
  there. A row that isn't a `ShelfRow` (the Live TV rows, the show page's
  episodes) puts `rowStartCard` on its cards and the modifier on its
  `ScrollView`. Secondary actions are a long-press `contextMenu`.
- **Tests**: Swift Testing. Run `make apple-test` before handing work back,
  `make apple-test-sim` when the iPhone app or anything UIKit changed, and
  `make apple-tv-test-sim` when the Apple TV app did.
