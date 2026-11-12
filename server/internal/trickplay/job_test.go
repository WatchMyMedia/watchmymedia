package trickplay

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/jpeg"
	"log/slog"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/events"
)

// smallLayout keeps the fake sheets tiny: 2 × 2 tiles of 32 × 18, one
// every 10 s, so a 170 s file has 17 tiles on 5 sheets.
var smallLayout = Layout{IntervalMs: 10_000, Width: 32, Columns: 2, Rows: 2, Quality: 5}

// fakeSheets stands in for ffmpeg: it writes a blank sheet of the layout's
// size and records which sheets of which files it made, and when.
type fakeSheets struct {
	mu     sync.Mutex
	made   []string // "<file>:<sheet>"
	tones  []string // how each was tone mapped
	at     []time.Time
	fail   map[string]error // by "<file>:<sheet>"
	after  func(s sheetJob) // runs after each sheet
	layout Layout
}

func (f *fakeSheets) make(_ context.Context, s sheetJob) error {
	key := fmt.Sprintf("%s:%d", filepath.Base(s.path), s.sheet)
	f.mu.Lock()
	err := f.fail[key]
	f.mu.Unlock()
	if err != nil {
		return err
	}
	img := image.NewGray(image.Rect(0, 0, f.layout.Width*f.layout.Columns, 18*f.layout.Rows))
	out, err := os.Create(s.out)
	if err != nil {
		return err
	}
	if err := jpeg.Encode(out, img, nil); err != nil {
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	f.mu.Lock()
	f.made = append(f.made, key)
	f.tones = append(f.tones, s.tone)
	f.at = append(f.at, time.Now())
	f.mu.Unlock()
	if f.after != nil {
		f.after(s)
	}
	return nil
}

func (f *fakeSheets) list() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return slices.Clone(f.made)
}

type testLib struct {
	t    *testing.T
	conn *sql.DB
	q    *db.Queries
	lib  int64
	g    *Generator
	fake *fakeSheets
}

// newTestLib is a database with a library and a generator over it whose
// sheets are fakes; its disk always has room.
func newTestLib(t *testing.T) *testLib {
	t.Helper()
	conn, q := dbtest.Open(t)
	lib, err := q.UpsertLibrary(t.Context(), db.UpsertLibraryParams{Type: "movie", Name: "Movies", Path: "/m"})
	if err != nil {
		t.Fatal(err)
	}
	g := New(q, conn, events.NewBus(), slog.New(slog.DiscardHandler), Options{Dir: filepath.Join(t.TempDir(), "trickplay"), Enabled: true, Layout: smallLayout})
	fake := &fakeSheets{fail: map[string]error{}, layout: smallLayout}
	g.makeSheet = fake.make
	g.free = func(string) (uint64, error) { return 100 << 30, nil }
	return &testLib{t: t, conn: conn, q: q, lib: lib.ID, g: g, fake: fake}
}

type fileOpt func(*db.UpsertMediaFileParams)

func withHDR(hdr string, dv int64) fileOpt {
	return func(p *db.UpsertMediaFileParams) {
		p.Hdr = hdr
		if dv > 0 {
			p.DvProfile = &dv
		}
	}
}

func withSize(n int64) fileOpt { return func(p *db.UpsertMediaFileParams) { p.Size = n } }

func noVideo() fileOpt { return func(p *db.UpsertMediaFileParams) { p.VideoCodec = nil } }

// add stores a present file of durationMs, first seen at seen.
func (l *testLib) add(name string, durationMs int64, seen time.Time, opts ...fileOpt) db.MediaFile {
	l.t.Helper()
	h264 := "h264"
	p := db.UpsertMediaFileParams{LibraryID: l.lib, Path: "/m/" + name, Size: 1000, Mtime: "2026-10-01T00:00:00Z", AddedAt: db.FormatTime(seen),
		Parsed: "{}", VideoCodec: &h264, DurationMs: &durationMs, LastSeenAt: db.FormatTime(seen), SeenInScan: 1}
	for _, o := range opts {
		o(&p)
	}
	f, err := l.q.UpsertMediaFile(l.t.Context(), p)
	if err != nil {
		l.t.Fatal(err)
	}
	if _, err := l.conn.ExecContext(l.t.Context(), `UPDATE media_files SET first_seen_at = ? WHERE id = ?`, db.FormatTime(seen), f.ID); err != nil {
		l.t.Fatal(err)
	}
	return f
}

