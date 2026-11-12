package integrations

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/integrations/arrtest"
)

type fakeScanner struct {
	mu    sync.Mutex
	calls map[int64][]string
}

func (f *fakeScanner) RequestPaths(lib int64, paths []string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.calls == nil {
		f.calls = map[int64][]string{}
	}
	f.calls[lib] = append(f.calls[lib], paths...)
}

func setup(t *testing.T) (*Service, *db.Queries, *fakeScanner) {
	t.Helper()
	conn, err := db.Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := db.Migrate(context.Background(), conn); err != nil {
		t.Fatal(err)
	}
	q := db.New(conn)
	sc := &fakeScanner{}
	return New(q, sc, slog.New(slog.NewTextHandler(io.Discard, nil))), q, sc
}

func TestCreateChecksTheApp(t *testing.T) {
	s, _, _ := setup(t)
	ctx := context.Background()
	app := arrtest.New(t, "radarr")

	if _, err := s.Create(ctx, Input{Kind: "radarr", Name: new("Radarr"), URL: new(app.URL), APIKey: new("wrong")}); err == nil {
		t.Fatal("a refused key was saved")
	}
	if _, err := s.Create(ctx, Input{Kind: "sonarr", Name: new("Radarr"), URL: new(app.URL), APIKey: new(app.Key)}); err == nil ||
		!strings.Contains(err.Error(), "not sonarr") {
		t.Fatalf("Radarr saved as Sonarr: %v", err)
	}
	app.Version = "4.7.5.7809"
	if _, err := s.Create(ctx, Input{Kind: "radarr", Name: new("Radarr"), URL: new(app.URL), APIKey: new(app.Key)}); err == nil {
		t.Fatal("Radarr 4 was accepted")
	}
	app.Version = "6.4.4.10685"
	c, err := s.Create(ctx, Input{Kind: "radarr", Name: new("Radarr"), URL: new(app.URL + "/"), APIKey: new(app.Key)})
	if err != nil {
		t.Fatal(err)
	}
	if c.URL != app.URL || c.APIKeyHint != "1234" || !c.Status.OK || c.Status.Version != "6.4.4.10685" {
		t.Errorf("connection = %+v", c)
	}
	if strings.Contains(c.APIKeyHint, "fake") {
		t.Error("the key was sent back")
	}
}

func TestWebhookScansMappedPaths(t *testing.T) {
	s, q, sc := setup(t)
	ctx := context.Background()
	tvRoot := filepath.Join(t.TempDir(), "TV")
	lib, err := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: tvRoot})
	if err != nil {
		t.Fatal(err)
	}
	app := arrtest.New(t, "sonarr")
	app.Series = []map[string]any{{"id": 7, "title": "Harbor Lights", "path": "/tv/Harbor Lights", "tvdbId": 900001, "tmdbId": 800001}}
	c, err := s.Create(ctx, Input{Kind: "sonarr", Name: new("Sonarr"), URL: new(app.URL), APIKey: new(app.Key),
		PathMap: &PathMap{{From: "/tv", To: tvRoot}}})
	if err != nil {
		t.Fatal(err)
	}
	row, _ := q.GetIntegration(ctx, c.ID)

	post := func(token, body string) int {
		req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/api/integrations/1/webhook", strings.NewReader(body))
		req.SetBasicAuth(WebhookUser, token)
		w := httptest.NewRecorder()
		s.ServeWebhook(w, req, c.ID)
		return w.Code
	}
	if code := post("wrong", `{"eventType":"Test"}`); code != http.StatusUnauthorized {
		t.Errorf("wrong token -> %d", code)
	}
	if code := post(row.WebhookToken, `{"eventType":"Test"}`); code != http.StatusNoContent {
		t.Errorf("test event -> %d", code)
	}
	if got, _ := s.Get(c.ID); got.Status.Webhook.LastTestAt == nil {
		t.Error("the test event was not recorded")
	}
	code := post(row.WebhookToken, `{"eventType":"Download","series":{"id":7,"path":"/tv/Harbor Lights","tmdbId":800001},
		"episodeFile":{"path":"/tv/Harbor Lights/Season 01/e1.mkv"}}`)
	if code != http.StatusNoContent {
		t.Fatalf("download -> %d", code)
	}
	want := filepath.Join(tvRoot, "Harbor Lights", "Season 01", "e1.mkv")
	if got := sc.calls[lib.ID]; len(got) != 1 || got[0] != want {
		t.Errorf("scanned %v, want [%s]", got, want)
	}
	post(row.WebhookToken, `{"eventType":"Download","series":{"id":8,"path":"/elsewhere/X"},"episodeFile":{"path":"/elsewhere/X/e.mkv"}}`)
	if got, _ := s.Get(c.ID); got.Status.Webhook.Outside != 1 {
		t.Errorf("outside = %d, want 1", got.Status.Webhook.Outside)
	}

	// The index says which show a file in the series folder is.
	if tmdb, tvdb := s.Hint(lib, want); tmdb != 800001 || tvdb != 900001 {
		t.Errorf("Hint = %d, %d", tmdb, tvdb)
	}
	if tmdb, _ := s.Hint(lib, filepath.Join(tvRoot, "Unknown Show", "e.mkv")); tmdb != 0 {
		t.Errorf("Hint for a folder Sonarr lacks = %d", tmdb)
	}
}

