package watchstate

import (
	"testing"
	"time"
)

func (f *fixture) hidden(t *testing.T, target Target) bool {
	t.Helper()
	_, w := f.current(t, f.user, target)
	if w == nil {
		t.Fatalf("%+v has no state", target)
	}
	return w.HiddenAt != nil
}

// Hiding takes an item off Continue watching and keeps its place; Unhide,
// a progress report here and offline progress bring it back; an import
// doesn't, and nothing is written for an item without state.
func TestHideKeepsThePlace(t *testing.T) {
	t.Parallel()
	f := setup(t)

	t.Run("hide and unhide", func(t *testing.T) {
		m := f.movie(t)
		f.report(t, f.user, m, 60_000)
		_, before := f.current(t, f.user, m)
		if err := f.s.Hide(f.ctx, f.user, Movie, m.ItemID); err != nil {
			t.Fatal(err)
		}
		g, after := f.current(t, f.user, m)
		if after.HiddenAt == nil || after.PositionMs != 60_000 || after.UpdatedAt != before.UpdatedAt || g.Owner != ownerLocal {
			t.Fatalf("hidden state %+v, guard %+v", after, g)
		}
		// Hiding again changes nothing.
		if err := f.s.Hide(f.ctx, f.user, Movie, m.ItemID); err != nil {
			t.Fatal(err)
		}
		if _, again := f.current(t, f.user, m); *again.HiddenAt != *after.HiddenAt {
			t.Errorf("hidden again at %v, was %v", *again.HiddenAt, *after.HiddenAt)
		}
		if err := f.s.Unhide(f.ctx, f.user, Movie, m.ItemID); err != nil {
			t.Fatal(err)
		}
		if f.hidden(t, m) {
			t.Error("still hidden after Unhide")
		}
	})

	t.Run("a play here brings it back", func(t *testing.T) {
		m := f.movie(t)
		f.report(t, f.user, m, 60_000)
		if err := f.s.Hide(f.ctx, f.user, Movie, m.ItemID); err != nil {
			t.Fatal(err)
		}
		f.report(t, f.user, m, 61_000)
		if f.hidden(t, m) {
			t.Error("still hidden after a progress report")
		}
	})

	t.Run("offline progress brings it back", func(t *testing.T) {
		m := f.movie(t)
		f.report(t, f.user, m, 60_000)
		if err := f.s.Hide(f.ctx, f.user, Movie, m.ItemID); err != nil {
			t.Fatal(err)
		}
		if got := f.reportAt(t, f.user, []int64{m.ItemID}, Movie, 90_000, time.Now().Add(time.Minute)); got[0] != Applied {
			t.Fatalf("offline report %v", got)
		}
		if f.hidden(t, m) {
			t.Error("still hidden after offline progress")
		}
	})

	t.Run("an import leaves it hidden", func(t *testing.T) {
		m := f.movie(t)
		f.report(t, f.user, m, 60_000)
		if err := f.s.Hide(f.ctx, f.user, Movie, m.ItemID); err != nil {
			t.Fatal(err)
		}
		if got := f.importOne(t, f.user, 7, m); got != LocalKept {
			t.Fatalf("import %v", got)
		}
		if !f.hidden(t, m) {
			t.Error("an import brought it back")
		}
	})

	t.Run("a rematch keeps it hidden", func(t *testing.T) {
		from, to := f.movie(t), f.movie(t)
		f.report(t, f.user, from, 60_000)
		if err := f.s.Hide(f.ctx, f.user, Movie, from.ItemID); err != nil {
			t.Fatal(err)
		}
		if err := f.s.Move(f.ctx, Movie, []Move{{From: from.ItemID, To: to.ItemID}}); err != nil {
			t.Fatal(err)
		}
		if !f.hidden(t, to) {
			t.Error("the moved state lost its hidden mark")
		}
	})

	t.Run("no state, nothing written", func(t *testing.T) {
		m := f.movie(t)
		if err := f.s.Hide(f.ctx, f.user, Movie, m.ItemID); err != nil {
			t.Fatal(err)
		}
		if err := f.s.Unhide(f.ctx, f.user, Movie, m.ItemID); err != nil {
			t.Fatal(err)
		}
		if _, w := f.current(t, f.user, m); w != nil {
			t.Errorf("state %+v", w)
		}
	})
}

// Hide and Unhide tell the person's screens, once each, and only when
// something changed.
func TestHideAnnounces(t *testing.T) {
	t.Parallel()
	f := setup(t)
	m := f.movie(t)
	f.report(t, f.user, m, 60_000)
	other := f.movie(t)
	ch, cancel := f.bus.Subscribe()
	defer cancel()
	if err := f.s.Hide(f.ctx, f.user, Movie, other.ItemID); err != nil { // no state: no event
		t.Fatal(err)
	}
	for _, step := range []func() error{
		func() error { return f.s.Hide(f.ctx, f.user, Movie, m.ItemID) },
		func() error { return f.s.Unhide(f.ctx, f.user, Movie, m.ItemID) },
	} {
		if err := step(); err != nil {
			t.Fatal(err)
		}
		select {
		case ev := <-ch:
			got, ok := ev.Data.(WatchChanged)
			if ev.Type != "watch.changed" || ev.To == nil || ev.To.UserID != f.user || !ev.To.Private || !ok ||
				got.ItemType != Movie || len(got.ItemIDs) != 1 || got.ItemIDs[0] != m.ItemID {
				t.Fatalf("event %+v to %+v", ev, ev.To)
			}
		case <-time.After(time.Second):
			t.Fatal("no event")
		}
	}
	if err := f.s.Unhide(f.ctx, f.user, Movie, m.ItemID); err != nil { // already shown: no event
		t.Fatal(err)
	}
	select {
	case ev := <-ch:
		t.Errorf("extra event %+v", ev)
	default:
	}
}
