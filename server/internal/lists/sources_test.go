package lists

import (
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"golang.org/x/time/rate"

	"watchmymedia/server/internal/lists/mdblisttest"
	"watchmymedia/server/internal/tmdb/tmdbtest"
)

// mdbClient is a client of the fake with its key and no rate limit.
func mdbClient(s *mdblisttest.Server) *MDBList {
	return NewMDBList(mdblisttest.Key, MDBListOptions{BaseURL: s.URL, Limiter: rate.NewLimiter(rate.Inf, 0)})
}

// moviesPage is a page of 20 fictional movies from id on.
func moviesPage(id int) []any {
	out := make([]any, 20)
	for i := range out {
		out[i] = tmdbtest.Movie(id+i, fmt.Sprintf("Film %d", id+i), "2001-05-04")
	}
	return out
}

// A chart of 50 entries takes three pages and stops there; discover sends
// its filters and never asks for adult titles.
func TestTMDBPagesAndDiscover(t *testing.T) {
	t.Parallel()
	fake := tmdbtest.New(t)
	fake.Pages("/movie/top_rated", moviesPage(1), moviesPage(21), moviesPage(41), moviesPage(61))
	src := &TMDB{Client: fake.Client()}
	res, err := src.Fetch(t.Context(), Request{Params: Params{Chart: ChartTopRated}, MediaType: "movie", Limit: 50})
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Entries) != 50 || res.Entries[0].Rank != 1 || res.Entries[0].TMDBID != 1 || res.Entries[49].TMDBID != 50 || res.Entries[0].Year != 2001 {
		t.Fatalf("entries = %d, first %+v", len(res.Entries), res.Entries[0])
	}
	if fake.Total() != 3 || res.URL != "https://www.themoviedb.org/movie/top-rated" {
		t.Errorf("%d requests, url %q", fake.Total(), res.URL)
	}

	var query string
	fake.Handle("/discover/tv", func(r *http.Request) any {
		query = r.URL.RawQuery
		return map[string]any{"page": 1, "total_pages": 1, "results": []any{map[string]any{"id": 7, "name": "Harbour", "first_air_date": "1985-01-01"}}}
	})
	res, err = src.Fetch(t.Context(), Request{Params: Params{Chart: ChartDiscover, Genres: []int64{878, 18}, YearFrom: 1980, YearTo: 1989, MinRating: 7, SortBy: "rating"}, MediaType: "tv", Limit: 50})
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"include_adult=false", "with_genres=878%2C18", "first_air_date.gte=1980-01-01", "first_air_date.lte=1989-12-31", "vote_average.gte=7", "vote_count.gte=200", "sort_by=vote_average.desc"} {
		if !strings.Contains(query, want) {
			t.Errorf("discover query %q lacks %s", query, want)
		}
	}
	if len(res.Entries) != 1 || res.Entries[0].MediaType != "tv" || res.Entries[0].Title != "Harbour" {
		t.Errorf("discover entries = %+v", res.Entries)
	}
}

func TestTMDBValidateAndRefs(t *testing.T) {
	t.Parallel()
	src := &TMDB{}
	for _, c := range []struct {
		p     Params
		media string
		ok    bool
	}{
		{Params{Chart: ChartTrendingWeek}, "all", true},
		{Params{Chart: ChartPopular}, "all", false},
		{Params{Chart: ChartUpcoming}, "tv", false},
		{Params{Chart: ChartAiringToday}, "tv", true},
		{Params{Chart: ChartDiscover, YearFrom: 1990, YearTo: 1980}, "movie", false},
		{Params{Chart: ChartDiscover, Language: "Korean"}, "movie", false},
		{Params{Chart: ChartList, Ref: "https://www.themoviedb.org/list/8136-best-picture"}, "all", true},
		{Params{Chart: ChartList, Ref: "https://example.com/list/8136"}, "all", false},
		{Params{Chart: ChartCollection, Ref: "themoviedb.org/fr/collection/10-star"}, "movie", true},
		{Params{Chart: ChartCollection, Ref: "10"}, "tv", false},
		{Params{Chart: "best"}, "movie", false},
		{Params{}, "movie", false},
	} {
		err := src.Validate(Request{Params: c.p, MediaType: c.media})
		if (err == nil) != c.ok {
			t.Errorf("Validate(%+v, %s) = %v, want ok %v", c.p, c.media, err, c.ok)
		}
		if _, isInvalid := errors.AsType[*InvalidError](err); err != nil && !isInvalid {
			t.Errorf("Validate(%+v) = %T, not an InvalidError", c.p, err)
		}
	}
	if id, err := tmdbRef("https://www.themoviedb.org/list/8136-best-picture?language=en", "list"); id != 8136 || err != nil {
		t.Errorf("list ref = %d, %v", id, err)
	}
}

