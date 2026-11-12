package integrations

import (
	"context"
	"net/http"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/integrations/arrtest"
	"watchmymedia/server/internal/tmdb"
	"watchmymedia/server/internal/tmdb/tmdbtest"
)

// fakeTMDB knows one movie (700001, Quiet Field) and one show (800001,
// Harbor Lights, two seasons, TheTVDB 900001).
func fakeTMDB(t *testing.T) *tmdb.Client {
	f := tmdbtest.New(t)
	f.JSON("/movie/700001", map[string]any{"id": 700001, "title": "Quiet Field", "release_date": "2019-04-02", "poster_path": "/qf.jpg"})
	f.JSON("/tv/800001", map[string]any{"id": 800001, "name": "Harbor Lights", "first_air_date": "2021-01-05", "seasons": []any{
		map[string]any{"season_number": 1, "name": "Season 1", "episode_count": 2, "air_date": "2021-01-05"},
		map[string]any{"season_number": 2, "name": "Season 2", "episode_count": 2, "air_date": "2022-01-05"},
	}})
	f.JSON("/tv/800001/external_ids", map[string]any{"tvdb_id": 900001})
	return f.Client()
}

type requestEnv struct {
	s             *Service
	q             *db.Queries
	radarr        *arrtest.App
	sonarr        *arrtest.App
	admin, member Person
	movies, tv    db.Library
	ctx           context.Context
}

func setupRequests(t *testing.T) requestEnv {
	t.Helper()
	s, q, _ := setup(t)
	ctx := context.Background()
	s.Configure(fakeTMDB(t), events.NewBus())
	moviesDir, tvDir := filepath.Join(t.TempDir(), "Movies"), filepath.Join(t.TempDir(), "TV")
	movies, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "movie", Name: "Movies", Path: moviesDir})
	tv, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: tvDir})
	admin, _ := q.CreateUser(ctx, db.CreateUserParams{Name: "admin", PasswordHash: "x", IsAdmin: 1})
	member, _ := q.CreateUser(ctx, db.CreateUserParams{Name: "sam", PasswordHash: "x"})

	radarr, sonarr := arrtest.New(t, "radarr"), arrtest.New(t, "sonarr")
	for _, a := range []struct {
		app  *arrtest.App
		kind string
		lib  db.Library
		root string
	}{{radarr, "radarr", movies, "/movies"}, {sonarr, "sonarr", tv, "/tv"}} {
		if _, err := s.Create(ctx, Input{Kind: a.kind, Name: new(a.kind), URL: new(a.app.URL), APIKey: new(a.app.Key),
			PathMap: &PathMap{{From: a.root, To: a.lib.Path}},
			Targets: &[]Target{{LibraryID: a.lib.ID, RootFolder: a.root, QualityProfileID: 4}}}); err != nil {
			t.Fatal(err)
		}
	}
	return requestEnv{s: s, q: q, radarr: radarr, sonarr: sonarr, admin: Person{ID: admin.ID, Admin: true}, member: Person{ID: member.ID},
		movies: movies, tv: tv, ctx: ctx}
}

