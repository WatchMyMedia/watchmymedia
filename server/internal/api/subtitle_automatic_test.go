package api

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/opensubtitles"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/probe"
	"watchmymedia/server/internal/settings"
	"watchmymedia/server/internal/subtitles"
)

type automaticProvider struct {
	searches        atomic.Int32
	downloads       atomic.Int32
	languages       chan string
	searchStarted   chan struct{}
	searchRelease   chan struct{}
	downloadStarted chan struct{}
	downloadRelease chan struct{}
	quota           bool
	noHash          bool
	once            sync.Once
}

func automaticSubtitleEnv(t *testing.T, p *automaticProvider) (*testEnv, *activity.Tracker) {
	t.Helper()
	var provider *httptest.Server
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/subtitles", func(w http.ResponseWriter, r *http.Request) {
		p.searches.Add(1)
		if p.languages != nil {
			p.languages <- r.URL.Query().Get("languages")
		}
		if p.searchStarted != nil {
			p.once.Do(func() { close(p.searchStarted) })
		}
		if p.searchRelease != nil {
			<-p.searchRelease
		}
		_, _ = fmt.Fprintf(w, `{"data":[{"attributes":{"language":"en","download_count":99,"release":"Wrong.Release","moviehash_match":false,"files":[{"file_id":78}]}},{"attributes":{"language":"en","download_count":10,"release":"Exact.Release","moviehash_match":%t,"files":[{"file_id":77}]}}]}`, !p.noHash)
	})
	mux.HandleFunc("POST /api/v1/download", func(w http.ResponseWriter, _ *http.Request) {
		p.downloads.Add(1)
		if p.downloadStarted != nil {
			p.once.Do(func() { close(p.downloadStarted) })
		}
		if p.downloadRelease != nil {
			<-p.downloadRelease
		}
		if p.quota {
			w.WriteHeader(http.StatusNotAcceptable)
			_, _ = w.Write([]byte(`{"message":"quota exceeded","remaining":0}`))
			return
		}
		_, _ = fmt.Fprintf(w, `{"link":"%s/f.srt","remaining":4}`, provider.URL)
	})
	mux.HandleFunc("GET /f.srt", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("1\n00:00:01,000 --> 00:00:03,000\nAn exact subtitle\n"))
	})
	provider = httptest.NewServer(mux)
	t.Cleanup(provider.Close)
	var tracker *activity.Tracker
	srv := newTestServerWith(t, func(d *Deps) {
		tracker = activity.New(d.Conn, d.Bus, slog.New(slog.DiscardHandler), activity.Options{})
		d.Activity = tracker
		client := opensubtitles.New("test-key", "WatchMyMedia vtest", provider.URL+"/api/v1")
		d.OnlineSubtitles = subtitles.New(d.Queries, d.Settings, client, filepath.Join(d.DataDir, "subtitles-downloaded"), slog.New(slog.DiscardHandler))
	})
	return srv, tracker
}

func subtitlePreferenceFile(t *testing.T, srv *testEnv, subs []probe.SubtitleStream) db.MediaFile {
	t.Helper()
	path := filepath.Join(t.TempDir(), "Fictional Clip.mp4")
	if err := os.WriteFile(path, make([]byte, 300_000), 0o600); err != nil {
		t.Fatal(err)
	}
	info := probe.MediaInfo{Container: "mov", DurationMs: 60_000,
		Video:     []probe.VideoStream{{Index: 0, Codec: "h264", Width: 1280, Height: 720}},
		Audio:     []probe.AudioStream{{Index: 1, Codec: "aac", Channels: 2, Language: "eng", Default: true}},
		Subtitles: subs,
	}
	encoded, err := json.Marshal(info)
	if err != nil {
		t.Fatal(err)
	}
	probeJSON := string(encoded)
	now := db.FormatTime(time.Now())
	f, err := srv.q.UpsertMediaFile(t.Context(), db.UpsertMediaFileParams{
		LibraryID: srv.fileLibrary(t, path), Path: path, Size: 300_000, Mtime: now, AddedAt: now,
		Parsed: "{}", Probe: &probeJSON, LastSeenAt: now, SeenInScan: 1,
	})
	if err != nil {
		t.Fatal(err)
	}
	return f
}