func (l *testLib) row(id int64) (db.Trickplay, bool) {
	l.t.Helper()
	r, err := l.q.GetTrickplay(l.t.Context(), id)
	if errors.Is(err, sql.ErrNoRows) {
		return r, false
	}
	if err != nil {
		l.t.Fatal(err)
	}
	return r, true
}

// The work list holds present files of a minute or more with video, newest
// first, until their thumbnails are made or fail for this version.
func TestWorkList(t *testing.T) {
	t.Parallel()
	l := newTestLib(t)
	ctx := t.Context()
	base := time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)
	old := l.add("old.mkv", 120_000, base)
	newer := l.add("newer.mkv", 120_000, base.Add(time.Hour))
	newest := l.add("newest.mkv", 60_000, base.Add(2*time.Hour))
	l.add("short.mkv", 59_999, base.Add(3*time.Hour))
	l.add("audio.mkv", 120_000, base.Add(4*time.Hour), noVideo())
	gone := l.add("gone.mkv", 120_000, base.Add(5*time.Hour))
	if _, err := l.q.MarkMediaFilesMissing(ctx, []int64{gone.ID}); err != nil {
		t.Fatal(err)
	}
	list := func() []int64 {
		rows, err := l.q.ListFilesNeedingTrickplay(ctx, db.ListFilesNeedingTrickplayParams{AlgoVersion: AlgoVersion, BeforeSeen: "\uffff", BeforeID: 1 << 62, Page: 50})
		if err != nil {
			t.Fatal(err)
		}
		var ids []int64
		for _, r := range rows {
			ids = append(ids, r.ID)
		}
		return ids
	}
	if got, want := list(), []int64{newest.ID, newer.ID, old.ID}; !slices.Equal(got, want) {
		t.Fatalf("work list %v, want newest first %v", got, want)
	}
	rec := func(f db.MediaFile, status string, algo int64) {
		if err := l.q.UpsertTrickplay(ctx, db.UpsertTrickplayParams{MediaFileID: f.ID, Version: Version(f.Size, f.Mtime, ToneSDR), AlgoVersion: algo,
			FileSize: f.Size, FileMtime: f.Mtime, Status: status}); err != nil {
			t.Fatal(err)
		}
	}
	rec(newest, "done", AlgoVersion)
	rec(newer, "failed", AlgoVersion)
	rec(old, "done", AlgoVersion-1)
	if got, want := list(), []int64{old.ID}; !slices.Equal(got, want) {
		t.Fatalf("after done, failed and stale rows: %v, want only the stale one %v", got, want)
	}
	// A changed file is made again, failed or not.
	if _, err := l.q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: l.lib, Path: newer.Path, Size: 2000, Mtime: newer.Mtime, Parsed: "{}",
		VideoCodec: newer.VideoCodec, DurationMs: newer.DurationMs, LastSeenAt: newer.LastSeenAt, AddedAt: newer.AddedAt, SeenInScan: 2}); err != nil {
		t.Fatal(err)
	}
	if got, want := list(), []int64{newer.ID, old.ID}; !slices.Equal(got, want) {
		t.Errorf("after the failed file changed: %v, want %v", got, want)
	}
	// The status counts the same files.
	s, err := l.g.Status(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if s.Files != 3 || s.Done != 1 || s.Failed != 0 || s.Pending != 2 || !s.Enabled {
		t.Errorf("status %+v, want 3 files, 1 done, 2 pending", s)
	}
}