func TestMemberRequestWaitsThenGoesToRadarr(t *testing.T) {
	e := setupRequests(t)
	r, created, err := e.s.Ask(e.ctx, e.member, "movie", 700001, nil)
	if err != nil || !created {
		t.Fatalf("ask: %v %v", created, err)
	}
	if r.Status != "pending" || r.Title != "Quiet Field" || r.Year != 2019 || !r.Mine || r.RequestedBy != "sam" {
		t.Errorf("request = %+v", r)
	}
	if n := len(e.radarr.CallsTo(http.MethodPost, "/movie")); n != 0 {
		t.Fatalf("Radarr was asked before approval (%d)", n)
	}
	open, _ := e.s.Open(e.ctx, e.admin)
	if len(open) != 1 || open[0].Status != "pending" || open[0].Mine {
		t.Fatalf("inbox = %+v", open)
	}
	// Someone else asking for it gets the same request.
	other, _ := e.q.CreateUser(e.ctx, db.CreateUserParams{Name: "alex", PasswordHash: "x"})
	if again, created, err := e.s.Ask(e.ctx, Person{ID: other.ID}, "movie", 700001, nil); err != nil || created || again.ID != r.ID || again.Mine {
		t.Errorf("second ask: %+v %v %v", again, created, err)
	}

	r, err = e.s.Decide(e.ctx, e.admin, r.ID, true, "")
	if err != nil || r.Status != "approved" {
		t.Fatalf("approve: %+v %v", r, err)
	}
	add := e.radarr.CallsTo(http.MethodPost, "/movie")
	if len(add) != 1 {
		t.Fatalf("Radarr adds = %d", len(add))
	}
	b := add[0].Body
	if b["tmdbId"] != float64(700001) || b["rootFolderPath"] != "/movies" || b["qualityProfileId"] != float64(4) || b["monitored"] != true ||
		b["addOptions"].(map[string]any)["searchForMovie"] != true {
		t.Errorf("added = %v", b)
	}

	// Once the library has the movie, the request is done.
	m, _ := e.q.UpsertMovie(e.ctx, db.UpsertMovieParams{LibraryID: e.movies.ID, TmdbID: 700001, Title: "Quiet Field", Genres: "[]", Credits: "[]"})
	f, _ := e.q.UpsertMediaFile(e.ctx, db.UpsertMediaFileParams{LibraryID: e.movies.ID, Path: filepath.Join(e.movies.Path, "Quiet Field (2019)", "q.mkv"),
		Size: 1, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()), Parsed: "{}", LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	if err := e.q.SetMediaFileMatch(e.ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &m.ID, ID: f.ID}); err != nil {
		t.Fatal(err)
	}
	mine, _ := e.s.Mine(e.ctx, e.member)
	if len(mine) != 1 || mine[0].Status != "available" || mine[0].MovieID != m.ID {
		t.Errorf("mine = %+v", mine)
	}
	// What the library has can't be asked for.
	if _, _, err := e.s.Ask(e.ctx, e.member, "movie", 700001, nil); err == nil || !strings.Contains(err.Error(), "in your library") {
		t.Errorf("asking for a movie in the library: %v", err)
	}
}

func TestAdminRequestAddsSeriesWithOnlyItsSeasons(t *testing.T) {
	e := setupRequests(t)
	r, _, err := e.s.Ask(e.ctx, e.admin, "tv", 800001, []int{2})
	if err != nil || r.Status != "approved" {
		t.Fatalf("ask: %+v %v", r, err)
	}
	add := e.sonarr.CallsTo(http.MethodPost, "/series")
	if len(add) != 1 {
		t.Fatalf("Sonarr adds = %d", len(add))
	}
	b := add[0].Body
	if b["tvdbId"] != float64(900001) || b["rootFolderPath"] != "/tv" || b["addOptions"].(map[string]any)["searchForMissingEpisodes"] != true {
		t.Errorf("added = %v", b)
	}
	var monitored []float64
	for _, x := range b["seasons"].([]any) {
		if s := x.(map[string]any); s["monitored"] == true {
			monitored = append(monitored, s["seasonNumber"].(float64))
		}
	}
	if !slices.Equal(monitored, []float64{2}) {
		t.Errorf("monitored seasons = %v, want [2]", monitored)
	}
	if lookups := e.sonarr.CallsTo(http.MethodGet, "/series/lookup"); len(lookups) != 1 || lookups[0].Query != "term=tvdb%3A900001" {
		t.Errorf("lookups = %+v", lookups)
	}
}

