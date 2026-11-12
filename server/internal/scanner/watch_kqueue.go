//go:build dragonfly || freebsd || netbsd || openbsd

package scanner

import "golang.org/x/sys/unix"

// watchHoldsFile is set where each watched folder holds an open file
// (kqueue), and watchesEntries where every entry in it holds one too, as
// fsnotify's kqueue backend opens them all.
const watchHoldsFile, watchesEntries = true, true

func newNotifier() (notifier, error) { return newFsnotifier() }

// systemFiles is the open-file table every process on the system shares.
func systemFiles() (int, bool) {
	n, err := unix.SysctlUint32("kern.maxfiles")
	return int(n), err == nil && n > 0
}
