package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"reflect"
	"slices"
	"sync"
	"testing"
	"time"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
)

// watchlistsEnv is listsEnv (seedRowsLibrary: movies 9001-9005, shows
// 9101-9103, the admin partway through 9002 and an episode of 9101) with
// the watchlists' clock stopped at the start of 2026 and moved a minute
// by each save, and a third person, Robin.
type watchlistsEnv struct {
	*listsEnv
	robin string // Robin's bearer token
}

func newWatchlistsEnv(t *testing.T) *watchlistsEnv {
	t.Helper()
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	var mu sync.Mutex
	var authSvc *auth.Service
	e := &watchlistsEnv{}
	e.listsEnv = newListsEnv(t, func(d *Deps) {
		authSvc = d.Auth
		d.Lists.Now = func() time.Time {
			mu.Lock()
			defer mu.Unlock()
			now = now.Add(time.Minute)
			return now
		}
	})
	robin, err := e.q.CreateUser(t.Context(), db.CreateUserParams{Name: "Robin", PasswordHash: adminHash()})
	if err != nil {
		t.Fatal(err)
	}
	grant, err := authSvc.CreateSession(t.Context(), robin.ID, auth.Client{Kind: auth.KindBrowser, Name: "Robin"})
	if err != nil {
		t.Fatal(err)
	}
	e.robin = grant.Token
	return e
}

func (e *watchlistsEnv) asRobin(method, path, body string, out any) reply {
	e.t.Helper()
	zero(out)
	c := &http.Client{Transport: e.Client().Transport}
	return doJSON(e.t, c, method, e.URL+path, body, out, "Authorization", "Bearer "+e.robin)
}

// watchlist makes the admin a watchlist holding titles ("movie/9001",
// "tv/9101"), saved in that order.
func (e *watchlistsEnv) watchlist(name string, titles ...string) Watchlist {
	e.t.Helper()
	var w Watchlist
	if res := e.admin(http.MethodPost, "/api/me/watchlists", fmt.Sprintf(`{"name":%q}`, name), &w); res.StatusCode != http.StatusCreated {
		e.t.Fatalf("make %s: %d %s", name, res.StatusCode, res.Body)
	}
	for _, title := range titles {
		if res := e.admin(http.MethodPut, fmt.Sprintf("/api/me/watchlists/%d/titles/%s", w.ID, title), `{"title":"Ninety Fathoms"}`, nil); res.StatusCode != http.StatusOK {
			e.t.Fatalf("save %s: %d %s", title, res.StatusCode, res.Body)
		}
	}
	return w
}

// itemID is the library's movie or show with a TMDB id.
func (e *watchlistsEnv) itemID(table string, tmdbID int64) int64 {
	e.t.Helper()
	var id int64
	if err := e.conn.QueryRowContext(e.t.Context(), `SELECT id FROM `+table+` WHERE tmdb_id = ?`, tmdbID).Scan(&id); err != nil {
		e.t.Fatal(err)
	}
	return id
}

// row is a screen's row with id, and its cards' TMDB ids.
func (e *watchlistsEnv) row(path, id string) (ScreenRow, []int64) {
	e.t.Helper()
	var screen struct {
		Rows []ScreenRow `json:"rows"`
	}
	e.admin(http.MethodGet, path, "", &screen)
	for _, r := range screen.Rows {
		if r.ID == id {
			var ids []int64
			for _, it := range r.Items {
				ids = append(ids, it.TMDBID)
			}
			return r, ids
		}
	}
	return ScreenRow{}, nil
}

func detail(res reply) string {
	var p struct{ Detail string }
	_ = json.Unmarshal(res.Body, &p)
	return p.Detail
}

