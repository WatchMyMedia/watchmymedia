# server/AGENTS.md

The Go server. Read the root [AGENTS.md](../AGENTS.md) first: the API contract
rules, generated files and authority limits there apply here.

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
| `library`, `scanner`, `parser`, `probe`, `metadata`, `tmdb`, `images` | Finding files, naming them, matching them on TMDB, artwork. Season line-ups, for seasons TMDB numbers differently from the files, are in `internal/metadata/seasonmap.go`; episodes named after files, for a known show's episodes TMDB does not list, in `internal/metadata/filenamed.go` |
| `artwork` | The pictures admins chose in place of TMDB's posters: uploads and links copied once, re-encoded as JPEG and named `c-<hash>.jpg` under `<data_dir>/artwork`, the address check on links, and the sweep. `images` serves them scaled on the TMDB image route; the routes are `internal/api/posters.go`. `poster_path` stays the poster to show: change it only through `SetMoviePoster`, `SetShowPoster` and `SetSeasonPoster`, and keep the upserts' `poster_source` guard, or a refresh overwrites an admin's choice |
| `trailers` | Movies' YouTube trailers from TMDB: choosing the best three, `movies.trailers`, the background refresh (`Refresher`) and whether the Trailers from YouTube setting is on |
| `related` | Related titles at the end of movie and show pages: each title's tags from TMDB (`movie_tags`, `show_tags`: recommendations, keywords, genres, billed cast, director or creator, language) and a movie's collection, written when the matcher stores a title (`StoreMovie`, `StoreShow`, in its transaction); the scoring (`Similar`, weights in `score.go`); and the background job (`Refresher`) that fills in titles matched before and refetches a title whose page was opened with data older than 30 or 90 days. Pages never wait on TMDB. The routes are `internal/api/related.go` |
| `lists` | The lists rows are made from: a `Source` per kind (TMDB's trending, charts, discover, lists and collections; MDBList with the admin's key; a saved library filter; an import of an IMDb or Letterboxd export or pasted lines), what they fetched in `media_lists` and `media_list_entries`, and the refresh job (`Service.Run`), with a fake MDBList in `internal/lists/mdblisttest`. Each person's row layouts (`user_preferences` `rows.*`, the household's in `settings` `rows.default.*`), their merge, the arranged screens and matching entries to the library are in `api` (`screen_layout.go`, `media_list_rows.go`) |
| `playback` | Direct play, remux and transcode decisions, bounded event and VOD HLS sessions, owned temporary-storage accounting, hardware encoders, Automatic quality's policy (`auto.go`) |
| `streamtest` | Test-only aggregate/per-viewer HTTP media shaper for `cmd/livetvfixture`; never wire it into the production server. `make streaming-check` runs the isolated F0/F1 harness. |
| `upload` | Production remote media upload accounting, start reservations, bounded response pacing and download priority. The API middleware covers old/anonymous media routes too; `streamtest` remains fixture-only. |
| `ffexec` | Runs every ffmpeg and ffprobe: its own process group, killed with the server, the end of stderr kept |
| `activity` | Plays now, history and statistics, with hand-written SQL tuned by `make bench` |
| `auth`, `turnstile`, `netguard` | Accounts, device sessions and pairing, the sign-in bot check, the real client address behind trusted proxies, and where admin and setup work |
| `segments`, `subtitles`, `opensubtitles` | Intro and credits detection; subtitles from files and OpenSubtitles |
| `trickplay` | Seek thumbnails: the background job that makes the JPEG sheets under `<data_dir>/trickplay`, their versions and the sweep, and the I-frame track VOD master playlists offer Apple TV, made from the sheets on first request |
| `settings`, `config` | Settings kept in the database; `config.yaml` and `WATCHMYMEDIA_*` variables |
| `discovery`, `events`, `backup`, `updates`, `webui` | mDNS, the in-process event bus behind `/api/events`, backups, update checks, the embedded web app |
| `watchstate` | Every change to watch state, with the guards that keep local choices safe from imports, and offline progress applied by when it happened (`ReportAt`, `MarkAt`) |
| `offline` | Offline downloads outside the database: who may download (`offline.access`) and the transfers running now; the records, routes and sync are in `api` (`offline.go`, `offline_media.go`) |
| `livetv` | Live TV: HDHomeRun tuners, channels and live HLS sessions; listings in `internal/livetv/guide`: SiliconDust's XMLTV feed (`internal/livetv/guide/xmltv`), its pictures (`internal/livetv/guide/art`), the refresh schedule and TMDB pictures and descriptions matched to listings (`internal/livetv/guide/enrich`), and each person's favourites, reminders and listings search (`internal/livetv/guide/personal.go`), with fakes in `internal/livetv/guide/guidetest` and `internal/livetv/hdhomerun/hdhrtest`. The tuner's `DeviceAuth` is a `hdhomerun.Secret`, which never prints. `internal/livetv/dvr` owns schedules, capture, shared tuners, finalization, deletion and progress handoff; `internal/growing` follows owned recording files, and `internal/bgwork` gates optional background analysis |
| `plex` | The Plex adapter; `cmd/plexprobe` is its read-only probe, `internal/plex/plextest` a fictional plex.tv and server for tests. `cmd/plexsyncfixture` runs a disposable loopback provider/server for browser and client acceptance ([usage](cmd/plexsyncfixture/README.md)) |
| `plexsync` | Plex sign-in, private grants, mappings, previews, durable opt-in watched imports, guarded undo and separate history. Watch mutations and checkpoints commit through `watchstate.ImportChecked`; worker and preview shutdown are awaited. A scheduled sync (`planScheduled`) writes only items an import would mark or whose source identity is not recorded yet, and adds the rest to the run's counts in one fenced update; a manual import writes every item. One item whose identity changed in Plex is skipped as unavailable (`sourceChanges`); only most of them changing stops the run as `source_identity_changed`. `TestScheduledSyncNoChangeCost` measures a 10,000-item no-change run. |
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

