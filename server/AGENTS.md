# server/AGENTS.md

The Go server. Read the root [AGENTS.md](../AGENTS.md) first: the API contract
rules, generated files and authority limits there apply here. Architecture
and decisions are in [PLAN.md](../PLAN.md) and the feature plans.

## Commands

From `server/` unless noted. Go 1.27 (`go.mod`), sqlc v1.31.1 and
golangci-lint v2.15.0, the versions CI pins.

| What | Command |
|---|---|
| Run with hot reload (data in `../data`) | `make dev` from the root (`air`) |
| Run on the fixture or a scratch data dir | launch configs `server-fixture`, `server-scratch` in `.claude/launch.json` |
| Tests | `go test ./...` |
| One package or test | `go test ./internal/playback -run TestVOD -count=1` |
| Vet as CI does | `go vet ./... && GOOS=linux GOARCH=amd64 go vet ./... && GOOS=windows GOARCH=amd64 go vet ./...` |
| Lint | `golangci-lint run ./...` (or `make lint` from the root, which also lints the web); `.golangci.yml` adds errorlint, nilerr, rowserrcheck, sqlclosecheck, bodyclose, noctx, modernize and gosec to the standard set, and `go fix ./...` applies the modernizers |
| Race detector, as CI's `server-race` job runs it | `go test -race -short -timeout 20m ./...` (`-short` skips the speed budgets; CI runs it on Linux, and the macOS-only code under the race detector in the `server-macos` job) |
| Regenerate queries | `sqlc generate` (or `make gen-sqlc`) |
| Regenerate the API documents and web types | `make gen-api` from the root |
| Speed budgets | `make bench` from the root |
| Update Go modules and the Go patch release | `make server-deps` from the root reports what is out of date; `UPDATE=patch` or `UPDATE=minor` updates and runs the checks (`server/scripts/update-deps.sh`) |

