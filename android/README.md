# WatchMyMedia for Android and Google TV

One app, one APK: the TV UI on Android TV and Google TV (including the Google
TV Streamer), the phone UI on phones and tablets. The screens follow the
maintainers' designs.

Tablet layouts follow the tablet boards. At 600 dp the bottom bar
becomes a navigation rail, grids fit the available width, and dialogs and
single-column pages keep readable widths. At 1000 dp Home, Library, Search,
details and the Inbox use two panes. Settings puts its list of groups beside the
open group's page from 600 dp. Narrow windows retain the phone layout.
Tablet playback supports portrait, with episodes below the video, and landscape,
with Audio, Subtitles and Quality in a side panel that leaves the video playing.

## Build and run

Needs the Android SDK (platform 37) and a JDK 17 or newer; Android Studio's
bundled one works.

```bash
cd android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest        # unit tests, every module
./gradlew coverageReport           # the same, with coverage: build/reports/jacoco/coverageReport/html/index.html
./gradlew assembleDebug -PcomposeCompilerReports=true  # also writes each Compose module's compiler reports to build/compose-compiler/
./gradlew lintDebug
./gradlew :app:assembleRelease     # unsigned and minified by R8, as CI builds it
./gradlew :app:installDebug        # onto the running emulator or device
./gradlew :core:api:regenerateApi  # after `make gen-api`: rewrites core:api's generated client; commit it
```

Debug builds include [LeakCanary](https://square.github.io/leakcanary/): when
an activity, ViewModel or service outlives its screen, it dumps the heap (the
app pauses for a few seconds), posts a notification and lists the leak in
its Leaks app, a second launcher icon on phones and TVs. Release builds don't
include it.

`local.properties` (not committed) holds `sdk.dir`. For a TV emulator, add an
Android TV or Google TV system image in the SDK Manager and create a TV AVD.
Without one, `-PforceTv=true` shows the TV UI on any emulator (the Pixel
Tablet AVD in landscape works well); drive it with the D-pad keys, for
example `adb shell input keyevent DPAD_RIGHT` and `DPAD_CENTER`. Send one key
before tapping anything: a phone emulator starts in touch mode, where nothing
takes focus, and a TV never is.
`-PappIdSuffix=.something` installs a debug build beside others on the same
device, so several people (or sessions) can test at once:

```bash
./gradlew :app:installDebug -PforceTv=true -PappIdSuffix=.tvbrowse
adb shell am start -n media.watchmy.client.tvbrowse/media.watchmy.client.MainActivity
```

The app talks to a real server. To try it on the synthetic test library,
run `make fixture` once and then `make fixture-serve` from the repository
root: the server listens on port 27588 and the test accounts are at the top
of `deploy/fixture/fixture.sh`. An emulator reaches the Mac at `10.0.2.2`, so
enter `10.0.2.2:27588` in the server picker.

`-Pfakes=true` runs on the fakes in `core:fake` instead (the mockups'
fictional library with a few fictional backdrops from `core/fake/src/fakes/assets`,
no server): the TV shows a pairing code and signs itself
in after a few seconds, and the phone takes any name with the password
`password`. Any name but Alex signs in as an admin; Alex is a member, whose
requests wait for approval. On the fakes the server takes requests:
search for "harbor" for the Not in your
library row, and Copperline lists its missing episodes in every state the
boards draw. Add `-PfakesSeerr=true` to exercise a Seerr-only server: requests
go to a separate fake Seerr list, while admins can still approve earlier
local requests and resolve local reports in the Inbox. Its Open Seerr link
uses the reserved `seerr.example` hostname. The fake servers are at the current API
level (`FAKE_SERVER_API_LEVEL`); a test that selects one at a lower level
gets what that level offers, as from a real server: no requests below 5, no
Delete below 6, no Automatic quality below 3, no seek thumbnails below 9, no Live TV listings below 13, no casting a channel below 14, no trailers below 20, no arranged rows or lists below 22, no Notify me below 29, no related titles below 30, no Sound, subtitle size or subtitle timing below 35.

The fakes are a device signed in to several servers ("Several servers"
below): the Living room server in use, the Cabin server with a small library
of its own (`FakeCabin`), the Den server, which never answers, one more
signed out, and the Office Mac mini on the network and not added yet.
Search for "harbor" finds titles on the first two. A test of a device with
one server makes its fakes with `several = false`.

Requests, missing episodes' state and Report a problem need a server at API
level 5 and the corresponding `getRequestOptions` capability. Requests can
go through Radarr/Sonarr or Seerr; unlinked Seerr users cannot request.
Your requests remains available if permission to create new requests changes.
Seerr approvals open in a browser, while earlier local requests and local
problem reports stay in the Inbox. Admin Inbox access follows both the
account and the server’s admin-network permission; Search now and Monitor
still require Radarr/Sonarr management. Settings has Your requests, and admins' Inbox with its count
on the Settings tab (phone) and the rail's Settings (tablet and TV).

Settings on a phone or tablet
opens on a list of groups named and ordered as the
web app's tabs, each saying what is chosen: Playback, Audio & subtitles,
Downloads, Home rows, then Account & devices, Your requests and About, and for
admins a Server group (Inbox, and Server settings, which opens the web app's
Settings). A group opens its own page (`MobileRoutes.Settings*`), with headings
for what is kept on this device and what follows the account. A change that
doesn't save stays on its row with Try again; the account's rows wait until its
settings have loaded. Signing out, signing another device out and Remove all
downloads ask first.

A show, a season and an episode all open one page (`ShowPageViewModel`):
the chosen episode on top with Play, Mark watched and
its own ⋮ (the TV's More), the season chips or tabs (Specials last) over the
season's row of episodes, and About the show with Mark show watched and
Request missing seasons. Which episode goes on top is `onTop` in `core:model`:
the show's up-next episode, a season's first unfinished one, or the episode
linked. A chip or a card changes the episode on top in place, so Back leaves
the show in one step; on the TV the episode on top follows focus along the
row, and OK on a card moves focus back up to its Play. From 1000 dp tablets
put the episode beside About the show, with the chips and the row across the
bottom. The episode's menu has Delete episode… on servers at API
level 6 that say so in `RequestOptions.delete` (admins, or everyone when
the server allows it), and Replace file for admins when Sonarr manages the
show; after a delete the next episode goes on top. On the fakes, admins delete Copperline's episodes (the fake Sonarr's)
and get the can't-delete dialog on the other shows; Alex gets neither.
A `watchmymedia://shows/{id}?season=&episode=` link opens the show page with that episode on top.
A `watchmymedia://play/{fileId}` link plays only when the TV's Watch Next
row published it for the account signed in (`source=watch-next&account=`);
any other, from a web page, a QR code or another app, opens the movie's or
episode's page instead and ignores its `t`, `title` and `subtitle`
(`DeepLink.open`). Try one with
`adb shell am start -a android.intent.action.VIEW -d 'watchmymedia://play/9101'`.
The row follows the server's Continue watching each time Home loads (at
start and after every `watch.changed`), loses what is marked watched, and
offers the next episode after one finishes (`WatchNextLibrary`, `TvWatchNext`);
its cards show the app's banner, as the launcher can't sign in to fetch artwork.

