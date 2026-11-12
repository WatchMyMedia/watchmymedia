package enrich

import (
	"context"
	"database/sql"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/tmdb"
	"watchmymedia/server/internal/tmdb/tmdbtest"
)

// The shows, movies and people here are fictional.

var now = time.Date(2026, 11, 2, 12, 0, 0, 0, time.UTC)

type env struct {
	t    *testing.T
	conn *sql.DB
	q    *db.Queries
	fake *tmdbtest.Server
	e    *Enricher
	on   bool
	// at is when the next programme added starts.
	at time.Time
}

func newEnv(t *testing.T) *env {
	t.Helper()
	conn, q := dbtest.Open(t)
	ctx := t.Context()
	if _, err := conn.ExecContext(ctx, `INSERT INTO live_tv_sources (id, device_id, name, address, model, firmware, tuner_count)
		VALUES ('s1', 'D1', 'Tuner', 'http://192.0.2.1', 'HDHR', '20260313', 2)`); err != nil {
		t.Fatal(err)
	}
	if _, err := conn.ExecContext(ctx, `INSERT INTO live_tv_channels (id, source_id, provider_key, number, name, stream_path, major, minor, last_seen_at)
		VALUES ('c1', 's1', '7.1', '7.1', 'WXYZ', '/auto/v7.1', 7, 1, '')`); err != nil {
		t.Fatal(err)
	}
	v := &env{t: t, conn: conn, q: q, fake: tmdbtest.New(t), on: true, at: now.Add(time.Hour)}
	v.e = New(q, v.fake.Client(), Options{
		Log: slog.New(slog.DiscardHandler), On: func(context.Context) bool { return v.on }, Now: func() time.Time { return now },
	})
	return v
}

// prog is a listing as a refresh stores it.
type prog struct {
	id, kind, key, title, episodeTitle, airDate, desc, picture string
	season, episode                                            int64
	categories                                                 string
	ended                                                      bool
}

func ptr(n int64) *int64 {
	if n == 0 {
		return nil
	}
	return &n
}

// add stores p as a refresh would, half an hour after the one before.
func (v *env) add(p prog) {
	v.t.Helper()
	if p.categories == "" {
		p.categories = `["Series"]`
	}
	if p.picture == "" {
		p.picture = "0123456789abcdef01234567"
	}
	start := v.at
	if p.ended {
		start = now.Add(-2 * time.Hour)
	} else {
		v.at = v.at.Add(30 * time.Minute)
	}
	src := ""
	if p.desc != "" {
		src = SourceProvider
	}
	picSrc := ""
	if p.picture != "-" {
		picSrc = SourceProvider
	} else {
		p.picture = ""
	}
	err := v.q.InsertProgramme(v.t.Context(), db.InsertProgrammeParams{
		ID: p.id, ChannelID: "c1", StartAt: start.Unix(), EndAt: start.Add(30 * time.Minute).Unix(), Title: p.title,
		EpisodeTitle: p.episodeTitle, Season: ptr(p.season), Episode: ptr(p.episode), AirDate: p.airDate,
		Categories: p.categories, Kind: p.kind, SeriesKey: p.key, Description: p.desc, DescriptionSource: src,
		Picture: p.picture, PictureSource: picSrc,
	})
	if err != nil {
		v.t.Fatal(err)
	}
}

func (v *env) enrich() {
	v.t.Helper()
	if err := v.e.Enrich(v.t.Context()); err != nil {
		v.t.Fatal(err)
	}
}

func (v *env) get(id string) db.ListEnrichProgrammesRow {
	v.t.Helper()
	rows, err := v.q.ListEnrichProgrammes(v.t.Context())
	if err != nil {
		v.t.Fatal(err)
	}
	for _, r := range rows {
		if r.ID == id {
			return r
		}
	}
	v.t.Fatalf("no programme %s", id)
	return db.ListEnrichProgrammesRow{}
}

func results[T any](rs ...T) map[string]any { return map[string]any{"results": rs} }

func (v *env) shows(rs ...tmdb.TVResult) {
	v.fake.Handle("/search/tv", func(*http.Request) any { return results(rs...) })
}

