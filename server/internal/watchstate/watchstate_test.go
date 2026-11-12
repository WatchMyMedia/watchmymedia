package watchstate

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/events"
)

type fixture struct {
	ctx    context.Context
	conn   *sql.DB
	q      *db.Queries
	s      *Service
	bus    *events.Bus
	movies db.Library
	tv     db.Library
	file   int64
	user   int64
	tmdb   int64
}

func setup(t *testing.T) *fixture {
	t.Helper()
	ctx := context.Background()
	conn, q := dbtest.Open(t)
	bus := events.NewBus()
	f := &fixture{ctx: ctx, conn: conn, q: q, s: New(q, bus), bus: bus, tmdb: 1000}
	var err error
	if f.movies, err = q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "movie", Name: "Movies", Path: "/m"}); err != nil {
		t.Fatal(err)
	}
	if f.tv, err = q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: "/t"}); err != nil {
		t.Fatal(err)
	}
	mf, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: f.movies.ID, Path: "/m/a.mkv", Size: 1, Mtime: db.FormatTime(time.Now()),
		AddedAt: db.FormatTime(time.Now()), Parsed: "{}", LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	f.file = mf.ID
	f.user = f.newUser(t, "viewer")
	return f
}

func (f *fixture) newUser(t *testing.T, name string) int64 {
	t.Helper()
	u, err := f.q.CreateUser(f.ctx, db.CreateUserParams{Name: name, PasswordHash: "x"})
	if err != nil {
		t.Fatal(err)
	}
	return u.ID
}

// movie adds a movie with a TMDB ID of its own and returns its import target.
func (f *fixture) movie(t *testing.T) Target {
	t.Helper()
	f.tmdb++
	m, err := f.q.UpsertMovie(f.ctx, db.UpsertMovieParams{LibraryID: f.movies.ID, TmdbID: f.tmdb, Title: fmt.Sprintf("Movie %d", f.tmdb), Genres: "[]", Credits: "[]"})
	if err != nil {
		t.Fatal(err)
	}
	return Target{ItemType: Movie, ItemID: m.ID, Identity: Identity{LibraryID: f.movies.ID, TMDBID: f.tmdb}}
}

// show adds a show with the given number of episodes in each season,
// numbered from 1, and returns its ID, its seasons' IDs and their
// episodes' import targets.
func (f *fixture) show(t *testing.T, episodes ...int) (int64, []int64, [][]Target) {
	t.Helper()
	f.tmdb++
	sh, err := f.q.UpsertShow(f.ctx, db.UpsertShowParams{LibraryID: f.tv.ID, TmdbID: f.tmdb, Title: fmt.Sprintf("Show %d", f.tmdb), Genres: "[]", Credits: "[]"})
	if err != nil {
		t.Fatal(err)
	}
	var seasons []int64
	var targets [][]Target
	for i, n := range episodes {
		se, err := f.q.UpsertSeason(f.ctx, db.UpsertSeasonParams{ShowID: sh.ID, Number: int64(i + 1), EpisodeCount: int64(n)})
		if err != nil {
			t.Fatal(err)
		}
		seasons = append(seasons, se.ID)
		var eps []Target
		for e := 1; e <= n; e++ {
			ep, err := f.q.UpsertEpisode(f.ctx, db.UpsertEpisodeParams{SeasonID: se.ID, ShowID: sh.ID, Number: int64(e), Title: fmt.Sprintf("Episode %d", e)})
			if err != nil {
				t.Fatal(err)
			}
			eps = append(eps, Target{ItemType: Episode, ItemID: ep.ID, Identity: Identity{LibraryID: f.tv.ID, TMDBID: f.tmdb, Season: int64(i + 1), Episode: int64(e)}})
		}
		targets = append(targets, eps)
	}
	return sh.ID, seasons, targets
}

// current reads the user's guard and state on the target; either may be nil.
func (f *fixture) current(t *testing.T, user int64, target Target) (*db.WatchGuard, *db.WatchState) {
	t.Helper()
	g, w, err := current(f.ctx, f.q, user, target.ItemType, target.ItemID)
	if err != nil {
		t.Fatal(err)
	}
	return g, w
}