**Cross-origin requests.** `sameOrigin` (`internal/api/api.go`) refuses
writes a browser makes for another site, which the session cookie would
sign in. Three kinds pass: from the Cast receiver origins while casting is
on; any request with an `Authorization: Bearer` token, as the Samsung TV
app sends from its `file://` page; and starting and claiming a pairing,
which carry no credentials. The bearer exemption is safe only because such
a request is signed in by its token alone (`auth.TokenFrom`): a bad bearer
is a 401, never the cookie's user. Keep it so, and keep other schemes
(a proxy's Basic sign-in, which browsers repeat on their own) under the
check. `apiCORS` answers CORS for the receiver origins and for media while
casting is on, and for `file://` either way (the API and media); never for `null` or another
site, and never with credentials.

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
- An `EXISTS` is planned like a join. "Has a present file" written as
  `EXISTS (SELECT 1 FROM episode_files ef JOIN media_files f ...)` started
  from every present file in the library, once for each row outside it: one
  search of the large seeded library took 69 s. Inside it too, put the
  row's own table first with `CROSS JOIN` (`internal/db/queries/search.sql`).
- Trailer files are `media_files` rows too: `extra_kind = 'trailer'`.
  A query that reads
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
  conflict side for that reason.
- Put a `sqlc.slice` after every other parameter in its query. sqlc numbers
  the parameters that follow a slice (`?2`, `?3`) as if the slice were one
  value, so once the slice holds several, those numbers point into it and
  the query quietly matches nothing or the wrong rows. The Live TV guide's
  window query returned no programmes for more than one channel until its
  slice moved last (`ListProgrammesWindow` in
  `internal/db/queries/live_tv_guide.sql`). Test such a query with at least
  two values in the slice.
- sqlc reads an FTS5 table's columns from its `CREATE VIRTUAL TABLE`, but
  not the column named after the table, so `WHERE t MATCH ?` fails to
  generate ("column does not exist"). Ask as a table function,
  `FROM t(CAST(sqlc.arg(words) AS TEXT))`, and cast a computed column that
  would otherwise come out as `interface{}`.
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
- A write that still finds the database locked after `busy_timeout` (5 s,
  `db.BusyTimeout`) fails with "database is locked" (`db.IsBusy`). That is
  about another writer, not about what was being written: don't store it
  as the outcome of the work. A request someone is waiting for returns the
  error. Work nobody waits for tries again with `db.RetryBusy`, as the
  matcher's writes do; what it runs has to be safe to run twice, so a
  transaction that counts or collects as it goes resets that at its start.
  To test it, open a second connection with `db.OpenBusy` and a wait of
  milliseconds, hold the lock on the first with `BeginTx`, and let go from
  a hook rather than after a sleep (`internal/metadata/busy_test.go`).
- `TestHomeSpeed` (`internal/api/home_speed_test.go`) seeds a library the size
  of the largest real one (about 2,850 movies, 1,100 shows, 18,700 files),
  with ten 250-entry lists and three library lists on Home, and times each
  page alone and the web Home's requests as one burst, arranged and not;
  `TestRelatedSpeed` (`internal/api/related_speed_test.go`) gives it TMDB-like tags and times a movie's and a show's related rows, alone and ten at once, and checks their plans; `TestNewEpisodesSpeed` (`internal/api/follows_speed_test.go`) adds 500
  follows and a week of arrivals to it and times the new-episodes feed, the
  follows list and Home's followed row, alone and ten at once, and fails
  when their plans read a whole table; `TestSearchSpeed`
  (`internal/api/search_speed_test.go`) gives it titles of made-up words,
  and descriptions as long as TMDB's, and times a search of one letter, two
  letters, a common word, a full title and words that match nothing, alone
  and ten at once, checks the title queries' plans, and times
  `GET /api/server` while ten searches run;
  `internal/activity`'s speed test seeds plays; `internal/livetv/dvr` measures list and guide-mark bursts over 10,000 recordings without statistics. `make bench` fails over budget;
  plain `go test` runs smaller versions with looser budgets. Name such a
  test `Test...Speed`: CI's `server` and `windows-server` jobs skip `Speed$` in the
  main test step and run those tests in a step of their own, one package at
  a time. Beside the other packages on a three-core runner,
  `TestHistorySpeed` went over its budget (PR #16, 2026-11-12) though alone
  it has six times the room.

**Matching and a busy database.** The matcher's writes of a result
(`record`, `storeMovie`, `storeShow`, `storeSeason`, `linkFileNamed`) go
through `Matcher.write` (`internal/metadata/matcher.go`), which tries again
for a minute (`Options.BusyWait`) while another writer holds the database.
When it stays locked for longer, `leftBusy` leaves the file as it was, so
one waiting for its first match is matched by the next run (after a scan
that finds a change, at startup, and once a day), with `BusyNote` as its
match error if the database takes that; the run counts it as failed.
Before, such a file was recorded as `failed` with "database is locked" as
what TMDB said, which only the daily retry picks up, or left pending with
nothing to say why. Keep a new write of the matcher's on `Matcher.write`,
and its function safe to run again.

**Episode links and arrivals.** Link files to episodes only through
`Matcher.record` (`internal/metadata/matcher.go`): in the same transaction it
records each episode's arrival in `episode_arrivals`, which people
following the show are told about.
An arrival belongs
to the TMDB episode (show TMDB id, season, number), not the episode row, so
it is made once across libraries, the first time any file is linked to
it, and outlives a library removed. It is announced only when that file's
`added_at` is within the New badge's 7 days (`metadata.NewWindow`, which
`newWindow` in `internal/api` is), and is quiet otherwise. So better
copies, renames, a 4K library's copy and a library added again are silent,
old files matched late or moved onto another episode are quiet, and a new
file fixed onto the right episode is announced. Inserting `episode_files`
anywhere else skips that. An episode named after a file (`file_named`) has
no arrival: it is no TMDB episode, and would take the number's arrival from
the one TMDB lists there later.

**Episodes named after files.** A numbered file whose show is known but
whose season or episode TMDB does not list is linked to an episode of that
show at its numbers, titled with the file's name (`episodes.file_named`,
and `seasons.file_named` for a season TMDB lacks;
`internal/metadata/filenamed.go`). The file stays unmatched, in the
Unmatched view and the daily retry. `linkEpisodes` never treats such a
row as TMDB's; `UpsertEpisode` and `UpsertSeason` turn it into TMDB's in
place when TMDB lists it, so links and watch state stay. `Matcher.record`
keeps a file's file-named links when it records the file unmatched with
no episodes (TMDB failing or still lacking it), and when it gives the file
others, moves their watch state there and removes them. Counts of what TMDB
lists (requests' season availability) leave them out; the show and season
pages count them.

**Search.** `GET /api/search` (`internal/api/search.go`) reads a trigram
index of the titles, `movies_search`, `shows_search` and `episodes_search`
(FTS5, migration `00045_search_index.sql`), which triggers on the three
tables keep: nothing that writes a title has to know about it, and writing
rows with `sqlite3` keeps it right too. It holds titles only. Whether a
title has a present file is asked at search time, so a file that goes
missing or a rematch needs no index write. Don't put file state in it.

- The index gives candidates; `internal/api/search_match.go` decides what
  matches and in what order (`searchQuery.rank`), on text folded in Go.
  Anything the old search matched (what was typed, anywhere in a title, by
  `LIKE`) is always kept: each title query returns that as `legacy`, and
  `TestSearchKeepsWhatItFound` runs the old queries beside the new search.
  Keep that test passing when the matching changes.
- The index looks up three characters or more, and finds nothing at all for
  fewer. A word goes to it only with three letters or digits
  (`searchLetters`; an accent typed apart from its letter does not count,
  since the index drops it). Shorter words ask for the titles where they
  start a word (`searchQuery.startsMatch`): the index's `words` column has
  two unit separators at the start of a title and after every space, so
  one letter there is three characters; two letters are also asked for
  after each mark a word can follow (`searchBreaks`), which is three
  characters with the mark and needs nothing kept. Only when those don't
  fill the list is every title read (`searchQuery.hits`). So with one
  letter typed and a full list, a title whose word follows a hyphen or a
  bracket is not in it until the second letter.
- The triggers are compiled into every statement that writes a title, each
  time it is prepared, whether or not they fire, and the queries here are
  prepared on every call. Keep them to one statement and a few function
  calls. A first version worked the words out in a helper table's trigger
  with some fifty `replace()` calls: a title write, changed or not, went
  from 42 to 245 microseconds, and under the race detector from 1.1 to
  8.8 ms, where the matcher's two writers then waited for each other up to
  1.65 s on a Mac and past the 5 s busy timeout on CI
  (`TestMatchByOtherNames` left a file pending). As they are, a new title
  costs 101 microseconds and one written again unchanged 64 (2.9 and
  2.1 ms under the detector); measure a change to them the same way,
  with and without `-race`.
- The marks the index leaves out of a title's second form (`searchJoiners`)
  and its two separators are written out in the migration nine times (the
  first fill, the insert trigger and the update trigger of each table),
  since SQLite has no function for them and a shared helper costs every
  write. A change to them is a new migration that replaces the triggers
  and rebuilds the three indexes, with the Go constants changed to match;
  `TestSearchIndexWords` and `TestMigrationSearchIndex` compare every copy.
- The triggers belong to `movies`, `shows` and `episodes`. A migration that
  rebuilds one of those tables (a new table, the rows copied, the old one
  dropped) drops its triggers with it and has to make them again;
  `TestSearchIndexFollowsTheLibrary` fails on the latest schema if one is
  gone.
- An app gives up on a search at the next keystroke. The handler's queries
  run on the request's context, which ends when the connection closes
  (`TestSearchStopsWhenDropped`): keep new work in it on that context.
- `GET /api/server`, which an app asks before it switches servers, reads
  the database three times and is not kept in memory, because it does not
  wait for the requests beside it: with ten Home requests running on the
  large seeded library it took a median 0.27 ms against 0.08 ms alone, and
  `GET /api/health`, which reads nothing, 0.13 ms (2026-11-20).
  `TestSearchSpeed` holds it to a budget beside ten searches.

**Watch state.** Change `watch_state` only through `internal/watchstate`, never
with its queries directly: each change must also take a revision and leave a
local guard in the same transaction, or an import can later overwrite the
user's choice. Deleting a movie or episode deletes its state and guards by
trigger. `episodes` holds every episode TMDB lists, aired or not (and the
ones named after files, which have a file instead of an air date), so a bulk
mark must not mark what can't have been watched: Mark season and Mark show
watched mark (and guard) only episodes that have aired by today (UTC) or
have a file present, so a new episode isn't already watched when it comes.

**Migrations.** New numbered files in `internal/db/migrations/` (`000NN_name.sql`)
with `-- +goose Up` and `-- +goose Down` and a comment saying why. Never edit
one that has shipped. The schema has to be readable by the `sqlite3` tool
people have, not only by the server's own SQLite: the fixture scripts
(`deploy/fixture/golden-replies.sh`) and checks on a scratch database write
rows with it. A trigger it cannot read fails every statement on the
database. A draft of the search index had fifty `replace()` calls inside
one another in a trigger, which the server read and sqlite3 3.45 refused
("parser stack overflow"); keep an expression to a dozen.
`TestSQLiteToolWritesTitles` (`internal/db`) writes titles with the
`sqlite3` on the `PATH`, when it is 3.45 or later. A trigger also costs
every write to its table, fired or not (see Search): time a write to the
table before and after adding one. Test data-moving migrations in `migrations_test.go` by
migrating up to the version before, inserting rows, then migrating on.
Use raw SQL until the database is at the latest version: the generated
queries list every column of today's schema, so one run at an older version
fails once a later migration adds a column to its table (`TestMigrationCustomPosters`
broke that way when `00043` added columns to `movies`).
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

**Converted audio**: `audioTarget`
(`internal/playback/audio_target.go`) picks FLAC, E-AC-3, AC-3 or AAC.
ffmpeg's E-AC-3 and AC-3 encoders stop at 5.1 (from 7.1 it downmixes to 5.1
without asking) and AC-3 at 640 kbps (asked for 768k, it writes 640k). PCM in
fMP4 is written as `ipcm`, which the players don't read, and ExoPlayer reads
no `in24` or `fl32` PCM in MOV (what ffmpeg writes for 24-bit), so PCM is
copied only in direct play of Matroska. FLAC keeps a 24-bit source's depth
with no `-sample_fmt`, downmixed or not. ffmpeg's experimental TrueHD encoder
writes 5.1 at most, so 7.1 test sources are PCM or FLAC. `-ac 3` on a 3.0 AC-3
source gives AAC 2.1; 4, 5 and 6 channels map cleanly. Declared bandwidth for
a single rendition counts the audio (`PeakBandwidthWithAudio`); adaptive
families keep the stereo AAC allowance.

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