func (v *env) movies(rs ...tmdb.MovieResult) {
	v.fake.Handle("/search/movie", func(*http.Request) any { return results(rs...) })
}

func (v *env) season(tv, n int, eps ...tmdb.Episode) {
	v.fake.JSON(fmt.Sprintf("/tv/%d/season/%d", tv, n), tmdb.Season{SeasonNumber: n, Episodes: eps})
}

// wantMatch checks a programme's TMDB link and still.
func wantMatch(t *testing.T, p db.ListEnrichProgrammesRow, kind string, id int64, still string) {
	t.Helper()
	got := int64(0)
	if p.TmdbID != nil {
		got = *p.TmdbID
	}
	if p.TmdbKind != kind || got != id || p.Still != still {
		t.Errorf("%s: matched %q %d still %q; want %q %d still %q", p.ID, p.TmdbKind, got, p.Still, kind, id, still)
	}
}

func TestMovieSameTitleDifferentYear(t *testing.T) {
	v := newEnv(t)
	v.movies(
		tmdb.MovieResult{ID: 11, Title: "Lantern Bay", ReleaseDate: "1998-05-01", BackdropPath: "/bay98.jpg", Overview: "A keeper's last winter."},
		tmdb.MovieResult{ID: 12, Title: "Lantern Bay", ReleaseDate: "2019-09-13", BackdropPath: "/bay19.jpg", Overview: "A remake set in a storm."},
	)
	v.add(prog{id: "m19", kind: "movie", key: "MV001", title: "Lantern Bay", airDate: "2019", desc: "Two keepers. (3.0/4.0)"})
	v.add(prog{id: "m05", kind: "movie", key: "MV002", title: "Lantern Bay", airDate: "2005"})
	v.add(prog{id: "mnone", kind: "movie", key: "MV003", title: "Lantern Bay", desc: "No year listed."})
	v.add(prog{id: "m98", kind: "movie", key: "MV004", title: "Lantern Bay", airDate: "1998", picture: "-"})
	v.enrich()

	m19 := v.get("m19")
	wantMatch(t, m19, KindMovie, 12, "/api/images/w780/bay19.jpg")
	if m19.Description != "Two keepers. (3.0/4.0)" || m19.DescriptionSource != SourceProvider {
		t.Errorf("a movie's own description became %q (%s)", m19.Description, m19.DescriptionSource)
	}
	wantMatch(t, v.get("m05"), "", 0, "")
	wantMatch(t, v.get("mnone"), "", 0, "")
	m98 := v.get("m98")
	wantMatch(t, m98, KindMovie, 11, "/api/images/w780/bay98.jpg")
	if m98.Picture != "/api/images/w780/bay98.jpg" || m98.PictureSource != SourceTMDB {
		t.Errorf("a movie without a picture got %q (%s)", m98.Picture, m98.PictureSource)
	}
	if m98.Description != "A keeper's last winter." || m98.DescriptionSource != SourceTMDB || m98.FeedDescription != "" {
		t.Errorf("a movie without a description got %q (%s), feed %q", m98.Description, m98.DescriptionSource, m98.FeedDescription)
	}
	// The listing without a year made no call.
	if n := v.fake.Total(); n != 3 {
		t.Errorf("%d TMDB calls; want 3", n)
	}
}

func TestTwoShowsOfOneName(t *testing.T) {
	v := newEnv(t)
	v.shows(
		tmdb.TVResult{ID: 21, Name: "Copper Kettle", FirstAirDate: "1987-09-20", BackdropPath: "/kettle87.jpg"},
		tmdb.TVResult{ID: 22, Name: "The Copper Kettle", FirstAirDate: "2021-01-08", BackdropPath: "/kettle21.jpg"},
		tmdb.TVResult{ID: 23, Name: "Copper Kettle Kids", FirstAirDate: "1990-01-01"},
	)
	// An episode from 1988 can only be the first show's.
	v.add(prog{id: "old", kind: "episode", key: "C100", title: "Copper Kettle", airDate: "1988-02-11", picture: "-"})
	// One from 2022 could be either: no match.
	v.add(prog{id: "new", kind: "episode", key: "C200", title: "Copper Kettle", airDate: "2022-03-01"})
	// Without an air date, either: no match.
	v.add(prog{id: "undated", kind: "episode", key: "C300", title: "Copper Kettle"})
	v.enrich()

	old := v.get("old")
	wantMatch(t, old, KindTV, 21, "")
	if old.Picture != "/api/images/w780/kettle87.jpg" || old.PictureSource != SourceTMDB {
		t.Errorf("an episode without a picture got %q (%s); want the series backdrop", old.Picture, old.PictureSource)
	}
	wantMatch(t, v.get("new"), "", 0, "")
	wantMatch(t, v.get("undated"), "", 0, "")
}

