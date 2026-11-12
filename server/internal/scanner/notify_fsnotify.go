//go:build !darwin

package scanner

import "github.com/fsnotify/fsnotify"

// fsnotifier is fsnotify behind the notifier interface.
type fsnotifier struct{ w *fsnotify.Watcher }

func newFsnotifier() (notifier, error) {
	w, err := fsnotify.NewWatcher()
	if err != nil {
		return nil, err
	}
	return fsnotifier{w}, nil
}

func (n fsnotifier) Add(dir string) error          { return n.w.Add(dir) }
func (n fsnotifier) Remove(dir string) error       { return n.w.Remove(dir) }
func (n fsnotifier) Close() error                  { return n.w.Close() }
func (n fsnotifier) Events() <-chan fsnotify.Event { return n.w.Events }
func (n fsnotifier) Errors() <-chan error          { return n.w.Errors }