func TestRequestForASeriesSonarrHasMonitorsAndSearches(t *testing.T) {
	e := setupRequests(t)
	e.sonarr.Series = []map[string]any{{"id": 7, "title": "Harbor Lights", "path": "/tv/Harbor Lights", "tmdbId": 800001, "tvdbId": 900001,
		"seasons": []any{map[string]any{"seasonNumber": 1, "monitored": true}, map[string]any{"seasonNumber": 2, "monitored": false}}}}
	e.s.refreshAll(e.ctx)
	if _, _, err := e.s.Ask(e.ctx, e.admin, "tv", 800001, []int{2}); err != nil {
		t.Fatal(err)
	}
	if n := len(e.sonarr.CallsTo(http.MethodPost, "/series")); n != 0 {
		t.Errorf("the series was added again (%d)", n)
	}
	put := e.sonarr.CallsTo(http.MethodPut, "/series/7")
	if len(put) != 1 {
		t.Fatalf("series updates = %d", len(put))
	}
	for _, x := range put[0].Body["seasons"].([]any) {
		if s := x.(map[string]any); s["monitored"] != true {
			t.Errorf("season %v left unmonitored", s["seasonNumber"])
		}
	}
	cmds := e.sonarr.CallsTo(http.MethodPost, "/command")
	if len(cmds) != 1 || cmds[0].Body["name"] != "SeasonSearch" || cmds[0].Body["seasonNumber"] != float64(2) {
		t.Errorf("commands = %+v", cmds)
	}
}

func TestRequestFailsWithTheAppsMessageAndCanBeRetried(t *testing.T) {
	e := setupRequests(t)
	e.radarr.Close() // Radarr is down
	r, _, err := e.s.Ask(e.ctx, e.admin, "movie", 700001, nil)
	if err != nil {
		t.Fatal(err)
	}
	if r.Status != "failed" || r.Note == "" {
		t.Fatalf("request = %+v", r)
	}
	if _, err := e.s.Decide(e.ctx, e.admin, r.ID, false, "  Already on the other server "); err != nil {
		t.Fatal(err)
	}
	got, _ := e.s.request(e.ctx, e.member, r.ID)
	if got.Status != "declined" || got.Note != "Already on the other server" {
		t.Errorf("declined = %+v", got)
	}
	if _, err := e.s.Decide(e.ctx, e.admin, r.ID, true, ""); err == nil {
		t.Error("a declined request was approved")
	}
}

func TestAskValidates(t *testing.T) {
	e := setupRequests(t)
	if _, _, err := e.s.Ask(e.ctx, e.member, "tv", 800001, nil); err == nil {
		t.Error("a show request with no seasons was taken")
	}
	if _, _, err := e.s.Ask(e.ctx, e.member, "tv", 800001, []int{9}); err == nil || !strings.Contains(err.Error(), "no season 9") {
		t.Errorf("season 9: %v", err)
	}
	if _, _, err := e.s.Ask(e.ctx, e.member, "movie", 1, nil); err == nil {
		t.Error("an unknown movie was taken")
	}
	r, _, _ := e.s.Ask(e.ctx, e.member, "tv", 800001, []int{1})
	r2, created, _ := e.s.Ask(e.ctx, e.member, "tv", 800001, []int{2})
	if created || r2.ID != r.ID || !slices.Equal(r2.Seasons, []int{1, 2}) {
		t.Errorf("asking again for another season = %+v (created %v)", r2, created)
	}
	if err := e.s.Cancel(e.ctx, e.admin, r.ID); err != nil {
		t.Errorf("an admin cancels a waiting request: %v", err)
	}
	if mine, _ := e.s.Mine(e.ctx, e.member); len(mine) != 0 {
		t.Errorf("a cancelled request is still listed: %+v", mine)
	}
}

