package integrations

import (
	"context"
	"net/http"
	"net/http/httptest"
	"net/http/httputil"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/integrations/arrtest"
	"watchmymedia/server/internal/integrations/seerr"
	"watchmymedia/server/internal/integrations/seerrtest"
)

// orderScanner records scans and runs check first, to see what had happened
// by the time the server scanned.
type orderScanner struct {
	mu    sync.Mutex
	paths []string
	check func()
}

func (o *orderScanner) got() []string {
	o.mu.Lock()
	defer o.mu.Unlock()
	return slices.Clone(o.paths)
}

func (o *orderScanner) RequestPaths(_ int64, paths []string) {
	if o.check != nil {
		o.check()
	}
	o.mu.Lock()
	defer o.mu.Unlock()
	o.paths = append(o.paths, paths...)
}

type deleteEnv struct {
	requestEnv
	scan *orderScanner
}

func setupDelete(t *testing.T) deleteEnv {
	t.Helper()
	// The fakes delete nothing on disk: scan at once.
	wait := goneWait
	goneWait = 0
	t.Cleanup(func() { goneWait = wait })
	e := deleteEnv{requestEnv: setupRequests(t), scan: &orderScanner{}}
	e.s.scan = e.scan
	e.admin.Name = "admin"
	e.radarr.RecycleBin = "/data/recycle"
	return e
}