func TestConnectWebhook(t *testing.T) {
	s, q, _ := setup(t)
	ctx := context.Background()
	app := arrtest.New(t, "radarr")
	c, err := s.Create(ctx, Input{Kind: "radarr", Name: new("Radarr"), URL: new(app.URL), APIKey: new(app.Key)})
	if err != nil {
		t.Fatal(err)
	}
	if err := s.ConnectWebhook(ctx, c.ID, "http://192.168.1.20:27587/"); err != nil {
		t.Fatal(err)
	}
	row, _ := q.GetIntegration(ctx, c.ID)
	saved := app.CallsTo(http.MethodPost, "/notification")
	if len(saved) != 1 {
		t.Fatalf("notification saves = %d", len(saved))
	}
	b := saved[0].Body
	if b["name"] != "WatchMyMedia" || b["onDownload"] != true || b["onGrab"] != true || b["onHealthIssue"] != false {
		t.Errorf("webhook = %v", b)
	}
	fields := map[string]any{}
	for _, f := range b["fields"].([]any) {
		m := f.(map[string]any)
		fields[m["name"].(string)] = m["value"]
	}
	if fields["url"] != "http://192.168.1.20:27587/api/integrations/1/webhook" || fields["username"] != WebhookUser || fields["password"] != row.WebhookToken {
		t.Errorf("fields = %v", fields)
	}
	// A second connect updates the one it made.
	app.Lock()
	app.Notifications[0]["implementation"] = "Webhook"
	app.Unlock()
	if err := s.ConnectWebhook(ctx, c.ID, "http://192.168.1.21:27587"); err != nil {
		t.Fatal(err)
	}
	if n := len(app.CallsTo(http.MethodPut, "/notification/101")); n != 1 {
		t.Errorf("updates = %d", n)
	}
	app.RefuseHook = "Unable to send test message"
	if err := s.ConnectWebhook(ctx, c.ID, "http://10.0.0.9:27587"); err == nil || !strings.Contains(err.Error(), "Unable to send") {
		t.Errorf("refused webhook: %v", err)
	}
}

func TestDetails(t *testing.T) {
	s, q, _ := setup(t)
	ctx := context.Background()
	movies := filepath.Join(t.TempDir(), "Movies")
	lib, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "movie", Name: "Movies", Path: movies})
	app := arrtest.New(t, "radarr")
	app.RootFolders = []map[string]any{{"id": 1, "path": "/movies"}, {"id": 2, "path": movies}}
	app.Profiles = []map[string]any{{"id": 4, "name": "HD-1080p"}}
	app.Movies = []map[string]any{{"id": 1, "path": movies + "/Quiet Field (2019)", "tmdbId": 700001}}
	app.RecycleBin = "/data/recycle"
	c, err := s.Create(ctx, Input{Kind: "radarr", Name: new("Radarr"), URL: new(app.URL), APIKey: new(app.Key)})
	if err != nil {
		t.Fatal(err)
	}
	d, err := s.Details(ctx, c.ID)
	if err != nil {
		t.Fatal(err)
	}
	if len(d.RootFolders) != 2 || d.RootFolders[0].LibraryID != 0 || d.RootFolders[1].LibraryID != lib.ID || d.RootFolders[1].Items != 1 {
		t.Errorf("root folders = %+v", d.RootFolders)
	}
	if len(d.QualityProfiles) != 1 || d.RecycleBin != "/data/recycle" {
		t.Errorf("details = %+v", d)
	}
}
