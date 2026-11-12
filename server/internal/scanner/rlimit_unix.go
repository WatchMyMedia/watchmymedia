//go:build unix

package scanner

import (
	"log/slog"
	"math"
	"syscall"
)

// fileReserve is the open files kept free of watches, for the database,
// connections, ffmpeg and the files it writes.
const fileReserve = 4096

// systemShare is the most of the system's open files (kern.maxfiles) that
// watches take: the table is shared by every process, and a full one fails
// whatever opens a file next, anywhere on the system. A process may hold
// half of it, which a large library watched by fsnotify's kqueue came near.
const systemShare = 8 // an eighth

// watchBudget raises the open-file limit as far as allowed and returns how
// much watches may use. With kqueue every watched folder holds an open file
// (see watchHoldsFile). inotify takes one descriptor however many folders
// it watches, so only the system refusing a watch limits it.
func watchBudget(log *slog.Logger) int {
	limit := raiseFileLimit(log)
	if !watchHoldsFile {
		return math.MaxInt
	}
	budget := max(int(min(limit, 1<<30))-fileReserve, 0)
	if total, ok := systemFiles(); ok {
		budget = min(budget, total/systemShare)
	}
	return budget
}

// raiseFileLimit lifts the soft open-file limit to the hard limit and
// returns the soft limit in effect. Go raises it at startup already, on
// macOS only as far as kern.maxfilesperproc, where setrlimit refuses more.
func raiseFileLimit(log *slog.Logger) uint64 {
	var rl syscall.Rlimit
	if err := syscall.Getrlimit(syscall.RLIMIT_NOFILE, &rl); err != nil {
		return 0
	}
	if rl.Cur < rl.Max {
		want := rl
		want.Cur = rl.Max
		if err := syscall.Setrlimit(syscall.RLIMIT_NOFILE, &want); err == nil {
			return want.Cur
		}
		log.Debug("could not raise open file limit", "have", rl.Cur, "max", rl.Max)
	}
	return rl.Cur
}
