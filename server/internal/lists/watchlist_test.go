package lists

import (
	"database/sql"
	"errors"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/db/dbtest"
)

// watchlistEnv is a database with one person, Robin, a movie the library
// has (Ninety Fathoms) and a list from TMDB holding a show it lacks
// (Harbor Lights), with the watchlists' clock stopped.
type watchlistEnv struct {
	t     *testing.T
	conn  *sql.DB
	q     *db.Queries
	s     *Service
	robin int64
	now   time.Time
}

const (
	fathoms = 501 // Ninety Fathoms, a movie in the library
	harbor  = 601 // Harbor Lights, a show on a TMDB list, not in the library
)

func newWatchlistEnv(t *testing.T) *watchlistEnv {
	t.Helper()
	conn, q := dbtest.Open(t)
	e := &watchlistEnv{t: t, conn: conn, q: q, now: time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC)}
	e.s = New(Options{Queries: q, Conn: conn})
	e.s.Now = func() time.Time { return e.now }
	u, err := q.CreateUser(t.Context(), db.CreateUserParams{Name: "Robin", PasswordHash: "x"})
	if err != nil {
		t.Fatal(err)
	}
	e.robin = u.ID
	ctx := t.Context()
	lib, err := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "movie", Name: "Movies", Path: "/m"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := conn.ExecContext(ctx, `INSERT INTO movies (library_id, tmdb_id, imdb_id, title, year, genres, poster_path)
		VALUES (?, ?, 'tt0000501', 'Ninety Fathoms', 1998, '[]', '/fathoms.jpg')`, lib.ID, fathoms); err != nil {
		t.Fatal(err)
	}
	_, err = e.s.Create(ctx, db.CreateMediaListParams{Screen: "home", Name: "Trending", SourceKind: KindTMDB, Params: "{}", MediaType: "all", EntryLimit: 50},
		[]Entry{{Rank: 1, MediaType: "tv", TMDBID: harbor, Title: "Harbor Lights", Year: 2011, PosterPath: "/harbor.jpg"}})
	if err != nil {
		t.Fatal(err)
	}
	return e
}

func (e *watchlistEnv) create(name string) db.MediaList {
	e.t.Helper()
	l, err := e.s.CreateWatchlist(e.t.Context(), e.robin, name, true)
	if err != nil {
		e.t.Fatal(err)
	}
	return l
}

func (e *watchlistEnv) entries(l db.MediaList) []Entry {
	e.t.Helper()
	got, err := e.s.Entries(e.t.Context(), l.ID)
	if err != nil {
		e.t.Fatal(err)
	}
	return got
}

// reason is what a refusal says to the person, or "" for none.
func reason(err error) string {
	if e, ok := errors.AsType[*InvalidError](err); ok {
		return e.Reason
	}
	if err != nil {
		return "unexpected: " + err.Error()
	}
	return ""
}

func TestWatchlistNames(t *testing.T) {
	t.Parallel()
	e := newWatchlistEnv(t)
	e.create(FirstWatchlist)
	halloween := e.create("Halloween")
	ctx := t.Context()
	for _, c := range []struct {
		name, give, want string
		rename           *db.MediaList
	}{
		{name: "a new name", give: "With the kids"},
		{name: "spaces around it are dropped", give: "  Rainy days  "},
		{name: "empty", give: "   ", want: "Give the watchlist a name."},
		{name: "too long", give: strings.Repeat("é", MaxWatchlistName+1), want: "A watchlist's name has at most 60 characters."},
		{name: "60 characters", give: strings.Repeat("é", MaxWatchlistName)},
		{name: "another's, in another case", give: "WATCHLIST", want: "You already have a watchlist called Watchlist."},
		{name: "renamed to its own name", give: "halloween", rename: &halloween},
		{name: "renamed to another's", give: "Watchlist", rename: &halloween, want: "You already have a watchlist called Watchlist."},
	} {
		var err error
		if c.rename != nil {
			_, err = e.s.UpdateWatchlist(ctx, *c.rename, &c.give, nil)
		} else {
			_, err = e.s.CreateWatchlist(ctx, e.robin, c.give, true)
		}
		if got := reason(err); got != c.want {
			t.Errorf("%s: %q, want %q", c.name, got, c.want)
		}
	}
}

