package api

import (
	"bufio"
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"testing/synctest"
	"time"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/backup"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/livetv/guide"
	"watchmymedia/server/internal/metadata"
	"watchmymedia/server/internal/netguard"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/probe"
	"watchmymedia/server/internal/scanner"
	"watchmymedia/server/internal/segments"
	"watchmymedia/server/internal/settings"
	"watchmymedia/server/internal/tmdb"
)

// testEnv is one test's server, with a database, event bus and data folder
// of its own, so tests can run in parallel. client is signed in as the
// admin, "admin" with password1.
type testEnv struct {
	*httptest.Server
	q       *db.Queries
	conn    *sql.DB
	client  *http.Client
	bus     *events.Bus
	dataDir string
	// api is the server's huma API, whose OpenAPI document lists every
	// operation with its metadata.
	api huma.API
}

func newTestServer(t *testing.T) *testEnv {
	t.Helper()
	return newTestServerWith(t, nil)
}

// newTestServerWith lets a test adjust Deps before the router is built.
func newTestServerWith(t *testing.T, adjust func(*Deps)) *testEnv {
	t.Helper()
	return startTestEnv(t, adjust, httptest.NewServer)
}

// adminHash is password1's hash, made once per run: Argon2 was most of
// each test's setup.
var adminHash = sync.OnceValue(func() string {
	h, err := auth.HashPassword("password1")
	if err != nil {
		panic(err)
	}
	return h
})

// startTestEnv builds the server with start, which is httptest.NewServer,
// or an in-memory server for tests inside a synctest bubble.
func startTestEnv(t *testing.T, adjust func(*Deps), start func(http.Handler) *httptest.Server) *testEnv {
	t.Helper()
	ctx := t.Context()
	conn := dbtest.OpenPath(t, filepath.Join(t.TempDir(), "test.db"))
	q := db.New(conn)
	lib, err := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "movie", Name: "Movies", Path: "/m"})
	if err != nil {
		t.Fatal(err)
	}
	probeJSON := `{"container":"matroska","durationMs":5000,"video":[{"codec":"h264","width":1920,"height":1080}],"audio":[],"subtitles":[]}`
	h264, mkv := "h264", "matroska"
	if _, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{
		LibraryID: lib.ID, Path: "/m/Heat (1995).mkv", Size: 10, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()),
		Parsed: `{"kind":"movie","title":"Heat","year":1995}`, Probe: &probeJSON, Container: &mkv, VideoCodec: &h264,
		LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1,
	}); err != nil {
		t.Fatal(err)
	}
	admin, err := q.CreateUser(ctx, db.CreateUserParams{Name: "admin", PasswordHash: adminHash(), IsAdmin: 1})
	if err != nil {
		t.Fatal(err)
	}
	authSvc := auth.New(q, time.Hour, false)
	env := &testEnv{q: q, conn: conn, bus: events.NewBus(), dataDir: t.TempDir()}
	deps := Deps{Version: "test", Queries: q, Bus: env.bus, Auth: authSvc, Settings: settings.New(q), TMDB: tmdb.New("", ""), Conn: conn, DataDir: env.dataDir, DefaultHWAccel: "auto"}
	if adjust != nil {
		adjust(&deps)
	}
	r, h := New(deps)
	env.api = h
	env.Server = start(r)
	t.Cleanup(env.Close)
	// Signed in without checking the password, which only the sign-in
	// tests need.
	grant, err := authSvc.CreateSession(ctx, admin.ID, auth.Client{Kind: auth.KindBrowser, Name: "test"})
	if err != nil {
		t.Fatal(err)
	}
	// An in-memory server starts, and has its URL, at its first Client().
	env.client = env.Client()
	jar, _ := cookiejar.New(nil)
	u, err := url.Parse(env.URL)
	if err != nil {
		t.Fatal(err)
	}
	jar.SetCookies(u, []*http.Cookie{{Name: auth.CookieName, Value: grant.Token, Path: "/", HttpOnly: true, SameSite: http.SameSiteLaxMode}}) //nolint:gosec // plain HTTP test server
	env.client.Jar = jar
	return env
}

// reply is a test request's answer, its body read whole.
type reply struct {
	StatusCode int
	Header     http.Header
	Body       []byte
}

// doJSON makes one request for a test: with c (nil sends no cookie or
// token), a JSON body ("" for none) and header as name, value pairs. A 2xx
// reply is decoded into out when out is not nil; any other reply to such a
// request is logged with its body, so the failing check after it says why.
func doJSON(t testing.TB, c *http.Client, method, url, body string, out any, header ...string) reply {
	t.Helper()
	var r io.Reader
	if body != "" {
		r = strings.NewReader(body)
	}
	req, err := http.NewRequestWithContext(t.Context(), method, url, r)
	if err != nil {
		t.Fatal(err)
	}
	if body != "" {
		req.Header.Set("Content-Type", "application/json")
	}
	for i := 0; i+1 < len(header); i += 2 {
		req.Header.Set(header[i], header[i+1])
	}
	if c == nil {
		c = http.DefaultClient
	}
	res, err := c.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	b, err := io.ReadAll(res.Body)
	_ = res.Body.Close()
	if err != nil {
		t.Fatal(err)
	}
	if out != nil {
		if res.StatusCode >= 300 {
			t.Logf("%s %s -> %d: %s", method, url, res.StatusCode, b)
		} else if err := json.Unmarshal(b, out); err != nil {
			t.Fatalf("%s %s: %v: %s", method, url, err, b)
		}
	}
	return reply{StatusCode: res.StatusCode, Header: res.Header, Body: b}
}

func TestHealth(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	res, err := srv.client.Get(srv.URL + "/api/health")
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		t.Fatalf("status = %d", res.StatusCode)
	}
	var body Health
	if err := json.NewDecoder(res.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if body.Status != "ok" || body.Version != "test" {
		t.Errorf("unexpected body %+v", body)
	}
}

func TestListLibraries(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	res, err := srv.client.Get(srv.URL + "/api/libraries")
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	var body LibraryList
	if err := json.NewDecoder(res.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if len(body.Items) != 1 || body.Items[0].Path != "/m" || body.Items[0].CreatedAt.IsZero() {
		t.Errorf("unexpected items %+v", body.Items)
	}
}

func TestGetLibraryNotFound(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	res, err := srv.client.Get(srv.URL + "/api/libraries/999")
	if err != nil {
		t.Fatal(err)
	}
	_ = res.Body.Close()
	if res.StatusCode != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", res.StatusCode)
	}
}

func TestOpenAPIServed(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	res, err := srv.client.Get(srv.URL + OpenAPIPath + ".json")
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		t.Fatalf("status = %d", res.StatusCode)
	}
	var doc struct {
		OpenAPI string         `json:"openapi"`
		Paths   map[string]any `json:"paths"`
	}
	if err := json.NewDecoder(res.Body).Decode(&doc); err != nil {
		t.Fatal(err)
	}
	if doc.OpenAPI == "" || doc.Paths["/api/libraries"] == nil {
		t.Errorf("unexpected openapi doc: version=%q paths=%d", doc.OpenAPI, len(doc.Paths))
	}
}

func TestListFilesAndDetail(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	// Seed one file directly.
	res, err := srv.client.Get(srv.URL + "/api/libraries")
	if err != nil {
		t.Fatal(err)
	}
	var libs LibraryList
	_ = json.NewDecoder(res.Body).Decode(&libs)
	_ = res.Body.Close()
	if len(libs.Items) != 1 || libs.Items[0].FileCount != 1 {
		t.Fatalf("libraries = %+v", libs.Items)
	}
	libID := libs.Items[0].ID

	res, err = srv.client.Get(srv.URL + fmt.Sprintf("/api/libraries/%d/files?status=present", libID))
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	var list MediaFileList
	if err := json.NewDecoder(res.Body).Decode(&list); err != nil {
		t.Fatal(err)
	}
	if list.Total != 1 || len(list.Items) != 1 || list.Items[0].RelativePath != "Heat (1995).mkv" || list.Items[0].Parsed.Title != "Heat" {
		t.Fatalf("list = %+v", list)
	}
	res, err = srv.client.Get(srv.URL + fmt.Sprintf("/api/files/%d", list.Items[0].ID))
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	var detail MediaFileDetail
	if err := json.NewDecoder(res.Body).Decode(&detail); err != nil {
		t.Fatal(err)
	}
	if detail.Probe == nil || detail.Probe.Video[0].Codec != "h264" || detail.VideoCodec != "h264" {
		t.Errorf("detail = %+v", detail)
	}
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+fmt.Sprintf("/api/libraries/%d/files?status=bogus", libID), "", nil).StatusCode; code != http.StatusUnprocessableEntity {
		t.Errorf("bad status filter -> %d, want 422", code)
	}
}

func TestEventsStream(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, srv.URL+"/api/events", nil)
	res, err := srv.client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	if ct := res.Header.Get("Content-Type"); !strings.HasPrefix(ct, "text/event-stream") {
		t.Fatalf("content-type = %q", ct)
	}
	lines := bufio.NewScanner(res.Body)
	// readUntil returns the data line that follows the named event.
	readUntil := func(event string) string {
		t.Helper()
		seen := false
		for lines.Scan() {
			line := lines.Text()
			if line == "event: "+event {
				seen = true
				continue
			}
			if seen && strings.HasPrefix(line, "data: ") {
				return line
			}
		}
		t.Fatalf("stream ended before %q: %v", event, lines.Err())
		return ""
	}
	// The connect ping means the handler has subscribed; publishing now cannot race it.
	readUntil("ping")
	srv.bus.Publish(events.Event{Type: "scan.finished", Data: scanner.ScanFinished{LibraryID: 1, Added: 2}})
	if got := readUntil("scan.finished"); !strings.Contains(got, `"added":2`) {
		t.Errorf("unexpected data: %q", got)
	}
}

// A person's favourites and reminders changing reach their devices as named
// events: the stream names events by their data's type, and an unnamed one
// reaches no client's listener.
func TestEventsNameLiveTVChanges(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, srv.URL+"/api/events", nil)
	res, err := srv.client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	lines := bufio.NewScanner(res.Body)
	next := func() string {
		t.Helper()
		for lines.Scan() {
			if line := lines.Text(); strings.HasPrefix(line, "event: ") {
				return strings.TrimPrefix(line, "event: ")
			}
		}
		t.Fatalf("stream ended: %v", lines.Err())
		return ""
	}
	if got := next(); got != "ping" {
		t.Fatalf("first event %q", got)
	}
	for _, kind := range []string{guide.FavouritesChangedEvent, guide.RemindersChangedEvent} {
		srv.bus.Publish(events.Event{Type: kind, Data: guide.ChangedData(kind), To: &events.Audience{UserID: 1, Private: true}})
		if got := next(); got != kind {
			t.Errorf("event %q, want %q", got, kind)
		}
	}
}

