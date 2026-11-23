# android/AGENTS.md

The Android app: one APK for phones, tablets and Android TV / Google TV. Read
the root [AGENTS.md](../AGENTS.md) first. [README.md](README.md) has the build
commands, the modules and the code conventions (screens take callbacks, state
from `LocalAppServices`, TV focus and sizes, libraries in
`gradle/libs.versions.toml`); follow it. The screens follow
the maintainers' designs.

## Before handing work back

```bash
cd android
./gradlew testDebugUnitTest lintDebug
```

CI also runs `coverageReport` (no threshold), `assembleDebug` and an unsigned
`:app:assembleRelease`, so R8 runs on every pull request, and validates the
Gradle wrapper. It leaves out `lintVitalRelease`, since `lintDebug` has run the
same checks; local and tagged release builds keep it. A screen change is also run on an emulator, on
the fakes and against a fixture or throwaway server, and the PR says which.
Lint warnings that were already on `main` are not yours to fix in passing.

## Rules for agents

- **Generated client.** `core/api`'s `apis/`, `models/` and `infrastructure/`
  come from `../api/openapi-3.0.json` through `./gradlew :core:api:regenerateApi`.
  CI does not check them, so regenerate and commit whenever the Android app
  uses an API change; hand-written files elsewhere in the package are kept.
  The client leaves null fields out of requests (`openApiGenerate` sets
  `explicitNulls = false`): an older server refuses a field it does not know,
  even as null, so set a request field for newer servers to null for older
  ones (as `PlaybackProfile.forApiLevel` does) and it is not sent at all.
- **Server levels.** A feature that needs a newer server is gated on the
  server's `apiLevel` and hidden below it; the app's minimum level does not
  move for it. Gate with `ServerFeatures` in `core:model` (named booleans:
  `http.features().rows`), and give a new level its boolean there. The app
  refreshes the level at each sign-in, at start and when the event stream
  reconnects.
- **Mirrors.** `core/cast`'s `CastProtocol` mirrors `web/src/cast/protocol.ts`;
  the wording in `core:model` and `core:ui` matches the Apple apps'. Change
  both sides together.
- **Never reach past `core:model`** from `tv` or `mobile` (README,
  Conventions); the shared ViewModels in `core:presentation` build on it
  alone. New behaviour gets a fake in `core:fake` so the harnesses,
  previews and tests run without a server.
- **Shared ViewModels.** A page both UIs have keeps its logic in one
  ViewModel in `core:presentation`, tested there against the fakes; the TV
  and phone screens only draw it. Where the two UIs used to differ, the
  Apple apps' `WMMFeatures` models decide.