// quietField puts Quiet Field (TMDB 700001) in app as movie id, in folder
// under root (as the app sees it), with its file on disk under local.
func quietField(t *testing.T, app *arrtest.App, id int, root, local string, size int64) string {
	t.Helper()
	dir := filepath.Join(local, "Quiet Field (2019)")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(dir, "Quiet Field (2019).mkv")
	if err := os.WriteFile(file, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	app.Movies = append(app.Movies, map[string]any{"id": id, "title": "Quiet Field", "tmdbId": 700001, "monitored": true, "hasFile": true,
		"path": root + "/Quiet Field (2019)", "movieFile": map[string]any{"id": id*10 + 1, "path": root + "/Quiet Field (2019)/Quiet Field (2019).mkv",
			"relativePath": "Quiet Field (2019).mkv", "size": size}})
	return file
}

func (e deleteEnv) request(t *testing.T, mediaType string, tmdbID int64, status, seasons string) int64 {
	t.Helper()
	r, err := e.q.CreateRequest(e.ctx, db.CreateRequestParams{UserID: e.member.ID, MediaType: mediaType, TmdbID: tmdbID, Title: "x", Seasons: seasons})
	if err != nil {
		t.Fatal(err)
	}
	if status != "pending" {
		if err := e.q.SetRequestStatus(e.ctx, db.SetRequestStatusParams{Status: status, ID: r.ID}); err != nil {
			t.Fatal(err)
		}
	}
	return r.ID
}

func (e deleteEnv) requestRow(t *testing.T, id int64) db.GetRequestRow {
	t.Helper()
	r, err := e.q.GetRequest(e.ctx, id)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func callIndex(app *arrtest.App, method, path string) int {
	app.Lock()
	defer app.Unlock()
	return slices.IndexFunc(app.Calls, func(c arrtest.Call) bool { return c.Method == method && c.Path == path })
}

func TestDeleteMovieUnmonitorsDeletesClosesRequestsThenScans(t *testing.T) {
	e := setupDelete(t)
	file := quietField(t, e.radarr, 5, "/movies", e.movies.Path, 1000)
	dir := filepath.Dir(file)
	// Radarr lists the movie file and its .nfo; the extras folder is
	// someone's own, and NAS clutter doesn't count.
	for _, f := range []string{"Quiet Field (2019).nfo", "Extras/Behind the scenes.mkv", ".DS_Store", "@eaDir/thumb.jpg"} {
		_ = os.MkdirAll(filepath.Dir(filepath.Join(dir, f)), 0o755)
		_ = os.WriteFile(filepath.Join(dir, f), []byte("x"), 0o600)
	}
	e.radarr.ExtraFiles = []map[string]any{{"movieId": 5, "relativePath": "Quiet Field (2019).nfo"}}
	e.s.refreshAll(e.ctx)
	pending := e.request(t, "movie", 700001, "pending", "[]")
	approved := e.request(t, "movie", 700001, "approved", "[]")
	done := e.request(t, "movie", 700001, "available", "[]")
	target := DeleteTarget{Type: "movie", Title: "Quiet Field (2019)", LibraryID: e.movies.ID, LibraryPath: e.movies.Path, TMDBID: 700001, Files: []string{file}}

	p := e.s.PreviewDelete(e.ctx, target)
	if !p.Managed || p.Files != 1 || p.Size != 1000 || p.Kept != 0 || len(p.Apps) != 1 {
		t.Fatalf("preview = %+v", p)
	}
	a := p.Apps[0]
	if a.Connection != "radarr" || a.Folder != "/movies/Quiet Field (2019)" || a.LocalFolder != dir || a.Extra != 1 ||
		a.RecycleBin != "/data/recycle" || a.RecycleDays != 7 || a.Error != "" {
		t.Errorf("app = %+v", a)
	}
	if p.Choices.Keep || p.Choices.Exclude || p.Choices.KeepMonitored {
		t.Errorf("choices = %+v", p.Choices)
	}
	e.radarr.Lock()
	e.radarr.Calls = nil
	e.radarr.Unlock()

	e.scan.check = func() {
		if callIndex(e.radarr, http.MethodDelete, "/movie/5") < 0 {
			t.Error("scanned before Radarr deleted")
		}
		if r := e.requestRow(t, approved); r.Status != "cancelled" {
			t.Errorf("scanned before the requests closed: %s", r.Status)
		}
	}
	res, err := e.s.DeleteMedia(e.ctx, e.admin, target, DeleteOptions{})
	if err != nil {
		t.Fatal(err)
	}
	unmonitor, del := callIndex(e.radarr, http.MethodPut, "/movie/editor"), callIndex(e.radarr, http.MethodDelete, "/movie/5")
	if unmonitor < 0 || del < 0 || unmonitor > del {
		t.Errorf("unmonitor at %d, delete at %d", unmonitor, del)
	}
	if c := e.radarr.CallsTo(http.MethodPut, "/movie/editor"); len(c) != 1 || c[0].Body["monitored"] != false {
		t.Errorf("unmonitor = %+v", c)
	}
	if c := e.radarr.CallsTo(http.MethodDelete, "/movie/5"); len(c) != 1 || c[0].Query != "addImportExclusion=false&deleteFiles=true" {
		t.Errorf("delete = %+v", c)
	}
	if len(res.Apps) != 1 || res.Apps[0].Error != "" || res.Apps[0].Files != 1 || res.Requests != 2 {
		t.Errorf("result = %+v", res)
	}
	if !slices.Equal(e.scan.paths, []string{dir}) {
		t.Errorf("scanned %v, want %v", e.scan.paths, dir)
	}
	for id, want := range map[int64]string{pending: "cancelled", approved: "cancelled", done: "available"} {
		if r := e.requestRow(t, id); r.Status != want || (want == "cancelled" && (r.Note == nil || !strings.Contains(*r.Note, "by admin"))) {
			t.Errorf("request %d = %s %v, want %s", id, r.Status, r.Note, want)
		}
	}
	if e.s.Managed("movie", Item{LibraryPath: e.movies.Path, TMDBID: 700001, Files: []string{file}}) {
		t.Error("the removed movie is still in the index")
	}
	if res.Seerr != nil || p.Seerr != "" {
		t.Errorf("Seerr without a Seerr: %+v, %q", res.Seerr, p.Seerr)
	}
}

func TestDeleteMovieKeptUnmonitoredDeletesOnlyItsFile(t *testing.T) {
	e := setupDelete(t)
	file := quietField(t, e.radarr, 5, "/movies", e.movies.Path, 1000)
	e.s.refreshAll(e.ctx)
	target := DeleteTarget{Type: "movie", LibraryID: e.movies.ID, LibraryPath: e.movies.Path, TMDBID: 700001, Files: []string{file}}
	if _, err := e.s.DeleteMedia(e.ctx, e.admin, target, DeleteOptions{Keep: true, Exclude: true}); err != nil {
		t.Fatal(err)
	}
	if n := len(e.radarr.CallsTo(http.MethodDelete, "/movie/5")); n != 0 {
		t.Errorf("the movie was removed from Radarr (%d)", n)
	}
	if n := len(e.radarr.CallsTo(http.MethodDelete, "/moviefile/51")); n != 1 {
		t.Errorf("movie file deletes = %d", n)
	}
	e.radarr.Lock()
	monitored := e.radarr.Movies[0]["monitored"]
	e.radarr.Unlock()
	if monitored != false {
		t.Errorf("monitored = %v", monitored)
	}
	if callIndex(e.radarr, http.MethodPut, "/movie/editor") > callIndex(e.radarr, http.MethodDelete, "/moviefile/51") {
		t.Error("the file was deleted before the movie was unmonitored")
	}
	// The folder stays, so the file is what is scanned.
	if !slices.Equal(e.scan.paths, []string{file}) {
		t.Errorf("scanned %v", e.scan.paths)
	}
}

func TestRescanWaitsUntilThePathIsGone(t *testing.T) {
	every, wait := goneEvery, goneWait
	goneEvery, goneWait = time.Second, time.Minute
	t.Cleanup(func() { goneEvery, goneWait = every, wait })
	synctest.Test(t, func(t *testing.T) {
		sc := &orderScanner{}
		s := &Service{scan: sc, ctx: t.Context()}
		lib := t.TempDir()
		still, gone := filepath.Join(lib, "Still (2001)"), filepath.Join(lib, "Gone (2002)")
		_ = os.MkdirAll(still, 0o755)

		s.rescanWhenGone(1, lib, []string{gone, still, filepath.Join(t.TempDir(), "outside")})
		if got := sc.got(); !slices.Equal(got, []string{gone}) {
			t.Fatalf("scanned %v at once, want only what is gone", got)
		}
		synctest.Sleep(5 * goneEvery)
		if got := sc.got(); len(got) != 1 {
			t.Fatalf("scanned %v before the folder went", got)
		}
		_ = os.RemoveAll(still)
		synctest.Sleep(goneEvery)
		if got := sc.got(); !slices.Equal(got, []string{gone, still}) {
			t.Errorf("scanned %v", got)
		}
	})
}

// A rescan still waiting stops when the server shuts down, rather than
// looking every goneEvery until goneWait.
func TestRescanStopsOnShutdown(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		sc := &orderScanner{}
		ctx, shutdown := context.WithCancel(t.Context())
		s := &Service{scan: sc, ctx: ctx}
		lib := t.TempDir()
		still := filepath.Join(lib, "Still (2001)")
		_ = os.MkdirAll(still, 0o755)
		s.rescanWhenGone(1, lib, []string{still})
		synctest.Sleep(3 * goneEvery)
		shutdown()
		synctest.Wait() // Test fails if the loop is still running when it returns
		if got := sc.got(); len(got) != 0 {
			t.Errorf("scanned %v", got)
		}
	})
}

