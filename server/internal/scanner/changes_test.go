package scanner

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"slices"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
)

// scanned reloads lib, so the next scan sees last_scanned_at as a running
// server would.
func scanned(t *testing.T, s *Scanner, q *db.Queries, lib db.Library, paths ...string) ScanFinished {
	t.Helper()
	lib, err := q.GetLibrary(context.Background(), lib.ID)
	if err != nil {
		t.Fatal(err)
	}
	var sum ScanFinished
	if paths == nil {
		sum, err = s.Scan(context.Background(), lib)
	} else {
		sum, err = s.ScanPaths(context.Background(), lib, paths)
	}
	if err != nil {
		t.Fatal(err)
	}
	return sum
}

func row(t *testing.T, q *db.Queries, path string) db.MediaFile {
	t.Helper()
	f, err := q.GetMediaFileByPath(context.Background(), path)
	if err != nil {
		t.Fatalf("%s: %v", path, err)
	}
	return f
}

// After a library's first scan, a file counts as added when it is found,
// though a copy keeps a date on disk from years ago.
func TestScanAddedWhenFoundAfterFirstScan(t *testing.T) {
	s, q, _, root, lib := setup(t)
	writeOld(t, filepath.Join(root, "Heat (1995).mkv"), "a")
	scanned(t, s, q, lib)

	copied := filepath.Join(root, "Alien (1979)", "Alien (1979).mkv")
	writeOld(t, copied, "b")
	years := time.Now().AddDate(-6, 0, 0)
	if err := os.Chtimes(copied, years, years); err != nil {
		t.Fatal(err)
	}
	before := time.Now()
	if sum := scanned(t, s, q, lib); sum.Added != 1 {
		t.Fatalf("summary %+v", sum)
	}
	if got := db.ParseTime(row(t, q, copied).AddedAt); got.Before(before.Add(-time.Second)) {
		t.Errorf("copied file added %v, want about now", got)
	}
}