- **Several servers.** A device is signed in to several servers, one in
  use (README, "Several servers"). The signed-in screens are rebuilt on
  every switch (`key(server.id, user)` in `MobileApp` and `TvApp`), so
  anything that must outlive one (a note, a flow's step, where to land)
  belongs to `ServerSwitchViewModel`, above them. A call to a server that
  isn't in use goes through `Http.bindTo(serverId, userId)`, which builds
  it for that server's address and sign-in; never send the current
  sign-in to an address taken from another server's reply. Before the
  sign-in of a server that isn't in use goes anywhere, its address is asked
  which server it is with no sign-in, and gets the sign-in only if it is
  the one remembered (`Http.askFirst`; README, "Asked first"). `Http` does
  it for you: a `ServerAccess` asks before each call, and account
  credentials are made nowhere else. So reach another server through
  `Http.bindTo`, `bind` or `accountCalls`, never through `http.calls` with
  a `Credential` of your own; where a token no longer in the store has to
  be sent (the end of a session), call `askFirst` yourself first, as
  `signOutQuietly` does. Test it on the test servers' recorded requests:
  no `Authorization` header at all for a stranger at the address. A test
  whose server stops being the one in use now needs that server to answer
  `GET /api/server` with its id, and should let the sign-in's own checks
  finish (`awaitIdle`) before it counts requests. The fakes are
  signed in to several too, so signing out there moves to the next server:
  a test of what a device with one server does makes them with
  `several = false` (`quietTvFakes(several = false)` on the TV).
  Two things only real servers showed, on an emulator with two throwaway
  ones. A switch takes a moment (it runs in the data layer's scope), so
  what is set before it is drawn once for the server still in use: a search
  result's landing was opened, and used up, by the old server's screens, and
  the app arrived on Home. Name the server such state is for and hand it
  only to that server's screens (`State.landingFor`); the fakes' switch
  waits their usual delay so a fakes build shows the same order. And
  `LocalStore` sets the server in use before the saved list: a collector of
  `current` on the main thread can run between the two, so count or look up
  the server in use from `current`, and only the others from `saved`.
- **Server changes.** A page's ViewModel reloads for `libraryChanges`
  (core:model's `LibraryChange`, with the items and libraries an event
  names) through `Freshness` in core:presentation, which ignores changes
  that aren't the page's and holds them while the page is hidden. Its
  screen calls `ReportShown(vm::setShown)` (core:ui) once, so a page under
  another one, or with the app in the background, reloads when next shown
  rather than with every event. Tests drive `libraryChanges`, not `changes`.
- **Text fields.** A field holds its own text in a `TextFieldState`
  (`rememberModelTextField` in core:ui) and tells the ViewModel what is
  typed; a ViewModel that sets the text itself (cleared, a recent search,
  the form started over) moves its state's `revision` on. Driving a field
  through a StateFlow lost characters to fast typing and paste. The TV's
  `OnScreenKeyboard(field = …)` edits the same state and offers "Use the
  TV's keyboard".
- **Watchlists.** Saving goes through one `WatchlistRepository`, whose
  `held` says where titles saved or removed on this device now are, and one
  session-wide `WatchlistViewModel`, which the nav hosts hand to every
  screen as `LocalWatchlistControls` (core:ui). A card that shows a movie
  or show gets its menu entry from it: `withWatchlist(item)` on the phone,
  `holdFor(item, …)` on the TV; a new card type that skips them has no
  Add to Watchlist. Lay `held` over what a screen read, never the reverse:
  a reply begun before a press would undo it (`known` takes a `since`).
- **Live TV polling.** The channel list is polled only while a screen that
  shows channels is on screen: such a screen or row calls
  `ShowsLiveChannels()` (core:ui) once. Nothing else polls it.
- **Work that outlives its screen.** A change can close the screen that
  made it: Sign out clears the sign-in and Settings gives way to sign-in at
  once, which cancels its ViewModel's scope. What must finish once started
  (a write to storage, telling the server) runs in the data layer's scope
  while the caller waits: `LocalStore.updateAndSave` writes in the store's
  scope and `RealAuthRepository.signOut` runs in `DataServices`'. Never catch
  `CancellationException` as a failure: `LocalStore.save` logged a cancelled
  write as "could not save settings", and a sign-out kept in memory alone
  signed back in after a restart.
- **Stored settings in tests.** `LocalStore` writes behind: a change is in
  memory at once and on disk a moment later. In `core:data` tests, write
  storage with `Harness.editStorage`, not `dataStore.edit`, and reopen with
  `Harness.reopen`. Both wait for the writes still running, which would
  otherwise land over the test's: `StoredSettingsFormatTest` failed on CI
  that way. `Harness.reopen` also waits for the old instance's sign-in
  checks, which wrote the old sign-in back once when they finished late.
  Request options, Live TV capabilities and the library list are kept per
  sign-in (`SessionMemo`): a test that asks twice in one sign-in moves
  `h.now` past `SessionMemo.TTL_MS` first.
- **Crypto under Robolectric.** Robolectric installs Conscrypt, beside
  which the JDK's AES-GCM fails to load ("Framework jar verification can
  not be initialized"). A test that seals with a JVM key runs with
  `@ConscryptMode(ConscryptMode.Mode.OFF)`, as `TokenSealerTest` does.
- **Pipes under Robolectric.** `ParcelFileDescriptor.createPipe` is a
  temporary file there, not a pipe: a read that comes before the writer
  finishes finds nothing. Join the writer first, as `WatchNextArtTest` does.
- **The fakes follow the wall clock.** Their Live TV schedule, and the two
  reminders they seed for tonight, come from the time the test runs. While
  a reminder is due, the TV's banner takes Back first, so a test that runs
  the whole TV app and presses Back uses `quietTvFakes()` (tv tests).
- **Build conventions.** The settings modules share (SDK levels, Java and
  Kotlin targets, test options, Compose) are set by the plugins in
  `build-logic/` (README, "Build conventions"); a module's build file holds
  only its own. Don't copy them back into a module.
- **Compose lint.** compose-lints fails `lintDebug` on new findings in the
  Compose modules. Fix new code rather than adding it to a module's
  `lint-baseline.xml`; that file lists only what predates the rules.
  A screen's parts take an interface of what they call, which the
  ViewModel implements (`ShowPageActions`), rather than the ViewModel:
  `ComposeViewModelForwarding` refuses one passed down.
- **Focus that changes state.** Where moving focus changes the screen's
  state (the TV show page's row puts the focused episode on top), remember
  each item's focus modifier and click lambda by its key. Built inline they
  are new on every move, and every visible card draws again.
- **Pagers the D-pad pages.** A focus change asks every scrolling parent to
  bring the focused item into view, and a pager answers even under a
  `BringIntoViewSpec` that says not to scroll: mid-move it settles on a page
  of its own choosing and cancels whoever was scrolling it. Home's rows
  arrived without focus that way when ◀ or ▶ was pressed during the slide.
  Put `stopBringIntoView()` on each page, move focus first and have the pager
  follow it (`ArrangedHomeContent`'s `goal`), and test keys pressed partway
  through a slide with the test clock stopped, as `HomeFocusTest` does.
- **Dependency verification.** Gradle checks every download against
  `gradle/verification-metadata.xml` (README, "Dependency verification").
  After changing a library, a plugin or the wrapper, run
  `make android-verify-metadata` and commit the file with the change, after
  reading its diff: only the versions you changed, and what they bring,
  should move. Don't hand-edit the components, and never rewrite the file
  over a checksum mismatch: find out why the download changed.
- **Dependabot's Gradle pull requests** fail the `android` job with
  "Dependency verification failed", naming the new version's POM, because
  Dependabot can't update the metadata. To fix one:
  1. Check its branch out in a worktree
     (`git worktree add <path> -B <branch> origin/<branch>`). A worktree has
     no `android/local.properties`: copy yours in, or set `ANDROID_HOME`.
  2. Run `make android-verify-metadata` (about a minute).
  3. Read the diff. No checksum of an artifact the file already listed may
     change, and `<configuration>` stays as it was. Older versions leave, or
     keep only their POMs. Each new component must be something a bumped
     library's POM asks for; the POMs are in Gradle's cache, under
     `caches/modules-2/files-2.1/<group>/<name>/<version>/`. On pull
     request 89 (19 November 2026), Play Publisher 4.1.2 and Robolectric 4.18
     moved 535 lines of the file, and every one traced back to them.
  4. Commit only that file and push it to Dependabot's branch. Dependabot
     stops rebasing a branch once someone else has pushed to it.
  5. Merge once the `android` job has passed, with a merge commit
     (Validation in the root `AGENTS.md` says why).
- **Don't change `gradle.properties`, the wrapper or the toolchain** as part
  of another change; those are their own change.
- **The fakes' artwork** is fictional and lives in `core/fake/src/fakes/assets`,
  a source set only `-Pfakes=true` builds include, so a release APK carries
  no sample art. `FakeData.backdrop` names a file there; posters stay null.
- **The player's thread in tests.** ExoPlayer throws when read off its own
  thread. Controller tests give the controller a separate IO dispatcher
  (`OffMainDispatcher` in `core/playback`'s `Fakes.kt`), on whose tasks
  `FakeMediaPlayer` throws the same way; with one dispatcher for both, Live
  TV's Automatic heartbeat read the player inside `withContext(io)` unseen.
  Read the player on the main thread, then call the server.
- **Event playlists' positions.** The server's event playlists of a file or
  a recording are live to Media3. Media3 gives a live
  position within the live window, which moves on each time the server trims
  the playlist's head, so `ExoMediaPlayer` reads and seeks those positions
  from the stream's start (`windowShiftMs`); Live TV channels (`atLiveEdge`)
  keep the window's terms. Media3 keeps an explicit start position, 0
  included, over the playlist's `EXT-X-START` default: a load at 0 starts at
  the session's start, not near its live edge (`ExoMediaPlayerLoadTest`).
- **Media3's download service from the background.** On Android 12 and
  later, never start `WmmDownloadService` from a job or receiver: Media3's
  `DownloadService` calls `startForeground` unguarded once a download runs,
  which Android refuses from the background. `DownloadRestarts` runs the
  `DownloadManager` itself from its WorkManager job instead.
- **Start-up work.** `WatchMyMediaApp.onCreate` runs for every process
  start: a reminder's alarm, the download service and WorkManager jobs
  start the process too. Work only someone using the app needs waits for
  the first `ON_START` (`FirstForeground`), as the Cast SDK does;
  WorkManager starts on first use (the manifest removes its initializer).
  Debug builds log StrictMode findings to logcat. A main-thread disk read
  the start-up makes on purpose runs in `allowingStartupReads`; anything
  else it logs is a finding to fix, not to allow.
- **Baseline profile.** CI's android job fails when the committed profiles
  have many rules for classes that are gone
  (`deploy/ci/baseline_profile.py`). Generate them again (README,
  "Baseline profile") rather than loosening the check. The journeys find
  screens by `TestTags`; keep a tag when you rework its screen.
- **Focus between two panes.** On the TV's Settings (groups beside a pane),
  `focusProperties { onEnter = … }` on a focus group did not redirect a ▶ from
  the sibling column under Robolectric: focus went to the nearest row. Catch
  the key instead (`onPreviewKeyEvent` for ▶ on the groups and ◀ on the pane).
  When a dialog removes the focused row (a device signed out from its
  confirmation), move focus in a `SideEffect` of the composition that removed
  it; a `LaunchedEffect` a frame later finds focus already elsewhere. A
  `FocusRequester` whose element has left the composition throws on
  `requestFocus()`; use `tryFocus()` (`tv/src/main/kotlin/media/watchmy/client/tv/components/Focus.kt`).
- **Focus in a panel.** A `TvDialog`'s content is a composition of its
  own. Ask for focus from inside it (a `LaunchedEffect` in its content): one
  outside runs before the panel's rows exist, the request finds nothing,
  and focus opens on the panel's first row instead. Settings' Servers page
  opened on the server in use that way.
- **A list beside other controls.** A focus search in a direction takes
  only what starts further that way than the focused item, and it judges a
  scrolling list by the list's own bounds, not its cards'. Search's results
  on the TV started under the keyboard's last column with their cards inset,
  so ▶ from 0, p, ' and - found nothing, and only the bottom row's wider
  keys reached a result. Start the list itself past the controls beside it
  and inset its content less (`ResultsInset` in
  `tv/src/main/kotlin/media/watchmy/client/tv/search/SearchScreen.kt`), or
  name the way across, as the rail does with `focusProperties { right = … }`.
  `SearchFocusTest` presses ▶ from the last key of every row.
- **Text widths under Robolectric.** The tests measure text far narrower
  than a device does: a 65-character line at 19 sp came out 65 px wide. A
  test there can't see text overflow or run into something beside it, so a
  width check passes on a layout that collides on a TV. Assert on structure
  instead (which line a thing is on, what it lines up with), as
  `SearchFocusTest` does for Search's key hints, and check the widths on an
  emulator.
- **Passwords in fields.** `rememberModelTextField` keeps its text across
  process death, so password fields use a plain `remember`ed state
  (`ChangePasswordScreen`): a password never outlives the process.
- **A row's way in.** `focusRestorer(fallback)` hands every focus request
  for one of its children to the fallback until a child has had focus, so
  a page's own restore after Back (`FocusMemory.restore`) landed on the
  related rows' first card instead of the card that opened the title. A
  row with an `enter` card (`CardRow`) takes the remembered card as its way
  in when it is composed again
  (`tv/src/main/kotlin/media/watchmy/client/tv/detail/RelatedRows.kt`).
- **TV sizes and focus.** Text on the TV uses `TvType`'s seven sizes and
  nothing under 22 outside Settings; focus is `FocusableBox`'s one treatment
  and "chosen" is a gold tick or underline (README, "TV focus" and "TV
  sizes"). Keep text and controls inside `SafeX` and `SafeY`.
- **A way across that can be missing.** `focusProperties { up = r }` (or
  `right`) with a requester whose element isn't composed cancels the move:
  focus stays put. Where the target can be absent (a button that leaves the
  page once watched, results not in yet), catch the key in
  `onPreviewKeyEvent` and try targets in turn with `tryFocus()`, as `upTo`
  in `tv/src/main/kotlin/media/watchmy/client/tv/detail/DetailParts.kt` and
  Search's microphone do.
- **Tests with a panel open.** A `TvDialog` is a window of its own, so while
  one is open there are two semantics roots and two focused nodes.
  `onRoot().performKeyInput` fails: send keys to the focused node that has
  a dialog ancestor, and Back through `ShadowDialog.getLatestDialog()`'s
  dispatcher, as `SettingsFocusTest` and `LibraryFocusTest` do.
- **Cleared semantics and focus.** A card or cell that `clearAndSetSemantics`
  to read as one thing clears its own focus state with its parts', so it
  must set `focused` itself (as `TvOnNowCard` does). Without it TalkBack is
  never told the element is in focus, and a test that finds the focused
  element by its `Focused` semantics (`HomeFocusTest`) sees nothing after a
  move onto it. Search's Live TV results and every cell of the Live TV
  guide were like that until 20 November 2026; `adb shell uiautomator dump`
  showed no focused node while one plainly had focus. Read the state only
  in the semantics, so a move of focus composes nothing again.

## Emulators

Other sessions run emulators on this Mac. Don't use, wipe or close one you
didn't start.

- Make a throwaway AVD from an installed image on a port of its own, boot it
  headless and delete it afterwards:
  `avdmanager create avd -n <name> -k "system-images;android-37.0;google_apis;arm64-v8a" -d pixel_8`
  (or `-d tv_1080p` for a TV profile), then
  `emulator -avd <name> -port 56NN -no-window -no-audio`. Address it with
  `adb -s emulator-56NN`.
- Install beside other sessions' builds with `-PappIdSuffix=.<name>`.
- The installable module is `:app` (`:tv` and `:mobile` are libraries). There
  is no TV system image; `-PforceTv=true -Pfakes=true` shows the TV UI with
  the sample library, and the fake pairing signs in by itself after a few
  seconds.
- Send one D-pad key before tapping: a phone emulator starts in touch mode,
  where nothing takes focus. From the TV rail, `KEYCODE_TAB` moves focus into
  the content when `DPAD_RIGHT` doesn't.
- `adb shell input text` scrambles fast input in Compose fields: type a
  character at a time. The sign-in screen's title and button are both "Sign
  in", so submit with `input keyevent 66`.
- Reach a server on this Mac at `10.0.2.2:<port>`, or `adb reverse tcp:<port>
  tcp:<port>` and `127.0.0.1:<port>`; loopback needs no local network
  permission, so it also tests Android 17's "Not now" case.
- The emulator's software decoders can't keep up with 1080p: test playback at
  720p or smaller. On this Mac the `android-37.0` image's video decoders
  (`c2.goldfish.*`) fail on their first buffer ("Failed to send buffer with
  BufferPool", then "Can't play this"), with either GPU mode and in debug
  builds too: test playback on an `android-35` image. `adb emu network
  speed` does not throttle; use a proxy (root file, Test environments).
- Measure motion (bounces, jumps) with `adb shell screenrecord`, frames from
  ffmpeg, and per-row comparisons between frames.
- Run periodic WorkManager work, such as the hourly new-episode check, by
  hand: WorkManager won't run it before its period is up, even when forced. Move
  the emulator's clock past the period (`adb shell cmd alarm set-time <ms>`),
  then force the job in WorkManager's namespace: `adb shell cmd jobscheduler
  run -f -n androidx.work.systemjobscheduler <package> <jobId>`, with the id
  from `dumpsys jobscheduler` (it changes each time the work is scheduled).
  Without `-n` it says "Could not find job".
- YouTube's embedded player (the phone's and the TV's trailer screens)
  draws and plays on an `android-35` emulator with the default `-gpu`. Its
  WebView needs `MATCH_PARENT` layout params: AndroidView gives a view
  without its own `WRAP_CONTENT`, and a WebView whose height wraps its
  content lays the page out 0 px high, so YouTube's frame plays the sound
  with no picture. That, not the emulators, was the black picture this
  note used to describe: before the fix the page's
  `getComputedStyle(document.documentElement).height` was `0px` and the
  frame 616×0 on a phone emulator, as on a Google TV. On `android-37.0`
  with `-gpu host`, WebView's GPU thread has aborted the app
  (`gl_version_info.cc`, "Chrome runs only on top of OpenGL ES"). Debug
  builds' WebViews take Chrome DevTools: `adb forward tcp:<port>
  localabstract:webview_devtools_remote_<pid>`, then `/json` and the page's
  WebSocket.
- YouTube's embedded player ignores its keyboard controls (k, Space, j, the
  arrows), on an emulator and on a Google TV Streamer, whoever sends them.
  The TV's trailer screen drives it through its IFrame API only, holding
  the focus itself; the WebView never takes it.

## Casting

A Google APIs emulator sees the real Cast devices on the home network. Never
pick a real TV or speaker in the chooser without the user's go-ahead: it
launches the receiver in someone's room. `adb shell dumpsys media_router`
shows the routes Play services knows and the discovery filter in force. On
Android 17 the Cast button opens the system Output Switcher, whose layout the
app can't change. The phone player harness has `casting` and `handover`
scenarios on the fakes (README).

## Release builds

Release builds are minified: R8 shrinks, optimizes and obfuscates the code and
drops unused resources (`app/build.gradle.kts`). The keep rules the app needs
beyond its libraries' own are in `app/proguard-rules.pro`; keep them to what
is loaded by name (say why in a comment), never whole packages, which would
undo the obfuscation Play scores. The mapping file rides in the bundle (the
`.aab`'s obfuscation metadata, `proguard.map`), where Play takes it for
deobfuscating crashes. After adding a library or anything
loaded by name, run a release build on an emulator: sign
`app/build/outputs/apk/release/app-release-unsigned.apk` with the debug key
(`zipalign -p -f 4`, then `apksigner sign --ks ~/.android/debug.keystore
--ks-pass pass:android`) in the scratchpad, never through a committed change,
and check that it signs in, browses, plays and casts. `apkanalyzer dex
packages --defined-only` shows which classes kept their names.

The `android` job in `release.yml` builds on every `v*` tag. While the
repository has no signing secrets its bundle and APK are unsigned, so signed
bundles for Play are built locally, and only when the user asks:

- from a worktree at the release tag, with `android/local.properties` copied
  in, and the flags CI uses:
  `./gradlew bundleRelease assembleRelease -PappVersion=vX.Y.Z -Pkotlin.compiler.execution.strategy=in-process --no-daemon --no-configuration-cache`;
- signed with the upload key by sourcing the main checkout's
  `android/secrets/upload.env` and setting `ANDROID_KEYSTORE_FILE` (README,
  "Releasing the Android app"). Never print or copy what is in `secrets/`;
- copied to the main checkout's `dist/android/` as
  `WatchMyMedia-<version>-<versionCode>.aab` and `.apk`, beside the earlier ones;
- checked with `aapt2 dump badging` (version) and `apksigner verify
  --print-certs` (signer).

Uploading to Play Console is the user's, or done only on their explicit ask.
Store screenshots are made from `-Pfakes=true` builds on a dedicated AVD, so
the titles and artwork are fictional. They and the listing text are kept by
the maintainers, outside this repository.