// A run walks the work list a page at a time: more files than a page all
// get thumbnails, in order, newest first.
func TestRunPagesThroughEveryFile(t *testing.T) {
	t.Parallel()
	l := newTestLib(t)
	base := time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)
	n := pageSize + 7
	for i := range n {
		l.add(fmt.Sprintf("f%03d.mkv", i), 60_000, base.Add(time.Duration(i)*time.Minute))
	}
	if err := l.g.RunOnce(t.Context()); err != nil {
		t.Fatal(err)
	}
	// Newest first, each file's two sheets in order.
	var want []string
	for i := n - 1; i >= 0; i-- {
		want = append(want, fmt.Sprintf("f%03d.mkv:0", i), fmt.Sprintf("f%03d.mkv:1", i))
	}
	got := l.fake.list()
	if !slices.Equal(got, want) {
		t.Fatalf("made %d sheets, first %v; want %d, first %v", len(got), got[:min(4, len(got))], len(want), want[:4])
	}
	s, _ := l.g.Status(t.Context())
	if s.Done != int64(n) || s.Pending != 0 || s.Bytes == 0 || s.Running {
		t.Errorf("status after the run %+v", s)
	}
}

// A file's thumbnails are made into a temporary folder, then moved into
// place with a manifest and recorded; a run stopped after one sheet makes
// only the rest when it starts again.
func TestRunResumesAtTheNextSheet(t *testing.T) {
	t.Parallel()
	l := newTestLib(t)
	f := l.add("film.mkv", 170_000, time.Now())
	ctx, cancel := context.WithCancel(t.Context())
	l.fake.after = func(sheetJob) { cancel() }
	if err := l.g.RunOnce(ctx); !errors.Is(err, context.Canceled) {
		t.Fatalf("stopped run -> %v", err)
	}
	if _, ok := l.row(f.ID); ok {
		t.Fatal("a stopped file was recorded")
	}
	version := Version(f.Size, f.Mtime, ToneSDR)
	if _, err := os.Stat(filepath.Join(tmpDir(l.g.dir, f.ID, version), SheetName(0))); err != nil {
		t.Fatalf("the first sheet was not kept: %v", err)
	}
	l.fake.after = nil
	l.fake.made = nil
	if err := l.g.RunOnce(t.Context()); err != nil {
		t.Fatal(err)
	}
	if got := l.fake.list(); !slices.Equal(got, []string{"film.mkv:1", "film.mkv:2", "film.mkv:3", "film.mkv:4"}) {
		t.Fatalf("the second run made %v, want sheets 1 to 4", got)
	}
	r, ok := l.row(f.ID)
	if !ok || r.Status != "done" || r.Version != version || r.Tiles != 17 || r.Sheets != 5 || r.Width != 32 || r.Height != 18 ||
		r.Columns != 2 || r.Rows != 2 || r.IntervalMs != 10_000 || r.Bytes == 0 {
		t.Fatalf("row %+v", r)
	}
	dir := FileDir(l.g.dir, f.ID, version)
	for s := range 5 {
		if _, err := os.Stat(filepath.Join(dir, SheetName(s))); err != nil {
			t.Errorf("sheet %d: %v", s, err)
		}
	}
	var m map[string]any
	if b, err := os.ReadFile(filepath.Join(dir, manifestName)); err != nil || json.Unmarshal(b, &m) != nil || m["count"] != float64(17) || m["sheets"] != float64(5) || m["url"] != nil {
		t.Errorf("manifest %s (%v)", m, err)
	}
	if _, err := os.Stat(tmpDir(l.g.dir, f.ID, version)); !os.IsNotExist(err) {
		t.Errorf("the temporary folder is still there: %v", err)
	}
	if got, ok := l.g.Done(t.Context(), f.ID, f.Size, f.Mtime); !ok || got.Version != version {
		t.Errorf("Done = %+v %v", got, ok)
	}
	if p, ok := l.g.SheetPath(t.Context(), f.ID, version, 4); !ok || p != filepath.Join(dir, "004.jpg") {
		t.Errorf("SheetPath = %s %v", p, ok)
	}
	for _, c := range []struct {
		version string
		sheet   int
	}{{version, 5}, {version, -1}, {Version(1, "x", ToneSDR), 0}, {"../../..", 0}} {
		if _, ok := l.g.SheetPath(t.Context(), f.ID, c.version, c.sheet); ok {
			t.Errorf("SheetPath(%s, %d) found a sheet", c.version, c.sheet)
		}
	}
}