func subtitleProfileBody(extra string) string {
	return `{"profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"],"subtitleFormats":["webvtt"]}` + extra + `}`
}

func startAutomaticSubtitlePlay(t *testing.T, srv *testEnv, file db.MediaFile, extra string) playback.Decision {
	t.Helper()
	var d playback.Decision
	url := fmt.Sprintf("%s/api/files/%d/playback", srv.URL, file.ID)
	if code := doJSON(t, srv.client, http.MethodPost, url, subtitleProfileBody(extra), &d).StatusCode; code != 200 {
		t.Fatalf("playback -> %d", code)
	}
	return d
}

func enableAutomaticSubtitles(t *testing.T, srv *testEnv) {
	t.Helper()
	if code := doJSON(t, srv.client, http.MethodPut, srv.URL+"/api/me/preferences", `{"subtitles":{"mode":"on","languages":["en","es"],"automaticDownload":true}}`, nil).StatusCode; code != 200 {
		t.Fatalf("enable preferences -> %d", code)
	}
}

func TestAutomaticSubtitleRunsAfterPlaybackAndSpendsQuotaOnce(t *testing.T) {
	t.Parallel()
	p := &automaticProvider{languages: make(chan string, 1)}
	srv, _ := automaticSubtitleEnv(t, p)
	file := subtitlePreferenceFile(t, srv, nil)
	enableAutomaticSubtitles(t, srv)
	d := startAutomaticSubtitlePlay(t, srv, file, `,"subtitleMode":"auto"`)
	if p.searches.Load() != 0 || p.downloads.Load() != 0 {
		t.Fatal("admitting playback contacted the provider")
	}
	url := fmt.Sprintf("%s/api/files/%d/subtitles/automatic", srv.URL, file.ID)
	body := fmt.Sprintf(`{"playId":%q}`, d.PlayID)
	var wg sync.WaitGroup
	for range 3 {
		wg.Go(func() {
			var got AutomaticSubtitleResult
			if code := doJSON(t, srv.client, http.MethodPost, url, body, &got).StatusCode; code != 200 || got.Track == nil || got.Track.ID != "d1" || got.Track.Release != "Exact.Release" || got.Error != "" {
				t.Errorf("automatic -> %d %+v", code, got)
			}
		})
	}
	wg.Wait()
	var got AutomaticSubtitleResult
	doJSON(t, srv.client, http.MethodPost, url, body, &got)
	if p.searches.Load() != 1 || p.downloads.Load() != 1 || <-p.languages != "en" {
		t.Fatal("automatic work repeated or searched beyond the first preferred language")
	}
	if got.Track == nil || got.Track.URL != fmt.Sprintf("/api/files/%d/subtitles/d1.vtt", file.ID) {
		t.Fatalf("automatic track = %+v", got)
	}
	// A second play reuses the local copy at admission, with no provider call.
	second := startAutomaticSubtitlePlay(t, srv, file, `,"subtitleMode":"auto"`)
	if second.SubtitleID != "d1" || p.downloads.Load() != 1 {
		t.Fatalf("second play = %+v", second)
	}
}

