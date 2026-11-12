package playback

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"
)

func TestRenditionCacheFirstViewerMayCancelWithoutStoppingPeer(t *testing.T) {
	c := newRenditionCache(1024, 1, 2, time.Minute)
	t.Cleanup(c.close)
	started := make(chan struct{})
	finish := make(chan struct{})
	var produces atomic.Int32
	produce := func(ctx context.Context) ([]byte, error) {
		produces.Add(1)
		close(started)
		select {
		case <-finish:
			return []byte("complete media"), nil
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	validate := func(b []byte) error { return nil }
	firstCtx, cancelFirst := context.WithCancel(context.Background())
	first := make(chan error, 1)
	go func() {
		_, release, err := c.get(firstCtx, context.Background(), "same-rung/0", produce, validate)
		if release != nil {
			release()
		}
		first <- err
	}()
	<-started
	second := make(chan error, 1)
	go func() {
		b, release, err := c.get(context.Background(), context.Background(), "same-rung/0", produce, validate)
		if err == nil && string(b) != "complete media" {
			err = errors.New("peer received wrong media")
		}
		if release != nil {
			release()
		}
		second <- err
	}()
	deadline := time.After(2 * time.Second)
	for {
		_, _, _, followers := c.stats()
		if followers == 2 {
			break
		}
		select {
		case <-deadline:
			t.Fatal("second viewer did not join the producer")
		default:
			time.Sleep(time.Millisecond)
		}
	}
	cancelFirst()
	if err := <-first; !errors.Is(err, context.Canceled) {
		t.Fatalf("first viewer: %v", err)
	}
	close(finish)
	if err := <-second; err != nil {
		t.Fatal(err)
	}
	if got := produces.Load(); got != 1 {
		t.Fatalf("producers = %d, want 1", got)
	}
}

func TestRenditionCacheRejectsBadMediaAndRetries(t *testing.T) {
	c := newRenditionCache(1024, 1, 2, time.Minute)
	t.Cleanup(c.close)
	var calls int
	produce := func(context.Context) ([]byte, error) {
		calls++
		if calls == 1 {
			return []byte("partial"), nil
		}
		return []byte("complete"), nil
	}
	validate := func(b []byte) error {
		if string(b) != "complete" {
			return errors.New("truncated fragment")
		}
		return nil
	}
	if _, _, err := c.get(context.Background(), context.Background(), "rung/0", produce, validate); err == nil {
		t.Fatal("accepted partial media")
	}
	if bytes, entries, _, _ := c.stats(); bytes != 0 || entries != 0 {
		t.Fatalf("failure poisoned cache: %d bytes, %d entries", bytes, entries)
	}
	b, release, err := c.get(context.Background(), context.Background(), "rung/0", produce, validate)
	if err != nil || string(b) != "complete" {
		t.Fatalf("retry: %q, %v", b, err)
	}
	release()
	if calls != 2 {
		t.Fatalf("producer calls = %d, want 2", calls)
	}
}

func TestRenditionCachePinsAndByteBudget(t *testing.T) {
	c := newRenditionCache(4, 2, 3, time.Minute)
	t.Cleanup(c.close)
	get := func(key string) ([]byte, func(), error) {
		return c.get(context.Background(), context.Background(), key,
			func(context.Context) ([]byte, error) { return []byte(key), nil },
			func([]byte) error { return nil })
	}
	_, releaseA, err := get("aaaa")
	if err != nil {
		t.Fatal(err)
	}
	b, releaseB, err := get("bbbb")
	if !errors.Is(err, ErrRenditionBusy) || b != nil || releaseB != nil {
		t.Fatalf("pinned full cache must refuse unaccounted bytes: %q, %v", b, err)
	}
	if bytes, entries, _, _ := c.stats(); bytes != 4 || entries != 1 {
		t.Fatalf("pinned budget: %d bytes, %d entries", bytes, entries)
	}
	releaseA()
	_, releaseC, err := get("cccc")
	if err != nil {
		t.Fatal(err)
	}
	releaseC()
	if bytes, entries, _, _ := c.stats(); bytes != 4 || entries != 1 {
		t.Fatalf("eviction budget: %d bytes, %d entries", bytes, entries)
	}
	c.sweep(time.Now().Add(2 * time.Minute))
	if bytes, entries, _, _ := c.stats(); bytes != 0 || entries != 0 {
		t.Fatalf("expired cache: %d bytes, %d entries", bytes, entries)
	}
}

func TestRenditionCacheBoundsReadyReadersAndAllowsRetry(t *testing.T) {
	c := newRenditionCache(1024, 2, 2, time.Minute)
	t.Cleanup(c.close)
	calls := 0
	get := func(key string) ([]byte, func(), error) {
		return c.get(t.Context(), t.Context(), key, func(context.Context) ([]byte, error) {
			calls++
			return []byte("media"), nil
		}, func([]byte) error { return nil })
	}
	_, first, err := get("same")
	if err != nil {
		t.Fatal(err)
	}
	_, second, err := get("same")
	if err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"same", "cold"} {
		if _, release, err := get(key); !errors.Is(err, ErrRenditionBusy) {
			if release != nil {
				release()
			}
			t.Fatalf("%s bypassed the reader limit: %v", key, err)
		}
	}
	if calls != 1 {
		t.Fatalf("rejected reader started a producer: %d calls", calls)
	}
	first()
	first() // Duplicate cleanup cannot open another slot.
	_, third, err := get("same")
	if err != nil {
		t.Fatalf("reader could not retry after a pin released: %v", err)
	}
	if _, release, err := get("same"); !errors.Is(err, ErrRenditionBusy) {
		if release != nil {
			release()
		}
		t.Fatalf("duplicate release over-admitted readers: %v", err)
	}
	second()
	third()
	c.sweep(time.Now().Add(2 * time.Minute))
	if bytes, entries, flights, followers := c.stats(); bytes != 0 || entries != 0 || flights != 0 || followers != 0 {
		t.Fatalf("reader cleanup leaked cache state: %d/%d/%d/%d", bytes, entries, flights, followers)
	}
}

func TestRenditionCacheRejectsOversizedMedia(t *testing.T) {
	c := newRenditionCache(4, 1, 1, time.Minute)
	t.Cleanup(c.close)
	_, _, err := c.get(context.Background(), context.Background(), "too-large",
		func(context.Context) ([]byte, error) { return []byte("12345"), nil },
		func([]byte) error { return nil })
	if !errors.Is(err, ErrStorageBudget) {
		t.Fatalf("oversized rendition: %v", err)
	}
	if bytes, entries, _, _ := c.stats(); bytes != 0 || entries != 0 {
		t.Fatalf("oversized media retained: %d bytes, %d entries", bytes, entries)
	}
}

func TestRenditionCacheBoundsFlightsAndFollowers(t *testing.T) {
	c := newRenditionCache(1024, 1, 1, time.Minute)
	t.Cleanup(c.close)
	started := make(chan struct{})
	finish := make(chan struct{})
	producer := func(ctx context.Context) ([]byte, error) {
		close(started)
		select {
		case <-finish:
			return []byte("media"), nil
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	first := make(chan error, 1)
	go func() {
		_, release, err := c.get(context.Background(), context.Background(), "one", producer, func([]byte) error { return nil })
		if release != nil {
			release()
		}
		first <- err
	}()
	<-started
	for _, key := range []string{"one", "two"} {
		_, _, err := c.get(context.Background(), context.Background(), key, producer, func([]byte) error { return nil })
		if !errors.Is(err, ErrRenditionBusy) {
			t.Fatalf("%s: %v, want busy", key, err)
		}
	}
	close(finish)
	if err := <-first; err != nil {
		t.Fatal(err)
	}
}
