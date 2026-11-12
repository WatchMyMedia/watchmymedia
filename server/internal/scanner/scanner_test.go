package scanner

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/probe"
)

type fakeProber struct{ n atomic.Int64 }

// calls is how many files were probed; workers probe concurrently.
func (f *fakeProber) calls() int { return int(f.n.Load()) }

func (f *fakeProber) Probe(_ context.Context, file string) (*probe.MediaInfo, error) {
	f.n.Add(1)
	if filepath.Base(file) == "corrupt.mkv" {
		return nil, errors.New("ffprobe: Invalid data found")
	}
	if filepath.Base(file) == "dv.mkv" {
		return &probe.MediaInfo{Container: "matroska", DurationMs: 5000, Extended: true,
			Video: []probe.VideoStream{{Codec: "hevc", Width: 3840, Height: 2160, HDR: "dolbyvision", DVProfile: 8, DVCompat: 1, DVLevel: 6, Detailed: true}}}, nil
	}
	return &probe.MediaInfo{Container: "matroska", DurationMs: 5000,
		Video: []probe.VideoStream{{Codec: "h264", Width: 1920, Height: 1080}},
		Audio: []probe.AudioStream{{Codec: "aac", Channels: 2}}}, nil
}

func setup(t *testing.T) (*Scanner, *db.Queries, *fakeProber, string, db.Library) {
	t.Helper()
	_, q := dbtest.Open(t)
	root := t.TempDir()
	lib, err := q.UpsertLibrary(context.Background(), db.UpsertLibraryParams{Type: "movie", Name: "Movies", Path: root})
	if err != nil {
		t.Fatal(err)
	}
	fp := &fakeProber{}
	s := New(q, fp, events.NewBus(), slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelWarn})),
		Options{Workers: 2, StableFor: 2 * time.Second})
	return s, q, fp, root, lib
}

// writeOld creates a file whose mtime is well in the past so it counts as stable.
func writeOld(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	old := time.Now().Add(-time.Hour)
	if err := os.Chtimes(path, old, old); err != nil {
		t.Fatal(err)
	}
}

