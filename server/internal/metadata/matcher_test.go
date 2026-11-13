package metadata

import (
	"context"
	"database/sql"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/tmdb"
	"watchmymedia/server/internal/tmdb/tmdbtest"
)

// fakeTMDB answers the handful of requests the matcher makes.
func fakeTMDB(t *testing.T) *tmdbtest.Server {
	t.Helper()
	f := tmdbtest.New(t)
	f.Handle("/search/movie", func(r *http.Request) any {
		q := strings.ToLower(r.URL.Query().Get("query"))
		switch {
		case strings.Contains(q, "matrix"):
			return map[string]any{"results": []any{
				map[string]any{"id": 603, "title": "The Matrix", "release_date": "1999-03-30", "popularity": 80, "poster_path": "/matrix.jpg"},
				map[string]any{"id": 604, "title": "The Matrix Reloaded", "release_date": "2003-05-15", "popularity": 60},
			}}
		case strings.Contains(q, "heat"):
			return map[string]any{"results": []any{
				map[string]any{"id": 949, "title": "Heat", "release_date": "1995-12-15", "popularity": 50},
				map[string]any{"id": 950, "title": "Heat", "release_date": "1986-12-05", "popularity": 5},
			}}
		case strings.Contains(q, "ambiguous"):
			return map[string]any{"results": []any{
				map[string]any{"id": 1, "title": "Something Else Entirely", "release_date": "2010-01-01", "popularity": 1},
			}}
		}
		return map[string]any{"results": []any{}}
	})
	f.JSON("/movie/603", map[string]any{"id": 603, "imdb_id": "tt0133093", "title": "The Matrix", "original_title": "The Matrix", "release_date": "1999-03-30",
		"overview": "A hacker learns the truth.", "tagline": "Welcome to the Real World", "runtime": 136,
		"genres":       []any{map[string]any{"id": 28, "name": "Action"}, map[string]any{"id": 878, "name": "Science Fiction"}},
		"vote_average": 8.2, "poster_path": "/matrix.jpg", "backdrop_path": "/matrix-bd.jpg",
		"release_dates": matrixReleases,
		"videos": map[string]any{"results": []any{
			map[string]any{"key": "teaserKey01", "name": "Teaser", "site": "YouTube", "type": "Teaser", "official": true, "iso_639_1": "en", "size": 1080},
			map[string]any{"key": "makingOf001", "name": "Making of", "site": "YouTube", "type": "Featurette", "official": true, "iso_639_1": "en", "size": 1080},
			map[string]any{"key": "trailerKey1", "name": "Official Trailer", "site": "YouTube", "type": "Trailer", "official": true, "iso_639_1": "en", "size": 1080},
		}}})
	f.JSON("/movie/603/release_dates", matrixReleases)
	f.JSON("/movie/949", map[string]any{"id": 949, "title": "Heat", "release_date": "1995-12-15", "runtime": 170, "genres": []any{}})
	f.Handle("/search/tv", func(r *http.Request) any {
		q := strings.ToLower(r.URL.Query().Get("query"))
		switch {
		case strings.Contains(q, "daily show"):
			return map[string]any{"results": []any{
				map[string]any{"id": 2224, "name": "The Daily Show", "first_air_date": "1996-07-22", "popularity": 50, "origin_country": []string{"US"}},
			}}
		case strings.Contains(q, "breaking"):
			return map[string]any{"results": []any{
				map[string]any{"id": 1396, "name": "Breaking Bad", "first_air_date": "2008-01-20", "popularity": 300, "origin_country": []string{"US"}},
			}}
		}
		return map[string]any{"results": []any{}}
	})
	f.JSON("/tv/1396", map[string]any{"id": 1396, "name": "Breaking Bad", "first_air_date": "2008-01-20", "overview": "Chemistry teacher.", "status": "Ended",
		"genres": []any{map[string]any{"id": 18, "name": "Drama"}}, "vote_average": 8.9, "poster_path": "/bb.jpg",
		"content_ratings": breakingBadRatings,
		"seasons": []any{
			map[string]any{"season_number": 1, "name": "Season 1", "episode_count": 7, "air_date": "2008-01-20"},
			map[string]any{"season_number": 2, "name": "Season 2", "episode_count": 13},
		}})
	f.JSON("/tv/1396/content_ratings", breakingBadRatings)
	f.JSON("/tv/1396/season/2", map[string]any{"id": 3573, "season_number": 2, "name": "Season 2", "episodes": []any{
		map[string]any{"id": 62092, "episode_number": 1, "season_number": 2, "name": "Seven Thirty-Seven", "air_date": "2009-03-08"},
	}})
	f.JSON("/tv/1396/season/1", map[string]any{"id": 3572, "season_number": 1, "name": "Season 1", "episodes": []any{
		map[string]any{"id": 62085, "episode_number": 1, "season_number": 1, "name": "Pilot", "air_date": "2008-01-20", "still_path": "/p.jpg", "runtime": 58},
		map[string]any{"id": 62086, "episode_number": 2, "season_number": 1, "name": "Cat's in the Bag...", "runtime": 48},
	}})
	// A same-name show with a one-episode season 1.
	f.JSON("/tv/1397", map[string]any{"id": 1397, "name": "Breaking Bad", "first_air_date": "2020-01-01", "genres": []any{},
		"seasons": []any{map[string]any{"season_number": 1, "name": "Season 1", "episode_count": 1}}})
	f.JSON("/tv/1397/season/1", map[string]any{"id": 9001, "season_number": 1, "name": "Season 1", "episodes": []any{
		map[string]any{"id": 9002, "episode_number": 1, "season_number": 1, "name": "First"},
	}})
	// Breaking Bad's IMDb and TheTVDB IDs.
	breakingBad := map[string]any{"movie_results": []any{}, "tv_results": []any{
		map[string]any{"id": 1396, "name": "Breaking Bad", "first_air_date": "2008-01-20", "origin_country": []string{"US"}},
	}}
	f.JSON("/find/tt0903747", breakingBad)
	f.JSON("/find/81189", breakingBad)
	f.JSON("/find/tt0133093", map[string]any{"tv_results": []any{}, "movie_results": []any{ // The Matrix
		map[string]any{"id": 603, "title": "The Matrix", "release_date": "1999-03-30"},
	}})
	// A daily show: one season per year, episodes found by air date.
	f.JSON("/tv/2224", map[string]any{"id": 2224, "name": "The Daily Show", "first_air_date": "1996-07-22", "status": "Returning Series", "genres": []any{},
		"seasons": []any{
			map[string]any{"season_number": 0, "name": "Specials", "episode_count": 1, "air_date": "2024-11-04"},
			map[string]any{"season_number": 29, "name": "Season 29", "episode_count": 6, "air_date": "2024-01-08"},
		}})
	f.JSON("/tv/2224/season/29", map[string]any{"id": 22429, "season_number": 29, "name": "Season 29", "episodes": []any{
		map[string]any{"id": 1, "episode_number": 1, "season_number": 29, "name": "January Guest", "air_date": "2024-01-08"},
		map[string]any{"id": 2, "episode_number": 2, "season_number": 29, "name": "October Guest", "air_date": "2024-10-28"},
		map[string]any{"id": 3, "episode_number": 3, "season_number": 29, "name": "April Guest", "air_date": "2024-04-11"},
		map[string]any{"id": 4, "episode_number": 4, "season_number": 29, "name": "Early May Guest", "air_date": "2024-05-06"},
		// Two the same day, and a special that day too.
		map[string]any{"id": 5, "episode_number": 5, "season_number": 29, "name": "November 4, 2024 - Alpha Guest", "air_date": "2024-11-04"},
		map[string]any{"id": 6, "episode_number": 6, "season_number": 29, "name": "November 4, 2024 - Bonus Episode", "air_date": "2024-11-04"},
	}})
	f.JSON("/tv/2224/season/0", map[string]any{"id": 22400, "season_number": 0, "name": "Specials", "episodes": []any{
		map[string]any{"id": 7, "episode_number": 1, "season_number": 0, "name": "Election Night Special", "air_date": "2024-11-04"},
	}})
	return f
}