func TestAutomaticSubtitleRefusesForeignInactiveAndManualPlays(t *testing.T) {
	t.Parallel()
	p := &automaticProvider{}
	srv, tracker := automaticSubtitleEnv(t, p)
	file := subtitlePreferenceFile(t, srv, nil)
	enableAutomaticSubtitles(t, srv)
	d := startAutomaticSubtitlePlay(t, srv, file, `,"subtitleMode":"auto"`)
	url := fmt.Sprintf("%s/api/files/%d/subtitles/automatic", srv.URL, file.ID)
	body := fmt.Sprintf(`{"playId":%q}`, d.PlayID)
	other, _ := securityTestUser(t, srv, "alex")
	if code := doJSON(t, other, http.MethodPost, url, body, nil).StatusCode; code != http.StatusConflict {
		t.Errorf("foreign play -> %d", code)
	}
	if code := doJSON(t, other, http.MethodPost, url, fmt.Sprintf(`{"playId":%q,"cancel":true}`, d.PlayID), nil).StatusCode; code != http.StatusConflict {
		t.Errorf("foreign cancellation -> %d", code)
	}
	// Even a second sign-in to the same account cannot spend the first one's quota.
	second := srv.Client()
	second.Jar, _ = cookiejar.New(nil)
	if code := doJSON(t, second, http.MethodPost, srv.URL+"/api/auth/login", `{"name":"admin","password":"password1"}`, nil).StatusCode; code != 200 {
		t.Fatalf("second sign-in -> %d", code)
	}
	if code := doJSON(t, second, http.MethodPost, url, body, nil).StatusCode; code != http.StatusConflict {
		t.Errorf("other sign-in -> %d", code)
	}
	if code := doJSON(t, second, http.MethodPost, url, fmt.Sprintf(`{"playId":%q,"cancel":true}`, d.PlayID), nil).StatusCode; code != http.StatusConflict {
		t.Errorf("other sign-in cancellation -> %d", code)
	}
	// An explicit Off continuation invalidates the original automatic request.
	startAutomaticSubtitlePlay(t, srv, file, fmt.Sprintf(`,"playId":%q,"subtitleMode":"off"`, d.PlayID))
	if code := doJSON(t, srv.client, http.MethodPost, url, body, nil).StatusCode; code != http.StatusConflict {
		t.Errorf("manual Off -> %d", code)
	}
	manual := startAutomaticSubtitlePlay(t, srv, file, `,"subtitleMode":"off"`)
	if code := doJSON(t, srv.client, http.MethodPost, url, fmt.Sprintf(`{"playId":%q}`, manual.PlayID), nil).StatusCode; code != http.StatusConflict {
		t.Errorf("manual play -> %d", code)
	}
	active := startAutomaticSubtitlePlay(t, srv, file, `,"subtitleMode":"auto"`)
	_, err := tracker.Heartbeat(t.Context(), active.PlayID, 1, file.ID, activity.Report{State: activity.StateStopped})
	if err != nil {
		t.Fatal(err)
	}
	if code := doJSON(t, srv.client, http.MethodPost, url, fmt.Sprintf(`{"playId":%q}`, active.PlayID), nil).StatusCode; code != http.StatusConflict {
		t.Errorf("stopped play -> %d", code)
	}
	if p.searches.Load() != 0 || p.downloads.Load() != 0 {
		t.Fatal("refused requests reached provider")
	}
}

func TestAutomaticSubtitleWithoutHashMatchOrQuotaDoesNotBreakPlayback(t *testing.T) {
	t.Parallel()
	for _, tc := range []struct {
		name          string
		quota, noHash bool
		wantDownloads int32
	}{
		{"no hash match", false, true, 0}, {"quota rejected", true, false, 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			p := &automaticProvider{quota: tc.quota, noHash: tc.noHash}
			srv, _ := automaticSubtitleEnv(t, p)
			file := subtitlePreferenceFile(t, srv, nil)
			enableAutomaticSubtitles(t, srv)
			d := startAutomaticSubtitlePlay(t, srv, file, `,"subtitleMode":"auto"`)
			url := fmt.Sprintf("%s/api/files/%d/subtitles/automatic", srv.URL, file.ID)
			body := fmt.Sprintf(`{"playId":%q}`, d.PlayID)
			for range 2 {
				var got AutomaticSubtitleResult
				if code := doJSON(t, srv.client, http.MethodPost, url, body, &got).StatusCode; code != 200 || got.Track != nil {
					t.Fatalf("automatic -> %d %+v", code, got)
				}
				if tc.quota && got.Error != "No subtitle downloads left today." {
					t.Fatalf("quota result = %+v", got)
				}
			}
			if p.downloads.Load() != tc.wantDownloads {
				t.Fatalf("provider downloads = %d", p.downloads.Load())
			}
		})
	}
}

