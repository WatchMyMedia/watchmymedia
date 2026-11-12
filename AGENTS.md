# AGENTS.md

How to work on WatchMyMedia as a coding agent. It covers the whole repository.
The folders with code of their own each have an `AGENTS.md` with more:
[server](server/AGENTS.md), [web](web/AGENTS.md) (the web app and the Cast
receiver), [android](android/AGENTS.md), [apple](apple/AGENTS.md),
[deploy](deploy/AGENTS.md) (packaging, CI support, the fixture) and
[windows](windows/AGENTS.md).
Read the one for the folder you are changing as well as this file. The user's
instructions in the conversation come first; then the nearest `AGENTS.md`; then
this one. Each `CLAUDE.md` imports its folder's `AGENTS.md` for Claude Code;
the root one adds notes on Claude Code's own tools.

The READMEs are for people and hold the full build and install instructions.
This file does not repeat them; it says where they are and what an agent must
not get wrong.

Where an `AGENTS.private.md` sits beside an `AGENTS.md`, read it too. It holds
the maintainers' rules for releases, the site and the plans, which are kept
outside the public repository, and it comes first where the two differ.

## What this is

A self-hosted media server and its clients. One Go server (with the React web
app embedded in the binary) scans Movies and TV folders, matches them on TMDB
and streams to:

- the web app, and the Cast receiver built from the same folder (`web/`);
- one Android APK for phones, tablets and Android TV / Google TV (`android/`);
- the iPhone, iPad and Apple TV apps on a shared Swift package (`apple/`);
- the macOS menu bar app that runs the server (`macos/`).

Releases ship the server for macOS, Linux, Windows and Docker, the Mac app,
the APK and bundle (Play), and the iOS app (TestFlight). The people who run
WatchMyMedia are the audience for `CHANGELOG.md` and the README.

## Layout

| Path | What |
|---|---|
| `server/` | Go module: chi + huma API, SQLite (modernc) through sqlc and goose, ffmpeg orchestration, embedded web UI |
| `web/` | React + Vite web app (`src/`), Cast receiver (`receiver/`) and Samsung prototype (`tizen/`, signing and TV tests deferred) |
| `api/` | The OpenAPI documents the native apps generate their clients from. Generated |
| `android/` | Android app; modules and conventions in [android/README.md](android/README.md) |
| `apple/` | iPhone, iPad and Apple TV apps and the `Kit` package; layout and conventions in [apple/README.md](apple/README.md) |
| `windows/` | Native Go/Win32 tray app; built by `make windows-app` (development installer; signing pending) |
| `macos/` | The menu bar app (SwiftPM, Sparkle); built by `macos/build-app.sh` |
| `deploy/` | Dockerfile and compose files, the Unraid template, the fixture library, CI support |
| `brand/export/` | The icons and logos the Mac, Windows and Samsung builds read. Generated; don't edit |
| `Makefile` | `make help` lists every target |

Not source, never commit: `data/` (databases, the fixture library), `bin/`,
`build/`, `dist/` (kept release DMGs and signed Android bundles),
`server/internal/webui/dist/*`, `web/dist/`, `web/dist-receiver/`,
`web/dist-tizen/`, `android/secrets/`,
`android/local.properties`, `.claude/worktrees/`, `server.log`.

## Sources of truth and generated files

Change the source, run the generator, commit both. A hook in
`.claude/settings.json` refuses direct edits to the generated paths.

| Generated | Source | Regenerate | Checked by CI |
|---|---|---|---|
| `server/internal/db/*.sql.go`, `models.go`, `db.go` | `server/internal/db/queries/*.sql`, `server/internal/db/migrations/*.sql` | `make gen-sqlc` (sqlc v1.31.1) | `server` job |
| `api/openapi.json`, `api/openapi-3.0.json`, `web/src/api/openapi.json`, `web/src/api/schema.d.ts` | huma types in `server/internal/api` | `make gen-api` | `contract` job |
| `apple/Kit/Sources/WMMAPI/Generated/` | `api/openapi.json` | `make apple-api` (after `make gen-api`) | `apple` job |
| `android/core/api/src/main/kotlin/media/watchmy/client/core/api/{apis,models,infrastructure}/` | `api/openapi-3.0.json` | `cd android && ./gradlew :core:api:regenerateApi` | **no**: regenerate when Android uses the change |
| `android/gradle/verification-metadata.xml` | the libraries and plugins the Android build downloads | `make android-verify-metadata` | `android` job: a library missing from it fails the build |
| `apple/WatchMyMedia.xcodeproj`, `apple/App/Info.plist`, `apple/TV/Info.plist` (not committed) | `apple/project.yml` | `make apple-gen` | built by the `apple` jobs |
| `server/internal/webui/dist/` (not committed) | `web/` | `make web` | — |