func TestEpisodeChecks(t *testing.T) {
	v := newEnv(t)
	v.shows(tmdb.TVResult{ID: 31, Name: "Orchard Lane", FirstAirDate: "2024-01-10", BackdropPath: "/orchard.jpg"})
	v.season(31, 2,
		tmdb.Episode{EpisodeNumber: 4, Name: "The Frost", AirDate: "2025-02-03", StillPath: "/frost.jpg", Overview: "The first frost."},
		tmdb.Episode{EpisodeNumber: 5, Name: "Pruning", AirDate: "2025-02-10", StillPath: "/pruning.jpg", Overview: "Ada prunes."},
		tmdb.Episode{EpisodeNumber: 6, Name: "Cider", AirDate: "2025-02-17", StillPath: "/cider.jpg", Overview: "Cider day."},
	)
	// The date agrees and the name doesn't: matched.
	v.add(prog{id: "date", kind: "episode", key: "C31", title: "Orchard Lane", season: 2, episode: 4, episodeTitle: "Frost", airDate: "2025-02-03", desc: "The first frost."})
	// A day apart, as a late episode may be: matched.
	v.add(prog{id: "late", kind: "episode", key: "C31", title: "Orchard Lane", season: 2, episode: 5, airDate: "2025-02-11", desc: "Ada prunes the trees."})
	// The name agrees and the date doesn't: matched.
	v.add(prog{id: "name", kind: "episode", key: "C31", title: "Orchard Lane", season: 2, episode: 6, episodeTitle: "Cider!", airDate: "2025-03-01", desc: "Pressing day."})
	// Both disagree: the series, but no still.
	v.add(prog{id: "neither", kind: "episode", key: "C31", title: "Orchard Lane", season: 2, episode: 6, episodeTitle: "Wassail", airDate: "2025-03-01", desc: "Singing."})
	// TMDB has no such episode.
	v.add(prog{id: "missing", kind: "episode", key: "C31", title: "Orchard Lane", season: 2, episode: 9, airDate: "2025-03-20", desc: "Late."})
	// No season and episode: the series alone.
	v.add(prog{id: "unnumbered", kind: "episode", key: "C31", title: "Orchard Lane", episodeTitle: "The Frost", airDate: "2025-02-03", desc: "Frost again."})
	v.enrich()

	wantMatch(t, v.get("date"), KindTV, 31, "/api/images/w780/frost.jpg")
	wantMatch(t, v.get("late"), KindTV, 31, "/api/images/w780/pruning.jpg")
	wantMatch(t, v.get("name"), KindTV, 31, "/api/images/w780/cider.jpg")
	neither := v.get("neither")
	wantMatch(t, neither, KindTV, 31, "")
	if neither.Picture != "0123456789abcdef01234567" || neither.PictureSource != SourceProvider || neither.Description != "Singing." {
		t.Errorf("an unmatched episode lost the feed's picture or description: %+v", neither)
	}
	wantMatch(t, v.get("missing"), KindTV, 31, "")
	wantMatch(t, v.get("unnumbered"), KindTV, 31, "")
	// Each episode's own description stays.
	if d := v.get("late"); d.Description != "Ada prunes the trees." || d.DescriptionSource != SourceProvider {
		t.Errorf("an episode's own description became %q (%s)", d.Description, d.DescriptionSource)
	}
	// One search and one season.
	if n := v.fake.Total(); n != 2 {
		t.Errorf("%d TMDB calls; want 2", n)
	}
}