// A file ffmpeg cannot read is recorded as failed and not tried again
// until it changes; ffmpeg that cannot run at all stops the run and records
// nothing.
func TestFailures(t *testing.T) {
	t.Parallel()
	l := newTestLib(t)
	ctx := t.Context()
	broken := l.add("broken.mkv", 90_000, time.Now())
	good := l.add("good.mkv", 90_000, time.Now().Add(-time.Hour))
	l.fake.fail["broken.mkv:1"] = errors.New("ffmpeg: Invalid data found when processing input")
	if err := l.g.RunOnce(ctx); err != nil {
		t.Fatal(err)
	}
	if r, _ := l.row(broken.ID); r.Status != "failed" || !strings.Contains(r.Error, "Invalid data") {
		t.Errorf("broken file: %+v", r)
	}
	if s, _ := l.g.Status(ctx); s.Current != nil || s.Running {
		t.Errorf("status after the run still names a file: %+v", s)
	}
	if _, err := os.Stat(tmpDir(l.g.dir, broken.ID, Version(broken.Size, broken.Mtime, ToneSDR))); !os.IsNotExist(err) {
		t.Errorf("a failed file's sheets were kept: %v", err)
	}
	if r, _ := l.row(good.ID); r.Status != "done" {
		t.Errorf("the file after the broken one: %+v", r)
	}
	s, _ := l.g.Status(ctx)
	if s.Files != 2 || s.Done != 1 || s.Failed != 1 || s.Pending != 0 {
		t.Errorf("status %+v", s)
	}
	// Not tried again while the file is the same…
	l.fake.made = nil
	delete(l.fake.fail, "broken.mkv:1")
	if err := l.g.RunOnce(ctx); err != nil || len(l.fake.list()) != 0 {
		t.Fatalf("second run made %v (%v)", l.fake.list(), err)
	}
	// …but once it changes.
	l.add("broken.mkv", 90_000, time.Now(), withSize(5000))
	if err := l.g.RunOnce(ctx); err != nil {
		t.Fatal(err)
	}
	if r, _ := l.row(broken.ID); r.Status != "done" || r.FileSize != 5000 {
		t.Errorf("changed file: %+v", r)
	}

	// Without ffmpeg nothing is recorded, and the file waits for a run
	// that can make it.
	l2 := newTestLib(t)
	l2.g.ffmpeg = filepath.Join(t.TempDir(), "no-ffmpeg")
	l2.g.makeSheet = l2.g.ffmpegSheet
	f := l2.add("film.mkv", 90_000, time.Now())
	if err := l2.g.RunOnce(ctx); !errors.Is(err, ErrNoDecoder) {
		t.Fatalf("run without ffmpeg -> %v, want ErrNoDecoder", err)
	}
	if _, ok := l2.row(f.ID); ok {
		t.Error("recorded a file ffmpeg never ran on")
	}
}

