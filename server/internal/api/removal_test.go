package api

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"syscall"
	"testing"
	"time"

	"github.com/danielgtaylor/huma/v2"
	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/hdhomerun"
	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
)

func confirmProblem(t *testing.T, r reply) bool {
	t.Helper()
	var problem struct{ Type string }
	_ = json.Unmarshal(r.Body, &problem)
	return r.StatusCode == http.StatusForbidden && problem.Type == confirmPasswordType
}

// Removing a library or a tuner, and turning remote admin off, ask for the
// password again away from home; at home they don't.
func TestRemovalsAwayNeedPassword(t *testing.T) {
	t.Parallel()
	fake := hdhrtest.New(nil)
	tuner := httptest.NewServer(fake)
	defer tuner.Close()
	srv := newTestServerWith(t, func(d *Deps) {
		remoteAdminDeps(d)
		d.LiveTV = livetv.New(d.Queries, d.Settings, func(a string) (*hdhomerun.Client, error) { return hdhomerun.New(a, hdhrtest.Transport(tuner.URL)) })
	})
	ctx := t.Context()
	library := func(name string) int64 {
		lib, err := srv.q.CreateLibrary(ctx, db.CreateLibraryParams{Type: "movie", Name: name, Path: "/" + name})
		if err != nil {
			t.Fatal(err)
		}
		return lib.ID
	}
	source := func() string {
		var s livetv.Source
		if r := doJSON(t, srv.client, "POST", srv.URL+"/api/settings/live-tv/sources", `{"address":"tuner.test"}`, &s); r.StatusCode != 200 {
			t.Fatalf("add tuner: %d %s", r.StatusCode, r.Body)
		}
		return s.ID
	}
	away, home := library("Away"), library("Home")
	tunerID := source()
	put := func(body string, header ...string) reply {
		return doJSON(t, srv.client, "PUT", srv.URL+"/api/settings", body, nil, header...)
	}

	// Away, without the password.
	if r := doJSON(t, srv.client, "DELETE", fmt.Sprintf("%s/api/libraries/%d", srv.URL, away), "", nil, remoteHTTPS...); !confirmProblem(t, r) {
		t.Fatalf("library away: %d %s", r.StatusCode, r.Body)
	}
	if _, err := srv.q.GetLibrary(ctx, away); err != nil {
		t.Fatal("library removed without the password:", err)
	}
	if r := doJSON(t, srv.client, "DELETE", srv.URL+"/api/settings/live-tv/sources/"+tunerID, "", nil, remoteHTTPS...); !confirmProblem(t, r) {
		t.Fatalf("tuner away: %d %s", r.StatusCode, r.Body)
	}
	if _, err := srv.q.GetLiveSource(ctx, tunerID); err != nil {
		t.Fatal("tuner removed without the password:", err)
	}
	if r := put(`{"remoteAdmin":false}`, remoteHTTPS...); !confirmProblem(t, r) {
		t.Fatalf("remote admin off away: %d %s", r.StatusCode, r.Body)
	}
	// Turning it on away is refused whatever the password.
	if r := put(`{"remoteAdmin":true}`, remoteHTTPS...); r.StatusCode != 403 || confirmProblem(t, r) {
		t.Fatalf("remote admin on away: %d %s", r.StatusCode, r.Body)
	}

	// At home, no password.
	if r := doJSON(t, srv.client, "DELETE", fmt.Sprintf("%s/api/libraries/%d", srv.URL, home), "", nil); r.StatusCode != 204 {
		t.Fatalf("library at home: %d %s", r.StatusCode, r.Body)
	}
	if r := doJSON(t, srv.client, "DELETE", srv.URL+"/api/settings/live-tv/sources/"+tunerID, "", nil); r.StatusCode != 204 {
		t.Fatalf("tuner at home: %d %s", r.StatusCode, r.Body)
	}
	for _, on := range []string{"false", "true"} {
		if r := put(`{"remoteAdmin":` + on + `}`); r.StatusCode != 200 {
			t.Fatalf("remote admin %s at home: %d %s", on, r.StatusCode, r.Body)
		}
	}

	// Away, with the password entered again.
	tunerID = source()
	if r := doJSON(t, srv.client, "POST", srv.URL+"/api/auth/confirm", `{"password":"password1"}`, nil, remoteHTTPS...); r.StatusCode != 204 {
		t.Fatalf("confirm: %s", r.Body)
	}
	if r := doJSON(t, srv.client, "DELETE", fmt.Sprintf("%s/api/libraries/%d", srv.URL, away), "", nil, remoteHTTPS...); r.StatusCode != 204 {
		t.Fatalf("confirmed library away: %d %s", r.StatusCode, r.Body)
	}
	if r := doJSON(t, srv.client, "DELETE", srv.URL+"/api/settings/live-tv/sources/"+tunerID, "", nil, remoteHTTPS...); r.StatusCode != 204 {
		t.Fatalf("confirmed tuner away: %d %s", r.StatusCode, r.Body)
	}
	if r := put(`{"remoteAdmin":false}`, remoteHTTPS...); r.StatusCode != 200 {
		t.Fatalf("confirmed remote admin off away: %d %s", r.StatusCode, r.Body)
	}
}

