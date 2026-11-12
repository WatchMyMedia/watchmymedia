package metadata

import "testing"

func TestParseRef(t *testing.T) {
	cases := []struct {
		in, source, id, kind string
		ok, err              bool
	}{
		{"https://www.themoviedb.org/tv/2224-the-daily-show", "tmdb", "2224", "tv", true, false},
		{"themoviedb.org/movie/603", "tmdb", "603", "movie", true, false},
		{"tmdb:2224", "tmdb", "2224", "", true, false},
		{"tvdb 71256", "tvdb", "71256", "", true, false},
		{"https://thetvdb.com/?tab=series&id=71256", "tvdb", "71256", "", true, false},
		{"https://thetvdb.com/series/the-daily-show", "", "", "", true, true},
		{"https://www.imdb.com/title/tt0115147/", "imdb", "tt0115147", "", true, false},
		{"TT0115147", "imdb", "tt0115147", "", true, false},
		{"1917", "", "", "", false, false}, // a title, not an ID
		{"The Daily Show", "", "", "", false, false},
	}
	for _, c := range cases {
		r, ok, err := parseRef(c.in)
		if ok != c.ok || (err != nil) != c.err || r.source != c.source || r.id != c.id || r.kind != c.kind {
			t.Errorf("parseRef(%q) = %+v %v %v", c.in, r, ok, err)
		}
	}
}

func TestSearchByID(t *testing.T) {
	e := setup(t)
	for _, c := range []struct {
		libType, q string
		want       int
	}{
		{"tv", "tt0903747", 1396},
		{"tv", "tvdb:81189", 1396},
		{"tv", "https://www.themoviedb.org/tv/1396-breaking-bad", 1396},
		{"movie", "https://www.imdb.com/title/tt0133093/", 603},
		{"movie", "tmdb:603", 603},
	} {
		got, err := e.m.Search(e.ctx, c.libType, c.q, nil)
		if err != nil || len(got) != 1 || got[0].TMDBID != c.want {
			t.Errorf("Search(%s, %q) = %+v %v", c.libType, c.q, got, err)
		}
	}
	if _, err := e.m.Search(e.ctx, "movie", "tt0903747", nil); !IsNoMatch(err) {
		t.Errorf("a TV show's ID in a movie library: err = %v", err)
	}
	if got, err := e.m.Search(e.ctx, "tv", "tmdb:999999", nil); err != nil || len(got) != 0 {
		t.Errorf("unknown TMDB ID = %+v %v", got, err)
	}
}
