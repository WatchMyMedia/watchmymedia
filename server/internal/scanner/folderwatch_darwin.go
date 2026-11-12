package scanner

import (
	"errors"
	"os"
	"path/filepath"
	"slices"
	"sync"
	"syscall"

	"github.com/fsnotify/fsnotify"
	"golang.org/x/sys/unix"
)

// folderWatcher watches folders with kqueue, and not the files in them.
// kqueue holds an open file for every watch, and fsnotify watches every
// entry of a folder to mimic inotify, so a library held one for each of its
// files and folders: tens of thousands, taken from the table every process
// on the Mac shares (kern.maxfiles). A folder's NOTE_WRITE says its entries
// changed, and comparing the listing kept from before says which. Writes
// into a file that stays where it is go unseen; files still being copied
// are waited out by the scanner, and the scheduled scan finds the rest.
type folderWatcher struct {
	kq     int
	wake   [2]int // closing wake[1] stops the reader
	events chan fsnotify.Event
	errors chan error
	done   chan struct{}

	mu      sync.Mutex
	closed  bool
	byFD    map[int]*folder
	byPath  map[string]*folder
	stopped chan struct{} // closed once the reader has let go of kq
}

// folder is one watched folder and its entries when last listed.
type folder struct {
	fd      int
	path    string
	dev     int32
	ino     uint64
	entries map[string]stamp
}

// stamp tells one file from another under the same name: a file replaced
// by a move or a copy differs in at least one of these. Folders compare by
// inode alone, as their size and date change with what is inside.
type stamp struct {
	dir   bool
	ino   uint64
	size  int64
	mtime int64
}

const folderNotes = unix.NOTE_WRITE | unix.NOTE_DELETE | unix.NOTE_RENAME | unix.NOTE_REVOKE

func newFolderWatcher() (*folderWatcher, error) {
	kq, err := unix.Kqueue()
	if err != nil {
		return nil, os.NewSyscallError("kqueue", err)
	}
	unix.CloseOnExec(kq)
	w := &folderWatcher{kq: kq, events: make(chan fsnotify.Event), errors: make(chan error), done: make(chan struct{}),
		byFD: map[int]*folder{}, byPath: map[string]*folder{}, stopped: make(chan struct{})}
	if err := unix.Pipe(w.wake[:]); err != nil {
		_ = unix.Close(kq)
		return nil, os.NewSyscallError("pipe", err)
	}
	unix.CloseOnExec(w.wake[0])
	unix.CloseOnExec(w.wake[1])
	var ev unix.Kevent_t
	unix.SetKevent(&ev, w.wake[0], unix.EVFILT_READ, unix.EV_ADD|unix.EV_ENABLE|unix.EV_ONESHOT)
	if _, err := unix.Kevent(kq, []unix.Kevent_t{ev}, nil, nil); err != nil {
		_ = unix.Close(kq)
		_ = unix.Close(w.wake[0])
		_ = unix.Close(w.wake[1])
		return nil, os.NewSyscallError("kevent", err)
	}
	go w.read()
	return w, nil
}

func (w *folderWatcher) Events() <-chan fsnotify.Event { return w.events }
func (w *folderWatcher) Errors() <-chan error          { return w.errors }

// Add watches dir. It is registered before it is listed, so a change in
// between shows in the listing or raises an event, or both.
func (w *folderWatcher) Add(dir string) error {
	dir = filepath.Clean(dir)
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.closed {
		return fsnotify.ErrClosed
	}
	if _, ok := w.byPath[dir]; ok {
		return nil
	}
	fd, err := ignoringEINTR(func() (int, error) {
		return unix.Open(dir, unix.O_EVTONLY|unix.O_DIRECTORY|unix.O_CLOEXEC, 0)
	})
	if err != nil {
		return &os.PathError{Op: "open", Path: dir, Err: err}
	}
	var st unix.Stat_t
	if err := unix.Fstat(fd, &st); err != nil {
		_ = unix.Close(fd)
		return &os.PathError{Op: "fstat", Path: dir, Err: err}
	}
	var ev unix.Kevent_t
	unix.SetKevent(&ev, fd, unix.EVFILT_VNODE, unix.EV_ADD|unix.EV_CLEAR|unix.EV_ENABLE)
	ev.Fflags = folderNotes
	if _, err := unix.Kevent(w.kq, []unix.Kevent_t{ev}, nil, nil); err != nil {
		_ = unix.Close(fd)
		return &os.PathError{Op: "kevent", Path: dir, Err: err}
	}
	f := &folder{fd: fd, path: dir, dev: st.Dev, ino: st.Ino}
	f.entries, _ = listFolder(dir)
	w.byFD[fd] = f
	w.byPath[dir] = f
	return nil
}

// Remove stops watching dir; closing its file drops its kqueue filter.
func (w *folderWatcher) Remove(dir string) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	f, ok := w.byPath[filepath.Clean(dir)]
	if !ok {
		return fsnotify.ErrNonExistentWatch
	}
	w.drop(f)
	return nil
}

