package watchstate

import (
	"testing"
	"time"

	"watchmymedia/server/internal/db"
)

func (f *fixture) reportAt(t *testing.T, user int64, ids []int64, itemType string, positionMs int64, at time.Time) []Outcome {
	t.Helper()
	got, err := f.s.ReportAt(f.ctx, Progress{UserID: user, ItemType: itemType, ItemIDs: ids, FileID: f.file, PositionMs: positionMs, DurationMs: 600_000}, at)
	if err != nil {
		t.Fatal(err)
	}
	return got
}

// PLAN-DOWNLOADS.md §6's table: an offline change applies to an item no
// one decided, or an import decided, or a local change made before it; a
// local change made after it stays; a gone item is unavailable.
func TestOfflineChangeNewestLocalWins(t *testing.T) {
	f := setup(t)
	past := time.Now().Add(-2 * time.Hour).UTC().Truncate(time.Millisecond)

	t.Run("no guard", func(t *testing.T) {
		m := f.movie(t)
		if got := f.reportAt(t, f.user, []int64{m.ItemID}, Movie, 60_000, past); got[0] != Applied {
			t.Fatalf("outcome = %v", got)
		}
		g, w := f.current(t, f.user, m)
		if g == nil || g.Owner != ownerLocal || g.UpdatedAt != db.FormatTime(past) {
			t.Errorf("guard = %+v, want local at %s", g, db.FormatTime(past))
		}
		if w == nil || w.PositionMs != 60_000 || w.UpdatedAt != db.FormatTime(past) || w.MediaFileID == nil || *w.MediaFileID != f.file {
			t.Errorf("state = %+v, want 60 s from the offline time", w)
		}
	})

	t.Run("an import's mark never beats it", func(t *testing.T) {
		m := f.movie(t)
		f.importOne(t, f.user, 7, m) // imported now, after the offline play
		if got := f.reportAt(t, f.user, []int64{m.ItemID}, Movie, 90_000, past); got[0] != Applied {
			t.Fatalf("outcome = %v", got)
		}
		g, w := f.current(t, f.user, m)
		if g.Owner != ownerLocal || g.ImportRun != nil || w.Completed != 0 || w.PositionMs != 90_000 {
			t.Errorf("guard %+v state %+v, want the offline position, local", g, w)
		}
		if got, _ := f.s.Import(f.ctx, f.user, 8, []Target{m}); got[0] != LocalKept {
			t.Errorf("a later import = %v, want local_kept", got)
		}
	})

	t.Run("local change before it", func(t *testing.T) {
		m := f.movie(t)
		f.report(t, f.user, m, 30_000) // now
		later := time.Now().Add(time.Minute)
		if got := f.reportAt(t, f.user, []int64{m.ItemID}, Movie, 120_000, later); got[0] != Applied {
			t.Fatalf("outcome = %v", got)
		}
		if _, w := f.current(t, f.user, m); w.PositionMs != 120_000 {
			t.Errorf("position = %d, want the offline one", w.PositionMs)
		}
	})

	t.Run("local change after it", func(t *testing.T) {
		m := f.movie(t)
		f.report(t, f.user, m, 30_000) // played on the TV since
		before, beforeState := f.current(t, f.user, m)
		if got := f.reportAt(t, f.user, []int64{m.ItemID}, Movie, 400_000, past); got[0] != Newer {
			t.Fatalf("outcome = %v, want newer", got)
		}
		after, afterState := f.current(t, f.user, m)
		if !sameGuard(before, after) || !sameState(beforeState, afterState) {
			t.Errorf("changed: %+v %+v -> %+v %+v", before, beforeState, after, afterState)
		}
	})

	t.Run("offline changes in order and again", func(t *testing.T) {
		m := f.movie(t)
		t1, t2 := past, past.Add(10*time.Minute)
		f.reportAt(t, f.user, []int64{m.ItemID}, Movie, 100_000, t2)
		if got := f.reportAt(t, f.user, []int64{m.ItemID}, Movie, 50_000, t1); got[0] != Newer {
			t.Errorf("an older offline report = %v, want newer", got)
		}
		// The same entry sent again (its reply was lost) changes nothing.
		if got := f.reportAt(t, f.user, []int64{m.ItemID}, Movie, 100_000, t2); got[0] != Newer {
			t.Errorf("a repeat = %v, want newer", got)
		}
		if _, w := f.current(t, f.user, m); w.PositionMs != 100_000 || w.UpdatedAt != db.FormatTime(t2) {
			t.Errorf("state = %+v", w)
		}
	})

	t.Run("older server's state counts by its own time", func(t *testing.T) {
		m := f.movie(t)
		f.legacy(t, f.user, m, 10_000, 0) // now, no guard
		if got := f.reportAt(t, f.user, []int64{m.ItemID}, Movie, 20_000, past); got[0] != Newer {
			t.Errorf("outcome = %v, want newer", got)
		}
	})

	t.Run("gone item", func(t *testing.T) {
		if got := f.reportAt(t, f.user, []int64{987654}, Movie, 1000, past); got[0] != Unavailable {
			t.Errorf("outcome = %v, want unavailable", got)
		}
	})
}

