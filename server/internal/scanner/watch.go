package scanner

import (
	"context"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/fsnotify/fsnotify"

	"watchmymedia/server/internal/parser"
)

// WatchStatus returns how much of a library the filesystem watcher covers.
func (s *Scanner) WatchStatus(libraryID int64) WatchStatus {
	s.mu.Lock()
	defer s.mu.Unlock()
	if w, ok := s.watch[libraryID]; ok {
		return w
	}
	return WatchStatus{State: "off"}
}

func (s *Scanner) setWatch(libraryID int64, w WatchStatus) {
	s.mu.Lock()
	s.watch[libraryID] = w
	s.mu.Unlock()
	select {
	case s.watchChanged <- struct{}{}:
	default:
	}
}

// watcher keeps a watch on every folder of every library it can, and turns
// events into scans of the paths that changed. It runs on one goroutine.
type watcher struct {
	s *Scanner
	w notifier
	// budget is what is left before the system's limit: open files where
	// each watched folder holds one (kqueue; with fsnotify's kqueue, each
	// entry in it too), else unbounded until the system refuses a watch
	// (inotify's max_user_watches).
	budget int
	// blocked is set when the budget ran out or the system refused a watch;
	// a watched folder going away clears it.
	blocked   bool
	warned    bool
	dirs      map[string]watched
	roots     map[int64]string
	unwatched map[int64]int
}

type watched struct {
	library int64
	cost    int
}

// runWatcher subscribes to filesystem events under every library root and
// requests a debounced scan of the paths that changed (see debouncer). Roots that are not
// mounted yet are retried once a minute, so plugging in a drive later works.
func (s *Scanner) runWatcher(ctx context.Context) {
	fw, err := newNotifier()
	if err != nil {
		s.log.Error("filesystem watcher unavailable; relying on scheduled scans", "err", err)
		return
	}
	defer func() { _ = fw.Close() }()
	budget := watchBudget(s.log)
	if s.watchLimit > 0 {
		budget = s.watchLimit
	}
	wt := &watcher{s: s, w: fw, budget: budget, dirs: map[string]watched{}, roots: map[int64]string{}, unwatched: map[int64]int{}}

	changes := newDebouncer(s.opts.Debounce, maxDebounce)

	wt.addRoots(ctx, true)
	retry := time.NewTicker(time.Minute)
	defer retry.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-retry.C:
			wt.addRoots(ctx, false)
		case err := <-fw.Errors():
			if errors.Is(err, fsnotify.ErrEventOverflow) {
				// Events were dropped: only reading everything finds what they were.
				s.log.Warn("watcher dropped events; scanning every library", "err", err)
				s.RequestAll()
				continue
			}
			s.log.Warn("watcher error", "err", err)
		case b := <-changes.due:
			if id, paths, ok := changes.take(b); ok {
				s.RequestPaths(id, paths)
			}
		case ev := <-fw.Events():
			base := filepath.Base(ev.Name)
			if strings.HasPrefix(base, ".") {
				continue // .DS_Store and friends churn constantly
			}
			id := wt.libraryFor(ev.Name)
			if id == 0 {
				continue
			}
			if ev.Has(fsnotify.Remove) || ev.Has(fsnotify.Rename) {
				// A folder moved away keeps its watch under the old name on
				// some systems; the new name arrives as a Create.
				wt.forget(ev.Name)
			}
			fi, err := os.Stat(ev.Name)
			switch {
			case err != nil:
				// Gone: scanning the path marks what was there missing.
			case fi.IsDir():
				// Changes inside a folder arrive as events of their own; a new
				// folder (made, or moved in with files) is read whole.
				if !ev.Has(fsnotify.Create) || skipDir(wt.roots[id], ev.Name) {
					continue
				}
				wt.addTree(id, ev.Name)
				wt.publish(id)
			case !parser.IsVideo(base):
				continue // artwork, subtitles and .nfo files are not scanned
			}
			changes.add(ctx, id, ev.Name)
		}
	}
}