Casting needs the receiver's Cast App ID, from the Google Cast SDK Developer
Console: `-PcastAppId=ABCD1234`. Without it the phone shows no Cast button
and the TV app takes no casts. The phone also needs Google Play services and
a server at API level 2 or later with casting on. Debug builds should use the
unpublished development App ID, which only works on Cast devices registered
in the console by serial number. The phone player harness has `casting` (the
TV's remote) and `handover` (a TV turns up while playing) scenarios on the
fakes, which draw the Cast glyph instead of the real button:

```bash
adb shell am start -n media.watchmy.client/media.watchmy.client.mobile.player.harness.PlayerHarnessActivity -e scenario casting
```

Automatic quality needs a
server at API level 3; older ones don't offer it. The harnesses show a step
down on the fakes (`-e scenario auto` on the phone, `--es scene auto` on the
TV) and a return to Direct play (`-e scenario autorecovery` or `--es scene
autorecovery`). At API level 24, Android reports fresh completed HLS segment
transfers and can recover quality after sustained spare bandwidth. It waits
for Media3's first rendered frame before acknowledging a negotiated switch,
keeps the previous server session during that switch, and restores it if the
switch fails. Direct play and fixed quality keep their existing behavior;
older servers do not receive the new request or report fields. A cold
segment's server preparation time can make a transfer look slower, so recovery
may be delayed. To see a real bandwidth change, slow the link between the
emulator and a local server: `adb emu network speed` does not throttle current
emulators, so put a rate-limiting TCP proxy in front of the server and connect
to the proxy's port. The fixture's `S01E01` (720p, 2.7 Mbps) steps down at
1.5 Mbps; its other episodes are too small to stall. On an emulator, use 720p
or smaller: its software decoders fall behind at 1080p.

Seek thumbnails show the picture
at the target while a finger drags the scrubber on a phone or tablet, or the
remote seeks on a TV (with the step beside the time). They need a server at
API level 9 that has made them for the file, which it does in the background
after a scan; until then, and on older servers, the scrubber shows the time
alone. Sheets come through the image loader (`CoilSeekThumbnails`), which
signs in only to the current server, and each tile is cut out of its sheet
with `BitmapRegionDecoder`. On the fakes every play has them, drawn in code.

On a TV, ◀ and ▶ (and Rewind and Fast-forward, and the ⟲ and ⟳ buttons)
move the scrubber's target, and the seek goes once the key is up and the
remote has been quiet for 400 ms (`SeekState`), so a run of quick presses
is one seek to where they add up. Each seek
flushes the player's buffers and audio output, a TV keeps nothing behind
the picture, and on a server's stream a seek can restart the transcode.
Two seeks a moment apart on a passthrough audio track (Dolby to a TV or
receiver) left Media3 1.11 buffering for good, with its buffer full and no
error, until the viewer left the player. Whatever the cause, a player that
sits buffering for 10 s with 10 s or more in hand and doesn't move is
stuck: the controller seeks where it is, then loads the stream again, then
stops with "Playback stopped and didn’t start again.", which Play retries
(`stuck` and `unstick` in `RealPlaybackController`, logged under
`WmmPlayback`).

Movie trailers need a server at
API level 20, which lists them on the movie (`trailers`, best first); older
servers list none, so there is no Trailer button. The button (after Play or
Resume and Play from start) plays the first trailer the server lists that
this device can play, and the logic is `MovieViewModel`'s (`trailerPlay`):

- A **trailer file** plays like any file (`PlayRequest` from 0, titled
  "<movie> · Trailer"), and casts like one.
- A **YouTube trailer on a phone or tablet** opens `TrailerScreen`: our bar
  above a WebView holding YouTube's IFrame player at 16:9, in landscape on
  phones, with nothing of ours over the video. The WebView loads a page of
  ours (`TrailerPage.kt` in `core:ui`, shared with the TV) with `loadDataWithBaseURL` at
  `https://<application id>/`, which gives YouTube's player the Referer
  YouTube asks apps to identify themselves by (error 153 without one). The
  page tells the app what happens through a JavaScript interface; errors 101
  and 150 (the owner keeps the video on YouTube) move to the next YouTube
  trailer, then "This trailer can’t play here" offers Open in YouTube
  (`TrailerViewModel`). The WebView has no file or content access, opens no
  windows, and sends any link that would leave the page to the browser or the
  YouTube app. It pauses when the screen is hidden, and YouTube's own full
  screen button shows the video over the screen (Back leaves it first). The
  WebView has `MATCH_PARENT` layout params: with the `WRAP_CONTENT`
  AndroidView gives it, the page is laid out 0 px high and the trailer
  plays its sound with no picture (AGENTS.md, Emulators).
- A **YouTube trailer on a TV** plays in the app too:
  `TvTrailerScreen` loads
  the same page, without autoplay, in a WebView that fills the screen.
  - While the player loads, our screen shows the trailer, the movie and
    "Plays from YouTube" under a spinner, and the WebView is there but
    invisible.
  - Once the page says the player is ready, the screen shows it, with
    nothing of ours on it or beside it (the reminder banner waits too),
    then asks it to play. Nothing plays unseen.
  - A player that isn't ready within 20 s counts as unreachable.
  - Refused (101 and 150 after the last trailer) and unreachable take the
    player off the screen for ours, with Open in YouTube only when an app
    takes the link, and Back to the movie.
  - A trailer that played closes the screen when it ends. It pauses in
    `onStop` and keeps the TV awake while it plays.
  - The remote's keys (`TrailerRemote.kt`) go through
    YouTube's IFrame API: the screen holds the focus, the WebView never
    takes it, and the keys call `playVideo`, `pauseVideo` and `seekTo` in
    the page. Back is always the screen's, and Up and Down do nothing.
    YouTube's keyboard controls (k, j, l) did nothing in the embedded
    player, on a Google TV Streamer and on an emulator, so the app doesn't
    use them.
  - While the trailer is paused, YouTube's player leaves the screen, as
    while it loads, and our pause screen takes its place:
    a pause sign, the trailer, the movie, "Paused · 0:13 /
    7:45" and the keys ("Select to play · ◀ ▶ 10 s · Back to the movie"),
    with the time and length from the page (`trailerTime()`). YouTube's player shows nothing
    when it is paused through its API, so a paused trailer looked frozen.
    Select, Play/Pause and Play show the player again, then ask it to play;
    Left, Right, Rewind and Fast-forward jump 10 s and update the time,
    and the trailer stays paused. A trailer that plays any other way is
    shown, never left playing out of sight.
  - Where the TV has no usable WebView (`WebView.getCurrentWebViewPackage()`
    is null, or making one throws, which `TrailerWebViews` tries once, on
    the first press), Trailer hands off as before: an `ACTION_VIEW` of the
    watch link, which the YouTube app takes, with "Opens the YouTube app"
    under the row. With neither a WebView nor an app for the link, the
    button is hidden for a YouTube trailer. The manifest's `<queries>`
    entry lets `resolveActivity` see YouTube on Android 11 and later.
  - The TV's hand-offs (that one, and Open in YouTube) add the YouTube
    app's `force_fullscreen` and `finish_on_ended` extras, so it plays the
    trailer full screen and comes back to the app when it ends, instead of
    playing another video four Backs away. They're undocumented, from the
    retired YouTube Android Player API; the check on a Google TV Streamer
    saw both work.

On the fakes, Dead Reckoning Bay's trailers are on YouTube (Blender Studio's
own uploads of two Creative Commons open movies stand in for them) and The
Paper Kite's is a file. The fixture's Paper Moons and Harbor Lights have
trailer files, linked once their movies are matched.

This device's own playback settings act when a player opens or asks the
server how to play. On mobile data (a network without
`NET_CAPABILITY_NOT_METERED`; `adb shell svc wifi disable` puts an emulator
on its cellular network) the phone asks for its On mobile data quality at
each decision, and a pick in the player is saved to that setting. On a TV,
Match frame rate asks the display for a mode at a whole multiple of the
video's frame rate and gives the window its own preference back when the
player goes (`FrameRateMatch.kt`). An emulator has one 60 Hz mode, so a
23.976 fps file asks for nothing there; a 30 fps one (the fixture's Paper
Moons) asks for that mode, which `adb shell dumpsys display` shows as
`PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE` until the player goes. With
the setting off, `adb shell dumpsys SurfaceFlinger` shows Media3's own
`OnlySeamless` vote on the player's SurfaceView instead. Audio passthrough
Off leaves the bitstream formats out of the profile (logged under
`WmmProfile`) and decodes Dolby in the player; on an emulator,
`adb shell settings put global use_external_surround_sound_flag 1` and
`external_surround_sound_enabled 1` (then restart the app) make Media3
report AC-3 and E-AC-3 passthrough, so the two settings send different
profiles. The profile's channel limit is the larger of the output's PCM
channels and its bitstream formats'; when the PCM ones are fewer (a
soundbar on ARC that takes E-AC-3 but two channels of PCM) it also sends
`maxPcmChannels`, so a converted track keeps its surround as E-AC-3. It
lists `pcm`, which the server copies only in direct play of MKV: Media3
also reads `ipcm`, `fpcm`, `lpcm`, `sowt` and `twos` in MP4 and MOV, but
not QuickTime's `in24`, `in32`, `fl32` or `fl64`, which ffmpeg writes for
24-bit, so the server converts PCM in any other file. Both go only to
servers at API level 31 or later.
The profile's size limit is the largest picture the offered
codecs' decoders take at 24 fps, whatever the screen, so a phone whose
hardware decodes 4K direct-plays a 4K file: read from
`MediaCodecInfo.VideoCapabilities` (the decoders' performance points from
Android 10), hardware decoders first, a software-only codec counting for
1080p at most, and the display's size only when no decoder says. A video
decoder that fails on a stream gets one more decision for that viewing, at
a size it takes or without its codec (logged under `WmmPlayback`).
The person's Subtitle size (Settings › Audio & subtitles, from API level
35) scales every player's text subtitles, on top of the system's caption
size where phones follow it while captions are on; a TV keeps its own size
for older servers, and gives a size other than Normal to a person with none
saved once (`moveSubtitleSizeUp`). The player's Audio & subtitles has the
person's Sound (Boost dialogue and Reduce loud sounds, which the server
applies when it decides, so a change saves and reloads the play where it
is) and the selected track's Subtitle timing, which moves a side-loaded
track's cues with Media3's `SubtitleConfiguration.timeOffsetUs` (a
rendition or a track read from the file moves to the server's WebVTT copy
first) and saves for the person a second after the last press. Phones and tablets
keep 15 s of played video so a jump back plays at once (`PlayerOptions`);
TVs keep none, as it would come out of the buffer ahead of a 4K remux, and
neither do low-RAM devices.

### Several servers

A device can be signed in to several servers at once, each with its own
sign-in, and one is in use: Home, the libraries, Live TV, requests and
Settings' account pages are its. A server it has signed in to and not
removed is registered (`SavedServer.registered`), up to 12 (`MAX_SERVERS`);
one whose sign-in ended stays, as signed out. The list stays on the device:
nothing ties two servers together, and the server has no part in this.