// A TMDB list holds movies and shows; a movie list keeps its movies, and a
// list TMDB doesn't have says so.
func TestTMDBListAndCollection(t *testing.T) {
	t.Parallel()
	fake := tmdbtest.New(t)
	fake.JSON("/list/42", map[string]any{"page": 1, "total_pages": 1, "items": []any{tmdbtest.Movie(1, "Lantern", "1999-01-01"), tmdbtest.Show(2, "Harbour", "2010-01-01"), tmdbtest.Movie(3, "Quay", "")}})
	fake.JSON("/collection/9", map[string]any{"parts": []any{tmdbtest.Movie(5, "Quay II", "2003-01-01"), tmdbtest.Movie(4, "Quay", "2001-01-01")}})
	src := &TMDB{Client: fake.Client()}
	res, err := src.Fetch(t.Context(), Request{Params: Params{Chart: ChartList, Ref: "42"}, MediaType: "movie", Limit: 50})
	if err != nil || len(res.Entries) != 2 || res.Entries[1].TMDBID != 3 || res.Entries[1].Rank != 2 || res.URL != "https://www.themoviedb.org/list/42" {
		t.Fatalf("list = %+v, %v", res, err)
	}
	if res, err := src.Fetch(t.Context(), Request{Params: Params{Chart: ChartList, Ref: "42"}, MediaType: "all", Limit: 2}); err != nil || len(res.Entries) != 2 || res.Entries[1].MediaType != "tv" {
		t.Errorf("both kinds = %+v, %v", res.Entries, err)
	}
	if res, err := src.Fetch(t.Context(), Request{Params: Params{Chart: ChartCollection, Ref: "9"}, MediaType: "movie", Limit: 50}); err != nil || len(res.Entries) != 2 {
		t.Errorf("collection = %+v, %v", res.Entries, err)
	}
	_, err = src.Fetch(t.Context(), Request{Params: Params{Chart: ChartList, Ref: "43"}, MediaType: "movie", Limit: 50})
	if _, ok := errors.AsType[*RefusedError](err); !ok || !strings.Contains(Describe(err), "TMDB has no such list") {
		t.Errorf("a missing list = %v", err)
	}
}