func TestDeleteMovieInTwoRadarrs(t *testing.T) {
	e := setupDelete(t)
	uhd := arrtest.New(t, "radarr")
	uhd.RecycleBin = ""
	uhdDir := filepath.Join(e.movies.Path, "4K")
	if _, err := e.s.Create(e.ctx, Input{Kind: "radarr", Name: new("Radarr 4K"), URL: new(uhd.URL), APIKey: new(uhd.Key),
		PathMap: &PathMap{{From: "/movies-4k", To: uhdDir}}}); err != nil {
		t.Fatal(err)
	}
	hd := quietField(t, e.radarr, 5, "/movies", e.movies.Path, 1000)
	four := quietField(t, uhd, 9, "/movies-4k", uhdDir, 5000)
	// A copy someone put in the library by hand stays.
	loose := filepath.Join(e.movies.Path, "Loose", "Quiet Field.mkv")
	e.s.refreshAll(e.ctx)
	target := DeleteTarget{Type: "movie", LibraryID: e.movies.ID, LibraryPath: e.movies.Path, TMDBID: 700001, Files: []string{hd, four, loose}}

	p := e.s.PreviewDelete(e.ctx, target)
	if len(p.Apps) != 2 || p.Files != 2 || p.Size != 6000 || p.Kept != 1 {
		t.Fatalf("preview = %+v", p)
	}
	if p.Apps[1].Connection != "Radarr 4K" || p.Apps[1].Size != 5000 || p.Apps[1].RecycleBin != "" || p.Apps[1].RecycleDays != 0 {
		t.Errorf("4K = %+v", p.Apps[1])
	}

	res, err := e.s.DeleteMedia(e.ctx, e.admin, target, DeleteOptions{Connections: []int64{p.Apps[1].ConnectionID}})
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Apps) != 1 || res.Apps[0].Connection != "Radarr 4K" {
		t.Errorf("result = %+v", res)
	}
	if n := len(uhd.CallsTo(http.MethodDelete, "/movie/9")); n != 1 {
		t.Errorf("4K deletes = %d", n)
	}
	if n := len(e.radarr.CallsTo(http.MethodPut, "/movie/editor")) + len(e.radarr.CallsTo(http.MethodDelete, "/movie/5")); n != 0 {
		t.Errorf("the unticked Radarr was changed (%d calls)", n)
	}
	if !slices.Equal(e.scan.paths, []string{filepath.Dir(four)}) {
		t.Errorf("scanned %v", e.scan.paths)
	}
	if _, err := e.s.DeleteMedia(e.ctx, e.admin, target, DeleteOptions{Connections: []int64{999}}); err == nil {
		t.Error("a delete from no app went ahead")
	}
}