var (
	matrixReleases = map[string]any{"results": []any{
		map[string]any{"iso_3166_1": "US", "release_dates": []any{map[string]any{"certification": "R", "type": 3}}},
	}}
	breakingBadRatings = map[string]any{"results": []any{map[string]any{"iso_3166_1": "US", "rating": "TV-MA"}}}
)

type env struct {
	m    *Matcher
	q    *db.Queries
	conn *sql.DB
	bus  *events.Bus
	mov  db.Library
	tv   db.Library
	ctx  context.Context
	file func(lib db.Library, path, parsed string) db.MediaFile
}

func setup(t *testing.T) env {
	t.Helper()
	return setupWith(t, fakeTMDB(t).Client())
}

// setupWith is setup with another TMDB client.
func setupWith(t *testing.T, client *tmdb.Client) env {
	t.Helper()
	conn := dbtest.OpenPath(t, filepath.Join(t.TempDir(), "t.db"))
	ctx := context.Background()
	q := db.New(conn)
	mov, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "movie", Name: "Movies", Path: "/m"})
	tv, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: "/t"})
	bus := events.NewBus()
	m := New(q, client, bus, slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelError})), Options{Workers: 2})
	file := func(lib db.Library, path, parsed string) db.MediaFile {
		f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: lib.ID, Path: path, Size: 1, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()),
			Parsed: parsed, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
		if err != nil {
			t.Fatal(err)
		}
		return f
	}
	return env{m: m, q: q, conn: conn, bus: bus, mov: mov, tv: tv, ctx: ctx, file: file}
}