func TestScanLifecycle(t *testing.T) {
	s, q, fp, root, lib := setup(t)
	ctx := context.Background()

	writeOld(t, filepath.Join(root, "The Matrix (1999)", "The Matrix (1999).mkv"), "a")
	writeOld(t, filepath.Join(root, "Heat (1995).mp4"), "bb")
	writeOld(t, filepath.Join(root, "corrupt.mkv"), "c")
	writeOld(t, filepath.Join(root, "Extras", "making-of.mkv"), "d")         // ignored dir
	writeOld(t, filepath.Join(root, "The Matrix (1999)", "poster.jpg"), "e") // not video
	writeOld(t, filepath.Join(root, "Heat (1995)-trailer.mp4"), "f")         // a trailer: read, but not library content

	sum, err := s.Scan(ctx, lib)
	if err != nil {
		t.Fatal(err)
	}
	if sum.Added != 4 || sum.Failed != 1 || sum.Missing != 0 || sum.Skipped != 0 {
		t.Fatalf("first scan summary = %+v", sum)
	}
	if fp.calls() != 4 {
		t.Errorf("probe calls = %d, want 4", fp.calls())
	}
	rows, _ := q.ListMediaFiles(ctx, db.ListMediaFilesParams{LibraryID: lib.ID, Limit: 100})
	if len(rows) != 3 {
		t.Fatalf("rows = %d", len(rows))
	}
	matrix, err := q.GetMediaFileByPath(ctx, filepath.Join(root, "The Matrix (1999)", "The Matrix (1999).mkv"))
	if err != nil {
		t.Fatal(err)
	}
	if *matrix.VideoCodec != "h264" || *matrix.Width != 1920 || *matrix.Container != "matroska" || matrix.ProbeError != nil {
		t.Errorf("matrix row = %+v", matrix)
	}
	if matrix.Parsed == "" || matrix.Parsed == "{}" {
		t.Errorf("parsed not stored: %q", matrix.Parsed)
	}
	corrupt, _ := q.GetMediaFileByPath(ctx, filepath.Join(root, "corrupt.mkv"))
	if corrupt.ProbeError == nil || corrupt.Probe != nil {
		t.Errorf("corrupt row = %+v", corrupt)
	}

	// Second scan: nothing changed, nothing re-probed.
	sum, _ = s.Scan(ctx, lib)
	if sum.Unchanged != 4 || sum.Added+sum.Updated+sum.Missing != 0 || fp.calls() != 4 {
		t.Fatalf("second scan summary = %+v (probe calls %d)", sum, fp.calls())
	}

	// A file grows: re-probed and updated. Another disappears: missing.
	writeOld(t, filepath.Join(root, "Heat (1995).mp4"), "bbbb")
	if err := os.Remove(filepath.Join(root, "corrupt.mkv")); err != nil {
		t.Fatal(err)
	}
	sum, _ = s.Scan(ctx, lib)
	if sum.Updated != 1 || sum.Missing != 1 || sum.Unchanged != 2 {
		t.Fatalf("third scan summary = %+v", sum)
	}
	corrupt, _ = q.GetMediaFileByPath(ctx, filepath.Join(root, "corrupt.mkv"))
	if corrupt.Status != "missing" {
		t.Errorf("removed file status = %q", corrupt.Status)
	}

	// It comes back: present again, same row.
	writeOld(t, filepath.Join(root, "corrupt.mkv"), "c")
	sum, _ = s.Scan(ctx, lib)
	back, _ := q.GetMediaFileByPath(ctx, filepath.Join(root, "corrupt.mkv"))
	if back.Status != "present" || back.ID != corrupt.ID || sum.Updated != 1 {
		t.Errorf("restored row = %+v summary = %+v", back, sum)
	}

	lib2, _ := q.GetLibrary(ctx, lib.ID)
	if lib2.LastScannedAt == nil {
		t.Error("last_scanned_at not set")
	}
}

func TestScanSkipsFilesStillChanging(t *testing.T) {
	s, _, fp, root, lib := setup(t)
	path := filepath.Join(root, "Fresh (2020).mkv")
	if err := os.WriteFile(path, []byte("x"), 0o600); err != nil { // mtime = now
		t.Fatal(err)
	}
	sum, err := s.Scan(context.Background(), lib)
	if err != nil {
		t.Fatal(err)
	}
	if sum.Skipped != 1 || sum.Added != 0 || fp.calls() != 0 {
		t.Errorf("summary = %+v probe calls = %d", sum, fp.calls())
	}
}

func TestScanUnavailableRootDoesNotMarkMissing(t *testing.T) {
	s, q, _, root, lib := setup(t)
	ctx := context.Background()
	writeOld(t, filepath.Join(root, "Heat (1995).mp4"), "bb")
	if _, err := s.Scan(ctx, lib); err != nil {
		t.Fatal(err)
	}
	gone := db.Library{ID: lib.ID, Type: lib.Type, Path: filepath.Join(root, "unmounted")}
	if _, err := s.Scan(ctx, gone); err == nil {
		t.Fatal("expected error for unavailable root")
	}
	row, _ := q.GetMediaFileByPath(ctx, filepath.Join(root, "Heat (1995).mp4"))
	if row.Status != "present" {
		t.Errorf("status = %q; an unmounted drive must not mark files missing", row.Status)
	}
}

// Requests while a scan runs merge into one more scan after it.
func TestRequestCoalesces(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, _, fp, root, lib := setup(t)
		writeOld(t, filepath.Join(root, "Heat (1995).mp4"), "bb")
		ch, cancel := s.bus.Subscribe()
		defer cancel()

		for range 5 {
			s.Request(lib.ID)
		}
		// Every scan has finished and nothing is left to run.
		synctest.Wait()
		finished := 0
		for len(ch) > 0 {
			if (<-ch).Type == "scan.finished" {
				finished++
			}
		}
		if finished != 2 {
			t.Errorf("%d scans, want 2", finished)
		}
		s.mu.Lock()
		running := s.states[lib.ID].running
		s.mu.Unlock()
		if running {
			t.Error("scan still running")
		}
		if fp.calls() != 1 {
			t.Errorf("probe calls = %d, want 1 (file unchanged on the second scan)", fp.calls())
		}
	})
}