// harborLights puts Harbor Lights (TMDB 800001) in Sonarr as series 7: two
// episodes of season 1 in one file, one of season 2 with its own.
func harborLights(e deleteEnv) {
	e.sonarr.Series = []map[string]any{{"id": 7, "title": "Harbor Lights", "path": "/tv/Harbor Lights", "tmdbId": 800001, "monitored": true,
		"seasons": []any{map[string]any{"seasonNumber": 1, "monitored": true}, map[string]any{"seasonNumber": 2, "monitored": true}}}}
	e.sonarr.EpisodeFiles = []map[string]any{
		{"id": 701, "seriesId": 7, "seasonNumber": 1, "path": "/tv/Harbor Lights/Season 01/Harbor Lights S01E01-E02.mkv", "relativePath": "Season 01/Harbor Lights S01E01-E02.mkv", "size": 300},
		{"id": 702, "seriesId": 7, "seasonNumber": 2, "path": "/tv/Harbor Lights/Season 02/Harbor Lights S02E01.mkv", "relativePath": "Season 02/Harbor Lights S02E01.mkv", "size": 200},
	}
	e.sonarr.Episodes = []map[string]any{
		{"id": 71, "seriesId": 7, "seasonNumber": 1, "episodeNumber": 1, "episodeFileId": 701, "hasFile": true, "monitored": true},
		{"id": 72, "seriesId": 7, "seasonNumber": 1, "episodeNumber": 2, "episodeFileId": 701, "hasFile": true, "monitored": true},
		{"id": 81, "seriesId": 7, "seasonNumber": 2, "episodeNumber": 1, "episodeFileId": 702, "hasFile": true, "monitored": true},
		{"id": 82, "seriesId": 7, "seasonNumber": 2, "episodeNumber": 2, "monitored": true},
	}
}

func (e deleteEnv) local(p string) string {
	return filepath.Join(e.tv.Path, filepath.FromSlash(strings.TrimPrefix(p, "/tv/")))
}

func TestDeleteShowRemovesTheSeries(t *testing.T) {
	e := setupDelete(t)
	harborLights(e)
	// Sonarr lists no extra files: the .nfo it wrote doesn't count, the
	// featurette someone added does.
	for _, f := range []string{"Season 01/Harbor Lights S01E01-E02.mkv", "Season 01/Harbor Lights S01E01-E02.nfo", "tvshow.nfo", "Featurettes/Making of.mkv"} {
		path := e.local("/tv/Harbor Lights/" + f)
		_ = os.MkdirAll(filepath.Dir(path), 0o755)
		_ = os.WriteFile(path, []byte("x"), 0o600)
	}
	e.s.refreshAll(e.ctx)
	open := e.request(t, "tv", 800001, "approved", "[1,2]")
	target := DeleteTarget{Type: "show", LibraryID: e.tv.ID, LibraryPath: e.tv.Path, TMDBID: 800001,
		Files: []string{e.local("/tv/Harbor Lights/Season 01/Harbor Lights S01E01-E02.mkv")}}
	p := e.s.PreviewDelete(e.ctx, target)
	if p.Files != 2 || p.Size != 500 || p.Apps[0].Folder != "/tv/Harbor Lights" || p.Apps[0].RecycleBin != "" || p.Apps[0].Extra != 1 || p.Apps[0].Error != "" {
		t.Errorf("preview = %+v", p)
	}
	if _, err := e.s.DeleteMedia(e.ctx, e.admin, target, DeleteOptions{Exclude: true}); err != nil {
		t.Fatal(err)
	}
	put := e.sonarr.CallsTo(http.MethodPut, "/series/7")
	if len(put) != 1 || put[0].Body["monitored"] != false {
		t.Fatalf("unmonitor = %+v", put)
	}
	for _, x := range put[0].Body["seasons"].([]any) {
		if x.(map[string]any)["monitored"] != false {
			t.Errorf("season %v left monitored", x)
		}
	}
	if c := e.sonarr.CallsTo(http.MethodDelete, "/series/7"); len(c) != 1 || c[0].Query != "addImportListExclusion=true&deleteFiles=true" {
		t.Errorf("delete = %+v", c)
	}
	if callIndex(e.sonarr, http.MethodPut, "/series/7") > callIndex(e.sonarr, http.MethodDelete, "/series/7") {
		t.Error("deleted before unmonitoring")
	}
	if r := e.requestRow(t, open); r.Status != "cancelled" {
		t.Errorf("request = %s", r.Status)
	}
	if !slices.Equal(e.scan.paths, []string{e.local("/tv/Harbor Lights")}) {
		t.Errorf("scanned %v", e.scan.paths)
	}
}

