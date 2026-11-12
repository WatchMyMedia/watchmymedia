package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"reflect"
	"slices"
	"strings"
	"testing"
	"testing/synctest"

	"golang.org/x/time/rate"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/integrations"
	"watchmymedia/server/internal/integrations/arrtest"
	"watchmymedia/server/internal/lists"
	"watchmymedia/server/internal/lists/mdblisttest"
	"watchmymedia/server/internal/scanner"
	"watchmymedia/server/internal/tmdb/tmdbtest"
)

// listsEnv is a test server over seedRowsLibrary with fake TMDB and
// MDBList services and a member signed in beside the admin.
type listsEnv struct {
	*testEnv
	t        *testing.T
	tv       int64
	tmdb     *tmdbtest.Server
	mdb      *mdblisttest.Server
	memberID int64
	member   string // the member's bearer token
}

func newListsEnv(t *testing.T, adjust func(*Deps)) *listsEnv {
	t.Helper()
	fake, mdb := tmdbtest.New(t), mdblisttest.New(t)
	var authSvc *auth.Service
	srv := newTestServerWith(t, func(d *Deps) {
		authSvc = d.Auth
		d.TMDB = fake.Client()
		d.Lists = lists.New(lists.Options{Queries: d.Queries, Conn: d.Conn, TMDB: d.TMDB, Bus: d.Bus,
			MDBList: lists.NewMDBList("", lists.MDBListOptions{BaseURL: mdb.URL, Limiter: rate.NewLimiter(rate.Inf, 0)}),
			Changed: ListsChangedPublisher(d.Bus)})
		if adjust != nil {
			adjust(d)
		}
	})
	e := &listsEnv{testEnv: srv, t: t, tmdb: fake, mdb: mdb}
	e.tv = seedRowsLibrary(t, srv.conn)
	member, err := srv.q.CreateUser(t.Context(), db.CreateUserParams{Name: "member", PasswordHash: adminHash(), IsAdmin: 0})
	if err != nil {
		t.Fatal(err)
	}
	grant, err := authSvc.CreateSession(t.Context(), member.ID, auth.Client{Kind: auth.KindBrowser, Name: "member"})
	if err != nil {
		t.Fatal(err)
	}
	e.memberID, e.member = member.ID, grant.Token
	return e
}

// admin and asMember make a request as the admin or the member, decoding
// a 2xx reply into out.
func (e *listsEnv) admin(method, path, body string, out any) reply {
	e.t.Helper()
	zero(out)
	return doJSON(e.t, e.client, method, e.URL+path, body, out)
}

// zero empties out before a reply is decoded into it: decoding into a
// slice reuses its elements, which would keep fields the reply leaves out.
func zero(out any) {
	if out != nil {
		reflect.ValueOf(out).Elem().SetZero()
	}
}

func (e *listsEnv) asMember(method, path, body string, out any) reply {
	e.t.Helper()
	zero(out)
	// The server's own client carries the admin's cookie.
	c := &http.Client{Transport: e.Client().Transport}
	return doJSON(e.t, c, method, e.URL+path, body, out, "Authorization", "Bearer "+e.member)
}

func rowIDs(rows []ScreenRow) []string {
	out := make([]string, len(rows))
	for i, r := range rows {
		out[i] = r.ID
	}
	return out
}

func layoutIDs(l RowLayout) []string {
	out := make([]string, len(l.Rows))
	for i, r := range l.Rows {
		out[i] = r.ID
		if r.Hidden {
			out[i] = "-" + r.ID
		}
	}
	return out
}

func sameJSON(t *testing.T, what string, got, want any) {
	t.Helper()
	g, _ := json.Marshal(got)
	w, _ := json.Marshal(want)
	if string(g) != string(w) {
		t.Errorf("%s:\n got %s\nwant %s", what, g, w)
	}
}