func (f *fixture) report(t *testing.T, user int64, target Target, positionMs int64) {
	t.Helper()
	if _, err := f.s.Report(f.ctx, Progress{UserID: user, ItemType: target.ItemType, ItemIDs: []int64{target.ItemID}, FileID: f.file,
		PositionMs: positionMs, DurationMs: 600_000, Playing: true}); err != nil {
		t.Fatal(err)
	}
}

func (f *fixture) importOne(t *testing.T, user, run int64, target Target) Outcome {
	t.Helper()
	got, err := f.s.Import(f.ctx, user, run, []Target{target})
	if err != nil {
		t.Fatal(err)
	}
	return got[0]
}

// legacy writes state as a server from before guards did: no guard.
func (f *fixture) legacy(t *testing.T, user int64, target Target, positionMs, completed int64) {
	t.Helper()
	if _, err := f.q.UpsertWatchState(f.ctx, db.UpsertWatchStateParams{UserID: user, ItemType: target.ItemType, ItemID: target.ItemID, PositionMs: positionMs, Completed: completed}); err != nil {
		t.Fatal(err)
	}
}

func sameGuard(a, b *db.WatchGuard) bool {
	if a == nil || b == nil {
		return a == b
	}
	return a.Owner == b.Owner && a.Revision == b.Revision && a.UpdatedAt == b.UpdatedAt && (a.ImportRun == nil) == (b.ImportRun == nil) &&
		(a.ImportRun == nil || *a.ImportRun == *b.ImportRun)
}

func sameState(a, b *db.WatchState) bool {
	if a == nil || b == nil {
		return a == b
	}
	return a.PositionMs == b.PositionMs && a.DurationMs == b.DurationMs && a.Completed == b.Completed && a.UpdatedAt == b.UpdatedAt &&
		(a.MediaFileID == nil) == (b.MediaFileID == nil)
}

