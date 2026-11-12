package api

import (
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/playback"
)

// fakeClock is a settable clock for the tracker.
type fakeClock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *fakeClock) now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}

func (c *fakeClock) add(d time.Duration) {
	c.mu.Lock()
	c.t = c.t.Add(d)
	c.mu.Unlock()
}

// An Automatic play starts as direct play on the local network, is told to
// drop to 720p when it stalls on a slow link, and its reload is decided at
// 720p: a transcode capped at 4 Mbps, with the audio copied.
func TestAutomaticQuality(t *testing.T) {
	t.Parallel()
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not installed")
	}
	mgr := playback.NewManager(ffmpeg, filepath.Join(t.TempDir(), "transcode"), playback.Capabilities{Encoder: "libx264"}, time.Minute, 8, slog.New(slog.NewTextHandler(io.Discard, nil)))
	t.Cleanup(mgr.StopAll)
	clock := &fakeClock{t: time.Now()}
	srv := newTestServerWith(t, func(d *Deps) {
		d.Playback = mgr
		d.Activity = activity.New(d.Conn, d.Bus, nil, activity.Options{Sessions: mgr, Now: clock.now})
	})
	ctx := t.Context()
	path := filepath.Join(t.TempDir(), "Heat (1995).mkv")
	if b, err := exec.Command(ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10:duration=20",
		"-f", "lavfi", "-i", "sine=frequency=440:duration=20", "-c:v", "mpeg4", "-c:a", "aac", "-shortest", path).CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg: %v\n%s", err, b)
	}
	// The probe says a 40 Mbps 1080p remux, which is what the decision goes by.
	probeJSON := `{"container":"matroska","durationMs":20000,"bitRate":40000000,"video":[{"index":0,"codec":"mpeg4","width":1920,"height":1080}],"audio":[{"index":1,"codec":"aac","channels":2,"bitRate":160000,"default":true}],"subtitles":[]}`
	f, err := srv.q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: srv.fileLibrary(t, path), Path: path, Size: 1, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()),
		Parsed: "{}", Probe: &probeJSON, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	base := srv.URL + "/api"
	decide := func(playID string) playback.Decision {
		t.Helper()
		var d playback.Decision
		body := `{"profile":{"containers":["mkv"],"videoCodecs":["mpeg4","h264"],"audioCodecs":["aac"]},"quality":"auto","playId":"` + playID + `","client":{"deviceId":"tab-1"}}`
		if playID == "" {
			body = `{"profile":{"containers":["mkv"],"videoCodecs":["mpeg4","h264"],"audioCodecs":["aac"]},"quality":"auto","client":{"deviceId":"tab-1"}}`
		}
		res, err := srv.client.Post(fmt.Sprintf("%s/files/%d/playback", base, f.ID), "application/json", strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		b, _ := io.ReadAll(res.Body)
		if res.StatusCode != 200 || json.Unmarshal(b, &d) != nil {
			t.Fatalf("playback -> %d %s", res.StatusCode, b)
		}
		return d
	}
	beat := func(playID, body string) Progress {
		t.Helper()
		var p Progress
		if code := doJSON(t, srv.client, http.MethodPut, fmt.Sprintf("%s/files/%d/progress", base, f.ID), `{"playId":"`+playID+`",`+body+`}`, &p).StatusCode; code != 200 {
			t.Fatalf("heartbeat -> %d", code)
		}
		return p
	}

	d := decide("")
	if d.Mode != playback.ModeDirect || d.Auto == nil || d.Auto.Step != "original" || d.Auto.Reason != "on the local network" || d.Bitrate != 40_000_000 {
		t.Fatalf("first decision: mode %s, auto %+v, bitrate %d", d.Mode, d.Auto, d.Bitrate)
	}
	stale := `{"profile":{"containers":["mkv"],"videoCodecs":["mpeg4","h264"],"audioCodecs":["aac"]},"quality":"auto","autoRecovery":true,"playId":"` + d.PlayID + `","switchGeneration":1}`
	if code := doJSON(t, srv.client, http.MethodPost, fmt.Sprintf("%s/files/%d/playback", base, f.ID), stale, nil).StatusCode; code != http.StatusConflict {
		t.Fatalf("stale generation returned %d, want 409", code)
	}
	// Positions stay inside the 20 s the probe gives: near the end, a player
	// holding the rest of the file is never told to step down.
	clock.add(2 * time.Second)
	beat(d.PlayID, `"positionMs":0,"state":"playing","bufferedMs":3000,"throughputBps":6000000,"stalls":0`)
	clock.add(30 * time.Second)
	p := beat(d.PlayID, `"positionMs":10000,"state":"buffering","bufferedMs":0,"throughputBps":6000000,"stalls":1,"stallMs":400`)
	if p.Directive == nil || p.Directive.Action != "quality" || p.Directive.Quality != "720" {
		t.Fatalf("directive = %+v", p.Directive)
	}

	d2 := decide(d.PlayID)
	if d2.PlayID != d.PlayID || d2.Mode != playback.ModeTranscode || d2.Auto == nil || d2.Auto.Step != "720" || d2.Video.Height != 720 || d2.Bitrate != 4_000_000+160_000 {
		t.Fatalf("reload: mode %s, auto %+v, %dx%d at %d", d2.Mode, d2.Auto, d2.Video.Width, d2.Video.Height, d2.Bitrate)
	}
	var live LiveActivity
	doJSON(t, srv.client, http.MethodGet, base+"/activity", "", &live)
	if len(live.Plays) != 1 {
		t.Fatalf("%d plays", len(live.Plays))
	}
	lp := live.Plays[0]
	if !lp.Auto.On || lp.Auto.Step != "720" || len(lp.Auto.Switches) != 1 || lp.Stream.Quality != "auto" || lp.Stream.Step != "720" ||
		lp.Throughput != 6_000_000 || lp.Stalls != 1 || lp.Stream.Stream.Bitrate != 4_160_000 {
		t.Fatalf("live play: auto %+v, stream %+v, throughput %d, stalls %d", lp.Auto, lp.Stream, lp.Throughput, lp.Stalls)
	}
	// A heartbeat after the reload carries no directive.
	clock.add(5 * time.Second)
	if p := beat(d.PlayID, `"positionMs":11000,"state":"playing","bufferedMs":5000,"throughputBps":6000000,"stalls":0`); p.Directive != nil {
		t.Fatalf("directive after the reload: %+v", p.Directive)
	}
	beat(d.PlayID, `"positionMs":11000,"state":"stopped"`)
}