// When config.yaml sets remote admin, a change away from home is refused as
// a conflict before the password is asked for.
func TestRemoteAdminConfigConflictBeforePassword(t *testing.T) {
	t.Parallel()
	enabled := true
	srv := newTestServerWith(t, func(d *Deps) { remoteAdminDeps(d); d.RemoteAdminConfig = &enabled; d.Net.SetRemoteAdmin(true) })
	if r := doJSON(t, srv.client, "PUT", srv.URL+"/api/settings", `{"remoteAdmin":false}`, nil, remoteHTTPS...); r.StatusCode != 409 {
		t.Fatalf("got %d %s, want 409", r.StatusCode, r.Body)
	}
}

func TestAuthStatusAdminConfirmedUntil(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, remoteAdminDeps)
	status := func(c *http.Client, header ...string) map[string]json.RawMessage {
		var raw map[string]json.RawMessage
		doJSON(t, c, "GET", srv.URL+"/api/auth/status", "", &raw, header...)
		return raw
	}
	if _, ok := status(srv.client, remoteHTTPS...)["adminConfirmedUntil"]; ok {
		t.Fatal("confirmed before the password was entered")
	}
	before := time.Now()
	if r := doJSON(t, srv.client, "POST", srv.URL+"/api/auth/confirm", `{"password":"password1"}`, nil, remoteHTTPS...); r.StatusCode != 204 {
		t.Fatalf("confirm: %s", r.Body)
	}
	after := time.Now()
	for _, header := range [][]string{remoteHTTPS, nil} {
		var st AuthStatus
		doJSON(t, srv.client, "GET", srv.URL+"/api/auth/status", "", &st, header...)
		if st.AdminConfirmedUntil == nil || st.AdminConfirmedUntil.Before(before.Add(9*time.Minute)) || st.AdminConfirmedUntil.After(after.Add(10*time.Minute)) {
			t.Fatalf("away=%v: confirmed until %v", header != nil, st.AdminConfirmedUntil)
		}
	}
	// Another sign-in, and a member, have none.
	member, _ := securityTestUser(t, srv, "member")
	if _, ok := status(member, remoteHTTPS...)["adminConfirmedUntil"]; ok {
		t.Fatal("member has a confirmation time")
	}
	if _, ok := status(nil)["adminConfirmedUntil"]; ok {
		t.Fatal("visitor has a confirmation time")
	}
}

func TestSettingsTMDBKeyFromConfig(t *testing.T) {
	t.Parallel()
	for _, fromConfig := range []bool{false, true} {
		srv := newTestServerWith(t, func(d *Deps) { d.TMDBKeyFromConfig = fromConfig })
		var raw map[string]json.RawMessage
		doJSON(t, srv.client, "GET", srv.URL+"/api/settings", "", &raw)
		if got := string(raw["tmdbKeyFromConfig"]); got != fmt.Sprint(fromConfig) {
			t.Errorf("from config %v: tmdbKeyFromConfig = %q", fromConfig, got)
		}
	}
}

