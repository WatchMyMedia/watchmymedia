package metadata

import (
	"encoding/json"
	"strings"
	"testing"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/parser"
	"watchmymedia/server/internal/watchstate"
)

func TestRematchShowCarriesWatchState(t *testing.T) {
	e := setup(t)
	e.file(e.tv, "/t/BB/Season 01/BB - S01E01.mkv", `{"kind":"episode","title":"Breaking Bad","season":1,"episodes":[1]}`)
	e.file(e.tv, "/t/BB/Season 01/BB - S01E02.mkv", `{"kind":"episode","title":"Breaking Bad","season":1,"episodes":[2]}`)
	if sum, err := e.m.MatchLibrary(e.ctx, e.tv, false); err != nil || sum.Matched != 2 {
		t.Fatalf("match: %+v %v", sum, err)
	}
	old, err := e.q.GetShowByTMDB(e.ctx, db.GetShowByTMDBParams{LibraryID: e.tv.ID, TmdbID: 1396})
	if err != nil {
		t.Fatal(err)
	}
	user, _ := e.q.CreateUser(e.ctx, db.CreateUserParams{Name: "u", PasswordHash: "x"})
	for _, n := range []int64{1, 2} {
		id, _ := e.m.episodeByNumber(e.ctx, old.ID, 1, n)
		if _, err := e.q.UpsertWatchState(e.ctx, db.UpsertWatchStateParams{UserID: user.ID, ItemType: "episode", ItemID: id, Completed: 1}); err != nil {
			t.Fatal(err)
		}
	}

	plan, err := e.m.PreviewShowRematch(e.ctx, old, 1397)
	if err != nil {
		t.Fatal(err)
	}
	if plan != (RematchPlan{Files: 2, Found: 1, Watched: 2, WatchedKept: 1}) {
		t.Errorf("plan = %+v", plan)
	}

	res, err := e.m.RematchShow(e.ctx, e.tv, old, 1397, true)
	if err != nil {
		t.Fatal(err)
	}
	if res.Moved != 1 || res.NeedEpisodes != 1 || res.WatchedKept != 1 || res.WatchedCleared != 1 || res.Folder != "BB" || res.ShowID == nil {
		t.Fatalf("result = %+v", res)
	}
	if _, err := e.q.GetShow(e.ctx, old.ID); err == nil {
		t.Error("old show still exists")
	}
	ep, ok := e.m.episodeByNumber(e.ctx, *res.ShowID, 1, 1)
	if !ok {
		t.Fatal("new S01E01 missing")
	}
	if w, err := e.q.GetWatchState(e.ctx, db.GetWatchStateParams{UserID: user.ID, ItemType: "episode", ItemID: ep}); err != nil || w.Completed != 1 {
		t.Errorf("watch state on new episode = %+v %v", w, err)
	}
	f, _ := e.q.GetMediaFileByPath(e.ctx, "/t/BB/Season 01/BB - S01E02.mkv")
	if f.MatchStatus != StatusUnmatched {
		t.Errorf("E02 = %s", f.MatchStatus)
	}
	if id, _ := e.q.GetMatchOverride(e.ctx, db.GetMatchOverrideParams{LibraryID: e.tv.ID, Folder: "BB"}); id != 1397 {
		t.Errorf("override = %d", id)
	}
}

func TestRematchMovie(t *testing.T) {
	e := setup(t)
	e.file(e.mov, "/m/The Matrix (1999)/The Matrix.mkv", `{"kind":"movie","title":"The Matrix","year":1999}`)
	if sum, err := e.m.MatchLibrary(e.ctx, e.mov, false); err != nil || sum.Matched != 1 {
		t.Fatalf("match: %+v %v", sum, err)
	}
	f, _ := e.q.GetMediaFileByPath(e.ctx, "/m/The Matrix (1999)/The Matrix.mkv")
	old, _ := e.q.GetMovie(e.ctx, *f.MovieID)
	user, _ := e.q.CreateUser(e.ctx, db.CreateUserParams{Name: "u", PasswordHash: "x"})
	if _, err := e.q.UpsertWatchState(e.ctx, db.UpsertWatchStateParams{UserID: user.ID, ItemType: "movie", ItemID: old.ID, PositionMs: 5000}); err != nil {
		t.Fatal(err)
	}
	res, err := e.m.RematchMovie(e.ctx, e.mov, old, 949, false)
	if err != nil {
		t.Fatal(err)
	}
	if res.Moved != 1 || res.WatchedKept != 1 || res.MovieID == nil || res.Folder != "" {
		t.Fatalf("result = %+v", res)
	}
	if _, err := e.q.GetMovie(e.ctx, old.ID); err == nil {
		t.Error("old movie still exists")
	}
	if w, err := e.q.GetWatchState(e.ctx, db.GetWatchStateParams{UserID: user.ID, ItemType: "movie", ItemID: *res.MovieID}); err != nil || w.PositionMs != 5000 {
		t.Errorf("watch state = %+v %v", w, err)
	}
	if row, _ := e.q.GetMediaFile(e.ctx, f.ID); row.MatchLocked != 1 || *row.MovieID != *res.MovieID {
		t.Errorf("file = locked %d movie %v", row.MatchLocked, row.MovieID)
	}
}