// With arranged=true Home's rows are the fixed fields' items in the
// built-in order, the fixed arrays come back empty, and the hero and
// totals are as ever; so are a library's.
func TestArrangedRowsMatchTheFixedRows(t *testing.T) {
	t.Parallel()
	e := newListsEnv(t, nil)
	var fixed, arranged HomeData
	e.admin(http.MethodGet, "/api/home", "", &fixed)
	e.admin(http.MethodGet, "/api/home?arranged=true", "", &arranged)
	if want := []string{rowContinue, rowRecentlyAddedShows, rowRecentlyAired, rowRecentlyAddedMovies, rowRecentlyReleased}; !slices.Equal(rowIDs(arranged.Rows), want) {
		t.Fatalf("rows = %v, want %v", rowIDs(arranged.Rows), want)
	}
	if len(arranged.ContinueWatching)+len(arranged.RecentlyAddedShows)+len(arranged.RecentlyAired)+len(arranged.RecentlyAddedMovies)+len(arranged.RecentlyReleased) != 0 {
		t.Errorf("the fixed arrays aren't empty: %+v", arranged)
	}
	sameJSON(t, "hero", arranged.Hero, fixed.Hero)
	sameJSON(t, "totals", arranged.Totals, fixed.Totals)
	r := arranged.Rows
	sameJSON(t, "continue", r[0].Continue, fixed.ContinueWatching)
	sameJSON(t, "shows", r[1].Items, fixed.RecentlyAddedShows)
	sameJSON(t, "aired", r[2].Episodes, fixed.RecentlyAired)
	sameJSON(t, "movies", r[3].Items, fixed.RecentlyAddedMovies)
	sameJSON(t, "released", r[4].Items, fixed.RecentlyReleased)
	if r[0].Style != RowStyleContinue || r[2].Style != RowStyleEpisode || r[4].Style != RowStyleWide || r[3].Title != "Recently added · Movies" {
		t.Errorf("styles and titles = %+v", r)
	}
	if r[0].SeeAll != nil || r[3].SeeAll == nil || r[3].SeeAll.LibraryID != 1 || r[3].SeeAll.Sort != "added" || r[2].SeeAll.LibraryID != e.tv || r[2].SeeAll.Sort != "aired" {
		t.Errorf("see all = %+v %+v %+v", r[0].SeeAll, r[3].SeeAll, r[2].SeeAll)
	}

	for _, c := range []struct {
		lib  int64
		want []string
	}{{1, []string{rowRecentlyAdded, rowRecentlyReleased}}, {e.tv, []string{rowRecentlyAdded, rowRecentlyAired}}} {
		var fixedRows, arrangedRows LibraryRows
		e.admin(http.MethodGet, fmt.Sprintf("/api/libraries/%d/rows", c.lib), "", &fixedRows)
		e.admin(http.MethodGet, fmt.Sprintf("/api/libraries/%d/rows?arranged=true", c.lib), "", &arrangedRows)
		if !slices.Equal(rowIDs(arrangedRows.Rows), c.want) || len(arrangedRows.RecentlyAdded) != 0 {
			t.Fatalf("library %d rows = %v", c.lib, rowIDs(arrangedRows.Rows))
		}
		sameJSON(t, "recently added", arrangedRows.Rows[0].Items, fixedRows.RecentlyAdded)
		if c.lib == 1 {
			sameJSON(t, "recently released", arrangedRows.Rows[1].Items, fixedRows.RecentlyReleased)
		} else {
			sameJSON(t, "recently aired", arrangedRows.Rows[1].Episodes, fixedRows.RecentlyAired)
		}
		if l := arrangedRows.Rows[0].SeeAll; l == nil || l.LibraryID != c.lib || l.Sort != "added" {
			t.Errorf("library %d see all = %+v", c.lib, l)
		}
	}
}

// A person moves and hides rows; rows they left out keep their default
// place, unknown rows are refused, and their other devices hear of it. The
// household's default is what everyone who never saved an order follows.
func TestRowLayouts(t *testing.T) {
	t.Parallel()
	e := newListsEnv(t, nil)
	sub, cancel := e.bus.Subscribe()
	defer cancel()
	var l RowLayout
	e.admin(http.MethodGet, "/api/me/rows/home", "", &l)
	if want := []string{"continue", "recentlyAddedShows", "recentlyAired", "recentlyAddedMovies", "recentlyReleased"}; !slices.Equal(layoutIDs(l), want) || l.Customized || !l.Rows[0].Shown || l.Rows[0].Kind != rowKindBuiltin {
		t.Fatalf("default layout = %+v", l)
	}
	e.admin(http.MethodPut, "/api/me/rows/home", `{"rows":[{"id":"recentlyReleased"},{"id":"continue","hidden":true}]}`, &l)
	if want := []string{"recentlyReleased", "-continue", "recentlyAddedShows", "recentlyAired", "recentlyAddedMovies"}; !slices.Equal(layoutIDs(l), want) || !l.Customized || l.Rows[1].Shown {
		t.Fatalf("saved layout = %v %+v", layoutIDs(l), l)
	}
	select {
	case ev := <-sub:
		if ev.Type != RowsChangedEvent || ev.To == nil || ev.To.UserID != 1 || !ev.To.Private || ev.Data.(RowsChanged).Screen != "home" {
			t.Errorf("event = %+v", ev)
		}
	default:
		t.Error("no rows.changed")
	}
	var home HomeData
	e.admin(http.MethodGet, "/api/home?arranged=true", "", &home)
	if want := []string{"recentlyReleased", "recentlyAddedShows", "recentlyAired", "recentlyAddedMovies"}; !slices.Equal(rowIDs(home.Rows), want) {
		t.Errorf("home rows = %v", rowIDs(home.Rows))
	}
	for _, body := range []string{`{"rows":[{"id":"live"}]}`, `{"rows":[{"id":"list:99"}]}`, `{"rows":[{"id":"continue"},{"id":"continue"}]}`} {
		if res := e.admin(http.MethodPut, "/api/me/rows/home", body, nil); res.StatusCode != http.StatusUnprocessableEntity {
			t.Errorf("PUT %s = %d", body, res.StatusCode)
		}
	}
	if res := e.admin(http.MethodGet, "/api/me/rows/library-99", "", nil); res.StatusCode != http.StatusNotFound {
		t.Errorf("a library that doesn't exist = %d", res.StatusCode)
	}
	if res := e.admin(http.MethodGet, "/api/me/rows/library-x", "", nil); res.StatusCode != http.StatusUnprocessableEntity {
		t.Errorf("a screen that can't be = %d", res.StatusCode)
	}

	// The household's default: the member follows it, the admin keeps
	// their own order.
	if res := e.asMember(http.MethodPut, "/api/rows/defaults/home", `{"rows":[]}`, nil); res.StatusCode != http.StatusForbidden {
		t.Errorf("a member set the default: %d", res.StatusCode)
	}
	household := `{"rows":[{"id":"recentlyAddedMovies"},{"id":"recentlyReleased"},{"id":"continue"},{"id":"recentlyAddedShows","hidden":true},{"id":"recentlyAired"}]}`
	e.admin(http.MethodPut, "/api/rows/defaults/home", household, &l)
	if !l.Customized || l.Rows[0].ID != "recentlyAddedMovies" {
		t.Errorf("default = %+v", l)
	}
	for ev := range len(sub) {
		_ = ev
		if got := <-sub; got.Type == RowsChangedEvent && got.To != nil {
			t.Errorf("the default's event went to one person: %+v", got)
		}
	}
	e.asMember(http.MethodGet, "/api/me/rows/home", "", &l)
	if want := []string{"recentlyAddedMovies", "recentlyReleased", "continue", "-recentlyAddedShows", "recentlyAired"}; !slices.Equal(layoutIDs(l), want) || l.Customized {
		t.Errorf("member's layout = %v customized %v", layoutIDs(l), l.Customized)
	}
	// The admin's saved order stays theirs; the rows it leaves out go by
	// the household's order now.
	e.admin(http.MethodGet, "/api/me/rows/home", "", &l)
	if want := []string{"recentlyAddedMovies", "recentlyReleased", "-continue", "recentlyAddedShows", "recentlyAired"}; !slices.Equal(layoutIDs(l), want) || !l.Customized {
		t.Errorf("the admin's own order = %v", layoutIDs(l))
	}
	// Back to the household's, then to the built-in order.
	e.admin(http.MethodDelete, "/api/me/rows/home", "", &l)
	if l.Rows[0].ID != "recentlyAddedMovies" || l.Customized {
		t.Errorf("after reset = %v", layoutIDs(l))
	}
	e.admin(http.MethodDelete, "/api/rows/defaults/home", "", &l)
	e.asMember(http.MethodGet, "/api/me/rows/home", "", &l)
	if l.Rows[0].ID != "continue" {
		t.Errorf("after the default's reset = %v", layoutIDs(l))
	}
	// Loading the person's other preferences ignores the layout keys.
	var prefs Preferences
	if res := e.admin(http.MethodGet, "/api/me/preferences", "", &prefs); res.StatusCode != http.StatusOK || !prefs.AutoplayNext {
		t.Errorf("preferences = %d %+v", res.StatusCode, prefs)
	}
	// Deleting a library forgets its layouts.
	e.admin(http.MethodPut, fmt.Sprintf("/api/me/rows/library-%d", e.tv), `{"rows":[{"id":"recentlyAired"}]}`, nil)
	e.admin(http.MethodDelete, fmt.Sprintf("/api/libraries/%d", e.tv), "", nil)
	if _, err := e.q.GetUserPreference(t.Context(), db.GetUserPreferenceParams{UserID: 1, Key: fmt.Sprintf("rows.library.%d", e.tv)}); err == nil {
		t.Error("a deleted library's layout is kept")
	}
}

