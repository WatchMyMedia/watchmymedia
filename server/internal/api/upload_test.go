package api

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"sync"
	"testing"
	"time"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/netguard"
	"watchmymedia/server/internal/upload"
)

func TestUploadMiddlewareMediaPathsAndClassification(t *testing.T) {
	paths := []string{
		"/api/files/3/stream", "/api/playback/session/master.m3u8",
		"/api/live-tv/sessions/live/hls/00001.ts", "/api/offline/record/stream",
		"/api/offline/record/hls/00001.m4s", "/api/offline/record/subtitles/1",
	}
	for _, path := range paths {
		t.Run(path, func(t *testing.T) {
			l := upload.New(1_000_000, false)
			h := uploadMiddleware(l)(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write([]byte("media")) }))
			r := httptest.NewRequest(http.MethodGet, path, nil)
			r = r.WithContext(netguard.NewContext(auth.WithUser(r.Context(), auth.User{SignInID: "fictional-sign-in"}), netguard.Info{IP: netip.MustParseAddr("198.51.100.1")}))
			h.ServeHTTP(httptest.NewRecorder(), r)
			if got := l.Snapshot().DeliveredBytes; got != 5 {
				t.Fatalf("remote media delivered = %d", got)
			}
		})
	}
	l := upload.New(1_000_000, false)
	h := uploadMiddleware(l)(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write([]byte("media")) }))
	for _, info := range []netguard.Info{{IP: netip.MustParseAddr("192.168.1.10")}, {IP: netip.MustParseAddr("127.0.0.1"), Proxied: true}} {
		r := httptest.NewRequest(http.MethodGet, "/api/files/3/stream", nil)
		r = r.WithContext(netguard.NewContext(r.Context(), info))
		h.ServeHTTP(httptest.NewRecorder(), r)
	}
	if got := l.Snapshot().DeliveredBytes; got != 5 {
		t.Fatalf("trusted local vs unknown proxy = %d", got)
	}
	for _, path := range []string{"/api/settings", "/api/playback/capabilities", "/api/playback/local"} {
		r := httptest.NewRequest(http.MethodGet, path, nil)
		h.ServeHTTP(httptest.NewRecorder(), r)
		if got := l.Snapshot().DeliveredBytes; got != 5 {
			t.Fatalf("control route %s counted: %d", path, got)
		}
	}
}

func TestUploadHTTPBudgetChangeWithFourViewers(t *testing.T) {
	l := upload.New(4_000_000, true)
	const viewers, size = 4, 192 << 10
	body := bytes.Repeat([]byte{'m'}, size)
	h := uploadMiddleware(l)(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "video/mp4")
		_, _ = w.Write(body)
	}))
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		r = r.WithContext(netguard.NewContext(auth.WithUser(r.Context(), auth.User{SignInID: r.Header.Get("X-Test-Sign-In")}), netguard.Info{IP: netip.MustParseAddr("198.51.100.9")}))
		h.ServeHTTP(w, r)
	}))
	defer srv.Close()
	start := time.Now()
	var wg sync.WaitGroup
	for i := range viewers {
		wg.Go(func() {
			r, err := http.NewRequest(http.MethodGet, srv.URL+"/api/files/3/stream", nil)
			if err != nil {
				t.Error(err)
				return
			}
			r.Header.Set("X-Test-Sign-In", string(rune('a'+i)))
			res, err := srv.Client().Do(r)
			if err != nil {
				t.Error(err)
				return
			}
			defer res.Body.Close()
			n, err := io.Copy(io.Discard, res.Body)
			if err != nil || n != size {
				t.Errorf("viewer %d received %d bytes: %v", i, n, err)
			}
		})
	}
	deadline := time.Now().Add(time.Second)
	for l.Snapshot().ActiveViews < viewers && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if l.Snapshot().ActiveViews != viewers {
		t.Fatal("viewers did not join concurrently")
	}
	l.Update(1_200_000, true)
	time.Sleep(250 * time.Millisecond)
	l.Update(4_000_000, true)
	wg.Wait()
	s := l.Snapshot()
	if s.DeliveredBytes != viewers*size || s.PacedBytes != viewers*size || s.ActiveViews != 0 || s.WaitNanos == 0 {
		t.Fatalf("budget change/cleanup = %+v", s)
	}
	if time.Since(start) < time.Second {
		t.Fatalf("concurrent streams bypassed aggregate cap in %s", time.Since(start))
	}
}
