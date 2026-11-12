package streamtest

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"testing/synctest"
	"time"
)

func TestAggregateIsSharedAcrossConcurrentRequests(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s := NewShaper()
		if err := s.Update(Profile{Aggregate: Rate{Bps: 8 * ChunkBytes}}); err != nil {
			t.Fatal(err)
		}
		handler := s.Middleware(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			_, _ = w.Write(make([]byte, 2*ChunkBytes))
		}))
		start := time.Now()
		var wg sync.WaitGroup
		for range 4 {
			wg.Go(func() {
				r := httptest.NewRequest(http.MethodGet, "/api/files/1/stream", nil)
				r.Header.Set("X-WMM-Fixture-Viewer", "viewer-1")
				w := httptest.NewRecorder()
				handler.ServeHTTP(w, r)
				if w.Body.Len() != 2*ChunkBytes {
					t.Errorf("body size %d", w.Body.Len())
				}
			})
		}
		wg.Wait()
		if elapsed := time.Since(start); elapsed < 8*time.Second {
			t.Fatalf("four requests multiplied the aggregate rate: %s", elapsed)
		}
		snap := s.Snapshot()
		if snap.Total.Bytes != 8*ChunkBytes || snap.Total.Requests != 4 || snap.Total.Active != 0 {
			t.Fatalf("traffic %+v", snap)
		}
	})
}

func TestViewerCapIsIndependentAndSharedAcrossConnections(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s := NewShaper()
		if err := s.Update(Profile{Viewers: map[string]Rate{"viewer-1": {Bps: 8 * ChunkBytes}}}); err != nil {
			t.Fatal(err)
		}
		start := time.Now()
		finished := make(chan time.Duration, 3)
		for range 2 {
			go func() { _ = s.wait(t.Context(), "viewer-1", ChunkBytes); finished <- time.Since(start) }()
		}
		if err := s.wait(t.Context(), "viewer-2", ChunkBytes); err != nil {
			t.Fatal(err)
		}
		if time.Since(start) != 0 {
			t.Fatal("slow viewer blocked an unrelated viewer")
		}
		a, b := <-finished, <-finished
		if max(a, b) < 2*time.Second {
			t.Fatalf("new connection reset viewer credit: %s, %s", a, b)
		}
	})
}

func TestBothCapsAreChargedAtDelivery(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s := NewShaper()
		if err := s.Update(Profile{Aggregate: Rate{Bps: 8 * ChunkBytes}, Viewers: map[string]Rate{"viewer-1": {Bps: 4 * ChunkBytes}}}); err != nil {
			t.Fatal(err)
		}
		start := time.Now()
		if err := s.wait(t.Context(), "viewer-1", ChunkBytes); err != nil {
			t.Fatal(err)
		}
		// A slow child must not pre-charge aggregate tokens and release a
		// collection of previously paid chunks in a later unrestricted burst.
		for range 5 {
			if err := s.wait(t.Context(), "viewer-2", ChunkBytes); err != nil {
				t.Fatal(err)
			}
		}
		if time.Since(start) < 5*time.Second {
			t.Fatal("hierarchical shaping released uncharged bytes")
		}
	})
}

func TestProfileChangeWakesAnOpenTransferAndDiscardsCredit(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s := NewShaper()
		if err := s.Update(Profile{Aggregate: Rate{Blocked: true}}); err != nil {
			t.Fatal(err)
		}
		done := make(chan error, 1)
		go func() { done <- s.wait(t.Context(), "viewer-1", ChunkBytes) }()
		synctest.Wait()
		if err := s.Update(Profile{Aggregate: Rate{Bps: 8 * ChunkBytes}}); err != nil {
			t.Fatal(err)
		}
		synctest.Wait()
		time.Sleep(500 * time.Millisecond)
		select {
		case <-done:
			t.Fatal("profile update granted a free chunk")
		default:
		}
		if err := <-done; err != nil {
			t.Fatal(err)
		}
		if s.Snapshot().Revision != 2 {
			t.Fatal("missing profile revision")
		}
	})
}

func TestCancellationReleasesARequestDuringOutage(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s := NewShaper()
		if err := s.Update(Profile{Aggregate: Rate{Blocked: true}}); err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithCancel(t.Context())
		done := make(chan error, 1)
		h := s.Middleware(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			_, err := w.Write(make([]byte, ChunkBytes))
			done <- err
		}))
		r := httptest.NewRequest(http.MethodGet, "/api/files/1/stream", nil).WithContext(ctx)
		joined := make(chan struct{})
		go func() { h.ServeHTTP(httptest.NewRecorder(), r); close(joined) }()
		synctest.Wait()
		cancel()
		if err := <-done; !errors.Is(err, context.Canceled) {
			t.Fatal(err)
		}
		<-joined
		if snap := s.Snapshot(); snap.Total.Active != 0 || snap.Total.Bytes != 0 {
			t.Fatalf("leaked request: %+v", snap)
		}
	})
}

func TestControlsAndHeartbeatsAreNotShapedOrAuthorizedByViewerHeader(t *testing.T) {
	s := NewShaper()
	if err := s.Update(Profile{Aggregate: Rate{Blocked: true}}); err != nil {
		t.Fatal(err)
	}
	h := s.Middleware(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusForbidden)
		_, _ = w.Write([]byte("denied"))
	}))
	for _, path := range []string{"/_fixture/status", "/_fixture/streaming/profile", "/api/files/1/progress", "/api/playback/capabilities", "/api/playback/local", "/api/offline/downloads", "/api/offline/record/token"} {
		if MediaPath(path) {
			t.Fatalf("metadata/control route classified as media: %s", path)
		}
		r := httptest.NewRequest(http.MethodPut, path, nil)
		r.Header.Set("X-WMM-Fixture-Viewer", "viewer-1")
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != http.StatusForbidden || w.Body.String() != "denied" {
			t.Fatal("fixture identity bypassed authorization")
		}
	}
	if s.Snapshot().Total.Requests != 0 {
		t.Fatal("control traffic was shaped")
	}
	for _, path := range []string{"/api/files/1/stream", "/api/playback/0123/master.m3u8", "/api/live-tv/sessions/0123/hls/live.m3u8",
		"/api/offline/record/stream", "/api/offline/record/hls/00001.m4s", "/api/offline/record/subtitles/1"} {
		if !MediaPath(path) {
			t.Fatalf("media route escaped shaping: %s", path)
		}
	}
}

func TestProfileValidationAndSnapshotsDoNotShareMutableMaps(t *testing.T) {
	s := NewShaper()
	for _, p := range []Profile{{Aggregate: Rate{Bps: -1}}, {Viewers: map[string]Rate{"unknown": {Bps: 1}}}} {
		if s.Update(p) == nil {
			t.Fatal("accepted an invalid profile")
		}
	}
	p := Profile{Viewers: map[string]Rate{"viewer-1": {Bps: 10}}}
	if err := s.Update(p); err != nil {
		t.Fatal(err)
	}
	p.Viewers["viewer-1"] = Rate{}
	snap := s.Snapshot()
	snap.Profile.Viewers["viewer-1"] = Rate{}
	if s.Snapshot().Profile.Viewers["viewer-1"].Bps != 10 {
		t.Fatal("caller mutated shaper state")
	}
}
