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
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/probe"
)

// resumeEnv is a server with real ffmpeg and ffprobe and one movie: an
// H.264 MP4 with a keyframe every 10 s and AC-3 sound, which a player that
// takes AAC only gets as copied video with converted sound. The server has
// no keyframe list for it (no Keyframes), so every play of it gets an event
// playlist, as a file's first play does while its keyframes are scanned.
type resumeEnv struct {
	*testEnv
	mgr    *playback.Manager
	fileID int64
}

func newResumeEnv(t *testing.T) resumeEnv {
	t.Helper()
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not installed")
	}
	if _, err := exec.LookPath("ffprobe"); err != nil {
		t.Skip("ffprobe not installed")
	}
	path := filepath.Join(t.TempDir(), "Clip (2020).mp4")
	if b, err := exec.Command(ffmpeg, "-v", "error", "-y",
		"-f", "lavfi", "-i", "testsrc2=size=160x90:rate=25:duration=60",
		"-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=60",
		"-c:v", "libx264", "-preset", "veryfast", "-g", "250", "-keyint_min", "250", "-sc_threshold", "0", "-bf", "2",
		"-c:a", "ac3", "-shortest", path).CombinedOutput(); err != nil {
		t.Skipf("ffmpeg cannot make the clip (libx264 and ac3 needed): %v\n%s", err, b)
	}
	mgr := playback.NewManager(ffmpeg, filepath.Join(t.TempDir(), "transcode"), playback.Capabilities{Encoder: "libx264"}, time.Minute, 8, slog.New(slog.NewTextHandler(io.Discard, nil)))
	// Beside the package's other ffmpeg tests a 20 ms lookup can take
	// longer than the start's bound, and the session then starts as asked.
	mgr.KeyframeLookupsMayTake(10 * time.Second)
	t.Cleanup(mgr.StopAll)
	srv := newTestServerWith(t, func(d *Deps) { d.Playback, d.Prober = mgr, &probe.Prober{} })
	ctx := t.Context()
	probeJSON := `{"container":"mov,mp4,m4a,3gp,3g2,mj2","durationMs":60000,"video":[{"index":0,"codec":"h264","profile":"High","width":160,"height":90,"pixFmt":"yuv420p","frameRate":25}],"audio":[{"index":1,"codec":"ac3","channels":2,"default":true}],"subtitles":[]}`
	f, err := srv.q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: srv.fileLibrary(t, path), Path: path, Size: 1, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()),
		Parsed: "{}", Probe: &probeJSON, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	movie, err := srv.q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: f.LibraryID, TmdbID: 4242, Title: "Clip", Genres: "[]"})
	if err != nil {
		t.Fatal(err)
	}
	if err := srv.q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &movie.ID, ID: f.ID}); err != nil {
		t.Fatal(err)
	}
	return resumeEnv{testEnv: srv, mgr: mgr, fileID: f.ID}
}

// play asks for playback as a player that takes MP4, H.264 and AAC; extra
// is added to the request body, such as `,"startMs":25000`.
func (e resumeEnv) play(t *testing.T, extra string) playback.Decision {
	t.Helper()
	body := `{"profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"],"maxAudioChannels":2},"playlist":"vod"` + extra + `}`
	var d playback.Decision
	if res := doJSON(t, e.client, http.MethodPost, fmt.Sprintf("%s/api/files/%d/playback", e.URL, e.fileID), body, &d); res.StatusCode != http.StatusOK {
		t.Fatalf("playback %s -> %d %s", body, res.StatusCode, res.Body)
	}
	if d.Mode != playback.ModeAudio || d.Protocol != "hls" || d.Playlist != "event" {
		t.Fatalf("want copied video in an event playlist, got mode %s, %s, playlist %q (%v)", d.Mode, d.Protocol, d.Playlist, d.Reasons)
	}
	return d
}

func (e resumeEnv) playlist(t *testing.T, d playback.Decision) string {
	t.Helper()
	res := doJSON(t, e.client, http.MethodGet, e.URL+d.URL, "", nil)
	if res.StatusCode != http.StatusOK {
		t.Fatalf("playlist -> %d", res.StatusCode)
	}
	return string(res.Body)
}

// An event play of copied video starts on the keyframe before the asked-for
// time and says so, and its playlist tells the player to start at the asked-
// for frame rather than near the end of what is prepared.
func TestEventPlaybackStartsOnAKeyframe(t *testing.T) {
	t.Parallel()
	e := newResumeEnv(t)
	d := e.play(t, `,"startMs":25000`)
	if d.StartMs != 20_000 {
		t.Errorf("startMs = %d, want 20000: the keyframe the stream really starts on", d.StartMs)
	}
	if pl := e.playlist(t, d); !strings.Contains(pl, "#EXT-X-START:TIME-OFFSET=5.000,PRECISE=YES\n") {
		t.Errorf("playlist does not say to start 5 s in:\n%s", pl)
	}
	// A start that is a keyframe starts there, at the playlist's start.
	d = e.play(t, `,"startMs":40000,"playId":"`+d.PlayID+`"`)
	if d.StartMs != 40_000 {
		t.Errorf("startMs from 40 s = %d, want 40000", d.StartMs)
	}
	if pl := e.playlist(t, d); !strings.Contains(pl, "#EXT-X-START:TIME-OFFSET=0.000,PRECISE=YES\n") {
		t.Errorf("playlist from a keyframe does not say to start at its beginning:\n%s", pl)
	}
}

// Resuming starts 5 s before where the person stopped, from one request:
// with no start in it, an event play starts there (on the keyframe before),
// while the saved position stays where it was. An explicit start, 0
// included, is not moved.
func TestResumeStartsFiveSecondsBack(t *testing.T) {
	t.Parallel()
	e := newResumeEnv(t)
	var p Progress
	if res := doJSON(t, e.client, http.MethodPut, fmt.Sprintf("%s/api/files/%d/progress", e.URL, e.fileID), `{"positionMs":40000,"durationMs":60000}`, &p); res.StatusCode != http.StatusOK || p.PositionMs != 40_000 {
		t.Fatalf("progress -> %d %+v", res.StatusCode, p)
	}
	d := e.play(t, "")
	if d.ResumeMs != 35_000 {
		t.Errorf("resumeMs = %d, want 35000", d.ResumeMs)
	}
	if d.StartMs != 30_000 {
		t.Errorf("startMs with no start = %d, want 30000: the keyframe before the resume point", d.StartMs)
	}
	if pl := e.playlist(t, d); !strings.Contains(pl, "#EXT-X-START:TIME-OFFSET=5.000,PRECISE=YES\n") {
		t.Errorf("playlist does not start at the resume point:\n%s", pl)
	}
	if d = e.play(t, `,"startMs":0,"playId":"`+d.PlayID+`"`); d.StartMs != 0 || d.ResumeMs != 35_000 {
		t.Errorf("from the start: startMs %d resumeMs %d, want 0 and 35000", d.StartMs, d.ResumeMs)
	}
	if d = e.play(t, `,"startMs":40000,"playId":"`+d.PlayID+`"`); d.StartMs != 40_000 {
		t.Errorf("an explicit start of 40 s was moved to %d", d.StartMs)
	}
	var cont ContinueList
	if res := doJSON(t, e.client, http.MethodGet, e.URL+"/api/watch/continue", "", &cont); res.StatusCode != http.StatusOK || len(cont.Items) != 1 || cont.Items[0].PositionMs != 40_000 {
		b, _ := json.Marshal(cont)
		t.Errorf("continue watching -> %d %s, want the saved 40 s", res.StatusCode, b)
	}
}