func TestAutomaticSubtitleCancelDuringSearchDoesNotSpendQuota(t *testing.T) {
	t.Parallel()
	p := &automaticProvider{searchStarted: make(chan struct{}), searchRelease: make(chan struct{})}
	srv, _ := automaticSubtitleEnv(t, p)
	file := subtitlePreferenceFile(t, srv, nil)
	enableAutomaticSubtitles(t, srv)
	d := startAutomaticSubtitlePlay(t, srv, file, `,"subtitleMode":"auto"`)
	url := fmt.Sprintf("%s/api/files/%d/subtitles/automatic", srv.URL, file.ID)
	done := make(chan AutomaticSubtitleResult, 1)
	go func() {
		var got AutomaticSubtitleResult
		doJSON(t, srv.client, http.MethodPost, url, fmt.Sprintf(`{"playId":%q}`, d.PlayID), &got)
		done <- got
	}()
	<-p.searchStarted
	if code := doJSON(t, srv.client, http.MethodPost, url, fmt.Sprintf(`{"playId":%q,"cancel":true}`, d.PlayID), nil).StatusCode; code != 200 {
		t.Fatalf("cancel automatic search -> %d", code)
	}
	close(p.searchRelease)
	if got := <-done; got.Track != nil || got.Error != "" || p.downloads.Load() != 0 {
		t.Fatalf("manual Off lost to automatic: %+v", got)
	}
	if code := doJSON(t, srv.client, http.MethodPost, url, fmt.Sprintf(`{"playId":%q}`, d.PlayID), nil).StatusCode; code != http.StatusConflict {
		t.Fatalf("cancelled attempt was replayed -> %d", code)
	}
}