// addRoots watches every library root that is reachable. After startup, a
// root that becomes reachable (a drive was mounted) is scanned right away;
// at startup the scheduler already queues the first scan of everything.
// A library that moved folders is watched at its new one, and a library
// with folders left unwatched gets another try once watches have freed up.
func (wt *watcher) addRoots(ctx context.Context, startup bool) {
	libs, err := wt.s.q.ListLibraries(ctx)
	if err != nil {
		wt.s.log.Error("watcher: list libraries", "err", err)
		return
	}
	current := map[int64]bool{}
	for _, lib := range libs {
		current[lib.ID] = true
		root := filepath.Clean(lib.Path)
		if old, ok := wt.roots[lib.ID]; ok && old != root {
			wt.forget(old)
			delete(wt.roots, lib.ID)
		}
		_, known := wt.roots[lib.ID]
		if known && (wt.unwatched[lib.ID] == 0 || wt.blocked) {
			continue
		}
		if fi, err := os.Stat(root); err != nil || !fi.IsDir() {
			wt.s.setWatch(lib.ID, WatchStatus{State: "off"})
			continue
		}
		wt.roots[lib.ID] = root
		wt.unwatched[lib.ID] = 0
		wt.addTree(lib.ID, root)
		st := wt.publish(lib.ID)
		if !known {
			wt.s.log.Info("watching library", "library", lib.ID, "path", root, "dirs", st.Folders, "unwatched", st.Unwatched)
			if !startup {
				wt.s.Request(lib.ID)
			}
		}
	}
	for id, root := range wt.roots {
		if !current[id] {
			wt.forget(root)
			delete(wt.roots, id)
			delete(wt.unwatched, id)
		}
	}
}

func (wt *watcher) libraryFor(p string) int64 {
	bestID, bestLen := int64(0), 0
	for id, root := range wt.roots {
		if (p == root || within(root, p)) && len(root) > bestLen {
			bestID, bestLen = id, len(root)
		}
	}
	return bestID
}

// addTree watches dir and every folder below it that scans read, as far as
// the budget goes, and counts the folders left unwatched.
func (wt *watcher) addTree(id int64, dir string) {
	_ = filepath.WalkDir(dir, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			if p == dir {
				return err
			}
			return nil // unreadable subfolder: the scheduled scan reports it
		}
		if !d.IsDir() {
			return nil
		}
		if p != dir && skipDir(wt.roots[id], p) {
			return filepath.SkipDir
		}
		if _, ok := wt.dirs[p]; ok {
			return nil
		}
		if wt.blocked {
			wt.unwatched[id]++
			return nil
		}
		cost := 1
		if watchesEntries {
			entries, _ := os.ReadDir(p)
			cost += len(entries)
		}
		if cost > wt.budget {
			wt.block(errOpenFiles)
			wt.unwatched[id]++
			return nil
		}
		if err := wt.w.Add(p); err != nil {
			if errors.Is(err, syscall.ENOSPC) || errors.Is(err, syscall.EMFILE) || errors.Is(err, syscall.ENFILE) {
				wt.block(err)
				wt.unwatched[id]++
			}
			return nil // unreadable folder: the scheduled scan still covers it
		}
		wt.dirs[p] = watched{library: id, cost: cost}
		wt.budget -= cost
		return nil
	})
}

// errOpenFiles is the watch budget running out before the system says so.
var errOpenFiles = errors.New("open file limit")

func (wt *watcher) block(err error) {
	wt.blocked = true
	if wt.warned {
		return
	}
	wt.warned = true
	hint := ""
	switch {
	case errors.Is(err, syscall.ENOSPC):
		hint = "raise fs.inotify.max_user_watches on the host"
	case errors.Is(err, errOpenFiles), errors.Is(err, syscall.EMFILE), errors.Is(err, syscall.ENFILE):
		hint = "raise the open-file limits (ulimit -n; kern.maxfiles on macOS)"
	}
	wt.s.log.Warn("cannot watch more folders; changes in the rest are found by the scheduled scan",
		"watched", len(wt.dirs), "err", err, "hint", hint, "interval", wt.s.Interval())
}

// forget drops the watches on p and every folder below it, if p was a
// watched folder.
func (wt *watcher) forget(p string) {
	if _, ok := wt.dirs[p]; !ok {
		return
	}
	libs := map[int64]bool{}
	for d, w := range wt.dirs {
		if d == p || within(p, d) {
			_ = wt.w.Remove(d) // already gone when the folder was deleted
			wt.budget += w.cost
			delete(wt.dirs, d)
			libs[w.library] = true
		}
	}
	wt.blocked = false
	for id := range libs {
		wt.publish(id)
	}
}

// publish stores the library's watch coverage for the API.
func (wt *watcher) publish(id int64) WatchStatus {
	st := WatchStatus{State: "watching", Unwatched: wt.unwatched[id]}
	for _, w := range wt.dirs {
		if w.library == id {
			st.Folders++
		}
	}
	switch {
	case st.Unwatched > 0:
		st.State = "partial"
	case st.Folders == 0:
		st.State = "off"
	}
	wt.s.setWatch(id, st)
	return st
}