// The counts match what deleteLibrary removes: they are taken, the library
// is removed, and what is left is checked.
func TestLibraryRemovalCounts(t *testing.T) {
	t.Parallel()
	var tracker *activity.Tracker
	srv := newTestServerWith(t, func(d *Deps) {
		tracker = activity.New(d.Conn, d.Bus, nil, activity.Options{})
		d.Activity = tracker
	})
	ctx := t.Context()
	exec := func(q string, args ...any) {
		t.Helper()
		if _, err := srv.conn.ExecContext(ctx, q, args...); err != nil {
			t.Fatal(q, err)
		}
	}
	member, account := securityTestUser(t, srv, "member")
	tv, err := srv.q.CreateLibrary(ctx, db.CreateLibraryParams{Type: "tv", Name: "Shows", Path: "/tv"})
	if err != nil {
		t.Fatal(err)
	}
	other, err := srv.q.CreateLibrary(ctx, db.CreateLibraryParams{Type: "tv", Name: "Other", Path: "/other"})
	if err != nil {
		t.Fatal(err)
	}
	// Two shows with three episodes between them and a movie, and a show
	// and a movie in another library, which stay.
	exec(`INSERT INTO shows (id, library_id, tmdb_id, title) VALUES (50, ?, 1, 'Meadow'), (51, ?, 2, 'Harbour'), (52, ?, 3, 'Elsewhere')`, tv.ID, tv.ID, other.ID)
	exec(`INSERT INTO seasons (id, show_id, number) VALUES (60, 50, 1), (61, 51, 1), (62, 52, 1)`)
	exec(`INSERT INTO episodes (id, season_id, show_id, number) VALUES (70, 60, 50, 1), (71, 60, 50, 2), (72, 61, 51, 1), (73, 62, 52, 1)`)
	exec(`INSERT INTO movies (id, library_id, tmdb_id, title) VALUES (80, ?, 9, 'Lantern'), (81, ?, 10, 'Kept')`, tv.ID, other.ID)
	for i, path := range []string{"/tv/a.mkv", "/tv/b.mkv", "/tv/c.mkv"} {
		status := "present"
		if i == 2 {
			status = "missing"
		}
		exec(`INSERT INTO media_files (library_id, path, size, mtime, status, parsed, added_at, last_seen_at) VALUES (?, ?, 1, '', ?, '{}', '', '')`, tv.ID, path, status)
	}
	exec(`INSERT INTO media_files (library_id, path, size, mtime, parsed, added_at, last_seen_at, extra_kind) VALUES (?, '/tv/trailer.mkv', 1, '', '{}', '', '', 'trailer')`, tv.ID)
	exec(`INSERT INTO media_files (library_id, path, size, mtime, parsed, added_at, last_seen_at) VALUES (?, '/other/x.mkv', 1, '', '{}', '', '')`, other.ID)
	// The admin watched one episode and is part way through the movie; the
	// member is part way through an episode; the other library's show is
	// watched and stays.
	exec(`INSERT INTO watch_state (user_id, item_type, item_id, position_ms, completed) VALUES
		(1, 'episode', 70, 0, 1), (1, 'movie', 80, 500, 0), (?, 'episode', 72, 900, 0), (1, 'episode', 73, 0, 1), (?, 'movie', 81, 0, 1)`, account.ID, account.ID)
	// Two plays of the library's files and one elsewhere, in history.
	for i, lib := range []int64{tv.ID, tv.ID, other.ID} {
		exec(`INSERT INTO history_items (id, item_type, item_id, title) VALUES (?, 'file', ?, 'x')`, 90+i, 90+i)
		exec(`INSERT INTO plays (public_id, user_id, user_name, history_item_id, library_id, item_type, started_at, last_seen_at, stopped_at, mode, local_day, local_hour, local_weekday)
			VALUES (?, 1, 'admin', ?, ?, 'file', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z', '2026-01-01T00:10:00Z', 'direct', '2026-01-01', 0, 4)`, fmt.Sprint("p", i), 90+i, lib)
	}

	var got LibraryRemoval
	if r := doJSON(t, srv.client, "GET", fmt.Sprintf("%s/api/libraries/%d/removal", srv.URL, tv.ID), "", &got); r.StatusCode != 200 {
		t.Fatalf("removal: %d %s", r.StatusCode, r.Body)
	}
	want := LibraryRemoval{Movies: 1, Shows: 2, Episodes: 3, Files: 3, Watched: 1, InProgress: 2, People: 2, PlaysKept: 2}
	if got != want {
		t.Fatalf("removal = %+v, want %+v", got, want)
	}
	if r := doJSON(t, member, "GET", fmt.Sprintf("%s/api/libraries/%d/removal", srv.URL, tv.ID), "", nil); r.StatusCode != 403 {
		t.Fatalf("member: %d", r.StatusCode)
	}
	if r := doJSON(t, srv.client, "GET", srv.URL+"/api/libraries/999/removal", "", nil); r.StatusCode != 404 {
		t.Fatalf("missing library: %d", r.StatusCode)
	}

	count := func(q string, args ...any) int64 {
		t.Helper()
		var n int64
		if err := srv.conn.QueryRowContext(ctx, q, args...).Scan(&n); err != nil {
			t.Fatal(err)
		}
		return n
	}
	before := map[string]int64{
		"movies": count(`SELECT COUNT(*) FROM movies`), "shows": count(`SELECT COUNT(*) FROM shows`),
		"episodes": count(`SELECT COUNT(*) FROM episodes`), "files": count(`SELECT COUNT(*) FROM media_files WHERE extra_kind IS NULL`),
		"watched": count(`SELECT COUNT(*) FROM watch_state WHERE completed = 1`), "inProgress": count(`SELECT COUNT(*) FROM watch_state WHERE completed = 0`),
		"plays": count(`SELECT COUNT(*) FROM plays`),
	}
	if r := doJSON(t, srv.client, "DELETE", fmt.Sprintf("%s/api/libraries/%d", srv.URL, tv.ID), "", nil); r.StatusCode != 204 {
		t.Fatalf("delete: %d %s", r.StatusCode, r.Body)
	}
	for name, removed := range map[string]int64{
		"movies": want.Movies, "shows": want.Shows, "episodes": want.Episodes, "files": want.Files,
		"watched": want.Watched, "inProgress": want.InProgress, "plays": 0,
	} {
		q := map[string]string{
			"movies": `SELECT COUNT(*) FROM movies`, "shows": `SELECT COUNT(*) FROM shows`, "episodes": `SELECT COUNT(*) FROM episodes`,
			"files": `SELECT COUNT(*) FROM media_files WHERE extra_kind IS NULL`, "watched": `SELECT COUNT(*) FROM watch_state WHERE completed = 1`,
			"inProgress": `SELECT COUNT(*) FROM watch_state WHERE completed = 0`, "plays": `SELECT COUNT(*) FROM plays`,
		}[name]
		if left := count(q); before[name]-left != removed {
			t.Errorf("%s: %d removed, the count said %d", name, before[name]-left, removed)
		}
	}
}