Two hand-kept mirrors must change together:

- `web/src/cast/protocol.ts`, `android/core/cast` (`CastProtocol`) and
  `apple/Kit/Sources/WMMCast/CastProtocol.swift`: the Cast messages. Changes
  are additive; each side ignores what it does not know.
- Shared wording: the Apple apps word dates, durations, sizes and track labels
  as the Android app does (`WMMModel` helpers in Swift, the same strings in
  Kotlin), so the apps read alike.

## The server and app contract

Apps in people's hands talk to older and newer servers. Every API change has
to keep both working.

- **New reply fields are optional.** Tag them `required:"false"` (see `Casting`
  in `server/internal/api/system.go`). A plain `json:"x"` makes the field
  required in `api/openapi.json`, and the regenerated Swift client then rejects
  every reply from a server older than the field.
- **Changes are additive.** Don't rename or remove fields, enum values or
  routes that a released app reads.
- **`APILevel`** in `server/internal/api/system.go` goes up by one for any
  change the native apps must know about; add the level's meaning to the
  comment above it. Apps gate a new feature on `apiLevel >= N` and keep their
  minimum level where it is, unless the user decides otherwise.
- **After any API change**, run `make gen-api`, then `make apple-api` and
  `make apple-test`, before pushing. CI's apple job only runs when `api/`
  changes, so a broken Swift client otherwise shows up late. Regenerate
  Android's client when the Android app uses the change.
- **Migrations** are new numbered goose files in
  `server/internal/db/migrations/` with an Up and a Down. Never edit a
  migration that has shipped. When a migration moves data, test it in
  `server/internal/db/migrations_test.go`.

## How work goes

1. **Read first**: this file and the folder's `AGENTS.md` and README.
2. **One PR per change and platform**, on a branch named `taimoorq/<kebab-name>`.
   A batch of fixes, such as a review's findings, is one change: put it in
   one PR with a commit per fix, not one PR each, since every PR runs the CI
   jobs for what it touches (the Windows and Apple jobs take 12–20 minutes
   each, plus any wait for a macOS runner). Split only when a
   fix needs a different reviewer, could hold the rest back, or is on
   another platform. Small direct commits to `main` have also been normal;
   ask when unsure whether the user wants a PR. Bring a branch up to date by
   merging `origin/main` into it.
3. **Validate by what changed** (next section), and say plainly what was not
   checked, such as real devices or a Chromecast.
4. **Update the docs in the same change**: the README or folder README when a
   command or behaviour changes, this
   file or a folder `AGENTS.md` when a rule, path or command it names changes
   (`make agents-check` finds paths and targets that no longer exist).
   `CHANGELOG.md` is written for a release in its own PR
   (`taimoorq/changelog-X.Y.Z`).

### Parallel sessions and the shared checkout

Several agent sessions use this repository at once.

- Do multi-step work in a git worktree under `.claude/worktrees/<name>`. A new
  worktree needs `android/local.properties` copied from the main checkout to
  build Android.
- The main checkout's branch can be switched by another session mid-task. Run
  `git branch --show-current` before every commit, and commit to your branch
  early.
- Stage only your own files, by path. `git status` shows other sessions' work
  (for example a modified `android/gradle.properties`): leave it alone.
- Ports 27587 and 27588 and the default emulators and simulators are usually
  in use. Give throwaway servers, emulators and simulators their own port or
  name, and delete them afterwards (see Test environments).

### Commits and pull requests