// How a file's HDR is mapped follows what ffmpeg has, as transcodes do.
func TestToneFor(t *testing.T) {
	t.Parallel()
	five, seven, eight := int64(5), int64(7), int64(8)
	none, cpu, all := ToneMapping{}, ToneMapping{CPU: true}, ToneMapping{CPU: true, Dovi: true}
	for _, c := range []struct {
		name string
		hdr  string
		dv   *int64
		tm   ToneMapping
		want string
	}{
		{"sdr", "", nil, all, ToneSDR},
		{"hdr10", "hdr10", nil, all, ToneZscale},
		{"hlg", "hlg", nil, cpu, ToneZscale},
		{"hdr10 without zscale", "hdr10", nil, none, TonePlain},
		{"dv 5 with tonemapx", "dolbyvision", &five, all, ToneTonemapx},
		{"dv 5 with zscale only", "dolbyvision", &five, cpu, ToneDVBase},
		{"dv 5 with neither", "dolbyvision", &five, none, TonePlain},
		{"dv of no known profile with zscale only", "dolbyvision", nil, cpu, ToneDVBase},
		{"dv 8 with tonemapx", "dolbyvision", &eight, all, ToneTonemapx},
		{"dv 8 with zscale only", "dolbyvision", &eight, cpu, ToneZscale},
		{"dv 7 with zscale only", "dolbyvision", &seven, cpu, ToneZscale},
	} {
		if got := toneFor(c.hdr, c.dv, c.tm); got != c.want {
			t.Errorf("%s: %q, want %q", c.name, got, c.want)
		}
	}
}

// Tiles made with less tone mapping than ffmpeg later has are made again,
// under a new version (sheets are cached for good): HDR made without zscale
// once there is zscale, and Dolby Vision 5 made from its base layer once
// there is tonemapx. Until then the status counts them as still to do.
func TestBetterToneMappingIsMadeAgain(t *testing.T) {
	t.Parallel()
	l := newTestLib(t)
	ctx := t.Context()
	var tm ToneMapping
	l.g.toneMapping = func() ToneMapping { return tm }
	hdr := l.add("hdr10.mkv", 60_000, time.Now(), withHDR("hdr10", 0))
	dv5 := l.add("dv5.mkv", 60_000, time.Now().Add(-time.Hour), withHDR("dolbyvision", 5))
	sdr := l.add("sdr.mkv", 60_000, time.Now().Add(-2*time.Hour))
	tones := func() map[int64]string {
		out := map[int64]string{}
		for _, f := range []db.MediaFile{hdr, dv5, sdr} {
			r, ok := l.row(f.ID)
			if !ok || r.Status != "done" || r.Version != Version(f.Size, f.Mtime, r.ToneMap) {
				t.Fatalf("%s: %+v", f.Path, r)
			}
			if _, err := os.Stat(FileDir(l.g.dir, f.ID, r.Version)); err != nil {
				t.Errorf("%s: %v", f.Path, err)
			}
			out[f.ID] = r.ToneMap
		}
		return out
	}
	run := func() []string {
		t.Helper()
		l.fake.made = nil
		if err := l.g.RunOnce(ctx); err != nil {
			t.Fatal(err)
		}
		return l.fake.list()
	}
	pending := func() int64 {
		s, err := l.g.Status(ctx)
		if err != nil {
			t.Fatal(err)
		}
		return s.Pending
	}

	run()
	if got := tones(); got[hdr.ID] != TonePlain || got[dv5.ID] != TonePlain || got[sdr.ID] != ToneSDR {
		t.Fatalf("without zscale or tonemapx: %v", got)
	}
	plainDV := Version(dv5.Size, dv5.Mtime, TonePlain)

	tm = ToneMapping{CPU: true}
	if n := pending(); n != 2 {
		t.Errorf("with zscale, %d files still to do, want the 2 HDR ones", n)
	}
	if got := run(); !slices.Equal(got, []string{"hdr10.mkv:0", "hdr10.mkv:1", "dv5.mkv:0", "dv5.mkv:1"}) {
		t.Errorf("with zscale the run made %v, want the HDR files again", got)
	}
	if got := tones(); got[hdr.ID] != ToneZscale || got[dv5.ID] != ToneDVBase {
		t.Fatalf("with zscale: %v", got)
	}
	if _, err := os.Stat(FileDir(l.g.dir, dv5.ID, plainDV)); !os.IsNotExist(err) {
		t.Errorf("the washed-out version's folder is still there: %v", err)
	}

	tm = ToneMapping{CPU: true, Dovi: true}
	if n := pending(); n != 1 {
		t.Errorf("with tonemapx, %d files still to do, want Dolby Vision 5", n)
	}
	if got := run(); !slices.Equal(got, []string{"dv5.mkv:0", "dv5.mkv:1"}) || l.fake.tones[len(l.fake.tones)-1] != ToneTonemapx {
		t.Errorf("with tonemapx the run made %v (%v), want Dolby Vision 5 again through it", got, l.fake.tones)
	}
	if got := tones(); got[hdr.ID] != ToneZscale || got[dv5.ID] != ToneTonemapx {
		t.Fatalf("with tonemapx: %v", got)
	}
	if got := run(); len(got) != 0 || pending() != 0 {
		t.Errorf("nothing left to do, but the run made %v", got)
	}
}