// An unwatch leaves a guard and no watch state. A rematch carries it to the
// new episode all the same, so an import retried after the rematch still
// leaves the episode unwatched.
func TestRematchShowCarriesUnwatches(t *testing.T) {
	e := setup(t)
	e.file(e.tv, "/t/BB/Season 01/BB - S01E01.mkv", `{"kind":"episode","title":"Breaking Bad","season":1,"episodes":[1]}`)
	f2 := e.file(e.tv, "/t/BB/Season 01/BB - S01E02.mkv", `{"kind":"episode","title":"Breaking Bad","season":1,"episodes":[2]}`)
	if sum, err := e.m.MatchLibrary(e.ctx, e.tv, false); err != nil || sum.Matched != 2 {
		t.Fatalf("match: %+v %v", sum, err)
	}
	old, err := e.q.GetShowByTMDB(e.ctx, db.GetShowByTMDBParams{LibraryID: e.tv.ID, TmdbID: 1396})
	if err != nil {
		t.Fatal(err)
	}
	user, _ := e.q.CreateUser(e.ctx, db.CreateUserParams{Name: "u", PasswordHash: "x"})
	ws := e.m.opts.Watch
	e1, _ := e.m.episodeByNumber(e.ctx, old.ID, 1, 1)
	e2, _ := e.m.episodeByNumber(e.ctx, old.ID, 1, 2)
	if err := ws.MarkUnwatched(e.ctx, user.ID, watchstate.Episode, e1); err != nil {
		t.Fatal(err)
	}
	if _, err := ws.Report(e.ctx, watchstate.Progress{UserID: user.ID, ItemType: watchstate.Episode, ItemIDs: []int64{e2}, FileID: f2.ID, PositionMs: 1000, Playing: true}); err != nil {
		t.Fatal(err)
	}

	res, err := e.m.RematchShow(e.ctx, e.tv, old, 1397, false)
	if err != nil {
		t.Fatal(err)
	}
	if res.WatchedKept != 0 || res.WatchedCleared != 1 {
		t.Fatalf("result = %+v", res)
	}
	to, ok := e.m.episodeByNumber(e.ctx, *res.ShowID, 1, 1)
	if !ok {
		t.Fatal("new S01E01 missing")
	}
	if g, err := e.q.GetWatchGuard(e.ctx, db.GetWatchGuardParams{UserID: user.ID, ItemType: watchstate.Episode, ItemID: to}); err != nil || g.Owner != "local" {
		t.Errorf("guard on the new episode = %+v %v", g, err)
	}
	if _, err := e.q.GetWatchState(e.ctx, db.GetWatchStateParams{UserID: user.ID, ItemType: watchstate.Episode, ItemID: to}); err == nil {
		t.Error("the unwatch became watch state")
	}
	target := watchstate.Target{ItemType: watchstate.Episode, ItemID: to, Identity: watchstate.Identity{LibraryID: e.tv.ID, TMDBID: 1397, Season: 1, Episode: 1}}
	stale := target
	stale.Identity.TMDBID = 1396
	got, err := ws.Import(e.ctx, user.ID, 1, []watchstate.Target{target, stale})
	if err != nil {
		t.Fatal(err)
	}
	if got[0] != watchstate.LocalKept || got[1] != watchstate.Unavailable {
		t.Errorf("import after the rematch = %v", got)
	}
	for _, id := range []int64{e1, e2} {
		if g, err := e.q.GetWatchGuard(e.ctx, db.GetWatchGuardParams{UserID: user.ID, ItemType: watchstate.Episode, ItemID: id}); err == nil {
			t.Errorf("guard left on old episode %d: %+v", id, g)
		}
	}
}

