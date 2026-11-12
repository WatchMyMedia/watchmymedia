package memo

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"
)

func TestMapExpiresAndStaysSmall(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m := NewMap[string, int](time.Minute, 3)
		m.Set("a", 1)
		if v, ok := m.Get("a"); !ok || v != 1 {
			t.Fatalf("a = %d %v", v, ok)
		}
		time.Sleep(time.Minute)
		if _, ok := m.Get("a"); ok {
			t.Error("a outlived its minute")
		}

		for i, k := range []string{"a", "b", "c", "d"} {
			m.Set(k, i)
		}
		if _, ok := m.Get("a"); ok || m.Len() != 3 {
			t.Errorf("the oldest stayed past the size: len %d", m.Len())
		}
		m.Set("b", 10) // setting again makes it the newest
		m.Set("e", 4)
		if _, ok := m.Get("c"); ok {
			t.Error("c should have gone before b")
		}
		if v, _ := m.Get("b"); v != 10 {
			t.Errorf("b = %d", v)
		}

		if m.Add("b", 99) {
			t.Error("Add replaced a live value")
		}
		if !m.Add("x", 5) {
			t.Error("Add refused a missing key")
		}
		m.SetFor("long", 7, time.Hour)
		time.Sleep(2 * time.Minute)
		if !m.Add("b", 11) {
			t.Error("Add refused an expired key")
		}
		if v, ok := m.Get("long"); !ok || v != 7 {
			t.Errorf("long = %d %v", v, ok)
		}
		m.Delete("long")
		if _, ok := m.Get("long"); ok {
			t.Error("Delete kept it")
		}
		m.Clear()
		if m.Len() != 0 {
			t.Errorf("len after Clear = %d", m.Len())
		}

		forever := NewMap[int, string](0, 2)
		forever.Set(1, "x")
		time.Sleep(24 * time.Hour)
		if _, ok := forever.Get(1); !ok {
			t.Error("a map without a TTL dropped a value")
		}
	})
}

func TestDoSharesOneCallAndOutlivesItsFirstCaller(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		var g Group
		var calls atomic.Int32
		release := make(chan struct{})
		fetch := func(ctx context.Context) (string, error) {
			calls.Add(1)
			select {
			case <-release:
				return "answer", nil
			case <-ctx.Done():
				return "", ctx.Err()
			}
		}
		first, leave := context.WithCancel(context.Background())
		var wg sync.WaitGroup
		var firstErr, secondErr error
		var second string
		wg.Go(func() { _, firstErr = Do(first, &g, "k", fetch) })
		synctest.Wait()
		wg.Go(func() { second, secondErr = Do(context.Background(), &g, "k", fetch) })
		synctest.Wait()
		leave() // the first caller goes away
		synctest.Wait()
		close(release)
		wg.Wait()
		if !errors.Is(firstErr, context.Canceled) {
			t.Errorf("first caller = %v", firstErr)
		}
		if second != "answer" || secondErr != nil {
			t.Errorf("second caller = %q %v", second, secondErr)
		}
		if n := calls.Load(); n != 1 {
			t.Errorf("calls = %d", n)
		}

		g.Timeout = time.Second
		never := func(ctx context.Context) (string, error) { <-ctx.Done(); return "", ctx.Err() }
		if _, err := Do(context.Background(), &g, "slow", never); !errors.Is(err, context.DeadlineExceeded) {
			t.Errorf("past the timeout = %v", err)
		}
	})
}

func TestLoadKeepsOnlySuccesses(t *testing.T) {
	m := NewMap[int, string](time.Minute, 10)
	var g Group
	calls := 0
	fail := true
	fetch := func(context.Context) (string, error) {
		calls++
		if fail {
			return "", errors.New("down")
		}
		return "up", nil
	}
	ctx := context.Background()
	if _, err := Load(ctx, m, &g, 1, fetch); err == nil {
		t.Fatal("no error")
	}
	fail = false
	for range 2 {
		if v, err := Load(ctx, m, &g, 1, fetch); v != "up" || err != nil {
			t.Fatalf("load = %q %v", v, err)
		}
	}
	if calls != 2 {
		t.Errorf("calls = %d, want 2 (the failure is not kept)", calls)
	}
}
