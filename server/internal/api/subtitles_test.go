package api

import (
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/opensubtitles"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/settings"
	"watchmymedia/server/internal/subtitles"
)

// fakeOpenSubtitles answers search and download like the real API.
func fakeOpenSubtitles(t *testing.T, downloads *atomic.Int32) *httptest.Server {
	t.Helper()
	var srv *httptest.Server
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/subtitles", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"data":[
			{"attributes":{"language":"en","download_count":10,"release":"Clip.2020.1080p","moviehash_match":true,"files":[{"file_id":77}]}},
			{"attributes":{"language":"en","download_count":99,"release":"Clip.2020.720p","hearing_impaired":true,"files":[{"file_id":78}]}}
		]}`))
	})
	mux.HandleFunc("POST /api/v1/download", func(w http.ResponseWriter, r *http.Request) {
		downloads.Add(1)
		_, _ = fmt.Fprintf(w, `{"link":"%s/f.srt","remaining":4,"reset_time_utc":"2026-10-31T00:00:00.000Z"}`, srv.URL)
	})
	mux.HandleFunc("GET /f.srt", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("1\n00:00:01,000 --> 00:00:03,000\nFrom online\n"))
	})
	srv = httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

func TestOnlineSubtitles(t *testing.T) {
	t.Parallel()
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not installed")
	}
	var downloads atomic.Int32
	api := fakeOpenSubtitles(t, &downloads)
	srv := newTestServerWith(t, func(d *Deps) {
		d.Subtitles = playback.NewSubtitles(ffmpeg, filepath.Join(d.DataDir, "subtitles"))
		client := opensubtitles.New("key", "WatchMyMedia vtest", api.URL+"/api/v1")
		d.OnlineSubtitles = subtitles.New(d.Queries, d.Settings, client, filepath.Join(d.DataDir, "subtitles-downloaded"), slog.New(slog.DiscardHandler))
	})
	ctx := t.Context()
	path := filepath.Join(t.TempDir(), "Clip (2020).mp4")
	if err := os.WriteFile(path, make([]byte, 300_000), 0o600); err != nil {
		t.Fatal(err)
	}
	probeJSON := `{"container":"mov","durationMs":5000,"video":[{"index":0,"codec":"h264","width":1280,"height":720}],"audio":[{"index":1,"codec":"aac","channels":2,"default":true}],"subtitles":[]}`
	f, err := srv.q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: srv.fileLibrary(t, path), Path: path, Size: 300_000, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()), Parsed: "{}",
		Probe: &probeJSON, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	base := fmt.Sprintf("%s/api/files/%d/subtitles", srv.URL, f.ID)

	var found subtitles.SubtitleSearch
	if code := doJSON(t, srv.client, http.MethodGet, base+"/search", "", &found).StatusCode; code != 200 {
		t.Fatalf("search -> %d", code)
	}
	if !found.Available || !found.CanDownload || len(found.Results) != 2 || found.Results[0].ID != "77" || !found.Results[0].HashMatch ||
		found.Attribution != opensubtitles.Attribution || strings.Join(found.Languages, ",") != "en" {
		t.Fatalf("search = %+v", found)
	}

	download := func(id string) (int, playback.SubtitleTrack) {
		res, err := srv.client.Post(base+"/download", "application/json", strings.NewReader(`{"resultId":"`+id+`"}`))
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		var tr playback.SubtitleTrack
		_ = json.NewDecoder(res.Body).Decode(&tr)
		return res.StatusCode, tr
	}
	code, tr := download("77")
	if code != 200 || tr.ID != "d1" || tr.Source != "opensubtitles" || tr.Release != "Clip.2020.1080p" || tr.URL != fmt.Sprintf("/api/files/%d/subtitles/d1.vtt", f.ID) {
		t.Fatalf("download -> %d %+v", code, tr)
	}
	// Picking it again costs nothing.
	if code, again := download("77"); code != 200 || again.ID != "d1" || downloads.Load() != 1 {
		t.Errorf("second download -> %d %+v, %d provider downloads", code, again, downloads.Load())
	}
	// Results nobody searched for are refused.
	if code, _ := download("12345"); code != http.StatusNotFound {
		t.Errorf("unknown result -> %d", code)
	}
	// Search marks what is downloaded.
	doJSON(t, srv.client, http.MethodGet, base+"/search", "", &found)
	if found.Results[0].TrackID != "d1" || found.Results[1].TrackID != "" {
		t.Errorf("trackIds = %q %q", found.Results[0].TrackID, found.Results[1].TrackID)
	}

	// The decision lists it with the file's own tracks, and it plays.
	body := `{"profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"],"subtitleFormats":["webvtt"]}}`
	res, err := srv.client.Post(fmt.Sprintf("%s/api/files/%d/playback", srv.URL, f.ID), "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	var d playback.Decision
	_ = json.NewDecoder(res.Body).Decode(&d)
	_ = res.Body.Close()
	if len(d.Subtitles) != 1 || d.Subtitles[0].ID != "d1" || d.Subtitles[0].URL != tr.URL || d.Subtitles[0].Source != "opensubtitles" {
		t.Fatalf("decision subtitles = %+v", d.Subtitles)
	}
	vtt := getBody(t, srv.client, srv.URL+tr.URL)
	if !strings.HasPrefix(vtt, "WEBVTT") || !strings.Contains(vtt, "From online") {
		t.Errorf("vtt = %q", vtt)
	}
	// An event playlist's shift moves the cues.
	if shifted := getBody(t, srv.client, srv.URL+tr.URL+"?shift=500"); !strings.Contains(shifted, "00:00:00.500 --> 00:00:02.500") {
		t.Errorf("shifted = %q", shifted)
	}

	// A media token reaches the track but not search or download.
	var mt MediaToken
	res, _ = srv.client.Post(srv.URL+"/api/auth/media-token", "application/json", nil)
	_ = json.NewDecoder(res.Body).Decode(&mt)
	_ = res.Body.Close()
	for path, want := range map[string]int{tr.URL: 200, fmt.Sprintf("/api/files/%d/subtitles/search", f.ID): 401} {
		res, err := http.Get(srv.URL + path + "?access_token=" + mt.Token)
		if err != nil {
			t.Fatal(err)
		}
		_ = res.Body.Close()
		if res.StatusCode != want {
			t.Errorf("%s with media token -> %d, want %d", path, res.StatusCode, want)
		}
	}

	// Settings show the provider and keep downloads to admins on request.
	var s Settings
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/settings", "", &s)
	if !s.Subtitles.Available || s.Subtitles.Downloads != "everyone" {
		t.Errorf("settings subtitles = %+v", s.Subtitles)
	}
	req, _ := http.NewRequestWithContext(t.Context(), http.MethodPut, srv.URL+"/api/settings", strings.NewReader(`{"subtitles":{"languages":["ES","pt-BR","nonsense!"],"downloads":"admins"}}`))
	req.Header.Set("Content-Type", "application/json")
	res, err = srv.client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	_ = json.NewDecoder(res.Body).Decode(&s)
	_ = res.Body.Close()
	if res.StatusCode != 200 || strings.Join(s.Subtitles.Languages, ",") != "es,pt-br" || s.Subtitles.Downloads != "admins" {
		t.Errorf("updated settings -> %d %+v", res.StatusCode, s.Subtitles)
	}
	if v, _ := settings.New(srv.q).Get(ctx, settings.KeySubtitleLanguages, ""); v != "es,pt-br" {
		t.Errorf("stored languages = %q", v)
	}

	// Removing the download drops it from the next decision.
	req, _ = http.NewRequestWithContext(t.Context(), http.MethodDelete, base+"/d1", nil)
	res, err = srv.client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	_ = res.Body.Close()
	if res.StatusCode != http.StatusNoContent {
		t.Errorf("delete -> %d", res.StatusCode)
	}
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+tr.URL, "", nil).StatusCode; code != http.StatusNotFound {
		t.Errorf("deleted track -> %d", code)
	}
}

func TestSearchWithoutProvider(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	var found subtitles.SubtitleSearch
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/files/1/subtitles/search", "", &found).StatusCode; code != 200 || found.Available || found.Results == nil {
		t.Errorf("search -> %d %+v", code, found)
	}
	res, err := srv.client.Post(srv.URL+"/api/files/1/subtitles/download", "application/json", strings.NewReader(`{"resultId":"1"}`))
	if err != nil {
		t.Fatal(err)
	}
	_ = res.Body.Close()
	if res.StatusCode != http.StatusServiceUnavailable {
		t.Errorf("download -> %d", res.StatusCode)
	}
}

func getBody(t *testing.T, c *http.Client, url string) string {
	t.Helper()
	res, err := c.Get(url)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	if res.StatusCode != 200 {
		t.Errorf("GET %s -> %d %s", url, res.StatusCode, b)
	}
	return string(b)
}

// Invalid streams must never cause an ffmpeg process, even when requested directly.
func TestSubtitleRouteRejectsUnprobedStreams(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, func(d *Deps) {
		d.Subtitles = playback.NewSubtitles("missing-ffmpeg-must-not-run", t.TempDir())
	})
	path := filepath.Join(t.TempDir(), "Clip (2020).mp4")
	if err := os.WriteFile(path, []byte("synthetic"), 0600); err != nil {
		t.Fatal(err)
	}
	p := `{"container":"mp4","subtitles":[{"index":3,"codec":"hdmv_pgs_subtitle"}]}`
	f, err := srv.q.UpsertMediaFile(t.Context(), db.UpsertMediaFileParams{
		LibraryID: srv.fileLibrary(t, path), Path: path, Size: 9, Mtime: db.FormatTime(time.Now()),
		AddedAt: db.FormatTime(time.Now()), Parsed: "{}", Probe: &p, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1,
	})
	if err != nil {
		t.Fatal(err)
	}
	for _, track := range []string{"s0", "s3", "s9999", "s999999999999999999999999999999999999"} {
		res, err := srv.client.Get(fmt.Sprintf("%s/api/files/%d/subtitles/%s.vtt", srv.URL, f.ID, track))
		if err != nil {
			t.Fatal(err)
		}
		_ = res.Body.Close()
		if res.StatusCode != http.StatusNotFound {
			t.Errorf("%s -> %d", track, res.StatusCode)
		}
	}
}