func TestMatchMovies(t *testing.T) {
	e := setup(t)
	e.file(e.mov, "/m/The Matrix (1999).mkv", `{"kind":"movie","title":"The Matrix","year":1999}`)
	e.file(e.mov, "/m/The.Matrix.1999.1080p.mkv", `{"kind":"movie","title":"The Matrix","year":1999}`) // second version
	e.file(e.mov, "/m/Heat.mkv", `{"kind":"movie","title":"Heat"}`)
	e.file(e.mov, "/m/Ambiguous Title (2020).mkv", `{"kind":"movie","title":"Ambiguous Title","year":2020}`)
	e.file(e.mov, "/m/Unknown Thing (2001).mkv", `{"kind":"movie","title":"Unknown Thing","year":2001}`)

	sum, err := e.m.MatchLibrary(e.ctx, e.mov, false)
	if err != nil {
		t.Fatal(err)
	}
	if sum.Matched != 3 || sum.Unmatched != 2 || sum.Failed != 0 {
		t.Fatalf("summary = %+v", sum)
	}
	movies, _ := e.q.ListMovies(e.ctx, db.ListMoviesParams{LibraryID: e.mov.ID, Limit: 10})
	if len(movies) != 2 {
		t.Fatalf("movies = %d, want 2 (two Matrix files share one movie)", len(movies))
	}
	matrix := movies[1]
	if matrix.Movie.Title != "The Matrix" || matrix.FileCount != 2 || *matrix.Movie.ImdbID != "tt0133093" || *matrix.Movie.RuntimeMin != 136 || matrix.Movie.Genres != `["Action","Science Fiction"]` {
		t.Errorf("matrix = %+v", matrix)
	}
	// Its trailers came with its details, best first, and count as checked.
	if want := `[{"key":"trailerKey1","name":"Official Trailer","type":"Trailer"},{"key":"teaserKey01","name":"Teaser","type":"Teaser"}]`; matrix.Movie.Trailers != want || matrix.Movie.TrailersCheckedAt == nil {
		t.Errorf("matrix trailers = %s, checked %v", matrix.Movie.Trailers, matrix.Movie.TrailersCheckedAt)
	}
	if heat := movies[0].Movie; heat.Trailers != "[]" || heat.TrailersCheckedAt == nil {
		t.Errorf("heat trailers = %s, checked %v", heat.Trailers, heat.TrailersCheckedAt)
	}
	f, _ := e.q.GetMediaFileByPath(e.ctx, "/m/Ambiguous Title (2020).mkv")
	if f.MatchStatus != StatusUnmatched || f.MatchCandidates == nil || !strings.Contains(*f.MatchCandidates, "Something Else") {
		t.Errorf("ambiguous = %+v", f)
	}
	f, _ = e.q.GetMediaFileByPath(e.ctx, "/m/Unknown Thing (2001).mkv")
	if f.MatchStatus != StatusUnmatched || f.MatchError == nil {
		t.Errorf("unknown = %+v", f)
	}
	// A second run has nothing pending, leaves results alone, and still
	// reports completion on the bus.
	ch, cancel := e.bus.Subscribe()
	defer cancel()
	sum, _ = e.m.MatchLibrary(e.ctx, e.mov, false)
	if sum.Matched+sum.Unmatched+sum.Failed != 0 {
		t.Errorf("second run = %+v", sum)
	}
	select {
	case ev := <-ch:
		if ev.Type != "match.finished" {
			t.Errorf("event = %q, want match.finished", ev.Type)
		}
	default:
		t.Error("no match.finished event for an empty run")
	}
	// Rematch retries the unmatched ones only.
	sum, _ = e.m.MatchLibrary(e.ctx, e.mov, true)
	if sum.Unmatched != 2 || sum.Matched != 0 {
		t.Errorf("rematch = %+v", sum)
	}
}