// An import naming the library's movies by IMDb ID and a show by its TMDB
// link makes a row at the top of Home: what the library has, in the
// list's order, marked watched, with how many of the list it has. Titles
// nothing matches are kept as not found. The list is the person's own.
func TestImportListRows(t *testing.T) {
	t.Parallel()
	e := newListsEnv(t, nil)
	e.tmdb.JSON("/tv/9102", map[string]any{"id": 9102, "name": "Harbour 1", "first_air_date": "2010-01-01", "poster_path": "/h1.jpg"})
	sub, cancel := e.bus.Subscribe()
	defer cancel()
	body := `{"name":"Mine","mediaType":"all","screen":"home","source":{"kind":"import","text":"tt9000001\ntt9000003\nhttps://www.themoviedb.org/tv/9102\ntt0000000\nNowhere (1950)"}}`
	var l MediaList
	if res := e.admin(http.MethodPost, "/api/lists", body, &l); res.StatusCode != http.StatusCreated {
		t.Fatalf("create = %d %s", res.StatusCode, res.Body)
	}
	if l.Entries != 5 || l.InLibrary != 3 || l.NotFound != 2 || l.Owner != "me" || !l.CanEdit || l.Source.Format != lists.FormatLines || l.Source.Text != "" || l.NextFetchAt != nil ||
		l.SourceName != "your list" || l.SourceURL != "" {
		t.Errorf("list = %+v", l)
	}
	if ev := <-sub; ev.Type != ListsChangedEvent || ev.To == nil || ev.To.UserID != 1 || !ev.To.Private {
		t.Errorf("event = %+v", ev)
	}
	// Titles the library has are named from it, without asking TMDB.
	if n := e.tmdb.Count("/tv/9102"); n != 0 {
		t.Errorf("TMDB was asked %d times for a show the library has", n)
	}
	var home HomeData
	e.admin(http.MethodGet, "/api/home?arranged=true", "", &home)
	row := home.Rows[0]
	if row.ID != fmt.Sprintf("list:%d", l.ID) || row.ListID != l.ID || row.Subtitle != "3 of 5 · From your list" || row.Source == nil || row.Source.Kind != "import" ||
		row.SeeAll == nil || row.SeeAll.Kind != "list" || row.SeeAll.ListID != l.ID {
		t.Fatalf("row = %+v", row)
	}
	var titles []string
	for _, it := range row.Items {
		titles = append(titles, it.Title)
	}
	if !slices.Equal(titles, []string{"Lantern 1", "Lantern 3", "Harbour 1"}) || !row.Items[1].Watched || row.Items[0].Progress == 0 {
		t.Errorf("items = %v, watched %v, progress %v", titles, row.Items[1].Watched, row.Items[0].Progress)
	}
	var entries MediaListEntries
	e.admin(http.MethodGet, fmt.Sprintf("/api/lists/%d/entries", l.ID), "", &entries)
	if entries.Total != 5 || entries.InLibrary != 3 || entries.Items[0].Item == nil || entries.Items[3].Found || entries.Items[3].Title != "tt0000000" || entries.Items[4].Title != "Nowhere" ||
		entries.Items[2].MediaType != "tv" || entries.Items[2].Title != "Harbour 1" || entries.Items[0].Title != "Lantern 1" || entries.Items[0].RequestState != "" {
		t.Errorf("entries = %+v", entries)
	}
	e.admin(http.MethodGet, fmt.Sprintf("/api/lists/%d/entries?offset=4&limit=10", l.ID), "", &entries)
	if len(entries.Items) != 1 || entries.Items[0].Rank != 5 {
		t.Errorf("page = %+v", entries.Items)
	}
	// The layout lists it as the admin's, first.
	var layout RowLayout
	e.admin(http.MethodGet, "/api/me/rows/home", "", &layout)
	if layout.Rows[0].ListID != l.ID || layout.Rows[0].Kind != rowKindList || layout.Rows[0].Owner != "me" || !layout.Rows[0].Shown {
		t.Errorf("layout = %+v", layout.Rows[0])
	}

	// The member sees none of it.
	var mine MediaListsOutput
	e.asMember(http.MethodGet, "/api/lists", "", &mine.Body)
	if len(mine.Body.Items) != 0 {
		t.Errorf("the member sees %+v", mine.Body.Items)
	}
	if res := e.asMember(http.MethodGet, fmt.Sprintf("/api/lists/%d", l.ID), "", nil); res.StatusCode != http.StatusNotFound {
		t.Errorf("the member read the admin's list: %d", res.StatusCode)
	}
	e.asMember(http.MethodGet, "/api/home?arranged=true", "", &home)
	if slices.Contains(rowIDs(home.Rows), row.ID) {
		t.Error("the admin's own list is on the member's Home")
	}
	if res := e.asMember(http.MethodPost, "/api/lists", strings.Replace(body, `"screen"`, `"shared":true,"screen"`, 1), nil); res.StatusCode != http.StatusForbidden {
		t.Errorf("a member made a list for everyone: %d", res.StatusCode)
	}

	// One for everyone: the member sees it, can't change it, and has it at
	// the top of Home.
	var shared MediaList
	e.admin(http.MethodPost, "/api/lists", strings.Replace(body, `"screen"`, `"shared":true,"screen"`, 1), &shared)
	if shared.Owner != "everyone" {
		t.Fatalf("shared list = %+v", shared)
	}
	for len(sub) > 0 {
		if ev := <-sub; ev.Type == ListsChangedEvent && ev.To != nil {
			t.Errorf("the household list's event went to one person: %+v", ev)
		}
	}
	e.asMember(http.MethodGet, "/api/lists", "", &mine.Body)
	if len(mine.Body.Items) != 1 || mine.Body.Items[0].CanEdit || mine.Body.Items[0].InLibrary != 3 {
		t.Errorf("the member's lists = %+v", mine.Body.Items)
	}
	if res := e.asMember(http.MethodPatch, fmt.Sprintf("/api/lists/%d", shared.ID), `{"name":"Ours"}`, nil); res.StatusCode != http.StatusForbidden {
		t.Errorf("the member renamed the household's list: %d", res.StatusCode)
	}
	e.asMember(http.MethodGet, "/api/home?arranged=true", "", &home)
	if home.Rows[0].ListID != shared.ID {
		t.Errorf("member's Home = %v", rowIDs(home.Rows))
	}
	// Renamed, and deleted: its row goes from every screen.
	var renamed MediaList
	e.admin(http.MethodPatch, fmt.Sprintf("/api/lists/%d", shared.ID), `{"name":"Ours","limit":2}`, &renamed)
	if renamed.Name != "Ours" || renamed.Entries != 2 || renamed.Limit != 2 {
		t.Errorf("renamed = %+v", renamed)
	}
	if res := e.admin(http.MethodDelete, fmt.Sprintf("/api/lists/%d", shared.ID), "", nil); res.StatusCode != http.StatusNoContent {
		t.Errorf("delete = %d", res.StatusCode)
	}
	e.asMember(http.MethodGet, "/api/home?arranged=true", "", &home)
	if len(home.Rows) == 0 || home.Rows[0].ListID != 0 {
		t.Errorf("after delete = %v", rowIDs(home.Rows))
	}
	// A list made on a library's screen holds its type.
	if res := e.admin(http.MethodPost, "/api/lists", strings.Replace(strings.Replace(body, `"home"`, `"library-1"`, 1), `"all"`, `"tv"`, 1), nil); res.StatusCode != http.StatusUnprocessableEntity {
		t.Errorf("a TV list on the movie screen = %d", res.StatusCode)
	}
}

