package api

import (
	"fmt"
	"net/http"
	"net/http/cookiejar"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/watchstate"
)

// Home's banner is a new episode for the person looking: from a show they
// watch first, else the newest arrival, never one they have watched or
// started, and never an old episode whose file is merely new.
func TestHomeHeroIsPerPerson(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx := t.Context()
	q := srv.q
	if code := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/users", `{"name":"viewer","password":"password2"}`, nil).StatusCode; code != http.StatusCreated {
		t.Fatalf("create user -> %d", code)
	}
	jar, _ := cookiejar.New(nil)
	viewer := &http.Client{Jar: jar}
	if code := doJSON(t, viewer, http.MethodPost, srv.URL+"/api/auth/login", `{"name":"viewer","password":"password2"}`, nil).StatusCode; code != http.StatusOK {
		t.Fatalf("viewer login -> %d", code)
	}
	ids := map[string]int64{}
	for _, name := range []string{"admin", "viewer"} {
		u, err := q.GetUserByName(ctx, name)
		if err != nil {
			t.Fatal(err)
		}
		ids[name] = u.ID
	}
	now := time.Now().UTC()
	daysAgo := func(n int) *string {
		d := now.AddDate(0, 0, -n).Format(time.DateOnly)
		return &d
	}

	tv, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: "/t"})
	type ep struct {
		show                  string
		season, number, aired int
		added                 time.Duration
	}
	shows := map[string]db.Show{}
	episodes := map[string]db.Episode{}
	files := map[string]int64{}
	for i, e := range []ep{
		{"Lantern Bay", 1, 1, 40, 40 * 24 * time.Hour},
		{"Lantern Bay", 1, 2, 3, 3 * 24 * time.Hour},
		// A season released at once; episode 2's file arrived last.
		{"Copper Sound", 2, 1, 2, 26 * time.Hour},
		{"Copper Sound", 2, 2, 2, 25 * time.Hour},
		// The newest file, for an episode that aired a month ago.
		{"Saltmarsh", 1, 5, 30, time.Hour},
	} {
		s, ok := shows[e.show]
		if !ok {
			s, _ = q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: tv.ID, TmdbID: int64(100 + i), Title: e.show, Genres: "[]", Credits: "[]"})
			shows[e.show] = s
		}
		season, _ := q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: s.ID, Number: int64(e.season), Name: fmt.Sprintf("Season %d", e.season)})
		row, _ := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: season.ID, ShowID: s.ID, Number: int64(e.number), Title: fmt.Sprintf("Episode %d", e.number), AirDate: daysAgo(e.aired)})
		h := int64(1080)
		f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: tv.ID, Path: fmt.Sprintf("/t/%s/S%02dE%02d.mkv", e.show, e.season, e.number), Size: 100,
			Mtime: db.FormatTime(now), AddedAt: db.FormatTime(now.Add(-e.added)), Height: &h, Parsed: "{}", LastSeenAt: db.FormatTime(now), SeenInScan: 1})
		if err != nil {
			t.Fatal(err)
		}
		_ = q.InsertEpisodeFile(ctx, db.InsertEpisodeFileParams{EpisodeID: row.ID, MediaFileID: f.ID})
		_ = q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", ID: f.ID})
		key := fmt.Sprintf("%s %d.%d", e.show, e.season, e.number)
		episodes[key], files[key] = row, f.ID
	}
	watch := watchstate.New(q, nil)
	finish := func(who, key string) {
		t.Helper()
		if err := watch.MarkWatched(ctx, ids[who], "episode", episodes[key].ID); err != nil {
			t.Fatal(err)
		}
	}
	start := func(who, key string) {
		t.Helper()
		p := watchstate.Progress{UserID: ids[who], ItemType: "episode", ItemIDs: []int64{episodes[key].ID}, FileID: files[key], PositionMs: 60000, DurationMs: 2400000}
		if _, err := watch.Report(ctx, p); err != nil {
			t.Fatal(err)
		}
	}

	clients := map[string]*http.Client{"admin": srv.client, "viewer": viewer}
	hero := func(who, want string) {
		t.Helper()
		var home HomeData
		doJSON(t, clients[who], http.MethodGet, srv.URL+"/api/home", "", &home)
		h := home.Hero
		if h == nil || h.Kind != "episode" || h.EpisodeID == nil || *h.EpisodeID != episodes[want].ID {
			t.Fatalf("%s's hero = %+v, want %s", who, h, want)
		}
		if show := want[:strings.LastIndex(want, " ")]; h.Kicker != "New episode · "+show || h.FileID == nil || *h.FileID != files[want] {
			t.Errorf("%s's hero = %+v", who, h)
		}
	}

	// The admin has watched Lantern Bay, so its new episode comes before
	// Copper Sound's newer ones. The viewer watches nothing, so they get the
	// newest arrival's first new episode.
	finish("admin", "Lantern Bay 1.1")
	hero("admin", "Lantern Bay 1.2")
	hero("viewer", "Copper Sound 2.1")

	// A started episode is in Continue watching, so the banner moves on.
	start("viewer", "Copper Sound 2.1")
	hero("viewer", "Copper Sound 2.2")

	// Once the admin has watched it, Lantern Bay has nothing new for them.
	finish("admin", "Lantern Bay 1.2")
	hero("admin", "Copper Sound 2.1")
}

func TestHeroEpisodeOrder(t *testing.T) {
	t.Parallel()
	h1080, h2160 := int64(1080), int64(2160)
	rows := []db.ListNewEpisodeFilesRow{
		{EpisodeID: 1, ShowID: 1, SeasonNumber: 0, EpisodeNumber: 1, AddedAt: "2026-10-30T12:00:00Z"},
		{EpisodeID: 2, ShowID: 1, SeasonNumber: 3, EpisodeNumber: 4, FileID: 20, Height: &h1080, AddedAt: "2026-10-29T12:00:00Z"},
		{EpisodeID: 2, ShowID: 1, SeasonNumber: 3, EpisodeNumber: 4, FileID: 21, Height: &h2160, AddedAt: "2026-10-28T12:00:00Z"},
		{EpisodeID: 3, ShowID: 1, SeasonNumber: 3, EpisodeNumber: 3, AddedAt: "2026-10-27T12:00:00Z", Seen: true},
	}
	// A special waits behind the season, an episode watched is skipped, and
	// of two files the taller plays.
	e, ok := heroEpisode(rows)
	if !ok || e.EpisodeID != 2 || e.FileID != 21 {
		t.Errorf("hero episode = %+v", e)
	}
	if _, ok := heroEpisode(rows[3:]); ok {
		t.Error("a watched episode became the hero")
	}

	// Without a new episode, a movie the person hasn't watched comes first.
	m := pickHero(HomeData{RecentlyAddedMovies: []BrowseItem{
		{Kind: "unmatched", Title: "new.mkv"}, {Kind: "movie", ID: 1, Title: "Seen", Watched: true}, {Kind: "movie", ID: 2, Title: "Unseen"},
	}}, nil)
	if m == nil || m.Kind != "movie" || *m.MovieID != 2 {
		t.Errorf("movie hero = %+v", m)
	}
	m = pickHero(HomeData{RecentlyAddedMovies: []BrowseItem{{Kind: "movie", ID: 1, Title: "Seen", Watched: true}}}, nil)
	if m == nil || *m.MovieID != 1 {
		t.Errorf("all watched: movie hero = %+v", m)
	}
}
