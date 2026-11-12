package db

import (
	"fmt"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/pressly/goose/v3"
)

// migrateTo opens a fresh database migrated up to version v (0: none).
func migrateTo(t *testing.T, v int64) (*goose.Provider, *Queries) {
	t.Helper()
	conn, err := Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	p, err := migrations(conn)
	if err != nil {
		t.Fatal(err)
	}
	if v > 0 {
		if _, err := p.UpTo(t.Context(), v); err != nil {
			t.Fatal(err)
		}
	}
	return p, New(conn)
}

func TestRecordingMigrationKeepsOwnershipWhenAccountsAndRulesGoAway(t *testing.T) {
	t.Parallel()
	provider, q := migrateTo(t, 35)
	ctx := t.Context()
	if _, err := provider.UpTo(ctx, 36); err != nil {
		t.Fatal(err)
	}
	user, err := q.CreateUser(ctx, CreateUserParams{Name: "Casey", PasswordHash: "unused"})
	if err != nil {
		t.Fatal(err)
	}
	ruleID := "rule"
	if err = q.PutDVRRule(ctx, PutDVRRuleParams{ID: ruleID, UserID: &user.ID, Kind: "series", SeriesKey: "vane", Data: "{}", CreatedAt: 1}); err != nil {
		t.Fatal(err)
	}
	path := "/fictional-library/Inspector Vane.ts"
	if err = q.PutDVRRecording(ctx, PutDVRRecordingParams{ID: "recording", RuleID: &ruleID, UserID: &user.ID, SourceID: "old-tuner", ChannelID: "old-channel", Number: "7.1", StartAt: 1, EndAt: 2, State: "done", Section: "recorded", Path: &path, Data: "{}", CreatedAt: 1, UpdatedAt: 2}); err != nil {
		t.Fatal(err)
	}
	if err = q.DeleteUser(ctx, user.ID); err != nil {
		t.Fatal(err)
	}
	if err = q.DeleteDVRRule(ctx, ruleID); err != nil {
		t.Fatal(err)
	}
	row, err := q.GetDVRRecording(ctx, "recording")
	if err != nil || row.UserID != nil || row.RuleID != nil || row.Path == nil || *row.Path != path {
		t.Fatalf("ownership lost: %+v %v", row, err)
	}
	if _, err := provider.DownTo(ctx, 35); err != nil {
		t.Fatal(err)
	}
	if _, err := provider.UpTo(ctx, 36); err != nil {
		t.Fatal(err)
	}
	rows, err := q.ListDVRRules(ctx)
	if err != nil || len(rows) != 0 {
		t.Fatal(rows, err)
	}
}

