package scanner

import (
	"reflect"
	"testing"
	"testing/synctest"
	"time"
)

// due reports a burst that has come due by now, if any.
func due(d *debouncer) *burst {
	synctest.Wait()
	select {
	case b := <-d.due:
		return b
	default:
		return nil
	}
}

// Changes are scanned once events have stopped for the quiet period, all
// in one scan, and each library on its own.
func TestDebounceWaitsForQuiet(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		d := newDebouncer(5*time.Second, time.Minute)
		ctx := t.Context()
		d.add(ctx, 1, "/m/b.mkv")
		time.Sleep(4 * time.Second)
		d.add(ctx, 1, "/m/a.mkv")
		time.Sleep(time.Second)
		d.add(ctx, 2, "/t/x.mkv")
		time.Sleep(3 * time.Second)
		if b := due(d); b != nil {
			t.Fatalf("library %d scanned before its events stopped for 5 s", b.library)
		}
		time.Sleep(time.Second)
		b := due(d)
		if b == nil {
			t.Fatal("nothing due 5 s after the last event")
		}
		id, paths, ok := d.take(b)
		if !ok || id != 1 || !reflect.DeepEqual(paths, []string{"/m/a.mkv", "/m/b.mkv"}) {
			t.Errorf("took %d %v %v", id, paths, ok)
		}
		if _, _, ok := d.take(b); ok {
			t.Error("a burst was taken twice")
		}
		time.Sleep(time.Second)
		if b := due(d); b == nil || b.library != 2 {
			t.Errorf("library 2 due: %+v", b)
		}
	})
}

// A steady trickle of events, one every 2 s against a 5 s quiet period,
// is scanned a minute after its first event and every minute after that,
// not held off for as long as it lasts.
func TestDebounceFiresWithinAMinute(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		d := newDebouncer(5*time.Second, time.Minute)
		start := time.Now()
		var fired []time.Duration
		for range 90 {
			if b := due(d); b != nil {
				if _, _, ok := d.take(b); ok {
					fired = append(fired, time.Since(start))
				}
			}
			d.add(t.Context(), 1, "/m/growing.mkv")
			time.Sleep(2 * time.Second)
		}
		if want := []time.Duration{time.Minute, 2 * time.Minute}; !reflect.DeepEqual(fired, want) {
			t.Errorf("scans at %v, want %v", fired, want)
		}
	})
}