The tests use ffmpeg and ffprobe from `PATH`. CI runs them with the build the
releases ship: `deploy/macos/fetch-ffmpeg.sh arm64` from the root puts it in
`build/ffmpeg/darwin-arm64` (`linux-amd64` for the race job's Linux runner);
put that first on `PATH` to match. Tests skip
what the local ffmpeg can't do (libx265, the overlay filter) rather than fail.

## Layout

`cmd/server` is the binary (`serve`, `openapi`, `backup`, `version`, `probe-transcoder`).
Under `internal/`:

| Package | What |
|---|---|
| `api` | Every HTTP route: huma operations registered per area (`registerActivity`, …), their input and output types, and the OpenAPI document |
| `db` | Connection, embedded goose migrations, sqlc queries (`internal/db/queries/*.sql` → `*.sql.go`) |
| `library`, `scanner`, `parser`, `probe`, `metadata`, `tmdb`, `images` | Finding files, naming them, matching them on TMDB, artwork. Season line-ups, for seasons TMDB numbers differently from the files, are in `internal/metadata/seasonmap.go` ([PLAN-SEASON-MAPPING.md](../PLAN-SEASON-MAPPING.md)) |
| `artwork` | The pictures admins chose in place of TMDB's posters ([PLAN-POSTERS.md](../PLAN-POSTERS.md)): uploads and links copied once, re-encoded as JPEG and named `c-<hash>.jpg` under `<data_dir>/artwork`, the address check on links, and the sweep. `images` serves them scaled on the TMDB image route; the routes are `internal/api/posters.go`. `poster_path` stays the poster to show: change it only through `SetMoviePoster`, `SetShowPoster` and `SetSeasonPoster`, and keep the upserts' `poster_source` guard, or a refresh overwrites an admin's choice |
| `trailers` | Movies' YouTube trailers from TMDB: choosing the best three, `movies.trailers`, the background refresh (`Refresher`) and whether the Trailers from YouTube setting is on ([PLAN-TRAILERS.md](../PLAN-TRAILERS.md)) |
| `lists` | The lists rows are made from ([PLAN-ROWS.md](../PLAN-ROWS.md)): a `Source` per kind (TMDB's trending, charts, discover, lists and collections; MDBList with the admin's key; a saved library filter; an import of an IMDb or Letterboxd export or pasted lines), what they fetched in `media_lists` and `media_list_entries`, and the refresh job (`Service.Run`), with a fake MDBList in `internal/lists/mdblisttest`. Each person's row layouts (`user_preferences` `rows.*`, the household's in `settings` `rows.default.*`), their merge, the arranged screens and matching entries to the library are in `api` (`screen_layout.go`, `media_list_rows.go`) |
| `playback` | Direct play, remux and transcode decisions, bounded event and VOD HLS sessions, owned temporary-storage accounting, hardware encoders, Automatic quality's policy (`auto.go`) |
| `streamtest` | Test-only aggregate/per-viewer HTTP media shaper for `cmd/livetvfixture`; never wire it into the production server. `make streaming-check` runs the isolated F0/F1 harness. |
| `upload` | Production remote media upload accounting, start reservations, bounded response pacing and download priority. The API middleware covers old/anonymous media routes too; `streamtest` remains fixture-only. |
| `ffexec` | Runs every ffmpeg and ffprobe: its own process group, killed with the server, the end of stderr kept |
| `activity` | Plays now, history and statistics, with hand-written SQL tuned by `make bench` |
| `auth`, `turnstile`, `netguard` | Accounts, device sessions and pairing, the sign-in bot check, the real client address behind trusted proxies, and where admin and setup work |
| `segments`, `subtitles`, `opensubtitles` | Intro and credits detection; subtitles from files and OpenSubtitles |
| `trickplay` | Seek thumbnails: the background job that makes the JPEG sheets under `<data_dir>/trickplay`, their versions and the sweep, and the I-frame track VOD master playlists offer Apple TV, made from the sheets on first request ([PLAN-TRICKPLAY.md](../PLAN-TRICKPLAY.md)) |
| `settings`, `config` | Settings kept in the database; `config.yaml` and `WATCHMYMEDIA_*` variables |
| `discovery`, `events`, `backup`, `updates`, `webui` | mDNS, the in-process event bus behind `/api/events`, backups, update checks, the embedded web app |
| `watchstate` | Every change to watch state, with the guards that keep local choices safe from imports ([PLAN-PLEX-SYNC.md](../PLAN-PLEX-SYNC.md)), and offline progress applied by when it happened (`ReportAt`, `MarkAt`) |
| `offline` | Offline downloads outside the database: who may download (`offline.access`) and the transfers running now; the records, routes and sync are in `api` (`offline.go`, `offline_media.go`) ([PLAN-DOWNLOADS.md](../PLAN-DOWNLOADS.md)) |
| `livetv` | Live TV: HDHomeRun tuners, channels and live HLS sessions; listings in `internal/livetv/guide`: SiliconDust's XMLTV feed (`internal/livetv/guide/xmltv`), its pictures (`internal/livetv/guide/art`), the refresh schedule and TMDB pictures and descriptions matched to listings (`internal/livetv/guide/enrich`), and each person's favourites, reminders and listings search (`internal/livetv/guide/personal.go`), with fakes in `internal/livetv/guide/guidetest` and `internal/livetv/hdhomerun/hdhrtest` ([PLAN-LIVE-TV-GUIDE.md](../PLAN-LIVE-TV-GUIDE.md)). The tuner's `DeviceAuth` is a `hdhomerun.Secret`, which never prints. `internal/livetv/dvr` owns schedules, capture, shared tuners, finalization, deletion and progress handoff ([PLAN-LIVE-TV-DVR.md](../PLAN-LIVE-TV-DVR.md)); `internal/growing` follows owned recording files, and `internal/bgwork` gates optional background analysis |
| `plex` | The Plex adapter; `cmd/plexprobe` is its read-only probe, `internal/plex/plextest` a fictional plex.tv and server for tests. `cmd/plexsyncfixture` runs a disposable loopback provider/server for browser and client acceptance ([usage](cmd/plexsyncfixture/README.md)) |
| `plexsync` | Plex sign-in, private grants, mappings, previews, durable opt-in watched imports, guarded undo and separate history ([PLAN-PLEX-SYNC.md](../PLAN-PLEX-SYNC.md), “P3 as built”). Watch mutations and checkpoints commit through `watchstate.ImportChecked`; worker and preview shutdown are awaited. A scheduled sync (`planScheduled`) writes only items an import would mark or whose source identity is not recorded yet, and adds the rest to the run's counts in one fenced update; a manual import writes every item. One item whose identity changed in Plex is skipped as unavailable (`sourceChanges`); only most of them changing stops the run as `source_identity_changed`. `TestScheduledSyncNoChangeCost` measures a 10,000-item no-change run. |
| `httpx`, `memo`, `internal/integrations/apiclient`, `internal/tmdb/tmdbtest`, `internal/lists/mdblisttest` | What the clients for outside services share: URL redaction, size-limited reads and `Retry-After` (`httpx`); bounded TTL caches and shared calls detached from the first caller (`memo`); the Radarr, Sonarr and Seerr request and error code (`apiclient`); one fake TMDB for tests (`tmdbtest`) and one MDBList (`internal/lists/mdblisttest`) |

