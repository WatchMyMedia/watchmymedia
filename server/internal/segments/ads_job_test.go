package segments

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"watchmymedia/server/internal/bgwork"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/ffexec"
)

// One recording ffmpeg can't read is recorded as looked at, with nothing
// found, and the detector goes on to the next; a radio recording has no
// pictures to look at; ffmpeg not starting at all stops the run with
// nothing recorded.
func TestAdDetectorRecordsWhatItCouldNotReadAndGoesOn(t *testing.T) {
	t.Parallel()
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not installed")
	}
	ctx, cancel := context.WithTimeout(t.Context(), 2*time.Minute)
	defer cancel()
	conn, err := db.Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := db.Migrate(ctx, conn); err != nil {
		t.Fatal(err)
	}
	q := db.New(conn)
	dir := t.TempDir()
	lib, err := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "Recordings", Path: dir})
	if err != nil {
		t.Fatal(err)
	}
	broken := filepath.Join(dir, "broken.ts")
	if err := os.WriteFile(broken, []byte("not a broadcast at all"), 0o600); err != nil {
		t.Fatal(err)
	}
	ads := filepath.Join(dir, "ads.ts")
	windows := "between(t,10,10.2)+between(t,25,25.2)+between(t,40,40.2)"
	cmd := ffexec.Command(ctx, ffmpeg, ffexec.FFmpegArgs("-y", "-f", "lavfi", "-i", "testsrc2=size=96x64:rate=20:duration=45", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=45", "-vf", "drawbox=c=black:t=fill:enable='"+windows+"'", "-af", "volume=0:enable='"+windows+"'", "-c:v", "mpeg2video", "-threads", "1", "-c:a", "ac3", "-f", "mpegts", ads)...)
	if err := cmd.Run(); err != nil {
		t.Fatal(err)
	}
	now := db.FormatTime(time.Now())
	add := func(path string, probe *string) db.MediaFile {
		t.Helper()
		f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: lib.ID, Path: path, Size: 1, Mtime: now, AddedAt: now, LastSeenAt: now, Parsed: "{}", Probe: probe})
		if err != nil {
			t.Fatal(err)
		}
		return f
	}
	radioProbe := `{"container":"mpegts","durationMs":3600000,"video":[],"audio":[{"index":0,"codec":"mp2","channels":2}],"subtitles":[]}`
	files := []db.MediaFile{add(broken, nil), add(filepath.Join(dir, "radio.ts"), &radioProbe), add(ads, nil)}
	analysed := func(f db.MediaFile) bool {
		return Analysed(ctx, q, FileVersion{ID: f.ID, Mtime: f.Mtime, Size: f.Size}, "blackframe", AdBreakAlgoVersion)
	}
	// As the recorder's query does: finished files without a current analysis.
	candidates := func(rows []db.MediaFile) func(context.Context) ([]db.MediaFile, error) {
		return func(context.Context) ([]db.MediaFile, error) {
			var out []db.MediaFile
			for _, f := range rows {
				if !analysed(f) {
					out = append(out, f)
				}
			}
			return out, nil
		}
	}
	detector := func(ffmpeg string, rows []db.MediaFile) *AdDetector {
		return NewAdDetector(q, conn, events.NewBus(), slog.New(slog.NewTextHandler(io.Discard, nil)), AdOptions{FFmpeg: ffmpeg,
			Candidates: candidates(rows), Enabled: func(context.Context) bool { return true }, Gate: bgwork.Gate{}})
	}

	if err := detector(ffmpeg, files).RunOnce(ctx); err != nil {
		t.Fatal(err)
	}
	for _, f := range files {
		if !analysed(f) {
			t.Errorf("%s was not recorded as looked at", filepath.Base(f.Path))
		}
	}
	found, err := q.ListFileSegments(ctx, files[2].ID)
	if err != nil || len(found) != 1 || found[0].Kind != "commercial" {
		t.Fatalf("the recording after the broken one: %+v %v", found, err)
	}

	// Without ffmpeg nothing is learned: the run stops, and the file waits.
	later := add(filepath.Join(dir, "later.ts"), nil)
	if err := detector(filepath.Join(dir, "no-ffmpeg-here"), []db.MediaFile{later}).RunOnce(ctx); !errors.Is(err, ErrNoDecoder) {
		t.Fatalf("run without ffmpeg = %v", err)
	}
	if analysed(later) {
		t.Error("a file was recorded as looked at without ffmpeg")
	}
	// A radio recording is never decoded, so it needs no ffmpeg.
	radio := add(filepath.Join(dir, "radio-2.ts"), &radioProbe)
	if err := detector(filepath.Join(dir, "no-ffmpeg-here"), []db.MediaFile{radio}).RunOnce(ctx); err != nil || !analysed(radio) {
		t.Fatalf("radio without ffmpeg = %v, analysed %v", err, analysed(radio))
	}
}

func TestAdPassTimeGrowsWithTheRecording(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		durationMs, size int64
		want             time.Duration
	}{
		{0, 0, 2 * time.Hour},
		{(30 * time.Minute).Milliseconds(), 0, 3 * time.Hour},
		{(12 * time.Hour).Milliseconds(), 0, 26 * time.Hour},
		{0, 3600 * 1_000_000, 4 * time.Hour}, // an hour at 8 Mb/s
	} {
		if got := adTimeout(c.durationMs, c.size); got != c.want {
			t.Errorf("adTimeout(%d, %d) = %s, want %s", c.durationMs, c.size, got, c.want)
		}
	}
}