// Making, renaming and deleting watchlists, and the list operations on one.
func TestWatchlistsFlow(t *testing.T) {
	t.Parallel()
	e := newWatchlistsEnv(t)
	heard, stop := e.bus.Subscribe()
	defer stop()
	var all WatchlistList
	e.admin(http.MethodGet, "/api/me/watchlists", "", &all)
	if len(all.Items) != 0 || all.Max != 25 || all.MaxTitles != 500 {
		t.Fatalf("before any: %+v", all)
	}
	var w Watchlist
	if res := e.admin(http.MethodPost, "/api/me/watchlists", `{"name":"Halloween","removeWatched":false}`, &w); res.StatusCode != http.StatusCreated || w.Name != "Halloween" || w.RemoveWatched {
		t.Fatalf("make: %d %+v", res.StatusCode, w)
	}
	if res := e.admin(http.MethodPost, "/api/me/watchlists", `{"name":"HALLOWEEN"}`, nil); res.StatusCode != http.StatusUnprocessableEntity ||
		detail(res) != "You already have a watchlist called Halloween." {
		t.Errorf("a name in use: %d %s", res.StatusCode, res.Body)
	}
	if e.admin(http.MethodPatch, fmt.Sprintf("/api/me/watchlists/%d", w.ID), `{"name":"With the kids","removeWatched":true}`, &w); w.Name != "With the kids" || !w.RemoveWatched {
		t.Errorf("renamed: %+v", w)
	}

	// As a list, for apps that know lists but not watchlists.
	var l MediaList
	e.admin(http.MethodGet, fmt.Sprintf("/api/lists/%d", w.ID), "", &l)
	if l.Source.Kind != "watchlist" || l.SourceName != "" || l.Owner != "me" || !l.CanEdit || l.Limit != 500 || !l.RemoveWatched ||
		l.FetchedAt == nil || l.NextFetchAt != nil {
		t.Errorf("as a list: %+v", l)
	}
	for _, c := range []struct{ method, path, body string }{
		{http.MethodPatch, fmt.Sprintf("/api/lists/%d", w.ID), `{"name":"Renamed"}`},
		{http.MethodPost, fmt.Sprintf("/api/lists/%d/refresh", w.ID), ""},
		{http.MethodPost, "/api/lists", `{"name":"Mine","mediaType":"all","source":{"kind":"watchlist"},"screen":"home"}`},
	} {
		if res := e.admin(c.method, c.path, c.body, nil); res.StatusCode != http.StatusUnprocessableEntity {
			t.Errorf("%s %s on a watchlist: %d %s", c.method, c.path, res.StatusCode, res.Body)
		}
	}
	// Released phones offer Delete list on its page.
	if res := e.admin(http.MethodDelete, fmt.Sprintf("/api/lists/%d", w.ID), "", nil); res.StatusCode != http.StatusNoContent {
		t.Fatalf("deleteMediaList: %d %s", res.StatusCode, res.Body)
	}
	if e.admin(http.MethodGet, "/api/me/watchlists", "", &all); len(all.Items) != 0 {
		t.Errorf("after deleting: %+v", all)
	}
	moved := 0
	for len(heard) > 0 {
		if ev := <-heard; ev.Type == RowsChangedEvent && ev.To != nil && ev.To.UserID == 1 && ev.To.Private {
			moved++
		}
	}
	if moved != 3 {
		t.Errorf("rows.changed to the person %d times, want 3: made, renamed, deleted", moved)
	}
}

