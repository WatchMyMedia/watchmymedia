# WatchMyMedia for iPhone, iPad and Apple TV

The iOS app, the Apple TV app, and the Swift package both build on. The
plan is [PLAN-IOS.md](../PLAN-IOS.md); the iPhone screens follow the mockups
in [design/mockups/ios](../design/mockups/ios) (the canvas is linked from
PLAN-IOS.md §9), the Apple TV screens those in
[design/mockups/apple-tv](../design/mockups/apple-tv) (§11.2).

## Build and run

Needs Xcode (26 or newer, with an iOS simulator runtime) and XcodeGen 2.45.4
or newer (`brew install xcodegen`). From the repository root:

```bash
make apple-test       # the Kit's unit tests, on this Mac (seconds, no simulator)
make apple-build      # generate the project and build the app for the simulator
make apple-test-sim   # build and run every test on the iPhone 17 Pro simulator, or the newest iPhone Pro (SIM=… for another)
make apple-api        # after `make gen-api`: regenerate Kit/Sources/WMMAPI/Generated; commit it
make apple-e2e        # the app end to end against a server (default: the fixture server at localhost:27588)
make apple-tv-build   # build the Apple TV app for the simulator
make apple-tv-test-sim # build it and run every test on the Apple TV 4K simulator (TV_SIM=… for another)
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

`make apple-gen` writes `WatchMyMedia.xcodeproj` from `project.yml`; open it
in Xcode to run on a simulator or a device. The project and `App/Info.plist`
are generated and not committed: change `project.yml` instead. `App/`, `TV/`,
`UITests/` and `TVUITests/` are synced folders, so Xcode builds whatever files
they hold: after a pull that adds or removes files the open project needs
nothing, and only a change to `project.yml` needs `make apple-gen` again.

The project's version is a placeholder, 1.0 (1). Before archiving in Xcode
(Product → Archive), generate it for the release, `make apple-gen
VERSION=v1.2.3`, which writes that tag's App Store version (`v0.5.2` was 1.5.2,
`v1.0.0` was 1.6.0, `v1.3.0` is 1.7.0: it stays on 1.x, since the App Store's first release was 1.0)
and its build number (`scripts/version.sh`) into the project; a plain `make apple-gen` puts the
placeholder back. `make apple-archive VERSION=v1.2.3` sets them itself.
The Apple TV app (the WatchMyMedia TV scheme) can't be archived in Xcode until
an Apple TV is registered to the team, because Xcode's archive step signs with a
tvOS development profile. `make apple-tv-archive VERSION=v1.2.3` archives it
unsigned instead, and `make apple-tv-upload VERSION=v1.2.3` signs it for the App
Store when exporting and uploads it to TestFlight.

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
(`DeepLink.opening`, PLAN-APPLE-REVIEW.md §2.5):

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
(PLAN-HOME.md §7) can be judged on a simulator. `-fakesSignedOut` starts at sign-in, `-fakesNoServer` at the
server picker. The scheme's Run action lists `-fakes`, off by default.
`-fakesLargeLibrary` gives the fakes' Movies library about 1,200 titles (the
sample's, then made-up ones), for measuring the grid.
`-search harbor` searches at launch. The fake player has seek thumbnails drawn in
code, so `-open watchmymedia://play/9101 -playerKeepControls -playerScrub 0.44`
holds the scrubber at 44 % with its tile up (PLAN-TRICKPLAY.md T4).

The fakes' server is at API level 6, so requests, missing episodes
(PLAN-ARR.md) and deleting (PLAN-EPISODE-PAGE.md) show: Harbor Lights' season 2
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

A show is one page on every Apple device ([PLAN-SHOW-PAGE.md](../PLAN-SHOW-PAGE.md);
`ShowPage`, `Pad2ShowPage` and `TvShowPage`): the episode on top, the season
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

On an iPad the app has layouts of its own wherever the width is regular
([PLAN-TABLETS.md](../PLAN-TABLETS.md), `design/mockups/ipad-revised/`): full screen in either
orientation, or a window wider than half the screen. A compact width (every
iPhone, Slide Over, a 50/50 split) shows the iPhone screens, so check both:
run on the iPad Pro 11-inch simulator, where `-fakes` signs in as Sam’s iPad,
and resize its window from its corner.

At 1000 pt, Home puts Continue watching beside the hero, Library shows its
filter panel, and Show/Movie details and Search use two panes. Title-sorted
libraries offer an A–Z index when the server supplies it. In portrait the
player shows the season below the video; in landscape it fills the screen.
Audio, Subtitles and Quality open a side panel and keep the video playing.
Settings keeps server selection and sign-in inside its split view.

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
Audio, Subtitles and Quality tabs, which need a server.