- **Servers, the switcher.** The account button (the person's initial) opens
  it: a sheet on a phone, a popover beside the tablet's rail, a panel from
  the left on the TV, where OK on the button at the rail's foot opens it and
  Back closes it onto the button. It lists the registered servers, the one
  in use first, then Add a server and Manage servers (`ServersViewModel`,
  shared by the three). With two or more, a phone's Home names the server
  in use under its title; the rails have no room, so there it is the
  button's label. With Live TV in the bar Settings has no tab, so there
  Servers ends with a Settings row.
- **A switch** asks the server which one it is first (`GET /api/server` at
  the address kept, five seconds at most, the id must be the one
  remembered: `ServerRepository.switchTo`). It answers and its sign-in is
  kept: it is in use at once, the signed-in screens are rebuilt on it, and a
  note says "Now on Cabin server" for four seconds (a snackbar on phones and
  tablets, a banner at the top right of the TV that takes no focus). Its
  sign-in has ended: its sign-in screen opens, and Cancel goes back. The
  device learns of a sign-in ended elsewhere only from that server's first
  reply, after the switch; Cancel then goes to the server used last that is
  still signed in. It
  doesn't answer, or another server answers at its address: the app stays
  where it was and says so, with Try again and, on a device holding
  downloads from it, Watch its downloads, which switches without asking.
  A cast ends when the sign-in in use changes.
- **Add a server** is the server picker without the registered servers,
  which it names under the list (`ServerPickerViewModel(adding = true)`),
  then that server's sign-in, then on phones and tablets a last step that
  says what changed. Closing it at any step leaves the app where it was,
  and a server chosen and never signed in to isn't kept.
- **Settings › Account & devices › Servers** lists them, each opening its
  page: its address, version, who is signed in and what the device holds
  from it, Switch to this server, and Remove this server…, which asks
  first. Removing signs the device out of it and deletes its downloads
  here at once, whether or not it is the one in use, and changes nothing on
  the server. Removing or signing out of the server in use moves to the
  one used last that is still signed in.
- **Search** looks in every server signed in (`SearchViewModel`): each is
  asked at once, as its own person at its own address
  (`LibraryRepository.searchOn`), and gets four seconds. The server in
  use's results draw first; each other server's are added to the end of
  each group, with its name on them, once every server before it in
  Servers' order has answered or failed, so nothing on screen moves. One
  still out after a second is named at the end; one that fails gets a line
  with Try again and isn't asked again for 30 seconds on that visit. A
  title on several servers is one result, where its first copy is, saying
  "On Living room and Cabin" (the TV draws it as a flag on the picture).
  A result on another server opens there, in place, without switching.
  What can be requested, and marking watched, stay the
  server in use's. A picture from another server is asked for with that
  server's sign-in, which goes to no other address.
- **One card a title.** A movie or show on several signed-in servers is one
  card (the same kind and TMDB id; an episode, its show's TMDB id, season
  and number; `cardKey` in core:model), and a card says where its title
  is only when it matters (`Where`: "On Cabin", "from Cabin"). Home's
  Continue watching and its recent rows (`MERGED_HOME_ROWS`), Movies and
  TV Shows (`MergedPages`, a page at a time, the counts "about" until
  every server answers) and search are filled from every server through
  `OtherServers` (presentation), which asks each through
  `LibraryRepository.on(serverId)` with four seconds; a slow one joins at
  the end of what is drawn, one that fails is named with Try again. A title
  page asks the other servers for their copies by TMDB id (level 34,
  `copies`) and offers each server's versions, grouped by server (the TV's
  button after Play, the phones' Versions); Play uses the copy chosen for
  the title on this device (`CopyChoices`), else the one with the latest
  place, and Resume starts from the latest place on any copy. Another
  server's title opens in place (`TvRoutes.Movie(id, serverId)`, the
  phone's the same) and plays from there without switching
  (`PlaybackControllerFactory.createOn`), so its progress stays on that
  server. Downloads stay the server in use's. Below level 34 a server has
  no copies to offer and its episode cards don't merge.
- **Asked first.** A kept address is only an address: at home it is the
  Cabin server, and on another network it is someone else's machine. So
  before the sign-in of a server that isn't in use goes anywhere, the app
  asks that address which server it is, with no sign-in (`GET /api/server`),
  and sends the sign-in only when the id is the one remembered
  (`Http.askFirst`). Nothing in the app has to remember to: every access to
  a server (`ServerAccess`, from `Http.bind` and `bindTo`) asks before each
  call when its server is not, or is no longer, the one in use, and so do
  the event stream's calls and the end of a session. The server in use is
  never asked: its sign-in goes to it as it always has. What a server says
  of itself (name, version, level) is kept; one that says it is too old is
  left out. Nothing at the address, or another server there: no sign-in is
  sent, and the call fails as one that can't reach its server. What asks:
  - **Search.** Once a visit, not once a letter, and the question shares
    the server's four seconds. A visit begins each time Search comes on
    screen (`SearchViewModel.setShown`), since a device changes network
    while the app is away; servers whose results are showing are asked
    then, so their pictures go on loading (a picture is signed only while
    its server's answer stands). A call that fails forgets the answer, so
    Try again asks again. A server that doesn't say so gets the line and
    the rest any failure gets; one too old, or that refuses the sign-in, is
    said to be too old or signed out here.
  - **A download's reports** to the server it came from, while another is
    in use (`RealOfflineGateway`): each call asks, since they run in the
    background, hours apart and on any network, with nothing to say when
    an answer has grown old. A report that isn't sent fails and is tried
    again later, as one that can't reach its server always was.
  - **Removing a server**, and signing out of one when another takes its
    place: asked once, for what the removal reports and for the sign-out
    after. When it doesn't say it is that server, the server and its
    downloads are removed from the device at once all the same, and the
    sign-out is not sent, then or later: the session stays on that server
    until it runs out, or someone ends it from that server's list of
    devices.
  - **What outlives a switch**: a player's last report, a setting still
    being saved. Each asks before it sends.

  An answer stands only on the network it was given on, and only for the
  work that asked: one visit to Search, or one removal.
- **Above the signed-in screens.** Those screens are rebuilt on every
  switch (`key(server.id, user)`), so the note, Add a server's steps, the
  way back from a sign-in and a search result's landing are kept by
  `ServerSwitchViewModel`, which `MobileApp` and `TvApp` make for the
  activity. A landing names its server, and only that server's screens get
  it (`landingFor`): the switch takes a moment, and the screens of the
  server still in use would otherwise open the page and use the landing up.
- **Links and notifications.** A launcher's or notification's link names
  its account (`account=`). One for another account signed in on the
  device opens its title in place, from that server, without switching
  (`DeepLink.elsewhere`); its Live TV says which server it is for and opens
  nothing. One for an account that isn't here still opens nothing.

### Rows people arrange, and rows made from lists

From API level 22 Home and each
library's Movies or TV screen come in the person's order
(`LibraryRepository.arrangedHome` and `libraryRows`, `getHome` and
`getLibraryRows` with `arranged=true`); older servers keep the fixed rows.
Rows are drawn by their style (`poster`, `wide`, `episode`, `continue`,
`live`); a style the app doesn't know is skipped, and `live` is the app's
own Live TV row in that place. The tablet keeps Continue beside the hero only
while it is the first row drawn. The TV leads Home with a screen of its own
(`HomeLead`): a large card to resume Continue watching's first title, which
then leaves that row, with Resume, Go to episode and Remove from Continue
watching; the server's pick when nothing is in progress. Focus starts on
Resume (or Play), and the rows follow a screen each; with neither, Home
opens on its first row. Movies and TV get their rows above the grid.

- **Edit Home** (end of Home, and Settings › Home rows) and a
  library's **Edit rows**: a page on the phone, a dialog on the tablet.
  Rows drag by their handle or move with ⋮; Hide and Show, Add a row (the
  hidden rows and lists; lists are made on the web), Reset. Done saves once;
  Close leaves without saving (`RowLayoutViewModel`).
- **Settings › Home rows** on the TV: the Screen (Home, Movies, TV Shows),
  then Move up, Move down, Hide or Show in the side panel, each saved as it is
  made. A TV library's Edit rows opens it on that screen.
- **The list page**, from a list row's See all: rank order, the library's
  titles as cards, the missing ones dimmed with Request (the request sheet or
  dialog) and imports' unmatched titles as Not found
  (`MediaListViewModel`). Refresh now and Delete are on the phone's ⋮ for
  whoever may change the list.
- **See all**, on the phone and the tablet: a row
  with a `seeAll` link ends in a See all card the size of its cards and has
  See all in its heading ("See all TV shows · By last aired", `seeAllWords`).
  A list's opens the list page. A library's posts the link's sort to
  `AppServices.browseRequests` and opens the tab at its grid; the tab's
  `LibraryViewModel` takes the request once (`open`), as if the person had
  chosen the sort, and the screen scrolls to the grid's heading
  (`State.toGrid`). The rows above the Movies and TV grids call `open`
  directly. Continue watching and Live TV have none.
- **See all on the TV**: the list row's card, now on
  every row with a `seeAll` link (`TvSeeAll`, `tvHomeSeeAll`,
  `tvLibrarySeeAll` in `tv/rows/TvRows.kt`), as large as the row's artwork
  as it grows (`RowArtCard`). Home's one row a screen shows its panel
  ("See all TV shows", "By last aired", `CardTarget.Library`). OK opens TV
  Shows or Movies through the same request, and the screen puts focus on
  the grid's first card under All shows; Back comes back to the card.
- `rows.changed` and `lists.changed` read the screens again
  (`RowsRepository.changes`). The TV's Watch Next row follows the Continue
  row, or Continue watching asked for alone when Home has none.

On the fakes Home has two list rows (TMDB, and MDBList with a failed
refresh), a hidden import list with a Not found title, and Movies a library
list; the missing titles are the fake request flow's, in each request state.

### Following shows: new-episode notifications

From API level 29
a show's About has **Notify me** first, on the phone, the tablet and the TV:
it follows the show on the server at once, for the person on every device,
and goes back with the page's message if the server refuses
(`ShowPageViewModel.toggleFollow`). On Android 13 and later the first Notify
me on a phone or tablet explains, then asks for the notification permission
(`FollowAskDialog`, once a process); Not now keeps the follow. Settings ›
Notifications lists the shows with Stop notifying (`NotificationsViewModel`),
and on phones and tablets has this device's switch, kept on the device for
each account. TVs don't notify: the bell, the list and Home's `followed` row,
New from shows you follow, which Home draws by its episode style.

Phones and tablets check the server's feed (`GET /api/me/new-episodes`)
themselves (`app/newepisodes/`; the rule is `checkNewEpisodes` in
`core:model`):

- **When:** the app's one periodic work, hourly with a network
  (`NewEpisodeWorker`), its first run a period after it is scheduled. It is
  scheduled whenever this device could notify (signed in to a server at
  level 29, this device's switch on, Android allowing the channel), whatever
  this device last saw of the list, since a show followed on another device
  notifies here too; and cancelled otherwise. The app also checks at each
  foreground and three minutes after the last `library.changed` while it is
  open (scans that change nothing don't put it back; `NewEpisodeFollower`).
  A process started in the background leaves the work as it is. WorkManager
  still starts on first use: whether the work is scheduled is kept in the
  `new-episodes` preferences, so a start with nothing to cancel doesn't
  start it. Each foreground schedules the work again with
  `ExistingPeriodicWorkPolicy.UPDATE`, which keeps its timing.
- **The cursor** is kept for each server and person signed in on the
  device, by their ids (`followsAccount`, so a rename keeps it), in the
  `new-episodes` preferences (out of backups), so coming back to a server
  carries on from its last check. The first check keeps only the cursor; an
  unreachable server, or a 404 or 501 from the feed, keeps it and the work.
  Turning the switch off forgets that account's, and Android no longer
  allowing the channel forgets them all, so on again starts from now. The
  check and its work follow the server in use. A sign-in that ends (signed
  out, or its server removed) loses its cursor and takes away the
  notifications showing; a switch between two servers signed in keeps both,
  since a notification for the other switches to it when it is opened.
- **The notifications** are on the `new-episodes` channel ("New episodes",
  default importance), one a show under its own tag, each opening
  `watchmymedia://shows/{id}?season=&episode=&source=new-episodes&account=`
  for that account alone; past five shows, one more opens Home
  (`watchmymedia://home`) and sorts last (every one has a sort key). Two or
  more share a silent group summary.
- **Turn on notifications** (`openNotifications(context, channel)`) opens
  Android's app screen while the app's notifications are off, else the
  channel's; Settings shows one card for reminders and new episodes
  together, naming what can't show.

On the fakes Sam follows Harbor Lights, Copperline and Ashgrove, which has
left the library; the fake feed has Harbor Lights' S3 E7 once after the first
check. Run the work by hand on a device with `adb shell cmd jobscheduler run
-f <package> <job id>` (`adb shell dumpsys jobscheduler | grep -A3 <package>`
lists the id).

### Related titles at the end of movie and show pages

From API level 30 a movie's page
ends with its collection (the collection's other films in the library, in
release order, named as TMDB names it) and More like this, and a show's page
with More like this (`LibraryRepository.movieRelated` and `showRelated`,
`getMovieRelated` and `getShowRelated`). `MovieViewModel` and
`ShowPageViewModel` ask once the details are in, on their own, so the page
never waits; the rows stay with the page, and a quiet reload doesn't ask
again. An older server is not asked, and a failure leaves the page without
rows; either way the page ends as before. The rows are Home's poster rows:

- **Phones and tablets** (`mobile/detail/RelatedRows.kt`): under the cast or
  About the show, named as the page's sections are; the tablet's movie page
  puts them in its right pane, its show page across the bottom under the
  season's row. A card opens its title on top; its long-press has no menu.
- **TV** (`tv/detail/RelatedRows.kt`, `TvArrangedRow`): the page's last rows,
  the collection with "In release order" beside its name. The cast is a row of
  people cards that take focus (OK opens nothing until the server says which
  titles a person is in), and ▼ from the cast or About the show
  comes to the row's first card (`CardRow`'s `enter`); ▲ goes back. The row is
  kept whole in view as it grows, Hold OK marks nothing, and Back from the
  title a card opened puts focus back on that card. For TalkBack the heading
  is a heading and the row carries its name.

On the fakes Cold Harbour Road, Low Tide at Carrow and Dead Reckoning Bay are
the Carrow Coast Collection, every other movie and show has More like this,
and Tin Sky and Midnight Cartography have nothing like them.

### First-run Home and subtitles

An empty Home checks the library list and explains whether the server has no
libraries, is scanning, found no playable media, or could not read a folder.
Admins can open the server's web page on phones and tablets, or read its
address on TV; members get instructions to ask the server's owner. Scan
transitions refresh Home without refetching every progress event.

From API level 18, Settings has per-person subtitle preferences: Off, Forced
only (the default), or On, with an ordered language list. Automatic download
is off until enabled; it tries one exact-file match after playback starts
when no local track matches. A manual choice or Off wins over a late result.
The last acknowledged settings are retained for cold offline starts, scoped
to the account and server address. Older servers keep their previous behavior.

Image and ASS/SSA subtitles use the server's burn-in path to preserve their
appearance. This can transcode the video; choosing a different track restarts
the stream at the current position. A failed optional side-loaded text track
is dropped while the video continues.

In direct play the player reads the file's own text subtitles from the file
(Media3 extracts SRT, ASS, WebVTT and MP4 text) and side-loads only sidecar
and downloaded tracks; the server's copy of an embedded track reads the whole
video first. It finds the decision's `s{n}` among its text tracks by order, or
by language when the counts differ, and drops a track it can't find. Remux
and transcode streams keep their HLS renditions; downloads side-load the
copies stored beside the file.

### Downloads

Phones and tablets download movies and episodes to watch with no connection;
the TV app never shows them.
Download is offered from API level 10 to whoever the server's Who can
download allows (admins by default, `RequestOptions.offline`); with downloads
offered, Downloads takes Search's place in the phone's bottom bar (Search
stays in the top bar) and joins the tablet's rail.

How it works (`core:downloads`):

- **What is downloaded** is the playback decision for this device at the
  chosen quality (Settings › Downloads › Quality, Original by default): the
  original file with Range requests when it plays directly, else the server's
  download session as HLS. An original's sidecar and text subtitles come as
  files of their own beside it. Image and ASS/SSA subtitles can instead be
  burned into a prepared copy, including when Original was chosen; those
  subtitles stay on in that copy.
- **Media3** `DownloadManager` and `WmmDownloadService` (a `dataSync`
  foreground service with the progress notification) fetch one download at a
  time, segments in order, into a `SimpleCache` in `noBackupFilesDir/offline/`
  that never evicts. Wi-Fi only and enough storage are requirements. A
  download that would leave under 1 GB free is refused. The resulting
  prepared copy's size is checked again before queuing its media bytes.
- **After the app's process ends** (`DownloadRestarts`): the manager starts
  paused, so the app resumes it as soon as it is made, and starts the service
  on its first time in the foreground when a download is unfinished. Below
  Android 12 `WorkManagerScheduler` starts the service again when Wi-Fi or
  space comes back and after a reboot. Android 12 and later refuse a
  foreground service started from the background and Media3 drops that
  scheduler there, so while the app is away with a download unfinished a
  WorkManager job with the same requirements (`DownloadJobWorker`) runs the
  downloads in the app's process, without the notification: when the network
  or space returns, after a reboot, and again each time Android stops the job
  (about ten minutes a run). Coming back to the foreground cancels it and the
  service takes over. A user-initiated data transfer job (Android 14) is not
  used: Media3 1.11 has no downloader on one, it can be scheduled only while
  the app is in front, and it needs the service replaced by a job service of
  the app's own.
- **Tokens**: each fetch carries the download's own `o1.` token, renewed with
  the device's session on 401 (`OfflineDataSource`); 503 with Retry-After
  shows Preparing and is waited out; 409 starts the download over; 403 and
  410 end it for the next sync to explain. Cache keys leave the token out.
- **Records** (`OfflineStore`, `state.json` beside the cache): the server's
  reply for each download, with the decision the offline player needs, and
  the offline progress queue. Downloads belong to the account that made
  them: signing out reports them removed and deletes them, and so does a
  401.
- **Sync** (`POST /api/offline/sync`) at start, on coming to the
  foreground, after a play or a download ends, and when a network is back (a
  WorkManager job): it sends what the device holds and the progress made
  offline, by when it happened. Revoked downloads are deleted with a notice;
  a file deleted or replaced on the server only marks the copy, which still
  plays.
- **Playing**: a downloaded item always plays from the device, online or
  not (`OfflinePlaybackGateway` and the player's data source), and its
  heartbeats go to the queue. With no server, Home shows the downloads under
  You're offline, and a downloaded movie's or episode's page still opens and
  marks watched. Its quality is fixed: the player offers Audio and Subtitles,
  and ignores streaming-quality commands without changing the saved default.
- **Artwork** is retained separately from Coil's evictable cache, outside
  backups. It belongs to the download's account and endpoint, survives offline
  starts, and is removed with the download; a late image response cannot
  recreate a removed copy.

On the fakes (`-Pfakes=true`) the Downloads tab has every state the boards
draw; a new download waits, prepares and comes in steps, Tin Sky's fail at
35% (Couldn't download), and Alex, a member, may not download.
`-PfakesOffline=true` (with `-Pfakes=true`) makes the server unreachable, for
the offline Home and the offline pages.

To try it on a server, run a throwaway server from your branch (root
AGENTS.md, Test environments) and let everyone download, or sign in as the
fixture's admin:

```bash
sqlite3 scratch/watchmymedia.db "insert or replace into settings(key,value) values('offline.access','everyone')"
adb reverse tcp:27610 tcp:27610   # then 127.0.0.1:27610 in the server picker
adb shell svc wifi disable; adb shell svc data disable   # offline: play a download
```

The fixture's `Paper Moons` downloads as the file (direct play); `Harbor
Lights (2019)` at 720p downloads as a transcode. Activity › Downloads in the
web app lists them, with Revoke. Doze, OEM battery savers and Android 15's
6-hour `dataSync` limit are for real phones.

On Android 17 and later the app has to be allowed local network access, or
discovery and servers on the LAN can't be reached (Android blocks them for
apps targeting API 37). `LocalNetworkGate` in `app` asks once at launch; after
"Not now" it's under Settings › Apps › WatchMyMedia › Permissions › Nearby
devices. A system dialog dismissed without an answer asks again at the next
launch, and a Cast Connect play that opens the app while the question is
up waits for the answer (two minutes at most) before it looks for the
caster's server. The server picker offers it again when nothing is found.

TV Search's microphone listens in the app (`VoiceTyping`, with the TV's
`SpeechRecognizer`), so the app declares `RECORD_AUDIO` in `tv`'s manifest,
with `android.hardware.microphone` not required (the one APK runs on phones
and TVs without one). The permission is asked for at the microphone's first
press, on a TV only; the phone has no microphone key and never asks. A TV
that offers no speech recognition to apps has no microphone key.

## Live TV on phones and tablets

Live TV appears when a server at API level 11 or newer allows the signed-in
account to watch. It replaces Settings in the phone bar and tablet rail;
Settings remains under the account picture. Home also shows a channel row.
Older servers and accounts without Live TV keep their existing navigation.
Configure the antenna tuner and manage Activity in the server's web app.

The channel list works without programme listings (Listings, below). The dedicated live player
has channel switching, pause, Go live and quality choices. On servers before API
level 25, Automatic, Original and 4K preferences use the live 1080p cap. On a
level 25 server that advertises live Automatic, the player sends the current
Media3 device profile and offers Automatic, reports buffer/transfer health, and
shows the actual output step. A prepared step reloads within the same session;
the player acknowledges it only after a rendered frame or reports a failed
reload. Lower fixed choices and On mobile data still apply. Changing channel
or choosing a fixed quality releases the old tuner before starting the next
session.

The rolling buffer keeps about a minute. A pause that outlasts it resumes at
live; after five minutes paused the tuner is released. Four hours without input
asks whether you are still watching, with one minute to answer. Leaving the
player, backgrounding, signing out or changing server stops live playback.
Returning to a stopped player needs an explicit retry. Live TV does not write
file progress or history and has no downloads, next-episode actions, broadcast
caption/alternate-audio selection or PiP in this phase. Casting is below.

Tablets use a navigation rail from 600 dp and show the channel list beside the
selected channel from 1000 dp. Portrait playback stacks the video and channels;
landscape playback has a side panel. The fakes include fictional channels and
the same API-level gates (`FAKE_SERVER_API_LEVEL`).

### Listings

With a server at API level 13 whose `capabilities.guide` is true,
Live TV shows
programme listings; otherwise every screen above stays as it was. The phone's
guide shows an hour across, with the selected programme above it and its
details in a bottom sheet; from 1000 dp the tablet shows two hours beside the
details panel, and a Guide / Channels switch. Home's row becomes On now, and
the player's banner and channel panel show what is on, its picture, progress
and Next. Programmes move to next at their end by the clock (`rememberLiveNow`),
and the channel list is asked again then. `LiveGuideViewModel` keeps the
window, the days listed, the category filters and the selected programme for
both UIs; `RealLiveRepository` fetches the server's 6-hour windows on fixed
boundaries, fetches the next one ahead, and drops them when the account
changes. Logos and pictures load through `LiveRepository.artUrl`, signed in
like library artwork; a channel without a logo shows its number and name, and
a programme without a picture shows the logo. A channel without listings
still plays.

On the fakes, listings are the guide boards' fictional evening around the
time now, to 7 PM two days on; `FakeLiveRepository.listings = false` and
`.stale = true` give the channel-only screens and the out-of-date notice.
To try a server, `go run ./cmd/livetvfixture -port <port>` from `server/`
serves fictional listings from a fake SiliconDust once its tuner is added.

From the repository root, `cd server && go run ./cmd/livetvfixture -port <unused-port>`
starts a temporary loopback server with a fictional tuner
at `tuner.test`. Complete setup and add that source in the web app/API; reverse
the selected port with `adb -s <your-emulator> reverse tcp:<port> tcp:<port>`.
Use an isolated Android 35 AVD and an app-id suffix, as described above.

### Favourites, search and reminders

From API level 15 with listings,
the guide has a star column left of the channels and a Favourites
filter first among the chips once there is a favourite. Every client keeps
one rule (`LiveGuideFilter.keeps`): a favourite shows with or without
listings, in channel order. The TV's guide opens on Favourites when there
are some, else on All channels, deciding once as the listings arrive
(`LiveGuideViewModel`'s `opensOnFavourites`); the phone and tablet open on
All channels. Live TV's top bar has Search listings and More
(Reminders, Reminder time); from 1000 dp they are header buttons that switch
the panel beside the guide. A later programme that starts more than the lead
time ahead offers Remind me, and its details then show the reminder, moved
or gone, with Remove. Settings › Playback › Live TV reminders picks the lead time (0, 1, 5, 10 or
15 minutes, the same on all the person's devices) and opens the Live TV
reminders notification channel. Below level 15 nothing of this shows or is
asked for. The wording is in `core:model`'s `LivePersonal.kt`.

Reminders live on the server; the phone sets one inexact `AlarmManager`
`setWindow` alarm a reminder, from its time, asked for a minute wide (Play
allows the exact-alarm permissions only to alarm and calendar apps).
Android 12 and later stretch a window under 10 minutes to 10 for such apps,
as `adb shell dumpsys alarm` shows, and Doze may hold one back further, so
the notification says how long is left as it comes. The alarms follow the
list as it is read at start, on coming to the foreground, on
`live.reminders.changed` while in the foreground and after any change here
(`ReminderFollower`, `app/reminders/`). The alarms belong to the account
that set them: a sign-out or another account cancels them, also at a start
with alarms kept from before. After a reboot `LiveReminderBoot` sets them
again from what it kept and a WorkManager job reads the list once there is
a network. That receiver is off in the manifest and on only while alarms
are kept, so a boot with none, and every boot of a TV, doesn't start the
app. The notification
("Starting in 5 min", "Dollar Dash · 2.1 HRBR-HD", the channel's logo, Watch)
opens the channel only for the account that set it
(`watchmymedia://live/{channelId}?source=reminder&account=`; any other
`live/` link opens the guide). `POST_NOTIFICATIONS` is asked at the first
Remind me, after an explanation whose Not now keeps the reminder.

On the fakes the boards' favourites (2.1, 4.1, 4.3, 7.1, 11.1) and reminders
(one moved, one gone) are set, and searching "harbor" finds programmes on now
and later. Try a reminder's notification on a throwaway server with the
fixture's listings: set the lead time to 15 minutes and remind a programme
starting in about 16; `adb shell dumpsys alarm` shows its window.

### Recording Live TV

From API level 19, a server that enables recordings adds Record to programme
details and Recordings to Live TV. Phones and tablets also set manual channel,
time and repeat schedules; TVs use the guide and their remote. Everyone who may
record sees the household schedule. The person who set a recording and admins
can stop it; cancelling a series keeps its finished recordings. Recordings take
priority over live viewers, whose players show the server’s warning.

Recordings still in progress use the ordinary file player with a growing clock,
 **Recording** and **Go to now**. They play here while a Cast device is connected;
 casting a growing recording is not offered. Finished recordings use the library,
 download and Cast paths as any other file. Lists refresh on server events and
 use indexed pages rather than polling.

The opt-in acceptance test refuses anything except `cmd/livetvfixture -dvr`,
which records fictional channels in disposable folders:

```bash
# In server/: go run ./cmd/livetvfixture -dvr -port 28300
ANDROID_SERIAL=emulator-5690 ./gradlew :core:data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=media.watchmy.client.core.data.DvrServerTest \
  -Pandroid.testInstrumentationRunnerArguments.wmmDvrServer=http://10.0.2.2:28300
```

### Casting Live TV

With a server at API level 14 whose `capabilities.cast` is true,
Live TV's top bar and the live
player have the Cast button (the LiveCast and TabLiveCast boards). A channel is
offered to a TV only when its receiver's `state` lists the `live` feature, so
an older receiver or TV app is never sent one; file casting is unchanged.

- Connecting a TV while a channel plays here stops it here first (the
  tuners are few: `LivePlaybackController.stopAndRelease` waits for the
  DELETE), then casts it at the current quality. The player becomes the
  TV's remote: the channel, Now and Next, Ch− / Ch+, pause, Go live, Quality
  on the TV, On now to change channel, Continue on this phone (which stops
  the TV, then tunes here) and Stop casting (which ends the Cast session).
- With a TV connected that plays Live TV, the guide's Watch reads "Watch 4.1
  on TV" and casts; Home's On now does the same. A connected TV that can't
  play Live TV says so, and Watch plays here.
- The mini controller shows a cast channel on every screen but the players,
  Live TV included. Leaving the app doesn't end a cast.
- `LiveCastController` in `core:cast` is the remote: it sends a LOAD with
  `LoadData.live` as a `STREAM_TYPE_LIVE` stream, the `channel` and `quality`
  commands, and Go live as a seek to infinity, and reads `state.live`. It
  shares the Cast session's messages with the file remote (`SharedLink`).
  Live Automatic is offered only when the signed-in server advertises it at
  API level 26; the remote shows the receiver's actual step when reported.

On the fakes, `-PfakesCast=true` (with `-Pfakes=true`) has a TV called Living
Room TV connected from the start, which plays Live TV and files. The phone
player harness has `LiveCasting`, `LiveHandover`, `LiveNotLive`, `LiveBusy`
and `LiveStopped` scenarios:

```bash
adb shell am start -n media.watchmy.client/media.watchmy.client.mobile.player.harness.PlayerHarnessActivity -e scenario LiveCasting
```

## Live TV on Android TV and Google TV

With the same server-level and account gate, Live TV joins the rail between
Movies and Settings, and Home shows channels. The channel grid works without
programme listings and restores the selected channel when returning from the
player. With listings, the guide's hero follows the focused programme, Left
and Right at the window's edge move it half an hour, OK watches the channel,
Home shows On now, and ◀ in the player opens On now with pictures. The shared live session follows the lifecycle and quality limits above.

From API level 15 the guide's chips add Favourites and Reminders, a star
marks a favourite's number, and Hold OK (a long press of OK) on a channel
stars or unstars it, and on a programme opens Remind me or Remove reminder
and Watch. Search has an On Live TV row (OK watches what is on and opens a
later one in the guide; Hold OK reminds). The TV posts no notification: a
reminder shows as a banner over any screen while the app is open, which
never takes focus. Hold OK watches its channel, Back dismisses it, and it
hides itself after 15 s.

When player controls are hidden, Up/Down changes channel, Left opens Channels,
and OK shows controls. Dedicated Channel Up/Down keys also work while controls
or the Channels and Quality panels are visible. Menu opens Quality. The
remote's Last goes back to the channel watched before, Record offers to
record what is on (to people who may record; in the guide it opens the
programme's menu), and Guide opens the full guide, as it opens Live TV from
any other screen. In the file player, Next and Previous play the next and
the previous episode, and Episodes, before Audio, lists the season's
episodes (android-tv/TvPlayerEpisodes). Back closes a panel, then hides the
controls, then leaves; Stop releases the tuner and leaves immediately. Pausing
shows the controls. There is no file seek bar or next-episode action.

Go to channel offers an on-screen keypad. A numeric remote opens it with the
first digit; enter the full number, including the dot for a subchannel, then
confirm with OK or Watch. Partial, missing, unavailable and ambiguous numbers
never tune a channel automatically. The still-watching prompt has Keep watching
and Stop actions that are reachable with the D-pad.

Build the TV UI for an isolated Android 35 emulator with `-PforceTv=true` and a
unique `-PappIdSuffix`; add `-Pfakes=true` for fictional channels and the fake
player. Send a D-pad key before interacting so the emulator enters remote focus
mode. Real TV devices and remote layouts still need a separate device check.

A channel cast from a phone (Cast Connect, the LiveCastConnect board) plays
in the same live player, as the person who cast it: `CastConnectReceiver`
opens it on their cast token (`DataServices.castLivePlayback`; cast tokens
can't ask for media tokens, so the player's come from renewing the cast
token), with their channels and pictures. It says who cast, leaves On now,
Quality, Go to channel and Full guide to the phone, keeps ▲ ▼ and Ch− / Ch+,
and has Stop casting where Full guide was. Senders get `features: ["live"]`
and `state.live`, and their `channel`, `channelStep`, `quality`, input and
Still watching commands. When the phone stops casting, the TV says so for
5 seconds and goes back. On the fakes, `CastLiveHarnessActivity` shows it
(`--es scene playing|paused|still|busy|stopped`), and
`CastLiveServerTest` in `core:data`'s instrumented tests runs the data path
against `cmd/livetvfixture`.

## Modules

| Module | What |
|---|---|
| `app` | Application (builds `DataServices` and `PlaybackEngine`, the image loader, which also fetches the media notification's artwork; starts the Cast SDK at the first `ON_START`, `FirstForeground`; WorkManager starts on first use), the one activity for both launchers and its routing of casts and links (`IntentRouter`), the local network prompt, reminders' alarms (`app/reminders/`), debug builds' StrictMode (`StrictModeChecks`), the process's past exits (`AndroidDiagnostics`), manifest, icons |
| `core:model` | Domain models and the interfaces everything builds against: `ServerRepository`, `AuthRepository`, `LibraryRepository`, `PreferencesRepository`, `PlaybackController`, `PlaybackGateway`, `LiveRepository`, `LivePlaybackController`, `AppServices`; and the pure helpers both UIs share, so the phone and the TV say the same thing: labels, dates and counts, error messages and `attempt`, play requests, deep links |
| `core:presentation` | The ViewModels the phone and the TV share, so both pages behave alike (the Apple apps' WMMFeatures models): their state, with what loads as a `Load` (loading, failed or ready), and their one-shot events. Depends only on `core:model`, lifecycle's ViewModel and SavedStateHandle, and coroutines: no Compose, no Android UI |
| `core:ui` | Brand tokens (`WmmColors`), Inter, the phone Material 3 theme, `LocalAppServices`, shared pieces: `Artwork`, `Badge`, `ProgressStrip`, `WatchedCheck`, `BrandMark`, `WmmIcons`, the loading skeletons' blocks, lines and sheen (`Skeleton.kt`, still with animations off), `SubtitleCues` (both players' text subtitles, in a `CaptionLook`: the person's Subtitle size, `CaptionLook.at`, on top of the phone's system caption settings), `playerKey` (both players' remote and keyboard keys), `SeekThumbnail` and `ScrubberLayout` (both players' seek thumbnails) and `CoilSeekThumbnails` (their tiles), `TrailerPage.kt` (both trailer screens' page for YouTube's player), `PRIVACY_POLICY_URL` |
| `core:fake` | In-memory fakes of every interface, with the mockups' sample library and a pretend player (tests, previews, the player harnesses, `-Pfakes=true`) |
| `core:api` | Client generated from `../api/openapi-3.0.json` |
| `core:data` | Real repositories: servers (DataStore, mDNS), sign-in and pairing, library, preferences, the server's event stream, playback API calls (`DataServices`) |
| `core:playback` | `PlaybackEngine`, file `RealPlaybackController` and separate `RealLivePlaybackController` on Media3, device profile, media session, Watch Next |
| `core:downloads` | Offline downloads on phones and tablets: Media3's `DownloadManager` and `WmmDownloadService`, the download cache, `o1.` tokens, the records and offline progress queue, sync, and the player's way to downloaded items (`OfflineDownloads`) |
| `core:cast` | Casting: the phone's `RealCastService` and its remote `CastPlaybackController` on the Cast SDK, Cast Connect's `CastConnectReceiver` for the TV app, and `CastProtocol`, the Kotlin side of `web/src/cast/protocol.ts` |
| `tv` | Compose for TV screens; `tv/player/` is the TV player, with a debug harness in `tv/src/debug` |
| `mobile` | Material 3 phone screens; `mobile/player/` is the phone player, with a debug harness in `mobile/src/debug` |
| `baselineprofile` | Tests that drive `app` on a device: the journeys that write its baseline and startup profiles, the startup and frame-timing benchmarks (Macrobenchmark), and `fixture_server.py`, the seeded fixture server the real-build journey uses ("Baseline profile" below) |

## Build conventions

What the modules share is set once, by convention plugins in `build-logic/`
(an included build), so a module's build file holds only its own settings
and dependencies:

| Plugin | Applied by | Sets |
|---|---|---|
| `wmm.android.library` | every module but `app` and `baselineprofile` | compileSdk 37, minSdk 26, Java 17 and Kotlin's JVM 17 target (AGP's built-in Kotlin), the instrumented-test runner, JaCoCo coverage of the debug unit tests, and `unitTests.isReturnDefaultValues` |
| `wmm.android.application` | `app` | the same but `isReturnDefaultValues`, and targetSdk 37 |
| `wmm.android.test` | `baselineprofile` | the SDK levels, the test runner and the Java and Kotlin targets; no unit tests, so no coverage |
| `wmm.android.compose` | `app`, `core:ui`, `tv`, `mobile` | the Compose compiler plugin with `compose-stability.conf`, `buildFeatures.compose`, and Slack's [compose-lints](https://slackhq.github.io/compose-lints/) in lint |
| `wmm.android.coverage` | the root project | `coverageReport`, which merges every module's coverage into one JaCoCo report |

The SDK levels are constants in `build-logic/src/main/kotlin/media/watchmy/client/buildlogic/AndroidConventions.kt`;
change them there, not in a module. Versions stay in `gradle/libs.versions.toml`,
which `build-logic` reads too. `isReturnDefaultValues` makes `android.jar`'s
stubs return 0, false or null in unit tests instead of throwing, so a test
that reaches Android by mistake passes quietly; use Robolectric where a test
needs Android.

`build-logic` also holds `versionCodeOf` (`VersionCode.kt`), which turns a
release tag into the app's version code, and its tests (`VersionCodeTest`):
the order across alpha, beta, rc and the release, and the bounds Play sets.
The root project's `testDebugUnitTest` runs them, so `./gradlew
testDebugUnitTest` covers build-logic as well as the modules.

`coverageReport` runs the debug unit tests and reports line and branch
coverage across the modules, leaving out generated code: `R` and
`BuildConfig`, Compose's `ComposableSingletons`, serializers and `core:api`'s
generated client. It sets no threshold. The JaCoCo version is the catalog's
`jacoco`.

`-PcomposeCompilerReports=true` makes the Compose compiler write, for each
Compose module, which classes and parameters it treats as stable and which
composables can skip (`build/compose-compiler/reports/`), and its metrics
(`build/compose-compiler/metrics/`). It is off by default because it changes
the compiler's arguments, so those builds don't share cached compilations.

`compose-stability.conf` tells the Compose compiler that `core:model`'s value
types are stable: `core:model` is not compiled by it, so it can't infer that
itself, and counted parameters such as `EpisodeDetail` and `PlayerUiState` as
unstable. The file lists the data classes and sealed types by name and leaves
out the repositories, controllers, `CastedPlay` and the exceptions, which are
not values; the file says why. A new value type in `core:model`, or a shared
ViewModel's state in `core:presentation`, gets a line there. With it, the reports' unstable parameters went from 126 to 12, all of
them Android and Media3 types.

compose-lints' findings in code written before it was added are listed in
`lint-baseline.xml` in `core:ui`, `tv` and `mobile`, so `lintDebug` fails
only on new ones. Fixing a listed finding needs no baseline change. The
files hold only the `Compose*` issues: `updateLintBaseline` would add every
other current warning too, so remove those from its output.
`ComposeViewModelInjection` is turned off: screens make their ViewModel in
the body from `LocalAppServices` (Conventions).

## Dependency verification

Gradle checks every library, plugin, POM and BOM the build uses against its
SHA-256 in `gradle/verification-metadata.xml`, whether downloaded or cached,
and stops the build on a mismatch or on anything the file doesn't list.
The release job builds with the upload key in its environment, so nothing
it runs is taken on trust. The repositories are filtered too, in
`settings.gradle.kts` and `build-logic/settings.gradle.kts`: AndroidX,
`com.android.*` and Google's Play services groups come only from Google's
Maven repository, two plugins and kotlin-dsl only from the Gradle Plugin
Portal, and everything else only from Maven Central. Google's groups are
listed once, in `gradle/google-groups.txt`, which both settings scripts
read; a library from Google's repository in a group not listed there needs
its group added. Each script still names `google()` in its own
`dependencyResolutionManagement`, which is where Dependabot looks for it.

After changing a version in `libs.versions.toml`, adding a library or
plugin, or updating the Gradle wrapper, rewrite the file and commit it with
the change:

```bash
make android-verify-metadata   # from the repository root; it runs gradle/update-verification-metadata.sh
```

It takes about a minute and builds nothing: it empties the file's list,
keeping its `<configuration>`, and resolves every configuration of every
project (`gradle/resolve-all.init.gradle.kts`), with aapt2 for macOS, Linux
and Windows, since the Android Gradle plugin fetches only the build
machine's, and the Groovy sources Android Studio asks for when it syncs
(below). Read the diff before committing: only the versions that changed,
and what they bring with them, should come and go. Dependabot opens weekly
pull requests for the libraries and plugins (`.github/dependabot.yml`); it
can't update this file, so its Gradle pull requests fail CI until someone
runs the command on the branch and pushes the result.

A failure names the artifact and links a report. "If the artifacts are
trustworthy, you will need to update" means the file doesn't list it yet:
rewrite the file. "This can indicate that a dependency has been
compromised" means it differs from the recorded checksum: find out why
before rewriting anything. A change to the file also invalidates the
configuration cache. The file has checksums only, no PGP signatures: a
trial run needed 67 keys, a third of which no key server had, and Gradle
trusts a key for whole groups of libraries.

Not covered: Robolectric's `android-all-instrumented` jars, which
Robolectric downloads from Maven Central itself during unit tests (into
`~/.m2/repository`); the SDK packages `sdkmanager` installs; and the Gradle
distribution, which the wrapper's `distributionSha256Sum` and CI's wrapper
validation check instead. Sources and javadoc jars, which Android Studio
fetches and nothing runs, are trusted by name.

Android Studio's sync also has Gradle fetch the sources of the Groovy the
wrapper's Gradle ships (`org.apache.groovy:groovy*` at that Gradle's own
version, from repo.gradle.org). The sources jars are trusted, but their
`.module` files and the Groovy BOM are checked, and the build itself uses
another Groovy, so the command line never reads them. The rewrite resolves
them too, the way Gradle does for Studio. Without them, Studio's sync fails
with "Dependency verification failed for org.apache.groovy:groovy" while
`./gradlew` builds, as it did after the wrapper moved to Gradle 9.8.2
(Groovy 4.0.33).

## Baseline profile

Release builds carry a baseline profile and a startup profile in
`app/src/release/generated/baselineProfiles/`, made by the journeys in
`baselineprofile` and committed. Play (or `profileinstaller`, for other
installs) compiles the profile's code ahead of time at install, so the
first launches and the first scrolls don't run interpreted; the startup
profile puts the code a cold start runs first in the dex files (R8's dex
layout, `dexLayoutOptimization`). Debug builds don't use them.

The journeys come in three parts. Two run on the fakes and need no server:
on a phone build, a cold start to Home (the startup profile), then Home's
rows, the TV Shows grid, a show, and the player with its controls and a
picker; on the TV build, pairing, Home with the remote, the rail, the
library grid and the player. The fakes' player has no video and their
cards no posters, so the third part runs a real phone build against a
throwaway fixture server: it signs in, loads Home's posters and the Movies
grid through the image loader, and plays a file through ExoPlayer, so the
data layer, the images and the player are in the profiles too. The fakes'
own rules (`core:fake`, which a release never runs) are left out of the
merged files.

`baselineprofile/fixture_server.py` runs that server: a copy of the
fixture library's database (Test environments in the root `AGENTS.md`;
`make fixture`, then `make fixture-serve` once) on `127.0.0.1:$FIXTURE_PORT`
(28130 unless set), with each movie file given a page and a test-pattern
poster through the admin's upload route, deleted when it stops. It needs
the server built (`make build`); `FIXTURE_DIR` points it at a fixture
elsewhere. The emulator reaches it at `10.0.2.2`, and the journey types
that address in the server picker, never a server discovery lists.

The journeys find the screens by test tags (`TestTags` in `core:ui`,
spelled again in `Journeys.kt`), which `MainActivity` exposes as resource
ids, not by their wording: renaming a button doesn't break them, and a tag
removed does. A journey that fails leaves `journey-failure.png` and
`journey-failure.xml` (the window's hierarchy, with each element's
resource id) in
`baselineprofile/build/outputs/connected_android_test_additional_output/`.

Regenerate the profiles after a change to what a start or those screens
run, on throwaway `android-35` emulators (`google_apis`; AGENTS.md,
Emulators), with `ANDROID_SERIAL` naming the one to use. A portrait phone
draws the TV UI oddly, so make the TV part on a `tv_1080p` AVD:

```bash
baselineprofile/generate.sh phone   # on a phone emulator (pixel_8)
baselineprofile/generate.sh tv      # on a TV-shaped one (tv_1080p)
baselineprofile/generate.sh real    # on the phone emulator again; then commit what changes
```

Each part keeps its rules in `baselineprofile/build/profiles/`, and the
app's files are written, phone rules first, once all three are there;
`generate.sh` alone runs them all on one device. A part installs the
release build with the debug key and takes about a quarter of an hour on a
busy Mac.

The profiles go stale quietly: a renamed screen's rules match nothing, and
its replacement runs interpreted. CI's android job runs
`deploy/ci/baseline_profile.py`, which fails when more than 5% of the
profiles' rules for the app's own code (and at least 50) name classes no
longer in the sources; it lists them, most rules first. Run it from the
repository root before a pull request that renames screens:
`python3 -I deploy/ci/baseline_profile.py android`.

Three benchmarks measure what the profiles are for, each as installed
without a profile and with this one where it says so:

- `StartupBenchmark`: a cold start to the first frame, on whatever build
  you choose (without `-Pfakes`, the real app's start into the server
  picker, or Home's skeleton when signed in).
- `SignedInStartupBenchmark`: a real build's signed-in cold start against
  a fixture server, to the first frame and to Home loaded (time to full
  display, which both Homes report with `ReportDrawnWhen`). It needs the
  `fixtureServer` argument and is skipped without it.
- `FrameTimingBenchmark`: the frames drawn moving through Home's rows (a
  phone flings, a TV uses the remote) and, on a `-PforceTv` fakes build,
  through the Live TV guide with the remote; five runs, with the profile.

```bash
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=media.watchmy.client.baselineprofile.StartupBenchmark
python3 -I baselineprofile/fixture_server.py &   # from android/, for the next one
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=media.watchmy.client.baselineprofile.SignedInStartupBenchmark \
  -Pandroid.testInstrumentationRunnerArguments.fixtureServer=10.0.2.2:28130
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest -Pfakes=true -PforceTv=true \
  -Pandroid.testInstrumentationRunnerArguments.class=media.watchmy.client.baselineprofile.FrameTimingBenchmark
```

Their results (the times to first frame and full display, `bindApplication`
and the app's own `WMM …` startup sections, which `WatchMyMediaApp` marks in
the trace; the frames' CPU time and overrun) are in
`baselineprofile/build/outputs/connected_android_test_additional_output/`.
An emulator's numbers compare builds with each other; they say little about
devices, and a busy Mac makes them noisy, so compare medians of several runs.

To count what the app asks a server for, run against `fixture_server.py`,
whose log (at debug level, so media requests too) lists every request:
`--log FILE` keeps it after the server stops. Count the `"msg":"http"`
lines over a fixed stretch, such as five idle minutes on Home or five
minutes of playing a file, before and after a change.

## Conventions

- **Screens take callbacks, not the nav controller.** Their signatures are
  fixed by `TvNavHost` and `MobileNavHost`; a screen's owner may add optional
  parameters but not change the existing ones.
- **State comes from `LocalAppServices.current`**, through a ViewModel made
  with `viewModel { SomeViewModel(services.library) }`. Never reach into
  `core:data` or `core:playback` from a screen: only `core:model` interfaces,
  and the ViewModels in `core:presentation` built on them. A page both UIs
  have gets one ViewModel there, and each screen draws its state; what only
  one UI does stays in that UI's module. What a page should keep across the
  process being killed (a filter, a query, the season) goes in a
  `SavedStateHandle`: `viewModel { SomeViewModel(createSavedStateHandle(), …) }`.
  What must outlast the visit goes on the device: the TV keeps each
  library's sort and filters in `GridChoices` (`LibraryViewModel`'s `kept`,
  by "server id/type"), so Movies and TV Shows open as they were left; a
  row's See all sorts that visit only.
  A ViewModel never takes a screen's lambda to call later; it says what
  happened through its state or an `events` flow the screen collects.
  Outside a nav host (the server picker and sign-in), `viewModel {}` lives
  as long as the Activity, across every visit: give the screen its own with
  `viewModel(rememberViewModelStoreOwner()) { … }` (the TV's), or have the
  ViewModel start over when what it shows changes (the phone's sign-in).
- **Repositories throw** `ApiException` (status and the server's message);
  `SignedOutException` means show sign-in. Screens show the message.
- **The data layer** (`core:data`) builds its OkHttp clients off the main
  thread, on one connection pool shared by the API, images, the event stream
  and media (`DataServices.mediaHttpClient`, for the player and downloads).
  A request carries who it is sent as (a `Credential` tag), and one
  interceptor adds that token, only to its server, and handles 401. API
  replies with an ETag stay in a 20 MB HTTP cache and are asked again each
  time, so a quiet reload gets a 304. The event stream connects only while
  the app is in front (`DataServices.setInFront`, from the process
  lifecycle) and on a network, and again at once on the return to either;
  `LibraryRepository.libraryChanges` says which movies, episodes and
  libraries a burst of events touched. Request options, Live TV
  capabilities and the library list are asked once a sign-in, for five
  minutes, and again on their events. What a newer server brings is gated
  with `ServerFeatures(apiLevel)`.
- **Images**: `services.library.imageUrl(path, ImageSize.W342)` into
  `Artwork(url, seed = title, …)`, which draws the mockups' gradient when the
  URL is null or loading. The app's image loader adds the sign-in.
- **TV focus**: one treatment everywhere (`FocusableBox`): a 4 dp gold ring
  4 dp out, growth (`FocusGrowth`: 1.08 cards and people, 1.05 buttons,
  1.02 panel rows) and a shadow; while OK is held the target sinks and its
  ring thickens. A card's picture grows rather than the card (`FocusedArt`),
  so its words stay sharp; the main button keeps its burgundy and other
  controls lift to `TvPalette.Lift`. Recording and Live marks (the dot,
  REC, LIVE, Recording) are red (`WmmColors.Record`, fills
  `WmmColors.RecordFill`), those marks only, as on every app; burgundy is a
  button's fill. A choice that stays chosen is a gold
  tick (`OptionRow`, `PanelRow`, `TvChip`) or a gold underline (`TvTab`),
  never a gold fill or a ring, so it can't be taken for focus. Panels come
  from the right (`TvSidePanel`, 736 wide, 900 for detail); rows remember
  their focused item; a screen's remembered item survives
  navigation (`rememberFocusMemory`, and `rememberSaveable` for anything
  else focus depends on); a button busy with its work stays focusable and
  ignores OK (`FocusableBox`'s `enabled`). A row of cards shows where it
  starts: the card
  before the focused one stays in view (`KeylineSpec`), so at the start
  focus itself moves into the first place; cards scrolled past fade at the
  row's edge (`fadePassedCards`); and a run of ◀ (the key held, or pressed
  again within 400 ms) stops at the first card, where the row gives and the
  rail's active section pulses, while a ◀ pressed by itself opens the rail
  (`RowStart`). `CardRow` does all three; a row with a list of its own, as
  the show page's episodes, takes the same three. Back closes overlays first:
  handle it with `Modifier.onBack` (`core:ui`) on the screen or overlay it
  belongs to. A remote's Back arrives as a key, which Compose turns into a
  focus move before a `BackHandler` sees it; on Android 16 and later, where
  the app targets SDK 36 or higher, it arrives only as a back callback,
  with no key (seen on the `android-37.0` image: `adb shell input keyevent
  KEYCODE_BACK` reaches the app through `WindowOnBackDispatcher`). `onBack`
  takes both, once a press, and the overlay enabled last takes it first;
  `onPreviewKeyEvent` or `BackHandler` alone misses one. Something that
  floats over every screen, like the reminder banner, passes
  `overlay = true`, so it also comes before a page opened after it. A
  `TvDialog` gets Back from its own window.
- **TV sizes**: a 1080p Google TV is 960×540 dp, so `TvTheme` sets the
  density to make the window 1920 dp wide. Inside it, a 1080p pixel is a dp
  on every TV and on the tablet emulator. Text uses `TvType`'s seven sizes
  (64, 48, 34, 28, 26, 24, 22); nothing outside Settings is under 22.
  Text and anything focusable stays inside the safe area, `SafeX` (96) from
  the sides and `SafeY` (54) from the top and bottom; pictures run to the
  edges. The rail's icons sit at x 96 and content starts at `ContentStart`
  (216); wide cards are 360×202 and posters 216×324, 40 apart. `Dialog`, `Popup` and
  `ModalBottomSheet` start a new window at the real density: re-provide
  the outer `LocalDensity` inside them, as `TvDialog` does.
- **Libraries** go in `gradle/libs.versions.toml`, alphabetically, never as
  literals in a build file; then `make android-verify-metadata` ("Dependency
  verification").
- **Tests**: ViewModels and controllers get unit tests against the fakes
  (`kotlinx-coroutines-test`, Turbine); run `./gradlew testDebugUnitTest lintDebug`
  before handing work back. `core:data` decodes replies captured from a real
  server (`core/data/src/test/resources/golden/`); after a server change the
  app reads, regenerate them with `deploy/fixture/golden-replies.sh`. Screens
  and D-pad focus are tested under Robolectric with the Compose test rule on
  the fakes, as `tv`'s `HomeFocusTest` does: `performKeyInput` presses the
  remote's keys, and Back goes through the activity's
  `onBackPressedDispatcher`, which a key event from the test doesn't reach.