func TestMatchEpisodes(t *testing.T) {
	e := setup(t)
	e.file(e.tv, "/t/Breaking Bad (2008)/Season 01/Breaking Bad - S01E01 - Pilot.mkv", `{"kind":"episode","title":"Breaking Bad","year":2008,"season":1,"episodes":[1]}`)
	e.file(e.tv, "/t/Breaking Bad (2008)/Season 01/Breaking Bad - S01E01E02.mkv", `{"kind":"episode","title":"Breaking Bad","year":2008,"season":1,"episodes":[1,2]}`)
	e.file(e.tv, "/t/Breaking Bad (2008)/Season 01/Breaking Bad - S01E99.mkv", `{"kind":"episode","title":"Breaking Bad","year":2008,"season":1,"episodes":[99]}`)
	e.file(e.tv, "/t/Breaking Bad (2008)/Season 09/Breaking Bad - S09E01.mkv", `{"kind":"episode","title":"Breaking Bad","season":9,"episodes":[1]}`)
	e.file(e.tv, "/t/Breaking Bad (2008)/extra.mkv", `{"kind":"unknown","title":"Breaking Bad"}`)
	e.file(e.tv, "/t/Nope/Season 01/Nope - S01E01.mkv", `{"kind":"episode","title":"Nope","season":1,"episodes":[1]}`)

	sum, err := e.m.MatchLibrary(e.ctx, e.tv, false)
	if err != nil {
		t.Fatal(err)
	}
	if sum.Matched != 2 || sum.Unmatched != 4 || sum.Failed != 0 {
		t.Fatalf("summary = %+v", sum)
	}
	shows, _ := e.q.ListShows(e.ctx, db.ListShowsParams{LibraryID: e.tv.ID, Limit: 10})
	if len(shows) != 1 || shows[0].Show.Title != "Breaking Bad" || shows[0].EpisodeCount != 2 {
		t.Fatalf("shows = %+v", shows)
	}
	seasons, _ := e.q.ListSeasons(e.ctx, shows[0].Show.ID)
	if len(seasons) != 2 || seasons[0].Number != 1 || seasons[0].AvailableCount != 2 || seasons[1].AvailableCount != 0 {
		t.Errorf("seasons = %+v", seasons)
	}
	// Season 2 has no files but its episodes are stored anyway.
	if eps, _ := e.q.ListSeasonEpisodes(e.ctx, seasons[1].ID); len(eps) != 1 || eps[0].Title != "Seven Thirty-Seven" {
		t.Errorf("season 2 episodes = %+v", eps)
	}
	rows, _ := e.q.ListSeasonEpisodeFiles(e.ctx, seasons[0].ID)
	if len(rows) != 3 { // file1->ep1, file2->ep1, file2->ep2
		t.Errorf("episode files = %d, want 3", len(rows))
	}
	f, _ := e.q.GetMediaFileByPath(e.ctx, "/t/Breaking Bad (2008)/Season 01/Breaking Bad - S01E99.mkv")
	if f.MatchStatus != StatusUnmatched || !strings.Contains(*f.MatchError, "no episode [99]") {
		t.Errorf("E99 = %+v %v", f.MatchStatus, deref(f.MatchError))
	}
	f, _ = e.q.GetMediaFileByPath(e.ctx, "/t/Breaking Bad (2008)/Season 09/Breaking Bad - S09E01.mkv")
	if f.MatchStatus != StatusUnmatched || !strings.Contains(*f.MatchError, "no season 9") {
		t.Errorf("S09 = %+v %v", f.MatchStatus, deref(f.MatchError))
	}
}

func TestApplyManualMatchLocks(t *testing.T) {
	e := setup(t)
	f := e.file(e.mov, "/m/Ambiguous Title (2020).mkv", `{"kind":"movie","title":"Ambiguous Title","year":2020}`)
	if _, err := e.m.MatchLibrary(e.ctx, e.mov, false); err != nil {
		t.Fatal(err)
	}
	if err := e.m.Apply(e.ctx, e.mov, f, 603, nil, nil); err != nil {
		t.Fatal(err)
	}
	row, _ := e.q.GetMediaFile(e.ctx, f.ID)
	if row.MatchStatus != StatusMatched || row.MovieID == nil || row.MatchLocked != 1 {
		t.Fatalf("after apply = %+v", row)
	}
	// A rematch must not touch the locked file.
	if sum, _ := e.m.MatchLibrary(e.ctx, e.mov, true); sum.Matched+sum.Unmatched != 0 {
		t.Errorf("rematch touched locked file: %+v", sum)
	}
	// Manual TV match with explicit season/episode for an unparseable name.
	tvf := e.file(e.tv, "/t/Breaking Bad/weird name.mkv", `{"kind":"unknown","title":"weird name"}`)
	if err := e.m.Apply(e.ctx, e.tv, tvf, 1396, new(1), []int{2}); err != nil {
		t.Fatal(err)
	}
	ids, _ := e.q.ListEpisodeIDsForFile(e.ctx, tvf.ID)
	if len(ids) != 1 {
		t.Errorf("episode ids = %v", ids)
	}
}