The Apple TV simulator takes the Mac's keyboard as the Siri Remote: the
arrows move focus, Return selects, Escape is Menu, and a long press is
Return held down. `apple/TVUITests` drives the remote in XCUITest on the
fakes (where focus lands, the long-press menus, Menu going back, and the
player's Next Episode card, Still watching and stop and can't-play states)
and runs with the rest in `make apple-tv-test-sim`. XCUI does not see
AVKit's contextual actions, so Skip is checked by hand.

### Downloads

From API level 10 an iPhone or iPad downloads movies and episodes to watch
offline (PLAN-DOWNLOADS.md, D3 as built): Download on a movie's or an
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

A movie page's Trailer plays the first trailer the server lists (PLAN-TRAILERS.md
§6.3; servers from API level 20 list them). A trailer file plays in our player.
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
screen ([PLAN-ROWS.md](../PLAN-ROWS.md) §8.3, HR6 as built; the boards are
`HomeEditRows`, `AddRow`, `ListPage` and `LibraryRows` in `ios/`,
`Pad2HomeEditRows` and `Pad2LibraryRows` in `ipad-revised/`, and
`TvSettingsHomeRows`, `TvListPage` and `TvLibraryRows` in `apple-tv/`). Home
asks for `arranged=true` and draws `HomeData.rows` in the person's order by
style (`ArrangedRow`, `TVArrangedRow`), the app's own Live TV row in the
`live` slot, and skips a style it doesn't know. On iPad Continue watching sits
beside the hero only while it is the first row drawn. Movies and TV Shows put
the library's rows above the grid (`getLibraryRows`), then Edit rows and the
grid as All movies or All shows; a title search hides them, and a library
row's See all sorts and filters the grid below. Older servers keep today's
fixed rows, and their library screens have none.

Home rows (Edit Home at the end of Home, Settings › Home rows, and Edit rows
on a library) is one sheet on iPhone and iPad: drag handles, Hide and Show in
place, Add a row (the hidden rows by kind, and the line that lists are made on
the web), Reset to default; Done saves the whole order. On Apple TV it is a
full-screen sheet from Settings › Home rows (and Edit rows on a library) with
Move up, Move down and Hide, an Add a row menu and Reset, saving each change.
`RowEditorModel` holds the rules. A list row's See all opens the list's page
(`MediaListModel`; `watchmymedia://lists/{id}`): every title in rank order,
missing ones dimmed with Request through the request sheet, Requested or
Downloading, and Not found for an imported title TMDB couldn't find. Apple TV
list rows end in a See all card. `rows.changed` and `lists.changed` refetch
Home, the library screens and the list page. There is no list builder: lists
are made on the web (Decision 10).

`-fakes -fakesRows` puts the fakes' server at level 22: Home has two list rows
and Trending this week waits in Add a row; Movies has Best of 2026, whose last
MDBList fetch failed. On iPad and Apple TV an arranged Home is drawn one row a
screen (PLAN-HOME.md §7; `-fakesRows` is needed to see it). `-editRows` opens Home rows at launch and `-addRow` its
Add a row; `-open watchmymedia://lists/9001` opens Top rated science fiction
(with titles to request) and `lists/9002` Rainy Sunday films (an import with a
title not found). On Apple TV, `-tab settings -homeRowsSettings` opens Home
rows. Add `-fakesMember` for a member, who can't delete or refresh the
household's lists.

## First run, audio languages and subtitles

Empty Home explains whether the server has no libraries, is scanning, could not
read a library, or finished a scan without finding media. Admins get **Open the
web page** on iPhone/iPad and its address on Apple TV; members get instructions
to ask the server owner. Scan transitions refresh it, and Check again remains
available. `-fakes -fakesFirstRun noLibraries` (or `scanning`, `failed`, `empty`)
previews the states; add `-fakesMember` for the member wording.

Settings › Audio & subtitles (PLAN-LANGUAGES.md §5.6, L4 as built) is one page
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
it. A language's Move up, Move down and Remove are in a dialog from its row
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
display these transitions. Repository boards in `design/mockups/ios/`,
`ipad-revised/` and `apple-tv/` show recovery and rollback states; they are
pending sync to the source canvases. The L5 Live TV boards in those same folders
show the selected Automatic mode, the observed Original or converted output and
the short preparation note. The Cast remote offers Live TV Automatic only when
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