**Event sessions of copied video.** ffmpeg starts copied video at the
keyframe at or before an input `-ss`, while converted audio starts on the
dot, and `-t` stops copied video at the first packet decoded at or after the
limit. So an event session of copied video starts on the keyframe that
`KeyframeFinder` (`internal/playback/keyframes_near.go`) finds, reports it as
the decision's `startMs`, and ends each run with `-t` one millisecond before
the next keyframe's decode time, where the next run starts: playlist time
then stays file time from window to window. Before, a play asked for 25 s on
a file with a keyframe every 10 s showed 20 s while the apps counted 25 s,
and each window skipped or repeated a few seconds. The
served playlist carries `EXT-X-START` at the asked-for frame: without it
players treat an event playlist as live, and one loaded without a start of
its own (Safari's native player) begins near the end of what was prepared.
Media3 keeps an explicit start, 0 included, over the tag. Trimming never passes the viewer's reported
position (`Manager.NoteViewer`). A resume with no start begins 5 s before
the saved position (`resumePoint` in `internal/api/watch.go`); the saved
position itself stays exact.

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
when no node is visible and explains ffmpeg's "Permission denied" on one;
tests stand in for another platform
with `detectHardwareOn` and `pipelineOnOS`.
Add an encoder to the table in `internal/playback/encoders.go`. Store and
read probes with `probe.Encode` and `probe.Parse`: chapters, the start
time, each video stream's codec tag and Dolby Vision base layer and level,
and each audio stream's commentary and audio-description flags
are kept there for playback but
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

**Install kinds.** `WATCHMYMEDIA_INSTALL` says how the server was installed
(`internal/updates`: `macos-app`, `windows-app`, `docker`, `deb`, `rpm`, else
`binary`). It decides what Check for updates offers, and the web words updates,
restores, folder permissions and GPU setup by it (`UpdateStatus.install` for
admins, `ServerInfo.install` before sign-in). A kind whose file is published
apart from the others (`windows-app`, `deb`, `rpm`) is told about a release
only when the release list has that file for its architecture
(`requiredKey`). `UpdateStatus.install` is an enum, which no app decodes;
`ServerInfo.install` is a plain string because the apps do decode that reply.
The packages keep `config.yaml` outside the data folder
(`WATCHMYMEDIA_CONFIG`): `config.Config.File` is the file `Load` read, and
backups save that one.

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
- Read fixtures and golden files through `//go:embed`, not `os.ReadFile`.
  Go's test cache checks the files a test opens by size and modification
  time, and a fresh checkout gives every file a new one, so `internal/api`,
  `internal/plex` and `internal/livetv/guide/xmltv` re-ran on every CI run
  until 2026-11-16 (about 50 s on Windows for `internal/api`). Embedded files
  are checked by content. A test that rewrites its golden files
  (`WMM_UPDATE_GOLDEN=1`) still writes them to `testdata/` for the next
  build to embed; files in `t.TempDir()` are outside the module and don't
  count.
- Write a script that stands in for ffmpeg or ffprobe with
  `ffexectest.WriteScript` (`internal/ffexec/ffexectest`), not `os.WriteFile`.
  Linux refuses to run a file a process still has open for writing, and a
  process a parallel test starts holds the descriptor for a moment, so a
  script written the plain way failed now and then with "text file busy".
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
- Dolby Vision: ffmpeg writes
  the `dvcC`/`dvvC` record into MP4 and HLS only with `-strict unofficial`,
  so a copy without it loses the Dolby Vision. `probetest.DolbyVisionClip`
  makes a clip with a record (no RPU) for tests; check what a session
  wrote with `ffprobe -show_entries stream=codec_tag_string:stream_side_data`
  on its `init.mp4`.