// TMDB lists: the builder's sources, a preview served from a short cache,
// a chart fetched at once when made and fetched again on request; and
// with lists.enabled off, none of it.
func TestTMDBLists(t *testing.T) {
	t.Parallel()
	e := newListsEnv(t, nil)
	page := func(from int) []any {
		var out []any
		for i := range 20 {
			out = append(out, tmdbtest.Movie(from+i, fmt.Sprintf("Lantern %d", from+i-9001), "2000-01-01"))
		}
		return out
	}
	e.tmdb.Pages("/movie/popular", page(9001), page(9021))
	e.tmdb.JSON("/genre/movie/list", map[string]any{"genres": []any{map[string]any{"id": 18, "name": "Drama"}}})
	e.tmdb.JSON("/genre/tv/list", map[string]any{"genres": []any{map[string]any{"id": 35, "name": "Comedy"}}})
	var sources MediaListSources
	e.asMember(http.MethodGet, "/api/list-sources", "", &sources)
	ready := map[string]bool{}
	for _, s := range sources.Sources {
		ready[s.Kind] = s.Ready
		if s.Kind == lists.KindMDBList && s.Reason == "" {
			t.Error("MDBList has no reason it isn't ready")
		}
	}
	if !ready["tmdb"] || ready["mdblist"] || !ready["library"] || !ready["import"] || len(sources.MovieGenres) != 1 || sources.TVGenres[0].Name != "Comedy" || sources.MaxLists != 25 {
		t.Errorf("sources = %+v", sources)
	}
	preview := `{"mediaType":"movie","source":{"kind":"tmdb","chart":"popular"},"limit":30}`
	var p MediaListPreview
	for range 2 {
		e.asMember(http.MethodPost, "/api/lists/preview", preview, &p)
	}
	if p.Total != 30 || len(p.Items) != 30 || p.InLibrary != 5 || p.Items[0].Item == nil || p.Items[0].Item.Title != "Lantern 0" || p.Items[5].Item != nil {
		t.Errorf("preview = %d %d %d", p.Total, len(p.Items), p.InLibrary)
	}
	if n := e.tmdb.Count("/movie/popular"); n != 2 {
		t.Errorf("TMDB was asked %d times for two previews of two pages", n)
	}
	var l MediaList
	e.asMember(http.MethodPost, "/api/lists", `{"name":"Popular","mediaType":"movie","screen":"library-1","source":{"kind":"tmdb","chart":"popular"},"limit":30}`, &l)
	if l.Entries != 30 || l.InLibrary != 5 || l.FetchedAt == nil || l.NextFetchAt == nil || l.Screen != "library-1" || l.SourceName != "TMDB" || l.SourceURL != "https://www.themoviedb.org/movie" {
		t.Fatalf("list = %+v", l)
	}
	var rows LibraryRows
	e.asMember(http.MethodGet, "/api/libraries/1/rows?arranged=true", "", &rows)
	if rows.Rows[0].ListID != l.ID || rows.Rows[0].Subtitle != "5 of 30 · From TMDB" || rows.Rows[0].Source.URL != "https://www.themoviedb.org/movie" {
		t.Errorf("library rows = %+v", rows.Rows)
	}
	// On Home, where it wasn't made, it waits hidden in the editor.
	var layout RowLayout
	e.asMember(http.MethodGet, "/api/me/rows/home", "", &layout)
	if last := layout.Rows[len(layout.Rows)-1]; last.ListID != l.ID || !last.Hidden || last.Shown {
		t.Errorf("home layout = %+v", layout.Rows)
	}
	before := e.tmdb.Count("/movie/popular")
	var again MediaList
	if res := e.asMember(http.MethodPost, fmt.Sprintf("/api/lists/%d/refresh", l.ID), "", &again); res.StatusCode != http.StatusOK || e.tmdb.Count("/movie/popular") != before+2 {
		t.Errorf("refresh = %d, %d requests", res.StatusCode, e.tmdb.Count("/movie/popular")-before)
	}
	if res := e.asMember(http.MethodPost, "/api/lists", `{"name":"Bad","mediaType":"all","screen":"home","source":{"kind":"tmdb","chart":"upcoming"}}`, nil); res.StatusCode != http.StatusUnprocessableEntity {
		t.Errorf("an upcoming chart of both kinds = %d", res.StatusCode)
	}

	off := newListsEnv(t, func(d *Deps) {
		d.Lists = lists.New(lists.Options{Queries: d.Queries, Conn: d.Conn, TMDB: d.TMDB, Disabled: true})
	})
	off.asMember(http.MethodGet, "/api/list-sources", "", &sources)
	if sources.Sources[0].Ready || len(sources.MovieGenres) != 0 {
		t.Errorf("sources with lists off = %+v", sources)
	}
	if res := off.asMember(http.MethodPost, "/api/lists", `{"name":"Popular","mediaType":"movie","screen":"home","source":{"kind":"tmdb","chart":"popular"}}`, nil); res.StatusCode != http.StatusUnprocessableEntity {
		t.Errorf("a TMDB list with lists off = %d", res.StatusCode)
	}
	if res := off.asMember(http.MethodPost, "/api/lists", `{"name":"Mine","mediaType":"movie","screen":"home","source":{"kind":"import","text":"tt9000001"}}`, nil); res.StatusCode != http.StatusCreated {
		t.Errorf("an import with lists off = %d", res.StatusCode)
	}
}

