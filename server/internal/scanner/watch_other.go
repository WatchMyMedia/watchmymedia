//go:build !(darwin || dragonfly || freebsd || netbsd || openbsd)

package scanner

// watchHoldsFile is set where each watched folder holds an open file
// (kqueue), and watchesEntries where every entry in it holds one too, as
// fsnotify's kqueue backend opens them all.
const watchHoldsFile, watchesEntries = false, false

func newNotifier() (notifier, error) { return newFsnotifier() }