// A file that moves or is renamed keeps its added date: the same size and
// date as a file that just went missing.
func TestScanKeepsAddedDateOfMovedFile(t *testing.T) {
	s, q, _, root, lib := setup(t)
	old := filepath.Join(root, "Heat (1995)", "Heat.mkv")
	writeOld(t, old, "abc")
	scanned(t, s, q, lib)
	was := row(t, q, old).AddedAt

	moved := filepath.Join(root, "Heat (1995) [1080p]", "Heat.mkv")
	if err := os.MkdirAll(filepath.Dir(moved), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(old, moved); err != nil {
		t.Fatal(err)
	}
	sum := scanned(t, s, q, lib)
	if sum.Added != 1 || sum.Missing != 1 {
		t.Fatalf("summary %+v", sum)
	}
	if got := row(t, q, moved).AddedAt; got != was {
		t.Errorf("moved file added %s, want %s", got, was)
	}

	// A different file of the same size is new.
	other := filepath.Join(root, "Other (2001).mkv")
	writeOld(t, other, "xyz")
	before := time.Now()
	scanned(t, s, q, lib)
	if got := db.ParseTime(row(t, q, other).AddedAt); got.Before(before.Add(-time.Second)) {
		t.Errorf("new file added %v, want about now", got)
	}
}

// A scan of some paths reads and marks missing only below them: "Show" does
// not take in "Show 2", and a file removed elsewhere stays present until the
// whole library is read.
func TestScanPaths(t *testing.T) {
	s, q, fp, root, lib := setup(t)
	show := filepath.Join(root, "Show")
	show2 := filepath.Join(root, "Show 2")
	a := filepath.Join(show, "Season 01", "Show - S01E01.mkv")
	b := filepath.Join(show2, "Show 2 - S01E01.mkv")
	c := filepath.Join(root, "Elsewhere.mkv")
	for _, p := range []string{a, b, c} {
		writeOld(t, p, "a")
	}
	scanned(t, s, q, lib)
	probes := fp.calls()

	if err := os.Remove(b); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(c); err != nil {
		t.Fatal(err)
	}
	added := filepath.Join(show, "Season 01", "Show - S01E02.mkv")
	writeOld(t, added, "b")

	sum := scanned(t, s, q, lib, show)
	if !sum.Partial || sum.Added != 1 || sum.Unchanged != 1 || sum.Missing != 0 || fp.calls() != probes+1 {
		t.Fatalf("summary %+v, probes %d", sum, fp.calls()-probes)
	}
	for _, p := range []string{b, c} {
		if st := row(t, q, p).Status; st != "present" {
			t.Errorf("%s is %s; it is outside the scanned path", filepath.Base(p), st)
		}
	}

	// A path that is gone marks what was there missing; a file path reads
	// just that file.
	sum = scanned(t, s, q, lib, show2, filepath.Join(show, "Season 01", "Show - S01E01.mkv"))
	if sum.Missing != 1 || sum.Unchanged != 1 {
		t.Fatalf("summary %+v", sum)
	}
	if st := row(t, q, b).Status; st != "missing" {
		t.Errorf("removed file is %s", st)
	}
	if st := row(t, q, c).Status; st != "present" {
		t.Errorf("file outside the scan is %s", st)
	}
	if sum := scanned(t, s, q, lib); sum.Missing != 1 || sum.Partial {
		t.Errorf("whole-library summary %+v", sum)
	}
}

// Scans of paths in ignored folders, or outside the library, read nothing.
func TestScanPathsIgnored(t *testing.T) {
	s, q, fp, root, lib := setup(t)
	writeOld(t, filepath.Join(root, "Movie (2000)", "Extras", "making-of.mkv"), "a")
	writeOld(t, filepath.Join(root, "Movie (2000)", "Movie.mkv"), "a")
	sum := scanned(t, s, q, lib, filepath.Join(root, "Movie (2000)", "Extras"), filepath.Join(root, "Movie (2000)", "Extras", "making-of.mkv"), "/elsewhere")
	if sum.Added != 0 || fp.calls() != 0 {
		t.Errorf("summary %+v", sum)
	}
}

// A scan that finds nothing changed writes nothing.
func TestScanUnchangedWritesNothing(t *testing.T) {
	s, q, _, root, lib := setup(t)
	p := filepath.Join(root, "Heat (1995).mkv")
	writeOld(t, p, "a")
	scanned(t, s, q, lib)
	before := row(t, q, p)
	if sum := scanned(t, s, q, lib); sum.Unchanged != 1 {
		t.Fatalf("summary %+v", sum)
	}
	// Every write a scan makes changes seen_in_scan (unique to each scan)
	// or the name it read.
	if after := row(t, q, p); !reflect.DeepEqual(after, before) {
		t.Errorf("unchanged file written to:\n%+v\n%+v", before, after)
	}
}

// A missing file that comes back unchanged is present again, and announced.
func TestScanRestoredFileIsAnnounced(t *testing.T) {
	s, q, _, root, lib := setup(t)
	p := filepath.Join(root, "Heat (1995).mkv")
	writeOld(t, p, "a")
	scanned(t, s, q, lib)
	away := filepath.Join(t.TempDir(), "Heat.mkv")
	if err := os.Rename(p, away); err != nil {
		t.Fatal(err)
	}
	scanned(t, s, q, lib)
	if err := os.Rename(away, p); err != nil {
		t.Fatal(err)
	}
	ch, cancel := s.bus.Subscribe()
	defer cancel()
	if sum := scanned(t, s, q, lib); sum.Restored != 1 {
		t.Fatalf("summary %+v", sum)
	}
	if st := row(t, q, p).Status; st != "present" {
		t.Errorf("status %s", st)
	}
	if !published(ch, "library.changed") {
		t.Error("library.changed not sent")
	}
}

func published(ch <-chan events.Event, kind string) bool {
	for {
		select {
		case e := <-ch:
			if e.Type == kind {
				return true
			}
		default:
			return false
		}
	}
}

// With matching to follow, a scan that adds files leaves library.changed to
// it; a scan that only loses files sends it.
func TestScanLeavesAnnouncementToMatching(t *testing.T) {
	s, q, _, root, lib := setup(t)
	s.opts.MatchesAfter = func() bool { return true }
	ch, cancel := s.bus.Subscribe()
	defer cancel()
	p := filepath.Join(root, "Heat (1995).mkv")
	writeOld(t, p, "a")
	scanned(t, s, q, lib)
	if published(ch, "library.changed") {
		t.Error("library.changed sent for new files that matching will announce")
	}
	if err := os.Remove(p); err != nil {
		t.Fatal(err)
	}
	scanned(t, s, q, lib)
	if !published(ch, "library.changed") {
		t.Error("library.changed not sent for a missing file")
	}
	// Nothing changed: no scan.finished for a scan of changes either.
	scanned(t, s, q, lib, filepath.Join(root, "poster.jpg"))
	if published(ch, "scan.finished") {
		t.Error("scan.finished sent for a scan that changed nothing")
	}
}

// Files still being written are looked at again on their own, once stable.
func TestScanRechecksUnstableFilesOnly(t *testing.T) {
	s, q, _, root, lib := setup(t)
	writeOld(t, filepath.Join(root, "Heat (1995).mkv"), "a")
	scanned(t, s, q, lib)
	fresh := filepath.Join(root, "Fresh (2020).mkv")
	writeOld(t, fresh, "x")
	fi, err := os.Stat(fresh)
	if err != nil {
		t.Fatal(err)
	}
	// By the scanner's clock the file was written half a second ago.
	now := fi.ModTime().Add(500 * time.Millisecond)
	s.now = func() time.Time { return now }
	var recheck func()
	var wait time.Duration
	s.after = func(d time.Duration, f func()) { wait, recheck = d, f }
	if sum := scanned(t, s, q, lib); sum.Skipped != 1 || sum.Added != 0 {
		t.Fatalf("summary %+v", sum)
	}
	if want := s.opts.StableFor - 500*time.Millisecond + 250*time.Millisecond; recheck == nil || wait != want {
		t.Fatalf("recheck in %v, want %v", wait, want)
	}
	ch, cancel := s.bus.Subscribe()
	defer cancel()
	now = now.Add(wait)
	recheck()
	if f := nextScan(t, ch); f.Added != 1 || !f.Partial || f.Unchanged != 0 {
		t.Errorf("recheck: %+v; want the new file alone", f)
	}
}

func TestScopeMerge(t *testing.T) {
	native := func(paths ...string) []string {
		for i, p := range paths {
			paths[i] = filepath.FromSlash(p)
		}
		return paths
	}
	sc := pathsScope(native("/m/Show 2/a.mkv", "/m/Show/Season 1/x.mkv", "/m/Show", "/m/Show/", "/m/Show 2"))
	if want := native("/m/Show", "/m/Show 2"); sc.full || !reflect.DeepEqual(sc.paths, want) {
		t.Errorf("paths %v, want %v", sc.paths, want)
	}
	sc.merge(pathsScope(native("/m/Other")))
	if len(sc.paths) != 3 {
		t.Errorf("paths %v", sc.paths)
	}
	sc.merge(wholeLibrary())
	if !sc.full || sc.paths != nil {
		t.Errorf("merged with a whole-library scan: %+v", sc)
	}
	many := make([]string, maxScopePaths+1)
	for i := range many {
		many[i] = filepath.Join("/m", time.Duration(i).String())
	}
	if !pathsScope(many).full {
		t.Error("too many paths did not become a whole-library scan")
	}
}

// The watcher turns a new file into a scan of that file, and reports what
// it covers.
func TestWatcherScansWhatChanged(t *testing.T) {
	s, q, _, root, lib := setup(t)
	s.opts.Debounce = 100 * time.Millisecond
	writeOld(t, filepath.Join(root, "Show", "Season 01", "Show - S01E01.mkv"), "a")
	scanned(t, s, q, lib)
	ctx := t.Context()
	ch, cancel := s.bus.Subscribe()
	defer cancel()
	go s.runWatcher(ctx)
	waitFor(t, s, func() bool { return s.WatchStatus(lib.ID).State == "watching" })
	if st := s.WatchStatus(lib.ID); st.Folders != 3 || st.Unwatched != 0 {
		t.Errorf("watch status %+v, want 3 folders", st)
	}

	// A new season folder arrives with an episode in it, as a move would.
	staged := filepath.Join(t.TempDir(), "Season 02")
	writeOld(t, filepath.Join(staged, "Show - S02E01.mkv"), "b")
	if err := os.Rename(staged, filepath.Join(root, "Show", "Season 02")); err != nil {
		t.Fatal(err)
	}
	f := nextScan(t, ch)
	if !f.Partial || f.Added != 1 || f.Unchanged != 0 {
		t.Errorf("scan after a new folder: %+v", f)
	}
	waitFor(t, s, func() bool { return s.WatchStatus(lib.ID).Folders == 4 })

	// An episode in it is replaced by another.
	ep := filepath.Join(root, "Show", "Season 02", "Show - S02E02.mkv")
	writeOld(t, ep, "c")
	if err := os.Remove(filepath.Join(root, "Show", "Season 02", "Show - S02E01.mkv")); err != nil {
		t.Fatal(err)
	}
	f = nextScan(t, ch)
	if !f.Partial || f.Added != 1 || f.Missing != 1 || f.Unchanged != 0 {
		t.Errorf("scan after a replaced episode: %+v", f)
	}
}

// A renamed folder is scanned at both names and watched at the new one.
func TestWatcherFollowsRenamedFolder(t *testing.T) {
	s, q, _, root, lib := setup(t)
	s.opts.Debounce = 100 * time.Millisecond
	writeOld(t, filepath.Join(root, "Show", "Season 01", "Show - S01E01.mkv"), "a")
	scanned(t, s, q, lib)
	ctx := t.Context()
	ch, cancel := s.bus.Subscribe()
	defer cancel()
	go s.runWatcher(ctx)
	waitFor(t, s, func() bool { return s.WatchStatus(lib.ID).State == "watching" })

	if err := os.Rename(filepath.Join(root, "Show", "Season 01"), filepath.Join(root, "Show", "Season 1")); err != nil {
		t.Fatal(err)
	}
	f := nextScan(t, ch)
	if !f.Partial || f.Added != 1 || f.Missing != 1 {
		t.Errorf("scan after a renamed folder: %+v", f)
	}
	if st := s.WatchStatus(lib.ID); st.Folders != 3 || st.Unwatched != 0 {
		t.Errorf("watch status %+v, want 3 folders", st)
	}

	writeOld(t, filepath.Join(root, "Show", "Season 1", "Show - S01E02.mkv"), "b")
	if f := nextScan(t, ch); f.Added != 1 {
		t.Errorf("scan after a new file in the renamed folder: %+v", f)
	}
}

// Beyond the limit on watches, the folders left are counted, not dropped
// without a word.
func TestWatcherReportsFoldersBeyondLimit(t *testing.T) {
	s, _, _, root, lib := setup(t)
	s.watchLimit = 1
	for _, d := range []string{"A", "B", "C"} {
		writeOld(t, filepath.Join(root, d, "x.mkv"), "a")
	}
	ctx := t.Context()
	go s.runWatcher(ctx)
	waitFor(t, s, func() bool { return s.WatchStatus(lib.ID).State == "partial" })
	if st := s.WatchStatus(lib.ID); st.Folders+st.Unwatched != 4 || st.Unwatched < 3 {
		t.Errorf("watch status %+v, want 4 folders, at least 3 of them unwatched", st)
	}
}

func nextScan(t *testing.T, ch <-chan events.Event) ScanFinished {
	t.Helper()
	deadline := time.After(10 * time.Second)
	for {
		select {
		case e := <-ch:
			if f, ok := e.Data.(ScanFinished); ok {
				return f
			}
		case <-deadline:
			t.Fatal("no scan")
		}
	}
}

// waitFor waits until ok holds, checking whenever a watch status changes.
func waitFor(t *testing.T, s *Scanner, ok func() bool) {
	t.Helper()
	deadline := time.After(5 * time.Second)
	for !ok() {
		select {
		case <-s.watchChanged:
		case <-deadline:
			t.Fatal("timed out")
		}
	}
}

// Writes are committed in batches: more files than one batch holds are all
// added, and all restored when they come back.
func TestScanWritesInBatches(t *testing.T) {
	s, q, _, root, lib := setup(t)
	n := 2*batchSize + 7
	for i := range n {
		writeOld(t, filepath.Join(root, fmt.Sprintf("Folder %d", i%20), fmt.Sprintf("Movie %d (2000).mkv", i)), "a")
	}
	if sum := scanned(t, s, q, lib); sum.Added != n {
		t.Fatalf("first scan %+v", sum)
	}
	ctx := t.Context()
	rows, err := q.ListScanFiles(ctx, lib.ID)
	if err != nil || len(rows) != n {
		t.Fatalf("%d rows, %v", len(rows), err)
	}
	ids := make([]int64, 0, n)
	for _, r := range rows {
		ids = append(ids, r.ID)
	}
	for chunk := range slices.Chunk(ids, batchSize) {
		if _, err := q.MarkMediaFilesMissing(ctx, chunk); err != nil {
			t.Fatal(err)
		}
	}
	if sum := scanned(t, s, q, lib); sum.Restored != n || sum.Unchanged != n {
		t.Errorf("scan after they came back %+v", sum)
	}
	counts, err := q.CountMediaFilesByStatus(ctx)
	if err != nil || len(counts) != 1 || counts[0].Status != "present" || counts[0].Count != int64(n) {
		t.Errorf("statuses %+v, %v", counts, err)
	}
}