// A person may have 25 watchlists, each of up to 500 titles.
func TestWatchlistLimits(t *testing.T) {
	t.Parallel()
	e := newWatchlistEnv(t)
	ctx := t.Context()
	var l db.MediaList
	for i := range MaxWatchlists {
		l = e.create(strings.Repeat("x", i+1))
	}
	if _, err := e.s.CreateWatchlist(ctx, e.robin, "One more", true); reason(err) != "You have 25 watchlists, the most you can make." {
		t.Errorf("the 26th: %v", err)
	}
	fill(t, e.conn, l.ID, MaxWatchlistTitles-1)
	if _, err := e.s.SaveToWatchlist(ctx, l, Title{MediaType: "movie", TMDBID: fathoms}); err != nil {
		t.Fatalf("the 500th: %v", err)
	}
	if _, err := e.s.SaveToWatchlist(ctx, l, Title{MediaType: "tv", TMDBID: harbor}); reason(err) != "A watchlist holds up to 500 titles." {
		t.Errorf("the 501st: %v", err)
	}
}

// fill puts n made-up movies on a list, ranked from 1.
func fill(t testing.TB, conn *sql.DB, listID int64, n int) {
	t.Helper()
	if _, err := conn.ExecContext(t.Context(), `WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < ?)
		INSERT INTO media_list_entries (list_id, rank, media_type, tmdb_id, title, added_at)
		SELECT ?, i, 'movie', 100000 + i, 'Title ' || i, '2026-01-01T00:00:00.000Z' FROM n`, n, listID); err != nil {
		t.Fatal(err)
	}
}

// A new title goes below the lowest rank, so no other entry moves; the
// same title again keeps its place; and each save is dated by the clock.
func TestWatchlistRanks(t *testing.T) {
	t.Parallel()
	e := newWatchlistEnv(t)
	ctx := t.Context()
	l := e.create(FirstWatchlist)
	save := func(mediaType string, tmdbID int64, title string) bool {
		t.Helper()
		saved, err := e.s.SaveToWatchlist(ctx, l, Title{MediaType: mediaType, TMDBID: tmdbID, Title: title})
		if err != nil {
			t.Fatal(err)
		}
		e.now = e.now.Add(time.Minute)
		return saved
	}
	save("movie", fathoms, "")
	save("tv", harbor, "")
	save("movie", 700, "The Long Tide")
	if save("movie", fathoms, "") {
		t.Error("saving a title already there saved it again")
	}
	got := e.entries(l)
	want := []struct {
		rank   int
		tmdbID int64
		added  string
	}{{-2, 700, "2026-03-01T12:02:00.000Z"}, {-1, harbor, "2026-03-01T12:01:00.000Z"}, {0, fathoms, "2026-03-01T12:00:00.000Z"}}
	if len(got) != len(want) {
		t.Fatalf("entries %+v", got)
	}
	for i, w := range want {
		if got[i].Rank != w.rank || got[i].TMDBID != w.tmdbID || got[i].AddedAt != w.added {
			t.Errorf("entry %d: %+v, want rank %d, TMDB %d, saved %s", i, got[i], w.rank, w.tmdbID, w.added)
		}
	}
	if l, err := e.q.GetMediaList(ctx, l.ID); err != nil || deref(l.FetchedAt) != "2026-03-01T12:02:00.000Z" || l.UpdatedAt != "2026-03-01T12:02:00.000Z" {
		t.Errorf("the list's times after the last save: %+v, %v", l, err)
	}
}

