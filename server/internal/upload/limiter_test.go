package upload

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

func TestAggregateRangeAndConcurrentStreams(t *testing.T) {
	l := New(2_000_000, true)
	const streams, size = 4, 80 << 10
	start := time.Now()
	var wg sync.WaitGroup
	for i := range streams {
		wg.Go(func() {
			w, done := l.Wrap(httptest.NewRecorder(), context.Background(), string(rune('a'+i)), false)
			defer done()
			// io.Copy exercises ReadFrom: a sendfile path must not bypass pacing.
			n, err := io.Copy(w, bytes.NewReader(bytes.Repeat([]byte{'x'}, size)))
			if err != nil || n != size {
				t.Errorf("copy = %d, %v", n, err)
			}
		})
	}
	wg.Wait()
	s := l.Snapshot()
	if s.DeliveredBytes != streams*size || s.PacedBytes != streams*size || s.ActiveViews != 0 {
		t.Fatalf("incorrect aggregate accounting: %+v", s)
	}
	// Four independent connections cannot each receive an aggregate burst.
	if elapsed := time.Since(start); elapsed < 900*time.Millisecond {
		t.Fatalf("aggregate ceiling bypassed in %s", elapsed)
	}
}

func TestBudgetChangeWakesBlockedTransfer(t *testing.T) {
	l := New(80_000, true)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	w, done := l.Wrap(httptest.NewRecorder(), ctx, "viewer", false)
	defer done()
	finished := make(chan error, 1)
	go func() { _, err := w.Write(bytes.Repeat([]byte{'x'}, 64<<10)); finished <- err }()
	time.Sleep(80 * time.Millisecond)
	select {
	case err := <-finished:
		t.Fatalf("slow transfer finished before update: %v", err)
	default:
	}
	l.Update(8_000_000, true)
	select {
	case err := <-finished:
		if err != nil {
			t.Fatal(err)
		}
	case <-ctx.Done():
		t.Fatal("budget update did not wake transfer")
	}
	if l.Snapshot().Generation < 2 {
		t.Fatal("budget change lost")
	}
}

func TestCancelWhileWaiting(t *testing.T) {
	l := New(8_000, true)
	ctx, cancel := context.WithCancel(context.Background())
	w, done := l.Wrap(httptest.NewRecorder(), ctx, "viewer", false)
	finished := make(chan error, 1)
	go func() { _, err := w.Write(bytes.Repeat([]byte{'x'}, 64<<10)); finished <- err }()
	time.Sleep(30 * time.Millisecond)
	cancel()
	select {
	case err := <-finished:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("write error = %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("canceled transfer remained blocked")
	}
	done()
	if l.Snapshot().ActiveViews != 0 {
		t.Fatal("canceled flow leaked")
	}
}

func TestDownloadYieldsAndObserveOnly(t *testing.T) {
	l := New(1_000_000, false)
	w, done := l.Wrap(httptest.NewRecorder(), context.Background(), "viewer", false)
	_, _ = w.Write(bytes.Repeat([]byte{'x'}, 64<<10))
	done()
	if s := l.Snapshot(); s.DeliveredBytes != 64<<10 || s.PacedBytes != 0 {
		t.Fatalf("observe-only = %+v", s)
	}
	l.Update(1_000_000, true)
	view, viewDone := l.Wrap(httptest.NewRecorder(), context.Background(), "viewer", false)
	defer viewDone()
	_, downloadDone := l.Wrap(httptest.NewRecorder(), context.Background(), "download", true)
	defer downloadDone()
	l.mu.Lock()
	if got := l.flowRate(l.flows["download"]); got != 100_000 {
		t.Errorf("download share = %d", got)
	}
	if got := l.flowRate(l.flows["viewer"]); got != 900_000 {
		t.Errorf("view share = %d", got)
	}
	l.mu.Unlock()
	_, _ = view.Write([]byte("ok"))
}

func TestDownloadTransferYieldsToViewer(t *testing.T) {
	l := New(1_000_000, true)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	view, viewDone := l.Wrap(httptest.NewRecorder(), ctx, "foreground", false)
	defer viewDone()
	background, downloadDone := l.Wrap(httptest.NewRecorder(), ctx, "offline", true)
	defer downloadDone()
	finished := make(chan error, 1)
	go func() { _, err := background.Write(bytes.Repeat([]byte{'d'}, 64<<10)); finished <- err }()
	if n, err := view.Write(bytes.Repeat([]byte{'v'}, 64<<10)); err != nil || n != 64<<10 {
		t.Fatalf("foreground write = %d, %v", n, err)
	}
	select {
	case err := <-finished:
		t.Fatalf("background finished before foreground: %v", err)
	default:
	}
	cancel()
	select {
	case err := <-finished:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("background cancellation = %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("background did not cancel")
	}
}

func TestReservationsDoNotDisplaceExistingStreams(t *testing.T) {
	l := New(12_000_000, true)
	undoA, err := l.Reserve("viewer-a", 7_000_000)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := l.Reserve("viewer-b", 6_000_000); !errors.Is(err, ErrLimit) {
		t.Fatalf("over-budget start = %v", err)
	}
	if got := l.Snapshot().ReservedBps; got != 7_000_000 {
		t.Fatalf("refusal changed existing reservation: %d", got)
	}
	undoB, err := l.Reserve("viewer-a", 9_000_000)
	if err != nil {
		t.Fatal(err)
	}
	undoA() // A failed older request must not undo a newer replacement.
	if got := l.Snapshot().ReservedBps; got != 9_000_000 {
		t.Fatalf("stale undo = %d", got)
	}
	undoB()
	if got := l.Snapshot().ReservedBps; got != 7_000_000 {
		t.Fatalf("replacement undo = %d", got)
	}
	l.Release("viewer-a")
	if _, err := l.Reserve("viewer-b", 6_000_000); err != nil {
		t.Fatal(err)
	}
	if got := l.Available("viewer-b"); got != 12_000_000 {
		t.Fatalf("own capacity = %d", got)
	}
}
