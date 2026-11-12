package api

import (
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"slices"
	"sync"
	"testing"
	"time"

	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/integrations"
	"watchmymedia/server/internal/integrations/arrtest"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/scanner"
	"watchmymedia/server/internal/trailers"
)

// lockedRecorder records the paths a delete asks to scan. The delete keeps
// looking for files that aren't gone yet in the background, so it locks.
type lockedRecorder struct {
	mu    sync.Mutex
	paths []string
}

func (r *lockedRecorder) RequestPaths(_ int64, paths []string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.paths = append(r.paths, paths...)
}

func (r *lockedRecorder) has(p string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return slices.Contains(r.paths, p)
}

// trailerLibrary is a movie library on disk with one movie, Quiet Field,
// matched, and trailer files linked to it as a scan would: trailer.mp4 next
// to the movie and Teaser.mp4 in its Trailers folder.
type trailerLibrary struct {
	lib              db.Library
	movie            db.Movie
	file             db.MediaFile
	trailer, teaser  db.MediaFile
	folder           string
	trailerDuration  int64
	movieSize        int64
	trailerFileBytes []byte
}

func newTrailerLibrary(t *testing.T, q *db.Queries) trailerLibrary {
	t.Helper()
	ctx := t.Context()
	root := t.TempDir()
	lib, err := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "movie", Name: "Trailer movies", Path: root})
	if err != nil {
		t.Fatal(err)
	}
	movie, err := q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: lib.ID, TmdbID: 700002, Title: "Quiet Field", Genres: "[]", Credits: "[]",
		PosterPath: new("/quiet.jpg"), BackdropPath: new("/quiet-wide.jpg")})
	if err != nil {
		t.Fatal(err)
	}
	tl := trailerLibrary{lib: lib, movie: movie, folder: filepath.Join(root, "Quiet Field (2019)"), trailerDuration: 150_000, movieSize: 32,
		trailerFileBytes: []byte("0123456789abcdef")}
	add := func(rel string, size int64, duration int64, extra bool) db.MediaFile {
		t.Helper()
		path := filepath.Join(tl.folder, filepath.FromSlash(rel))
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, tl.trailerFileBytes[:min(int(size), len(tl.trailerFileBytes))], 0o600); err != nil {
			t.Fatal(err)
		}
		probeJSON := fmt.Sprintf(`{"container":"mov","durationMs":%d,"video":[{"index":0,"codec":"h264","width":1280,"height":720}],"audio":[{"index":1,"codec":"aac","channels":2,"default":true}],"subtitles":[]}`, duration)
		h264, mov := "h264", "mov"
		p := db.UpsertMediaFileParams{LibraryID: lib.ID, Path: path, Size: size, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()),
			Parsed: "{}", Probe: &probeJSON, Container: &mov, VideoCodec: &h264, DurationMs: &duration, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1}
		if extra {
			p.ExtraKind = new("trailer")
		}
		f, err := q.UpsertMediaFile(ctx, p)
		if err != nil {
			t.Fatal(err)
		}
		return f
	}
	tl.file = add("Quiet Field (2019).mp4", tl.movieSize, 6_000_000, false)
	if err := q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &movie.ID, ID: tl.file.ID}); err != nil {
		t.Fatal(err)
	}
	// Teaser first by path ("Trailers/" sorts before "trailer"), but the
	// file next to the movie comes first.
	tl.teaser = add("Trailers/Teaser.mp4", 16, 60_000, true)
	tl.trailer = add("trailer.mp4", 16, tl.trailerDuration, true)
	if n, err := scanner.LinkTrailers(ctx, q, lib); err != nil || n != 2 {
		t.Fatalf("LinkTrailers = %d, %v", n, err)
	}
	return tl
}