// After the connect ping the stream stays open with comment lines, which
// clients ignore, rather than more pings, which older apps took as changes.
// The stream runs on the fake clock of a synctest bubble, over an in-memory
// server, so 45 seconds of keep-alives take no time.
func TestEventsKeepAliveIsAComment(t *testing.T) {
	t.Parallel()
	synctest.Test(t, func(t *testing.T) {
		srv := startTestEnv(t, nil, func(h http.Handler) *httptest.Server { return httptest.NewTestServer(t, h) })
		ctx, cancel := context.WithCancel(t.Context())
		defer cancel()
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, srv.URL+"/api/events", nil)
		res, err := srv.client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		start := time.Now()
		lines := bufio.NewScanner(res.Body)
		pings, comments := 0, 0
		for comments < 3 && lines.Scan() {
			switch line := lines.Text(); {
			case line == "event: ping":
				pings++
			case strings.HasPrefix(line, ":"):
				comments++
			}
		}
		if pings != 1 || comments != 3 {
			t.Errorf("pings = %d, comment lines = %d (%v); want 1 ping, then comments", pings, comments, lines.Err())
		}
		if took := time.Since(start); took != 3*keepAliveEvery {
			t.Errorf("three keep-alives took %v, want %v", took, 3*keepAliveEvery)
		}
	})
}

func TestBrowseMoviesAndShows(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx := t.Context()
	q := srv.q
	lib, _ := q.GetLibrary(ctx, 1)
	movie, err := q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: lib.ID, TmdbID: 603, Title: "The Matrix", Overview: "o", Genres: `["Action"]`})
	if err != nil {
		t.Fatal(err)
	}
	f, _ := q.GetMediaFileByPath(ctx, "/m/Heat (1995).mkv")
	if err := q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &movie.ID, ID: f.ID}); err != nil {
		t.Fatal(err)
	}
	var list MovieList
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/libraries/1/movies", "", &list)
	if list.Total != 1 || list.Items[0].Title != "The Matrix" || list.Items[0].FileCount != 1 || list.Items[0].Genres[0] != "Action" {
		t.Fatalf("movies = %+v", list)
	}
	var detail MovieDetail
	doJSON(t, srv.client, http.MethodGet, srv.URL+fmt.Sprintf("/api/movies/%d", movie.ID), "", &detail)
	if len(detail.Files) != 1 || detail.Files[0].MatchStatus != "matched" || detail.Files[0].MovieID == nil {
		t.Errorf("detail = %+v", detail)
	}

	// Shows: one show, one season, one episode with a file linked.
	tv, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: "/t"})
	show, _ := q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: tv.ID, TmdbID: 1396, Title: "Breaking Bad", Genres: "[]"})
	season, _ := q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: show.ID, Number: 1, Name: "Season 1", EpisodeCount: 7})
	ep, _ := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: season.ID, ShowID: show.ID, Number: 1, Title: "Pilot"})
	tvFile, _ := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: tv.ID, Path: "/t/bb.mkv", Size: 1, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()),
		Parsed: `{"kind":"episode","title":"Breaking Bad","season":1,"episodes":[1]}`, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	_ = q.InsertEpisodeFile(ctx, db.InsertEpisodeFileParams{EpisodeID: ep.ID, MediaFileID: tvFile.ID})

	var shows ShowList
	doJSON(t, srv.client, http.MethodGet, srv.URL+fmt.Sprintf("/api/libraries/%d/shows", tv.ID), "", &shows)
	if shows.Total != 1 || shows.Items[0].EpisodeCount != 1 {
		t.Fatalf("shows = %+v", shows)
	}
	var sd SeasonDetail
	doJSON(t, srv.client, http.MethodGet, srv.URL+fmt.Sprintf("/api/shows/%d/seasons/1", show.ID), "", &sd)
	if sd.Show.Title != "Breaking Bad" || len(sd.Episodes) != 1 || len(sd.Episodes[0].Files) != 1 || sd.AvailableCount != 1 {
		t.Errorf("season = %+v", sd)
	}
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+fmt.Sprintf("/api/shows/%d/seasons/7", show.ID), "", nil).StatusCode; code != 404 {
		t.Errorf("missing season -> %d", code)
	}
	var libs LibraryList
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/libraries", "", &libs)
	if libs.Items[0].PendingCount != 0 || libs.Items[1].PendingCount != 1 {
		t.Errorf("pending counts = %+v", libs.Items)
	}
}

func TestRowsItemsAndHome(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx := t.Context()
	q := srv.q
	rel := "1999-03-30"
	movie, _ := q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: 1, TmdbID: 603, Title: "The Matrix", ReleaseDate: &rel, Overview: "o", Genres: `["Action"]`, Credits: "[]"})
	f, _ := q.GetMediaFileByPath(ctx, "/m/Heat (1995).mkv")
	_ = q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &movie.ID, ID: f.ID})

	tv, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: "/t"})
	status := "Returning Series"
	show, _ := q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: tv.ID, TmdbID: 1396, Title: "Breaking Bad", Status: &status, Genres: `["Drama"]`, Credits: "[]"})
	season, _ := q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: show.ID, Number: 1, Name: "Season 1", EpisodeCount: 3})
	past, past2 := "2008-01-20", "2008-01-27"
	future := time.Now().AddDate(0, 0, 7).Format("2006-01-02")
	ep1, _ := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: season.ID, ShowID: show.ID, Number: 1, Title: "Pilot", AirDate: &past})
	_, _ = q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: season.ID, ShowID: show.ID, Number: 2, Title: "Cat", AirDate: &past2})
	_, _ = q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: season.ID, ShowID: show.ID, Number: 3, Title: "Future", AirDate: &future})
	h := int64(1080)
	tvFile, _ := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: tv.ID, Path: "/t/bb.mkv", Size: 500, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()), Height: &h,
		Parsed: `{"kind":"episode","title":"Breaking Bad","season":1,"episodes":[1]}`, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	_ = q.InsertEpisodeFile(ctx, db.InsertEpisodeFileParams{EpisodeID: ep1.ID, MediaFileID: tvFile.ID})

	var items ItemList
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/libraries/1/items?sort=added", "", &items)
	if items.Total != 1 || items.Items[0].Kind != "movie" || items.Items[0].Title != "The Matrix" || !items.Items[0].IsNew || items.Items[0].ReleaseDate != rel {
		t.Fatalf("movie items = %+v", items)
	}
	if len(items.Facets.Genres) != 1 || items.Facets.Genres[0] != "Action" {
		t.Errorf("facets = %+v", items.Facets)
	}
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/libraries/1/items?filter=4k", "", &items)
	if items.Total != 0 {
		t.Errorf("4k filter should exclude an unprobed file: %+v", items)
	}
	doJSON(t, srv.client, http.MethodGet, srv.URL+fmt.Sprintf("/api/libraries/%d/items?filter=continuing&q=break", tv.ID), "", &items)
	if items.Total != 1 || items.Items[0].Quality != "1080p" || items.Items[0].EpisodeCount != 1 {
		t.Fatalf("show items = %+v", items)
	}

	var rows LibraryRows
	doJSON(t, srv.client, http.MethodGet, srv.URL+fmt.Sprintf("/api/libraries/%d/rows", tv.ID), "", &rows)
	// The show, then the fixture's file nobody has looked up, as a plain card with its name.
	if len(rows.RecentlyAdded) != 2 || rows.RecentlyAdded[0].Subtitle != "S01E01" ||
		rows.RecentlyAdded[1].Kind != "unmatched" || rows.RecentlyAdded[1].Title != "bb.mkv" || rows.RecentlyAdded[1].LibraryType != "tv" {
		t.Errorf("recently added shows = %+v", rows.RecentlyAdded)
	}
	// One episode added: the card names it, so the apps open its page.
	if c := rows.RecentlyAdded[0]; c.EpisodeID != ep1.ID || c.SeasonNumber == nil || *c.SeasonNumber != 1 {
		t.Errorf("one added episode = %d, season %v", c.EpisodeID, c.SeasonNumber)
	}
	if len(rows.RecentlyAired) != 2 || rows.RecentlyAired[0].EpisodeNumber != 2 || rows.RecentlyAired[0].HasFile || !rows.RecentlyAired[1].HasFile {
		t.Errorf("recently aired = %+v", rows.RecentlyAired)
	}
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/libraries/1/rows", "", &rows)
	if len(rows.RecentlyReleased) != 1 || len(rows.RecentlyAdded) != 1 {
		t.Errorf("movie rows = %+v", rows)
	}

	var home HomeData
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/home", "", &home)
	// An episode from 2008 is not new, so the hero is the newest movie.
	if home.Hero == nil || home.Hero.Kind != "movie" || *home.Hero.MovieID != movie.ID {
		t.Fatalf("hero = %+v", home.Hero)
	}
	if home.Totals.Movies != 1 || home.Totals.Shows != 1 || home.Totals.Episodes != 1 || home.Totals.Files != 2 || home.Totals.TotalSize != 510 {
		t.Errorf("totals = %+v", home.Totals)
	}
	if home.MovieLibraryID == nil || *home.MovieLibraryID != 1 || home.TVLibraryID == nil || *home.TVLibraryID != tv.ID {
		t.Errorf("library ids = %v %v", home.MovieLibraryID, home.TVLibraryID)
	}

	var sd SeasonDetail
	doJSON(t, srv.client, http.MethodGet, srv.URL+fmt.Sprintf("/api/shows/%d/seasons/1", show.ID), "", &sd)
	if !sd.Episodes[0].HasFile || !sd.Episodes[0].Aired || sd.Episodes[1].HasFile || !sd.Episodes[1].Aired || sd.Episodes[2].Aired {
		t.Errorf("episode flags = %+v", sd.Episodes)
	}
	var showDetail ShowDetail
	doJSON(t, srv.client, http.MethodGet, srv.URL+fmt.Sprintf("/api/shows/%d", show.ID), "", &showDetail)
	if showDetail.TotalEpisodes != 3 || showDetail.FileCount != 1 || showDetail.Quality != "1080p" || showDetail.TotalSize != 500 {
		t.Errorf("show detail = %+v", showDetail)
	}

	// A second episode added the same day: the card is the show's again.
	ep2File, _ := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: tv.ID, Path: "/t/bb2.mkv", Size: 500, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()), Height: &h,
		Parsed: `{"kind":"episode","title":"Breaking Bad","season":1,"episodes":[2]}`, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	_ = q.InsertEpisodeFile(ctx, db.InsertEpisodeFileParams{EpisodeID: sd.Episodes[1].ID, MediaFileID: ep2File.ID})
	var again LibraryRows
	doJSON(t, srv.client, http.MethodGet, srv.URL+fmt.Sprintf("/api/libraries/%d/rows", tv.ID), "", &again)
	if c := again.RecentlyAdded[0]; c.Subtitle != "2 episodes" || c.EpisodeID != 0 || c.SeasonNumber != nil {
		t.Errorf("two added episodes = %q, %d, season %v", c.Subtitle, c.EpisodeID, c.SeasonNumber)
	}
}

