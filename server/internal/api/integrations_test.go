package api

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"path/filepath"
	"strings"
	"testing"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/integrations"
	"watchmymedia/server/internal/integrations/arrtest"
)

type pathRecorder struct{ paths []string }

func (p *pathRecorder) RequestPaths(_ int64, paths []string) { p.paths = append(p.paths, paths...) }

func TestIntegrationsAPI(t *testing.T) {
	t.Parallel()
	rec := &pathRecorder{}
	srv := newTestServerWith(t, func(d *Deps) {
		d.Integrations = integrations.New(d.Queries, rec, slog.New(slog.NewTextHandler(io.Discard, nil)))
	})
	app := arrtest.New(t, "radarr")
	libraryPath := t.TempDir()
	if _, err := srv.q.UpsertLibrary(t.Context(), db.UpsertLibraryParams{Type: "movie", Name: "Mapped movies", Path: libraryPath}); err != nil {
		t.Fatal(err)
	}
	input, err := json.Marshal(map[string]any{
		"kind": "radarr", "name": "Radarr", "url": app.URL, "apiKey": app.Key,
		"pathMap": integrations.PathMap{{From: "/movies", To: libraryPath}},
	})
	if err != nil {
		t.Fatal(err)
	}

	res, err := srv.client.Post(srv.URL+"/api/integrations", "application/json",
		bytes.NewReader(input))
	if err != nil {
		t.Fatal(err)
	}
	var c integrations.Connection
	_ = json.NewDecoder(res.Body).Decode(&c)
	_ = res.Body.Close()
	if res.StatusCode != http.StatusCreated || c.ID == 0 || c.APIKeyHint != "1234" {
		t.Fatalf("create -> %d %+v", res.StatusCode, c)
	}

	res, _ = srv.client.Post(srv.URL+"/api/integrations", "application/json",
		strings.NewReader(`{"kind":"radarr","name":"Bad","url":"`+app.URL+`","apiKey":"nope"}`))
	_ = res.Body.Close()
	if res.StatusCode != http.StatusBadGateway {
		t.Errorf("refused key -> %d, want 502", res.StatusCode)
	}

	res, _ = srv.client.Get(srv.URL + "/api/integrations")
	body, _ := io.ReadAll(res.Body)
	_ = res.Body.Close()
	if !strings.Contains(string(body), `"name":"Radarr"`) || strings.Contains(string(body), app.Key) {
		t.Errorf("list = %s", body)
	}

	// The webhook needs no session, only the token.
	hook := func(user, pass string) int {
		req, _ := http.NewRequestWithContext(t.Context(), http.MethodPost, srv.URL+"/api/integrations/1/webhook",
			strings.NewReader(`{"eventType":"Download","movie":{"id":1,"folderPath":"/movies/Quiet Field (2019)"},"movieFile":{"path":"/movies/Quiet Field (2019)/q.mkv"}}`))
		if user != "" {
			req.SetBasicAuth(user, pass)
		}
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		_ = res.Body.Close()
		return res.StatusCode
	}
	if code := hook("", ""); code != http.StatusUnauthorized {
		t.Errorf("no token -> %d", code)
	}
	row, _ := srv.q.GetIntegration(t.Context(), c.ID)
	if code := hook(integrations.WebhookUser, row.WebhookToken); code != http.StatusNoContent {
		t.Fatalf("webhook -> %d", code)
	}
	if len(rec.paths) != 1 || rec.paths[0] != filepath.Join(libraryPath, "Quiet Field (2019)", "q.mkv") {
		t.Errorf("scanned %v", rec.paths)
	}
}
