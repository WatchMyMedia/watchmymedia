package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/probe"
)

// A file whose stored probe has its chapters plays without ffprobe: the
// first play's chapter segments come from the probe.
func TestPlaybackChaptersFromStoredProbe(t *testing.T) {
	// Any ffprobe run fails, and with it the chapter segments.
	srv := newTestServerWith(t, func(d *Deps) { d.Prober = &probe.Prober{Path: "/nonexistent/ffprobe"} })
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "Show - S01E01.mkv")
	if err := os.WriteFile(path, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	raw, err := probe.Encode(&probe.MediaInfo{Container: "matroska", DurationMs: 20000,
		Video: []probe.VideoStream{{Index: 0, Codec: "mpeg4", Width: 64, Height: 64}}, Audio: []probe.AudioStream{}, Subtitles: []probe.SubtitleStream{},
		Chapters: []probe.Chapter{{StartMs: 0, EndMs: 2000, Title: "Intro"}, {StartMs: 2000, EndMs: 12000, Title: "Episode"}, {StartMs: 12000, EndMs: 20000, Title: "End Credits"}},
		Extended: true})
	if err != nil {
		t.Fatal(err)
	}
	probeJSON := string(raw)
	now := time.Now()
	f, err := srv.q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: srv.fileLibrary(t, path), Path: path, Size: 1, Mtime: db.FormatTime(now), AddedAt: db.FormatTime(now),
		Parsed: "{}", Probe: &probeJSON, LastSeenAt: db.FormatTime(now), SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	body := `{"profile":{"containers":["mkv"],"videoCodecs":["mpeg4"],"audioCodecs":["aac"]}}`
	res, err := srv.client.Post(srv.URL+fmt.Sprintf("/api/files/%d/playback", f.ID), "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = res.Body.Close() }()
	var d playback.Decision
	if err := json.NewDecoder(res.Body).Decode(&d); err != nil || res.StatusCode != 200 {
		t.Fatalf("playback: %d %v", res.StatusCode, err)
	}
	if len(d.Segments) != 2 || d.Segments[0].Kind != "intro" || d.Segments[1].Kind != "credits" || d.CreditsMs != 12000 {
		t.Errorf("segments = %+v", d.Segments)
	}
	// The API's file detail shows the probe without what only playback reads.
	res2, err := srv.client.Get(srv.URL + fmt.Sprintf("/api/files/%d", f.ID))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = res2.Body.Close() }()
	var detail map[string]json.RawMessage
	if err := json.NewDecoder(res2.Body).Decode(&detail); err != nil {
		t.Fatal(err)
	}
	if p := string(detail["probe"]); !strings.Contains(p, `"durationMs":20000`) || strings.Contains(p, "chapters") || strings.Contains(p, "extra") {
		t.Errorf("file detail probe = %s", p)
	}
}

// A start the manager turns down is answered by why: busy (503, try again),
// replaced (409), or broken (500).
func TestStartErrorReplies(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		err    error
		status int
		retry  string
	}{
		{fmt.Errorf("%w (8)", playback.ErrTooManySessions), 503, "5"},
		{playback.ErrRenditionBusy, 503, "5"},
		{playback.ErrLadderNotReady, 503, "5"},
		{playback.ErrStorageBudget, 503, "10"},
		{playback.ErrStopped, 409, ""},
		{errors.New("ffmpeg exited"), 500, ""},
	} {
		err := startError(c.err)
		retry := ""
		if he, ok := errors.AsType[huma.HeadersError](err); ok {
			retry = he.GetHeaders().Get("Retry-After")
		}
		if se, ok := errors.AsType[huma.StatusError](err); !ok || se.GetStatus() != c.status || retry != c.retry {
			t.Errorf("%v: %v, Retry-After %q", c.err, err, retry)
		}
	}
}