// Every local situation in PLAN-PLEX-SYNC.md's merge rules, against an
// import of the same item: only an item nothing here has decided is marked,
// and nothing else changes, not even a time.
func TestImportKeepsLocalChoices(t *testing.T) {
	f := setup(t)
	_, seasons, eps := f.show(t, 2, 2, 2)
	showID, _, showEps := f.show(t, 1, 2)
	cases := []struct {
		name   string
		target Target
		local  func(Target)
		want   Outcome
	}{
		{"nothing here", f.movie(t), func(Target) {}, Applied},
		{"watched here", f.movie(t), func(m Target) { _ = f.s.MarkWatched(f.ctx, f.user, Movie, m.ItemID) }, AlreadyWatched},
		{"unfinished play", f.movie(t), func(m Target) { f.report(t, f.user, m, 60_000) }, LocalKept},
		{"rewatch", f.movie(t), func(m Target) {
			_ = f.s.MarkWatched(f.ctx, f.user, Movie, m.ItemID)
			f.report(t, f.user, m, 30_000)
		}, LocalKept},
		{"unwatched", f.movie(t), func(m Target) {
			_ = f.s.MarkWatched(f.ctx, f.user, Movie, m.ItemID)
			_ = f.s.MarkUnwatched(f.ctx, f.user, Movie, m.ItemID)
		}, LocalKept},
		{"unwatched without ever watching", f.movie(t), func(m Target) { _ = f.s.MarkUnwatched(f.ctx, f.user, Movie, m.ItemID) }, LocalKept},
		{"playback started, no report yet", f.movie(t), func(m Target) { _ = f.s.Claim(f.ctx, f.user, Movie, []int64{m.ItemID}) }, LocalKept},
		{"imported before", f.movie(t), func(m Target) { f.importOne(t, f.user, 3, m) }, AlreadyWatched},
		{"older server's progress", f.movie(t), func(m Target) { f.legacy(t, f.user, m, 60_000, 0) }, LocalKept},
		{"older server's mark", f.movie(t), func(m Target) { f.legacy(t, f.user, m, 0, 1) }, AlreadyWatched},
		{"season cleared, never watched", eps[0][1], func(Target) { _ = f.s.MarkSeason(f.ctx, f.user, seasons[0], false) }, LocalKept},
		{"season cleared after watching", eps[1][0], func(Target) {
			_ = f.s.MarkSeason(f.ctx, f.user, seasons[1], true)
			_ = f.s.MarkSeason(f.ctx, f.user, seasons[1], false)
		}, LocalKept},
		{"season watched", eps[2][1], func(Target) { _ = f.s.MarkSeason(f.ctx, f.user, seasons[2], true) }, AlreadyWatched},
		{"show cleared", showEps[1][1], func(Target) { _ = f.s.MarkShow(f.ctx, f.user, showID, false) }, LocalKept},
		{"another episode of the same file played", showEps[1][0], func(e Target) {
			if _, err := f.s.Report(f.ctx, Progress{UserID: f.user, ItemType: Episode, ItemIDs: []int64{showEps[0][0].ItemID, e.ItemID}, FileID: f.file, PositionMs: 5000, Playing: true}); err != nil {
				t.Fatal(err)
			}
		}, LocalKept},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			c.local(c.target)
			guard, state := f.current(t, f.user, c.target)
			preview, err := f.s.Evaluate(f.ctx, f.user, []Target{c.target})
			if err != nil {
				t.Fatal(err)
			}
			got := f.importOne(t, f.user, 7, c.target)
			if got != c.want || preview[0] != got {
				t.Fatalf("import = %s, preview %s, want %s", got, preview[0], c.want)
			}
			afterGuard, afterState := f.current(t, f.user, c.target)
			if got != Applied {
				if !sameGuard(guard, afterGuard) || !sameState(state, afterState) {
					t.Fatalf("changed: guard %+v -> %+v, state %+v -> %+v", guard, afterGuard, state, afterState)
				}
				return
			}
			if afterState == nil || afterState.Completed != 1 || afterState.PositionMs != 0 || afterState.MediaFileID != nil {
				t.Errorf("imported state = %+v", afterState)
			}
			if afterGuard == nil || afterGuard.Owner != "import" || afterGuard.ImportRun == nil || *afterGuard.ImportRun != 7 {
				t.Errorf("imported guard = %+v", afterGuard)
			}
		})
	}
	// Another user's choices are their own: the first user's season clear
	// leaves the second free to import.
	other := f.newUser(t, "other")
	if got := f.importOne(t, other, 7, eps[0][1]); got != Applied {
		t.Errorf("other user's import = %s", got)
	}
}

// A local change after an import wins, as any later local change does, and
// a preview taken before a local change does not let the import through.
func TestLocalChangesOverrideImports(t *testing.T) {
	f := setup(t)
	progress, unwatch, start, stale := f.movie(t), f.movie(t), f.movie(t), f.movie(t)
	for _, m := range []Target{progress, unwatch, start} {
		if got := f.importOne(t, f.user, 1, m); got != Applied {
			t.Fatalf("first import = %s", got)
		}
	}
	f.report(t, f.user, progress, 90_000)
	if err := f.s.MarkUnwatched(f.ctx, f.user, Movie, unwatch.ItemID); err != nil {
		t.Fatal(err)
	}
	if err := f.s.Claim(f.ctx, f.user, Movie, []int64{start.ItemID}); err != nil {
		t.Fatal(err)
	}
	g, w := f.current(t, f.user, progress)
	if w == nil || w.Completed != 0 || w.PositionMs != 90_000 || g.Owner != "local" || g.ImportRun != nil {
		t.Errorf("progress after import: %+v %+v", g, w)
	}
	g, w = f.current(t, f.user, unwatch)
	if w != nil || g == nil || g.Owner != "local" {
		t.Errorf("unwatch after import: %+v %+v", g, w)
	}
	g, w = f.current(t, f.user, start)
	if w == nil || w.Completed != 1 || g.Owner != "local" {
		t.Errorf("playback start after import: %+v %+v", g, w)
	}
	for _, m := range []Target{progress, unwatch} {
		if got := f.importOne(t, f.user, 2, m); got != LocalKept {
			t.Errorf("import again = %s", got)
		}
	}

	preview, err := f.s.Evaluate(f.ctx, f.user, []Target{stale})
	if err != nil || preview[0] != Applied {
		t.Fatalf("preview = %v %v", preview, err)
	}
	if err := f.s.MarkUnwatched(f.ctx, f.user, Movie, stale.ItemID); err != nil {
		t.Fatal(err)
	}
	if got := f.importOne(t, f.user, 2, stale); got != LocalKept {
		t.Errorf("import after a local unwatch since the preview = %s", got)
	}
}