// A movie's page lists its trailer files; a trailer plays like any file,
// shows in Now playing as "<movie> · Trailer", and leaves no watch state or
// history behind. It can't be matched or downloaded.
func TestTrailerFiles(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, func(d *Deps) {
		d.Activity = activity.New(d.Conn, d.Bus, nil, activity.Options{Config: activity.Config{Group: true}})
	})
	tl := newTrailerLibrary(t, srv.q)
	base, admin := srv.URL+"/api", srv.client
	doJSON(t, admin, http.MethodPut, base+"/settings", `{"activity":{"minPlaySeconds":0,"groupPlays":true,"retentionDays":0,"trimStats":false,"household":false}}`, nil)

	var m MovieDetail
	if code := doJSON(t, admin, http.MethodGet, fmt.Sprintf("%s/movies/%d", base, tl.movie.ID), "", &m).StatusCode; code != 200 {
		t.Fatalf("getMovie -> %d", code)
	}
	want := []MovieTrailer{
		{Source: "file", Name: "Trailer", FileID: tl.trailer.ID, DurationMs: tl.trailerDuration},
		{Source: "file", Name: "Teaser", FileID: tl.teaser.ID, DurationMs: 60_000},
	}
	if fmt.Sprint(m.Trailers) != fmt.Sprint(want) {
		t.Errorf("trailers = %+v, want %+v", m.Trailers, want)
	}
	if len(m.Files) != 1 || m.Files[0].ID != tl.file.ID || m.TotalSize != tl.movieSize {
		t.Errorf("files = %d, total size %d: trailers count as the movie's files", len(m.Files), m.TotalSize)
	}

	// It plays, and Now playing names it.
	var d playback.Decision
	body := `{"profile":{"containers":["mp4","mov"],"videoCodecs":["h264"],"audioCodecs":["aac"]},"quality":"original","client":{"deviceId":"dev-1","name":"Test laptop"}}`
	if code := doJSON(t, admin, http.MethodPost, fmt.Sprintf("%s/files/%d/playback", base, tl.trailer.ID), body, &d).StatusCode; code != 200 || d.Mode != playback.ModeDirect {
		t.Fatalf("playback -> %d %+v", code, d)
	}
	var live LiveActivity
	doJSON(t, admin, http.MethodGet, base+"/activity", "", &live)
	if len(live.Plays) != 1 || live.Plays[0].Item.Type != "file" || live.Plays[0].Item.Title != "Quiet Field · Trailer" ||
		live.Plays[0].Item.PosterPath != "/quiet.jpg" || live.Plays[0].Item.MovieID != nil {
		t.Fatalf("live = %+v", live.Plays)
	}

	// Its progress is taken and dropped, with or without a play.
	for _, b := range []string{
		`{"positionMs":140000,"durationMs":150000,"playId":"` + d.PlayID + `","state":"playing"}`,
		`{"positionMs":149000,"durationMs":150000}`,
		`{"positionMs":149000,"durationMs":150000,"playId":"` + d.PlayID + `","state":"stopped"}`,
	} {
		if code := doJSON(t, admin, http.MethodPut, fmt.Sprintf("%s/files/%d/progress", base, tl.trailer.ID), b, &Progress{}).StatusCode; code != 200 {
			t.Errorf("progress %s -> %d", b, code)
		}
	}
	var n int
	if err := srv.conn.QueryRowContext(t.Context(), "SELECT COUNT(*) FROM watch_state").Scan(&n); err != nil || n != 0 {
		t.Errorf("watch_state rows = %d (%v)", n, err)
	}
	if doJSON(t, admin, http.MethodGet, fmt.Sprintf("%s/movies/%d", base, tl.movie.ID), "", &m); m.Watch != nil {
		t.Errorf("the movie's watch state after its trailer: %+v", m.Watch)
	}
	var page activity.Page
	doJSON(t, admin, http.MethodGet, base+"/history", "", &page)
	if page.Total != 0 {
		t.Errorf("history = %d rows, want none", page.Total)
	}

	// Not matched by hand, not downloaded, not in the library's files.
	if code := doJSON(t, admin, http.MethodPost, fmt.Sprintf("%s/files/%d/match", base, tl.trailer.ID), `{"tmdbId":700002}`, nil).StatusCode; code != http.StatusUnprocessableEntity {
		t.Errorf("match a trailer -> %d", code)
	}
	if code := doJSON(t, admin, http.MethodPost, fmt.Sprintf("%s/files/%d/offline/plan", base, tl.trailer.ID), `{"profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"]}}`, nil).StatusCode; code != http.StatusConflict {
		t.Errorf("plan a trailer download -> %d", code)
	}
	if code := doJSON(t, admin, http.MethodPost, fmt.Sprintf("%s/files/%d/offline/plan", base, tl.file.ID), `{"profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"]}}`, nil).StatusCode; code != 200 {
		t.Errorf("plan the movie's download -> %d", code)
	}
	var files ListFilesOutput
	doJSON(t, admin, http.MethodGet, fmt.Sprintf("%s/libraries/%d/files?limit=50", base, tl.lib.ID), "", &files.Body)
	if files.Body.Total != 1 || len(files.Body.Items) != 1 || files.Body.Items[0].ID != tl.file.ID {
		t.Errorf("library files = %+v", files.Body)
	}
}