// Saving and removing titles: the one-press path with none, one and
// several watchlists, what the pages and cards say, and the page itself.
func TestWatchlistTitlesFlow(t *testing.T) {
	t.Parallel()
	e := newWatchlistsEnv(t)
	var ch WatchlistTitleChange
	e.admin(http.MethodPut, "/api/me/watchlist/titles/movie/9001", "", &ch)
	if !ch.Created || ch.Watchlist == nil || ch.Watchlist.Name != "Watchlist" || !reflect.DeepEqual(ch.Watchlists, []int64{ch.Watchlist.ID}) {
		t.Fatalf("none: %+v", ch)
	}
	first := ch.Watchlist.ID
	if e.admin(http.MethodPut, "/api/me/watchlist/titles/tv/9101", "", &ch); ch.Created || ch.Watchlist.Titles != 2 {
		t.Errorf("one: %+v", ch)
	}
	if res := e.admin(http.MethodPut, "/api/me/watchlist/titles/movie/4242", "", nil); res.StatusCode != http.StatusUnprocessableEntity {
		t.Errorf("a title the server has no record of, unnamed: %d %s", res.StatusCode, res.Body)
	}
	e.admin(http.MethodPut, "/api/me/watchlist/titles/movie/4242", `{"title":"Ninety Fathoms","year":1999,"posterPath":"/nf.jpg"}`, &ch)
	if ch.Watchlist.Titles != 3 || ch.Watchlist.InLibrary != 2 {
		t.Errorf("a missing title saved: %+v", ch.Watchlist)
	}

	var movie MovieDetail
	if e.admin(http.MethodGet, fmt.Sprintf("/api/movies/%d", e.itemID("movies", 9001)), "", &movie); !reflect.DeepEqual(movie.Watchlists, []int64{first}) {
		t.Errorf("the movie's page: %v", movie.Watchlists)
	}
	var grid ItemList
	e.admin(http.MethodGet, "/api/libraries/1/items?limit=10", "", &grid)
	for _, it := range grid.Items {
		if want := it.TMDBID == 9001; it.Kind == "movie" && (it.Watchlisted != want || it.TMDBID == 0) {
			t.Errorf("card %s: TMDB %d watchlisted %v", it.Title, it.TMDBID, it.Watchlisted)
		}
	}

	second := e.watchlist("Halloween")
	res := e.admin(http.MethodPut, "/api/me/watchlist/titles/movie/9003", "", nil)
	var conflict WatchlistsConflict
	_ = json.Unmarshal(res.Body, &conflict)
	if res.StatusCode != http.StatusConflict || len(conflict.Watchlists) != 2 || conflict.Detail != "You have 2 watchlists. Choose one to save to." {
		t.Errorf("several: %d %s", res.StatusCode, res.Body)
	}
	if e.admin(http.MethodPut, fmt.Sprintf("/api/me/watchlists/%d/titles/movie/9001", second.ID), "", &ch); !reflect.DeepEqual(ch.Watchlists, []int64{first, second.ID}) {
		t.Errorf("on both: %+v", ch)
	}
	var picker WatchlistList
	e.admin(http.MethodGet, "/api/me/watchlists?mediaType=tv&tmdbId=9101", "", &picker)
	if len(picker.Items) != 2 || !picker.Items[0].Contains || picker.Items[1].Contains || !reflect.DeepEqual(picker.Items[0].Posters, []string{"/nf.jpg", "/s.jpg", "/m.jpg"}) {
		t.Errorf("the picker: %+v", picker.Items)
	}

	// The page: newest first, numbered as sent, the missing title with its
	// own details; filtered and sorted.
	page := func(query string) []string {
		t.Helper()
		var got MediaListEntries
		e.admin(http.MethodGet, fmt.Sprintf("/api/lists/%d/entries%s", first, query), "", &got)
		var out []string
		for _, en := range got.Items {
			out = append(out, fmt.Sprintf("%d %s/%d %s %v %v", en.Rank, en.MediaType, en.TMDBID, en.Title, en.Item != nil, en.Watchlisted && en.AddedAt != nil))
		}
		return out
	}
	if got, want := page(""), []string{"1 movie/4242 Ninety Fathoms false true", "2 tv/9101 Harbour 0 true true", "3 movie/9001 Lantern 0 true true"}; !slices.Equal(got, want) {
		t.Errorf("the page:\n got %q\nwant %q", got, want)
	}
	if got, want := page("?mediaType=movie&sort=title"), []string{"1 movie/9001 Lantern 0 true true", "2 movie/4242 Ninety Fathoms false true"}; !slices.Equal(got, want) {
		t.Errorf("movies by title:\n got %q\nwant %q", got, want)
	}

	if e.admin(http.MethodDelete, fmt.Sprintf("/api/me/watchlists/%d/titles/movie/9001", second.ID), "", &ch); !reflect.DeepEqual(ch.Watchlists, []int64{first}) {
		t.Errorf("off one: %+v", ch)
	}
	if e.admin(http.MethodDelete, "/api/me/watchlist/titles/movie/9001", "", &ch); len(ch.Watchlists) != 0 || !reflect.DeepEqual(ch.Removed, []int64{first}) {
		t.Errorf("off every one: %+v", ch)
	}
}

