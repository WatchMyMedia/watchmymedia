package db

import (
	"context"
	"time"
)

// The pause between tries of a write the database was too busy for: short
// at first, since most writers are done in milliseconds, then longer.
const (
	busyPause    = 50 * time.Millisecond
	busyPauseMax = time.Second
)

// RetryBusy runs write, and runs it again for as long as it fails because
// the database is locked (IsBusy), until wait has passed since it first
// did or ctx is done. Every statement already waits out the busy timeout
// for another writer; this is for work nobody is waiting for, such as
// matching a library's files, where a write that still finds the database
// locked should be tried again rather than given up.
//
// write must be safe to run again: a transaction that failed has been
// rolled back, so one that sets nothing outside itself but what it sets
// each time is. It returns the last error, or ctx's when ctx ended first.
func RetryBusy(ctx context.Context, wait time.Duration, write func() error) error {
	begin := time.Now()
	pause := busyPause
	for {
		err := write()
		left := wait - time.Since(begin)
		if !IsBusy(err) || left <= 0 {
			return err
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(min(pause, left)):
		}
		pause = min(2*pause, busyPauseMax)
	}
}
