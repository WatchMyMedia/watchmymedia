package scanner

import "golang.org/x/sys/unix"

// watchHoldsFile is set where each watched folder holds an open file
// (kqueue), and watchesEntries where every entry in it holds one too, as
// fsnotify's kqueue backend opens them all. macOS uses folderWatcher, which
// opens the folders alone.
const watchHoldsFile, watchesEntries = true, false

func newNotifier() (notifier, error) { return newFolderWatcher() }

// systemFiles is the open-file table every process on the system shares.
func systemFiles() (int, bool) {
	n, err := unix.SysctlUint32("kern.maxfiles")
	return int(n), err == nil && n > 0
}