// The show page's quality, HDR, size and date added come from that show's
// present files alone, and agree with its card in the library.
func TestShowDetailFileStats(t *testing.T) {
	t.Parallel()
	var conn *sql.DB
	srv := newTestServerWith(t, func(d *Deps) { conn = d.Conn })
	exec := func(q string, args ...any) int64 {
		res, err := conn.ExecContext(t.Context(), q, args...)
		if err != nil {
			t.Fatal(err)
		}
		id, _ := res.LastInsertId()
		return id
	}
	tv := exec(`INSERT INTO libraries (type, name, path) VALUES ('tv', 'TV', '/t')`)
	show := func(tmdbID int, title string) int64 {
		return exec(`INSERT INTO shows (library_id, tmdb_id, title, genres) VALUES (?, ?, ?, '[]')`, tv, tmdbID, title)
	}
	episode := func(show, number int64) int64 {
		var season int64
		if err := conn.QueryRowContext(t.Context(), `SELECT id FROM seasons WHERE show_id = ?`, show).Scan(&season); err != nil {
			season = exec(`INSERT INTO seasons (show_id, number, episode_count) VALUES (?, 1, 3)`, show)
		}
		return exec(`INSERT INTO episodes (season_id, show_id, number, title) VALUES (?, ?, ?, 'e')`, season, show, number)
	}
	file := func(ep int64, status string, size, height int, hdr, added string) {
		f := exec(`INSERT INTO media_files (library_id, path, size, mtime, status, parsed, height, hdr, added_at, match_status)
			VALUES (?, ?, ?, ?, ?, '{}', ?, ?, ?, 'matched')`, tv, fmt.Sprintf("/t/%d-%s-%d", ep, status, size), size, added, status, height, hdr, added)
		exec(`INSERT INTO episode_files (episode_id, media_file_id) VALUES (?, ?)`, ep, f)
	}
	a, b, empty := show(1, "Andor"), show(2, "Bluey"), show(3, "Cheers")
	file(episode(a, 1), "present", 500, 1080, "", "2026-01-02T00:00:00.000Z")
	file(episode(a, 2), "present", 700, 2160, "hdr10", "2026-03-04T00:00:00.000Z")
	file(episode(a, 3), "missing", 9999, 4320, "dolbyvision", "2026-05-06T00:00:00.000Z")
	file(episode(b, 1), "present", 300, 720, "", "2025-12-01T00:00:00.000Z")

	var items ItemList
	doJSON(t, srv.client, http.MethodGet, srv.URL+fmt.Sprintf("/api/libraries/%d/items", tv), "", &items)
	cards := map[int64]BrowseItem{}
	for _, it := range items.Items {
		cards[it.ID] = it
	}
	for _, c := range []struct {
		id      int64
		quality string
		hdr     bool
		size    int64
		added   string
	}{
		{a, "4K", true, 1200, "2026-03-04"},
		{b, "720p", false, 300, "2025-12-01"},
		{empty, "", false, 0, ""},
	} {
		var d ShowDetail
		doJSON(t, srv.client, http.MethodGet, srv.URL+fmt.Sprintf("/api/shows/%d", c.id), "", &d)
		added := ""
		if d.AddedAt != nil {
			added = d.AddedAt.UTC().Format("2006-01-02")
		}
		if d.Quality != c.quality || d.HDR != c.hdr || d.TotalSize != c.size || added != c.added {
			t.Errorf("show %d detail = %s hdr=%v size=%d added=%q, want %s %v %d %q", c.id, d.Quality, d.HDR, d.TotalSize, added, c.quality, c.hdr, c.size, c.added)
		}
		if card, ok := cards[c.id]; ok && (card.Quality != d.Quality || card.HDR != d.HDR || card.TotalSize != d.TotalSize) {
			t.Errorf("show %d card %s/%v/%d differs from detail %s/%v/%d", c.id, card.Quality, card.HDR, card.TotalSize, d.Quality, d.HDR, d.TotalSize)
		}
	}

	// An episode whose only file went missing is missing, as the season counts it.
	var sd SeasonDetail
	doJSON(t, srv.client, http.MethodGet, srv.URL+fmt.Sprintf("/api/shows/%d/seasons/1", a), "", &sd)
	if sd.AvailableCount != 2 || len(sd.Episodes) != 3 || sd.Episodes[2].HasFile || len(sd.Episodes[2].Files) != 0 {
		t.Errorf("season = %d available, episode 3 hasFile=%v files=%d", sd.AvailableCount, sd.Episodes[2].HasFile, len(sd.Episodes[2].Files))
	}
}

func TestPlaybackDecisionAndStream(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx := t.Context()
	q := srv.q
	// A real file on disk so the stream route can serve ranges.
	dir := t.TempDir()
	path := filepath.Join(dir, "Clip (2020).mp4")
	content := []byte("0123456789abcdef")
	if err := os.WriteFile(path, content, 0o600); err != nil {
		t.Fatal(err)
	}
	probeJSON := `{"container":"mov","durationMs":5000,"video":[{"index":0,"codec":"h264","width":1280,"height":720}],"audio":[{"index":1,"codec":"aac","channels":2,"default":true}],"subtitles":[{"index":2,"codec":"subrip","language":"eng"}]}`
	f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: srv.fileLibrary(t, path), Path: path, Size: 16, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()), Parsed: "{}",
		Probe: &probeJSON, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	body := `{"profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"],"subtitleFormats":["webvtt"]}}`
	res, err := srv.client.Post(srv.URL+fmt.Sprintf("/api/files/%d/playback", f.ID), "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	var d playback.Decision
	if err := json.NewDecoder(res.Body).Decode(&d); err != nil {
		t.Fatal(err)
	}
	if res.StatusCode != 200 || d.Mode != playback.ModeDirect || d.URL != fmt.Sprintf("/api/files/%d/stream", f.ID) || d.MimeType != "video/mp4" {
		t.Fatalf("decision = %d %+v", res.StatusCode, d)
	}
	if len(d.Subtitles) != 1 || d.Subtitles[0].URL != fmt.Sprintf("/api/files/%d/subtitles/s2.vtt", f.ID) {
		t.Errorf("subtitles = %+v", d.Subtitles)
	}
	// Range request on the stream route.
	req, _ := http.NewRequestWithContext(t.Context(), http.MethodGet, srv.URL+d.URL, nil)
	req.Header.Set("Range", "bytes=4-7")
	res2, err := srv.client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res2.Body.Close()
	got, _ := io.ReadAll(res2.Body)
	if res2.StatusCode != http.StatusPartialContent || string(got) != "4567" || res2.Header.Get("Content-Type") != "video/mp4" {
		t.Errorf("range -> %d %q %s", res2.StatusCode, got, res2.Header.Get("Content-Type"))
	}
	// A client without mp4 support gets a remux, which needs the transcoder (nil here -> 503).
	body = `{"profile":{"containers":["webm"],"videoCodecs":["h264"],"audioCodecs":["aac"],"subtitleFormats":[]}}`
	res3 := doJSON(t, srv.client, http.MethodPost, srv.URL+fmt.Sprintf("/api/files/%d/playback", f.ID), body, nil)
	if res3.StatusCode != http.StatusServiceUnavailable {
		t.Errorf("remux without transcoder -> %d, want 503", res3.StatusCode)
	}
}