- Subject: imperative, sentence case, no prefix or trailing period, and named
  for what changes for people: "Jump to any letter in the A–Z bar, not just
  this page's", "Keep the new reply fields optional and regenerate the Swift
  client".
- Body: what was wrong and how it was found (numbers, dates, the device or
  log), then what changes and why, in plain prose or short bullets.
- PR body: `## What changes` and `## Testing`. The
  Testing section lists what ran (test counts, emulator or simulator, server
  build, network cap) and ends with what was not checked.
- No `Co-Authored-By` trailer or other attribution in commits or PR bodies,
  even though some old commits have one.
- `main` requires no checks, so `gh pr merge --auto` merges at once rather
  than waiting for CI. Merge only after the PR's checks have finished.
- A stacked PR (one whose base is another PR's branch) merges into that
  branch, not `main`, even after the base PR has merged. Its work then never
  reaches `main`. Before merging a stacked PR,
  check that its base PR is still open, or retarget it with
  `gh pr edit <n> --base main`. Push nothing to a PR's branch after asking
  for it to be merged.
- PRs against `main` that build on each other's branches stay one line in
  merge order: bring each one up to date by merging the branch below it, not
  `origin/main` on its own. When each merged `main` separately, the next PR up
  had two merge bases (`git merge-base --all`) once the one below merged, and
  GitHub reported conflicts that git resolved cleanly. Before asking for a chain to be merged, run a `git merge-tree
  --write-tree` simulation of the merge order and check for one merge base at
  each step.

## Validation

Run the checks for the surfaces you touched before handing work back. CI
(`.github/workflows/ci.yml`) runs the same ones on GitHub-hosted runners.
Each job runs only when what it builds or tests changed: the `changes` job's
path filters in `ci.yml` decide (the server jobs for `server/`, the apps for
their folder and `api/`, `scripts` for `deploy/`, the workflows and the docs),
and a change to `ci.yml` or a manual run runs everything. When a new job or
input is added, give it a filter; `deploy/ci/security_test.py` checks every
job has one. The Go jobs restore Go's build and test caches from the newest
push to `main`, so a PR reruns only the Go packages it changes and those that
depend on them. Only `main` saves those caches, and `main` and manual runs run
every test again (`go clean -testcache`). A change to the pinned ffmpeg starts
from an empty cache, since Go can't see the ffmpeg its tests run. The
`android` job's Gradle caches, kept by Gradle's setup-gradle action, work the
same way: restored everywhere, saved only by `main`. Its configuration cache
is encrypted with `GRADLE_ENCRYPTION_KEY`, the only secret `ci.yml` may read
(`security_test.py` checks); without it, as on fork PRs, that cache is skipped.

| Changed | Run |
|---|---|
| Go code | `cd server && go vet ./... && go test ./...`; `make lint` for golangci-lint |
| SQL queries or migrations | `make gen-sqlc`, the tests, and the query plan without statistics ([server/AGENTS.md](server/AGENTS.md)) |
| History, statistics, home, browse or recording-list queries | `make bench` |
| API types | `make gen-api`, `make apple-api`, `make apple-test`, `cd web && pnpm typecheck`; Android's client if used |
| Web app | `cd web && pnpm lint && pnpm typecheck && pnpm test && pnpm build` |
| Cast receiver | the web checks plus `cd web && pnpm build:receiver` |
| Android | `cd android && ./gradlew testDebugUnitTest lintDebug` (CI adds `coverageReport`, `assembleDebug` and an unsigned `:app:assembleRelease` without `lintVitalRelease`) |
| Apple Kit | `make apple-test` |
| iPhone or iPad app, or UIKit | `make apple-test-sim` |
| Apple TV app | `make apple-tv-test-sim` |
| Release scripts or `release.yml` | `python3 -I -m unittest discover -s deploy/release -p '*_test.py'` and `python3 -I -m unittest discover -s deploy/ci -p '*_test.py'` |
| `AGENTS.md`, `CLAUDE.md`, or paths and targets they name | `make agents-check` |
| Dockerfile | `make docker` |
| A screen or flow | run it: the web app in a browser, the apps on an emulator or simulator, on the fakes and against a fixture server |

Gaps the usual checks miss:

- The fixture library is all H.264 with made-up, unmatched titles, and has no
  artwork; the fakes have no posters and only a few fictional backdrops
  (`-Pfakes=true` builds). Test playback and Cast changes with an HEVC or
  E-AC-3 file too, and layout with real images where it matters.
- Emulators and simulators are not devices. Say which device checks (Cast,
  AirPlay, PiP, HDR, a real TV remote) are still to do.

## Test environments

- **Fixture server**: `make fixture` once, then `make fixture-serve` (port
  27588; accounts at the top of `deploy/fixture/fixture.sh`). From an Android
  emulator it is `10.0.2.2:27588`, from an iOS simulator `localhost:27588`.
- **Throwaway servers**: copy `data/fixture/watchmymedia.db` with
  `sqlite3 … ".backup …"` into a scratch folder, give the copy its own
  `server.id` and `server.name` in `settings` (copies of one database count as
  one server to the apps), and run on your own port with
  `WATCHMYMEDIA_DATA_DIR`, `WATCHMYMEDIA_PORT`, `WATCHMYMEDIA_HOST=127.0.0.1`
  and `WATCHMYMEDIA_DISCOVERY=false`. An empty data folder gives a server
  that still needs setup.
- **Fakes**: every client runs without a server on its fakes (`-Pfakes=true`
  on Android, `-fakes` on Apple), with a fictional library.
- **Slow links**: `dnctl` needs sudo and `adb emu network speed` does not
  throttle current emulators. Put a small rate-limited TCP proxy in front of a
  throwaway server and point the client at the proxy.
- **Dev servers**: `make dev` (Go with hot reload, :27587) and `make web-dev`
  (Vite, :5173). The launch configurations in `.claude/launch.json` start the
  web app, the receiver and fixture or scratch servers.

## Authority

Being able to run something is not permission to. These need the user's
explicit go-ahead in the current conversation, every time:

- **Releases**: `make release-tag`, `deploy/release/tag.sh`, pushing any `v*`
  tag (it starts `release.yml`, which builds, signs and uploads every app),
  creating or editing a GitHub release, `make apple-upload`.
- **Signing as the company**: running CI with "Also sign and verify a Windows
  installer" (it signs as Eldwick Software LLC), and changing the Azure
  Artifact Signing account, profile, its Entra app or the `windows-signing`
  environment.
- **Production deploys**: `pnpm deploy:receiver` (the Cast receiver on
  cast.watchmy.media, which every cast loads) and any other `wrangler deploy`.
- **Shared branches and settings**: pushing to `main`, merging pull requests,
  force-pushing, changing repository secrets or variables (`gh secret set`).
- **Real devices and servers on the user's network**: casting to or launching
  anything on the TVs and speakers in the house, and signing in to the user's
  own servers (the hosted instance, or the server on the LAN that discovery
  finds) from tests, emulators or simulators. Use fixture and throwaway
  servers instead, and don't pick the real server or a real TV in a chooser.
- **Store consoles**: Play Console and App Store Connect. Declarations, legal
  agreements, pricing, privacy answers and API keys are the user's to do.
- **Destructive local commands**: `make clean` and `make release` delete
  `dist/`, which keeps the release DMGs and the signed Android bundles. Don't
  delete other sessions' worktrees, emulators, simulators or servers.

Never, even when asked in passing:

- read, print or copy the contents of `android/secrets/`, `.env` files,
  `*.p8`, `*.p12`, `*.cer` or keystores into the conversation, a commit or a
  log (build commands may source them, as the README's signed-build recipe
  does);
- commit signing files, keys, databases, logs or anything under `data/`;
- put real people's names, library contents or server addresses in fixtures,
  tests or screenshots meant for the repo (the fixture and the fakes are
  fictional on purpose).

## Known unknowns

- The `ios` release job needs an App Store Connect API key with an Admin role
  in the repository secrets; until the user sets one it builds for the
  simulator only. Android release builds from CI are unsigned; signed bundles
  are built locally ([android/AGENTS.md](android/AGENTS.md)).