func TestAutomaticSubtitleRegistryBoundsAndCancellation(t *testing.T) {
	t.Parallel()
	s := newAutomaticSubtitlePlays()
	actor := auth.User{ID: 1, SignInID: "one"}
	for i := range maxAutomaticSubtitlePlays + 1 {
		s.remember(actor, 1, fmt.Sprint(i), playback.Request{}, true)
	}
	if len(s.plays) != maxAutomaticSubtitlePlays {
		t.Fatalf("registry grew to %d", len(s.plays))
	}
	s.now = func() time.Time { return time.Now().Add(automaticSubtitleLifetime + time.Second) }
	s.remember(actor, 1, "fresh", playback.Request{}, true)
	if len(s.plays) != 1 {
		t.Fatalf("expired entries retained: %d", len(s.plays))
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	var calls atomic.Int32
	work := func(ctx context.Context, _ playback.Request) AutomaticSubtitleResult {
		calls.Add(1)
		if ctx.Err() != nil {
			t.Error("one caller's cancellation cancelled shared work")
		}
		return AutomaticSubtitleResult{Error: "done"}
	}
	got, err := s.run(ctx, actor, 1, "fresh", work)
	if err != nil || got.Error != "done" {
		t.Fatalf("cancelled first request: %+v %v", got, err)
	}
	got, err = s.run(t.Context(), actor, 1, "fresh", work)
	if err != nil || got.Error != "done" || calls.Load() != 1 {
		t.Fatalf("shared outcome: %+v %v calls=%d", got, err, calls.Load())
	}
	// Changing Off and back does not reset the one-attempt limit.
	s.remember(actor, 1, "fresh", playback.Request{}, false)
	s.remember(actor, 1, "fresh", playback.Request{}, true)
	_, _ = s.run(t.Context(), actor, 1, "fresh", work)
	if calls.Load() != 1 {
		t.Fatal("manual toggle reset the attempt limit")
	}
}

func TestAutomaticSubtitleAccessAndOptIn(t *testing.T) {
	t.Parallel()
	p := &automaticProvider{}
	srv, _ := automaticSubtitleEnv(t, p)
	file := subtitlePreferenceFile(t, srv, nil)
	url := fmt.Sprintf("%s/api/files/%d/subtitles/automatic", srv.URL, file.ID)
	// The default is forced-only with auto-download disabled. A client
	// cannot turn on quota-spending by sending subtitleMode alone.
	d := startAutomaticSubtitlePlay(t, srv, file, `,"subtitleMode":"auto"`)
	if code := doJSON(t, srv.client, http.MethodPost, url, fmt.Sprintf(`{"playId":%q}`, d.PlayID), nil).StatusCode; code != http.StatusConflict {
		t.Fatalf("no opt-in -> %d", code)
	}
	member, _ := securityTestUser(t, srv, "alex")
	doJSON(t, member, http.MethodPut, srv.URL+"/api/me/preferences", `{"subtitles":{"mode":"on","languages":["en"],"automaticDownload":true}}`, nil)
	var memberPlay playback.Decision
	doJSON(t, member, http.MethodPost, fmt.Sprintf("%s/api/files/%d/playback", srv.URL, file.ID), subtitleProfileBody(`,"subtitleMode":"auto"`), &memberPlay)
	if err := settings.New(srv.q).Set(t.Context(), settings.KeySubtitleDownloads, subtitles.DownloadsAdmins); err != nil {
		t.Fatal(err)
	}
	if code := doJSON(t, member, http.MethodPost, url, fmt.Sprintf(`{"playId":%q}`, memberPlay.PlayID), nil).StatusCode; code != http.StatusForbidden {
		t.Fatalf("member without download access -> %d", code)
	}
	if code := doJSON(t, member, http.MethodPost, url, fmt.Sprintf(`{"playId":%q,"cancel":true}`, memberPlay.PlayID), nil).StatusCode; code != 200 {
		t.Fatalf("cancelling after download access changed -> %d", code)
	}
	if p.searches.Load() != 0 || p.downloads.Load() != 0 {
		t.Fatal("denied automatic requests contacted provider")
	}
}

func TestAutomaticSubtitleRechecksPreferencesAndSessionBeforeQuota(t *testing.T) {
	t.Parallel()
	for _, change := range []string{"mode", "language", "permission", "sign-out", "stop"} {
		t.Run(change, func(t *testing.T) {
			p := &automaticProvider{searchStarted: make(chan struct{}), searchRelease: make(chan struct{})}
			srv, tracker := automaticSubtitleEnv(t, p)
			file := subtitlePreferenceFile(t, srv, nil)
			member, _ := securityTestUser(t, srv, "alex")
			doJSON(t, member, http.MethodPut, srv.URL+"/api/me/preferences", `{"subtitles":{"mode":"on","languages":["en"],"automaticDownload":true}}`, nil)
			var d playback.Decision
			doJSON(t, member, http.MethodPost, fmt.Sprintf("%s/api/files/%d/playback", srv.URL, file.ID), subtitleProfileBody(`,"subtitleMode":"auto"`), &d)
			finished := make(chan AutomaticSubtitleResult, 1)
			go func() {
				var got AutomaticSubtitleResult
				doJSON(t, member, http.MethodPost, fmt.Sprintf("%s/api/files/%d/subtitles/automatic", srv.URL, file.ID), fmt.Sprintf(`{"playId":%q}`, d.PlayID), &got)
				finished <- got
			}()
			<-p.searchStarted
			switch change {
			case "mode":
				doJSON(t, member, http.MethodPut, srv.URL+"/api/me/preferences", `{"subtitles":{"mode":"off"}}`, nil)
			case "language":
				doJSON(t, member, http.MethodPut, srv.URL+"/api/me/preferences", `{"subtitles":{"languages":["es"]}}`, nil)
			case "permission":
				if err := settings.New(srv.q).Set(t.Context(), settings.KeySubtitleDownloads, subtitles.DownloadsAdmins); err != nil {
					t.Fatal(err)
				}
			case "sign-out":
				doJSON(t, member, http.MethodPost, srv.URL+"/api/auth/logout", "", nil)
			case "stop":
				user, err := srv.q.GetUserByName(t.Context(), "alex")
				if err != nil {
					t.Fatal(err)
				}
				if _, err := tracker.Heartbeat(t.Context(), d.PlayID, user.ID, file.ID, activity.Report{State: activity.StateStopped}); err != nil {
					t.Fatal(err)
				}
			}
			close(p.searchRelease)
			if got := <-finished; got.Track != nil || got.Error != "" || p.downloads.Load() != 0 {
				t.Fatalf("quota spent after %s: %+v, %d", change, got, p.downloads.Load())
			}
		})
	}
}

func TestAutomaticSubtitleCanCancelWithoutAProvider(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, func(d *Deps) { d.Activity = activity.New(d.Conn, d.Bus, nil, activity.Options{}) })
	file := subtitlePreferenceFile(t, srv, nil)
	enableAutomaticSubtitles(t, srv)
	d := startAutomaticSubtitlePlay(t, srv, file, `,"subtitleMode":"auto"`)
	url := fmt.Sprintf("%s/api/files/%d/subtitles/automatic", srv.URL, file.ID)
	if code := doJSON(t, srv.client, http.MethodPost, url, fmt.Sprintf(`{"playId":%q}`, d.PlayID), nil).StatusCode; code != http.StatusServiceUnavailable {
		t.Fatalf("no provider -> %d", code)
	}
	if code := doJSON(t, srv.client, http.MethodPost, url, fmt.Sprintf(`{"playId":%q,"cancel":true}`, d.PlayID), nil).StatusCode; code != 200 {
		t.Fatalf("cancel without provider -> %d", code)
	}
}

