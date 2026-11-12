# android/AGENTS.md

The Android app: one APK for phones, tablets and Android TV / Google TV. Read
the root [AGENTS.md](../AGENTS.md) first. [README.md](README.md) has the build
commands, the modules and the code conventions (screens take callbacks, state
from `LocalAppServices`, TV focus and sizes, libraries in
`gradle/libs.versions.toml`); follow it. The plan is
[PLAN-CLIENTS.md](../PLAN-CLIENTS.md) §5; the screens follow
`design/mockups/android/`, `design/mockups/android-tablet/` ([PLAN-TABLETS.md](../PLAN-TABLETS.md))
and `design/mockups/android-tv/`.

## Before handing work back

```bash
cd android
./gradlew testDebugUnitTest lintDebug
```

CI also runs `coverageReport` (no threshold), `assembleDebug` and an unsigned
`:app:assembleRelease`, so R8 runs on every pull request, and validates the
Gradle wrapper. A screen change is also run on an emulator, on
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
  move for it. The app refreshes the level at each sign-in and at start.
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
- **Stored settings in tests.** `LocalStore` writes behind: a change is in
  memory at once and on disk a moment later. In `core:data` tests, write
  storage with `Harness.editStorage`, not `dataStore.edit`, and reopen with
  `Harness.reopen`. Both wait for the writes still running, which would
  otherwise land over the test's: `StoredSettingsFormatTest` failed on CI
  that way.
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
- **Dependency verification.** Gradle checks every download against
  `gradle/verification-metadata.xml` (README, "Dependency verification").
  After changing a library, a plugin or the wrapper, run
  `make android-verify-metadata` and commit the file with the change, after
  reading its diff: only the versions you changed, and what they bring,
  should move. Dependabot's Gradle pull requests need the same command run
  on their branch. Don't hand-edit the components, and never rewrite the
  file over a checksum mismatch: find out why the download changed.
- **Don't change `gradle.properties`, the wrapper or the toolchain** as part
  of another change; those are their own change.
- **The fakes' artwork** is fictional and lives in `core/fake/src/fakes/assets`,
  a source set only `-Pfakes=true` builds include, so a release APK carries
  no sample art. `FakeData.backdrop` names a file there; posters stay null.
- **Focus tests and cleared semantics.** `HomeFocusTest` finds the focused
  element by its `Focused` semantics. A card that `clearAndSetSemantics` to
  read as one thing must set `focused` itself (as `TvOnNowCard` does), or the
  test sees nothing focused after a move onto it.

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
- YouTube's embedded player (the phone's trailer screen) never draws on these
  emulators: the WebView area stays black on `android-35` with any `-gpu`
  mode, though the video plays (`getPlayerState()` is 1), and on
  `android-37.0` with `-gpu host` WebView's GPU thread aborts the app
  (`gl_version_info.cc`, "Chrome runs only on top of OpenGL ES"). A plain page
  or `<video>` in the same WebView draws, so check the screen's layout,
  messages and hand-offs on an emulator and the picture on a device. Debug
  builds' WebViews take Chrome DevTools: `adb forward tcp:<port>
  localabstract:webview_devtools_remote_<pid>`, then `/json` and the page's
  WebSocket.

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
Store screenshots are generated locally in `play-listing/` (absent in a
fresh checkout), from `-Pfakes=true` builds on a dedicated AVD so the titles
and artwork are fictional.
