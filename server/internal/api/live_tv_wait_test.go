package api

import (
	"context"
	"fmt"
	"log/slog"
	"net/http/httptest"
	"testing"
	"time"

	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/hdhomerun"
	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
	"watchmymedia/server/internal/playback"
)

// getLiveTVSession with waitMs replies once a tuning channel plays (API
// level 28); without it the reply is the state now, as before. The waits'
// timing is TestStatusWaitReturnsWhenStartingEnds's, on a fake clock; this
// is the route, on a real tune.
func TestLiveTVSessionStatusWaits(t *testing.T) {
	t.Parallel()
	tuner := httptest.NewServer(hdhrtest.New(hdhrtest.Broadcast("ffmpeg")))
	t.Cleanup(tuner.Close)
	var pm *playback.Manager
	srv := newTestServerWith(t, func(d *Deps) {
		d.LiveTV = livetv.New(d.Queries, d.Settings, func(a string) (*hdhomerun.Client, error) { return hdhomerun.New(a, hdhrtest.Transport(tuner.URL)) })
		pm = playback.NewManager("ffmpeg", t.TempDir(), playback.Capabilities{Encoder: "libx264"}, time.Minute, 1, slog.New(slog.DiscardHandler))
		d.Playback = pm
		sessions := livetv.NewSessions(d.LiveTV, pm, livetv.SessionOptions{FFmpeg: "ffmpeg", Dir: t.TempDir(), Validate: func(ctx context.Context, id string) (livetv.Owner, error) {
			u, err := d.Auth.ValidateSignIn(ctx, id)
			return liveOwner(u), err
		}})
		d.LiveSessions = sessions
		t.Cleanup(sessions.Close)
	})
	t.Cleanup(pm.StopAll)
	if r := doJSON(t, srv.client, "POST", srv.URL+"/api/settings/live-tv/sources", `{"address":"tuner.test"}`, nil); r.StatusCode >= 300 {
		t.Fatalf("source: %d %s", r.StatusCode, r.Body)
	}
	var channels struct {
		Items []livetv.Channel `json:"items"`
	}
	doJSON(t, srv.client, "GET", srv.URL+"/api/live-tv/channels", "", &channels)
	var started livetv.SessionView
	if r := doJSON(t, srv.client, "POST", srv.URL+"/api/live-tv/sessions", fmt.Sprintf(`{"channelId":%q,"requestId":"wait","quality":"480"}`, channels.Items[0].ID), &started); r.StatusCode != 200 {
		t.Fatalf("start: %d %s", r.StatusCode, r.Body)
	}
	status := func(query string) (livetv.SessionView, time.Duration) {
		t.Helper()
		var v livetv.SessionView
		start := time.Now()
		if r := doJSON(t, srv.client, "GET", srv.URL+"/api/live-tv/sessions/"+started.ID+query, "", &v); r.StatusCode != 200 {
			t.Fatalf("status%s: %d %s", query, r.StatusCode, r.Body)
		}
		return v, time.Since(start)
	}
	// The tune needs seconds of broadcast, so the session is starting now.
	if v, took := status(""); v.State != "starting" || took > 2*time.Second {
		t.Fatalf("status without waitMs: %s after %v, want starting at once", v.State, took)
	}
	// A busy runner may take longer than one wait to tune.
	v, took := status("?waitMs=10000")
	for tries := 1; v.State == "starting" && tries < 3; tries++ {
		if took < livetv.MaxStatusWait {
			t.Fatalf("starting after only %v of a 10 s wait", took)
		}
		v, took = status("?waitMs=10000")
	}
	if v.State != "playing" || took > livetv.MaxStatusWait+5*time.Second {
		t.Fatalf("status with waitMs: %s after %v", v.State, took)
	}
	// Past starting, any wait answers at once; a huge one is capped, not refused.
	if v, took := status("?waitMs=9223372036854775807"); v.State != "playing" || took > 2*time.Second {
		t.Errorf("status of a playing session with a huge waitMs: %s after %v", v.State, took)
	}
	if r := doJSON(t, srv.client, "GET", srv.URL+"/api/live-tv/sessions/"+started.ID+"?waitMs=-1", "", nil); r.StatusCode != 422 {
		t.Errorf("negative waitMs -> %d, want 422", r.StatusCode)
	}
	if r := doJSON(t, srv.client, "DELETE", srv.URL+"/api/live-tv/sessions/"+started.ID, "", nil); r.StatusCode >= 300 {
		t.Errorf("stop: %d", r.StatusCode)
	}
}