func TestLookupFailureIsFailedNotUnmatched(t *testing.T) {
	e := setup(t)
	e.m.tmdb = tmdb.New("key", "http://127.0.0.1:1") // nothing listens here
	e.file(e.mov, "/m/The Matrix (1999).mkv", `{"kind":"movie","title":"The Matrix","year":1999}`)
	sum, err := e.m.MatchLibrary(e.ctx, e.mov, false)
	if err != nil {
		t.Fatal(err)
	}
	if sum.Failed != 1 {
		t.Errorf("summary = %+v", sum)
	}
	f, _ := e.q.GetMediaFileByPath(e.ctx, "/m/The Matrix (1999).mkv")
	if f.MatchStatus != StatusFailed {
		t.Errorf("status = %q", f.MatchStatus)
	}
}

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

func TestApplyGroupRemembersFolder(t *testing.T) {
	e := setup(t)
	a := e.file(e.tv, "/t/BB/Season 01/BB - S01E01.mkv", `{"kind":"episode","title":"BB","season":1,"episodes":[1]}`)
	b := e.file(e.tv, "/t/BB/Season 01/BB - S01E02.mkv", `{"kind":"episode","title":"BB","season":1,"episodes":[2]}`)
	c := e.file(e.tv, "/t/BB/Season 01/BB - S01E05.mkv", `{"kind":"episode","title":"BB","season":1,"episodes":[5]}`)
	res, err := e.m.ApplyGroup(e.ctx, e.tv, []db.MediaFile{a, b, c}, 1396, true)
	if err != nil {
		t.Fatal(err)
	}
	if res.Matched != 2 || res.NeedEpisodes != 1 || res.Folder != "BB" || res.ShowID == nil {
		t.Fatalf("result = %+v", res)
	}
	row, _ := e.q.GetMediaFile(e.ctx, c.ID)
	if row.MatchStatus != StatusUnmatched || row.MatchLocked != 0 || !strings.Contains(deref(row.MatchError), "no episode [5]") {
		t.Errorf("E05 = %s locked=%d %q", row.MatchStatus, row.MatchLocked, deref(row.MatchError))
	}
	// A new file in the folder follows the choice, though its name finds nothing on TMDB.
	e.file(e.tv, "/t/BB/Season 02/Unknown Words - S02E01.mkv", `{"kind":"episode","title":"Unknown Words","season":2,"episodes":[1]}`)
	sum, err := e.m.MatchLibrary(e.ctx, e.tv, false)
	if err != nil {
		t.Fatal(err)
	}
	if sum.Matched != 1 {
		t.Errorf("summary = %+v", sum)
	}
}

func TestLineUp(t *testing.T) {
	e := setup(t)
	files := []db.MediaFile{
		e.file(e.tv, "/t/BB/S01E01.mkv", `{"kind":"episode","title":"BB","season":1,"episodes":[1]}`),
		e.file(e.tv, "/t/BB/S01E09.mkv", `{"kind":"episode","title":"BB","season":1,"episodes":[9]}`), // season 1 has 7
		e.file(e.tv, "/t/BB/S03E01.mkv", `{"kind":"episode","title":"BB","season":3,"episodes":[1]}`), // no season 3
		e.file(e.tv, "/t/BB/extra.mkv", `{"kind":"unknown","title":"extra"}`),
	}
	lu, err := e.m.LineUp(e.ctx, e.tv, files, 1396)
	if err != nil {
		t.Fatal(err)
	}
	want := []LineUpSeason{{Season: 1, Files: 2, Found: 1, OnTMDB: 7}, {Season: 3, Files: 1, Found: 0, OnTMDB: 0}}
	if lu.Total != 4 || lu.Found != 1 || lu.Unnumbered != 1 || len(lu.Seasons) != 2 || lu.Seasons[0] != want[0] || lu.Seasons[1] != want[1] {
		t.Errorf("line-up = %+v", lu)
	}
	// Nothing was saved.
	if row, _ := e.q.GetMediaFile(e.ctx, files[0].ID); row.MatchStatus != StatusPending {
		t.Errorf("line-up changed a file: %s", row.MatchStatus)
	}
}