// A movie rematch moves progress, unwatches and imported marks, and what
// arrives is the user's own: retrying the import changes none of it, and an
// import for the old movie does not reach the new one.
func TestRematchMovieCarriesLocalChoices(t *testing.T) {
	e := setup(t)
	e.file(e.mov, "/m/The Matrix (1999)/The Matrix.mkv", `{"kind":"movie","title":"The Matrix","year":1999}`)
	if sum, err := e.m.MatchLibrary(e.ctx, e.mov, false); err != nil || sum.Matched != 1 {
		t.Fatalf("match: %+v %v", sum, err)
	}
	f, _ := e.q.GetMediaFileByPath(e.ctx, "/m/The Matrix (1999)/The Matrix.mkv")
	old, _ := e.q.GetMovie(e.ctx, *f.MovieID)
	ws := e.m.opts.Watch
	users := map[string]int64{}
	for _, name := range []string{"progress", "unwatched", "imported"} {
		u, _ := e.q.CreateUser(e.ctx, db.CreateUserParams{Name: name, PasswordHash: "x"})
		users[name] = u.ID
	}
	if _, err := ws.Report(e.ctx, watchstate.Progress{UserID: users["progress"], ItemType: watchstate.Movie, ItemIDs: []int64{old.ID}, FileID: f.ID, PositionMs: 5000, Playing: true}); err != nil {
		t.Fatal(err)
	}
	if err := ws.MarkUnwatched(e.ctx, users["unwatched"], watchstate.Movie, old.ID); err != nil {
		t.Fatal(err)
	}
	oldTarget := watchstate.Target{ItemType: watchstate.Movie, ItemID: old.ID, Identity: watchstate.Identity{LibraryID: e.mov.ID, TMDBID: 603}}
	if got, err := ws.Import(e.ctx, users["imported"], 5, []watchstate.Target{oldTarget}); err != nil || got[0] != watchstate.Applied {
		t.Fatalf("import before the rematch = %v %v", got, err)
	}

	res, err := e.m.RematchMovie(e.ctx, e.mov, old, 949, false)
	if err != nil {
		t.Fatal(err)
	}
	if res.WatchedKept != 2 || res.MovieID == nil {
		t.Fatalf("result = %+v", res)
	}
	state := func(name string) (*db.WatchGuard, *db.WatchState) {
		t.Helper()
		var g *db.WatchGuard
		var w *db.WatchState
		if row, err := e.q.GetWatchGuard(e.ctx, db.GetWatchGuardParams{UserID: users[name], ItemType: watchstate.Movie, ItemID: *res.MovieID}); err == nil {
			g = &row
		}
		if row, err := e.q.GetWatchState(e.ctx, db.GetWatchStateParams{UserID: users[name], ItemType: watchstate.Movie, ItemID: *res.MovieID}); err == nil {
			w = &row
		}
		return g, w
	}
	if g, w := state("progress"); g == nil || g.Owner != "local" || w == nil || w.PositionMs != 5000 {
		t.Errorf("progress: %+v %+v", g, w)
	}
	if g, w := state("unwatched"); g == nil || g.Owner != "local" || w != nil {
		t.Errorf("unwatched: %+v %+v", g, w)
	}
	if g, w := state("imported"); g == nil || g.Owner != "local" || g.ImportRun != nil || w == nil || w.Completed != 1 {
		t.Errorf("imported: %+v %+v", g, w)
	}
	target := watchstate.Target{ItemType: watchstate.Movie, ItemID: *res.MovieID, Identity: watchstate.Identity{LibraryID: e.mov.ID, TMDBID: 949}}
	want := map[string]watchstate.Outcome{"progress": watchstate.LocalKept, "unwatched": watchstate.LocalKept, "imported": watchstate.AlreadyWatched}
	for name, w := range want {
		got, err := ws.Import(e.ctx, users[name], 6, []watchstate.Target{target, oldTarget, {ItemType: watchstate.Movie, ItemID: *res.MovieID, Identity: oldTarget.Identity}})
		if err != nil {
			t.Fatal(err)
		}
		if got[0] != w || got[1] != watchstate.Unavailable || got[2] != watchstate.Unavailable {
			t.Errorf("%s: import after the rematch = %v", name, got)
		}
	}
}