func (w *folderWatcher) drop(f *folder) {
	delete(w.byFD, f.fd)
	delete(w.byPath, f.path)
	_ = unix.Close(f.fd)
}

// Close stops the watcher and closes every watched folder.
func (w *folderWatcher) Close() error {
	w.mu.Lock()
	if w.closed {
		w.mu.Unlock()
		return nil
	}
	w.closed = true
	for _, f := range w.byFD {
		w.drop(f)
	}
	w.mu.Unlock()
	close(w.done)
	_ = unix.Close(w.wake[1])
	<-w.stopped
	return nil
}

func (w *folderWatcher) read() {
	defer func() {
		_ = unix.Close(w.kq)
		_ = unix.Close(w.wake[0])
		close(w.stopped)
	}()
	buf := make([]unix.Kevent_t, 64)
	for {
		n, err := unix.Kevent(w.kq, nil, buf, nil)
		if errors.Is(err, unix.EINTR) {
			continue
		}
		if err != nil {
			if !w.sendError(os.NewSyscallError("kevent", err)) {
				return
			}
			continue
		}
		for _, kev := range buf[:n] {
			fd := int(kev.Ident)
			if fd == w.wake[0] {
				return
			}
			for _, ev := range w.changed(fd, kev.Fflags) {
				if !w.send(ev) {
					return
				}
			}
		}
	}
}

// changed turns a kqueue event on a folder into events naming what
// changed. It runs under the lock, and the events are sent after it is
// released, as the receiver may call Add or Remove before it reads on.
func (w *folderWatcher) changed(fd int, notes uint32) []fsnotify.Event {
	w.mu.Lock()
	defer w.mu.Unlock()
	f := w.byFD[fd]
	if f == nil {
		return nil // removed since: its events went with it
	}
	if notes&(unix.NOTE_DELETE|unix.NOTE_RENAME|unix.NOTE_REVOKE) != 0 && !f.stillThere() {
		// The folder itself went away (or its drive did). Its parent's
		// listing says so too, but a library's root has no watched parent.
		w.drop(f)
		op := fsnotify.Remove
		if notes&unix.NOTE_RENAME != 0 {
			op = fsnotify.Rename
		}
		return []fsnotify.Event{{Name: f.path, Op: op}}
	}
	if notes&unix.NOTE_WRITE == 0 {
		return nil
	}
	return f.relist()
}

// stillThere reports whether the folder's path still leads to it. A file
// number closed and reused for another folder can carry an event meant for
// the old one; the path says which is true.
func (f *folder) stillThere() bool {
	var st unix.Stat_t
	return unix.Lstat(f.path, &st) == nil && st.Dev == f.dev && st.Ino == f.ino
}

// relist lists the folder again and returns what was added, removed or
// replaced since the last listing: removals first, each in name order.
func (f *folder) relist() []fsnotify.Event {
	now, ok := listFolder(f.path)
	if !ok {
		return nil // gone: its own event or its parent's listing says so
	}
	was := f.entries
	f.entries = now
	var removed, created, written []string
	for name, old := range was {
		cur, ok := now[name]
		switch {
		case !ok:
			removed = append(removed, name)
		case cur == old:
		case cur.dir != old.dir || cur.dir:
			// Another folder, or a folder in place of a file: the old one's
			// watches go and the new one is read whole.
			removed = append(removed, name)
			created = append(created, name)
		default:
			written = append(written, name)
		}
	}
	for name := range now {
		if _, ok := was[name]; !ok {
			created = append(created, name)
		}
	}
	var out []fsnotify.Event
	for _, set := range []struct {
		names []string
		op    fsnotify.Op
	}{{removed, fsnotify.Remove}, {created, fsnotify.Create}, {written, fsnotify.Write}} {
		slices.Sort(set.names)
		for _, name := range set.names {
			out = append(out, fsnotify.Event{Name: filepath.Join(f.path, name), Op: set.op})
		}
	}
	return out
}

// listFolder reads a folder's entries. Entries that vanish while it reads are
// left out.
func listFolder(dir string) (map[string]stamp, bool) {
	des, err := os.ReadDir(dir)
	if err != nil {
		return map[string]stamp{}, false
	}
	out := make(map[string]stamp, len(des))
	for _, de := range des {
		fi, err := de.Info()
		if err != nil {
			continue
		}
		e := stamp{dir: fi.IsDir()}
		if st, ok := fi.Sys().(*syscall.Stat_t); ok {
			e.ino = st.Ino
		}
		if !e.dir {
			e.size, e.mtime = fi.Size(), fi.ModTime().UnixNano()
		}
		out[de.Name()] = e
	}
	return out, true
}

func (w *folderWatcher) send(ev fsnotify.Event) bool {
	select {
	case w.events <- ev:
		return true
	case <-w.done:
		return false
	}
}

func (w *folderWatcher) sendError(err error) bool {
	select {
	case w.errors <- err:
		return true
	case <-w.done:
		return false
	}
}

func ignoringEINTR(fn func() (int, error)) (int, error) {
	for {
		n, err := fn()
		if !errors.Is(err, unix.EINTR) {
			return n, err
		}
	}
}
