package bgwork

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"
)

func TestBackgroundPassRestartsAfterForegroundWorkAndCancellationJoins(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		var busy atomic.Bool
		gate := Gate{Busy: busy.Load, Poll: time.Second}
		ctx, cancel := context.WithCancel(t.Context())
		started := make(chan struct{}, 2)
		joined := make(chan struct{}, 2)
		done := make(chan error, 1)
		go func() {
			done <- gate.Run(ctx, func(ctx context.Context) error {
				started <- struct{}{}
				<-ctx.Done()
				joined <- struct{}{}
				return ctx.Err()
			})
		}()
		<-started
		busy.Store(true)
		time.Sleep(time.Second)
		<-joined
		synctest.Wait()
		select {
		case <-started:
			t.Fatal("started while busy")
		default:
		}
		busy.Store(false)
		time.Sleep(time.Second)
		<-started
		cancel()
		if e := <-done; !errors.Is(e, context.Canceled) {
			t.Fatal(e)
		}
		<-joined
	})
}
func TestDiskFloorDelaysOptionalWork(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		var free atomic.Uint64
		free.Store(5)
		gate := Gate{Dir: "fixture", MinFree: 10, Free: func(string) (uint64, error) { return free.Load(), nil }}
		done := make(chan error, 1)
		go func() { done <- gate.Wait(t.Context()) }()
		synctest.Wait()
		select {
		case <-done:
			t.Fatal("ignored disk floor")
		default:
		}
		free.Store(10)
		time.Sleep(time.Second)
		if err := <-done; err != nil {
			t.Fatal(err)
		}
	})
}