// The MDBList key is checked before it is kept, shows only its last four
// characters, and makes MDBList ready; an MDBList list is then made from
// its link.
func TestMDBListKeyAndLists(t *testing.T) {
	t.Parallel()
	e := newListsEnv(t, nil)
	e.mdb.Add(mdblisttest.List{ID: 5, User: "critic", Slug: "best", Name: "Best", Items: []mdblisttest.Item{
		{Title: "Lantern 4", Year: 2004, MediaType: "movie", TMDB: 9005}, {Title: "Harbour 2", Year: 2010, MediaType: "show", TMDB: 9103},
		{Title: "Elsewhere", Year: 2001, MediaType: "movie", TMDB: 1},
	}})
	if res := e.admin(http.MethodPut, "/api/settings", `{"listsMdblistKey":"wrong"}`, nil); res.StatusCode != http.StatusUnprocessableEntity {
		t.Errorf("a wrong key = %d", res.StatusCode)
	}
	var s Settings
	e.admin(http.MethodPut, "/api/settings", fmt.Sprintf(`{"listsMdblistKey":%q}`, mdblisttest.Key), &s)
	if !s.ListsMDBListConfigured || s.ListsMDBListKeyHint != "…-key" {
		t.Errorf("settings = %v %q", s.ListsMDBListConfigured, s.ListsMDBListKeyHint)
	}
	if v, _ := e.q.GetSetting(t.Context(), "lists.mdblist.api_key"); v != mdblisttest.Key {
		t.Errorf("stored key = %q", v)
	}
	var l MediaList
	if res := e.asMember(http.MethodPost, "/api/lists", `{"name":"Best","mediaType":"all","screen":"home","source":{"kind":"mdblist","ref":"https://mdblist.com/lists/critic/best"}}`, &l); res.StatusCode != http.StatusCreated {
		t.Fatalf("create = %d %s", res.StatusCode, res.Body)
	}
	if l.Entries != 3 || l.InLibrary != 2 || l.SourceName != "MDBList" || l.SourceURL != "https://mdblist.com/lists/critic/best" {
		t.Errorf("list = %+v", l)
	}
	var home HomeData
	e.asMember(http.MethodGet, "/api/home?arranged=true", "", &home)
	if home.Rows[0].Subtitle != "2 of 3 · From MDBList" || home.Rows[0].Source.URL != "https://mdblist.com/lists/critic/best" {
		t.Errorf("row = %+v", home.Rows[0])
	}
	if res := e.asMember(http.MethodPost, "/api/lists", `{"name":"Gone","mediaType":"all","screen":"home","source":{"kind":"mdblist","ref":"critic/gone"}}`, nil); res.StatusCode != http.StatusUnprocessableEntity {
		t.Errorf("a list MDBList doesn't have = %d", res.StatusCode)
	}
	e.admin(http.MethodPut, "/api/settings", `{"listsMdblistKey":""}`, &s)
	if s.ListsMDBListConfigured {
		t.Error("the key wasn't cleared")
	}
}