The new screens follow [approved repository boards](../design/proposals/ios-completion/README.md),
not yet synced to the Apple canvas. Simulator/fake transport checks do not prove
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
shows programme listings ([PLAN-LIVE-TV-GUIDE.md](../PLAN-LIVE-TV-GUIDE.md)
§6.4). The guide is a grid of channels against an hour (iPhone) or two (iPad,
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
the listings. Without listings every screen stays as L8 and L9 built it.

On servers at API level 15 with listings (§6.5, G9), each channel in the guide
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

On iPhone and iPad a reminder is a local notification (`LiveReminderNotifier`):
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
(`App/Screens/Player/PlayerShell.swift`, PLAN-APPLE-REVIEW.md §5.3): the status
bar and home indicator hidden, the scene phase and Picture in Picture (2 s for
it to start by itself in the background, `PlayerChromeModel.pictureInPictureGraceMs`),
the safe area for the camera housing, and controls that hide 3 s after the last
touch while playing (`ControlsAutoHide`, tested in the Kit; up for VoiceOver,
and for Channels and the Quality menu on Live TV). `-playerKeepControls` keeps
either player's controls up for screenshots and UI tests.

Quality is fixed at 1080p, 720p, 480p or 360p. Mobile data caps Live TV at 720p,
including when the connection changes during playback. AirPlay and Picture in
Picture use the live player; backgrounding without either stops the live session.
The AirPlay receiver must be able to reach the selected server address; live
playback does not perform the file player's LAN-address handoff. Simulator playback
does not certify physical AirPlay, PiP, cellular networks or Apple TV remotes.

Apple TV uses AVKit's native Play/Pause and a linear live transport. Ch−, Ch+
and Go live are contextual actions; Stop is in the transport bar. Swipe down
opens Channels and Quality while video continues. Back closes an open native
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
their own (PLAN-APPLE-REVIEW.md §6.3):

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
  size (§2.8); `UITests/TextSizeTests.swift` runs a movie's page and the
  Movies filters at the largest accessibility size. The guide and the
  player keep fixed sizes (Later).
- **Tapping the selected tab** goes back to its first page
  (`UITests/TabReselectTests.swift`).
- **Haptics** on taking the scrubber's thumb and on double-tap seeks.
- **Reduce Motion** turns the player's slides into fades and stills its
  spinner.
- **Pull to refresh** on Downloads syncs them.
- **A background refresh** (`App/Shell/BackgroundSync.swift`) sends what was
  watched offline. It stays off until `project.yml` gives the app
  `BGTaskSchedulerPermittedIdentifiers: [media.watchmy.client.sync]` and
  `UIBackgroundModes: [fetch]`.

## Release builds, privacy and measuring

**Release builds.** `make apple-build-release` and `make apple-tv-build-release`
build the Release configuration for devices without signing (what an archive
compiles), and CI's `apple` and `apple-tv` jobs run them after their tests, so a
break that only shows in Release (an `#if DEBUG`, optimisation, a missing
import) shows before a tag. `scripts/check-release-binary.sh` then fails if the
app has no privacy manifest, holds any `WMMFakes` symbol or still reads
`-fakesSignedOut`.

**The fakes are debug-only** (PLAN-APPLE-REVIEW.md §2.9): every
`import WMMFakes` in `App/` and `TV/`, the fakes' branch of each
`ServicesFactory` and the `#Preview`s that use them sit under `#if DEBUG`.
`project.yml` keeps `WMMFakes` as a dependency (so it builds and the import
resolves) with `link: false`, and only Debug's `OTHER_LDFLAGS` links its
object file: Xcode links a package product's whole object file, so without
that a Release binary carried all of the fakes. The UI tests, `make apple-perf`
and the App Store screenshot recipe all run Debug builds.

**Privacy manifests**: `App/Resources/PrivacyInfo.xcprivacy` and
`TV/Resources/PrivacyInfo.xcprivacy`. No tracking and no collected data; the
required-reason APIs with Apple's reasons: UserDefaults `CA92.1`
(`DeviceStorage`), system boot time `35F9.1` (`ProcessInfo.systemUptime` in the
players and `PlayerChromeModel`), and on iPhone and iPad disk space `85F4.1` and
`E174.1` (Downloads shows the free space and refuses a download that won't
fit). The Apple TV app has no downloads, so it doesn't declare disk space. When
code starts calling another API on [Apple's list](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api),
add it to the manifest of each app that links that code.

**Signposts** (PLAN-APPLE-REVIEW.md §4.3.1): `PerformanceSignposts.shared`
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

**Diagnostics** (PLAN-APPLE-REVIEW.md §6.4; the Settings row is still to be
designed). `Diagnostics.start()` (WMMData) subscribes to MetricKit on iPhone
and iPad and keeps the last launch, hang and crash summaries in
Application Support/Diagnostics, excluded from backups; MetricKit isn't on
tvOS, so on Apple TV it does nothing. `Diagnostics.text(server:servers:names:)`
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
  `<name>`; quoted text `"<text>"`; URLs, e-mail, IP addresses and host names
  `<address>`; file paths `<path>`; the value after `token`, `password`,
  `authorization` and the like, UUIDs and long opaque strings `<redacted>`.
  `Kit/Tests/WMMModelTests/DiagnosticsTests.swift` holds the cases.
- No disk space or system uptime: their required reasons don't allow them in
  a report without a reason the manifests don't declare.

## Layout

| Path | What |
|---|---|
| `Kit/` | Swift package `WatchMyMediaKit`; builds for iOS, tvOS and macOS, so `swift test` runs on the Mac |
| `Kit/Sources/WMMModel` | Domain types and the protocols every screen builds against (`ServerRepository`, `AuthRepository`, `LibraryRepository`, `RowsRepository`, `PreferencesRepository`, `PlaybackController`, `PlaybackGateway`, `AppServices`), errors, what a server offers at its API level (`ServerFeatures.swift`, the one table of levels), and the shared wording (`Formats.swift`, `TrackLabels.swift`, `Watching.swift`). Mirrors `android/core/model` |
| `Kit/Sources/WMMAPI` | The client generated from `../api/openapi.json` (only the operations an app makes, see the config); committed. Generation opens response enums while keeping request-only enums strict |
| `Kit/Sources/WMMData` | The real repositories. `Network/ServerAPI.swift` wraps the generated client: bearer token, user agent, and every failure as `APIError` or `SignedOutError`. Every request that carries a sign-in is built by its `RequestSigner` (the token only on the issuing server's addresses), and every 401 is read by `ServerAPI.revokes`, which signs out through the `ServerAPI` that `LocalStore.api(serverID:…)` made |
| `Kit/Sources/WMMPlayback` | The `PlaybackController` on `AVPlayer` (PLAN-CLIENTS.md §2.3; `RealPlaybackController` split by part into `RealPlaybackController+*.swift`) and the Live TV controller (`LivePlaybackEngine`), with what both share: `PlayerCore` (an item, its first frame, the access log, the metadata, the audio session, the failure classifier), `TokenKeeper`, `SwitchGenerations`, `HeartbeatPump`, `Ticker` (PLAN-APPLE-REVIEW.md §5.1); the device profile, WebVTT cues, Now Playing |
| `Kit/Sources/WMMDownloads` | Offline downloads on iPhone and iPad (PLAN-DOWNLOADS.md D3): the system's background sessions (`SystemDownloadEngine`), the offline store, sync, and `OfflinePlaybackGateway`, which plays a download from the device. Mirrors `android/core/downloads`; the Apple TV app doesn't link it |
| `Kit/Sources/WMMCast` | SDK-independent Cast protocol, session state, transfers and fakes; the Google SDK adapter lives only in `App/Cast/` |
| `Kit/Sources/WMMFakes` | In-memory fakes of every protocol, with the mockups' library and a pretend player (`AppServices.fake()`) |
| `Kit/Sources/WMMUI` | Brand tokens (`Palette`), `Artwork`, `Badge`, `Tag`, `ProgressStrip`, `WatchedCheck`, `BrandMark`, button styles, `Chip`, `LoadableView` and the empty and error states |
| `Kit/Sources/WMMFeatures` | Screen models and navigation, shared with the Apple TV app: `Navigation/` (`Navigator`, `Route`, `AppTab`, `DeepLink`), `Browse/` (Home, Library, Show, Movie, Search), `Account/` (server picker, sign-in and pairing, Settings, Link a TV), `Player/` (the controls' timing, double-tap seek, the scrubber, the picker's wording, and `TVPlayerModel` for the Apple TV's system player) |
| `App/` | The iPhone and iPad app: `Shell/` (app, root flow, tabs, `ServicesFactory`), `Screens/<feature>/` (SwiftUI views; a screen lays itself out for a regular width with `PadLayout` from `Components/Layout.swift`), `Components/Browse` and `Components/Account` (views those screens share) |
| `TV/` | The Apple TV app, target "WatchMyMedia TV" (tvOS 18+, the same bundle ID for the universal purchase): `Shell/` (app, root flow, the tab bar, `TabPage`, its own `ServicesFactory` with the TV profile), `Screens/<feature>/`, `Components/` (focus, buttons, chips, cards, rows), `Resources/` (the layered icon and Top Shelf images from `brand/`) |
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
- **Changes come from the server's events** (PLAN-APPLE-REVIEW.md §4.1.1):
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
  edges, the artboards' 90 pt gutter, the lockup and initial beside the tab
  bar). Buttons, cards and rows draw the focus themselves from
  `isFocused` (`TVButtonStyle`, `TVChipStyle`, `focusRim`): the system's
  lift and shadow plus the gold rim. A row of cards is a `ShelfRow`, a
  focus section that remembers its focused card. Secondary actions are a
  long-press `contextMenu`.
- **Tests**: Swift Testing. Run `make apple-test` before handing work back,
  `make apple-test-sim` when the iPhone app or anything UIKit changed, and
  `make apple-tv-test-sim` when the Apple TV app did.
