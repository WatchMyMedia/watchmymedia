package main

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/go-chi/chi/v5"
	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/probe"
	"watchmymedia/server/internal/settings"
	"watchmymedia/server/internal/streamtest"
)

func TestStreamingControlsRequireThisFixtureKey(t *testing.T) {
	t.Parallel()
	conn, _ := dbtest.Open(t)
	manager := playback.NewManager("unused", t.TempDir(), playback.Capabilities{}, time.Minute, 2, slog.New(slog.DiscardHandler))
	t.Cleanup(manager.StopAll)
	tracker := activity.New(conn, events.NewBus(), nil, activity.Options{})
	fixture := &streamingFixture{manifest: streamingManifest{Fixture: streamingMarker, ControlKey: "owned-test-key"}, shaper: streamtest.NewShaper()}
	router := chi.NewRouter()
	fixture.register(router, manager, hdhrtest.New(nil), tracker, nil, t.TempDir())
	fixture.registerLiveOutputs(router, nil, nil)
	for _, tc := range []struct {
		key, body string
		status    int
	}{
		{"", `{"aggregate":{"bps":1000}}`, 403},
		{"another-fixture", `{"aggregate":{"bps":1000}}`, 403},
		{"owned-test-key", `{"aggregate":{"bps":-1}}`, 400},
		{"owned-test-key", `{"extra":true}`, 400},
		{"owned-test-key", `{} {}`, 400},
		{"owned-test-key", strings.Repeat(" ", 9000) + `{}`, 400},
		{"owned-test-key", `{"aggregate":{"bps":1000},"viewers":{"viewer-1":{"bps":500}}}`, 200},
	} {
		r := httptest.NewRequest(http.MethodPost, "/_fixture/streaming/profile", strings.NewReader(tc.body))
		r.Header.Set("X-WMM-Fixture-Key", tc.key)
		w := httptest.NewRecorder()
		router.ServeHTTP(w, r)
		if w.Code != tc.status {
			t.Fatalf("profile -> %d, want %d", w.Code, tc.status)
		}
	}
	for _, path := range []string{"/_fixture/streaming/ladder", "/_fixture/streaming/ladder/stop", "/_fixture/streaming/live-output"} {
		r := httptest.NewRequest(http.MethodPost, path, strings.NewReader(`{"viewer":"Fictional Viewer 1"}`))
		w := httptest.NewRecorder()
		router.ServeHTTP(w, r)
		if w.Code != http.StatusForbidden {
			t.Fatalf("unkeyed ladder control %s -> %d", path, w.Code)
		}
	}
	r := httptest.NewRequest(http.MethodGet, "/_fixture/streaming/metrics", nil)
	w := httptest.NewRecorder()
	router.ServeHTTP(w, r)
	if w.Code != 403 {
		t.Fatal("unkeyed resource diagnostics exposed")
	}
	r.Header.Set("X-WMM-Fixture-Key", "owned-test-key")
	w = httptest.NewRecorder()
	router.ServeHTTP(w, r)
	var metrics struct {
		PlaybackLimit int                     `json:"playbackLimit"`
		Traffic       streamtest.Snapshot     `json:"traffic"`
		Renditions    playback.RenditionStats `json:"renditions"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &metrics); err != nil || w.Code != 200 || metrics.PlaybackLimit != 2 ||
		metrics.Traffic.Profile.Aggregate.Bps != 1000 || metrics.Renditions.MaxBytes <= 0 {
		t.Fatalf("metrics not wired: %d %v", w.Code, err)
	}
}

func TestStreamingMediaIsGeneratedProbedAndMatched(t *testing.T) {
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not installed")
	}
	ffprobe, err := exec.LookPath("ffprobe")
	if err != nil {
		t.Skip("ffprobe not installed")
	}
	_, q := dbtest.Open(t)
	accounts := auth.New(q, time.Hour, false)
	fixture, err := seedStreaming(t.Context(), q, settings.New(q), accounts, &probe.Prober{Path: ffprobe}, ffmpeg, t.TempDir(), streamingTitles{seconds: 10})
	if err != nil {
		t.Fatal(err)
	}
	if len(fixture.manifest.Files) != 3 || len(fixture.manifest.Accounts) != 17 || len(fixture.manifest.ControlKey) != 48 || fixture.manifest.ServerID == "" {
		t.Fatal("incomplete streaming manifest")
	}
	ids := map[int64]bool{}
	for _, f := range fixture.manifest.Files {
		if ids[f.ID] || f.DurationMs < 9000 || f.Bitrate < 5_000_000 || f.Video != "h264" {
			t.Fatalf("invalid fixture media: %+v", f)
		}
		ids[f.ID] = true
		row, err := q.GetMediaFile(t.Context(), f.ID)
		if err != nil || row.MovieID == nil || row.MatchStatus != "matched" || row.DurationMs == nil {
			t.Fatalf("unmatched fixture file: %v", err)
		}
		if f.Kind == "eac3" && f.Audio != "eac3" {
			t.Fatal("missing real E-AC-3 source")
		}
	}
}

func TestStreamingFixturePrivateLadderClaimsAndStops(t *testing.T) {
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not installed")
	}
	ffprobe, err := exec.LookPath("ffprobe")
	if err != nil {
		t.Skip("ffprobe not installed")
	}
	conn, q := dbtest.Open(t)
	accounts := auth.New(q, time.Hour, false)
	fixture, err := seedStreaming(t.Context(), q, settings.New(q), accounts, &probe.Prober{Path: ffprobe}, ffmpeg, t.TempDir(), streamingTitles{seconds: 10})
	if err != nil {
		t.Fatal(err)
	}
	manager := playback.NewManager(ffmpeg, t.TempDir(), playback.Capabilities{Encoder: "libx264", Encoders: []string{"libx264"}}, time.Minute, 2, slog.New(slog.DiscardHandler))
	t.Cleanup(manager.StopAll)
	tracker := activity.New(conn, events.NewBus(), nil, activity.Options{})
	router := chi.NewRouter()
	fixture.register(router, manager, hdhrtest.New(nil), tracker, q, t.TempDir())
	post := func(path, body string) (int, []byte) {
		t.Helper()
		r := httptest.NewRequest(http.MethodPost, path, strings.NewReader(body))
		r.Header.Set("X-WMM-Fixture-Key", fixture.manifest.ControlKey)
		w := httptest.NewRecorder()
		router.ServeHTTP(w, r)
		return w.Code, w.Body.Bytes()
	}
	code, body := post("/_fixture/streaming/ladder", `{"viewer":"Stream Viewer 1"}`)
	var start struct {
		PlayID string `json:"playId"`
		URL    string `json:"url"`
	}
	if err := json.Unmarshal(body, &start); err != nil || code != http.StatusOK ||
		!strings.HasPrefix(start.PlayID, "fixture-ladder-") || !strings.HasSuffix(start.URL, "/master.m3u8") || manager.Claimed() != 2 {
		t.Fatalf("fixture ladder start -> %d %s, claims %d: %v", code, body, manager.Claimed(), err)
	}
	code, body = post("/_fixture/streaming/ladder/stop", `{"playId":"`+start.PlayID+`","viewer":"Stream Viewer 1"}`)
	if code != http.StatusOK || manager.Claimed() != 0 {
		t.Fatalf("fixture ladder stop -> %d %s, claims %d", code, body, manager.Claimed())
	}
}

func TestStreamingDiskCountsOnlyOwnedFilesAndToleratesMissingRoots(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "segment.m4s"), []byte("synthetic"), 0o600); err != nil {
		t.Fatal(err)
	}
	bytes, files, err := streamingDisk(dir)
	if err != nil || bytes != 9 || files != 1 {
		t.Fatalf("scan %d %d %v", bytes, files, err)
	}
	bytes, files, err = streamingDisk(filepath.Join(dir, "not-created"))
	if err != nil || bytes != 0 || files != 0 {
		t.Fatalf("missing root %d %d %v", bytes, files, err)
	}
}