// Another person's watchlist is not found for anyone else, admins
// included, by every operation.
func TestWatchlistPrivacy(t *testing.T) {
	t.Parallel()
	e := newWatchlistsEnv(t)
	var w Watchlist
	if res := e.asMember(http.MethodPost, "/api/me/watchlists", `{"name":"Watchlist"}`, &w); res.StatusCode != http.StatusCreated {
		t.Fatalf("make: %d %s", res.StatusCode, res.Body)
	}
	e.asMember(http.MethodPut, fmt.Sprintf("/api/me/watchlists/%d/titles/movie/9001", w.ID), "", nil)
	for _, c := range []struct{ method, path, body string }{
		{http.MethodPatch, "/api/me/watchlists/%d", `{"name":"Mine now"}`},
		{http.MethodDelete, "/api/me/watchlists/%d", ""},
		{http.MethodPut, "/api/me/watchlists/%d/titles/movie/9002", ""},
		{http.MethodDelete, "/api/me/watchlists/%d/titles/movie/9001", ""},
		{http.MethodGet, "/api/lists/%d", ""},
		{http.MethodGet, "/api/lists/%d/entries", ""},
		{http.MethodDelete, "/api/lists/%d", ""},
	} {
		path := fmt.Sprintf(c.path, w.ID)
		for who, as := range map[string]func(string, string, string, any) reply{"the admin": e.admin, "Robin": e.asRobin} {
			if res := as(c.method, path, c.body, nil); res.StatusCode != http.StatusNotFound {
				t.Errorf("%s: %s %s -> %d", who, c.method, path, res.StatusCode)
			}
		}
	}
	var mine WatchlistList
	if e.asMember(http.MethodGet, "/api/me/watchlists", "", &mine); len(mine.Items) != 1 || mine.Items[0].Titles != 1 {
		t.Errorf("the owner's, after: %+v", mine.Items)
	}
	var theirs WatchlistList
	if e.admin(http.MethodGet, "/api/me/watchlists", "", &theirs); len(theirs.Items) != 0 {
		t.Errorf("the admin's: %+v", theirs.Items)
	}
}

// Watchlists don't count against the lists' own limits.
func TestListLimitsLeaveWatchlistsOut(t *testing.T) {
	t.Parallel()
	e := newWatchlistsEnv(t)
	for i := range 25 {
		e.watchlist(fmt.Sprintf("Watchlist %d", i))
	}
	var sources MediaListSources
	if e.admin(http.MethodGet, "/api/list-sources", "", &sources); sources.Lists != 0 {
		t.Errorf("lists counted: %d", sources.Lists)
	}
	if res := e.admin(http.MethodPost, "/api/lists", `{"name":"Lanterns","mediaType":"movie","source":{"kind":"library","libraryId":1},"screen":"home"}`, nil); res.StatusCode != http.StatusCreated {
		t.Errorf("a list beside 25 watchlists: %d %s", res.StatusCode, res.Body)
	}
}