// A file's added date is its modified date when first found, and stays put
// when the file changes later; a date in the future counts as the scan time.
func TestScanAddedAtFromFileDate(t *testing.T) {
	s, q, _, root, lib := setup(t)
	ctx := context.Background()
	at := func(path string, when time.Time) {
		if err := os.Chtimes(path, when, when); err != nil {
			t.Fatal(err)
		}
	}
	old, newer := time.Now().Add(-72*time.Hour), time.Now().Add(-time.Hour)
	heat, matrix, future := filepath.Join(root, "Heat (1995).mkv"), filepath.Join(root, "The Matrix (1999).mkv"), filepath.Join(root, "Alien (1979).mkv")
	writeOld(t, heat, "a")
	writeOld(t, matrix, "b")
	writeOld(t, future, "c")
	at(heat, old)
	at(matrix, newer)
	at(future, time.Now().Add(240*time.Hour))
	before := time.Now()
	if _, err := s.Scan(ctx, lib); err != nil {
		t.Fatal(err)
	}
	added := func(path string) string {
		f, err := q.GetMediaFileByPath(ctx, path)
		if err != nil {
			t.Fatal(err)
		}
		return f.AddedAt
	}
	if got := added(heat); got != db.FormatTime(old) {
		t.Errorf("heat added %s, want %s", got, db.FormatTime(old))
	}
	if got := added(matrix); got != db.FormatTime(newer) {
		t.Errorf("matrix added %s, want %s", got, db.FormatTime(newer))
	}
	if got := db.ParseTime(added(future)); got.Before(before.Add(-time.Second)) || got.After(time.Now()) {
		t.Errorf("future-dated file added %s, want the scan time", got)
	}

	// Replacing the file changes its mtime but not when it was added.
	if err := os.WriteFile(heat, []byte("changed"), 0o600); err != nil {
		t.Fatal(err)
	}
	at(heat, time.Now().Add(-time.Minute))
	if _, err := s.Scan(ctx, lib); err != nil {
		t.Fatal(err)
	}
	if got := added(heat); got != db.FormatTime(old) {
		t.Errorf("after change heat added %s, want %s", got, db.FormatTime(old))
	}
}