## Rules

**API.** Routes are `huma.Register` calls with an `OperationID`, a summary and
`doc:` tags on fields; the generated clients use the operation IDs and field
names, so treat them as public. New reply fields take `required:"false"`. Bump
`APILevel` in `internal/api/system.go` for changes the apps must know about. Then
`make gen-api` and the Swift client steps in the root file.

**Admin routes.** Wrap an admin-only operation in `adminOp` (`internal/api/auth.go`)
rather than checking inside the handler: it refuses members and admins
without access on this request before the body is read, and it marks the
operation so `TestAdminOperationsRefuseMembers` checks it. Library
maintenance (matching, rematching, hiding files, scans, and the TMDB
searches behind them) is admin work, GETs included. What members' lists
fetch from MDBList, on the admin's key, is limited in
`internal/api/media_list_limits.go`. Media tokens (`?access_token=`) open
GET and HEAD media routes only.

**SQL.** Queries go in `internal/db/queries/*.sql` and run through sqlc;
`internal/activity` keeps its own SQL. The server never runs `ANALYZE`, so
SQLite plans joins without statistics, and a plan that looks fine after
`ANALYZE` can be the slow one on a real library.

- When adding or changing a query over `media_files`, `episodes`, `shows` or
  plays, check `EXPLAIN QUERY PLAN` on a seeded database without running
  `ANALYZE`. Pin the order with `CROSS JOIN` or `INDEXED BY` where the
  planner's choice matters, as `internal/db/queries/browse.sql` and
  `internal/db/queries/metadata.sql` do.
- Adding `ANALYZE` or `PRAGMA optimize` would change many plans at once; it is
  a change of its own, not part of another.
- Trailer files are `media_files` rows too (`extra_kind = 'trailer'`,
  [PLAN-TRAILERS.md](../PLAN-TRAILERS.md) §5.2). A query that reads
  `media_files` other than through `movie_id` or `episode_files` leaves them
  out with `extra_kind IS NULL`. `media_files_library_status_idx`,
  `media_files_match_idx` and `media_files_trickplay_idx` hold `extra_kind`
  after their equality columns, so put the term where one of them can seek
  on it: the column comes after the probe JSON in a row, and reading it from
  the table reads every probe.
- Keep query files to ASCII, comments included. sqlc swaps `sqlc.arg` and
  `sqlc.slice` for `?N` by byte offset, so a `§` or an em dash earlier in
  the file shifts every later swap, and sqlc reports parse errors in
  unrelated queries ("extraneous input 'SELECTid'").
