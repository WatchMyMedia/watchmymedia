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

// RetryBusy runs write, and when it fails because the database is locked
// (IsBusy) runs it again, for as long as it keeps failing that way, until
// wait has passed since the first time it was refused or ctx is done.
// Every statement already waits out the busy timeout for another writer;
// this is for work nobody is waiting for, such as matching a library's
// files, where a write that still finds the database locked should be
// tried again rather than given up.
//
// wait counts from the first refusal, not from the start: how long a try
// takes is the connection's busy timeout and the machine's doing (a 10 ms
// timeout took 61 ms on CI's Windows runner), and is no part of how long
// to go on trying afterwards. So with any wait above zero a refused write
// is tried at least once more, and the last try may end after the wait
// has passed. With no wait it is tried once.
//
// write must be safe to run again: a transaction that failed has been
// rolled back, so one that sets nothing outside itself but what it sets
// each time is. It returns the last error, or ctx's when ctx ended first.
func RetryBusy(ctx context.Context, wait time.Duration, write func() error) error {
	var refused time.Time // when write first came back busy
	pause := busyPause
	for {
		err := write()
		if !IsBusy(err) {
			return err
		}
		if refused.IsZero() {
			refused = time.Now()
		}
		left := wait - time.Since(refused)
		if left <= 0 {
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