func TestLiveTVSourceRemovalCounts(t *testing.T) {
	t.Parallel()
	fake := hdhrtest.New(nil)
	tuner := httptest.NewServer(fake)
	defer tuner.Close()
	srv := newTestServerWith(t, func(d *Deps) {
		d.LiveTV = livetv.New(d.Queries, d.Settings, func(a string) (*hdhomerun.Client, error) { return hdhomerun.New(a, hdhrtest.Transport(tuner.URL)) })
	})
	var source livetv.Source
	if r := doJSON(t, srv.client, "POST", srv.URL+"/api/settings/live-tv/sources", `{"address":"tuner.test"}`, &source); r.StatusCode != 200 {
		t.Fatalf("add tuner: %d %s", r.StatusCode, r.Body)
	}
	now := time.Now().Unix()
	for i, r := range []struct {
		source, state string
		start, end    int64
	}{
		{source.ID, "scheduled", now + 3600, now + 7200},
		{source.ID, "conflict", now + 3600, now + 7200},
		{source.ID, "gone", now + 3600, now + 7200},
		{source.ID, "recording", now - 600, now + 600},
		{source.ID, "scheduled", now - 7200, now - 3600}, // ran out unrecorded
		{source.ID, "done", now - 7200, now - 3600},
		{source.ID, "cancelled", now + 3600, now + 7200},
		{"other-tuner", "scheduled", now + 3600, now + 7200},
	} {
		if _, err := srv.conn.ExecContext(t.Context(), `INSERT INTO live_tv_recordings (id, source_id, channel_id, number, start_at, end_at, priority, state, section, data, created_at, updated_at)
			VALUES (?, ?, 'c', '2.1', ?, ?, 0, ?, 'upcoming', '{}', 0, 0)`, fmt.Sprint("r", i), r.source, r.start, r.end, r.state); err != nil {
			t.Fatal(err)
		}
	}
	var got LiveTVSourceRemoval
	if r := doJSON(t, srv.client, "GET", srv.URL+"/api/settings/live-tv/sources/"+source.ID+"/removal", "", &got); r.StatusCode != 200 {
		t.Fatalf("removal: %d %s", r.StatusCode, r.Body)
	}
	if want := (LiveTVSourceRemoval{ScheduledRecordings: 3, RecordingNow: 1}); got != want {
		t.Fatalf("removal = %+v, want %+v", got, want)
	}
	if r := doJSON(t, srv.client, "GET", srv.URL+"/api/settings/live-tv/sources/missing/removal", "", nil); r.StatusCode != 404 {
		t.Fatalf("missing tuner: %d", r.StatusCode)
	}
}