func TestDeleteSeasonStillAiringKeepsTheSeasonMonitored(t *testing.T) {
	e := setupDelete(t)
	harborLights(e)
	e.s.refreshAll(e.ctx)
	both := e.request(t, "tv", 800001, "approved", "[1,2]")
	only := e.request(t, "tv", 800001, "pending", "[2]")
	other := e.request(t, "tv", 800001, "approved", "[1]")
	file := e.local("/tv/Harbor Lights/Season 02/Harbor Lights S02E01.mkv")
	target := DeleteTarget{Type: "season", LibraryID: e.tv.ID, LibraryPath: e.tv.Path, TMDBID: 800001, Files: []string{file}, Season: 2, Airing: true}

	p := e.s.PreviewDelete(e.ctx, target)
	if !p.Choices.KeepMonitored || !p.Choices.Airing || p.Files != 1 || p.Size != 200 || p.Apps[0].Folder != "/tv/Harbor Lights/Season 02" {
		t.Errorf("preview = %+v", p)
	}
	res, err := e.s.DeleteMedia(e.ctx, e.admin, target, DeleteOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if n := len(e.sonarr.CallsTo(http.MethodPut, "/series/7")); n != 0 {
		t.Errorf("the season was unmonitored (%d)", n)
	}
	mon := e.sonarr.CallsTo(http.MethodPut, "/episode/monitor")
	if len(mon) != 1 || mon[0].Body["monitored"] != false || !slices.Equal(mon[0].Body["episodeIds"].([]any), []any{81.0}) {
		t.Errorf("episode unmonitor = %+v", mon)
	}
	del := e.sonarr.CallsTo(http.MethodDelete, "/episodefile/bulk")
	if len(del) != 1 || !slices.Equal(del[0].Body["episodeFileIds"].([]any), []any{702.0}) {
		t.Errorf("file deletes = %+v", del)
	}
	if callIndex(e.sonarr, http.MethodPut, "/episode/monitor") > callIndex(e.sonarr, http.MethodDelete, "/episodefile/bulk") {
		t.Error("deleted before unmonitoring")
	}
	if !slices.Equal(e.scan.paths, []string{file}) {
		t.Errorf("scanned %v", e.scan.paths)
	}
	// Season 2 leaves each request; one left with nothing closes.
	if r := e.requestRow(t, both); r.Status != "approved" || r.Seasons != "[1]" {
		t.Errorf("request for 1 and 2 = %s %s", r.Status, r.Seasons)
	}
	if r := e.requestRow(t, only); r.Status != "cancelled" {
		t.Errorf("request for 2 = %s", r.Status)
	}
	if r := e.requestRow(t, other); r.Status != "approved" || r.Seasons != "[1]" {
		t.Errorf("request for 1 = %s %s", r.Status, r.Seasons)
	}
	if res.Requests != 2 {
		t.Errorf("requests closed = %d", res.Requests)
	}
}

func TestDeleteSeasonUnmonitorsIt(t *testing.T) {
	e := setupDelete(t)
	harborLights(e)
	e.s.refreshAll(e.ctx)
	target := DeleteTarget{Type: "season", LibraryID: e.tv.ID, LibraryPath: e.tv.Path, TMDBID: 800001, Season: 1,
		Files: []string{e.local("/tv/Harbor Lights/Season 01/Harbor Lights S01E01-E02.mkv")}}
	if p := e.s.PreviewDelete(e.ctx, target); p.Choices.KeepMonitored {
		t.Errorf("an ended season keeps getting episodes by default")
	}
	if _, err := e.s.DeleteMedia(e.ctx, e.admin, target, DeleteOptions{KeepMonitored: new(false)}); err != nil {
		t.Fatal(err)
	}
	put := e.sonarr.CallsTo(http.MethodPut, "/series/7")
	if len(put) != 1 {
		t.Fatalf("series updates = %d", len(put))
	}
	for _, x := range put[0].Body["seasons"].([]any) {
		s := x.(map[string]any)
		if want := s["seasonNumber"] != 1.0; s["monitored"] != want {
			t.Errorf("season %v monitored = %v", s["seasonNumber"], s["monitored"])
		}
	}
	if del := e.sonarr.CallsTo(http.MethodDelete, "/episodefile/bulk"); len(del) != 1 || !slices.Equal(del[0].Body["episodeFileIds"].([]any), []any{701.0}) {
		t.Errorf("file deletes = %+v", del)
	}
}

func TestDeleteEpisodeWhoseFileHoldsTwo(t *testing.T) {
	e := setupDelete(t)
	harborLights(e)
	e.s.refreshAll(e.ctx)
	file := e.local("/tv/Harbor Lights/Season 01/Harbor Lights S01E01-E02.mkv")
	target := DeleteTarget{Type: "episode", LibraryID: e.tv.ID, LibraryPath: e.tv.Path, TMDBID: 800001, Files: []string{file}, Season: 1, Episode: 1}
	p := e.s.PreviewDelete(e.ctx, target)
	if p.Files != 1 || p.Size != 300 || !slices.Equal(p.Apps[0].OtherEpisodes, []int{2}) || p.Apps[0].Extra != 0 {
		t.Errorf("preview = %+v", p)
	}
	if _, err := e.s.DeleteMedia(e.ctx, e.admin, target, DeleteOptions{}); err != nil {
		t.Fatal(err)
	}
	if mon := e.sonarr.CallsTo(http.MethodPut, "/episode/monitor"); len(mon) != 1 || !slices.Equal(mon[0].Body["episodeIds"].([]any), []any{71.0, 72.0}) {
		t.Errorf("unmonitor = %+v", mon)
	}
	if del := e.sonarr.CallsTo(http.MethodDelete, "/episodefile/bulk"); len(del) != 1 || !slices.Equal(del[0].Body["episodeFileIds"].([]any), []any{701.0}) {
		t.Errorf("file deletes = %+v", del)
	}
	if !slices.Equal(e.scan.paths, []string{file}) {
		t.Errorf("scanned %v", e.scan.paths)
	}
}

func TestNothingToDeleteWithoutAnApp(t *testing.T) {
	e := setupDelete(t)
	e.s.refreshAll(e.ctx)
	target := DeleteTarget{Type: "movie", LibraryID: e.movies.ID, LibraryPath: e.movies.Path, TMDBID: 700001,
		Files: []string{filepath.Join(e.movies.Path, "Quiet Field (2019)", "q.mkv")}}
	if p := e.s.PreviewDelete(e.ctx, target); p.Managed || len(p.Apps) != 0 || p.Kept != 1 {
		t.Errorf("preview = %+v", p)
	}
	if _, err := e.s.DeleteMedia(e.ctx, e.admin, target, DeleteOptions{}); err == nil {
		t.Fatal("deleted something no app manages")
	} else if _, ok := IsInvalid(err); !ok {
		t.Errorf("err = %v", err)
	}
	if len(e.scan.paths) != 0 {
		t.Errorf("scanned %v", e.scan.paths)
	}
}

func TestDeleteWhenAnAppIsDown(t *testing.T) {
	e := setupDelete(t)
	file := quietField(t, e.radarr, 5, "/movies", e.movies.Path, 1000)
	e.s.refreshAll(e.ctx)
	open := e.request(t, "movie", 700001, "approved", "[]")
	target := DeleteTarget{Type: "movie", LibraryID: e.movies.ID, LibraryPath: e.movies.Path, TMDBID: 700001, Files: []string{file}}

	// Refusing the delete: nothing else happens.
	e.radarr.Lock()
	e.radarr.Fail = map[string]int{"DELETE /movie/5": http.StatusInternalServerError}
	e.radarr.Unlock()
	res, err := e.s.DeleteMedia(e.ctx, e.admin, target, DeleteOptions{})
	if err == nil || len(res.Apps) != 1 || res.Apps[0].Error == "" {
		t.Fatalf("a refused delete = %+v, %v", res, err)
	}
	if r := e.requestRow(t, open); r.Status != "approved" {
		t.Errorf("request = %s after a failed delete", r.Status)
	}
	if len(e.scan.paths) != 0 {
		t.Errorf("scanned %v after a failed delete", e.scan.paths)
	}

	// Down: the preview says so, and the delete fails.
	e.radarr.Close()
	p := e.s.PreviewDelete(e.ctx, target)
	if !p.Managed || len(p.Apps) != 1 || p.Apps[0].Error == "" || p.Files != 0 || p.Kept != 1 {
		t.Errorf("preview = %+v", p)
	}
	if _, err := e.s.DeleteMedia(e.ctx, e.admin, target, DeleteOptions{}); err == nil {
		t.Error("a delete in an app that is down succeeded")
	}
	if r := e.requestRow(t, open); r.Status != "approved" || len(e.scan.paths) != 0 {
		t.Errorf("request = %s, scanned %v", r.Status, e.scan.paths)
	}
}

func TestReplaceMovieFileBlocklistsDeletesAndSearches(t *testing.T) {
	e := setupDelete(t)
	file := quietField(t, e.radarr, 5, "/movies", e.movies.Path, 1000)
	e.radarr.Movies[0]["monitored"] = false
	e.radarr.History = []map[string]any{
		{"id": 902, "movieId": 5, "eventType": "downloadFolderImported", "downloadId": "abc", "data": map[string]any{"importedPath": "/movies/Quiet Field (2019)/Quiet Field (2019).mkv"}},
		{"id": 901, "movieId": 5, "eventType": "grabbed", "downloadId": "abc", "data": map[string]any{}},
		{"id": 800, "movieId": 6, "eventType": "grabbed", "downloadId": "other"},
	}
	e.s.refreshAll(e.ctx)
	res, err := e.s.ReplaceFile(e.ctx, e.admin, ReplaceTarget{Type: "movie", LibraryID: e.movies.ID, LibraryPath: e.movies.Path, TMDBID: 700001, Path: file})
	if err != nil {
		t.Fatal(err)
	}
	if !res.Blocklisted || res.Note != "" || res.Connection != "radarr" {
		t.Errorf("result = %+v", res)
	}
	failed, del := callIndex(e.radarr, http.MethodPost, "/history/failed/901"), callIndex(e.radarr, http.MethodDelete, "/moviefile/51")
	search := callIndex(e.radarr, http.MethodPost, "/command")
	if failed < 0 || del < failed || search < del {
		t.Errorf("mark failed at %d, delete at %d, search at %d", failed, del, search)
	}
	if c := e.radarr.CallsTo(http.MethodPost, "/command"); len(c) != 1 || c[0].Body["name"] != "MoviesSearch" {
		t.Errorf("commands = %+v", c)
	}
	if c := e.radarr.CallsTo(http.MethodPut, "/movie/editor"); len(c) != 1 || c[0].Body["monitored"] != true {
		t.Errorf("an unmonitored movie wasn't monitored for the search: %+v", c)
	}
	if !slices.Equal(e.scan.paths, []string{file}) {
		t.Errorf("scanned %v", e.scan.paths)
	}
}

func TestReplaceEpisodeFileImportedByHand(t *testing.T) {
	e := setupDelete(t)
	harborLights(e)
	e.s.refreshAll(e.ctx)
	file := e.local("/tv/Harbor Lights/Season 01/Harbor Lights S01E01-E02.mkv")
	res, err := e.s.ReplaceFile(e.ctx, e.admin, ReplaceTarget{Type: "episode", LibraryID: e.tv.ID, LibraryPath: e.tv.Path, TMDBID: 800001, Path: file})
	if err != nil {
		t.Fatal(err)
	}
	if res.Blocklisted || !strings.Contains(res.Note, "no record of downloading") {
		t.Errorf("result = %+v", res)
	}
	if n := len(e.sonarr.CallsTo(http.MethodPost, "/history/failed/0")); n != 0 {
		t.Error("marked a grab failed with none found")
	}
	if c := e.sonarr.CallsTo(http.MethodDelete, "/episodefile/bulk"); len(c) != 1 || !slices.Equal(c[0].Body["episodeFileIds"].([]any), []any{701.0}) {
		t.Errorf("deletes = %+v", c)
	}
	if c := e.sonarr.CallsTo(http.MethodPost, "/command"); len(c) != 1 || c[0].Body["name"] != "EpisodeSearch" || !slices.Equal(c[0].Body["episodeIds"].([]any), []any{71.0, 72.0}) {
		t.Errorf("commands = %+v", c)
	}

	// A file Sonarr doesn't list can't be replaced.
	_, err = e.s.ReplaceFile(e.ctx, e.admin, ReplaceTarget{Type: "episode", LibraryID: e.tv.ID, LibraryPath: e.tv.Path, TMDBID: 800001,
		Path: e.local("/tv/Harbor Lights/Season 01/sample.mkv")})
	if _, ok := IsInvalid(err); !ok {
		t.Errorf("an unlisted file: %v", err)
	}
}

// withSeerr connects a fake Seerr that has Quiet Field (700001) as media 40
// and Harbor Lights (800001) as media 41, both available. Calls reach it
// through a proxy that runs before first; closing the proxy takes Seerr down.
func (e deleteEnv) withSeerr(t *testing.T, before func(*http.Request)) (*seerrtest.App, *httptest.Server) {
	t.Helper()
	app := seerrtest.New(t)
	app.Items = []*seerrtest.Item{
		{MediaType: "movie", TMDBID: 700001, Title: "Quiet Field", Date: "2019-04-02", MediaID: 40, Status: seerr.StatusAvailable},
		{MediaType: "tv", TMDBID: 800001, Title: "Harbor Lights", Date: "2021-01-05", MediaID: 41, Status: seerr.StatusAvailable, Seasons: []seerr.Season{
			{SeasonNumber: 1, Name: "Season 1", EpisodeCount: 2, AirDate: "2021-01-05"},
			{SeasonNumber: 2, Name: "Season 2", EpisodeCount: 2, AirDate: "2022-01-05"}}},
	}
	to, _ := url.Parse(app.URL)
	rp := httputil.NewSingleHostReverseProxy(to)
	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if before != nil {
			before(r)
		}
		rp.ServeHTTP(w, r)
	}))
	t.Cleanup(proxy.Close)
	if _, err := e.s.Create(e.ctx, Input{Kind: "seerr", Name: new("Seerr"), URL: new(proxy.URL), APIKey: new(app.Key)}); err != nil {
		t.Fatal(err)
	}
	return app, proxy
}