// A file holding two episodes applies to each by its own guard.
func TestOfflineReportMultiEpisodeFile(t *testing.T) {
	f := setup(t)
	_, _, eps := f.show(t, 2)
	e1, e2 := eps[0][0], eps[0][1]
	f.report(t, f.user, e2, 5000) // the second episode was played since
	past := time.Now().Add(-time.Hour)
	got := f.reportAt(t, f.user, []int64{e1.ItemID, e2.ItemID}, Episode, 70_000, past)
	if got[0] != Applied || got[1] != Newer {
		t.Fatalf("outcomes = %v, want applied, newer", got)
	}
	if _, w := f.current(t, f.user, e1); w.PositionMs != 70_000 {
		t.Errorf("first episode = %+v", w)
	}
	if _, w := f.current(t, f.user, e2); w.PositionMs != 5000 {
		t.Errorf("second episode = %+v, want the newer play", w)
	}
}

// Marks made offline follow the same rule: watched clears the position,
// unwatched deletes the state and keeps a guard dated when it happened.
func TestOfflineMarks(t *testing.T) {
	f := setup(t)
	m := f.movie(t)
	t1 := time.Now().Add(-3 * time.Hour)
	ch, cancel := f.bus.Subscribe()
	defer cancel()
	if got, err := f.s.MarkAt(f.ctx, f.user, Movie, []int64{m.ItemID}, true, t1); err != nil || got[0] != Applied {
		t.Fatalf("mark watched = %v, %v", got, err)
	}
	if e := <-ch; e.Type != "watch.changed" {
		t.Errorf("event = %s", e.Type)
	}
	if _, w := f.current(t, f.user, m); w == nil || w.Completed != 1 || w.PositionMs != 0 || w.MediaFileID != nil {
		t.Errorf("state = %+v, want watched", w)
	}
	t2 := t1.Add(time.Hour)
	if got, err := f.s.MarkAt(f.ctx, f.user, Movie, []int64{m.ItemID}, false, t2); err != nil || got[0] != Applied {
		t.Fatalf("mark unwatched = %v, %v", got, err)
	}
	g, w := f.current(t, f.user, m)
	if w != nil || g == nil || g.Owner != ownerLocal || g.UpdatedAt != db.FormatTime(t2) {
		t.Errorf("guard %+v state %+v, want a guard alone at %s", g, w, db.FormatTime(t2))
	}
	// Marked watched on the web since: an offline unwatch from before stays out.
	if err := f.s.MarkWatched(f.ctx, f.user, Movie, m.ItemID); err != nil {
		t.Fatal(err)
	}
	if got, _ := f.s.MarkAt(f.ctx, f.user, Movie, []int64{m.ItemID}, false, t2.Add(time.Minute)); got[0] != Newer {
		t.Errorf("an older unwatch = %v, want newer", got)
	}
}
