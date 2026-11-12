package events

import (
	"sync"
	"testing"
)

func TestEventFor(t *testing.T) {
	all := Event{}
	request := Event{To: &Audience{UserID: 2}}
	own := Event{To: &Audience{UserID: 2, Private: true}}
	play := Event{To: &Audience{UserID: 2, Anywhere: true}}
	cases := []struct {
		name    string
		e       Event
		user    int64
		admin   bool
		network bool
		want    bool
	}{
		{"everyone", all, 3, false, false, true},
		{"the user", request, 2, false, false, true},
		{"an admin", request, 1, true, true, true},
		{"an admin away", request, 1, true, false, false},
		{"someone else", request, 3, false, true, false},
		{"the user, privately", own, 2, false, false, true},
		{"an admin, privately", own, 1, true, true, false},
		{"an admin away, privately", own, 1, true, false, false},
		{"the viewer, anywhere", play, 2, false, false, true},
		{"an admin away, anywhere", play, 1, true, false, true},
		{"someone else, anywhere", play, 3, false, true, false},
	}
	for _, c := range cases {
		if got := c.e.For(c.user, c.admin, c.network); got != c.want {
			t.Errorf("%s: For = %v, want %v", c.name, got, c.want)
		}
	}
}

func TestStreamTakesOnlyAcceptedEvents(t *testing.T) {
	b := NewBus()
	ch, cancel := b.Stream(func(e Event) bool { return e.For(2, false, false) })
	defer cancel()
	b.Publish(Event{Type: "other", To: &Audience{UserID: 3, Private: true}})
	b.Publish(Event{Type: "mine", To: &Audience{UserID: 2, Private: true}})
	b.Publish(Event{Type: "everyone"})
	for _, want := range []string{"mine", "everyone"} {
		if e := <-ch; e.Type != want {
			t.Errorf("got %q, want %q", e.Type, want)
		}
	}
	// Other users' events do not fill the buffer.
	for range 2 * buffer {
		b.Publish(Event{Type: "other", To: &Audience{UserID: 3}})
	}
	b.Publish(Event{Type: "mine", To: &Audience{UserID: 2}})
	if e, ok := <-ch; !ok || e.Type != "mine" {
		t.Errorf("after other users' events: %q, open %v", e.Type, ok)
	}
}

// A stream that falls behind is closed rather than losing events silently.
func TestStreamClosesWhenBehind(t *testing.T) {
	b := NewBus()
	ch, cancel := b.Stream(nil)
	defer cancel()
	for range buffer + 1 {
		b.Publish(Event{Type: "progress"})
	}
	n := 0
	for range ch {
		n++
	}
	if n != buffer {
		t.Errorf("read %d events before the close, want the %d buffered", n, buffer)
	}
	b.Publish(Event{Type: "later"}) // must not panic on the closed channel
	cancel()                        // nor closing it twice
}

func TestUnsubscribe(t *testing.T) {
	b := NewBus()
	stream, cancelStream := b.Stream(nil)
	all, cancelAll := b.Subscribe()
	cancelStream()
	cancelAll()
	b.Publish(Event{Type: "after"})
	if _, ok := <-stream; ok {
		t.Error("stream still open after unsubscribing")
	}
	select {
	case e := <-all:
		t.Errorf("unsubscribed got %q", e.Type)
	default:
	}
}

// Subscribe keeps its channel open and drops what does not fit, for the
// server's own loops.
func TestSubscribeDropsWhenBehind(t *testing.T) {
	b := NewBus()
	ch, cancel := b.Subscribe()
	defer cancel()
	for range buffer + 10 {
		b.Publish(Event{Type: "progress"})
	}
	for range buffer {
		<-ch
	}
	b.Publish(Event{Type: "next"})
	if e := <-ch; e.Type != "next" {
		t.Errorf("got %q, want next", e.Type)
	}
}

func TestPublishWhileUnsubscribing(t *testing.T) {
	b := NewBus()
	var wg sync.WaitGroup
	for range 8 {
		wg.Go(func() {
			for range 200 {
				ch, cancel := b.Stream(nil)
				b.Publish(Event{Type: "x"})
				cancel()
				for range ch {
				}
			}
		})
		wg.Go(func() {
			for range 200 {
				b.Publish(Event{Type: "y"})
			}
		})
	}
	wg.Wait()
}