// When a file changes, its new thumbnails replace the old: a new version
// with its own folder and URL, and the old folder goes. Deleting the file's
// row deletes its thumbnails' row, and the sweep its folder.
func TestNewVersionAndCascade(t *testing.T) {
	t.Parallel()
	l := newTestLib(t)
	ctx := t.Context()
	f := l.add("film.mkv", 90_000, time.Now())
	if err := l.g.RunOnce(ctx); err != nil {
		t.Fatal(err)
	}
	v1 := Version(f.Size, f.Mtime, ToneSDR)
	if _, ok := l.g.Done(ctx, f.ID, f.Size, f.Mtime); !ok {
		t.Fatal("not done")
	}
	f2 := l.add("film.mkv", 90_000, time.Now(), withSize(4321))
	if _, ok := l.g.Done(ctx, f2.ID, f2.Size, f2.Mtime); ok {
		t.Fatal("thumbnails of the old version offered for the new")
	}
	if err := l.g.RunOnce(ctx); err != nil {
		t.Fatal(err)
	}
	v2 := Version(f2.Size, f2.Mtime, ToneSDR)
	if r, _ := l.row(f.ID); r.Version != v2 || v2 == v1 {
		t.Fatalf("row after the change %+v (old %s)", r, v1)
	}
	if _, err := os.Stat(FileDir(l.g.dir, f.ID, v1)); !os.IsNotExist(err) {
		t.Errorf("the old version's folder is still there: %v", err)
	}
	if _, ok := l.g.SheetPath(ctx, f.ID, v1, 0); ok {
		t.Error("the old version's sheets are still served")
	}
	if _, err := l.conn.ExecContext(ctx, `DELETE FROM media_files WHERE id = ?`, f.ID); err != nil {
		t.Fatal(err)
	}
	if _, ok := l.row(f.ID); ok {
		t.Fatal("the row outlived its file")
	}
	if n, err := l.g.Sweep(ctx); err != nil || n != 1 {
		t.Errorf("sweep removed %d (%v), want the file's folder", n, err)
	}
	if _, err := os.Stat(FileDir(l.g.dir, f.ID, v2)); !os.IsNotExist(err) {
		t.Errorf("the deleted file's folder is still there: %v", err)
	}
}