func TestDeleteTellsSeerrOnceTheRequestsCloseAndBeforeTheScan(t *testing.T) {
	e := setupDelete(t)
	file := quietField(t, e.radarr, 5, "/movies", e.movies.Path, 1000)
	open := e.request(t, "movie", 700001, "approved", "[]")
	var told atomic.Bool
	app, _ := e.withSeerr(t, func(r *http.Request) {
		if r.Method != http.MethodDelete || !strings.HasPrefix(r.URL.Path, "/api/v1/media/") {
			return
		}
		told.Store(true)
		if callIndex(e.radarr, http.MethodDelete, "/movie/5") < 0 {
			t.Error("Seerr was told before Radarr deleted")
		}
		if r := e.requestRow(t, open); r.Status != "cancelled" {
			t.Errorf("Seerr was told before the requests closed (%s)", r.Status)
		}
	})
	e.s.refreshAll(e.ctx)
	e.scan.check = func() {
		if !told.Load() {
			t.Error("scanned before Seerr was told")
		}
	}
	target := DeleteTarget{Type: "movie", LibraryID: e.movies.ID, LibraryPath: e.movies.Path, TMDBID: 700001, Files: []string{file}}
	if p := e.s.PreviewDelete(e.ctx, target); p.Seerr != "Seerr" {
		t.Errorf("preview names Seerr as %q", p.Seerr)
	}
	res, err := e.s.DeleteMedia(e.ctx, e.admin, target, DeleteOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if res.Seerr == nil || res.Seerr.Connection != "Seerr" || res.Seerr.Error != "" {
		t.Errorf("seerr = %+v", res.Seerr)
	}
	if n := len(app.CallsTo(http.MethodDelete, "/media/40")); n != 1 {
		t.Errorf("Seerr record clears = %d", n)
	}
	if n := len(app.CallsTo(http.MethodPost, "/settings/jobs/sonarr-scan/run")); n != 0 {
		t.Errorf("Sonarr sync run for a movie (%d)", n)
	}
	if len(e.scan.got()) != 1 {
		t.Errorf("scanned %v", e.scan.got())
	}
}

func TestDeleteSeasonHasSeerrRebuildTheShow(t *testing.T) {
	e := setupDelete(t)
	harborLights(e)
	app, _ := e.withSeerr(t, nil)
	e.s.refreshAll(e.ctx)
	target := DeleteTarget{Type: "episode", LibraryID: e.tv.ID, LibraryPath: e.tv.Path, TMDBID: 800001, Season: 2, Episode: 1,
		Files: []string{e.local("/tv/Harbor Lights/Season 02/Harbor Lights S02E01.mkv")}}
	res, err := e.s.DeleteMedia(e.ctx, e.admin, target, DeleteOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if res.Seerr == nil || res.Seerr.Error != "" {
		t.Errorf("seerr = %+v", res.Seerr)
	}
	clear, sync := seerrCallIndex(app, http.MethodDelete, "/media/41"), seerrCallIndex(app, http.MethodPost, "/settings/jobs/sonarr-scan/run")
	if clear < 0 || sync < clear {
		t.Errorf("record cleared at %d, Sonarr sync at %d", clear, sync)
	}
}

func TestDeleteGoesAheadWhenSeerrRefusesOrIsDown(t *testing.T) {
	e := setupDelete(t)
	harborLights(e)
	app, proxy := e.withSeerr(t, nil)
	e.s.refreshAll(e.ctx)
	open := e.request(t, "tv", 800001, "approved", "[1]")
	season := func(n int) DeleteTarget {
		return DeleteTarget{Type: "season", LibraryID: e.tv.ID, LibraryPath: e.tv.Path, TMDBID: 800001, Season: n}
	}

	// A new key in Seerr: refused.
	app.Lock()
	app.Key = "changed"
	app.Unlock()
	res, err := e.s.DeleteMedia(e.ctx, e.admin, season(1), DeleteOptions{})
	if err != nil {
		t.Fatalf("Seerr refusing failed the delete: %v", err)
	}
	if res.Seerr == nil || res.Seerr.Error == "" || strings.HasPrefix(res.Seerr.Error, "seerr:") {
		t.Errorf("refused: seerr = %+v", res.Seerr)
	}
	if n := len(e.sonarr.CallsTo(http.MethodDelete, "/episodefile/bulk")); n != 1 {
		t.Errorf("Sonarr deletes = %d", n)
	}
	if r := e.requestRow(t, open); r.Status != "cancelled" {
		t.Errorf("request = %s", r.Status)
	}

	// Down.
	proxy.Close()
	res, err = e.s.DeleteMedia(e.ctx, e.admin, season(2), DeleteOptions{})
	if err != nil {
		t.Fatalf("Seerr down failed the delete: %v", err)
	}
	if res.Seerr == nil || res.Seerr.Error == "" {
		t.Errorf("down: seerr = %+v", res.Seerr)
	}
	if n := len(e.sonarr.CallsTo(http.MethodDelete, "/episodefile/bulk")); n != 2 {
		t.Errorf("Sonarr deletes = %d", n)
	}
	if got := e.scan.got(); len(got) != 2 {
		t.Errorf("scanned %v", got)
	}
}

func seerrCallIndex(app *seerrtest.App, method, path string) int {
	app.Lock()
	defer app.Unlock()
	return slices.IndexFunc(app.Calls, func(c seerrtest.Call) bool { return c.Method == method && c.Path == path })
}