// A library list follows its filter over the library as it is: unwatched
// movies, by title, on the movie library's screen.
func TestLibraryListRows(t *testing.T) {
	t.Parallel()
	e := newListsEnv(t, nil)
	var l MediaList
	if res := e.admin(http.MethodPost, "/api/lists", `{"name":"To watch","mediaType":"all","screen":"library-1","source":{"kind":"library","libraryId":1,"filter":"unwatched","sort":"title"}}`, &l); res.StatusCode != http.StatusCreated {
		t.Fatalf("create = %d %s", res.StatusCode, res.Body)
	}
	if l.MediaType != "movie" || l.Entries != 4 || l.InLibrary != 4 || l.NextFetchAt != nil || l.FetchedAt != nil {
		t.Errorf("list = %+v", l)
	}
	var rows LibraryRows
	e.admin(http.MethodGet, "/api/libraries/1/rows?arranged=true", "", &rows)
	row := rows.Rows[0]
	var titles []string
	for _, it := range row.Items {
		titles = append(titles, it.Title)
	}
	if row.ListID != l.ID || !slices.Equal(titles, []string{"Lantern 0", "Lantern 1", "Lantern 2", "Lantern 4"}) || row.Subtitle != "4 titles · From your library" {
		t.Errorf("row = %v %q", titles, row.Subtitle)
	}
	var entries MediaListEntries
	e.admin(http.MethodGet, fmt.Sprintf("/api/lists/%d/entries", l.ID), "", &entries)
	if entries.Total != 4 || entries.Items[3].Item == nil || entries.Items[3].Title != "Lantern 4" {
		t.Errorf("entries = %+v", entries)
	}
	if res := e.admin(http.MethodPost, fmt.Sprintf("/api/lists/%d/refresh", l.ID), "", nil); res.StatusCode != http.StatusUnprocessableEntity {
		t.Errorf("refreshing a library list = %d", res.StatusCode)
	}
}

// A title in two libraries shows once: from the library whose screen it
// is, else the first. A show the list names without an episode on disk
// isn't drawn.
func TestListRowsAcrossLibraries(t *testing.T) {
	t.Parallel()
	e := newListsEnv(t, nil)
	ctx := t.Context()
	res, err := e.conn.ExecContext(ctx, `INSERT INTO libraries (type, name, path) VALUES ('movie', 'More', '/m2')`)
	if err != nil {
		t.Fatal(err)
	}
	more, _ := res.LastInsertId()
	res, err = e.conn.ExecContext(ctx, `INSERT INTO movies (library_id, tmdb_id, title) VALUES (?, 9001, 'Lantern 0 (4K)')`, more)
	if err != nil {
		t.Fatal(err)
	}
	movie, _ := res.LastInsertId()
	if _, err := e.conn.ExecContext(ctx, `INSERT INTO media_files (library_id, path, size, mtime, status, parsed, added_at, movie_id) VALUES (?, '/m2/l.mkv', 1, '2020-01-01T00:00:00.000Z', 'present', '{}', '2020-01-01T00:00:00.000Z', ?)`, more, movie); err != nil {
		t.Fatal(err)
	}
	if _, err := e.conn.ExecContext(ctx, `INSERT INTO shows (library_id, tmdb_id, title) VALUES (?, 9999, 'Empty Harbour')`, e.tv); err != nil {
		t.Fatal(err)
	}
	e.tmdb.JSON("/movie/9001", map[string]any{"id": 9001, "title": "Lantern 0", "release_date": "2000-03-01"})
	e.tmdb.JSON("/tv/9999", map[string]any{"id": 9999, "name": "Empty Harbour", "first_air_date": "2012-01-01"})
	var l MediaList
	e.admin(http.MethodPost, "/api/lists", `{"name":"Mine","mediaType":"all","screen":"home","source":{"kind":"import","text":"https://www.themoviedb.org/movie/9001\nhttps://www.themoviedb.org/tv/9999"}}`, &l)
	if l.Entries != 2 || l.InLibrary != 1 {
		t.Fatalf("list = %+v", l)
	}
	var home HomeData
	e.admin(http.MethodGet, "/api/home?arranged=true", "", &home)
	if r := home.Rows[0]; len(r.Items) != 1 || r.Items[0].LibraryID != 1 || r.Subtitle != "1 of 2 · From your list" {
		t.Errorf("home row = %+v", r)
	}
	// Add it to the other library's screen.
	screen := fmt.Sprintf("library-%d", more)
	e.admin(http.MethodPut, "/api/me/rows/"+screen, fmt.Sprintf(`{"rows":[{"id":"list:%d"}]}`, l.ID), nil)
	var rows LibraryRows
	e.admin(http.MethodGet, fmt.Sprintf("/api/libraries/%d/rows?arranged=true", more), "", &rows)
	// Recently added keeps its default place, first, before the list.
	if ids := rowIDs(rows.Rows); !slices.Equal(ids, []string{rowRecentlyAdded, fmt.Sprintf("list:%d", l.ID)}) {
		t.Fatalf("library rows = %v", ids)
	}
	if r := rows.Rows[1]; r.Items[0].LibraryID != more || r.Items[0].Title != "Lantern 0 (4K)" || r.Subtitle != "1 of 1 · From your list" {
		t.Errorf("list row = %+v", r)
	}
}