func TestDailyTalkShow(t *testing.T) {
	v := newEnv(t)
	v.shows(tmdb.TVResult{ID: 41, Name: "Late Hour with Rosa Vale", FirstAirDate: "2015-09-08"})
	var eps []tmdb.Episode
	for i := range 5 {
		d := time.Date(2026, 10, 26+i, 0, 0, 0, 0, time.UTC).Format(time.DateOnly)
		eps = append(eps, tmdb.Episode{EpisodeNumber: 40 + i, Name: "Guest " + strconv.Itoa(i), AirDate: d, StillPath: fmt.Sprintf("/rosa%d.jpg", 40+i)})
	}
	v.season(41, 12, eps...)
	// Numbered and dated as TMDB has it.
	v.add(prog{id: "right", kind: "episode", key: "C41", title: "Late Hour with Rosa Vale", season: 12, episode: 42, airDate: "2026-10-28"})
	// Numbered one off: TMDB's episode 43 aired the day after, and the
	// day's own episode is closer. No still.
	v.add(prog{id: "off", kind: "episode", key: "C41", title: "Late Hour with Rosa Vale", season: 12, episode: 43, airDate: "2026-10-28"})
	// Dated a day late with no episode on that day: still TMDB's 44.
	v.add(prog{id: "last", kind: "episode", key: "C41", title: "Late Hour with Rosa Vale", season: 12, episode: 44, airDate: "2026-10-31"})
	v.enrich()

	wantMatch(t, v.get("right"), KindTV, 41, "/api/images/w780/rosa42.jpg")
	wantMatch(t, v.get("off"), KindTV, 41, "")
	wantMatch(t, v.get("last"), KindTV, 41, "/api/images/w780/rosa44.jpg")
}

func TestYearNumberedSeason(t *testing.T) {
	v := newEnv(t)
	v.fake.Handle("/search/tv", func(r *http.Request) any {
		switch r.URL.Query().Get("query") {
		case "Market Morning":
			return results(tmdb.TVResult{ID: 51, Name: "Market Morning", FirstAirDate: "2003-01-06"})
		case "Garden Hour":
			return results(tmdb.TVResult{ID: 52, Name: "Garden Hour", FirstAirDate: "2010-04-01"})
		}
		return results[tmdb.TVResult]()
	})
	// TMDB numbers this show's seasons by year too.
	v.season(51, 2026, tmdb.Episode{EpisodeNumber: 210, Name: "October 30", AirDate: "2026-10-30", StillPath: "/market.jpg"})
	v.add(prog{id: "year", kind: "episode", key: "C51", title: "Market Morning", season: 2026, episode: 210, airDate: "2026-10-30"})
	// This one's TMDB seasons are numbered 1, 2, 3…: no season 2026.
	v.add(prog{id: "nosuch", kind: "episode", key: "C52", title: "Garden Hour", season: 2026, episode: 40, airDate: "2026-10-29"})
	v.enrich()

	wantMatch(t, v.get("year"), KindTV, 51, "/api/images/w780/market.jpg")
	wantMatch(t, v.get("nosuch"), KindTV, 52, "")
	if n := v.fake.Count("/tv/52/season/2026"); n != 1 {
		t.Fatalf("asked for the missing season %d times; want 1", n)
	}
	// Within a day the missing season isn't asked for again; after one it is.
	v.enrich()
	if n := v.fake.Count("/tv/52/season/2026"); n != 1 {
		t.Errorf("asked for the missing season again within a day (%d)", n)
	}
	later := now.Add(25 * time.Hour)
	v.e.now = func() time.Time { return later }
	v.at = later.Add(time.Hour)
	v.add(prog{id: "nosuch2", kind: "episode", key: "C52", title: "Garden Hour", season: 2026, episode: 41, airDate: "2026-10-30"})
	v.enrich()
	if n := v.fake.Count("/tv/52/season/2026"); n != 2 {
		t.Errorf("asked for the missing season %d times after a day; want 2", n)
	}
}