// What a watchlist keeps for a title: the library's record, else another
// list's, else what the app said, checked.
func TestSavedTitleDetails(t *testing.T) {
	t.Parallel()
	e := newWatchlistEnv(t)
	l := e.create(FirstWatchlist)
	for _, c := range []struct {
		name   string
		give   Title
		want   Entry
		refuse string
	}{
		{name: "the library's, over the app's", give: Title{MediaType: "movie", TMDBID: fathoms, Title: "Wrong", Year: 2001, PosterPath: "/wrong.jpg"},
			want: Entry{Title: "Ninety Fathoms", Year: 1998, PosterPath: "/fathoms.jpg", IMDbID: "tt0000501"}},
		{name: "another list's", give: Title{MediaType: "tv", TMDBID: harbor},
			want: Entry{Title: "Harbor Lights", Year: 2011, PosterPath: "/harbor.jpg"}},
		{name: "the app's", give: Title{MediaType: "movie", TMDBID: 801, Title: " The Long Tide ", Year: 1987, PosterPath: "/aB3_x-9.png"},
			want: Entry{Title: "The Long Tide", Year: 1987, PosterPath: "/aB3_x-9.png"}},
		{name: "a poster and year that aren't TMDB's are dropped", give: Title{MediaType: "movie", TMDBID: 802, Title: "Low Water", Year: 1500, PosterPath: "https://example.com/p.jpg"},
			want: Entry{Title: "Low Water"}},
		{name: "a poster path outside TMDB's shape", give: Title{MediaType: "movie", TMDBID: 803, Title: "Slack Tide", PosterPath: "/../a.jpg"},
			want: Entry{Title: "Slack Tide"}},
		{name: "no record and no title", give: Title{MediaType: "tv", TMDBID: 804}, refuse: "Say which title this is: the server has no record of it."},
	} {
		_, err := e.s.SaveToWatchlist(t.Context(), l, c.give)
		if got := reason(err); got != c.refuse {
			t.Errorf("%s: refused %q, want %q", c.name, got, c.refuse)
			continue
		}
		if c.refuse != "" {
			continue
		}
		top := e.entries(l)[0]
		got := Entry{Title: top.Title, Year: top.Year, PosterPath: top.PosterPath, IMDbID: top.IMDbID}
		if top.TMDBID != c.give.TMDBID || got != c.want {
			t.Errorf("%s: kept %+v, want %+v", c.name, top, c.want)
		}
	}
}

// The Watchlist button: with none it makes Watchlist, with one it saves
// there, with several it saves nothing and names them.
func TestSaveToOnlyWatchlist(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		name    string
		have    []string
		created bool
		several int
	}{
		{name: "none", created: true},
		{name: "one", have: []string{"Halloween"}},
		{name: "several", have: []string{"Watchlist", "Halloween", "With the kids"}, several: 3},
	} {
		e := newWatchlistEnv(t)
		for _, n := range c.have {
			e.create(n)
		}
		l, created, saved, err := e.s.SaveToOnlyWatchlist(t.Context(), e.robin, Title{MediaType: "movie", TMDBID: fathoms})
		if c.several > 0 {
			several, ok := errors.AsType[*SeveralError](err)
			if !ok || len(several.Watchlists) != c.several {
				t.Errorf("%s: %v", c.name, err)
			}
			all, _ := e.q.ListWatchlists(t.Context(), &e.robin)
			for _, w := range all {
				if n := len(e.entries(w)); n != 0 {
					t.Errorf("%s: %s holds %d", c.name, w.Name, n)
				}
			}
			continue
		}
		want := FirstWatchlist
		if len(c.have) == 1 {
			want = c.have[0]
		}
		if err != nil || !saved || created != c.created || l.Name != want || len(e.entries(l)) != 1 {
			t.Errorf("%s: %s created %v saved %v, %v; want %s created %v", c.name, l.Name, created, saved, err, want, c.created)
		}
	}
}