func TestCountPlaysOlderThan(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, func(d *Deps) { d.Activity = activity.New(d.Conn, d.Bus, nil, activity.Options{Location: time.UTC}) })
	exec := func(q string, args ...any) {
		t.Helper()
		if _, err := srv.conn.ExecContext(t.Context(), q, args...); err != nil {
			t.Fatal(q, err)
		}
	}
	exec(`INSERT INTO history_items (id, item_type, item_id, title) VALUES (1, 'file', 1, 'x')`)
	day := func(ago int) string { return time.Now().UTC().AddDate(0, 0, -ago).Format(time.DateOnly) }
	for i, p := range []struct {
		ago     int
		stopped bool
	}{{100, true}, {100, true}, {50, true}, {50, false}, {1, true}} {
		stopped := any(nil)
		if p.stopped {
			stopped = "2026-01-01T00:10:00Z"
		}
		exec(`INSERT INTO plays (public_id, user_id, user_name, history_item_id, item_type, started_at, last_seen_at, stopped_at, mode, local_day, local_hour, local_weekday)
			VALUES (?, 1, 'admin', 1, 'file', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z', ?, 'direct', ?, 0, 4)`, fmt.Sprint("p", i), stopped, day(p.ago))
	}
	for days, want := range map[int]int64{30: 3, 60: 2, 365: 0, 1: 3} {
		var got PlaysOlderThan
		if r := doJSON(t, srv.client, "GET", fmt.Sprintf("%s/api/settings/activity/older-than?days=%d", srv.URL, days), "", &got); r.StatusCode != 200 {
			t.Fatalf("%d days: %d %s", days, r.StatusCode, r.Body)
		}
		if got.Plays != want {
			t.Errorf("%d days: %d plays, want %d", days, got.Plays, want)
		}
	}
	for _, q := range []string{"", "?days=0", "?days=-3"} {
		if r := doJSON(t, srv.client, "GET", srv.URL+"/api/settings/activity/older-than"+q, "", nil); r.StatusCode != 422 {
			t.Errorf("%q: %d, want 422", q, r.StatusCode)
		}
	}
}

// A backup into a folder the server can't write to says so in words.
func TestBackupErrorsInWords(t *testing.T) {
	t.Parallel()
	if runtime.GOOS == "windows" {
		t.Skip("folder permissions work differently on Windows")
	}
	if os.Geteuid() == 0 {
		t.Skip("root writes to read-only folders")
	}
	dataDir := t.TempDir()
	srv := newTestServerWith(t, func(d *Deps) { d.DataDir = dataDir })
	if err := os.Chmod(dataDir, 0o500); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chmod(dataDir, 0o700) })
	r := doJSON(t, srv.client, "POST", srv.URL+"/api/backups", "", nil)
	var problem struct{ Detail string }
	_ = json.Unmarshal(r.Body, &problem)
	if r.StatusCode != 500 || problem.Detail != "the server can't write to the data folder" {
		t.Fatalf("got %d %s", r.StatusCode, r.Body)
	}
	if _, err := os.Stat(filepath.Join(dataDir, "backups")); !os.IsNotExist(err) {
		t.Fatalf("backups folder: %v", err)
	}
}

func TestBackupErrorMapping(t *testing.T) {
	t.Parallel()
	for _, tc := range []struct {
		err    error
		status int
	}{
		{&os.PathError{Op: "write", Path: "x", Err: syscall.ENOSPC}, http.StatusInsufficientStorage},
		{fmt.Errorf("copy: %w", &os.PathError{Op: "open", Path: "x", Err: os.ErrPermission}), http.StatusInternalServerError},
		{&os.PathError{Op: "mkdir", Path: "x", Err: syscall.EROFS}, http.StatusInternalServerError},
		{os.ErrNotExist, 0},
		{nil, 0},
	} {
		got := backupError(tc.err)
		if tc.status == 0 {
			if got != nil {
				t.Errorf("%v: explained as %v", tc.err, got)
			}
			continue
		}
		se, ok := errors.AsType[huma.StatusError](got)
		if !ok || se.GetStatus() != tc.status {
			t.Errorf("%v: %v, want %d", tc.err, got, tc.status)
		}
	}
}