// A watchlist's rows: on Home after Continue watching and New from shows
// you follow, first on the library screens; movies and shows on Home, one
// type on each library's; newest first with what arrived since it was
// saved at the front; and on Home without what Continue watching shows,
// until it is hidden.
func TestWatchlistRows(t *testing.T) {
	t.Parallel()
	e := newWatchlistsEnv(t)
	w := e.watchlist("Watchlist", "movie/9003", "tv/9101", "movie/9001", "movie/9002", "tv/9102", "movie/4242")
	e.watchlist("Halloween", "movie/4343")
	// 9003, saved first, reached the library after everything was saved.
	if _, err := e.conn.ExecContext(t.Context(), `UPDATE media_files SET added_at = '2026-01-02T00:00:00.000Z'
		WHERE movie_id = (SELECT id FROM movies WHERE tmdb_id = 9003)`); err != nil {
		t.Fatal(err)
	}
	id := listRowID(w.ID)
	tv := fmt.Sprintf("/api/libraries/%d/rows?arranged=true", e.tv)
	for _, c := range []struct {
		name, path string
		items      []int64
		line       string
	}{
		{"Home, without what Continue watching shows", "/api/home?arranged=true", []int64{9003, 9102, 9001}, "5 of 6 in your library"},
		{"Movies", "/api/libraries/1/rows?arranged=true", []int64{9003, 9002, 9001}, "3 of 4 in your library"},
		{"TV, everything in the library", tv, []int64{9102, 9101}, ""},
	} {
		row, items := e.row(c.path, id)
		if !slices.Equal(items, c.items) || row.Subtitle != c.line || row.Source != nil || row.SeeAll == nil || row.SeeAll.ListID != w.ID {
			t.Errorf("%s: %v %q %+v, want %v %q", c.name, items, row.Subtitle, row, c.items, c.line)
		}
	}
	var home, movies RowLayout
	e.admin(http.MethodGet, "/api/me/rows/home", "", &home)
	e.admin(http.MethodGet, "/api/me/rows/library-1", "", &movies)
	if got := layoutIDs(home); !slices.Equal(got[:3], []string{"continue", "followed", id}) {
		t.Errorf("Home's order: %v", got)
	}
	if got := layoutIDs(movies); got[0] != id {
		t.Errorf("Movies' order: %v", got)
	}
	for _, r := range home.Rows {
		if r.Title == "Halloween" && (r.Shown || r.Note != "None of these are in your library yet" || r.Owner != "me") {
			t.Errorf("a watchlist with nothing in the library: %+v", r)
		}
	}
	var got MediaListEntries
	e.admin(http.MethodGet, fmt.Sprintf("/api/lists/%d/entries", w.ID), "", &got)
	var page []int64
	for _, en := range got.Items {
		page = append(page, en.TMDBID)
	}
	if want := []int64{9003, 4242, 9102, 9002, 9001, 9101}; !slices.Equal(page, want) {
		t.Errorf("the page's order %v, want %v", page, want)
	}

	// Continue watching hidden: nothing is left out.
	var layout RowLayout
	hidden := fmt.Sprintf(`{"rows":[{"id":"continue","hidden":true},{"id":"followed"},{"id":%q}]}`, id)
	if res := e.admin(http.MethodPut, "/api/me/rows/home", hidden, &layout); res.StatusCode != http.StatusOK {
		t.Fatalf("hide Continue watching: %d %s", res.StatusCode, res.Body)
	}
	if _, items := e.row("/api/home?arranged=true", id); !slices.Equal(items, []int64{9003, 9102, 9002, 9001, 9101}) {
		t.Errorf("Continue watching hidden: %v", items)
	}
}