// Library sizes, file counts and seek thumbnails leave trailers out.
// A movie's trailer files come before its YouTube trailers, and stay while
// Trailers from YouTube is off (PLAN-TRAILERS.md §5.3).
func TestTrailerFilesBeforeYouTube(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	tl := newTrailerLibrary(t, srv.q)
	stored := trailers.Encode([]trailers.Trailer{{Key: "trailerKey1", Name: "Official Trailer", Type: "Trailer"}})
	if err := srv.q.SetMovieTrailers(t.Context(), db.SetMovieTrailersParams{Trailers: stored, ID: tl.movie.ID}); err != nil {
		t.Fatal(err)
	}
	files := []MovieTrailer{
		{Source: TrailerFile, Name: "Trailer", FileID: tl.trailer.ID, DurationMs: tl.trailerDuration},
		{Source: TrailerFile, Name: "Teaser", FileID: tl.teaser.ID, DurationMs: 60_000},
	}
	get := func() []MovieTrailer {
		t.Helper()
		var m MovieDetail
		if code := doJSON(t, srv.client, http.MethodGet, fmt.Sprintf("%s/api/movies/%d", srv.URL, tl.movie.ID), "", &m).StatusCode; code != 200 {
			t.Fatalf("getMovie -> %d", code)
		}
		return m.Trailers
	}
	if got, want := get(), append(slices.Clone(files), MovieTrailer{Source: TrailerYouTube, Name: "Official Trailer", Key: "trailerKey1"}); !reflect.DeepEqual(got, want) {
		t.Errorf("trailers = %+v, want %+v", got, want)
	}
	if code := doJSON(t, srv.client, http.MethodPut, srv.URL+"/api/settings", `{"trailersYouTube":false}`, nil).StatusCode; code != 200 {
		t.Fatalf("turning YouTube trailers off -> %d", code)
	}
	if got := get(); !reflect.DeepEqual(got, files) {
		t.Errorf("trailers with YouTube off = %+v, want the files", got)
	}
}

func TestTrailersLeftOutOfLibraryTotals(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx := t.Context()
	tl := newTrailerLibrary(t, srv.q)
	if size, err := srv.q.SumLibrarySize(ctx, tl.lib.ID); err != nil || size != tl.movieSize {
		t.Errorf("library size = %v (%v), want %d", size, err, tl.movieSize)
	}
	counts, err := srv.q.CountMediaFilesByStatus(ctx)
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range counts {
		if c.LibraryID == tl.lib.ID && c.Count != 1 {
			t.Errorf("library file count = %d, want 1", c.Count)
		}
	}
	page, err := srv.q.ListFilesNeedingTrickplay(ctx, db.ListFilesNeedingTrickplayParams{AlgoVersion: 1, BeforeSeen: "9999", BeforeID: 1 << 62, Page: 100})
	if err != nil {
		t.Fatal(err)
	}
	for _, f := range page {
		if f.ID == tl.trailer.ID || f.ID == tl.teaser.ID {
			t.Errorf("trailer %d needs seek thumbnails", f.ID)
		}
	}
	if len(page) == 0 {
		t.Error("the movie's file needs no seek thumbnails")
	}
	c, err := srv.q.CountTrickplay(ctx, db.CountTrickplayParams{AlgoVersion: 1})
	if err != nil {
		t.Fatal(err)
	}
	if c.Files != 1 {
		t.Errorf("trickplay files = %d, want the movie's only", c.Files)
	}
}