- A query's parameters are all `?` or all `sqlc.arg(name)`. Mixed, sqlc
  generates without complaint and the query fails when it runs ("missing
  argument with index 19"); `?` with a numbered `?13` is refused at
  generation ("can not mix $1 format with ? format"). So a long `?` insert
  cannot use one value twice: `UpsertMovie` sets `poster_tmdb` only on its
  conflict side for that reason ([PLAN-POSTERS.md](../PLAN-POSTERS.md) §4.1).
- Put a `sqlc.slice` after every other parameter in its query. sqlc numbers
  the parameters that follow a slice (`?2`, `?3`) as if the slice were one
  value, so once the slice holds several, those numbers point into it and
  the query quietly matches nothing or the wrong rows. The Live TV guide's
  window query returned no programmes for more than one channel until its
  slice moved last (`ListProgrammesWindow` in
  `internal/db/queries/live_tv_guide.sql`). Test such a query with at least
  two values in the slice.
- The modernc driver gains almost nothing from parallel queries: ten heavy
  queries at once take ten times one. Measure pages under the number of
  requests a client actually fires together, and prefer per-item queries to
  whole-library aggregates filtered in Go.
- `db.Open` begins transactions IMMEDIATE (`_txlock=immediate`), so one that
  reads and then writes holds the write lock from `BEGIN` and waits out
  `busy_timeout` instead of failing with "database is locked (517)"; pass
  `sql.TxOptions{ReadOnly: true}` for a transaction that only reads, and query
  through the transaction, not the pool, inside one. The pool keeps
  `db.IdleConns` (8) connections warm and does not cap open ones: code that
  holds rows or a transaction while running another query would deadlock a
  capped pool under a burst of requests.
- `TestHomeSpeed` (`internal/api/home_speed_test.go`) seeds a library the size
  of the largest real one (about 2,850 movies, 1,100 shows, 18,700 files),
  with ten 250-entry lists and three library lists on Home, and times each
  page alone and the web Home's requests as one burst, arranged and not;
  `internal/activity`'s speed test seeds plays; `internal/livetv/dvr` measures list and guide-mark bursts over 10,000 recordings without statistics. `make bench` fails over budget;
  plain `go test` runs smaller versions with looser budgets. Name such a
  test `Test...Speed`: CI's `server` and `windows-server` jobs skip `Speed$` in the
  main test step and run those tests in a step of their own, one package at
  a time. Beside the other packages on a three-core runner,
  `TestHistorySpeed` went over its budget (PR #16, 2026-11-12) though alone
  it has six times the room.

**Watch state.** Change `watch_state` only through `internal/watchstate`, never
with its queries directly: each change must also take a revision and leave a
local guard in the same transaction, or an import can later overwrite the
user's choice. Deleting a movie or episode deletes its state and guards by
trigger.

**Migrations.** New numbered files in `internal/db/migrations/` (`000NN_name.sql`)
with `-- +goose Up` and `-- +goose Down` and a comment saying why. Never edit
one that has shipped. Test data-moving migrations in `migrations_test.go` by
migrating up to the version before, inserting rows, then migrating on.
Migrations land on `main` in increasing number order: the goose provider
refuses to start a server whose database has a migration newer than one it
has not applied, so renumber a branch's migration above `main`'s latest
before merging. A number reserved for a branch stops holding once a higher
one merges (`00027` was reserved for offline downloads, which then took
`00029`); goose takes the gap.

**Platforms.** The server ships for macOS, Linux and Windows. Put OS-specific
code in `_windows.go` / `_other.go` (or `_darwin.go`) files, and vet for all
three as above. On macOS the library watcher is its own kqueue on folders
only: watching every file filled the system-wide open-file table and starved
ffmpeg. Keep file handles bounded. Go timers count monotonic time, which stops
while the computer sleeps: code that must act at a wall-clock time (the
recorder in `internal/livetv/dvr`) waits at most a minute and rereads the
clock. Libraries are often on NFS, SMB or FUSE mounts, which refuse the
no-replace rename (`renameat2` `RENAME_NOREPLACE`, macOS `RENAME_EXCL`) and
sometimes hard links; `internal/livetv/dvr/publish_unix.go` falls back in
that order.

**ffmpeg.** Run ffmpeg and ffprobe through `internal/ffexec`, never with
the standard library directly: it starts them in a process group that dies
with the server (Pdeathsig on Linux, a job object on Windows, pid files in
`<data_dir>/processes` that the next start reads on macOS), kills the group
when the context ends, keeps only the end of stderr, and holds ffmpeg's
usual options (`ffexec.FFmpegArgs`). Background work (sessions, keyframe
scans, subtitle extraction) runs under a context that shutdown ends, and
shutdown waits for it. A background job that reads a whole video
(seek thumbnails, ad-break scans, embedded subtitle extraction, downloads)
sets `LowPriority`: at full speed on a spinning disk it starved the stream of
the same file, which buffered until Automatic quality gave up (2026-11-09).
On macOS `LowPriority` is `taskpolicy -b`, the background policy: the process
keeps to the efficiency cores and gets almost no CPU while every core is busy
at normal priority (a transcode that takes 0.2 s took 74 s on a 10-core M5
with 14 busy loops; `nice` alone made no difference). That is for jobs nobody
waits for. A download, which someone does wait for, also sets `Awaited`: on
macOS `taskpolicy -c utility -d throttle`, the utility clamp with disk reads
in the throttled tier (the same 0.2 s transcode took 3.9 s). Beside a libx264
play running flat out the play was 3.7% slower than alone, against 2.4% beside
a background process, and no slower to start; set `Awaited` only where
someone waits. A test that waits for a low-priority process slows or fails
with the machine's load, and the suite's own parallel
transcodes are that load. `TestDownloadSession` (`internal/playback`) is the
one test that runs a download's ffmpeg at low priority, before its package's
parallel tests; tests of what a download serves call
`Manager.DownloadsAtNormalPriority` first, as `TestOfflineHLSDownloads` does.

**Playback files.** Sessions write under
`<transcode.dir>/watchmymedia-sessions`, the only part of `transcode.dir`
the server clears at startup; the folder may be shared (`/tmp`,
`/dev/shm`). Encoder detection runs in the background after startup and is
kept in `<data_dir>/transcoder.json`; `Manager.Capabilities` waits for it.
On Windows, mutable event and VOD run playlists are renamed to private snapshots
before opening; share-delete alone does not allow FFmpeg to replace an open
destination. Event sessions retain the last immutable bytes for repeated HTTP
reads. Immutable init/media pieces continue to use `OpenShared`.
After a successful FFmpeg exit, publish any complete leftover `.tmp` playlist
before closing the writer's done channel, excluding snapshot readers during
that final rename. FFmpeg can exit zero after its final Windows rename fails;
do not add an end marker to cached, possibly older playlist bytes.
Live HLS output paths use `filepath.ToSlash`: FFmpeg finds the fMP4 init
directory using `/` even on Windows. Keep its working directory unchanged.
Delete file and live
session output through `Manager.RemoveOutputDir`, which excludes storage scans;
opening a directory during deletion can otherwise reject unrelated playback.
The cache key includes the explicit adapter and probe revision; invalidate old
measurements when probe semantics change. The real-input 4K HDR-to-SDR pipeline
must report all 48 frames before admission. Time its frames, not ffmpeg's start-up
(`pipelineFPS`), and run it at normal priority: on macOS `LowPriority` keeps a
process to the efficiency cores, and a busy Mac then measured a tenth of a
session's rate. The listings and one-frame checks run at normal priority too
(they take milliseconds; below it, on a busy Mac, they ran past their timeouts
and detection fell back to software). Only building the 4K input starts below
normal priority, and it is built again at normal priority when that times out.
Keep probe diagnostics admin-only;
`probe-transcoder` emits a local report without reading library data or starting
network services. `internal/hwdevice` holds adapter validation shared with config
and the Windows CUDA/DXGI inventory. The normal `gpu-adapters` CLI and background
scanner isolate driver calls in managed child processes with deadlines and bounded
output/workers. Keep native ordinals, exclude DXGI software adapters without
renumbering, and keep inventory admin-only and out of the encoder cache. Never
infer encoding support from a name. Refresh/cancel inventory with detection and
reject results from obsolete generations. The `--native` CLI mode is internal.
On Linux a device index is a DRM render node (`renderNode`: N is
`/dev/dri/renderD128+N`) for QSV and VAAPI, named through `-init_hw_device`
for decoder, filters and encoder alike; VAAPI always names one, since its
encoder takes only uploaded frames. The probe records a `device` diagnostic
when no node is visible and explains ffmpeg's "Permission denied" on one
([PLAN-UNRAID.md](../PLAN-UNRAID.md)); tests stand in for another platform
with `detectHardwareOn` and `pipelineOnOS`.
Add an encoder to the table in `internal/playback/encoders.go`. Store and
read probes with `probe.Encode` and `probe.Parse`: chapters, the start
time, each video stream's codec tag and Dolby Vision base layer and level,
and each audio stream's commentary and audio-description flags
([PLAN-LANGUAGES.md](../PLAN-LANGUAGES.md)) are kept there for playback but
are `json:"-"`, so the API does not show them.

**Text.** Folder and file names on real servers are often NFD (decomposed
accents). Normalise before comparing names or sending them to TMDB, as
`parser` and `metadata` do.

**Outside services.** Errors from clients that put keys in URLs go through
`httpx.Redact`, replies are read with `httpx.ReadLimited` (which fails rather
than cuts), lookups many callers repeat go through `memo`, and clients that
send API keys refuse redirects to another host (`apiclient.SameHost`).

**Config.** New options go in `internal/config` with a `WATCHMYMEDIA_*`
variable, and in `deploy/config.example.yaml` with a comment. API keys
(TMDB, OpenSubtitles) come from the environment or release `ldflags`, never
the source.

**Tests.** Standard `testing` only (no assertion libraries), `t.TempDir()`
and `t.Setenv` for isolation, tests beside the code. Tests that call outside
services are opt-in behind an environment variable, like
`WATCHMYMEDIA_TEST_TURNSTILE`. In `internal/api`, `newTestServer` and
`newTestServerWith` return a `testEnv` of the test's own (server, queries,
an admin-signed-in `client`, bus), so tests call `t.Parallel()`; make
requests with `doJSON`, and keep package globals out of tests. A test that
waits on timers runs in a synctest bubble on an in-memory server
(`startTestEnv` with `httptest.NewTestServer`).

- Open a test database with `dbtest.Open(t)` (`internal/db/dbtest`), or
  `dbtest.OpenPath` where the test needs the file's path. Each is a copy of
  one database the test binary migrated as the server does: migrating takes
  about 0.9 s under the race detector against 30 ms without, and calling
  `db.Migrate` per test made `internal/api` three times slower there. `internal/db`'s own tests, which cannot import it,
  use `openTest` and `migrateTo`.
- Don't sleep to wait for goroutines or timers. Use the standard library's
  synctest package for code driven by timers (the watcher's debouncer,
  coalesced scans), or wait on an event or channel; give code that compares
  real file dates an injectable clock (the scanner's `now` and `after`).
- The server offers an adaptive family (`Manager.StartVODLadder`) only when
  this machine's encoder was fast enough: the first segment in a fifth of its
  length with four slots (`ladderPreparationReady`), inside the ladder's
  startup and segment deadlines. On a busy runner under the race detector it
  is not, the server falls back to a single rendition as it should, and a
  test that demanded a family fails
  (`TestAdaptiveVODConcurrentColdSeekAndIndependentStop` on CI, 2026-11-11
  and 2026-11-12). A test that needs a family, or needs one refused, calls
  `Manager.LadderPreparationTook` with the time each rung counts as having
  taken, as `adaptiveManager` in `internal/api/adaptive_test.go` does; the
  rule still decides, on that time and the configured slots
  (`TestAdaptiveVODFamilyNeedsFastPreparation`). The tests that measure the
  real encoder are in `internal/playback`: they skip when refused
  (`startLadderOrSkip`) or slow ffmpeg down on purpose (`slowLadderFFmpeg`).
  A refused family reaches the client as an ordinary reply with no error;
  the reason is the manager's debug line "VOD ladder refused", which the
  adaptive tests print when they fail.
- `internal/auth`'s `TestMain` lowers the Argon2 cost of new hashes; only
  `TestPasswordHashing` uses the real parameters.

**Events.** Clients' event streams subscribe with `Bus.Stream`, filtered to
the user, which closes the channel when the stream falls behind so the
client reconnects and refetches; the server's own loops use `Bus.Subscribe`.

## Debugging playback

- Read the log before theorising: `playback session started … mode=` lines
  say what each client got (direct, remux, audio, transcode); match them by
  client address and time. The menu bar app writes
  `~/Library/Logs/WatchMyMedia/server.log` on the machine it runs on.
- Behind a reverse proxy on the same machine, every client arrives from
  127.0.0.1 unless `network.trusted_proxies` is set, so addresses in the log
  can't tell clients apart there. A trusted proxy must send
  `X-Forwarded-For`: a request from its address without one counts as that
  machine's own (the menu bar app's do), and one whose header doesn't end in
  an address as an unknown, remote client (`netguard`).
- Cast and HLS problems: test with HEVC and E-AC-3 sources, not just the
  fixture's H.264. The `CODECS` written on the master playlist and what the
  TV's Media Source Extensions accept decide whether a cast plays.
- Dolby Vision ([PLAN-HDR-PROFILE.md](../PLAN-HDR-PROFILE.md)): ffmpeg writes
  the `dvcC`/`dvvC` record into MP4 and HLS only with `-strict unofficial`,
  so a copy without it loses the Dolby Vision. `probetest.DolbyVisionClip`
  makes a clip with a record (no RPU) for tests; check what a session
  wrote with `ffprobe -show_entries stream=codec_tag_string:stream_side_data`
  on its `init.mp4`.
