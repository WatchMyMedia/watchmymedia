package segments

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/probe"
)

// encode writes samples as an AAC or AC-3 file, as different releases of
// a show's episodes would be. The video is made as long as the audio rather
// than cut with -shortest, which on a busy machine can let it run on for
// minutes past the audio and so lengthen the file.
// It may run on its own goroutine, so it reports failures with Error.
func encode(t *testing.T, ffmpeg, dir, name string, samples []int16, codec string) string {
	t.Helper()
	raw := filepath.Join(dir, name+".raw")
	f, err := os.Create(raw)
	if err != nil {
		t.Error(err)
		return ""
	}
	_ = binary.Write(f, binary.LittleEndian, samples)
	_ = f.Close()
	out := filepath.Join(dir, name+".mkv")
	video := fmt.Sprintf("color=black:s=64x64:r=5:d=%.3f", float64(len(samples))/SampleRate)
	cmd := exec.Command(ffmpeg, "-v", "error", "-y", "-f", "s16le", "-ar", fmt.Sprint(SampleRate), "-ac", "1", "-i", raw,
		"-f", "lavfi", "-i", video, "-map", "1:v", "-map", "0:a", "-c:v", "mpeg4",
		"-c:a", codec, "-ar", "48000", "-ac", "2", out)
	if b, err := cmd.CombinedOutput(); err != nil {
		t.Errorf("ffmpeg: %v\n%s", err, b)
	}
	return out
}

func TestDetectorFindsIntrosAndCredits(t *testing.T) {
	t.Parallel()
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not installed")
	}
	ctx := context.Background()
	conn, err := db.Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := db.Migrate(ctx, conn); err != nil {
		t.Fatal(err)
	}
	q := db.New(conn)
	lib, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: "/tv"})
	show, _ := q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: lib.ID, TmdbID: 1, Title: "Show", Genres: "[]", Credits: "[]"})
	season, _ := q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: show.ID, Number: 1, Name: "Season 1", EpisodeCount: 3})

	dir := t.TempDir()
	intro, credits := tune(100, 35), tune(200, 40)
	introAt := []float64{0, 48.5, 20}
	codecs := []string{"aac", "ac3", "aac"}
	prober := &probe.Prober{}
	// Encoding is most of the test's time: the episodes are made at once.
	paths := make([]string, len(introAt))
	var wg sync.WaitGroup
	for i, at := range introAt {
		wg.Go(func() {
			paths[i] = encode(t, ffmpeg, dir, fmt.Sprintf("e%d", i+1), showEpisode(int64(i+1), 6*60, at, 40, intro, credits), codecs[i])
		})
	}
	wg.Wait()
	if t.Failed() {
		t.FailNow()
	}
	var ids, durations []int64
	for i, path := range paths {
		info, err := prober.Probe(ctx, path)
		if err != nil {
			t.Fatal(err)
		}
		// Credits are final only when they end near the end of the file,
		// so a file longer than its audio would fail for the fixture's sake.
		if abs(info.DurationMs-6*60_000) > 500 {
			t.Fatalf("episode %d probes as %d ms long, want the audio's 360000", i+1, info.DurationMs)
		}
		durations = append(durations, info.DurationMs)
		raw := mustJSON(t, info)
		f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: lib.ID, Path: path, Size: 1, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()),
			Parsed: "{}", Probe: &raw, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
		if err != nil {
			t.Fatal(err)
		}
		ep, _ := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: season.ID, ShowID: show.ID, Number: int64(i + 1), Title: "E"})
		_ = q.InsertEpisodeFile(ctx, db.InsertEpisodeFileParams{EpisodeID: ep.ID, MediaFileID: f.ID})
		ids = append(ids, f.ID)
	}

	d := NewDetector(q, conn, events.NewBus(), Decoder{FFmpeg: ffmpeg}, slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelWarn})))
	pending, _ := q.ListSeasonsToFingerprint(ctx, AlgoVersion)
	if len(pending) != 1 {
		t.Fatalf("pending seasons = %v", pending)
	}
	if err := d.RunOnce(ctx); err != nil {
		t.Fatal(err)
	}
	for i, id := range ids {
		rows, _ := q.ListFileSegments(ctx, id)
		got := map[string]db.MediaSegment{}
		for _, r := range rows {
			got[r.Kind] = r
		}
		in, ok := got["intro"]
		wantStart := int64(introAt[i] * 1000)
		if !ok || abs(in.StartMs-wantStart) > 1500 || in.EndMs == nil || abs(*in.EndMs-(wantStart+35_000)) > 2000 || in.Source != "fingerprint" {
			t.Errorf("episode %d intro = %s, want about %d-%d", i+1, describe(got, "intro"), wantStart, wantStart+35_000)
		}
		cr, ok := got["credits"]
		if !ok || abs(cr.StartMs-320_000) > 1500 || cr.Final != 1 {
			t.Errorf("episode %d credits = %s in a file probed as %d ms, want from about 320000, final", i+1, describe(got, "credits"), durations[i])
		}
	}
	if pending, _ := q.ListSeasonsToFingerprint(ctx, AlgoVersion); len(pending) != 0 {
		t.Errorf("still pending after a run: %v", pending)
	}
	// A second run uses the cached fingerprints: no decoding at all.
	d.dec.FFmpeg = "/nonexistent/ffmpeg"
	if err := d.Season(ctx, season.ID); err != nil {
		t.Fatal(err)
	}
	if rows, _ := q.ListFileSegments(ctx, ids[1]); len(rows) != 2 {
		t.Errorf("after a cached run, episode 2 has %d segments", len(rows))
	}
}