// A person may make 25 lists of their own; past that, and past the
// server's 200, making one is refused with why.
func TestListLimits(t *testing.T) {
	t.Parallel()
	e := newListsEnv(t, nil)
	for i := range maxListsPerPerson {
		if _, err := e.q.CreateMediaList(t.Context(), db.CreateMediaListParams{OwnerID: &e.memberID, Screen: "home", Name: fmt.Sprint(i), SourceKind: "import", Params: "{}", MediaType: "all", EntryLimit: 50}); err != nil {
			t.Fatal(err)
		}
	}
	body := `{"name":"One more","mediaType":"all","screen":"home","source":{"kind":"import","text":"tt9000001"}}`
	if res := e.asMember(http.MethodPost, "/api/lists", body, nil); res.StatusCode != http.StatusConflict || !strings.Contains(string(res.Body), "25 lists") {
		t.Errorf("the 26th list = %d %s", res.StatusCode, res.Body)
	}
	for i := range maxListsOnServer - maxListsPerPerson {
		l, err := e.q.CreateMediaList(t.Context(), db.CreateMediaListParams{Screen: "home", Name: fmt.Sprint(i), SourceKind: "import", Params: "{}", MediaType: "all", EntryLimit: 50})
		if err != nil {
			t.Fatal(err)
		}
		// Forty of the household's lists have a title the library has.
		if i < 40 {
			tmdbID := int64(9001)
			if err := e.q.InsertMediaListEntry(t.Context(), db.InsertMediaListEntryParams{ListID: l.ID, Rank: 1, MediaType: "movie", TmdbID: &tmdbID, Title: "Lantern 0"}); err != nil {
				t.Fatal(err)
			}
		}
	}
	if res := e.admin(http.MethodPost, "/api/lists", body, nil); res.StatusCode != http.StatusConflict || !strings.Contains(string(res.Body), "200 lists") {
		t.Errorf("the server's 201st list = %d %s", res.StatusCode, res.Body)
	}
	// The screen draws at most 30 rows; the rest say why.
	var l RowLayout
	e.admin(http.MethodGet, "/api/me/rows/home", "", &l)
	shown, tooMany := 0, 0
	for _, r := range l.Rows {
		if r.Shown {
			shown++
		}
		if r.Note == "Not shown: too many rows" {
			tooMany++
		}
	}
	if shown != maxRowsOnScreen || tooMany != 40+5-maxRowsOnScreen {
		t.Errorf("%d rows shown, %d too many", shown, tooMany)
	}
	var home HomeData
	e.admin(http.MethodGet, "/api/home?arranged=true", "", &home)
	if len(home.Rows) != maxRowsOnScreen {
		t.Errorf("Home draws %d rows", len(home.Rows))
	}
}

// With a tuner on, Home has a live row, with no items, second by default;
// for a member Live TV is kept from, it isn't there, and a layout naming
// it is refused.
func TestLiveRow(t *testing.T) {
	t.Parallel()
	e := newListsEnv(t, nil)
	if _, err := e.q.CreateLiveSource(t.Context(), db.CreateLiveSourceParams{ID: "s", DeviceID: "1045B0E4", Name: "Tuner", Address: "http://tuner.test", Model: "HDHR4-2US", Firmware: "20260313", TunerCount: 2}); err != nil {
		t.Fatal(err)
	}
	var home HomeData
	e.admin(http.MethodGet, "/api/home?arranged=true", "", &home)
	if ids := rowIDs(home.Rows); len(ids) < 2 || ids[1] != rowLive || home.Rows[1].Style != RowStyleLive || len(home.Rows[1].Items) != 0 || home.Rows[1].Title != "Live TV" {
		t.Errorf("rows = %v", ids)
	}
	// Admins only, as by default: the member has no live row.
	e.asMember(http.MethodGet, "/api/home?arranged=true", "", &home)
	if slices.Contains(rowIDs(home.Rows), rowLive) {
		t.Errorf("member's rows = %v", rowIDs(home.Rows))
	}
	if res := e.asMember(http.MethodPut, "/api/me/rows/home", `{"rows":[{"id":"live"}]}`, nil); res.StatusCode != http.StatusUnprocessableEntity {
		t.Errorf("a member placed live: %d", res.StatusCode)
	}
	if err := e.q.SetSetting(t.Context(), db.SetSettingParams{Key: "livetv.access", Value: "everyone"}); err != nil {
		t.Fatal(err)
	}
	e.asMember(http.MethodPut, "/api/me/rows/home", `{"rows":[{"id":"live","hidden":true}]}`, nil)
	e.asMember(http.MethodGet, "/api/home?arranged=true", "", &home)
	if slices.Contains(rowIDs(home.Rows), rowLive) {
		t.Errorf("a hidden live row was sent: %v", rowIDs(home.Rows))
	}
}

