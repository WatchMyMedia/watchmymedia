package scanner

import (
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/fsnotify/fsnotify"
)

func openFiles(t *testing.T) int {
	t.Helper()
	des, err := os.ReadDir("/dev/fd")
	if err != nil {
		t.Fatal(err)
	}
	return len(des)
}

func newTestFolderWatcher(t *testing.T) *folderWatcher {
	t.Helper()
	w, err := newFolderWatcher()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = w.Close() })
	return w
}

// expect waits until every wanted event has arrived, in any order, and
// fails on any other.
func expect(t *testing.T, w *folderWatcher, want ...fsnotify.Event) {
	t.Helper()
	left := map[fsnotify.Event]bool{}
	for _, e := range want {
		left[e] = true
	}
	deadline := time.After(5 * time.Second)
	for len(left) > 0 {
		select {
		case e := <-w.Events():
			if !left[e] {
				t.Fatalf("unexpected event %v; still waiting for %v", e, left)
			}
			delete(left, e)
		case err := <-w.Errors():
			t.Fatal(err)
		case <-deadline:
			t.Fatalf("no event for %v", left)
		}
	}
	select {
	case e := <-w.Events():
		t.Fatalf("unexpected event %v", e)
	case <-time.After(100 * time.Millisecond):
	}
}

// A watched folder holds one open file however many files are in it, and
// letting it go closes that file.
func TestFolderWatcherOpensFoldersOnly(t *testing.T) {
	root := t.TempDir()
	for _, d := range []string{"Season 01", "Season 02"} {
		for i := range 50 {
			writeOld(t, filepath.Join(root, d, fmt.Sprintf("Show - S01E%02d.mkv", i)), "a")
		}
	}
	w := newTestFolderWatcher(t)
	before := openFiles(t)
	for _, d := range []string{root, filepath.Join(root, "Season 01"), filepath.Join(root, "Season 02")} {
		if err := w.Add(d); err != nil {
			t.Fatal(err)
		}
	}
	if got := openFiles(t) - before; got != 3 {
		t.Errorf("watching 3 folders of 100 files opened %d files, want 3", got)
	}
	for _, d := range []string{root, filepath.Join(root, "Season 01"), filepath.Join(root, "Season 02")} {
		if err := w.Remove(d); err != nil {
			t.Fatal(err)
		}
	}
	if got := openFiles(t) - before; got != 0 {
		t.Errorf("%d files still open after removing every watch", got)
	}
}

// Changes inside a watched folder come out as fsnotify events naming the
// entry: new, gone, renamed, and replaced under the same name.
func TestFolderWatcherReportsChanges(t *testing.T) {
	root := t.TempDir()
	staging := t.TempDir()
	old := filepath.Join(root, "Show - S01E01.mkv")
	writeOld(t, old, "a")
	if err := os.Mkdir(filepath.Join(root, "Extras"), 0o755); err != nil {
		t.Fatal(err)
	}
	w := newTestFolderWatcher(t)
	if err := w.Add(root); err != nil {
		t.Fatal(err)
	}
	at := func(name string, op fsnotify.Op) fsnotify.Event {
		return fsnotify.Event{Name: filepath.Join(root, name), Op: op}
	}

	writeOld(t, filepath.Join(root, "Show - S01E02.mkv"), "b")
	expect(t, w, at("Show - S01E02.mkv", fsnotify.Create))

	if err := os.Rename(filepath.Join(root, "Show - S01E02.mkv"), filepath.Join(root, "Show - S01E03.mkv")); err != nil {
		t.Fatal(err)
	}
	expect(t, w, at("Show - S01E02.mkv", fsnotify.Remove), at("Show - S01E03.mkv", fsnotify.Create))

	// An upgrade moved over the old file keeps its name.
	writeOld(t, filepath.Join(staging, "new.mkv"), "a better copy")
	if err := os.Rename(filepath.Join(staging, "new.mkv"), old); err != nil {
		t.Fatal(err)
	}
	expect(t, w, at("Show - S01E01.mkv", fsnotify.Write))

	// A folder replaced by another of the same name is gone, then new.
	if err := os.Mkdir(filepath.Join(staging, "Extras"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.RemoveAll(filepath.Join(root, "Extras")); err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(filepath.Join(staging, "Extras"), filepath.Join(root, "Extras")); err != nil {
		t.Fatal(err)
	}
	expect(t, w, at("Extras", fsnotify.Remove), at("Extras", fsnotify.Create))

	if err := os.Remove(filepath.Join(root, "Show - S01E03.mkv")); err != nil {
		t.Fatal(err)
	}
	expect(t, w, at("Show - S01E03.mkv", fsnotify.Remove))
}

// A watched folder that is renamed or deleted says so under its old path
// and stops being watched, its open file closed.
func TestFolderWatcherReportsFolderGone(t *testing.T) {
	parent := t.TempDir()
	dir := filepath.Join(parent, "Movie (1999)")
	writeOld(t, filepath.Join(dir, "Movie.mkv"), "a")
	w := newTestFolderWatcher(t)
	before := openFiles(t)
	if err := w.Add(dir); err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(dir, filepath.Join(parent, "Movie (1999) [1080p]")); err != nil {
		t.Fatal(err)
	}
	expect(t, w, fsnotify.Event{Name: dir, Op: fsnotify.Rename})
	if got := openFiles(t) - before; got != 0 {
		t.Errorf("%d files still open after the folder moved", got)
	}
	if err := w.Remove(dir); err == nil {
		t.Error("the moved folder is still watched")
	}

	gone := filepath.Join(parent, "Gone")
	if err := os.Mkdir(gone, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := w.Add(gone); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(gone); err != nil {
		t.Fatal(err)
	}
	expect(t, w, fsnotify.Event{Name: gone, Op: fsnotify.Remove})
}

// Watches never take more than a share of the system's open files.
func TestWatchBudgetLeavesTheSystemMostFiles(t *testing.T) {
	total, ok := systemFiles()
	if !ok {
		t.Fatal("kern.maxfiles unread")
	}
	if got := watchBudget(slog.New(slog.DiscardHandler)); got > total/systemShare {
		t.Errorf("budget %d of the system's %d open files, want at most %d", got, total, total/systemShare)
	}
}