func TestAuthAndWatch(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx := t.Context()
	q := srv.q
	// Unauthenticated access is refused; public routes still work.
	anon := &http.Client{}
	res := doJSON(t, anon, http.MethodGet, srv.URL+"/api/libraries", "", nil)
	if res.StatusCode != http.StatusUnauthorized {
		t.Errorf("anonymous -> %d", res.StatusCode)
	}
	var status AuthStatus
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/auth/status", "", &status).StatusCode; code != 200 || status.SetupRequired || status.User == nil || status.User.Name != "admin" {
		t.Errorf("status = %+v", status)
	}
	res = doJSON(t, anon, http.MethodPost, srv.URL+"/api/setup", `{"name":"x","password":"password1"}`, nil)
	if res.StatusCode != http.StatusConflict {
		t.Errorf("setup after setup -> %d", res.StatusCode)
	}
	res = doJSON(t, anon, http.MethodPost, srv.URL+"/api/auth/login", `{"name":"admin","password":"wrong1"}`, nil)
	if res.StatusCode != http.StatusUnauthorized {
		t.Errorf("bad login -> %d", res.StatusCode)
	}
	// Repeated failures for one name are throttled with a 429 and Retry-After.
	for range 6 {
		res = doJSON(t, anon, http.MethodPost, srv.URL+"/api/auth/login", `{"name":"ghost","password":"wrong1"}`, nil)
	}
	res = doJSON(t, anon, http.MethodPost, srv.URL+"/api/auth/login", `{"name":"ghost","password":"wrong1"}`, nil)
	if res.StatusCode != http.StatusTooManyRequests || res.Header.Get("Retry-After") == "" {
		t.Errorf("throttled login -> %d, Retry-After %q", res.StatusCode, res.Header.Get("Retry-After"))
	}
	// Users admin.
	res = doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/users", `{"name":"kid","password":"kidpass1"}`, nil)
	if res.StatusCode != http.StatusCreated {
		t.Errorf("create user -> %d", res.StatusCode)
	}
	var users UserList
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/users", "", &users)
	if len(users.Items) != 2 {
		t.Errorf("users = %+v", users.Items)
	}

	// Watch progress on a matched movie file.
	movie, _ := q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: 1, TmdbID: 603, Title: "The Matrix", Overview: "o", Genres: "[]", Credits: "[]"})
	f, _ := q.GetMediaFileByPath(ctx, "/m/Heat (1995).mkv")
	_ = q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &movie.ID, ID: f.ID})
	put := func(body string) WatchState {
		req, _ := http.NewRequestWithContext(t.Context(), http.MethodPut, srv.URL+fmt.Sprintf("/api/files/%d/progress", f.ID), strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		res, err := srv.client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		var w WatchState
		if res.StatusCode != 200 {
			b, _ := io.ReadAll(res.Body)
			t.Fatalf("progress -> %d %s", res.StatusCode, b)
		}
		_ = json.NewDecoder(res.Body).Decode(&w)
		return w
	}
	w := put(`{"positionMs":60000,"durationMs":600000}`)
	if w.PositionMs != 60000 || w.Completed {
		t.Errorf("progress = %+v", w)
	}
	var home HomeData
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/home", "", &home)
	if len(home.ContinueWatching) != 1 || home.ContinueWatching[0].Title != "The Matrix" || home.ContinueWatching[0].PositionMs != 60000 {
		t.Errorf("continue watching = %+v", home.ContinueWatching)
	}
	var md MovieDetail
	doJSON(t, srv.client, http.MethodGet, srv.URL+fmt.Sprintf("/api/movies/%d", movie.ID), "", &md)
	if md.Watch == nil || md.Watch.PositionMs != 60000 {
		t.Errorf("movie watch = %+v", md.Watch)
	}
	var items ItemList
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/libraries/1/items", "", &items)
	if items.Items[0].Watched || items.Items[0].Progress < 0.09 || items.Items[0].Progress > 0.11 {
		t.Errorf("item progress = %+v", items.Items[0])
	}
	// Past 92% it is watched and leaves continue watching; unwatched filter hides it.
	w = put(`{"positionMs":590000,"durationMs":600000}`)
	if !w.Completed {
		t.Errorf("should be completed: %+v", w)
	}
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/home", "", &home)
	if len(home.ContinueWatching) != 0 {
		t.Errorf("continue watching after completion = %+v", home.ContinueWatching)
	}
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/libraries/1/items?filter=unwatched", "", &items)
	if items.Total != 0 {
		t.Errorf("unwatched filter = %+v", items.Items)
	}
	// Unmark.
	doJSON(t, srv.client, http.MethodDelete, srv.URL+fmt.Sprintf("/api/watch/movie/%d/watched", movie.ID), "", nil)
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/libraries/1/items?filter=unwatched", "", &items)
	if items.Total != 1 {
		t.Errorf("after unmark = %+v", items.Items)
	}
	// Search.
	var sr SearchResults
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/search?q=matr", "", &sr)
	if len(sr.Movies) != 1 || sr.Movies[0].Title != "The Matrix" {
		t.Errorf("search = %+v", sr)
	}
	// The other user sees no progress.
	kidJar, _ := cookiejar.New(nil)
	kid := &http.Client{Jar: kidJar}
	doJSON(t, kid, http.MethodPost, srv.URL+"/api/auth/login", `{"name":"kid","password":"kidpass1"}`, nil)
	var kidMovie MovieDetail
	doJSON(t, kid, http.MethodGet, srv.URL+fmt.Sprintf("/api/movies/%d", movie.ID), "", &kidMovie)
	if kidMovie.Watch != nil {
		t.Errorf("kid sees admin's progress: %+v", kidMovie.Watch)
	}
	res = doJSON(t, kid, http.MethodGet, srv.URL+"/api/users", "", nil)
	if res.StatusCode != http.StatusForbidden {
		t.Errorf("non-admin listing users -> %d", res.StatusCode)
	}
	// Logout clears the session.
	res = doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/auth/logout", "", nil)
	res = doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/libraries", "", nil)
	if res.StatusCode != http.StatusUnauthorized {
		t.Errorf("after logout -> %d", res.StatusCode)
	}
}

func TestSettingsLibrariesAndBackups(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	var st Settings
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/settings", "", &st)
	if st.TMDBConfigured || st.HWAccel != "auto" || st.BackupKeep != 7 || st.DataDir != srv.dataDir {
		t.Errorf("settings = %+v", st)
	}
	// Library CRUD with a real folder.
	dir := t.TempDir()
	_ = os.WriteFile(filepath.Join(dir, "Clip (2020).mkv"), []byte("x"), 0o600)
	var check PathCheck
	req, _ := http.NewRequestWithContext(t.Context(), http.MethodPost, srv.URL+"/api/settings/check-path", strings.NewReader(fmt.Sprintf(`{"path":%q}`, dir)))
	req.Header.Set("Content-Type", "application/json")
	res, _ := srv.client.Do(req)
	_ = json.NewDecoder(res.Body).Decode(&check)
	_ = res.Body.Close()
	if !check.Exists || !check.Readable || check.VideoFiles != 1 || len(check.Sample) != 1 {
		t.Errorf("check = %+v", check)
	}
	res, _ = srv.client.Post(srv.URL+"/api/libraries", "application/json", strings.NewReader(fmt.Sprintf(`{"type":"tv","name":"Kids TV","path":%q}`, dir)))
	var lib Library
	_ = json.NewDecoder(res.Body).Decode(&lib)
	_ = res.Body.Close()
	if res.StatusCode != http.StatusCreated || lib.Type != "tv" || lib.Path != dir {
		t.Fatalf("create library -> %d %+v", res.StatusCode, lib)
	}
	if code := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/libraries", fmt.Sprintf(`{"type":"tv","name":"Dup","path":%q}`, dir), nil).StatusCode; code != http.StatusConflict {
		t.Errorf("duplicate path -> %d", code)
	}
	if code := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/libraries", `{"type":"tv","name":"Nope","path":"/definitely/not/here"}`, nil).StatusCode; code != http.StatusUnprocessableEntity {
		t.Errorf("missing folder -> %d", code)
	}
	req, _ = http.NewRequestWithContext(t.Context(), http.MethodPut, srv.URL+fmt.Sprintf("/api/libraries/%d", lib.ID), strings.NewReader(fmt.Sprintf(`{"name":"Renamed","path":%q}`, dir)))
	req.Header.Set("Content-Type", "application/json")
	res, _ = srv.client.Do(req)
	_ = json.NewDecoder(res.Body).Decode(&lib)
	_ = res.Body.Close()
	if lib.Name != "Renamed" {
		t.Errorf("rename -> %+v", lib)
	}
	req, _ = http.NewRequestWithContext(t.Context(), http.MethodDelete, srv.URL+fmt.Sprintf("/api/libraries/%d", lib.ID), nil)
	res, _ = srv.client.Do(req)
	_ = res.Body.Close()
	if res.StatusCode != http.StatusNoContent {
		t.Errorf("delete -> %d", res.StatusCode)
	}
	var browse BrowseResult
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/settings/browse?path="+dir, "", &browse)
	if browse.Path != dir || browse.Parent == "" {
		t.Errorf("browse = %+v", browse)
	}
	// Settings update: hwaccel and backup keep; TMDB key is verified against the (unset) base URL and rejected.
	req, _ = http.NewRequestWithContext(t.Context(), http.MethodPut, srv.URL+"/api/settings", strings.NewReader(`{"hwaccel":"software","backupKeep":3}`))
	req.Header.Set("Content-Type", "application/json")
	res, _ = srv.client.Do(req)
	_ = json.NewDecoder(res.Body).Decode(&st)
	_ = res.Body.Close()
	if st.HWAccel != "software" || st.BackupKeep != 3 {
		t.Errorf("after update = %+v", st)
	}
	// Backup now, listed afterwards, and downloadable.
	res, _ = srv.client.Post(srv.URL+"/api/backups", "application/json", nil)
	var info backup.Info
	_ = json.NewDecoder(res.Body).Decode(&info)
	_ = res.Body.Close()
	if res.StatusCode != http.StatusCreated || info.Size == 0 {
		t.Fatalf("backup -> %d %+v", res.StatusCode, info)
	}
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/settings", "", &st)
	if len(st.Backups) != 1 || st.Backups[0].Name != info.Name {
		t.Errorf("backups listed = %+v", st.Backups)
	}
	res, _ = srv.client.Get(srv.URL + "/api/backups/download")
	body, _ := io.ReadAll(res.Body)
	_ = res.Body.Close()
	if res.StatusCode != 200 || res.Header.Get("Content-Type") != "application/gzip" || len(body) < 100 {
		t.Errorf("download -> %d %s %d bytes", res.StatusCode, res.Header.Get("Content-Type"), len(body))
	}
}

func TestAdminNetworks(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, func(d *Deps) {
		trusted, _ := netguard.Parse([]string{"127.0.0.1", "::1"})
		admin, _ := netguard.Parse([]string{"local"})
		d.Net = netguard.New(trusted, admin)
	})
	internet := []string{"X-Forwarded-For", "203.0.113.9"}
	lan := []string{"X-Forwarded-For", "192.168.1.5"}

	// An admin signed in from the internet can browse but not administer.
	if res := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/libraries", "", nil, internet...); res.StatusCode != http.StatusOK {
		t.Errorf("libraries from internet -> %d", res.StatusCode)
	}
	if res := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/users", "", nil, internet...); res.StatusCode != http.StatusForbidden {
		t.Errorf("users from internet -> %d", res.StatusCode)
	}
	if res := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/settings", "", nil, internet...); res.StatusCode != http.StatusForbidden {
		t.Errorf("settings from internet -> %d", res.StatusCode)
	}
	if res := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/users", "", nil, lan...); res.StatusCode != http.StatusOK {
		t.Errorf("users from LAN through proxy -> %d", res.StatusCode)
	}
	// The status endpoint tells the UI which applies. A request from the
	// trusted proxy's address that names no client is that machine's own.
	for _, c := range []struct {
		h    []string
		want bool
	}{{internet, false}, {lan, true}, {nil, true}} {
		var st AuthStatus
		doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/auth/status", "", &st, c.h...)
		if st.AdminAllowed != c.want {
			t.Errorf("status via %v: adminAllowed = %v", c.h, st.AdminAllowed)
		}
	}
}

func TestSetupRefusedFromInternet(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, func(d *Deps) {
		admin, _ := netguard.Parse([]string{"192.0.2.0/24"}) // not the test client's loopback
		d.Net = netguard.New(nil, admin)
	})
	if err := srv.q.DeleteUser(t.Context(), 1); err != nil {
		t.Fatal(err)
	}
	res := doJSON(t, nil, http.MethodPost, srv.URL+"/api/setup", `{"name":"intruder","password":"password1"}`, nil)
	if res.StatusCode != http.StatusForbidden {
		t.Errorf("setup from outside admin networks -> %d", res.StatusCode)
	}
	if n, _ := srv.q.CountUsers(t.Context()); n != 0 {
		t.Errorf("%d users after refused setup", n)
	}
}

