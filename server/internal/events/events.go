// Package events is a small in-process pub/sub bus. The scanner publishes
// progress; the SSE endpoint fans it out to connected clients.
package events

import (
	"sync"
)

// Event carries a typed payload. Type is the SSE event name; Data is one of
// the payload structs registered with the SSE endpoint.
type Event struct {
	Type string
	Data any
	// To limits who receives the event; nil sends it to every signed-in user.
	To *Audience
}

// Audience is admins plus one user: a play's activity goes to the household's
// admins and to the person watching.
type Audience struct {
	UserID int64
	// Private leaves the admins out, as for a user's own watch state.
	Private bool
	// Anywhere reaches admins on any network, not only on an admin network,
	// for what they may read from anywhere: activity.
	Anywhere bool
}

// For reports whether a user receives e. admin is true for an admin
// account, adminNetwork for a client on an admin network.
func (e Event) For(userID int64, admin, adminNetwork bool) bool {
	return e.To == nil || e.To.UserID == userID || (admin && !e.To.Private && (adminNetwork || e.To.Anywhere))
}

// buffer is how many events a subscriber may fall behind.
const buffer = 64

type Bus struct {
	mu   sync.RWMutex
	subs map[*subscriber]struct{}
}

type subscriber struct {
	ch chan Event
	// accept picks the events the subscriber gets; nil takes all.
	accept func(Event) bool
	// strict closes ch when the subscriber falls behind, rather than drop
	// the event; mu guards the close against sends.
	strict bool
	mu     sync.Mutex
	closed bool
}

func NewBus() *Bus { return &Bus{subs: make(map[*subscriber]struct{})} }

// Publish delivers e to every subscriber that accepts it, without blocking.
func (b *Bus) Publish(e Event) {
	b.mu.RLock()
	defer b.mu.RUnlock()
	for s := range b.subs {
		if s.accept == nil || s.accept(e) {
			s.send(e)
		}
	}
}

func (s *subscriber) send(e Event) {
	if !s.strict {
		select {
		case s.ch <- e:
		default:
		}
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return
	}
	select {
	case s.ch <- e:
	default:
		s.closed = true
		close(s.ch)
	}
}

func (s *subscriber) close() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.closed {
		s.closed = true
		close(s.ch)
	}
}

// Subscribe returns a channel of every event and a function that
// unsubscribes. A subscriber that has fallen 64 events behind loses the
// next ones; the server's own loops (matching, segment detection) only
// need to hear that something happened.
func (b *Bus) Subscribe() (<-chan Event, func()) {
	return b.subscribe(&subscriber{ch: make(chan Event, buffer)})
}

// Stream returns a channel of the events accept takes, for a client's event
// stream, and a function that unsubscribes. Other users' events never fill
// it. When it falls 64 events behind the channel is closed rather than an
// event lost, so the stream ends and the client, which reconnects, fetches
// again what it shows. Unsubscribing closes the channel too.
func (b *Bus) Stream(accept func(Event) bool) (<-chan Event, func()) {
	return b.subscribe(&subscriber{ch: make(chan Event, buffer), accept: accept, strict: true})
}

func (b *Bus) subscribe(s *subscriber) (<-chan Event, func()) {
	b.mu.Lock()
	b.subs[s] = struct{}{}
	b.mu.Unlock()
	return s.ch, func() {
		b.mu.Lock()
		delete(b.subs, s)
		b.mu.Unlock()
		if s.strict {
			s.close()
		}
	}
}