// MDBList's titles keep their TMDB IDs; one without is found on TMDB by its
// IMDb ID, once however often it is asked. Movies and shows come back
// together in rank order.
func TestMDBListItems(t *testing.T) {
	t.Parallel()
	mdb := mdblisttest.New(t)
	mdb.Add(mdblisttest.List{ID: 77, User: "critic", Slug: "best-of", Name: "Best of", Items: []mdblisttest.Item{
		{Title: "Lantern", Year: 1999, MediaType: "movie", TMDB: 11, IMDb: "tt0000011"},
		{Title: "Harbour", Year: 2010, MediaType: "show", TMDB: 22, IMDb: "tt0000022"},
		{Title: "Quay", Year: 2001, MediaType: "movie", IMDb: "tt0000033"},
	}})
	fake := tmdbtest.New(t)
	fake.JSON("/find/tt0000033", map[string]any{"movie_results": []any{map[string]any{"id": 33, "title": "Quay", "release_date": "2001-02-03", "poster_path": "/q.jpg"}}})
	src := newMDBListSource(mdbClient(mdb), newResolver(fake.Client()))
	for range 2 {
		res, err := src.Fetch(t.Context(), Request{Params: Params{Ref: "https://mdblist.com/lists/critic/best-of"}, MediaType: "all", Limit: 50})
		if err != nil {
			t.Fatal(err)
		}
		if len(res.Entries) != 3 || res.Entries[1].MediaType != "tv" || res.Entries[1].TMDBID != 22 || res.Entries[2].TMDBID != 33 || res.Entries[0].PosterPath != "/p11.jpg" {
			t.Fatalf("entries = %+v", res.Entries)
		}
		if res.URL != "https://mdblist.com/lists/critic/best-of" {
			t.Errorf("url = %q", res.URL)
		}
	}
	if n := fake.Count("/find/tt0000033"); n != 1 {
		t.Errorf("TMDB was asked %d times for the IMDb ID", n)
	}
	// By its numeric ID the list's page is looked up for the link; a movie
	// list keeps the movies.
	res, err := src.Fetch(t.Context(), Request{Params: Params{Ref: "77"}, MediaType: "movie", Limit: 50})
	if err != nil || len(res.Entries) != 2 || res.URL != "https://mdblist.com/lists/critic/best-of" {
		t.Errorf("by id = %+v, %v", res, err)
	}
	for _, ref := range []string{"critic/best-of", "77", "https://www.mdblist.com/lists/critic/best-of/"} {
		if _, err := ParseMDBListRef(ref); err != nil {
			t.Errorf("ParseMDBListRef(%q) = %v", ref, err)
		}
	}
	for _, ref := range []string{"", "https://example.com/lists/a/b", "a/b/c", "https://mdblist.com/movie/tt1"} {
		if _, err := ParseMDBListRef(ref); err == nil {
			t.Errorf("ParseMDBListRef(%q) took it", ref)
		}
	}
}

// A refused key, a missing list and the daily limit each say what they
// are; the key never shows in an error.
func TestMDBListErrors(t *testing.T) {
	t.Parallel()
	mdb := mdblisttest.New(t)
	client := mdbClient(mdb)
	if err := client.Verify(t.Context(), mdblisttest.Key); err != nil {
		t.Errorf("Verify = %v", err)
	}
	if err := client.Verify(t.Context(), "wrong-key"); !errors.Is(err, ErrMDBListKey) {
		t.Errorf("Verify(wrong) = %v", err)
	}
	src := newMDBListSource(client, nil)
	if _, err := src.Fetch(t.Context(), Request{Params: Params{Ref: "5"}, MediaType: "movie", Limit: 10}); Describe(err) != ErrMDBListNotFound.Reason {
		t.Errorf("missing list = %v", err)
	}
	mdb.Fail(http.StatusTooManyRequests)
	_, err := src.Fetch(t.Context(), Request{Params: Params{Ref: "a/b"}, MediaType: "movie", Limit: 10})
	if e, ok := errors.AsType[*MDBListLimitError](err); !ok || e.RetryAfter.Hours() != 1 {
		t.Errorf("daily limit = %v", err)
	}
	// A redirect to another host is refused, key and all.
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Errorf("the redirect was followed to %s", r.URL)
	}))
	defer other.Close()
	moved := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, other.URL+r.URL.Path+"?"+r.URL.RawQuery, http.StatusFound)
	}))
	defer moved.Close()
	err = NewMDBList("secret-key", MDBListOptions{BaseURL: moved.URL}).Verify(t.Context(), "secret-key")
	if err == nil || strings.Contains(err.Error(), "secret-key") {
		t.Errorf("redirect = %v", err)
	}
}