func TestSecurityHeaders(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, func(d *Deps) { d.HSTS = true })
	ui := doJSON(t, srv.client, http.MethodGet, srv.URL+"/", "", nil)
	if csp := ui.Header.Get("Content-Security-Policy"); !strings.Contains(csp, "frame-ancestors 'none'") {
		t.Errorf("UI CSP = %q", csp)
	}
	apiRes := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/health", "", nil)
	if apiRes.Header.Get("Content-Security-Policy") != "" {
		t.Error("CSP sent on an API response")
	}
	if apiRes.Header.Get("X-Accel-Buffering") != "" {
		t.Error("buffering turned off for an ordinary API response")
	}
	if res := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/files/1/stream", "", nil); res.Header.Get("X-Accel-Buffering") != "no" {
		t.Errorf("stream X-Accel-Buffering = %q", res.Header.Get("X-Accel-Buffering"))
	}
	for path, res := range map[string]reply{"/": ui, "/api/health": apiRes} {
		if res.Header.Get("X-Content-Type-Options") != "nosniff" || res.Header.Get("X-Frame-Options") != "DENY" ||
			res.Header.Get("Strict-Transport-Security") == "" {
			t.Errorf("%s headers = %v", path, res.Header)
		}
	}
}

func TestCompression(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	gz := []string{"Accept-Encoding", "gzip"}
	home := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/home", "", nil, gz...)
	if home.Header.Get("Content-Encoding") != "gzip" || !strings.Contains(home.Header.Get("Vary"), "Accept-Encoding") {
		t.Errorf("home Content-Encoding = %q, Vary = %q", home.Header.Get("Content-Encoding"), home.Header.Get("Vary"))
	}
	if res := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/home", "", nil, "Accept-Encoding", "identity"); res.Header.Get("Content-Encoding") != "" {
		t.Error("gzipped for a client that did not ask")
	}
	// Video goes out as it is, so byte ranges and sendfile keep working.
	if res := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/files/1/stream", "", nil, gz...); res.Header.Get("Content-Encoding") != "" {
		t.Errorf("stream Content-Encoding = %q", res.Header.Get("Content-Encoding"))
	}
	if res := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/home", "", nil, "Accept-Encoding", "gzip", "Range", "bytes=0-9"); res.Header.Get("Content-Encoding") != "" {
		t.Error("gzipped a range request")
	}
}

func TestMediaTokenAccess(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	var mt MediaToken
	res, err := srv.client.Post(srv.URL+"/api/auth/media-token", "application/json", nil)
	if err != nil {
		t.Fatal(err)
	}
	_ = json.NewDecoder(res.Body).Decode(&mt)
	_ = res.Body.Close()
	if res.StatusCode != http.StatusOK || mt.Token == "" || mt.Param != "access_token" {
		t.Fatalf("media token -> %d %+v", res.StatusCode, mt)
	}
	var session string
	for _, c := range srv.client.Jar.Cookies(res.Request.URL) {
		if c.Name == auth.CookieName {
			session = c.Value
		}
	}
	anon := &http.Client{}
	status := func(path string) int {
		res, err := anon.Get(srv.URL + path)
		if err != nil {
			t.Fatal(err)
		}
		_ = res.Body.Close()
		return res.StatusCode
	}
	// Media routes accept the media token (the file itself is missing here).
	if code := status("/api/files/1/stream?access_token=" + mt.Token); code == http.StatusUnauthorized {
		t.Errorf("stream with media token -> %d", code)
	}
	// Other routes do not, and session tokens never work in URLs.
	if code := status("/api/libraries?access_token=" + mt.Token); code != http.StatusUnauthorized {
		t.Errorf("API route with media token -> %d", code)
	}
	if code := status("/api/files/1/stream?access_token=" + session); code != http.StatusUnauthorized {
		t.Errorf("stream with session token in URL -> %d", code)
	}
	// A media token cannot mint more tokens.
	if code := doJSON(t, anon, http.MethodPost, srv.URL+"/api/auth/media-token?access_token="+mt.Token, "", nil).StatusCode; code != http.StatusUnauthorized {
		t.Errorf("media token minting -> %d", code)
	}
}

func TestTokenizePlaylist(t *testing.T) {
	t.Parallel()
	in := "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4.000000,\nseg00000.m4s\r\n#EXTINF:4.000000,\nseg00001.m4s?x=1\n#EXT-X-ENDLIST\n"
	got := string(tokenizePlaylist([]byte(in), "m1.abc.def"))
	for _, want := range []string{
		`#EXT-X-MAP:URI="init.mp4?access_token=m1.abc.def"`,
		"seg00000.m4s?access_token=m1.abc.def\n",
		"seg00001.m4s?x=1&access_token=m1.abc.def\n",
		"#EXTINF:4.000000,\n",
		"#EXT-X-ENDLIST\n",
	} {
		if !strings.Contains(got, want) {
			t.Errorf("missing %q in:\n%s", want, got)
		}
	}
}

// Recently added follows each file's own date, not the order a scan stored them.
func TestRecentlyAddedUsesFileDates(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx := t.Context()
	q := srv.q
	now := time.Now()
	add := func(title string, tmdb int64, path string, added time.Time) {
		m, err := q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: 1, TmdbID: tmdb, Title: title, Genres: "[]", Credits: "[]"})
		if err != nil {
			t.Fatal(err)
		}
		f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: srv.fileLibrary(t, path), Path: path, Size: 1, Mtime: db.FormatTime(added), AddedAt: db.FormatTime(added),
			Parsed: "{}", LastSeenAt: db.FormatTime(now), SeenInScan: 1})
		if err != nil {
			t.Fatal(err)
		}
		if err := q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &m.ID, ID: f.ID}); err != nil {
			t.Fatal(err)
		}
	}
	// Stored oldest-first by scan order, but with file dates the other way round.
	add("Alien", 348, "/m/Alien (1979).mkv", now.Add(-time.Hour))
	add("Brazil", 68, "/m/Brazil (1985).mkv", now.Add(-30*24*time.Hour))
	add("Casablanca", 289, "/m/Casablanca (1942).mkv", now.Add(-2*time.Hour))

	var rows LibraryRows
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/libraries/1/rows", "", &rows)
	var got []string
	for _, it := range rows.RecentlyAdded {
		if it.Kind != "unmatched" { // the fixture's file, added now and never looked up
			got = append(got, it.Title)
		}
	}
	if strings.Join(got, ",") != "Alien,Casablanca,Brazil" {
		t.Errorf("recently added = %v", got)
	}
	for _, it := range rows.RecentlyAdded {
		if it.Title == "Brazil" && it.IsNew {
			t.Error("a month-old file is marked new")
		}
	}
}

// Playback names the episode that follows in season order, skipping ones
// with no file and keeping specials apart from regular seasons.
func TestPlaybackNextEpisode(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx := t.Context()
	q := srv.q
	tv, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "Shows", Path: "/s"})
	show, _ := q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: tv.ID, TmdbID: 1399, Title: "Thrones", Genres: "[]", Credits: "[]"})
	still := "/still.jpg"
	probeJSON := `{"container":"matroska","durationMs":3000000,"video":[{"index":0,"codec":"h264","width":1280,"height":720}],"audio":[],"subtitles":[]}`
	file := func(name string) int64 {
		f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: tv.ID, Path: "/s/" + name, Size: 1, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()),
			Parsed: "{}", Probe: &probeJSON, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
		if err != nil {
			t.Fatal(err)
		}
		return f.ID
	}
	files := map[string]int64{}
	for _, sn := range []int64{0, 1, 2} {
		season, _ := q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: show.ID, Number: sn, Name: fmt.Sprintf("Season %d", sn), EpisodeCount: 3})
		for en := int64(1); en <= 3; en++ {
			code := fmt.Sprintf("S%02dE%02d", sn, en)
			ep, _ := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: season.ID, ShowID: show.ID, Number: en, Title: "Episode " + code, StillPath: &still})
			if code == "S01E02" || code == "S00E03" {
				continue // not on disk
			}
			files[code] = file(code + ".mkv")
			_ = q.InsertEpisodeFile(ctx, db.InsertEpisodeFileParams{EpisodeID: ep.ID, MediaFileID: files[code]})
		}
	}
	next := func(code string) string {
		body := `{"profile":{"containers":["mkv"],"videoCodecs":["h264"],"audioCodecs":["aac"]}}`
		res, err := srv.client.Post(srv.URL+fmt.Sprintf("/api/files/%d/playback", files[code]), "application/json", strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		var d playback.Decision
		if err := json.NewDecoder(res.Body).Decode(&d); err != nil || res.StatusCode != 200 {
			t.Fatalf("%s: %d %v", code, res.StatusCode, err)
		}
		if d.CreditsMs != 0 {
			t.Errorf("%s: credits %d without chapters", code, d.CreditsMs)
		}
		if d.NextEpisode == nil {
			return ""
		}
		n := d.NextEpisode
		if n.ShowTitle != "Thrones" || n.StillPath != still || n.FileID == 0 {
			t.Errorf("%s: next = %+v", code, n)
		}
		return fmt.Sprintf("S%02dE%02d", n.SeasonNumber, n.EpisodeNumber)
	}
	for code, want := range map[string]string{
		"S01E01": "S01E03", // E02 has no file
		"S01E03": "S02E01", // into the next season
		"S02E03": "",       // the last episode
		"S00E01": "S00E02", // specials lead to specials
		"S00E02": "",
	} {
		if got := next(code); got != want {
			t.Errorf("after %s: got %q, want %q", code, got, want)
		}
	}
}