func TestRepeatedDescriptions(t *testing.T) {
	v := newEnv(t)
	v.shows(tmdb.TVResult{ID: 61, Name: "Tidewater", FirstAirDate: "2019-03-01"})
	v.season(61, 1,
		tmdb.Episode{EpisodeNumber: 1, AirDate: "2019-03-01", Overview: "Mara arrives at the harbour.", StillPath: "/t1.jpg"},
		tmdb.Episode{EpisodeNumber: 2, AirDate: "2019-03-08", Overview: "The tide turns.", StillPath: "/t2.jpg"},
		tmdb.Episode{EpisodeNumber: 3, AirDate: "2019-03-15", StillPath: "/t3.jpg"},
	)
	series := "A harbour town and its secrets."
	v.add(prog{id: "e1", kind: "episode", key: "C61", title: "Tidewater", season: 1, episode: 1, airDate: "2019-03-01", desc: series})
	v.add(prog{id: "e2", kind: "episode", key: "C61", title: "Tidewater", season: 1, episode: 2, airDate: "2019-03-08", desc: series})
	// TMDB has no overview for this one: the feed's stays.
	v.add(prog{id: "e3", kind: "episode", key: "C61", title: "Tidewater", season: 1, episode: 3, airDate: "2019-03-15", desc: series})
	// Two airings of one episode share theirs: not repeated.
	v.add(prog{id: "e2b", kind: "episode", key: "C62", title: "Tidewater", season: 1, episode: 2, airDate: "2019-03-08", desc: "Its own."})
	v.add(prog{id: "e2c", kind: "episode", key: "C62", title: "Tidewater", season: 1, episode: 2, airDate: "2019-03-08", desc: "Its own."})
	v.enrich()

	for id, want := range map[string]string{"e1": "Mara arrives at the harbour.", "e2": "The tide turns."} {
		p := v.get(id)
		if p.Description != want || p.DescriptionSource != SourceTMDB || p.FeedDescription != series {
			t.Errorf("%s: description %q (%s), feed %q; want TMDB's", id, p.Description, p.DescriptionSource, p.FeedDescription)
		}
	}
	if p := v.get("e3"); p.Description != series || p.DescriptionSource != SourceProvider {
		t.Errorf("e3: description %q (%s); want the feed's", p.Description, p.DescriptionSource)
	}
	for _, id := range []string{"e2b", "e2c"} {
		if p := v.get(id); p.Description != "Its own." || p.DescriptionSource != SourceProvider {
			t.Errorf("%s: description %q (%s); want the feed's", id, p.Description, p.DescriptionSource)
		}
	}
}

func TestNewsSportsAndShowsMakeNoCalls(t *testing.T) {
	v := newEnv(t)
	v.fake.Fallback(func(*http.Request) any { return results[tmdb.TVResult]() })
	v.add(prog{id: "news", kind: "episode", key: "C71", title: "Channel 7 News", categories: `["News"]`})
	v.add(prog{id: "ball", kind: "episode", key: "C72", title: "College Football", categories: `["Sports event","Football"]`})
	v.add(prog{id: "talk", kind: "episode", key: "C73", title: "The Locker Room", categories: `["Sports talk"]`})
	v.add(prog{id: "weather", kind: "episode", key: "C74", title: "Forecast", categories: `["Weather"]`})
	v.add(prog{id: "shop", kind: "episode", key: "C75", title: "Gem Hour", categories: `["Shopping"]`})
	v.add(prog{id: "show", kind: "show", key: "C76", title: "Morning Service"})
	v.add(prog{id: "unkeyed", kind: "episode", title: "No Series Id"})
	// One programme in a sport keeps its series from TMDB.
	v.add(prog{id: "mixed1", kind: "episode", key: "C77", title: "Pit Lane"})
	v.add(prog{id: "mixed2", kind: "episode", key: "C77", title: "Pit Lane", categories: `["Auto racing"]`})
	v.enrich()
	if n := v.fake.Total(); n != 0 {
		t.Errorf("%d TMDB calls; want none", n)
	}
}