// The sweep makes the folder match the database.
func TestSweep(t *testing.T) {
	t.Parallel()
	l := newTestLib(t)
	ctx := t.Context()
	now := time.Now()
	long := l.add("long-gone.mkv", 90_000, now)
	lately := l.add("lately-gone.mkv", 90_000, now)
	here := l.add("here.mkv", 90_000, now)
	lost := l.add("lost.mkv", 90_000, now)
	if err := l.g.RunOnce(ctx); err != nil {
		t.Fatal(err)
	}
	if _, err := l.q.MarkMediaFilesMissing(ctx, []int64{long.ID, lately.ID}); err != nil {
		t.Fatal(err)
	}
	if _, err := l.conn.ExecContext(ctx, `UPDATE media_files SET updated_at = ? WHERE id = ?`, db.FormatTime(now.Add(-31*24*time.Hour)), long.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := l.conn.ExecContext(ctx, `UPDATE media_files SET updated_at = ? WHERE id = ?`, db.FormatTime(now.Add(-5*24*time.Hour)), lately.ID); err != nil {
		t.Fatal(err)
	}
	// lost.mkv's folder was deleted by hand (or the database came from a
	// backup): its row goes, so it is made again.
	if err := os.RemoveAll(FileDir(l.g.dir, lost.ID, Version(lost.Size, lost.Mtime, ToneSDR))); err != nil {
		t.Fatal(err)
	}
	mkdir := func(path string) {
		if err := os.MkdirAll(path, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	orphan := FileDir(l.g.dir, 9999, Version(1, "x", ToneSDR))          // no file
	otherVersion := FileDir(l.g.dir, here.ID, Version(1, "x", ToneSDR)) // not the row's
	resumable := tmpDir(l.g.dir, here.ID+100, "00000000")               // no such file
	staleTmp := tmpDir(l.g.dir, here.ID, Version(7, "old", ToneSDR))    // an old version
	pending := l.add("pending.mkv", 90_000, now)                        // made later
	pendingTmp := tmpDir(l.g.dir, pending.ID, Version(pending.Size, pending.Mtime, ToneSDR))
	for _, p := range []string{orphan, otherVersion, resumable, staleTmp, pendingTmp} {
		mkdir(p)
	}
	foreign := filepath.Join(l.g.dir, "notes")
	mkdir(foreign)

	if _, err := l.g.Sweep(ctx); err != nil {
		t.Fatal(err)
	}
	exists := func(p string) bool { _, err := os.Stat(p); return err == nil }
	for name, c := range map[string]struct {
		path string
		want bool
	}{
		"missing for 31 days":            {FileDir(l.g.dir, long.ID, Version(long.Size, long.Mtime, ToneSDR)), false},
		"missing for 5 days":             {FileDir(l.g.dir, lately.ID, Version(lately.Size, lately.Mtime, ToneSDR)), true},
		"present":                        {FileDir(l.g.dir, here.ID, Version(here.Size, here.Mtime, ToneSDR)), true},
		"a file that is not there":       {orphan, false},
		"another version":                {otherVersion, false},
		"half made, no file":             {resumable, false},
		"half made, old version":         {staleTmp, false},
		"half made, the current version": {pendingTmp, true},
		"not the sweep's":                {foreign, true},
	} {
		if got := exists(c.path); got != c.want {
			t.Errorf("%s: there %v, want %v", name, got, c.want)
		}
	}
	if _, ok := l.row(long.ID); ok {
		t.Error("the row of a file missing for 31 days was kept")
	}
	if _, ok := l.row(lately.ID); !ok {
		t.Error("the row of a file missing for 5 days went")
	}
	if _, ok := l.row(lost.ID); ok {
		t.Error("a done row without its folder was kept")
	}
}

// DeleteAll removes every row and folder, and with the setting on wakes
// the job to make them again.
func TestDeleteAll(t *testing.T) {
	t.Parallel()
	l := newTestLib(t)
	ctx := t.Context()
	f := l.add("film.mkv", 90_000, time.Now())
	if err := l.g.RunOnce(ctx); err != nil {
		t.Fatal(err)
	}
	if err := l.g.DeleteAll(ctx); err != nil {
		t.Fatal(err)
	}
	if _, ok := l.row(f.ID); ok {
		t.Error("a row is left")
	}
	if entries, _ := os.ReadDir(l.g.dir); len(entries) != 0 {
		t.Errorf("left %d entries in the folder", len(entries))
	}
	select {
	case <-l.g.wake:
	default:
		t.Error("the job was not woken to make them again")
	}
	s, _ := l.g.Status(ctx)
	if s.Bytes != 0 || s.Done != 0 || s.Pending != 1 {
		t.Errorf("status %+v", s)
	}
}

// No sheet starts while something plays, nor until 2 minutes after it
// stops; meanwhile the status says why.
func TestPausedWhilePlaying(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		l := newTestLib(t)
		var playing atomic.Bool
		playing.Store(true)
		l.g.busy = playing.Load
		l.add("film.mkv", 90_000, time.Now())
		done := make(chan error, 1)
		go func() { done <- l.g.RunOnce(t.Context()) }()
		time.Sleep(10 * time.Minute)
		synctest.Wait()
		if got := l.fake.list(); len(got) != 0 {
			t.Fatalf("made %v while something played", got)
		}
		if s, _ := l.g.Status(t.Context()); s.Paused != PausedPlaying || !s.Running || s.Current != nil {
			t.Errorf("status while playing %+v", s)
		}
		playing.Store(false)
		stopped := time.Now()
		time.Sleep(2*time.Minute - time.Second)
		synctest.Wait()
		if got := l.fake.list(); len(got) != 0 {
			t.Fatalf("made %v within 2 minutes of the play ending", got)
		}
		if err := <-done; err != nil {
			t.Fatal(err)
		}
		at := l.fake.at[0].Sub(stopped)
		if at < 2*time.Minute || at > 2*time.Minute+l.g.Poll {
			t.Errorf("first sheet %s after the play stopped, want 2 min to 2 min + %s", at, l.g.Poll)
		}
		if s, _ := l.g.Status(t.Context()); s.Paused != "" || s.Running || s.Done != 1 {
			t.Errorf("status after %+v", s)
		}

		// A play seen on the bus between sheets counts too.
		l.add("next.mkv", 90_000, time.Now())
		l.g.Played()
		played := time.Now()
		before := len(l.fake.list())
		if err := l.g.RunOnce(t.Context()); err != nil {
			t.Fatal(err)
		}
		if at := l.fake.at[before].Sub(played); at < 2*time.Minute {
			t.Errorf("a sheet started %s after a play was seen", at)
		}
	})
}

// No sheet starts while the data folder's disk has under 5 GB free.
func TestPausedWhileTheDiskIsFull(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		l := newTestLib(t)
		var free atomic.Uint64
		free.Store(3 << 30)
		l.g.free = func(string) (uint64, error) { return free.Load(), nil }
		l.add("film.mkv", 90_000, time.Now())
		done := make(chan error, 1)
		go func() { done <- l.g.RunOnce(t.Context()) }()
		time.Sleep(time.Hour)
		synctest.Wait()
		if got := l.fake.list(); len(got) != 0 {
			t.Fatalf("made %v with 3 GB free", got)
		}
		if s, _ := l.g.Status(t.Context()); s.Paused != PausedLowDisk || s.FreeBytes != 3<<30 {
			t.Errorf("status %+v", s)
		}
		free.Store(6 << 30)
		if err := <-done; err != nil {
			t.Fatal(err)
		}
		if got := l.fake.list(); len(got) != 3 {
			t.Errorf("made %v once there was room, want all 3 sheets", got)
		}
	})
}