func TestUnmatchedGroups(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx := t.Context()
	q := srv.q
	tv, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "Shows", Path: filepath.FromSlash("/s")})
	file := func(rel, parsed, status, candidates, errMsg string) int64 {
		f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: tv.ID, Path: filepath.FromSlash("/s/" + rel), Size: 1, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()),
			Parsed: parsed, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
		if err != nil {
			t.Fatal(err)
		}
		var cand, msg *string
		if candidates != "" {
			cand = &candidates
		}
		if errMsg != "" {
			msg = &errMsg
		}
		if err := q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: status, MatchCandidates: cand, MatchError: msg, ID: f.ID}); err != nil {
			t.Fatal(err)
		}
		return f.ID
	}
	twins := `[{"tmdbId":1,"title":"Blackwater Row","year":2016,"score":0.9},{"tmdbId":2,"title":"Blackwater Row","year":2022,"score":0.9}]`
	file("Blackwater Row/Season 01/S01E01.mkv", `{"kind":"episode","title":"Blackwater Row","season":1,"episodes":[1]}`, "unmatched", twins, "no confident match")
	file("Blackwater Row/Season 02/S02E01.mkv", `{"kind":"episode","title":"Blackwater Row","season":2,"episodes":[1]}`, "unmatched", "", "no confident match")
	file("Saltmarsh/S01E01.mkv", `{"kind":"episode","title":"Saltmarsh","season":1,"episodes":[1]}`, "failed", "", "timeout")
	file("Done/S01E01.mkv", `{"kind":"episode","title":"Done","season":1,"episodes":[1]}`, "matched", "", "")
	// Fallow: one episode already linked, so the folder's show is known.
	show, _ := q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: tv.ID, TmdbID: 77, Title: "Fallow", Genres: "[]", Credits: "[]"})
	season, _ := q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: show.ID, Number: 2})
	ep, _ := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: season.ID, ShowID: show.ID, Number: 1})
	linked := file("Fallow/Season 02/S02E01.mkv", `{"kind":"episode","title":"Fallow","season":2,"episodes":[1]}`, "matched", "", "")
	_ = q.InsertEpisodeFile(ctx, db.InsertEpisodeFileParams{EpisodeID: ep.ID, MediaFileID: linked})
	file("Fallow/Season 02/S02E11.mkv", `{"kind":"episode","title":"Fallow","season":2,"episodes":[11]}`, "unmatched", "", "no episode [11]")

	res, err := srv.client.Get(srv.URL + fmt.Sprintf("/api/libraries/%d/unmatched", tv.ID))
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	var body UnmatchedGroupList
	if err := json.NewDecoder(res.Body).Decode(&body); err != nil || res.StatusCode != 200 {
		t.Fatalf("%d %v", res.StatusCode, err)
	}
	if len(body.Groups) != 3 {
		t.Fatalf("groups = %+v", body.Groups)
	}
	g := body.Groups[0]
	if g.Kind != GroupShow || g.Folder != "Blackwater Row" || len(g.Files) != 2 || len(g.Seasons) != 2 || len(g.Candidates) != 2 ||
		!strings.Contains(g.Reason, "More than one show has this name") {
		t.Errorf("show group = %+v", g)
	}
	g = body.Groups[1]
	if g.Kind != GroupEpisodes || g.Show == nil || g.Show.Title != "Fallow" || len(g.Files) != 1 || !strings.Contains(g.Reason, "Fallow has no episodes") {
		t.Errorf("episodes group = %+v", g)
	}
	g = body.Groups[2]
	if g.Kind != GroupLookup || g.Folder != "Saltmarsh" || g.Reason != "TMDB did not answer: timeout" {
		t.Errorf("lookup group = %+v", g)
	}

	// Line-up and matching need TMDB.
	res, err = srv.client.Post(srv.URL+fmt.Sprintf("/api/libraries/%d/match-files", tv.ID), "application/json", strings.NewReader(`{"fileIds":[1],"tmdbId":1}`))
	if err != nil {
		t.Fatal(err)
	}
	_ = res.Body.Close()
	if res.StatusCode != http.StatusServiceUnavailable {
		t.Errorf("match without TMDB -> %d", res.StatusCode)
	}
}

// Two users can watch the same file at once: each request starts its own
// play, a seek replaces only the seeker's session, and nobody can read or
// stop someone else's session.
func TestPlaybackSessionsPerViewer(t *testing.T) {
	t.Parallel()
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not installed")
	}
	mgr := playback.NewManager(ffmpeg, filepath.Join(t.TempDir(), "transcode"), playback.Capabilities{}, time.Minute, 8, slog.New(slog.NewTextHandler(io.Discard, nil)))
	t.Cleanup(mgr.StopAll)
	srv := newTestServerWith(t, func(d *Deps) { d.Playback = mgr })
	ctx := t.Context()
	path := filepath.Join(t.TempDir(), "Clip (2020).mkv")
	if b, err := exec.Command(ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10:duration=20",
		"-f", "lavfi", "-i", "sine=frequency=440:duration=20", "-c:v", "mpeg4", "-c:a", "aac", "-shortest", path).CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg: %v\n%s", err, b)
	}
	probeJSON := `{"container":"matroska","durationMs":20000,"video":[{"index":0,"codec":"mpeg4","width":64,"height":64}],"audio":[{"index":1,"codec":"aac","channels":2,"default":true}],"subtitles":[]}`
	f, err := srv.q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: srv.fileLibrary(t, path), Path: path, Size: 1, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()),
		Parsed: "{}", Probe: &probeJSON, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/users", `{"name":"kid","password":"kidpass1"}`, nil)
	kidJar, _ := cookiejar.New(nil)
	kid := &http.Client{Jar: kidJar}
	res := doJSON(t, kid, http.MethodPost, srv.URL+"/api/auth/login", `{"name":"kid","password":"kidpass1"}`, nil)
	if res.StatusCode != http.StatusOK {
		t.Fatalf("kid login -> %d", res.StatusCode)
	}

	const remux, direct = `["mp4"]`, `["mkv"]` // the client's containers
	play := func(c *http.Client, containers, playID string) playback.Decision {
		t.Helper()
		body := `{"profile":{"containers":` + containers + `,"videoCodecs":["mpeg4"],"audioCodecs":["aac"]}`
		if playID != "" {
			body += `,"playId":"` + playID + `"`
		}
		res, err := c.Post(srv.URL+fmt.Sprintf("/api/files/%d/playback", f.ID), "application/json", strings.NewReader(body+`}`))
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		var d playback.Decision
		if err := json.NewDecoder(res.Body).Decode(&d); err != nil || res.StatusCode != 200 || len(d.PlayID) != 16 {
			t.Fatalf("playback: %d %v %+v", res.StatusCode, err, d)
		}
		return d
	}
	running := func(id string) bool {
		_, ok := mgr.Get(id)
		return ok
	}
	a := play(srv.client, remux, "")
	b := play(kid, remux, "")
	if a.PlayID == b.PlayID || a.SessionID == "" || b.SessionID == "" {
		t.Fatalf("plays %q and %q, sessions %q and %q", a.PlayID, b.PlayID, a.SessionID, b.SessionID)
	}
	if !running(a.SessionID) || !running(b.SessionID) {
		t.Fatal("the second viewer of the file stopped the first")
	}
	var adminActivity, viewerActivity LiveActivity
	if res := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/activity", "", &adminActivity); res.StatusCode != http.StatusOK || adminActivity.Transcoder.Cache == nil || !adminActivity.Transcoder.Cache.ScanComplete {
		t.Fatalf("admin cache telemetry: %d %+v", res.StatusCode, adminActivity.Transcoder.Cache)
	}
	var adminSettings Settings
	if res := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/settings", "", &adminSettings); res.StatusCode != http.StatusOK || adminSettings.Storage == nil || !adminSettings.Storage.ScanComplete {
		t.Fatalf("admin storage settings: %d %+v", res.StatusCode, adminSettings.Storage)
	}
	if res := doJSON(t, kid, http.MethodGet, srv.URL+"/api/activity", "", &viewerActivity); res.StatusCode != http.StatusOK || viewerActivity.Transcoder.Cache != nil {
		t.Fatalf("viewer cache telemetry: %d %+v", res.StatusCode, viewerActivity.Transcoder.Cache)
	}
	// A seek continues the play and replaces only its session.
	a2 := play(srv.client, remux, a.PlayID)
	if a2.PlayID != a.PlayID || running(a.SessionID) || !running(a2.SessionID) || !running(b.SessionID) {
		t.Errorf("after a seek: play %q, old %v, new %v, other viewer %v", a2.PlayID, running(a.SessionID), running(a2.SessionID), running(b.SessionID))
	}
	// Sending someone else's play ID does not touch their session.
	k := play(kid, remux, a.PlayID)
	if !running(a2.SessionID) || !running(k.SessionID) {
		t.Error("another user's request replaced the owner's session")
	}
	// Moving a play to direct play stops its transcode.
	if kd := play(kid, direct, k.PlayID); kd.Protocol != "file" || kd.PlayID != k.PlayID || running(k.SessionID) || !running(b.SessionID) {
		t.Errorf("direct play: %s %q, old session %v, other play %v", kd.Protocol, kd.PlayID, running(k.SessionID), running(b.SessionID))
	}
	// The kid can neither read nor stop the admin's session; the admin can.
	res = doJSON(t, kid, http.MethodGet, srv.URL+a2.URL, "", nil)
	if res.StatusCode != http.StatusNotFound {
		t.Errorf("other user's playlist -> %d, want 404", res.StatusCode)
	}
	stop := func(c *http.Client, session string) int {
		req, _ := http.NewRequestWithContext(t.Context(), http.MethodDelete, srv.URL+"/api/playback/"+session, nil)
		res, err := c.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		_ = res.Body.Close()
		return res.StatusCode
	}
	if code := stop(kid, a2.SessionID); code != http.StatusForbidden || !running(a2.SessionID) {
		t.Errorf("other user's stop -> %d, running %v", code, running(a2.SessionID))
	}
	if res := doJSON(t, srv.client, http.MethodGet, srv.URL+a2.URL, "", nil); res.StatusCode != http.StatusOK || !strings.Contains(string(res.Body), "#EXTM3U") {
		t.Errorf("own playlist -> %d %q", res.StatusCode, res.Body)
	}
	// Mutable event playlists are served from immutable snapshots, without a
	// session-wide Last-Modified value that could hide later publications.
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, srv.URL+a2.URL, nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("If-Modified-Since", time.Now().Add(24*time.Hour).UTC().Format(http.TimeFormat))
	conditional, err := srv.client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	_ = conditional.Body.Close()
	if conditional.StatusCode != http.StatusOK || conditional.Header.Get("Cache-Control") != "no-store" {
		t.Errorf("conditional playlist -> %d, cache %q", conditional.StatusCode, conditional.Header.Get("Cache-Control"))
	}
	if code := stop(srv.client, a2.SessionID); code != http.StatusNoContent || running(a2.SessionID) {
		t.Errorf("own stop -> %d, running %v", code, running(a2.SessionID))
	}
	// Play IDs are the server's own.
	if code := doJSON(t, srv.client, http.MethodPost, srv.URL+fmt.Sprintf("/api/files/%d/playback", f.ID),
		`{"profile":{"containers":["mkv"],"videoCodecs":["mpeg4"],"audioCodecs":["aac"]},"playId":"../x"}`, nil).StatusCode; code != http.StatusUnprocessableEntity {
		t.Errorf("malformed playId -> %d, want 422", code)
	}
}