func TestSwitchOffAndNoKey(t *testing.T) {
	v := newEnv(t)
	v.movies(tmdb.MovieResult{ID: 81, Title: "Paper Moons", ReleaseDate: "2011-06-01", BackdropPath: "/moons.jpg", Overview: "Two forgers."})
	v.add(prog{id: "m", kind: "movie", key: "MV081", title: "Paper Moons", airDate: "2011", picture: "-"})

	v.on = false
	v.enrich()
	if n := v.fake.Total(); n != 0 {
		t.Errorf("the switch off: %d TMDB calls", n)
	}
	unkeyed := New(v.q, tmdb.New("", v.fake.URL), Options{Log: slog.New(slog.DiscardHandler), Now: func() time.Time { return now }})
	if err := unkeyed.Enrich(t.Context()); err != nil {
		t.Fatal(err)
	}
	if n := v.fake.Total(); n != 0 {
		t.Errorf("no key: %d TMDB calls", n)
	}
	wantMatch(t, v.get("m"), "", 0, "")

	// On, then off again: TMDB's picture and description go, and the
	// feed's come back.
	v.on = true
	v.enrich()
	if p := v.get("m"); p.DescriptionSource != SourceTMDB || p.PictureSource != SourceTMDB {
		t.Fatalf("not matched with the switch on: %+v", p)
	}
	v.on = false
	v.enrich()
	p := v.get("m")
	wantMatch(t, p, "", 0, "")
	if p.Description != "" || p.DescriptionSource != "" || p.Picture != "" || p.PictureSource != "" || p.FeedDescription != "" {
		t.Errorf("the switch off left TMDB's in: %+v", p)
	}
}

func TestSwitchOffRestoresTheFeedsDescription(t *testing.T) {
	v := newEnv(t)
	v.shows(tmdb.TVResult{ID: 91, Name: "Signal Hill", FirstAirDate: "2020-01-01"})
	v.season(91, 1,
		tmdb.Episode{EpisodeNumber: 1, AirDate: "2020-01-01", Overview: "One."},
		tmdb.Episode{EpisodeNumber: 2, AirDate: "2020-01-08", Overview: "Two."},
	)
	v.add(prog{id: "a", kind: "episode", key: "C91", title: "Signal Hill", season: 1, episode: 1, airDate: "2020-01-01", desc: "A radio mast."})
	v.add(prog{id: "b", kind: "episode", key: "C91", title: "Signal Hill", season: 1, episode: 2, airDate: "2020-01-08", desc: "A radio mast."})
	v.enrich()
	if p := v.get("a"); p.Description != "One." {
		t.Fatalf("not matched: %+v", p)
	}
	v.on = false
	v.enrich()
	for _, id := range []string{"a", "b"} {
		if p := v.get(id); p.Description != "A radio mast." || p.DescriptionSource != SourceProvider || p.FeedDescription != "" {
			t.Errorf("%s: %q (%s), feed %q; want the feed's back", id, p.Description, p.DescriptionSource, p.FeedDescription)
		}
	}
}

