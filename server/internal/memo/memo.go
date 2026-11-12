// Package memo keeps what outside services answered for a while: a map whose
// entries expire and whose size is bounded, and a way for callers asking the
// same thing at once to share one call.
package memo

import (
	"container/list"
	"context"
	"fmt"
	"sync"
	"time"

	"golang.org/x/sync/singleflight"
)

// Map holds values for a time to live, and at most max of them: past that,
// the one set longest ago goes. A zero TTL keeps values until they are
// pushed out. It is safe for concurrent use.
type Map[K comparable, V any] struct {
	ttl time.Duration
	max int

	mu    sync.Mutex
	items map[K]*list.Element
	order list.List // of *entry[K, V], the one set longest ago first
}

type entry[K comparable, V any] struct {
	key     K
	v       V
	expires time.Time // zero: never
}

// NewMap returns a map whose values live for ttl, holding at most size of
// them (at least one).
func NewMap[K comparable, V any](ttl time.Duration, size int) *Map[K, V] {
	return &Map[K, V]{ttl: ttl, max: max(1, size), items: map[K]*list.Element{}}
}

// Get returns k's value while it lives.
func (m *Map[K, V]) Get(k K) (V, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if e, ok := m.live(k, time.Now()); ok {
		return e.v, true
	}
	var zero V
	return zero, false
}

// Set keeps v for k for the map's TTL.
func (m *Map[K, V]) Set(k K, v V) { m.SetFor(k, v, m.ttl) }

// SetFor keeps v for k for ttl (zero: until pushed out).
func (m *Map[K, V]) SetFor(k K, v V, ttl time.Duration) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.set(k, v, ttl, time.Now())
}

// Add keeps v for k only when k has no live value, and says whether it did.
func (m *Map[K, V]) Add(k K, v V) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := time.Now()
	if _, ok := m.live(k, now); ok {
		return false
	}
	m.set(k, v, m.ttl, now)
	return true
}

// Delete forgets k.
func (m *Map[K, V]) Delete(k K) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if el, ok := m.items[k]; ok {
		m.remove(el)
	}
}

// Clear forgets everything.
func (m *Map[K, V]) Clear() {
	m.mu.Lock()
	defer m.mu.Unlock()
	clear(m.items)
	m.order.Init()
}

// Len is how many values the map holds, some perhaps expired.
func (m *Map[K, V]) Len() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.items)
}

// live returns k's entry unless it has expired, which it then drops. The
// caller holds m.mu.
func (m *Map[K, V]) live(k K, now time.Time) (*entry[K, V], bool) {
	el, ok := m.items[k]
	if !ok {
		return nil, false
	}
	e := el.Value.(*entry[K, V])
	if !e.expires.IsZero() && !now.Before(e.expires) {
		m.remove(el)
		return nil, false
	}
	return e, true
}

func (m *Map[K, V]) set(k K, v V, ttl time.Duration, now time.Time) {
	var expires time.Time
	if ttl > 0 {
		expires = now.Add(ttl)
	}
	if el, ok := m.items[k]; ok {
		e := el.Value.(*entry[K, V])
		e.v, e.expires = v, expires
		m.order.MoveToBack(el)
		return
	}
	m.items[k] = m.order.PushBack(&entry[K, V]{key: k, v: v, expires: expires})
	for len(m.items) > m.max {
		m.remove(m.order.Front())
	}
	// Drop what has expired at the old end, so a map of short-lived values
	// doesn't sit full of dead ones.
	for el := m.order.Front(); el != nil; el = m.order.Front() {
		e := el.Value.(*entry[K, V])
		if e.expires.IsZero() || now.Before(e.expires) {
			break
		}
		m.remove(el)
	}
}

func (m *Map[K, V]) remove(el *list.Element) {
	delete(m.items, el.Value.(*entry[K, V]).key)
	m.order.Remove(el)
}

// Group shares one call among the callers asking for the same key at once.
// The call runs apart from the first caller's context, bounded by Timeout
// when set, so a caller that goes away (a closed page) fails nobody else;
// each caller stops waiting when its own context ends.
type Group struct {
	// Timeout bounds each shared call; zero means none beyond the call's own.
	Timeout time.Duration

	sf singleflight.Group
}

// Do calls fn once for every caller asking for key while it runs, and gives
// each of them its result.
func Do[V any](ctx context.Context, g *Group, key string, fn func(context.Context) (V, error)) (V, error) {
	ch := g.sf.DoChan(key, func() (any, error) {
		shared := context.WithoutCancel(ctx)
		if g.Timeout > 0 {
			var cancel context.CancelFunc
			shared, cancel = context.WithTimeout(shared, g.Timeout)
			defer cancel()
		}
		return fn(shared)
	})
	select {
	case r := <-ch:
		v, _ := r.Val.(V)
		return v, r.Err
	case <-ctx.Done():
		var zero V
		return zero, ctx.Err()
	}
}

// Load returns k's value from m, or else fetches it once for every caller
// asking at once, through g, and keeps it in m when fetch succeeds.
func Load[K comparable, V any](ctx context.Context, m *Map[K, V], g *Group, k K, fetch func(context.Context) (V, error)) (V, error) {
	if v, ok := m.Get(k); ok {
		return v, nil
	}
	return Do(ctx, g, fmt.Sprintf("%p/%v", m, k), func(ctx context.Context) (V, error) {
		if v, ok := m.Get(k); ok {
			return v, nil // a call that just finished kept it
		}
		v, err := fetch(ctx)
		if err == nil {
			m.Set(k, v)
		}
		return v, err
	})
}
