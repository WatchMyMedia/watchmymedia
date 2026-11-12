package server

import (
	"os"
	"sync"
)

// Rotate through a pipe writer, rather than handing the child a log handle:
// Windows cannot replace a log still held open by the child.
type rotatingLog struct {
	mu          sync.Mutex
	file        *os.File
	path        string
	size, limit int64
}

func newLog(path string, limit int64) (*rotatingLog, error) {
	l := &rotatingLog{path: path, limit: limit}
	e := l.open()
	return l, e
}
func (l *rotatingLog) open() error {
	f, e := os.OpenFile(l.path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
	if e != nil {
		return e
	}
	l.file = f
	if s, e := f.Stat(); e == nil {
		l.size = s.Size()
	}
	return nil
}
func (l *rotatingLog) Write(b []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.size >= l.limit {
		if e := l.file.Close(); e != nil {
			return 0, e
		}
		_ = os.Remove(l.path + ".1")
		if e := os.Rename(l.path, l.path+".1"); e != nil {
			_ = l.open()
			return 0, e
		}
		if e := l.open(); e != nil {
			return 0, e
		}
	}
	n, e := l.file.Write(b)
	l.size += int64(n)
	return n, e
}
func (l *rotatingLog) Close() error { l.mu.Lock(); defer l.mu.Unlock(); return l.file.Close() }
