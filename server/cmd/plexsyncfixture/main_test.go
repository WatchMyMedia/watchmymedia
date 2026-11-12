package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/api"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/plexsync"
)

type browser struct {
	t      *testing.T
	base   string
	client *http.Client
}

func (b browser) call(method, path string, body, out any, status int) {
	b.t.Helper()
	var encoded []byte
	var err error
	if body != nil {
		encoded, err = json.Marshal(body)
		if err != nil {
			b.t.Fatal(err)
		}
	}
	req, err := http.NewRequestWithContext(b.t.Context(), method, b.base+path, bytes.NewReader(encoded))
	if err != nil {
		b.t.Fatal(err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	response, err := b.client.Do(req)
	if err != nil {
		b.t.Fatal(err)
	}
	defer func() { _ = response.Body.Close() }()
	data, err := io.ReadAll(response.Body)
	if err != nil {
		b.t.Fatal(err)
	}
	if response.StatusCode != status {
		b.t.Fatalf("%s %s -> %d %s; want %d", method, path, response.StatusCode, data, status)
	}
	if strings.Contains(string(data), "synthetic-") {
		b.t.Fatalf("%s leaked a fictional provider credential", path)
	}
	if out != nil {
		if err := json.Unmarshal(data, out); err != nil {
			b.t.Fatalf("%s: %v (%s)", path, err, data)
		}
	}
}

func newBrowser(t *testing.T, server string, name string) browser {
	t.Helper()
	jar, err := cookiejar.New(nil)
	if err != nil {
		t.Fatal(err)
	}
	b := browser{t: t, base: server, client: &http.Client{Jar: jar, Timeout: 10 * time.Second}}
	if name != "" {
		b.call(http.MethodPost, "/api/auth/login", map[string]string{"name": name, "password": fixturePassword}, nil, http.StatusOK)
	}
	return b
}

func fixtureServer(t *testing.T) (*fixture, *httptest.Server) {
	t.Helper()
	f, err := newFixture(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(f.close)
	server := httptest.NewServer(f.handler)
	t.Cleanup(server.Close)
	t.Cleanup(func() {
		f.reporter.mu.Lock()
		defer f.reporter.mu.Unlock()
		if len(f.reporter.errors) > 0 {
			t.Errorf("Plex fake contract errors: %v", f.reporter.errors)
		}
	})
	return f, server
}

func (b browser) preview(base string) plexsync.PlexPreview {
	b.t.Helper()
	var p plexsync.PlexPreview
	b.call(http.MethodPost, base+"/previews", nil, &p, http.StatusAccepted)
	deadline := time.Now().Add(10 * time.Second)
	for p.Status == "running" && time.Now().Before(deadline) {
		b.call(http.MethodGet, base+"/previews/"+p.ID, nil, &p, http.StatusOK)
	}
	if p.Status != "complete" {
		b.t.Fatalf("preview did not complete: %+v", p)
	}
	return p
}

func (b browser) waitRun(base string, run plexsync.PlexRun) plexsync.PlexRun {
	b.t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for (run.Status == "queued" || run.Status == "running") && time.Now().Before(deadline) {
		b.call(http.MethodGet, fmt.Sprintf("%s/runs/%d", base, run.ID), nil, &run, http.StatusOK)
	}
	if run.Status != "complete" {
		b.t.Fatalf("run did not complete: %+v", run)
	}
	return run
}

func (b browser) importPreview(base string, p plexsync.PlexPreview) plexsync.PlexRun {
	b.t.Helper()
	var run plexsync.PlexRun
	b.call(http.MethodPost, base+"/runs", plexsync.PlexRunStart{PreviewID: p.ID}, &run, http.StatusAccepted)
	return b.waitRun(base, run)
}

// The browser's administrative routes feed the same user-scoped movie,
// season, Play next and event contracts read by Android, Apple and Cast.
// No synthetic Plex viewing is counted as a WatchMyMedia playback.
func TestImportPreservesExistingClientStateAndIndependentHistory(t *testing.T) {
	f, server := fixtureServer(t)
	admin := newBrowser(t, server.URL, "Fixture Admin")
	viewer := newBrowser(t, server.URL, "Fixture Viewer")
	base := fmt.Sprintf("/api/integrations/plex/connections/%d", f.connection.ID)
	changes, unsubscribe := f.bus.Stream(func(e events.Event) bool {
		return e.Type == "watch.changed" && e.For(f.viewer, false, false)
	})
	defer unsubscribe()
	p := admin.preview(base)
	var run plexsync.PlexRun
	admin.call(http.MethodPost, base+"/runs", plexsync.PlexRunStart{PreviewID: p.ID}, &run, http.StatusAccepted)
	admin.call(http.MethodPost, base+"/runs", plexsync.PlexRunStart{PreviewID: p.ID}, nil, http.StatusConflict)
	run = admin.waitRun(base, run)
	if run.Counts.Marked != 4 || run.Counts.LocalKept != 1 || run.Counts.HistoryImported != 2 || run.History != "complete" {
		t.Fatalf("independent import counts = %+v", run)
	}
	for _, check := range []struct {
		browser browser
		movie   int64
		watched bool
	}{
		{admin, f.movies[0], true}, {admin, f.movies[1], false}, {admin, f.movies[2], false},
		{viewer, f.movies[0], false}, {viewer, f.movies[1], true},
	} {
		var detail api.MovieDetail
		check.browser.call(http.MethodGet, fmt.Sprintf("/api/movies/%d", check.movie), nil, &detail, http.StatusOK)
		watched := detail.Watch != nil && detail.Watch.Completed
		if watched != check.watched {
			t.Fatalf("movie %d watched = %v; want %v", check.movie, watched, check.watched)
		}
	}
	// Play next follows on from the episode finished last (PLAN-SHOW-PAGE.md,
	// decision 10): the viewer finished E02 only, so E03, not the skipped E01.
	for _, check := range []struct {
		browser browser
		next    int64
	}{
		{admin, 2}, {viewer, 3},
	} {
		var show api.ShowDetail
		check.browser.call(http.MethodGet, fmt.Sprintf("/api/shows/%d", f.show), nil, &show, http.StatusOK)
		if show.NextEpisode == nil || show.NextEpisode.EpisodeNumber != check.next {
			t.Fatalf("Play next = %+v; want episode %d", show.NextEpisode, check.next)
		}
		var season api.SeasonDetail
		check.browser.call(http.MethodGet, fmt.Sprintf("/api/shows/%d/seasons/1", f.show), nil, &season, http.StatusOK)
		watched := 0
		for _, episode := range season.Episodes {
			if episode.Watch != nil && episode.Watch.Completed {
				watched++
			}
		}
		if watched != 1 {
			t.Fatalf("season watched episodes = %d; want 1", watched)
		}
	}
	var adminHistory, viewerHistory plexsync.PlexHistoryList
	admin.call(http.MethodGet, "/api/plex-history", nil, &adminHistory, http.StatusOK)
	viewer.call(http.MethodGet, "/api/plex-history", nil, &viewerHistory, http.StatusOK)
	if len(adminHistory.Items) != 1 || adminHistory.Items[0].Title != "Harbor Lights" || len(viewerHistory.Items) != 1 || viewerHistory.Items[0].Title != "Copper Sky" {
		t.Fatalf("history crossed users: admin=%+v viewer=%+v", adminHistory, viewerHistory)
	}
	viewer.call(http.MethodGet, fmt.Sprintf("/api/plex-history?userId=%d", f.admin), nil, nil, http.StatusForbidden)
	var operational activity.Page
	admin.call(http.MethodGet, "/api/history", nil, &operational, http.StatusOK)
	var stats activity.Cards
	admin.call(http.MethodGet, "/api/stats/cards", nil, &stats, http.StatusOK)
	if operational.Total != 0 || operational.PlayedMs != 0 || stats.Plays != 0 || stats.PlayedMs != 0 {
		t.Fatalf("Plex events polluted playback history/stats: %+v %+v", operational, stats)
	}
	for range 2 {
		select {
		case event := <-changes:
			if event.To == nil || event.To.UserID != f.viewer || !event.To.Private {
				t.Fatalf("watch event has wrong audience: %+v", event)
			}
		case <-t.Context().Done():
			t.Fatal("missing private watch change")
		}
	}
	select {
	case unexpected := <-changes:
		t.Fatalf("another person's watch event reached viewer: %+v", unexpected)
	default:
	}

	// A local decision before preview is omitted; a change after preview
	// is rechecked at undo. Both survive, and the preview is single-use.
	viewer.call(http.MethodPost, fmt.Sprintf("/api/watch/movie/%d/watched", f.movies[1]), nil, nil, http.StatusNoContent)
	var undo plexsync.PlexUndoPreview
	undoBase := fmt.Sprintf("%s/runs/%d", base, run.ID)
	admin.call(http.MethodPost, undoBase+"/undo-previews", nil, &undo, http.StatusOK)
	if undo.WillUndo != 3 || undo.LocalKept != 1 {
		t.Fatalf("guarded undo preview = %+v", undo)
	}
	admin.call(http.MethodDelete, fmt.Sprintf("/api/watch/movie/%d/watched", f.movies[0]), nil, nil, http.StatusNoContent)
	var undone plexsync.PlexUndoResult
	admin.call(http.MethodPost, undoBase+"/undo", map[string]string{"previewId": undo.ID}, &undone, http.StatusOK)
	if undone.Undone != 2 || undone.LocalKept != 2 {
		t.Fatalf("guarded undo = %+v", undone)
	}
	admin.call(http.MethodPost, undoBase+"/undo", map[string]string{"previewId": undo.ID}, nil, http.StatusConflict)
	var retained api.MovieDetail
	viewer.call(http.MethodGet, fmt.Sprintf("/api/movies/%d", f.movies[1]), nil, &retained, http.StatusOK)
	if retained.Watch == nil || !retained.Watch.Completed {
		t.Fatal("undo removed a local watched mark")
	}
	second := admin.importPreview(base, admin.preview(base))
	if second.Counts.Marked != 0 || second.Counts.HistoryImported != 0 {
		t.Fatalf("reimport reintroduced undone marks or duplicated history: %+v", second)
	}
	viewer.call(http.MethodGet, "/api/plex-history", nil, &viewerHistory, http.StatusOK)
	if len(viewerHistory.Items) != 1 {
		t.Fatalf("history changed after watch-mark undo/reimport: %+v", viewerHistory)
	}
	viewer.call(http.MethodDelete, fmt.Sprintf("/api/plex-history/%d", viewerHistory.Items[0].ID), nil, nil, http.StatusNoContent)
	viewer.call(http.MethodGet, "/api/plex-history", nil, &viewerHistory, http.StatusOK)
	if len(viewerHistory.Items) != 0 {
		t.Fatal("history deletion did not apply to the requesting person")
	}
}

func TestRunControlsRequireAnAdminAndPreviewSession(t *testing.T) {
	f, server := fixtureServer(t)
	admin := newBrowser(t, server.URL, "Fixture Admin")
	otherSession := newBrowser(t, server.URL, "Fixture Admin")
	viewer := newBrowser(t, server.URL, "Fixture Viewer")
	anonymous := newBrowser(t, server.URL, "")
	base := fmt.Sprintf("/api/integrations/plex/connections/%d", f.connection.ID)
	for _, route := range []struct {
		method string
		path   string
		body   any
	}{
		{http.MethodPost, "/runs", plexsync.PlexRunStart{PreviewID: "fictional-preview"}},
		{http.MethodGet, "/runs", nil}, {http.MethodGet, "/runs/1", nil},
		{http.MethodPost, "/runs/1/cancel", nil}, {http.MethodPost, "/runs/1/retry", nil},
		{http.MethodPut, "/sync", plexsync.PlexSyncUpdate{Generation: f.connection.Generation}},
		{http.MethodGet, "/exclusions", nil},
		{http.MethodPut, "/exclusions", plexsync.PlexExclusions{Generation: f.connection.Generation, Items: []plexsync.PlexExclusion{}}},
		{http.MethodPost, "/runs/1/undo-previews", nil}, {http.MethodPost, "/runs/1/undo", map[string]string{"previewId": "fictional-preview"}},
		{http.MethodPost, "/rebaseline", map[string]int64{"generation": f.connection.Generation}},
	} {
		viewer.call(route.method, base+route.path, route.body, nil, http.StatusForbidden)
		anonymous.call(route.method, base+route.path, route.body, nil, http.StatusUnauthorized)
	}
	p := admin.preview(base)
	otherSession.call(http.MethodPost, base+"/runs", plexsync.PlexRunStart{PreviewID: p.ID}, nil, http.StatusConflict)
	run := admin.importPreview(base, p)
	var undo plexsync.PlexUndoPreview
	undoBase := fmt.Sprintf("%s/runs/%d", base, run.ID)
	admin.call(http.MethodPost, undoBase+"/undo-previews", nil, &undo, http.StatusOK)
	otherSession.call(http.MethodPost, undoBase+"/undo", map[string]string{"previewId": undo.ID}, nil, http.StatusConflict)
}
