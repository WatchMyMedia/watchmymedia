package scanner

import "github.com/fsnotify/fsnotify"

// notifier is the system's file change notification: fsnotify, or on macOS
// kqueue on folders alone (see folderWatcher). Events name the path that
// changed, as fsnotify's do.
type notifier interface {
	Add(dir string) error
	Remove(dir string) error
	Close() error
	Events() <-chan fsnotify.Event
	Errors() <-chan error
}