// A member whose request an admin approved can't add seasons to it: they
// would go to Sonarr without an admin. They get a new request that waits.
func TestMoreSeasonsAfterApprovalWaitAgain(t *testing.T) {
	e := setupRequests(t)
	r, _, err := e.s.Ask(e.ctx, e.member, "tv", 800001, []int{1})
	if err != nil {
		t.Fatal(err)
	}
	if r, err = e.s.Decide(e.ctx, e.admin, r.ID, true, ""); err != nil || r.Status != "approved" {
		t.Fatalf("approve: %+v %v", r, err)
	}
	adds := len(e.sonarr.CallsTo(http.MethodPost, "/series"))
	more, created, err := e.s.Ask(e.ctx, e.member, "tv", 800001, []int{1, 2})
	if err != nil {
		t.Fatal(err)
	}
	if !created || more.ID == r.ID || more.Status != "pending" || !slices.Equal(more.Seasons, []int{2}) {
		t.Errorf("asking for season 2 after season 1 was approved = %+v (created %v)", more, created)
	}
	if got, _ := e.s.request(e.ctx, e.member, r.ID); !slices.Equal(got.Seasons, []int{1}) {
		t.Errorf("the approved request grew to %v", got.Seasons)
	}
	if n := len(e.sonarr.CallsTo(http.MethodPost, "/series")); n != adds {
		t.Errorf("Sonarr was asked again without an admin (%d adds, want %d)", n, adds)
	}
	// Once an admin approves the new one too, nothing is left waiting.
	if more, err = e.s.Decide(e.ctx, e.admin, more.ID, true, ""); err != nil || more.Status != "approved" {
		t.Errorf("approve season 2: %+v %v", more, err)
	}
}

// An admin needs no approval, so more seasons join their approved request.
func TestAdminsApprovedRequestTakesMoreSeasons(t *testing.T) {
	e := setupRequests(t)
	r, _, _ := e.s.Ask(e.ctx, e.admin, "tv", 800001, []int{1})
	more, created, err := e.s.Ask(e.ctx, e.admin, "tv", 800001, []int{2})
	if err != nil || created || more.ID != r.ID || more.Status != "approved" || !slices.Equal(more.Seasons, []int{1, 2}) {
		t.Errorf("admin asking for season 2 = %+v (created %v) %v", more, created, err)
	}
}

func TestSeriesState(t *testing.T) {
	e := setupRequests(t)
	e.sonarr.Series = []map[string]any{{"id": 7, "path": "/tv/Harbor Lights", "tmdbId": 800001, "monitored": true,
		"seasons": []any{map[string]any{"seasonNumber": 1, "monitored": true}}}}
	e.sonarr.Episodes = []map[string]any{
		{"id": 71, "seriesId": 7, "seasonNumber": 1, "episodeNumber": 1, "monitored": true, "hasFile": true},
		{"id": 72, "seriesId": 7, "seasonNumber": 1, "episodeNumber": 2, "monitored": true},
		{"id": 73, "seriesId": 7, "seasonNumber": 1, "episodeNumber": 3, "monitored": false},
	}
	e.sonarr.Queue = []map[string]any{{"id": 1, "seriesId": 7, "episodeId": 72, "seasonNumber": 1, "size": 1000.0, "sizeleft": 550.0,
		"status": "downloading", "trackedDownloadState": "downloading"}}
	e.s.refreshAll(e.ctx)
	st := e.s.Series(e.ctx, Item{LibraryPath: e.tv.Path, TMDBID: 800001})
	if st == nil {
		t.Fatal("no state for a series Sonarr has")
	}
	if d := st.Episodes[[2]int{1, 2}].Download; d == nil || d.State != "downloading" || d.Progress < 0.44 || d.Progress > 0.46 {
		t.Errorf("E2 download = %+v", d)
	}
	if st.Episodes[[2]int{1, 3}].Monitored || !st.Seasons[1] {
		t.Errorf("state = %+v", st)
	}
	if err := e.s.SearchMissing(e.ctx, Item{LibraryPath: e.tv.Path, TMDBID: 800001}, 1, []int{3}); err != nil {
		t.Fatal(err)
	}
	cmds := e.sonarr.CallsTo(http.MethodPost, "/command")
	if len(cmds) != 1 || cmds[0].Body["name"] != "EpisodeSearch" {
		t.Errorf("commands = %+v", cmds)
	}
	if mon := e.sonarr.CallsTo(http.MethodPut, "/episode/monitor"); len(mon) != 1 || mon[0].Body["monitored"] != true {
		t.Errorf("monitor calls = %+v", mon)
	}
	// A show no Sonarr has has no state.
	if st := e.s.Series(e.ctx, Item{LibraryPath: e.tv.Path, TMDBID: 1}); st != nil {
		t.Errorf("state for an unknown show = %+v", st)
	}
}