// Migration 16 moves files found lately to when they were found, unless they
// were part of the library's first scan, look like a move, or were dated
// within a day of being found anyway.
func TestMigrationAddedWhenFound(t *testing.T) {
	ctx := t.Context()
	p, q := migrateTo(t, 15)
	conn := q.db
	if _, err := conn.ExecContext(ctx, `INSERT INTO libraries (id, type, name, path) VALUES (1, 'movie', 'Movies', '/m'), (2, 'tv', 'TV', '/t')`); err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	ago := func(d time.Duration) string { return FormatTime(now.Add(-d)) }
	day := 24 * time.Hour
	old := FormatTime(time.Date(2019, 1, 1, 0, 0, 0, 0, time.UTC))
	files := []struct {
		lib                 int
		path, status, found string
		added               string
		size                int
		want                string
	}{
		{1, "/m/imported.mkv", "present", ago(60 * day), old, 1, old},       // the library's first scan
		{1, "/m/copied.mkv", "present", ago(3 * day), old, 2, ago(3 * day)}, // found lately with an old date
		{1, "/m/moved.mkv", "present", ago(3 * day), old, 3, old},           // a missing twin: moved
		{1, "/m/gone/moved.mkv", "missing", ago(60 * day), old, 3, old},     // its old place
		{1, "/m/earlier.mkv", "present", ago(20 * day), old, 4, old},        // before the 14 days
		{1, "/m/recent.mkv", "present", ago(2 * day), ago(2*day + time.Hour), 5, ago(2*day + time.Hour)},
		{2, "/t/new-library.mkv", "present", ago(5 * day), old, 6, old}, // a library first scanned lately
		{2, "/t/new-library-2.mkv", "present", ago(5*day - time.Hour), old, 7, old},
	}
	for _, f := range files {
		if _, err := conn.ExecContext(ctx, `INSERT INTO media_files (library_id, path, size, mtime, status, parsed, first_seen_at, added_at, last_seen_at)
			VALUES (?, ?, ?, ?, ?, '{}', ?, ?, ?)`, f.lib, f.path, f.size, old, f.status, f.found, f.added, f.found); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := p.UpTo(ctx, 16); err != nil {
		t.Fatal(err)
	}
	for _, f := range files {
		var got string
		if err := conn.QueryRowContext(ctx, `SELECT added_at FROM media_files WHERE path = ?`, f.path).Scan(&got); err != nil {
			t.Fatal(err)
		}
		if got != f.want {
			t.Errorf("%s added %s, want %s", f.path, got, f.want)
		}
	}
}

// Migration 23 gives every existing watch_state row a local guard, drops
// state on items that no longer exist, and from then on deletes state and
// guards with their movie or episode, library deletions included. Down
// leaves watch state as it is.
func TestMigrationWatchGuards(t *testing.T) {
	ctx := t.Context()
	p, q := migrateTo(t, 22)
	conn := q.db
	for _, q := range []string{
		`INSERT INTO users (id, name, password_hash) VALUES (1, 'a', 'x'), (2, 'b', 'x')`,
		`INSERT INTO libraries (id, type, name, path) VALUES (1, 'movie', 'Movies', '/m'), (2, 'tv', 'TV', '/t')`,
		`INSERT INTO movies (id, library_id, tmdb_id, title) VALUES (10, 1, 100, 'Kept'), (11, 1, 101, 'Unwatched')`,
		`INSERT INTO shows (id, library_id, tmdb_id, title) VALUES (20, 2, 200, 'Show')`,
		`INSERT INTO seasons (id, show_id, number) VALUES (30, 20, 1)`,
		`INSERT INTO episodes (id, season_id, show_id, number) VALUES (40, 30, 20, 1), (41, 30, 20, 2)`,
		`INSERT INTO watch_state (user_id, item_type, item_id, completed, updated_at) VALUES
			(1, 'movie', 10, 1, '2026-01-01T00:00:00.000Z'),
			(2, 'movie', 10, 0, '2026-01-02T00:00:00.000Z'),
			(1, 'episode', 40, 1, '2026-01-03T00:00:00.000Z'),
			(2, 'episode', 41, 0, '2026-01-04T00:00:00.000Z'),
			(1, 'movie', 99, 1, '2026-01-05T00:00:00.000Z'),
			(1, 'episode', 99, 0, '2026-01-06T00:00:00.000Z')`,
	} {
		if _, err := conn.ExecContext(ctx, q); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := p.UpTo(ctx, 23); err != nil {
		t.Fatal(err)
	}
	count := func(q string, args ...any) int {
		t.Helper()
		var n int
		if err := conn.QueryRowContext(ctx, q, args...).Scan(&n); err != nil {
			t.Fatal(err)
		}
		return n
	}
	if n := count(`SELECT count(*) FROM watch_state WHERE item_id = 99`); n != 0 {
		t.Errorf("state on items that do not exist: %d rows", n)
	}
	if n := count(`SELECT count(*) FROM watch_state`); n != 4 {
		t.Errorf("watch_state rows = %d, want 4", n)
	}
	// One local guard per remaining row, at the first revision, dated as the row.
	if n := count(`SELECT count(*) FROM watch_state w JOIN watch_guards g USING (user_id, item_type, item_id)
		WHERE g.owner = 'local' AND g.revision = 1 AND g.import_run IS NULL AND g.updated_at = w.updated_at`); n != 4 {
		t.Errorf("backfilled guards = %d, want 4", n)
	}
	if n := count(`SELECT count(*) FROM watch_guards`); n != 4 {
		t.Errorf("guards = %d, want 4", n)
	}
	if n := count(`SELECT last FROM watch_revision`); n != 1 {
		t.Errorf("revision = %d, want 1", n)
	}
	if rev, err := q.NextWatchRevision(ctx); err != nil || rev != 2 {
		t.Errorf("next revision = %d %v", rev, err)
	}
	if _, err := conn.ExecContext(ctx, `INSERT INTO watch_guards (user_id, item_type, item_id, owner, revision) VALUES (1, 'movie', 11, 'import', 2)`); err == nil {
		t.Error("an import guard without its run was accepted")
	}

	// Deleting a movie, or a library (whose shows, seasons and episodes go
	// by cascade), takes its state and guards along.
	if err := q.ClaimWatchGuard(ctx, ClaimWatchGuardParams{UserID: 2, ItemType: "movie", ItemID: 11, Revision: 2}); err != nil {
		t.Fatal(err)
	}
	if err := q.DeleteMovie(ctx, 11); err != nil {
		t.Fatal(err)
	}
	if n := count(`SELECT count(*) FROM watch_guards WHERE item_type = 'movie' AND item_id = 11`); n != 0 {
		t.Errorf("guards on the deleted movie: %d", n)
	}
	if err := q.DeleteLibrary(ctx, 2); err != nil {
		t.Fatal(err)
	}
	if n := count(`SELECT count(*) FROM watch_state WHERE item_type = 'episode'`) + count(`SELECT count(*) FROM watch_guards WHERE item_type = 'episode'`); n != 0 {
		t.Errorf("state and guards left on a deleted library's episodes: %d", n)
	}

	if _, err := p.DownTo(ctx, 22); err != nil {
		t.Fatal(err)
	}
	if n := count(`SELECT count(*) FROM watch_state`); n != 2 {
		t.Errorf("watch_state after down = %d, want the movie's 2", n)
	}
	if n := count(`SELECT count(*) FROM sqlite_master WHERE name IN ('watch_guards', 'watch_revision', 'watch_state_item_idx', 'movies_delete_watch', 'episodes_delete_watch')`); n != 0 {
		t.Errorf("%d objects left after down", n)
	}
	if _, err := p.Up(ctx); err != nil {
		t.Fatal(err)
	}
}

// Migration 25 takes the TMDB API key out of match errors that printed the
// request URL, and leaves other errors alone.
func TestMigrationScrubsTMDBKeys(t *testing.T) {
	ctx := t.Context()
	p, q := migrateTo(t, 24)
	conn := q.db
	if _, err := conn.ExecContext(ctx, `INSERT INTO libraries (id, type, name, path) VALUES (1, 'movie', 'Movies', '/m')`); err != nil {
		t.Fatal(err)
	}
	const key = "0123456789abcdef0123456789abcdef"
	cases := []struct{ stored, want string }{
		{
			`tmdb: Get "https://api.themoviedb.org/3/search/movie?api_key=` + key + `&query=Heat&year=1995": dial tcp: lookup api.themoviedb.org: no such host`,
			`tmdb: Get "https://api.themoviedb.org/3/search/movie": dial tcp: lookup api.themoviedb.org: no such host`,
		},
		{
			`tmdb: Get "https://api.themoviedb.org/3/tv/1396?append_to_response=external_ids&api_key=` + key + `": context deadline exceeded (Client.Timeout exceeded while awaiting headers)`,
			`tmdb: Get "https://api.themoviedb.org/3/tv/1396": context deadline exceeded (Client.Timeout exceeded while awaiting headers)`,
		},
		{`request to ?api_key=` + key + ` failed`, `tmdb: request failed`},
		{`no movie on TMDB matches "Heat?" (1995)`, `no movie on TMDB matches "Heat?" (1995)`},
		{`tmdb: unauthorized: check the API key`, `tmdb: unauthorized: check the API key`},
	}
	for i, c := range cases {
		if _, err := conn.ExecContext(ctx, `INSERT INTO media_files (library_id, path, size, mtime, parsed, last_seen_at, match_status, match_error)
			VALUES (1, ?, 1, '2020-01-01T00:00:00.000Z', '{}', '2020-01-01T00:00:00.000Z', 'failed', ?)`, fmt.Sprintf("/m/%d.mkv", i), c.stored); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := conn.ExecContext(ctx, `INSERT INTO media_files (library_id, path, size, mtime, parsed, last_seen_at) VALUES (1, '/m/ok.mkv', 1, '', '{}', '')`); err != nil {
		t.Fatal(err)
	}
	if _, err := p.UpTo(ctx, 25); err != nil {
		t.Fatal(err)
	}
	for i, c := range cases {
		var got string
		if err := conn.QueryRowContext(ctx, `SELECT match_error FROM media_files WHERE path = ?`, fmt.Sprintf("/m/%d.mkv", i)).Scan(&got); err != nil {
			t.Fatal(err)
		}
		if got != c.want || strings.Contains(got, key) {
			t.Errorf("match_error\n%s\nbecame\n%s\nwant\n%s", c.stored, got, c.want)
		}
	}
	var unset *string
	if err := conn.QueryRowContext(ctx, `SELECT match_error FROM media_files WHERE path = '/m/ok.mkv'`).Scan(&unset); err != nil || unset != nil {
		t.Errorf("file without an error now has %v (%v)", unset, err)
	}
}

// Every migration goes down again on a fresh database, and back up.
func TestMigrationsDown(t *testing.T) {
	ctx := t.Context()
	p, _ := migrateTo(t, 0)
	if _, err := p.Up(ctx); err != nil {
		t.Fatal(err)
	}
	latest, err := p.GetDBVersion(ctx)
	if err != nil || latest < 1 {
		t.Fatalf("version %d, %v", latest, err)
	}
	// Versions can skip numbers that other work has taken, so step down by
	// what the database says rather than by one.
	for v := latest; v > 0; {
		if _, err := p.Down(ctx); err != nil {
			t.Fatalf("down from %d: %v", v, err)
		}
		if v, err = p.GetDBVersion(ctx); err != nil {
			t.Fatal(err)
		}
	}
	if v, err := p.GetDBVersion(ctx); err != nil || v != 0 {
		t.Fatalf("version after going down: %d, %v", v, err)
	}
	if _, err := p.Up(ctx); err != nil {
		t.Fatalf("up again: %v", err)
	}
}

// Deleting a library deletes its files, which sets plays, progress and
// problems that point at them to NULL. Without an index on media_file_id
// each file read those tables whole: 4,000 files with 20,000 plays took 10 s.
func TestDeleteLibraryWithPlaysIsFast(t *testing.T) {
	if testing.Short() {
		t.Skip("seeds a large library")
	}
	files, playsPerFile := 3000, 5
	if raceDetector {
		files = 300
	}
	ctx := t.Context()
	_, q := migrateTo(t, 1<<62)
	err := q.Transaction(ctx, func(q *Queries) error {
		exec := func(query string, args ...any) {
			t.Helper()
			if _, err := q.db.ExecContext(ctx, query, args...); err != nil {
				t.Fatal(err)
			}
		}
		exec(`INSERT INTO libraries (id, type, name, path) VALUES (1, 'movie', 'Movies', '/m')`)
		exec(`INSERT INTO users (id, name, password_hash) VALUES (1, 'ann', 'x')`)
		exec(`INSERT INTO history_items (id, item_type, item_id, title) VALUES (1, 'file', 1, 'x')`)
		for i := 1; i <= files; i++ {
			exec(`INSERT INTO media_files (id, library_id, path, size, mtime, parsed, last_seen_at) VALUES (?, 1, ?, 1, '', '{}', '')`, i, fmt.Sprintf("/m/%d.mkv", i))
			exec(`INSERT INTO watch_state (user_id, item_type, item_id, media_file_id) VALUES (1, 'movie', ?, ?)`, i, i)
			exec(`INSERT INTO problems (user_id, item_type, item_id, media_file_id, kind) VALUES (1, 'movie', ?, ?, 'video')`, i, i)
			for j := range playsPerFile {
				exec(`INSERT INTO plays (public_id, user_name, history_item_id, media_file_id, item_type, started_at, last_seen_at, mode, local_day, local_hour, local_weekday)
					VALUES (?, 'ann', 1, ?, 'movie', '', '', 'direct', '2026-01-01', 0, 0)`, fmt.Sprintf("%d-%d", i, j), i)
			}
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	start := time.Now()
	if err := q.DeleteLibrary(ctx, 1); err != nil {
		t.Fatal(err)
	}
	took := time.Since(start)
	var left int
	if err := q.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM plays WHERE media_file_id IS NOT NULL`).Scan(&left); err != nil || left != 0 {
		t.Fatalf("plays still pointing at files: %d, %v", left, err)
	}
	// About 80 ms with the indexes and 7 s without; the budget leaves room
	// for slow machines.
	if took > 2*time.Second && !raceDetector {
		t.Errorf("deleting a library of %d files with %d plays took %v", files, files*playsPerFile, took)
	}
	t.Logf("deleted %d files with %d plays in %v", files, files*playsPerFile, took)
}

// Migration 28 adds Plex connections. Losing a link, however it happens,
// and losing the admin who authorized the connection, make the
// connection's generation move on so earlier previews are known stale.
func TestMigrationPlexConnections(t *testing.T) {
	ctx := t.Context()
	p, q := migrateTo(t, 1<<62)
	conn := q.db
	admin, err := q.CreateUser(ctx, CreateUserParams{Name: "admin", PasswordHash: "x", IsAdmin: 1})
	if err != nil {
		t.Fatal(err)
	}
	viewer, err := q.CreateUser(ctx, CreateUserParams{Name: "viewer", PasswordHash: "x"})
	if err != nil {
		t.Fatal(err)
	}
	movies, err := q.UpsertLibrary(ctx, UpsertLibraryParams{Type: "movie", Name: "Movies", Path: "/m"})
	if err != nil {
		t.Fatal(err)
	}
	ref := "synthetic-ref"
	c, err := q.InsertPlexConnection(ctx, InsertPlexConnectionParams{MachineIdentifier: "synthetic-machine", Origin: "http://192.0.2.10:32400",
		CredentialRef: &ref, OwnerPlexID: 701, HistoryCapability: "unknown", AuthorizedBy: &admin.ID})
	if err != nil {
		t.Fatal(err)
	}
	if c.SyncEnabled != 0 || c.Generation != 1 {
		t.Fatalf("a new connection must start with sync off at generation 1: %+v", c)
	}
	for _, link := range []InsertPlexUserLinkParams{
		{ConnectionID: c.ID, PlexUserID: 701, Kind: "owner", UserID: admin.ID},
		{ConnectionID: c.ID, PlexUserID: 706, Kind: "managed", UserID: viewer.ID},
	} {
		if err := q.InsertPlexUserLink(ctx, link); err != nil {
			t.Fatal(err)
		}
	}
	if err := q.InsertPlexUserLink(ctx, InsertPlexUserLinkParams{ConnectionID: c.ID, PlexUserID: 702, Kind: "account", UserID: viewer.ID}); err == nil {
		t.Error("one local account was linked to two Plex people")
	}
	if err := q.InsertPlexUserLink(ctx, InsertPlexUserLinkParams{ConnectionID: c.ID, PlexUserID: 707, Kind: "friend", UserID: admin.ID}); err == nil {
		t.Error("an unknown kind was accepted")
	}
	if err := q.InsertPlexLibraryLink(ctx, InsertPlexLibraryLinkParams{ConnectionID: c.ID, SectionID: 1, SectionUuid: "u1", LibraryID: movies.ID}); err != nil {
		t.Fatal(err)
	}
	generation := func() int64 {
		t.Helper()
		got, err := q.GetPlexConnection(ctx, c.ID)
		if err != nil {
			t.Fatal(err)
		}
		return got.Generation
	}
	before := generation()
	if err := q.DeleteUser(ctx, viewer.ID); err != nil {
		t.Fatal(err)
	}
	if links, _ := q.ListPlexUserLinks(ctx, c.ID); len(links) != 1 || generation() <= before {
		t.Fatalf("deleting an account left its link (%d links) or the generation (%d → %d)", len(links), before, generation())
	}
	before = generation()
	if _, err := conn.ExecContext(ctx, "DELETE FROM libraries WHERE id = ?", movies.ID); err != nil {
		t.Fatal(err)
	}
	if links, _ := q.ListPlexLibraryLinks(ctx, c.ID); len(links) != 0 || generation() <= before {
		t.Fatalf("deleting a library left its link or the generation")
	}
	before = generation()
	if err := q.DeleteUser(ctx, admin.ID); err != nil {
		t.Fatal(err)
	}
	got, _ := q.GetPlexConnection(ctx, c.ID)
	if got.AuthorizedBy != nil || got.Generation <= before {
		t.Fatalf("deleting the authorizing admin must pause the connection: %+v", got)
	}
	if err := q.DeletePlexConnection(ctx, c.ID); err != nil {
		t.Fatalf("deleting a connection: %v", err)
	}
	if _, err := p.Down(ctx); err != nil {
		t.Fatal(err)
	}
	if _, err := p.Up(ctx); err != nil {
		t.Fatal(err)
	}
}

// Existing connections acquire a private source epoch without losing mappings
// or watch choices, and the migration can be rolled back without rebuilding the
// rest of the application's database.
func TestMigrationPlexDurableImports(t *testing.T) {
	t.Parallel()
	ctx := t.Context()
	p, q := migrateTo(t, 34)
	admin, err := q.CreateUser(ctx, CreateUserParams{Name: "synthetic-admin", PasswordHash: "x", IsAdmin: 1})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := q.db.ExecContext(ctx, "INSERT INTO plex_connections(machine_identifier,origin,owner_plex_id,authorized_by) VALUES('synthetic-migration-machine','http://192.0.2.10:32400',701,?)", admin.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := p.Up(ctx); err != nil {
		t.Fatal(err)
	}
	c, err := q.GetPlexConnectionByMachine(ctx, "synthetic-migration-machine")
	if err != nil || len(c.SourceEpoch) != 32 || c.SyncEnabled != 0 {
		t.Fatalf("migrated connection %+v %v", c, err)
	}
	if _, err := q.db.ExecContext(ctx, "UPDATE users SET is_admin=0 WHERE id=?", admin.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := q.db.ExecContext(ctx, "UPDATE users SET is_admin=1 WHERE id=?", admin.ID); err != nil {
		t.Fatal(err)
	}
	got, err := q.GetPlexConnection(ctx, c.ID)
	if err != nil || got.AuthorizedBy != nil || got.Generation <= c.Generation {
		t.Fatalf("demotion revocation %+v %v", got, err)
	}
	if _, err := p.Down(ctx); err != nil {
		t.Fatal(err)
	}
	var n int
	if err := q.db.QueryRowContext(ctx, "SELECT COUNT(*) FROM plex_connections WHERE machine_identifier='synthetic-migration-machine'").Scan(&n); err != nil || n != 1 {
		t.Fatalf("down lost existing connection: %d %v", n, err)
	}
	if _, err := p.Up(ctx); err != nil {
		t.Fatal(err)
	}
}

// Migration 38 adds trailer files (PLAN-TRAILERS.md §5.2). It moves no data:
// files keep their match until the next scan reads their names. A trailer
// lets its movie go when the movie is deleted, and going down removes
// trailer rows, which older servers skip.
func TestMigrationTrailerFiles(t *testing.T) {
	t.Parallel()
	ctx := t.Context()
	p, q := migrateTo(t, 36)
	conn := q.db
	exec := func(query string, args ...any) {
		t.Helper()
		if _, err := conn.ExecContext(ctx, query, args...); err != nil {
			t.Fatal(err)
		}
	}
	exec(`INSERT INTO libraries (id, type, name, path) VALUES (1, 'movie', 'Movies', '/m')`)
	exec(`INSERT INTO movies (id, library_id, tmdb_id, title, genres) VALUES (7, 1, 949, 'Harbor Lights', '[]')`)
	for i, path := range []string{"/m/Harbor Lights (2019)/Harbor Lights (2019).mkv", "/m/Harbor Lights (2019)/trailer.mp4"} {
		exec(`INSERT INTO media_files (id, library_id, path, size, mtime, match_status, movie_id) VALUES (?, 1, ?, 1, '', 'matched', 7)`, i+1, path)
	}
	if _, err := p.UpTo(ctx, 38); err != nil {
		t.Fatal(err)
	}
	var matched int
	if err := conn.QueryRowContext(ctx, `SELECT COUNT(*) FROM media_files WHERE movie_id = 7 AND extra_kind IS NULL AND extra_of IS NULL`).Scan(&matched); err != nil || matched != 2 {
		t.Fatalf("files as they were: %d, %v", matched, err)
	}
	exec(`INSERT INTO media_files (id, library_id, path, size, mtime, extra_kind, extra_of) VALUES (3, 1, '/m/Harbor Lights (2019)/Trailers/Teaser.mkv', 1, '', 'trailer', 7)`)
	if trailers, err := q.ListMovieTrailerFiles(ctx, new(int64(7))); err != nil || len(trailers) != 1 || trailers[0].ID != 3 {
		t.Fatalf("the movie's trailers: %+v, %v", trailers, err)
	}
	exec(`DELETE FROM movies WHERE id = 7`)
	var of *int64
	if err := conn.QueryRowContext(ctx, `SELECT extra_of FROM media_files WHERE id = 3`).Scan(&of); err != nil || of != nil {
		t.Fatalf("trailer of a deleted movie: %v, %v", of, err)
	}
	if _, err := p.DownTo(ctx, 36); err != nil {
		t.Fatal(err)
	}
	var left int
	if err := conn.QueryRowContext(ctx, `SELECT COUNT(*) FROM media_files`).Scan(&left); err != nil || left != 2 {
		t.Fatalf("files after going down: %d, %v", left, err)
	}
	if _, err := p.Up(ctx); err != nil {
		t.Fatal(err)
	}
}

// Movies matched before trailers were kept have none and have never been
// checked, so the trailer job asks TMDB for them first.
func TestMigrationMovieTrailers(t *testing.T) {
	ctx := t.Context()
	p, q := migrateTo(t, 36)
	conn := q.db
	for _, s := range []string{
		`INSERT INTO libraries (id, type, name, path) VALUES (1, 'movie', 'Movies', '/m')`,
		`INSERT INTO movies (id, library_id, tmdb_id, title, release_date) VALUES (7, 1, 949, 'Heat', '1995-12-15')`,
		`INSERT INTO media_files (library_id, path, size, mtime, status, parsed, first_seen_at, added_at, last_seen_at, movie_id)
		 VALUES (1, '/m/Heat.mkv', 1, 'x', 'present', '{}', 'x', 'x', 'x', 7)`,
	} {
		if _, err := conn.ExecContext(ctx, s); err != nil {
			t.Fatal(err)
		}
	}
	// On to the newest schema: the queries read columns later migrations
	// added, and none of them touches the trailers.
	if _, err := p.Up(ctx); err != nil {
		t.Fatal(err)
	}
	m, err := q.GetMovie(ctx, 7)
	if err != nil {
		t.Fatal(err)
	}
	if m.Trailers != "[]" || m.TrailersCheckedAt != nil {
		t.Errorf("trailers %q, checked %v", m.Trailers, m.TrailersCheckedAt)
	}
	now := time.Now()
	due, err := q.ListMoviesForTrailers(ctx, ListMoviesForTrailersParams{OlderBefore: FormatTime(now.Add(-30 * 24 * time.Hour)),
		RecentSince: now.AddDate(0, -6, 0).Format(time.DateOnly), RecentBefore: FormatTime(now.Add(-7 * 24 * time.Hour)), MaxRows: 10})
	if err != nil || len(due) != 1 || due[0].ID != 7 || due[0].TmdbID != 949 {
		t.Errorf("due = %+v, %v", due, err)
	}
}

// Going back below the chosen-poster columns puts TMDB's posters back, so
// an older server never shows a path it cannot serve (PLAN-POSTERS.md).
func TestMigrationCustomPosters(t *testing.T) {
	t.Parallel()
	ctx := t.Context()
	p, q := migrateTo(t, 41)
	conn := q.db
	exec := func(query string, args ...any) {
		t.Helper()
		if _, err := conn.ExecContext(ctx, query, args...); err != nil {
			t.Fatal(err)
		}
	}
	exec(`INSERT INTO libraries (id, type, name, path) VALUES (1, 'movie', 'Movies', '/m'), (2, 'tv', 'TV', '/tv')`)
	exec(`INSERT INTO movies (id, library_id, tmdb_id, title, genres, poster_path) VALUES (7, 1, 949, 'Harbor Lights', '[]', '/tmdb.jpg'), (8, 1, 950, 'Quiet Field', '[]', '/plain.jpg')`)
	exec(`INSERT INTO shows (id, library_id, tmdb_id, title, genres, poster_path) VALUES (3, 2, 77, 'Inspector Vane', '[]', '/show.jpg')`)
	exec(`INSERT INTO seasons (id, show_id, number, name, overview, poster_path, episode_count) VALUES (4, 3, 1, 'Season 1', '', '/season.jpg', 6)`)
	custom := "/c-0123456789abcdef01234567.jpg"
	if m, err := q.SetMoviePoster(ctx, SetMoviePosterParams{ID: 7, Path: &custom, Source: "upload"}); err != nil || *m.PosterPath != custom || *m.PosterTmdb != "/tmdb.jpg" {
		t.Fatalf("a chosen poster: %+v, %v", m, err)
	}
	other := "/other.jpg"
	if _, err := q.SetShowPoster(ctx, SetShowPosterParams{ID: 3, Path: &other, Source: "tmdb"}); err != nil {
		t.Fatal(err)
	}
	if _, err := q.SetSeasonPoster(ctx, SetSeasonPosterParams{ID: 4, Path: &custom, Source: "url"}); err != nil {
		t.Fatal(err)
	}
	if list, err := q.ListCustomPosters(ctx); err != nil || len(list) != 1 || *list[0] != custom {
		t.Fatalf("pictures in use: %v, %v", list, err)
	}
	// A second choice keeps TMDB's own poster, not the first choice.
	if m, err := q.SetMoviePoster(ctx, SetMoviePosterParams{ID: 7, Path: &other, Source: "tmdb"}); err != nil || *m.PosterTmdb != "/tmdb.jpg" {
		t.Fatalf("a second choice: %+v, %v", m, err)
	}
	if _, err := p.DownTo(ctx, 40); err != nil {
		t.Fatal(err)
	}
	for query, want := range map[string]string{
		`SELECT poster_path FROM movies WHERE id = 7`:  "/tmdb.jpg",
		`SELECT poster_path FROM movies WHERE id = 8`:  "/plain.jpg",
		`SELECT poster_path FROM shows WHERE id = 3`:   "/show.jpg",
		`SELECT poster_path FROM seasons WHERE id = 4`: "/season.jpg",
	} {
		var got string
		if err := conn.QueryRowContext(ctx, query).Scan(&got); err != nil || got != want {
			t.Errorf("%s: %q, %v, want %q", query, got, err, want)
		}
	}
	if _, err := p.Up(ctx); err != nil {
		t.Fatal(err)
	}
}