// When ffmpeg cannot run at all, the run stops and records nothing, so the
// seasons are looked at again rather than stored as having no segments.
func TestDetectorRecordsNothingWithoutFFmpeg(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	conn, err := db.Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := db.Migrate(ctx, conn); err != nil {
		t.Fatal(err)
	}
	q := db.New(conn)
	lib, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: "/tv"})
	show, _ := q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: lib.ID, TmdbID: 1, Title: "Show", Genres: "[]", Credits: "[]"})
	raw := mustJSON(t, probe.MediaInfo{DurationMs: 40 * 60_000, Audio: []probe.AudioStream{{Index: 1, Default: true}}})
	var files []int64
	for s := range int64(2) {
		season, _ := q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: show.ID, Number: s + 1, Name: "Season", EpisodeCount: 2})
		for e := range int64(2) {
			f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: lib.ID, Path: fmt.Sprintf("/tv/Show/S%dE%d.mkv", s+1, e+1), Size: 1,
				Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()), Parsed: "{}", Probe: &raw, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
			if err != nil {
				t.Fatal(err)
			}
			ep, _ := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: season.ID, ShowID: show.ID, Number: e + 1, Title: "E"})
			_ = q.InsertEpisodeFile(ctx, db.InsertEpisodeFileParams{EpisodeID: ep.ID, MediaFileID: f.ID})
			files = append(files, f.ID)
		}
	}

	d := NewDetector(q, conn, events.NewBus(), Decoder{FFmpeg: filepath.Join(t.TempDir(), "ffmpeg")}, slog.New(slog.DiscardHandler))
	if err := d.RunOnce(ctx); !errors.Is(err, ErrNoDecoder) {
		t.Fatalf("run without ffmpeg: %v, want ErrNoDecoder", err)
	}
	for _, id := range files {
		if _, err := q.GetSegmentAnalysis(ctx, db.GetSegmentAnalysisParams{MediaFileID: id, Source: "fingerprint"}); err == nil {
			t.Errorf("file %d recorded as analysed", id)
		}
	}
	if pending, _ := q.ListSeasonsToFingerprint(ctx, AlgoVersion); len(pending) != 2 {
		t.Errorf("pending seasons after the failed run = %v, want both", pending)
	}
}

// describe prints a file's stored segment of a kind for a failure message,
// with its end as a number where %+v would print a pointer.
func describe(got map[string]db.MediaSegment, kind string) string {
	s, ok := got[kind]
	if !ok {
		return "none"
	}
	end := "end of file"
	if s.EndMs != nil {
		end = fmt.Sprint(*s.EndMs)
	}
	return fmt.Sprintf("%d-%s ms (final %d, source %s, confidence %.2f)", s.StartMs, end, s.Final, s.Source, s.Confidence)
}