// Imports mark only the item they matched: not one that took a deleted
// item's ID, not a rematched one, and not with a deleted user.
func TestImportChecksIdentity(t *testing.T) {
	f := setup(t)
	m := f.movie(t)
	wrong := m
	wrong.Identity.TMDBID++
	inOtherLibrary := m
	inOtherLibrary.Identity.LibraryID = f.tv.ID
	asEpisode := m
	asEpisode.Identity.Season = 1
	got, err := f.s.Import(f.ctx, f.user, 1, []Target{wrong, inOtherLibrary, asEpisode})
	if err != nil {
		t.Fatal(err)
	}
	for i, o := range got {
		if o != Unavailable {
			t.Errorf("mismatch %d = %s", i, o)
		}
	}
	if _, w := f.current(t, f.user, m); w != nil {
		t.Fatalf("a mismatch wrote state: %+v", w)
	}

	// The newest movie's ID is the one SQLite hands out again once it is
	// deleted. Its state and guards go with it, and an import for it does
	// not land on its successor.
	gone := f.movie(t)
	f.report(t, f.user, gone, 60_000)
	if err := f.q.DeleteMovie(f.ctx, gone.ItemID); err != nil {
		t.Fatal(err)
	}
	successor := f.movie(t)
	if successor.ItemID != gone.ItemID {
		t.Fatalf("successor ID %d, want the reused %d", successor.ItemID, gone.ItemID)
	}
	if g, w := f.current(t, f.user, successor); g != nil || w != nil {
		t.Fatalf("successor inherited %+v %+v", g, w)
	}
	if got := f.importOne(t, f.user, 1, gone); got != Unavailable {
		t.Errorf("import for the deleted movie = %s", got)
	}
	if got := f.importOne(t, f.user, 1, successor); got != Applied {
		t.Errorf("import for the successor = %s", got)
	}

	_, _, eps := f.show(t, 2)
	e := eps[0][0]
	moved := e
	moved.Identity.Episode = 2
	if got := f.importOne(t, f.user, 1, moved); got != Unavailable {
		t.Errorf("episode under another number = %s", got)
	}
	if got := f.importOne(t, f.user, 1, e); got != Applied {
		t.Errorf("episode = %s", got)
	}

	other := f.newUser(t, "other")
	f.report(t, other, eps[0][1], 1000)
	if err := f.q.DeleteUser(f.ctx, other); err != nil {
		t.Fatal(err)
	}
	if _, err := f.s.Import(f.ctx, other, 1, []Target{m}); !errors.Is(err, ErrNoUser) {
		t.Errorf("import for a deleted user: %v", err)
	}
	if _, err := f.s.Evaluate(f.ctx, other, []Target{m}); !errors.Is(err, ErrNoUser) {
		t.Errorf("preview for a deleted user: %v", err)
	}
	var left int
	if err := f.conn.QueryRowContext(f.ctx, `SELECT count(*) FROM watch_guards WHERE user_id = ?`, other).Scan(&left); err != nil || left != 0 {
		t.Errorf("deleted user's guards = %d %v", left, err)
	}
	if _, err := f.s.Import(f.ctx, f.user, 0, []Target{m}); err == nil {
		t.Error("import without a run accepted")
	}
}

