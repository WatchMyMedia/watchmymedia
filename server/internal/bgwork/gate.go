// Package bgwork coordinates optional disk and CPU work with foreground plays.
package bgwork

import (
	"context"
	"errors"
	"time"
)

var ErrBusy = errors.New("foreground work started")

// Gate keeps optional work behind foreground playback and a free-space floor.
// Free and Pause are hooks for deterministic checks and an operator status.
// Wait is used by one worker at a time; Played may be supplied through Busy.
type Gate struct {
	Busy    func() bool
	Dir     string
	MinFree uint64
	Free    func(string) (uint64, error)
	Poll    time.Duration
	Pause   func(string, uint64)
	Check   func() error
}

func (g Gate) blocked() (string, uint64, error) {
	if g.Check != nil {
		if err := g.Check(); err != nil {
			return "", 0, err
		}
	}
	if g.Busy != nil && g.Busy() {
		return "playing", 0, nil
	}
	free := g.Free
	if free == nil {
		free = FreeBytes
	}
	if g.Dir != "" && g.MinFree > 0 {
		n, err := free(g.Dir)
		if err != nil {
			return "", 0, err
		}
		if n < g.MinFree {
			return "lowDisk", n, nil
		}
	}
	return "", 0, nil
}
func (g Gate) Wait(ctx context.Context) error {
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		reason, free, err := g.blocked()
		if err != nil {
			return err
		}
		if g.Pause != nil {
			g.Pause(reason, free)
		}
		if reason == "" {
			return nil
		}
		poll := g.Poll
		if poll <= 0 {
			poll = time.Second
		}
		if err = Sleep(ctx, poll); err != nil {
			return err
		}
	}
}

// Run restarts an uncommitted analysis after foreground work interrupts it.
// Its function must publish results only after a successful full pass.
func (g Gate) Run(ctx context.Context, fn func(context.Context) error) error {
	type outcome struct{ err error }
	for {
		if err := g.Wait(ctx); err != nil {
			return err
		}
		run, cancel := context.WithCancelCause(ctx)
		finished := make(chan outcome, 1)
		go func() { finished <- outcome{fn(run)} }()
		timer := time.NewTicker(time.Second)
		var err error
	wait:
		for {
			select {
			case result := <-finished:
				err = result.err
				break wait
			case <-ctx.Done():
				cancel(ctx.Err())
				<-finished
				err = ctx.Err()
				break wait
			case <-timer.C:
				reason, _, checkErr := g.blocked()
				if reason != "" || checkErr != nil {
					cancel(ErrBusy)
					<-finished
					err = ErrBusy
					break wait
				}
			}
		}
		timer.Stop()
		cancel(nil)
		if errors.Is(err, ErrBusy) {
			continue
		}
		return err
	}
}
func Sleep(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}
