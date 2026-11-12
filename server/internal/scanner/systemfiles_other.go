//go:build unix && !(darwin || dragonfly || freebsd || netbsd || openbsd)

package scanner

// systemFiles is the open-file table every process on the system shares;
// inotify takes no open file per watch, so it is not looked up. Only Unix
// systems ask (rlimit_unix.go).
func systemFiles() (int, bool) { return 0, false }