// An import announces only what it marked, once per item type, after the
// commit.
func TestImportAnnouncesWhatItMarked(t *testing.T) {
	f := setup(t)
	a, b := f.movie(t), f.movie(t)
	_, _, eps := f.show(t, 2)
	if err := f.s.MarkUnwatched(f.ctx, f.user, Movie, b.ItemID); err != nil {
		t.Fatal(err)
	}
	ch, cancel := f.bus.Subscribe()
	defer cancel()
	if _, err := f.s.Import(f.ctx, f.user, 1, []Target{a, b, eps[0][0], eps[0][1]}); err != nil {
		t.Fatal(err)
	}
	var got []WatchChanged
	for len(got) < 2 {
		select {
		case ev := <-ch:
			if ev.Type != "watch.changed" || ev.To == nil || ev.To.UserID != f.user || !ev.To.Private {
				t.Fatalf("event %+v to %+v", ev, ev.To)
			}
			got = append(got, ev.Data.(WatchChanged))
		case <-time.After(time.Second):
			t.Fatalf("events = %+v", got)
		}
	}
	if got[0].ItemType != Movie || len(got[0].ItemIDs) != 1 || got[0].ItemIDs[0] != a.ItemID ||
		got[1].ItemType != Episode || len(got[1].ItemIDs) != 2 {
		t.Errorf("events = %+v", got)
	}
	select {
	case ev := <-ch:
		t.Errorf("extra event %+v", ev)
	default:
	}
}

// Local changes and imports racing on the same items: whichever commits
// first, the local change decides. Run with -race.
func TestImportRacesLocalChanges(t *testing.T) {
	f := setup(t)
	type action struct {
		name string
		do   func(Target) error
		// check is the state after the local change, whether the import
		// committed before it or not.
		check func(*db.WatchGuard, *db.WatchState) bool
	}
	local := func(g *db.WatchGuard) bool { return g != nil && g.Owner == "local" && g.ImportRun == nil }
	actions := []action{
		{"progress", func(m Target) error {
			_, err := f.s.Report(f.ctx, Progress{UserID: f.user, ItemType: Movie, ItemIDs: []int64{m.ItemID}, FileID: f.file, PositionMs: 42_000, DurationMs: 600_000, Playing: true})
			return err
		}, func(g *db.WatchGuard, w *db.WatchState) bool {
			return local(g) && w != nil && w.Completed == 0 && w.PositionMs == 42_000
		}},
		{"unwatch", func(m Target) error { return f.s.MarkUnwatched(f.ctx, f.user, Movie, m.ItemID) },
			func(g *db.WatchGuard, w *db.WatchState) bool { return local(g) && w == nil }},
		{"mark watched", func(m Target) error { return f.s.MarkWatched(f.ctx, f.user, Movie, m.ItemID) },
			func(g *db.WatchGuard, w *db.WatchState) bool { return local(g) && w != nil && w.Completed == 1 }},
		// A playback start leaves an import's mark until the first report,
		// but owns the item either way.
		{"playback start", func(m Target) error { return f.s.Claim(f.ctx, f.user, Movie, []int64{m.ItemID}) },
			func(g *db.WatchGuard, w *db.WatchState) bool { return local(g) && (w == nil || w.Completed == 1) }},
	}
	const perAction = 12
	var targets []Target
	for range actions {
		for range perAction {
			targets = append(targets, f.movie(t))
		}
	}
	importedFirst := 0
	var mu sync.Mutex
	var wg sync.WaitGroup
	start := make(chan struct{})
	for i, m := range targets {
		a := actions[i/perAction]
		wg.Add(2)
		go func() {
			defer wg.Done()
			<-start
			if err := a.do(m); err != nil {
				t.Errorf("%s: %v", a.name, err)
			}
		}()
		go func() {
			defer wg.Done()
			<-start
			got, err := f.s.Import(f.ctx, f.user, 9, []Target{m})
			if err != nil {
				t.Errorf("import: %v", err)
				return
			}
			if got[0] == Applied {
				mu.Lock()
				importedFirst++
				mu.Unlock()
			}
		}()
	}
	close(start)
	wg.Wait()
	for i, m := range targets {
		a := actions[i/perAction]
		if g, w := f.current(t, f.user, m); !a.check(g, w) {
			t.Errorf("%s on movie %d: guard %+v, state %+v", a.name, m.ItemID, g, w)
		}
	}
	t.Logf("the import committed first for %d of %d items", importedFirst, len(targets))
}