// Files scanned before the parser learned a naming style (date-named daily
// shows) are read again on the next scan and sent back to matching, without
// probing them again.
func TestScanRereadsNamesOfUnchangedFiles(t *testing.T) {
	s, q, fp, _, _ := setup(t)
	ctx := context.Background()
	root := t.TempDir()
	tv, err := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: root})
	if err != nil {
		t.Fatal(err)
	}
	dated := filepath.Join(root, "The Daily Show", "The Daily Show - 2024-10-28 - Guest.mkv")
	matched := filepath.Join(root, "The Daily Show", "The Daily Show - 2024-10-29 - Guest.mkv")
	writeOld(t, dated, "a")
	writeOld(t, matched, "b")
	if _, err := s.Scan(ctx, tv); err != nil {
		t.Fatal(err)
	}
	// Make both look as an older parser left them: no air date, and one of
	// them already matched by hand.
	stale := `{"kind":"unknown","title":"The Daily Show"}`
	for _, p := range []string{dated, matched} {
		f, _ := q.GetMediaFileByPath(ctx, p)
		if err := q.UpdateMediaFileParsed(ctx, db.UpdateMediaFileParsedParams{Parsed: stale, ID: f.ID}); err != nil {
			t.Fatal(err)
		}
	}
	f, _ := q.GetMediaFileByPath(ctx, dated)
	msg := "could not read a season and episode number from the file name"
	_ = q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "unmatched", MatchError: &msg, ID: f.ID})
	m, _ := q.GetMediaFileByPath(ctx, matched)
	_ = q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MatchLocked: 1, ID: m.ID})
	probes := fp.calls()

	sum, err := s.Scan(ctx, tv)
	if err != nil {
		t.Fatal(err)
	}
	if sum.Unchanged != 2 || sum.Reparsed != 2 || fp.calls() != probes {
		t.Errorf("summary = %+v, probes %d -> %d", sum, probes, fp.calls())
	}
	f, _ = q.GetMediaFileByPath(ctx, dated)
	if f.MatchStatus != "pending" || f.MatchError != nil || !strings.Contains(f.Parsed, `"airDate":"2024-10-28"`) {
		t.Errorf("dated file = %s %v %s", f.MatchStatus, f.MatchError, f.Parsed)
	}
	m, _ = q.GetMediaFileByPath(ctx, matched)
	if m.MatchStatus != "matched" || !strings.Contains(m.Parsed, `"airDate":"2024-10-29"`) {
		t.Errorf("matched file = %s %s", m.MatchStatus, m.Parsed)
	}
	// Nothing changed since: nothing is read differently.
	if sum, _ := s.Scan(ctx, tv); sum.Reparsed != 0 {
		t.Errorf("third scan reparsed %d", sum.Reparsed)
	}
}

func TestDolbyVisionProfile(t *testing.T) {
	s, q, fp, root, lib := setup(t)
	ctx := context.Background()
	dv := filepath.Join(root, "dv.mkv")
	writeOld(t, dv, "a")
	writeOld(t, filepath.Join(root, "sdr.mkv"), "b")
	if _, err := s.Scan(ctx, lib); err != nil {
		t.Fatal(err)
	}
	row, _ := q.GetMediaFileByPath(ctx, dv)
	if row.Hdr != "dolbyvision" || row.DvProfile == nil || *row.DvProfile != 8 {
		t.Fatalf("dv row: hdr %q, profile %v", row.Hdr, row.DvProfile)
	}
	sdr, _ := q.GetMediaFileByPath(ctx, filepath.Join(root, "sdr.mkv"))
	if sdr.DvProfile != nil {
		t.Errorf("SDR file has a profile: %v", *sdr.DvProfile)
	}

	// A Dolby Vision file probed before profiles were kept is probed again
	// once, though it has not changed; other files are left alone.
	if _, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: row.LibraryID, Path: row.Path, Size: row.Size, Mtime: row.Mtime,
		Parsed: row.Parsed, Probe: row.Probe, Container: row.Container, VideoCodec: row.VideoCodec, Width: row.Width, Height: row.Height,
		DurationMs: row.DurationMs, LastSeenAt: row.LastSeenAt, SeenInScan: row.SeenInScan, Hdr: row.Hdr, AddedAt: row.AddedAt}); err != nil {
		t.Fatal(err)
	}
	calls := fp.calls()
	sum, _ := s.Scan(ctx, lib)
	if fp.calls() != calls+1 || sum.Updated != 1 || sum.Unchanged != 1 {
		t.Fatalf("rescan: %d probes, summary %+v", fp.calls()-calls, sum)
	}
	row, _ = q.GetMediaFileByPath(ctx, dv)
	if row.DvProfile == nil || *row.DvProfile != 8 {
		t.Errorf("profile after rescan = %v", row.DvProfile)
	}
	if sum, _ = s.Scan(ctx, lib); sum.Unchanged != 2 || fp.calls() != calls+1 {
		t.Errorf("third scan: summary %+v, probes %d", sum, fp.calls()-calls)
	}

	// So is one whose stored probe has the profile but not the record's
	// base layer and level, as probes stored before they were kept.
	for _, old := range []string{
		`{"container":"matroska","durationMs":5000,"video":[{"index":0,"codec":"hevc","width":3840,"height":2160,"hdr":"dolbyvision","dvProfile":8,"default":false}],"audio":[],"subtitles":[]}`,
		`{"container":"matroska","durationMs":5000,"video":[{"index":0,"codec":"hevc","width":3840,"height":2160,"hdr":"dolbyvision","dvProfile":8,"default":false}],"audio":[],"subtitles":[],"extra":{"startUs":0,"chapters":[]}}`,
	} {
		if _, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: row.LibraryID, Path: row.Path, Size: row.Size, Mtime: row.Mtime,
			Parsed: row.Parsed, Probe: &old, Container: row.Container, VideoCodec: row.VideoCodec, Width: row.Width, Height: row.Height,
			DurationMs: row.DurationMs, LastSeenAt: row.LastSeenAt, SeenInScan: row.SeenInScan, Hdr: row.Hdr, AddedAt: row.AddedAt, DvProfile: row.DvProfile}); err != nil {
			t.Fatal(err)
		}
		calls = fp.calls()
		if sum, _ = s.Scan(ctx, lib); fp.calls() != calls+1 || sum.Updated != 1 || sum.Unchanged != 1 {
			t.Fatalf("rescan of %s: %d probes, summary %+v", old, fp.calls()-calls, sum)
		}
		row, _ = q.GetMediaFileByPath(ctx, dv)
		info, err := probe.Parse(*row.Probe)
		if err != nil || !info.Video[0].Detailed || info.Video[0].DVCompat != 1 || info.Video[0].DVLevel != 6 {
			t.Errorf("probe after rescan = %+v, %v", info, err)
		}
		if sum, _ = s.Scan(ctx, lib); sum.Unchanged != 2 || fp.calls() != calls+1 {
			t.Errorf("scan after: summary %+v, probes %d", sum, fp.calls()-calls)
		}
	}
}