// Chapter names become stored segments on first play, and a segment a
// person deleted stays deleted when the file is analysed again.
func TestPlaybackSegmentsFromChapters(t *testing.T) {
	t.Parallel()
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not installed")
	}
	var conn *sql.DB
	srv := newTestServerWith(t, func(d *Deps) { d.Prober, conn = &probe.Prober{}, d.Conn })
	ctx := t.Context()
	q := srv.q
	dir := t.TempDir()
	meta := filepath.Join(dir, "chapters.txt")
	text := ";FFMETADATA1\n"
	for _, c := range [][3]any{{0, 2000, "Intro"}, {2000, 12000, "Episode"}, {12000, 20000, "End Credits"}} {
		text += fmt.Sprintf("[CHAPTER]\nTIMEBASE=1/1000\nSTART=%d\nEND=%d\ntitle=%s\n", c[0], c[1], c[2])
	}
	if err := os.WriteFile(meta, []byte(text), 0o600); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "Show - S01E01.mkv")
	if b, err := exec.Command(ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10:duration=20",
		"-i", meta, "-map", "0:v", "-map_chapters", "1", "-c:v", "mpeg4", path).CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg: %v\n%s", err, b)
	}
	probeJSON := `{"container":"matroska","durationMs":20000,"video":[{"index":0,"codec":"mpeg4","width":64,"height":64}],"audio":[],"subtitles":[]}`
	upsert := func(mtime time.Time) db.MediaFile {
		f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: srv.fileLibrary(t, path), Path: path, Size: 1, Mtime: db.FormatTime(mtime), AddedAt: db.FormatTime(mtime),
			Parsed: "{}", Probe: &probeJSON, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
		if err != nil {
			t.Fatal(err)
		}
		return f
	}
	f := upsert(time.Now().Add(-time.Hour))
	play := func() playback.Decision {
		body := `{"profile":{"containers":["mkv"],"videoCodecs":["mpeg4"],"audioCodecs":["aac"]}}`
		res, err := srv.client.Post(srv.URL+fmt.Sprintf("/api/files/%d/playback", f.ID), "application/json", strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		var d playback.Decision
		if err := json.NewDecoder(res.Body).Decode(&d); err != nil || res.StatusCode != 200 {
			t.Fatalf("playback: %d %v", res.StatusCode, err)
		}
		return d
	}
	kinds := func(segs []playback.Segment) string {
		var out []string
		for _, s := range segs {
			out = append(out, fmt.Sprintf("%s@%d", s.Kind, s.StartMs))
		}
		return strings.Join(out, ",")
	}

	d := play()
	if kinds(d.Segments) != "intro@0,credits@12000" || !d.Segments[1].Final || d.CreditsMs != 12000 || d.WatchedAtMs != 12000 {
		t.Fatalf("segments = %+v credits %d watched %d", d.Segments, d.CreditsMs, d.WatchedAtMs)
	}

	// A person deletes the intro; a changed file is analysed again but the
	// intro does not come back.
	if _, err := conn.ExecContext(ctx, `UPDATE media_segments SET state = 'suppressed' WHERE media_file_id = ? AND kind = 'intro'`, f.ID); err != nil {
		t.Fatal(err)
	}
	f = upsert(time.Now())
	if d = play(); kinds(d.Segments) != "credits@12000" {
		t.Errorf("after re-analysis segments = %s", kinds(d.Segments))
	}
	if n, _ := q.ListFileSegments(ctx, f.ID); len(n) != 2 {
		t.Errorf("stored rows = %d, want the credits and the suppressed intro", len(n))
	}

}

func TestRematchGuards(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx := t.Context()
	q := srv.q
	tv, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "Shows", Path: "/s"})
	show, _ := q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: tv.ID, TmdbID: 55, Title: "Fallow", Genres: "[]", Credits: "[]"})
	post := func(path, body string) int {
		res, err := srv.client.Post(srv.URL+path, "application/json", strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		_ = res.Body.Close()
		return res.StatusCode
	}
	if code := post(fmt.Sprintf("/api/shows/%d/rematch", show.ID), `{"tmdbId":55}`); code != http.StatusUnprocessableEntity {
		t.Errorf("rematch to the same show -> %d", code)
	}
	if code := post(fmt.Sprintf("/api/shows/%d/rematch", show.ID), `{"tmdbId":56}`); code != http.StatusServiceUnavailable {
		t.Errorf("rematch without TMDB -> %d", code)
	}
	if code := post("/api/shows/9999/rematch", `{"tmdbId":56}`); code != http.StatusNotFound {
		t.Errorf("rematch of a missing show -> %d", code)
	}
}

func TestIgnoreFiles(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx := t.Context()
	q := srv.q
	tv, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "Shows", Path: "/s"})
	add := func(rel, status string) int64 {
		f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: tv.ID, Path: "/s/" + rel, Size: 1, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()),
			Parsed: `{"kind":"unknown","title":"clip"}`, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
		if err != nil {
			t.Fatal(err)
		}
		_ = q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: status, ID: f.ID})
		return f.ID
	}
	clip := add("incoming/clip.mkv", "unmatched")
	done := add("Done/S01E01.mkv", "matched")
	post := func(body string) int {
		res, err := srv.client.Post(srv.URL+fmt.Sprintf("/api/libraries/%d/ignore-files", tv.ID), "application/json", strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		_ = res.Body.Close()
		return res.StatusCode
	}
	state := func() (kind string, unmatched int64) {
		var groups UnmatchedGroupList
		res, _ := srv.client.Get(srv.URL + fmt.Sprintf("/api/libraries/%d/unmatched", tv.ID))
		_ = json.NewDecoder(res.Body).Decode(&groups)
		_ = res.Body.Close()
		var lib Library
		res, _ = srv.client.Get(srv.URL + fmt.Sprintf("/api/libraries/%d", tv.ID))
		_ = json.NewDecoder(res.Body).Decode(&lib)
		_ = res.Body.Close()
		if len(groups.Groups) != 1 {
			t.Fatalf("groups = %+v", groups.Groups)
		}
		return groups.Groups[0].Kind, lib.UnmatchedCount
	}
	if code := post(fmt.Sprintf(`{"fileIds":[%d],"ignored":true}`, clip)); code != http.StatusNoContent {
		t.Fatalf("ignore -> %d", code)
	}
	if kind, n := state(); kind != GroupIgnored || n != 0 {
		t.Errorf("after ignore: kind %s, unmatched count %d", kind, n)
	}
	if code := post(fmt.Sprintf(`{"fileIds":[%d],"ignored":false}`, clip)); code != http.StatusNoContent {
		t.Fatalf("un-ignore -> %d", code)
	}
	if kind, n := state(); kind != GroupShow || n != 1 {
		t.Errorf("after un-ignore: kind %s, unmatched count %d", kind, n)
	}
	if code := post(fmt.Sprintf(`{"fileIds":[%d],"ignored":true}`, done)); code != http.StatusUnprocessableEntity {
		t.Errorf("ignoring a matched file -> %d", code)
	}
}

func TestPreferences(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	get := func() Preferences {
		res, err := srv.client.Get(srv.URL + "/api/me/preferences")
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		var p Preferences
		if err := json.NewDecoder(res.Body).Decode(&p); err != nil || res.StatusCode != 200 {
			t.Fatalf("get -> %d %v", res.StatusCode, err)
		}
		return p
	}
	put := func(body string) int {
		req, _ := http.NewRequestWithContext(t.Context(), http.MethodPut, srv.URL+"/api/me/preferences", strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		res, err := srv.client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		_ = res.Body.Close()
		return res.StatusCode
	}
	if p := get(); p.Skip["intro"] != SkipAsk || p.Skip["preview"] != SkipNone || !p.AutoplayNext {
		t.Errorf("defaults = %+v", p)
	}
	if code := put(`{"skip":{"intro":"auto","credits":"auto-delay"},"autoplayNext":false}`); code != 200 {
		t.Fatalf("put -> %d", code)
	}
	if p := get(); p.Skip["intro"] != SkipAuto || p.Skip["credits"] != SkipAutoDelay || p.Skip["recap"] != SkipAsk || p.AutoplayNext {
		t.Errorf("after put = %+v", p)
	}
	if code := put(`{"skip":{"trailer":"auto"}}`); code != http.StatusUnprocessableEntity {
		t.Errorf("unknown kind -> %d", code)
	}
	if code := put(`{"skip":{"intro":"sometimes"}}`); code != http.StatusUnprocessableEntity {
		t.Errorf("unknown mode -> %d", code)
	}
}

func TestSegmentEditing(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx := t.Context()
	q := srv.q
	tv, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "Shows", Path: "/s"})
	dur := int64(600_000)
	f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: tv.ID, Path: "/s/ep.mkv", Size: 1, Mtime: "m1", AddedAt: db.FormatTime(time.Now()),
		Parsed: "{}", DurationMs: &dur, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	ver := segments.FileVersion{ID: f.ID, Mtime: f.Mtime, Size: f.Size}
	// A detector found an intro and the credits.
	detected := []playback.Segment{{Kind: "intro", StartMs: 0, EndMs: 60_000, Source: "chapter", Confidence: 1}, {Kind: "credits", StartMs: 540_000, Final: true, Source: "chapter", Confidence: 1}}
	if err := segments.Replace(ctx, nil, q, ver, "chapter", 1, detected); err != nil {
		t.Fatal(err)
	}
	get := func() FileSegments {
		res, err := srv.client.Get(srv.URL + fmt.Sprintf("/api/files/%d/segments", f.ID))
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		var out FileSegments
		if err := json.NewDecoder(res.Body).Decode(&out); err != nil || res.StatusCode != 200 {
			t.Fatalf("get -> %d %v", res.StatusCode, err)
		}
		return out
	}
	put := func(body string) (int, FileSegments) {
		req, _ := http.NewRequestWithContext(t.Context(), http.MethodPut, srv.URL+fmt.Sprintf("/api/files/%d/segments", f.ID), strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		res, err := srv.client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		var out FileSegments
		_ = json.NewDecoder(res.Body).Decode(&out)
		return res.StatusCode, out
	}
	before := get()
	if len(before.Segments) != 2 || len(before.Effective) != 2 || before.DurationMs != dur {
		t.Fatalf("before = %+v", before)
	}
	var chapterIntro int64
	for _, s := range before.Segments {
		if s.Kind == "intro" {
			chapterIntro = s.ID
		}
	}
	// A person draws the intro where it really is and hides the detected one.
	code, after := put(fmt.Sprintf(`{"manual":[{"kind":"intro","startMs":5000,"endMs":70000}],"suppressed":[%d]}`, chapterIntro))
	if code != 200 {
		t.Fatalf("put -> %d", code)
	}
	var effIntro *playback.Segment
	for i := range after.Effective {
		if after.Effective[i].Kind == "intro" {
			effIntro = &after.Effective[i]
		}
	}
	if effIntro == nil || effIntro.Source != "manual" || effIntro.StartMs != 5000 || effIntro.EndMs != 70_000 {
		t.Errorf("effective intro = %+v", effIntro)
	}
	for _, s := range after.Segments {
		if s.ID == chapterIntro && s.State != "suppressed" {
			t.Errorf("chapter intro state = %s", s.State)
		}
	}
	// The detector runs again and finds the same intro: it must not come back.
	if err := segments.Replace(ctx, nil, q, ver, "chapter", 2, detected); err != nil {
		t.Fatal(err)
	}
	again := get()
	intros := 0
	for _, s := range again.Segments {
		if s.Kind == "intro" && s.State == "active" {
			intros++
		}
	}
	if intros != 1 || len(again.Effective) != 2 {
		t.Errorf("after re-detection: %d active intros, effective %+v", intros, again.Effective)
	}
	// Manual segments are replaced as a set; clearing them brings the chapter credits back as the only ones.
	if code, out := put(`{"manual":[],"suppressed":[]}`); code != 200 || len(out.Effective) != 1 || out.Effective[0].Kind != "credits" {
		t.Errorf("clear manual -> %d %+v", code, out.Effective)
	}
	if code, _ := put(`{"manual":[{"kind":"intro","startMs":9000,"endMs":5000}],"suppressed":[]}`); code != http.StatusUnprocessableEntity {
		t.Errorf("end before start -> %d", code)
	}
	if code, _ := put(`{"manual":[{"kind":"intro","startMs":700000,"endMs":0}],"suppressed":[]}`); code != http.StatusUnprocessableEntity {
		t.Errorf("past the end -> %d", code)
	}
}