// Deleting a movie deletes its trailer files when none of its files stays
// (decided 2026-11-05): with its folder when Radarr removes the movie, else
// the server deletes them; while a file stays, they do too.
func TestDeleteMovieTakesItsTrailers(t *testing.T) {
	t.Parallel()
	rec := &lockedRecorder{}
	var svc *integrations.Service
	srv := newTestServerWith(t, func(d *Deps) {
		svc = integrations.New(d.Queries, rec, slog.New(slog.NewTextHandler(io.Discard, nil)))
		d.Integrations = svc
	})
	ctx := t.Context()
	tl := newTrailerLibrary(t, srv.q)
	app := arrtest.New(t, "radarr")
	app.Movies = []map[string]any{{"id": 5, "tmdbId": 700002, "monitored": true, "hasFile": true, "path": "/movies/Quiet Field (2019)",
		"movieFile": map[string]any{"id": 51, "path": "/movies/Quiet Field (2019)/Quiet Field (2019).mp4", "relativePath": "Quiet Field (2019).mp4", "size": tl.movieSize}}}
	if _, err := svc.Create(ctx, integrations.Input{Kind: "radarr", Name: new("Radarr"), URL: new(app.URL), APIKey: new(app.Key),
		PathMap: &integrations.PathMap{{From: "/movies", To: tl.lib.Path}}}); err != nil {
		t.Fatal(err)
	}
	base := srv.URL + "/api"
	exists := func(f db.MediaFile) bool {
		_, err := os.Stat(f.Path)
		return !errors.Is(err, fs.ErrNotExist)
	}

	var p integrations.DeletePreview
	doJSON(t, srv.client, http.MethodGet, fmt.Sprintf("%s/delete/preview?type=movie&id=%d", base, tl.movie.ID), "", &p)
	if !p.Managed || p.Trailers != 2 || p.Kept != 0 {
		t.Errorf("preview = %+v", p)
	}

	// Kept in Radarr, only the movie's file goes: the server deletes the
	// trailers.
	var res integrations.DeleteResult
	if code := doJSON(t, srv.client, http.MethodPost, fmt.Sprintf("%s/movies/%d/delete", base, tl.movie.ID), `{"keep":true}`, &res).StatusCode; code != 200 || res.Trailers != 2 {
		t.Fatalf("delete keeping it in Radarr -> %d %+v", code, res)
	}
	if exists(tl.trailer) || exists(tl.teaser) {
		t.Error("trailers left after the movie's files went")
	}
	if !exists(tl.file) {
		t.Error("the server deleted the movie's file itself")
	}
	if !rec.has(tl.trailer.Path) {
		t.Error("deleted trailers not scanned")
	}

	// Removed from Radarr: its folder goes, trailers and all, so the server
	// leaves them to it (the fake Radarr removes nothing).
	for _, f := range []db.MediaFile{tl.trailer, tl.teaser} {
		if err := os.WriteFile(f.Path, tl.trailerFileBytes, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	if code := doJSON(t, srv.client, http.MethodPost, fmt.Sprintf("%s/movies/%d/delete", base, tl.movie.ID), `{}`, &res).StatusCode; code != 200 || res.Trailers != 2 {
		t.Fatalf("delete -> %d %+v", code, res)
	}
	if !exists(tl.trailer) || !exists(tl.teaser) {
		t.Error("the server deleted trailers the app's folder delete takes")
	}
}

// While one of the movie's files stays (no app holds it), its trailers stay.
func TestDeleteMovieKeepsTrailersWhileAFileStays(t *testing.T) {
	t.Parallel()
	var svc *integrations.Service
	srv := newTestServerWith(t, func(d *Deps) {
		svc = integrations.New(d.Queries, &lockedRecorder{}, slog.New(slog.NewTextHandler(io.Discard, nil)))
		d.Integrations = svc
	})
	ctx := t.Context()
	tl := newTrailerLibrary(t, srv.q)
	other := filepath.Join(tl.lib.Path, "Elsewhere", "Quiet Field (2019) - 720p.mkv")
	f, err := srv.q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: tl.lib.ID, Path: other, Size: 5, Mtime: db.FormatTime(time.Now()),
		AddedAt: db.FormatTime(time.Now()), Parsed: "{}", LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	if err := srv.q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &tl.movie.ID, ID: f.ID}); err != nil {
		t.Fatal(err)
	}
	app := arrtest.New(t, "radarr")
	app.Movies = []map[string]any{{"id": 5, "tmdbId": 700002, "monitored": true, "hasFile": true, "path": "/movies/Quiet Field (2019)",
		"movieFile": map[string]any{"id": 51, "path": "/movies/Quiet Field (2019)/Quiet Field (2019).mp4", "relativePath": "Quiet Field (2019).mp4", "size": tl.movieSize}}}
	if _, err := svc.Create(ctx, integrations.Input{Kind: "radarr", Name: new("Radarr"), URL: new(app.URL), APIKey: new(app.Key),
		PathMap: &integrations.PathMap{{From: "/movies", To: tl.lib.Path}}}); err != nil {
		t.Fatal(err)
	}
	var res integrations.DeleteResult
	if code := doJSON(t, srv.client, http.MethodPost, fmt.Sprintf("%s/api/movies/%d/delete", srv.URL, tl.movie.ID), `{"keep":true}`, &res).StatusCode; code != 200 || res.Trailers != 0 {
		t.Fatalf("delete -> %d %+v", code, res)
	}
	for _, f := range []db.MediaFile{tl.trailer, tl.teaser} {
		if _, err := os.Stat(f.Path); err != nil {
			t.Errorf("trailer gone while a file of the movie stays: %v", err)
		}
	}
}