func TestParseImports(t *testing.T) {
	t.Parallel()
	imdb := "Position,Const,Created,Modified,Description,Title,Original Title,URL,Title Type,IMDb Rating,Runtime (mins),Year,Genres,Num Votes,Release Date,Directors,Your Rating,Date Rated\n" +
		"1,tt0000011,2026-01-01,2026-01-01,,Lantern,Lantern,https://www.imdb.com/title/tt0000011/,Movie,7.9,120,1999,Drama,1000,1999-01-01,Someone,,\n" +
		"2,tt0000022,2026-01-01,2026-01-01,,Harbour,Harbour,https://www.imdb.com/title/tt0000022/,TV Series,8.1,50,2010,Comedy,500,2010-01-01,,,\n" +
		"3,tt0000044,2026-01-01,2026-01-01,,Pilot,Pilot,https://www.imdb.com/title/tt0000044/,TV Episode,8.1,50,2010,Comedy,500,2010-01-01,,,\n"
	format, lines, err := Parse(imdb)
	if err != nil || format != FormatIMDb || len(lines) != 2 || lines[0].IMDbID != "tt0000011" || lines[0].MediaType != "movie" || lines[1].MediaType != "tv" {
		t.Fatalf("imdb = %s %+v %v", format, lines, err)
	}
	// A Letterboxd list export has its own header lines first, and names
	// with decomposed accents are put together.
	letterboxd := "Letterboxd list export v7\nDate,Name,Tags,URL,Description\n2026-01-01,Best,,https://boxd.it/x,\n\n" +
		"Position,Name,Year,URL,Description\n1,Amélie Street,2001,https://boxd.it/a,\n2,\"Quay, The\",1980,https://boxd.it/b,\n"
	format, lines, err = Parse(letterboxd)
	if err != nil || format != FormatLetterboxd || len(lines) != 2 || lines[0].Title != "Amélie Street" || lines[1].Title != "Quay, The" || lines[1].Year != 1980 {
		t.Fatalf("letterboxd = %s %+v %v", format, lines, err)
	}
	pasted := "1. tt0000011\nhttps://www.themoviedb.org/tv/22-harbour\n# a comment\n\nQuay (2001)\nJust A Title\n"
	format, lines, err = Parse(pasted)
	if err != nil || format != FormatLines || len(lines) != 4 {
		t.Fatalf("lines = %s %+v %v", format, lines, err)
	}
	if lines[0].IMDbID != "tt0000011" || lines[1].TMDBID != 22 || lines[1].MediaType != "tv" || lines[2].Title != "Quay" || lines[2].Year != 2001 || lines[3].Title != "Just A Title" {
		t.Errorf("lines = %+v", lines)
	}
	if _, _, err := Parse(strings.Repeat("x", MaxImportBytes+1)); err == nil {
		t.Error("an import over 1 MB was read")
	}
	_, lines, _ = Parse(strings.Repeat("tt0000011\n", 300))
	if len(lines) != MaxEntries {
		t.Errorf("%d lines kept of 300", len(lines))
	}
}

// An import looks each title up on TMDB; one it can't find is kept, not
// found, with its title.
func TestImportResolve(t *testing.T) {
	t.Parallel()
	fake := tmdbtest.New(t)
	fake.JSON("/find/tt0000011", map[string]any{"movie_results": []any{map[string]any{"id": 11, "title": "Lantern", "release_date": "1999-01-01"}}})
	fake.JSON("/search/movie", map[string]any{"results": []any{
		map[string]any{"id": 90, "title": "Quay", "release_date": "1975-01-01"},
		map[string]any{"id": 91, "title": "Quay", "release_date": "2002-03-01"},
	}})
	fake.JSON("/tv/22", map[string]any{"id": 22, "name": "Harbour", "first_air_date": "2010-04-01", "poster_path": "/h.jpg"})
	im := &Importer{tmdb: fake.Client(), find: newResolver(fake.Client()), Workers: 2}
	res, err := im.Fetch(t.Context(), Request{Params: Params{Text: "tt0000011\nQuay (2001)\nhttps://www.themoviedb.org/tv/22\ntt9999999\nNowhere (1950)"}, MediaType: "all", Limit: 50})
	if err != nil {
		t.Fatal(err)
	}
	want := []struct {
		id    int64
		kind  string
		title string
	}{{11, "movie", "Lantern"}, {91, "movie", "Quay"}, {22, "tv", "Harbour"}, {0, "movie", "tt9999999"}, {0, "movie", "Nowhere"}}
	if len(res.Entries) != len(want) || res.Format != FormatLines {
		t.Fatalf("entries = %+v", res.Entries)
	}
	for i, w := range want {
		if e := res.Entries[i]; e.TMDBID != w.id || e.MediaType != w.kind || e.Title != w.title || e.Rank != i+1 {
			t.Errorf("entry %d = %+v, want %+v", i, e, w)
		}
	}
}