func TestUnmatchedFilesInLists(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx := t.Context()
	q := srv.q
	tv, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "Shows", Path: filepath.FromSlash("/s")})
	h := int64(2160)
	file := func(rel, parsed, status string) int64 {
		f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: tv.ID, Path: filepath.FromSlash("/s/" + rel), Size: 10, Height: &h, Mtime: db.FormatTime(time.Now()),
			AddedAt: db.FormatTime(time.Now()), Parsed: parsed, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
		if err != nil {
			t.Fatal(err)
		}
		_ = q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: status, ID: f.ID})
		return f.ID
	}
	ep := `{"kind":"episode","title":"Blackwater Row","season":1,"episodes":[1]}`
	file("Blackwater Row/Season 01/Blackwater Row - S01E01.mkv", ep, "unmatched")
	file("Blackwater Row/Season 01/Blackwater Row - S01E02.mkv", ep, "unmatched")
	sample := file("incoming/sample.mkv", `{"kind":"unknown","title":"sample"}`, "unmatched")
	_ = q.SetMediaFileIgnored(ctx, db.SetMediaFileIgnoredParams{MatchIgnored: 1, ID: sample})
	// A file of a show that is already in the list waits under Pick episodes, not as a card.
	show, _ := q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: tv.ID, TmdbID: 77, Title: "Fallow", Genres: "[]", Credits: "[]"})
	season, _ := q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: show.ID, Number: 2})
	e1, _ := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: season.ID, ShowID: show.ID, Number: 1})
	linked := file("Fallow/Season 02/S02E01.mkv", `{"kind":"episode","title":"Fallow","season":2,"episodes":[1]}`, "matched")
	_ = q.InsertEpisodeFile(ctx, db.InsertEpisodeFileParams{EpisodeID: e1.ID, MediaFileID: linked})
	file("Fallow/Season 02/S02E11.mkv", `{"kind":"episode","title":"Fallow","season":2,"episodes":[11]}`, "unmatched")

	var items ItemList
	doJSON(t, srv.client, http.MethodGet, srv.URL+fmt.Sprintf("/api/libraries/%d/items", tv.ID), "", &items)
	var cards []BrowseItem
	for _, it := range items.Items {
		if it.Kind == "unmatched" {
			cards = append(cards, it)
		}
	}
	if items.Total != 2 || len(cards) != 1 {
		t.Fatalf("items = %+v", items.Items)
	}
	c := cards[0]
	if c.Title != "Blackwater Row" || c.FileName != "Blackwater Row" || c.FileCount != 2 || c.Subtitle != "Not matched · 2 files" ||
		c.Quality != "4K" || c.LibraryType != "tv" || c.PosterPath != "" {
		t.Errorf("card = %+v", c)
	}
	// Filters about a show's status or watching leave unmatched files out; a search finds them by name.
	doJSON(t, srv.client, http.MethodGet, srv.URL+fmt.Sprintf("/api/libraries/%d/items?filter=continuing", tv.ID), "", &items)
	for _, it := range items.Items {
		if it.Kind == "unmatched" {
			t.Errorf("continuing filter kept %+v", it)
		}
	}
	doJSON(t, srv.client, http.MethodGet, srv.URL+fmt.Sprintf("/api/libraries/%d/items?q=blackwater", tv.ID), "", &items)
	if items.Total != 1 || items.Items[0].Kind != "unmatched" {
		t.Errorf("search = %+v", items.Items)
	}
	// Recently added also shows new episodes of a known show that still need one picked.
	var rows LibraryRows
	doJSON(t, srv.client, http.MethodGet, srv.URL+fmt.Sprintf("/api/libraries/%d/rows", tv.ID), "", &rows)
	var pick *BrowseItem
	for i, it := range rows.RecentlyAdded {
		if it.Kind == "unmatched" && it.Title == "Fallow" {
			pick = &rows.RecentlyAdded[i]
		}
	}
	if pick == nil || pick.Subtitle != "Episode to pick" || pick.FileName != "Fallow/Season 02/S02E11.mkv" {
		t.Errorf("recently added = %+v", rows.RecentlyAdded)
	}
	var home HomeData
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/home", "", &home)
	if home.Hero != nil && home.Hero.Kind == "show" && home.Hero.Title == "Blackwater Row" {
		t.Errorf("an unmatched folder became the hero: %+v", home.Hero)
	}
}

// A dated file that fits more than one episode asks a person to choose,
// rather than saying TMDB lacks the date.
func TestGroupReasonForDatesThatFitTwoEpisodes(t *testing.T) {
	t.Parallel()
	g := &UnmatchedGroup{Kind: GroupEpisodes, Show: &GroupShowRef{Title: "The Daily Show"}, Files: []UnmatchedFile{
		{AirDate: "2024-11-04", MatchError: `2 episodes of "The Daily Show" aired on 2024-11-04; ` + metadata.PickOne},
	}}
	if got := groupReason("tv", g); !strings.HasPrefix(got, "Some dates fit more than one episode") {
		t.Errorf("reason = %q", got)
	}
	g.Files[0].MatchError = `no episode of "The Daily Show" aired on 2024-11-05 on TMDB`
	if got := groupReason("tv", g); !strings.HasPrefix(got, "TMDB lists no episode") {
		t.Errorf("reason = %q", got)
	}
}

// Files with no season and episode numbers are never looked up, so the
// reason says so rather than that TMDB found nothing.
func TestGroupReasonForUnnumberedFiles(t *testing.T) {
	t.Parallel()
	g := &UnmatchedGroup{Kind: GroupShow, Title: "Horace and Pete", Seasons: []int{}, Candidates: []metadata.Candidate{},
		Files: []UnmatchedFile{{RelativePath: "Horace and Pete/HORACE_AND_PETE_ONE.mkv"}}}
	if got := groupReason("tv", g); got != "The file names have no season and episode numbers." {
		t.Errorf("reason = %q", got)
	}
	g.Seasons = []int{1}
	if got := groupReason("tv", g); !strings.HasPrefix(got, "TMDB has no results") {
		t.Errorf("numbered reason = %q", got)
	}
}

// Progress tells the user's screens when an item is started, left or
// finished, not at every heartbeat; marks always do. Only that user hears.
func TestWatchChangedEvents(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx := t.Context()
	q := srv.q
	movie, _ := q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: 1, TmdbID: 603, Title: "The Matrix", Overview: "o", Genres: "[]", Credits: "[]"})
	f, _ := q.GetMediaFileByPath(ctx, "/m/Heat (1995).mkv")
	_ = q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &movie.ID, ID: f.ID})
	ch, cancel := srv.bus.Subscribe()
	defer cancel()
	send := func(method, path, body string) {
		t.Helper()
		req, _ := http.NewRequestWithContext(t.Context(), method, srv.URL+path, strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		res, err := srv.client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		_ = res.Body.Close()
		if res.StatusCode >= 300 {
			t.Fatalf("%s %s -> %d", method, path, res.StatusCode)
		}
	}
	progress := fmt.Sprintf("/api/files/%d/progress", f.ID)
	beat := func(pos int, state string) {
		t.Helper()
		send(http.MethodPut, progress, fmt.Sprintf(`{"positionMs":%d,"durationMs":600000,"playId":"0123456789abcdef","state":%q}`, pos, state))
	}
	// Events are published before the response, so they are waiting by now.
	changed := func() bool {
		t.Helper()
		for {
			select {
			case e := <-ch:
				if e.Type != "watch.changed" {
					continue
				}
				w, _ := e.Data.(WatchChanged)
				if e.To == nil || e.To.UserID != 1 || !e.To.Private || w.ItemType != "movie" || len(w.ItemIDs) != 1 || w.ItemIDs[0] != movie.ID {
					t.Errorf("event = %+v to %+v", e.Data, e.To)
				}
				return true
			default:
				return false
			}
		}
	}
	steps := []struct {
		name string
		do   func()
		want bool
	}{
		{"first report", func() { beat(60_000, "playing") }, true},
		{"heartbeat", func() { beat(70_000, "playing") }, false},
		{"pause", func() { beat(71_000, "paused") }, true},
		{"resume", func() { beat(71_000, "playing") }, false},
		{"past the end", func() { beat(590_000, "playing") }, true},
		{"credits", func() { beat(595_000, "playing") }, false},
		{"stop", func() { beat(599_000, "stopped") }, true},
		{"report outside a play", func() { send(http.MethodPut, progress, `{"positionMs":1000,"durationMs":600000}`) }, true},
		{"mark watched", func() { send(http.MethodPost, fmt.Sprintf("/api/watch/movie/%d/watched", movie.ID), "") }, true},
		{"mark unwatched", func() { send(http.MethodDelete, fmt.Sprintf("/api/watch/movie/%d/watched", movie.ID), "") }, true},
	}
	for _, s := range steps {
		s.do()
		if got := changed(); got != s.want {
			t.Errorf("%s: watch.changed = %v, want %v", s.name, got, s.want)
		}
	}
}