func TestAutomaticSubtitleNeverEvictsInflightWorkAfterClockJump(t *testing.T) {
	t.Parallel()
	s := newAutomaticSubtitlePlays()
	actor := auth.User{ID: 1, SignInID: "one"}
	s.remember(actor, 1, "original", playback.Request{}, true)
	started, release, finished := make(chan struct{}), make(chan struct{}), make(chan struct{})
	go func() {
		defer close(finished)
		_, err := s.run(t.Context(), actor, 1, "original", func(context.Context, playback.Request) AutomaticSubtitleResult {
			close(started)
			<-release
			return AutomaticSubtitleResult{}
		})
		if err != nil {
			t.Errorf("original attempt failed: %v", err)
		}
	}()
	<-started
	s.mu.Lock()
	s.now = func() time.Time { return time.Now().Add(automaticSubtitleLifetime + time.Second) }
	s.mu.Unlock()
	s.remember(actor, 1, "another", playback.Request{}, true)
	s.mu.Lock()
	original := s.plays["original"]
	s.mu.Unlock()
	close(release)
	<-finished
	if original == nil {
		t.Fatal("clock jump evicted in-flight quota-spending work")
	}
}

func TestAutomaticSubtitleDifferentPlaysShareTheSameFileDownload(t *testing.T) {
	t.Parallel()
	p := &automaticProvider{downloadStarted: make(chan struct{}), downloadRelease: make(chan struct{})}
	srv, _ := automaticSubtitleEnv(t, p)
	file := subtitlePreferenceFile(t, srv, nil)
	enableAutomaticSubtitles(t, srv)
	one := startAutomaticSubtitlePlay(t, srv, file, `,"subtitleMode":"auto"`)
	two := startAutomaticSubtitlePlay(t, srv, file, `,"subtitleMode":"auto"`)
	var wg sync.WaitGroup
	for _, d := range []playback.Decision{one, two} {
		wg.Go(func() {
			var got AutomaticSubtitleResult
			if code := doJSON(t, srv.client, http.MethodPost, fmt.Sprintf("%s/api/files/%d/subtitles/automatic", srv.URL, file.ID), fmt.Sprintf(`{"playId":%q}`, d.PlayID), &got).StatusCode; code != 200 || got.Track == nil || got.Track.ID != "d1" {
				t.Errorf("automatic -> %d %+v", code, got)
			}
		})
	}
	<-p.downloadStarted
	close(p.downloadRelease)
	wg.Wait()
	if p.downloads.Load() != 1 {
		t.Fatalf("two plays spent %d downloads for the same result", p.downloads.Load())
	}
}
