package api

import (
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/integrations"
	"watchmymedia/server/internal/integrations/arrtest"
)

func TestDeleteAPI(t *testing.T) {
	t.Parallel()
	rec := &pathRecorder{}
	var svc *integrations.Service
	srv := newTestServerWith(t, func(d *Deps) {
		svc = integrations.New(d.Queries, rec, slog.New(slog.NewTextHandler(io.Discard, nil)))
		d.Integrations = svc
	})
	ctx := t.Context()
	q := srv.q
	libraryPath := t.TempDir()
	lib, err := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "movie", Name: "Managed movies", Path: libraryPath})
	if err != nil {
		t.Fatal(err)
	}
	movie, err := q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: lib.ID, TmdbID: 700001, Title: "Quiet Field", Genres: "[]", Credits: "[]"})
	if err != nil {
		t.Fatal(err)
	}
	height := int64(2160)
	folder := filepath.Join(libraryPath, "Quiet Field (2019)")
	f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: lib.ID, Path: filepath.Join(folder, "Quiet Field (2019).mkv"), Size: 10,
		Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()), Parsed: "{}", LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1,
		Height: &height, Hdr: "dolbyvision"})
	if err != nil {
		t.Fatal(err)
	}
	if err := q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &movie.ID, ID: f.ID}); err != nil {
		t.Fatal(err)
	}

	app := arrtest.New(t, "radarr")
	app.Movies = []map[string]any{{"id": 5, "tmdbId": 700001, "monitored": true, "hasFile": true, "path": "/movies/Quiet Field (2019)",
		"movieFile": map[string]any{"id": 51, "path": "/movies/Quiet Field (2019)/Quiet Field (2019).mkv", "relativePath": "Quiet Field (2019).mkv", "size": 10}}}
	if _, err := svc.Create(ctx, integrations.Input{Kind: "radarr", Name: new("Radarr"), URL: new(app.URL), APIKey: new(app.Key),
		PathMap: &integrations.PathMap{{From: "/movies", To: libraryPath}}}); err != nil {
		t.Fatal(err)
	}

	var p integrations.DeletePreview
	doJSON(t, srv.client, http.MethodGet, srv.URL+fmt.Sprintf("/api/delete/preview?type=movie&id=%d", movie.ID), "", &p)
	if !p.Managed || len(p.Apps) != 1 || p.Apps[0].Quality != "4K Dolby Vision" || p.Apps[0].Folder != "/movies/Quiet Field (2019)" {
		t.Errorf("preview = %+v", p)
	}
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/delete/preview?type=movie&id=999", "", nil).StatusCode; code != http.StatusNotFound {
		t.Errorf("preview of no movie -> %d", code)
	}

	// Members can't see or do any of it.
	doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/users", `{"name":"sam","password":"sampass1"}`, nil)
	jar, _ := cookiejar.New(nil)
	member := &http.Client{Jar: jar}
	res := doJSON(t, member, http.MethodPost, srv.URL+"/api/auth/login", `{"name":"sam","password":"sampass1"}`, nil)
	for _, try := range []func() (*http.Response, error){
		func() (*http.Response, error) {
			return member.Get(srv.URL + fmt.Sprintf("/api/delete/preview?type=movie&id=%d", movie.ID))
		},
		func() (*http.Response, error) {
			return member.Post(srv.URL+fmt.Sprintf("/api/movies/%d/delete", movie.ID), "application/json", strings.NewReader(`{}`))
		},
		func() (*http.Response, error) {
			return member.Post(srv.URL+fmt.Sprintf("/api/files/%d/replace", f.ID), "application/json", nil)
		},
	} {
		res, err := try()
		if err != nil {
			t.Fatal(err)
		}
		_ = res.Body.Close()
		if res.StatusCode != http.StatusForbidden {
			t.Errorf("member -> %d, want 403", res.StatusCode)
		}
	}
	if n := len(app.CallsTo(http.MethodDelete, "/movie/5")); n != 0 {
		t.Fatalf("a member's delete reached Radarr")
	}
	options := func(c *http.Client) RequestOptions {
		t.Helper()
		var o RequestOptions
		res, err := c.Get(srv.URL + "/api/requests/options")
		if err != nil {
			t.Fatal(err)
		}
		_ = json.NewDecoder(res.Body).Decode(&o)
		_ = res.Body.Close()
		return o
	}
	if !options(srv.client).Delete || options(member).Delete {
		t.Errorf("options.delete: admin %v, member %v; want true, false", options(srv.client).Delete, options(member).Delete)
	}

	// Who can delete: admins only by default, and only admins see or change it.
	var ds DeleteSettings
	if doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/settings/deleting", "", &ds); ds.Access != "admins" {
		t.Errorf("default access = %q", ds.Access)
	}
	setAccess := func(c *http.Client, body string) int {
		t.Helper()
		req, _ := http.NewRequestWithContext(t.Context(), http.MethodPut, srv.URL+"/api/settings/deleting", strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		res, err := c.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		_ = res.Body.Close()
		return res.StatusCode
	}
	if code := setAccess(member, `{"access":"everyone"}`); code != http.StatusForbidden {
		t.Errorf("member sets access -> %d, want 403", code)
	}
	if code := setAccess(srv.client, `{"access":"anyone"}`); code != http.StatusUnprocessableEntity {
		t.Errorf("bad access -> %d, want 422", code)
	}
	if code := setAccess(srv.client, `{"access":"everyone"}`); code != http.StatusOK {
		t.Fatalf("set access -> %d", code)
	}
	if !options(member).Delete {
		t.Errorf("member's options.delete is false with access everyone")
	}

	// With everyone, a member may preview and delete episodes and movies,
	// but shows, seasons and replacing stay with admins.
	for _, c := range []struct {
		name, method, path string
		want               int
	}{
		{"preview movie", http.MethodGet, fmt.Sprintf("/api/delete/preview?type=movie&id=%d", movie.ID), http.StatusOK},
		{"delete episode", http.MethodPost, "/api/episodes/999/delete", http.StatusNotFound},
		{"preview show", http.MethodGet, "/api/delete/preview?type=show&id=1", http.StatusForbidden},
		{"delete show", http.MethodPost, "/api/shows/1/delete", http.StatusForbidden},
		{"delete season", http.MethodPost, "/api/shows/1/seasons/1/delete", http.StatusForbidden},
		{"replace", http.MethodPost, fmt.Sprintf("/api/files/%d/replace", f.ID), http.StatusForbidden},
		{"read access", http.MethodGet, "/api/settings/deleting", http.StatusForbidden},
	} {
		var body io.Reader
		if c.method == http.MethodPost {
			body = strings.NewReader(`{}`)
		}
		req, _ := http.NewRequestWithContext(t.Context(), c.method, srv.URL+c.path, body)
		req.Header.Set("Content-Type", "application/json")
		res, err := member.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		_ = res.Body.Close()
		if res.StatusCode != c.want {
			t.Errorf("member %s -> %d, want %d", c.name, res.StatusCode, c.want)
		}
	}

	var out integrations.DeleteResult
	if code := doJSON(t, member, http.MethodPost, srv.URL+fmt.Sprintf("/api/movies/%d/delete", movie.ID), `{"exclude":true}`, &out).StatusCode; code != http.StatusOK || len(out.Apps) != 1 || out.Apps[0].Error != "" {
		t.Fatalf("delete -> %d %+v", code, out)
	}
	if c := app.CallsTo(http.MethodDelete, "/movie/5"); len(c) != 1 || c[0].Query != "addImportExclusion=true&deleteFiles=true" {
		t.Errorf("Radarr deletes = %+v", c)
	}
	if len(rec.paths) != 1 || rec.paths[0] != folder {
		t.Errorf("scanned %v", rec.paths)
	}
	// Gone from Radarr, so there's nothing left to delete.
	res = doJSON(t, srv.client, http.MethodPost, srv.URL+fmt.Sprintf("/api/movies/%d/delete", movie.ID), `{}`, nil)
	if res.StatusCode != http.StatusUnprocessableEntity {
		t.Errorf("second delete -> %d, want 422", res.StatusCode)
	}
}