// Leaving out what Continue watching shows takes whole titles, any copy
// of them, before the row's 20 are cut.
func TestHomeLeavesOutContinueWatching(t *testing.T) {
	t.Parallel()
	ref := func(rank int64, show bool, id, lib int64) rankRef {
		return rankRef{rank: rank, show: show, id: id, libraryID: lib}
	}
	// data is a screen's data for watchlist 7 with refs, as loadListData
	// picks it.
	l := db.MediaList{ID: 7, SourceKind: "watchlist"}
	data := func(refs []rankRef, types ...string) listData {
		d := listData{matches: map[int64]listMatches{7: {refs: refs}}, types: types}
		d.ranks = map[int64][]rankRef{7: d.pick(refs, nil)}
		return d
	}
	continuing := []ContinueItem{{Kind: "movie", MovieID: new(int64(10))}, {Kind: "episode", ShowID: new(int64(20))}}
	for _, c := range []struct {
		name       string
		refs       []rankRef
		continuing []ContinueItem
		want       []int64
	}{
		{name: "a movie and a show whose episode is playing", refs: []rankRef{ref(1, false, 10, 1), ref(2, true, 20, 2), ref(3, false, 11, 1)},
			continuing: continuing, want: []int64{3}},
		{name: "a copy in another library", refs: []rankRef{ref(1, false, 12, 1), ref(1, false, 10, 3), ref(2, false, 11, 1)},
			continuing: continuing, want: []int64{2}},
		{name: "nothing playing", refs: []rankRef{ref(1, false, 10, 1), ref(2, true, 20, 2)}, want: []int64{1, 2}},
	} {
		var got []int64
		for _, r := range data(c.refs, "movie", "tv").without([]*db.MediaList{&l}, c.continuing).ranks[7] {
			got = append(got, r.rank)
		}
		if !slices.Equal(got, c.want) {
			t.Errorf("%s: %v, want %v", c.name, got, c.want)
		}
	}
	// A row of 25 with 6 playing still has 19 to draw.
	var refs []rankRef
	var playing []ContinueItem
	for i := range int64(25) {
		refs = append(refs, ref(i, false, 100+i, 1))
		if i%4 == 0 && len(playing) < 6 {
			playing = append(playing, ContinueItem{Kind: "movie", MovieID: new(100 + i)})
		}
	}
	if n := len(data(refs, "movie").without([]*db.MediaList{&l}, playing).ranks[7]); n != 19 {
		t.Errorf("25 with 6 playing: %d", n)
	}
}

// A watchlist's order: newest saved first, a title counting from when it
// arrived when that was after it was saved, its copies together.
func TestSavedOrder(t *testing.T) {
	t.Parallel()
	at := func(minute int) string { return fmt.Sprintf("2026-01-01T00:%02d:00.000Z", minute) }
	ref := func(rank int64, lib int64, minute int) rankRef {
		return rankRef{rank: rank, id: rank, libraryID: lib, at: at(minute)}
	}
	var twentyOne []rankRef
	for i := range int64(21) {
		twentyOne = append(twentyOne, ref(-i, 1, int(i)))
	}
	twentyOne[0].at = at(59) // the oldest saved has just arrived
	for _, c := range []struct {
		name string
		refs []rankRef
		want []int64
	}{
		{name: "newest saved first", refs: []rankRef{ref(-2, 1, 3), ref(-1, 1, 2), ref(0, 1, 1)}, want: []int64{-2, -1, 0}},
		{name: "an arrival goes first", refs: []rankRef{ref(-2, 1, 3), ref(-1, 1, 2), ref(0, 1, 9)}, want: []int64{0, -2, -1}},
		{name: "copies keep their first arrival and stay together", refs: []rankRef{ref(-1, 1, 2), ref(0, 1, 1), ref(0, 2, 9)}, want: []int64{-1, 0, 0}},
		{name: "equal times: the newer saved first", refs: []rankRef{ref(-1, 1, 1), ref(0, 1, 1)}, want: []int64{-1, 0}},
		{name: "a 21st title that arrives is in the row", refs: twentyOne, want: []int64{0}},
	} {
		got := savedOrder(slices.Clone(c.refs))
		var ranks []int64
		for _, r := range got[:min(len(got), len(c.want))] {
			ranks = append(ranks, r.rank)
		}
		if !slices.Equal(ranks, c.want) {
			t.Errorf("%s: %v, want %v", c.name, ranks, c.want)
		}
	}
}