func TestIgnoredFilesAreSkipped(t *testing.T) {
	e := setup(t)
	f := e.file(e.mov, "/m/Heat (1995)/Heat-sample.mkv", `{"kind":"movie","title":"Heat","year":1995}`)
	if err := e.q.SetMediaFileIgnored(e.ctx, db.SetMediaFileIgnoredParams{MatchIgnored: 1, ID: f.ID}); err != nil {
		t.Fatal(err)
	}
	for _, rematch := range []bool{false, true} {
		if sum, err := e.m.MatchLibrary(e.ctx, e.mov, rematch); err != nil || sum.Matched+sum.Unmatched+sum.Failed != 0 {
			t.Errorf("rematch=%v touched an ignored file: %+v %v", rematch, sum, err)
		}
	}
	// Matching it by hand brings it back.
	if err := e.m.Apply(e.ctx, e.mov, f, 949, nil, nil); err != nil {
		t.Fatal(err)
	}
	if row, _ := e.q.GetMediaFile(e.ctx, f.ID); row.MatchIgnored != 0 || row.MatchStatus != StatusMatched {
		t.Errorf("after manual match: ignored=%d status=%s", row.MatchIgnored, row.MatchStatus)
	}
}

func TestDailyShowMatchesByAirDate(t *testing.T) {
	e := setup(t)
	e.file(e.tv, "/t/The Daily Show/The Daily Show - 2024-10-28 - Guest.mkv", `{"kind":"episode","title":"The Daily Show","airDate":"2024-10-28"}`)
	e.file(e.tv, "/t/The Daily Show/Season 2024/The Daily Show - S2024E01.mkv", `{"kind":"episode","title":"The Daily Show","season":2024,"episodes":[1]}`)
	e.file(e.tv, "/t/The Daily Show/The Daily Show - 2024-10-29.mkv", `{"kind":"episode","title":"The Daily Show","airDate":"2024-10-29"}`)
	sum, err := e.m.MatchLibrary(e.ctx, e.tv, false)
	if err != nil {
		t.Fatal(err)
	}
	if sum.Matched != 2 || sum.Unmatched != 1 {
		t.Fatalf("summary = %+v", sum)
	}
	title := func(path string) string {
		f, _ := e.q.GetMediaFileByPath(e.ctx, path)
		ids, _ := e.q.ListEpisodeIDsForFile(e.ctx, f.ID)
		if len(ids) != 1 {
			return deref(f.MatchError)
		}
		ep, _ := e.q.GetEpisode(e.ctx, ids[0])
		return ep.Title
	}
	if got := title("/t/The Daily Show/The Daily Show - 2024-10-28 - Guest.mkv"); got != "October Guest" {
		t.Errorf("dated file -> %q", got)
	}
	if got := title("/t/The Daily Show/Season 2024/The Daily Show - S2024E01.mkv"); got != "January Guest" {
		t.Errorf("year season file -> %q", got)
	}
	if got := title("/t/The Daily Show/The Daily Show - 2024-10-29.mkv"); !strings.Contains(got, "no episode of \"The Daily Show\" aired on 2024-10-29") {
		t.Errorf("missing date -> %q", got)
	}
	files, _ := e.q.ListFilesToRematch(e.ctx, e.tv.ID)
	lu, err := e.m.LineUp(e.ctx, e.tv, files, 2224)
	if err != nil || lu.Dated != 1 || lu.DatedFound != 1 || lu.Found != 1 {
		t.Errorf("line-up = %+v %v", lu, err)
	}
}

// Downloads usually keep each episode in its own release folder at the top
// of the library; the folder's date must not be taken as the show's year.
func TestDailyShowInReleaseFolders(t *testing.T) {
	e := setup(t)
	rel := "The.Daily.Show.2024.10.28.Guest.1080p.WEB.h264-GRP/The.Daily.Show.2024.10.28.Guest.1080p.WEB.h264-GRP.mkv"
	g, _ := json.Marshal(parser.ParseEpisode(rel))
	f := e.file(e.tv, "/t/"+rel, string(g))
	sum, err := e.m.MatchLibrary(e.ctx, e.tv, false)
	if err != nil || sum.Matched != 1 {
		row, _ := e.q.GetMediaFile(e.ctx, f.ID)
		t.Fatalf("summary = %+v %v: %s", sum, err, deref(row.MatchError))
	}
	ids, _ := e.q.ListEpisodeIDsForFile(e.ctx, f.ID)
	if len(ids) != 1 {
		t.Fatalf("episode ids = %v", ids)
	}
	if ep, _ := e.q.GetEpisode(e.ctx, ids[0]); ep.Title != "October Guest" {
		t.Errorf("linked to %q", ep.Title)
	}
}

// episodeTitle is the title of the one episode a file is linked to, or its
// match error.
func episodeTitle(e env, path string) string {
	f, _ := e.q.GetMediaFileByPath(e.ctx, path)
	ids, _ := e.q.ListEpisodeIDsForFile(e.ctx, f.ID)
	if len(ids) != 1 {
		return deref(f.MatchError)
	}
	ep, _ := e.q.GetEpisode(e.ctx, ids[0])
	return ep.Title
}