// Run makes thumbnails Delay after a library change; turning the setting
// off stops the run in progress and keeps what is made, and turning it on
// starts one at once.
func TestRunFollowsTheSetting(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		l := newTestLib(t)
		l.g.Delay = time.Minute
		block := make(chan struct{})
		var started atomic.Int32
		l.g.runOnce = func(ctx context.Context) error {
			started.Add(1)
			select {
			case <-block:
				return nil
			case <-ctx.Done():
				return ctx.Err()
			}
		}
		ctx, cancel := context.WithCancel(t.Context())
		stopped := make(chan struct{})
		go func() {
			l.g.Run(ctx)
			close(stopped)
		}()
		time.Sleep(time.Minute)
		synctest.Wait()
		if n := started.Load(); n != 1 {
			t.Fatalf("runs after Delay = %d", n)
		}
		l.g.SetEnabled(false)
		synctest.Wait()
		// Off: the run ended, and nothing starts, even on a library change.
		l.g.bus.Publish(events.Event{Type: "library.changed"})
		time.Sleep(10 * time.Minute)
		synctest.Wait()
		if n := started.Load(); n != 1 {
			t.Fatalf("runs while off = %d", n)
		}
		l.g.SetEnabled(true)
		synctest.Wait()
		if n := started.Load(); n != 2 {
			t.Fatalf("turning it on started %d runs, want one more", n-1)
		}
		close(block)
		cancel()
		<-stopped
	})
}