// A scan that can't reach the library's folder leaves why on the library,
// and the next full scan that finishes clears it.
func TestScanKeepsLastError(t *testing.T) {
	s, q, _, root, lib := setup(t)
	ctx := context.Background()
	gone := filepath.Join(root, "unmounted")
	lib.Path = gone
	if _, err := s.Scan(ctx, lib); err == nil {
		t.Fatal("scan of a missing folder succeeded")
	}
	got, _ := q.GetLibrary(ctx, lib.ID)
	if got.LastScanError == nil || !strings.Contains(*got.LastScanError, "not accessible") || got.LastScanErrorAt == nil {
		t.Fatalf("after a failed scan: error %v at %v", got.LastScanError, got.LastScanErrorAt)
	}

	writeOld(t, filepath.Join(root, "Heat (1995).mp4"), "a")
	lib.Path = root
	if _, err := s.Scan(ctx, lib); err != nil {
		t.Fatal(err)
	}
	got, _ = q.GetLibrary(ctx, lib.ID)
	if got.LastScanError != nil || got.LastScanErrorAt != nil {
		t.Errorf("after a good scan: error %v at %v", got.LastScanError, got.LastScanErrorAt)
	}
}

// Folders a full scan can't read, as when the files belong to another user,
// are named on the library.
func TestScanNamesUnreadableFolders(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows folders have no permission bits to take away")
	}
	if os.Geteuid() == 0 {
		t.Skip("root reads every folder")
	}
	s, q, _, root, lib := setup(t)
	ctx := context.Background()
	writeOld(t, filepath.Join(root, "Heat (1995).mp4"), "a")
	locked := filepath.Join(root, "Cold Orchard (2021)")
	writeOld(t, filepath.Join(locked, "Cold Orchard (2021).mkv"), "b")
	if err := os.Chmod(locked, 0); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chmod(locked, 0o755) })
	if _, err := s.Scan(ctx, lib); err != nil {
		t.Fatal(err)
	}
	got, _ := q.GetLibrary(ctx, lib.ID)
	if got.LastScanError == nil || *got.LastScanError != "couldn't read the folder Cold Orchard (2021)" {
		t.Errorf("error %v", got.LastScanError)
	}
}
