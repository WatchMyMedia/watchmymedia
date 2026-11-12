package api

import (
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/danielgtaylor/huma/v2"
	"golang.org/x/text/unicode/norm"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/settings"
	"watchmymedia/server/internal/tmdb/tmdbtest"
)

func touch(t *testing.T, path string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, nil, 0o600); err != nil {
		t.Fatal(err)
	}
}

// The folder check counts episodes in the usual Show/Season/episode layout,
// which is three levels down, and no deeper.
func TestCheckPathCountsSeasonFolders(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	touch(t, filepath.Join(dir, "Harbor Lights", "Season 1", "Harbor Lights - S01E01.mkv"))
	touch(t, filepath.Join(dir, "Harbor Lights", "Season 1", "Harbor Lights - S01E02.mkv"))
	touch(t, filepath.Join(dir, "Juniper Bay", "Season 2", "Juniper Bay - S02E01.mp4"))
	touch(t, filepath.Join(dir, "Juniper Bay", "Season 2", "Extras", "Behind the scenes.mkv"))
	touch(t, filepath.Join(dir, ".trash", "Old - S01E01.mkv"))
	touch(t, filepath.Join(dir, "notes.txt"))
	check := checkPath(dir)
	if !check.Exists || !check.Readable || check.VideoFiles != 3 || check.Truncated {
		t.Errorf("check = %+v", check)
	}
	if strings.Join(check.Sample, ",") != "Harbor Lights,Juniper Bay,notes.txt" {
		t.Errorf("sample = %q", check.Sample)
	}
}

// A folder with more folders than the check reads says the count is short.
func TestCheckPathStopsAtFolderLimit(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	for i := range checkFolders + 5 {
		touch(t, filepath.Join(dir, fmt.Sprintf("Show %04d", i), "episode.mkv"))
	}
	check := checkPath(dir)
	if !check.Truncated || check.VideoFiles != checkFolders-1 {
		t.Errorf("videoFiles %d, truncated %v", check.VideoFiles, check.Truncated)
	}
}

// A library can't sit inside another library's folder or hold one, whichever
// is added or moved first; a folder that only shares a prefix is fine.
func TestLibraryFoldersDoNotOverlap(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	root := t.TempDir()
	movies := filepath.Join(root, "media", "Movies")
	for _, d := range []string{movies, filepath.Join(movies, "Classics"), filepath.Join(root, "media", "Movies2"), filepath.Join(root, "tv")} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	create := func(name, path string) (int, Library) {
		var lib Library
		res := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/libraries", fmt.Sprintf(`{"type":"movie","name":%q,"path":%q}`, name, path), &lib)
		return res.StatusCode, lib
	}
	if code, _ := create("Movies", movies); code != http.StatusCreated {
		t.Fatalf("first library -> %d", code)
	}
	if code, _ := create("Classics", filepath.Join(movies, "Classics")); code != http.StatusConflict {
		t.Errorf("inside another -> %d", code)
	}
	if code, _ := create("Everything", filepath.Join(root, "media")); code != http.StatusConflict {
		t.Errorf("holding another -> %d", code)
	}
	if code, _ := create("More movies", filepath.Join(root, "media", "Movies2")); code != http.StatusCreated {
		t.Errorf("a sibling sharing the prefix -> %d", code)
	}
	code, tv := create("TV", filepath.Join(root, "tv"))
	if code != http.StatusCreated {
		t.Fatalf("tv -> %d", code)
	}
	move := func(path string) int {
		return doJSON(t, srv.client, http.MethodPut, srv.URL+fmt.Sprintf("/api/libraries/%d", tv.ID), fmt.Sprintf(`{"name":"TV","path":%q}`, path), nil).StatusCode
	}
	if code := move(filepath.Join(movies, "Classics")); code != http.StatusConflict {
		t.Errorf("moving inside another -> %d", code)
	}
	if code := move(filepath.Join(root, "tv")); code != http.StatusOK {
		t.Errorf("saving a library in its own folder -> %d", code)
	}
}