// Where Radarr can take requests, a list's page says for each movie what
// Request would do: in the library, asked for already, or requestable;
// shows, which nothing can take, say nothing.
func TestListEntriesRequestState(t *testing.T) {
	t.Parallel()
	var svc *integrations.Service
	e := newListsEnv(t, func(d *Deps) {
		withIntegrations(d)
		svc = d.Integrations
	})
	app := arrtest.New(t, "radarr")
	name, key := "Radarr", app.Key
	if _, err := svc.Create(t.Context(), integrations.Input{Kind: "radarr", Name: &name, URL: &app.URL, APIKey: &key,
		Targets: &[]integrations.Target{{LibraryID: 1, RootFolder: "/movies", QualityProfileID: 1}}}); err != nil {
		t.Fatal(err)
	}
	if _, err := e.conn.ExecContext(t.Context(), `INSERT INTO requests (user_id, media_type, tmdb_id, title, status) VALUES (1, 'movie', 7002, 'Asked', 'pending'), (1, 'movie', 7003, 'Coming', 'approved')`); err != nil {
		t.Fatal(err)
	}
	for _, id := range []int{7001, 7002, 7003} {
		e.tmdb.JSON(fmt.Sprintf("/movie/%d", id), map[string]any{"id": id, "title": fmt.Sprintf("Film %d", id), "release_date": "2001-01-01"})
	}
	e.tmdb.JSON("/tv/9102", map[string]any{"id": 9102, "name": "Harbour 1", "first_air_date": "2010-01-01"})
	e.tmdb.JSON("/tv/9998", map[string]any{"id": 9998, "name": "Far Harbour", "first_air_date": "2011-01-01"})
	text := `tt9000000\nhttps://www.themoviedb.org/movie/7001\nhttps://www.themoviedb.org/movie/7002\nhttps://www.themoviedb.org/movie/7003\nhttps://www.themoviedb.org/tv/9102\nhttps://www.themoviedb.org/tv/9998\nNowhere (1950)`
	var l MediaList
	e.asMember(http.MethodPost, "/api/lists", `{"name":"Wish","mediaType":"all","screen":"home","source":{"kind":"import","text":"`+text+`"}}`, &l)
	var entries MediaListEntries
	e.asMember(http.MethodGet, fmt.Sprintf("/api/lists/%d/entries", l.ID), "", &entries)
	var states []string
	for _, it := range entries.Items {
		states = append(states, it.RequestState)
	}
	if want := []string{"available", "requestable", "requested", "downloading", "", "", ""}; !slices.Equal(states, want) || entries.Items[4].Item == nil || !entries.Items[5].Found {
		t.Errorf("request states = %q, want %q", states, want)
	}
}

// What lists match in the library is kept while the list job runs, and
// read again once the library changes.
func TestLibraryListFollowsTheLibrary(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		srv := startTestEnv(t, func(d *Deps) {
			d.Lists = lists.New(lists.Options{Queries: d.Queries, Conn: d.Conn, TMDB: d.TMDB, Bus: d.Bus})
			go d.Lists.Run(t.Context())
		}, func(h http.Handler) *httptest.Server { return httptest.NewTestServer(t, h) })
		seedRowsLibrary(t, srv.conn)
		var l MediaList
		doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/lists", `{"name":"All","mediaType":"movie","screen":"home","source":{"kind":"library","libraryId":1}}`, &l)
		count := func() int {
			var got MediaList
			doJSON(t, srv.client, http.MethodGet, fmt.Sprintf("%s/api/lists/%d", srv.URL, l.ID), "", &got)
			return got.Entries
		}
		if n := count(); n != 5 {
			t.Fatalf("entries = %d", n)
		}
		// And an import naming a film the library doesn't have yet.
		var imported MediaList
		doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/lists", `{"name":"Wanted","mediaType":"movie","screen":"home","source":{"kind":"import","text":"https://www.themoviedb.org/movie/9100\nhttps://www.themoviedb.org/movie/9001"}}`, &imported)
		matched := func() int {
			var home HomeData
			doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/home?arranged=true", "", &home)
			for _, r := range home.Rows {
				if r.ListID == imported.ID {
					return len(r.Items)
				}
			}
			return 0
		}
		if n := matched(); n != 1 {
			t.Fatalf("the import's row has %d items", n)
		}
		res, err := srv.conn.ExecContext(t.Context(), `INSERT INTO movies (library_id, tmdb_id, title) VALUES (1, 9100, 'Late Arrival')`)
		if err != nil {
			t.Fatal(err)
		}
		id, _ := res.LastInsertId()
		if _, err := srv.conn.ExecContext(t.Context(), `INSERT INTO media_files (library_id, path, size, mtime, status, parsed, added_at, movie_id) VALUES (1, '/m/late.mkv', 1, '2020-01-01T00:00:00.000Z', 'present', '{}', '2020-01-01T00:00:00.000Z', ?)`, id); err != nil {
			t.Fatal(err)
		}
		if n := count(); n != 5 {
			t.Errorf("before library.changed, entries = %d (not kept)", n)
		}
		if n := matched(); n != 1 {
			t.Errorf("before library.changed, the import's row has %d items (not kept)", n)
		}
		srv.bus.Publish(events.Event{Type: "library.changed", Data: scanner.LibraryChanged{LibraryID: 1}})
		synctest.Wait()
		if n := count(); n != 6 {
			t.Errorf("after library.changed, entries = %d", n)
		}
		if n := matched(); n != 2 {
			t.Errorf("after library.changed, the import's row has %d items", n)
		}
	})
}