func TestRerunReusesTheCache(t *testing.T) {
	v := newEnv(t)
	v.shows(tmdb.TVResult{ID: 101, Name: "Ferryman", FirstAirDate: "2018-05-05", BackdropPath: "/ferry.jpg"})
	v.season(101, 3, tmdb.Episode{EpisodeNumber: 7, Name: "Crossing", AirDate: "2020-06-01", StillPath: "/crossing.jpg", Overview: "Fog."})
	v.movies()
	listings := func() {
		v.at = now.Add(time.Hour)
		v.add(prog{id: "f", kind: "episode", key: "C101", title: "Ferryman", season: 3, episode: 7, airDate: "2020-06-01"})
		v.add(prog{id: "x", kind: "movie", key: "MV102", title: "Unknown Shore", airDate: "1977"})
	}
	listings()
	v.enrich()
	first := v.fake.Total()
	if first != 3 {
		t.Fatalf("%d TMDB calls; want 3", first)
	}
	// The next refresh replaces the programmes with the feed's.
	listings()
	if p := v.get("f"); p.Still != "" || p.Description != "" {
		t.Fatalf("the refresh kept TMDB's: %+v", p)
	}
	v.enrich()
	if n := v.fake.Total(); n != first {
		t.Errorf("the rerun made %d more TMDB calls; want none", n-first)
	}
	p := v.get("f")
	wantMatch(t, p, KindTV, 101, "/api/images/w780/crossing.jpg")
	if p.Description != "Fog." || p.DescriptionSource != SourceTMDB {
		t.Errorf("the rerun lost the overview: %q (%s)", p.Description, p.DescriptionSource)
	}
	wantMatch(t, v.get("x"), "", 0, "")

	// After 30 days each is asked about again.
	later := now.Add(KeepFor + time.Hour)
	v.e.now = func() time.Time { return later }
	v.at = later.Add(time.Hour)
	v.add(prog{id: "f2", kind: "episode", key: "C101", title: "Ferryman", season: 3, episode: 7, airDate: "2020-06-01"})
	v.add(prog{id: "x2", kind: "movie", key: "MV102", title: "Unknown Shore", airDate: "1977"})
	v.enrich()
	if n := v.fake.Total(); n != 2*first {
		t.Errorf("%d TMDB calls after 30 days; want %d", n, 2*first)
	}
}

func TestCallBudget(t *testing.T) {
	v := newEnv(t)
	v.shows()
	for i := range MaxCalls + 20 {
		v.add(prog{id: fmt.Sprint("p", i), kind: "episode", key: fmt.Sprint("C", 1000+i), title: fmt.Sprint("Show ", i)})
	}
	v.enrich()
	if n := v.fake.Total(); n != MaxCalls {
		t.Fatalf("%d TMDB calls; want %d", n, MaxCalls)
	}
	// The next run asks about the rest, soonest first.
	v.enrich()
	if n := v.fake.Total(); n != MaxCalls+20 {
		t.Errorf("%d TMDB calls after the second run; want %d", n, MaxCalls+20)
	}
}

func TestTMDBFailingStopsLookups(t *testing.T) {
	v := newEnv(t)
	broken := true
	v.fake.Handle("/search/tv", func(*http.Request) any {
		if broken {
			return tmdbtest.Reply{Status: http.StatusInternalServerError, Body: `{}`}
		}
		return results(tmdb.TVResult{ID: 111, Name: "Quarry Road", FirstAirDate: "2001-01-01"})
	})
	v.add(prog{id: "q1", kind: "episode", key: "C111", title: "Quarry Road"})
	v.add(prog{id: "q2", kind: "episode", key: "C112", title: "Quarry Road"})
	v.enrich()
	// One lookup, tried three times by the client, then no more.
	if n := v.fake.Total(); n != 3 {
		t.Errorf("%d requests while TMDB fails; want 3", n)
	}
	// A failure isn't kept as "no match".
	broken = false
	v.enrich()
	wantMatch(t, v.get("q1"), KindTV, 111, "")
}

func TestEndedProgrammesKeepTheirMatchWithoutCalls(t *testing.T) {
	v := newEnv(t)
	v.shows(tmdb.TVResult{ID: 121, Name: "Bellwether", FirstAirDate: "2016-01-01"})
	v.season(121, 1, tmdb.Episode{EpisodeNumber: 2, AirDate: "2016-01-08", StillPath: "/bell.jpg"})
	v.add(prog{id: "on", kind: "episode", key: "C121", title: "Bellwether", season: 1, episode: 2, airDate: "2016-01-08"})
	v.add(prog{id: "gone", kind: "episode", key: "C121", title: "Bellwether", season: 1, episode: 2, airDate: "2016-01-08", ended: true})
	// A series with nothing still to come isn't looked up.
	v.add(prog{id: "past", kind: "episode", key: "C122", title: "Old Mill", ended: true})
	v.enrich()
	wantMatch(t, v.get("gone"), KindTV, 121, "/api/images/w780/bell.jpg")
	if n := v.fake.Count("/search/tv?Old Mill&"); n != 0 {
		t.Errorf("looked up a series that has ended %d times", n)
	}
}