// Paths are compared with accents composed: the same folder spelled with a
// decomposed accent is still the same folder.
func TestLibraryOverlapComposesAccents(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	composed := "/srv/media/Fiancé Films"
	if _, err := srv.q.CreateLibrary(t.Context(), db.CreateLibraryParams{Type: "movie", Name: "Fiancé", Path: composed}); err != nil {
		t.Fatal(err)
	}
	decomposed := norm.NFD.String(composed)
	if decomposed == composed {
		t.Fatal("test path has no accent to decompose")
	}
	deps := Deps{Queries: srv.q}
	var se huma.StatusError
	if err := overlapping(t.Context(), deps, 0, decomposed); !errors.As(err, &se) || se.GetStatus() != http.StatusConflict {
		t.Errorf("same folder, decomposed -> %v", err)
	}
	if err := overlapping(t.Context(), deps, 0, decomposed+"/Extras"); !errors.As(err, &se) || se.GetStatus() != http.StatusConflict {
		t.Errorf("inside it, decomposed -> %v", err)
	}
}

// Adding a library from the API marks the server seeded, so the configured
// folders are not added at the next start even if it is removed again.
func TestCreateLibraryMarksSeeded(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	dir := t.TempDir()
	if code := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/libraries", fmt.Sprintf(`{"type":"tv","name":"TV","path":%q}`, dir), nil).StatusCode; code != http.StatusCreated {
		t.Fatalf("create -> %d", code)
	}
	if v, _ := srv.q.GetSetting(t.Context(), settings.KeyLibrariesSeeded); v != "1" {
		t.Errorf("seeded = %q", v)
	}
}

// A library's last scan error is in its reply, with when it happened.
func TestLibraryShowsLastScanError(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	libs, _ := srv.q.ListLibraries(t.Context())
	msg := "library root is not accessible: stat /m: no such file or directory"
	at := "2026-11-02T18:00:00Z"
	if err := srv.q.SetLibraryScanError(t.Context(), db.SetLibraryScanErrorParams{LastScanError: &msg, LastScanErrorAt: &at, ID: libs[0].ID}); err != nil {
		t.Fatal(err)
	}
	var lib Library
	doJSON(t, srv.client, http.MethodGet, srv.URL+fmt.Sprintf("/api/libraries/%d", libs[0].ID), "", &lib)
	if lib.LastScanError == nil || *lib.LastScanError != msg || lib.LastScanErrorAt == nil || lib.LastScanErrorAt.Format("2006-01-02T15:04:05Z07:00") != at {
		t.Errorf("library = %+v", lib)
	}
	var list LibraryList
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/libraries", "", &list)
	if len(list.Items) != 1 || list.Items[0].LastScanError == nil {
		t.Errorf("list = %+v", list.Items)
	}
}

// A key TMDB refuses is a 422; TMDB out of reach is a 503, and neither is
// saved.
func TestTMDBKeyCheckTellsRefusedFromUnreachable(t *testing.T) {
	t.Parallel()
	fake := tmdbtest.New(t)
	fake.JSON("/configuration", map[string]any{"images": map[string]any{}})
	srv := newTestServerWith(t, func(d *Deps) { d.TMDB = fake.Client() })
	if code := doJSON(t, srv.client, http.MethodPut, srv.URL+"/api/settings", `{"tmdbApiKey":"wrong-key"}`, nil).StatusCode; code != http.StatusUnprocessableEntity {
		t.Errorf("refused key -> %d", code)
	}
	fake.Close()
	if code := doJSON(t, srv.client, http.MethodPut, srv.URL+"/api/settings", `{"tmdbApiKey":"`+tmdbtest.Key+`"}`, nil).StatusCode; code != http.StatusServiceUnavailable {
		t.Errorf("TMDB unreachable -> %d", code)
	}
	if v, _ := srv.q.GetSetting(t.Context(), settings.KeyTMDBAPIKey); v != "" {
		t.Errorf("a key was saved: %q", v)
	}
}

// The menu bar app's status says setup is unfinished while the server has
// no library and no tuner.
func TestLocalStatusSetupIncomplete(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	var out LocalStatus
	doJSON(t, nil, http.MethodGet, srv.URL+"/api/playback/local", "", &out)
	if out.SetupRequired || out.SetupIncomplete {
		t.Errorf("with a library: %+v", out)
	}
	libs, _ := srv.q.ListLibraries(t.Context())
	if err := srv.q.DeleteLibrary(t.Context(), libs[0].ID); err != nil {
		t.Fatal(err)
	}
	doJSON(t, nil, http.MethodGet, srv.URL+"/api/playback/local", "", &out)
	if out.SetupRequired || !out.SetupIncomplete {
		t.Errorf("without one: %+v", out)
	}
}