// Rematches move each user's state and guard: the destination's own choice
// stays, the source's otherwise replaces an import, and what moves is local.
func TestMoveRules(t *testing.T) {
	f := setup(t)
	from, to := f.movie(t), f.movie(t)
	users := map[string]int64{}
	for _, name := range []string{"progress", "unwatch", "dest-unwatched", "imported", "dest-progress", "dest-imported-only"} {
		users[name] = f.newUser(t, name)
	}
	f.report(t, users["progress"], from, 60_000)
	before, _ := f.q.GetWatchState(f.ctx, db.GetWatchStateParams{UserID: users["progress"], ItemType: Movie, ItemID: from.ItemID})

	if err := f.s.MarkUnwatched(f.ctx, users["unwatch"], Movie, from.ItemID); err != nil {
		t.Fatal(err)
	}
	f.importOne(t, users["unwatch"], 1, to)

	_ = f.s.MarkWatched(f.ctx, users["dest-unwatched"], Movie, from.ItemID)
	_ = f.s.MarkUnwatched(f.ctx, users["dest-unwatched"], Movie, to.ItemID)
	destGuard, _ := f.current(t, users["dest-unwatched"], to)

	f.importOne(t, users["imported"], 1, from)

	f.report(t, users["dest-progress"], from, 10_000)
	f.report(t, users["dest-progress"], to, 20_000)

	f.importOne(t, users["dest-imported-only"], 1, to)

	if err := f.s.Move(f.ctx, Movie, []Move{{From: from.ItemID, To: to.ItemID}}); err != nil {
		t.Fatal(err)
	}
	check := func(name string, ok func(*db.WatchGuard, *db.WatchState) bool) {
		t.Helper()
		g, w := f.current(t, users[name], to)
		if !ok(g, w) {
			t.Errorf("%s: guard %+v, state %+v", name, g, w)
		}
		if g, w := f.current(t, users[name], from); g != nil || w != nil {
			t.Errorf("%s: left on the old item: %+v %+v", name, g, w)
		}
	}
	check("progress", func(g *db.WatchGuard, w *db.WatchState) bool {
		return g.Owner == "local" && w.PositionMs == 60_000 && w.UpdatedAt == before.UpdatedAt && *w.MediaFileID == f.file
	})
	check("unwatch", func(g *db.WatchGuard, w *db.WatchState) bool { return g.Owner == "local" && w == nil })
	check("dest-unwatched", func(g *db.WatchGuard, w *db.WatchState) bool { return sameGuard(g, destGuard) && w == nil })
	check("imported", func(g *db.WatchGuard, w *db.WatchState) bool {
		return g.Owner == "local" && g.ImportRun == nil && w.Completed == 1
	})
	check("dest-progress", func(g *db.WatchGuard, w *db.WatchState) bool { return g.Owner == "local" && w.PositionMs == 20_000 })
	check("dest-imported-only", func(g *db.WatchGuard, w *db.WatchState) bool { return g.Owner == "import" && w.Completed == 1 })

	// Moving an item onto itself changes nothing; moving it nowhere clears it.
	f.report(t, f.user, from, 5000)
	if err := f.s.Move(f.ctx, Movie, []Move{{From: from.ItemID, To: from.ItemID}}); err != nil {
		t.Fatal(err)
	}
	if g, w := f.current(t, f.user, from); g == nil || w == nil {
		t.Errorf("moved onto itself: %+v %+v", g, w)
	}
	if err := f.s.Move(f.ctx, Movie, []Move{{From: from.ItemID}}); err != nil {
		t.Fatal(err)
	}
	if g, w := f.current(t, f.user, from); g != nil || w != nil {
		t.Errorf("cleared: %+v %+v", g, w)
	}
}