// tvFile adds a file to the TV library, parsed from its name.
func tvFile(e env, rel string) string {
	g, _ := json.Marshal(parser.ParseEpisode(rel))
	e.file(e.tv, "/t/"+rel, string(g))
	return "/t/" + rel
}

// Dates in other orders, dates that read both ways, and days with more
// than one episode.
func TestDatedEpisodeChoices(t *testing.T) {
	e := setup(t)
	cases := []struct{ rel, want string }{
		{"The Daily Show/The.Daily.Show.20241028.mkv", "October Guest"},
		{"The Daily Show/The Daily Show - 28.10.2024.mkv", "October Guest"},
		// 5 June or 6 May: only 6 May aired.
		{"The Daily Show/The Daily Show - 05.06.2024.mkv", "Early May Guest"},
		// 4 November or 11 April: both aired, so a person picks.
		{"The Daily Show/The Daily Show - 04.11.2024.mkv", `the date in the name could be 2024-11-04 or 2024-04-11, and "The Daily Show" aired on both`},
		// Two episodes and a special on 4 November.
		{"The Daily Show/The.Daily.Show.2024.11.04.Alpha.Guest.1080p.WEB.mkv", "November 4, 2024 - Alpha Guest"},
		{"The Daily Show/The.Daily.Show.2024.11.04.Part.2.1080p.WEB.mkv", "November 4, 2024 - Bonus Episode"},
		{"The Daily Show/The Daily Show - 2024-11-04.mkv", `2 episodes of "The Daily Show" aired on 2024-11-04; pick the right one`},
		{"The Daily Show/The Daily Show - 2099-01-05.mkv", "2099-01-05 is in the future"},
	}
	for _, c := range cases {
		tvFile(e, c.rel)
	}
	if _, err := e.m.MatchLibrary(e.ctx, e.tv, false); err != nil {
		t.Fatal(err)
	}
	for _, c := range cases {
		if got := episodeTitle(e, "/t/"+c.rel); !strings.HasPrefix(got, c.want) {
			t.Errorf("%s -> %q, want %q", c.rel, got, c.want)
		}
	}
}

// IDs written in folder names match without a title search; IDs TMDB does
// not know, or that name the wrong kind of thing, fall back to the title.
func TestIDsInNames(t *testing.T) {
	e := setup(t)
	cases := []struct{ rel, want string }{
		{"Wrong Name {tmdb-1396}/Season 01/S01E01.mkv", "Pilot"},
		{"Something {imdb-tt0903747}/Season 01/S01E02.mkv", "Cat's in the Bag..."},
		{"Other [tvdbid-81189]/Season 02/S02E01.mkv", "Seven Thirty-Seven"},
		{"Breaking Bad {tmdb-999999}/Season 01/S01E01.mkv", "Pilot"},
	}
	for _, c := range cases {
		tvFile(e, c.rel)
	}
	if sum, err := e.m.MatchLibrary(e.ctx, e.tv, false); err != nil || sum.Matched != len(cases) {
		t.Errorf("summary = %+v %v", sum, err)
	}
	for _, c := range cases {
		if got := episodeTitle(e, "/t/"+c.rel); got != c.want {
			t.Errorf("%s -> %q, want %q", c.rel, got, c.want)
		}
	}

	movies := []struct {
		rel  string
		want int64
	}{
		{"Not The Title (1999) {tmdb-603}/x.mkv", 603},
		{"Heat (1995) {imdb-tt0903747}/Heat.mkv", 949}, // a TV show's ID: matched by title instead
	}
	for _, c := range movies {
		g, _ := json.Marshal(parser.ParseMovie(c.rel))
		e.file(e.mov, "/m/"+c.rel, string(g))
	}
	if _, err := e.m.MatchLibrary(e.ctx, e.mov, false); err != nil {
		t.Fatal(err)
	}
	for _, c := range movies {
		f, _ := e.q.GetMediaFileByPath(e.ctx, "/m/"+c.rel)
		if f.MovieID == nil {
			t.Errorf("%s unmatched: %s", c.rel, deref(f.MatchError))
			continue
		}
		mv, _ := e.q.GetMovie(e.ctx, *f.MovieID)
		if mv.TmdbID != c.want {
			t.Errorf("%s -> TMDB %d, want %d", c.rel, mv.TmdbID, c.want)
		}
	}
}
