package api

import (
	"fmt"
	"net/http"
	"testing"

	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/db"
)

// A heartbeat takes the watched point its play worked out when it began,
// rather than reading the file's segments again, while the player reports
// the play's duration.
func TestProgressUsesThePlaysWatchedPoint(t *testing.T) {
	t.Parallel()
	var tracker *activity.Tracker
	srv := newTestServerWith(t, func(d *Deps) {
		tracker = activity.New(d.Conn, d.Bus, nil, activity.Options{})
		d.Activity = tracker
	})
	ctx := t.Context()
	f, err := srv.q.GetMediaFileByPath(ctx, "/m/Heat (1995).mkv")
	if err != nil {
		t.Fatal(err)
	}
	movie, err := srv.q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: f.LibraryID, TmdbID: 949, Title: "Heat", Genres: "[]"})
	if err != nil {
		t.Fatal(err)
	}
	if err := srv.q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &movie.ID, ID: f.ID}); err != nil {
		t.Fatal(err)
	}
	// Without segments the point would be 92% of the duration, 4,600 ms.
	play, err := tracker.Begin(ctx, activity.Start{UserID: 1, UserName: "admin", FileID: f.ID, DurationMs: 5000, WatchedAtMs: 1000})
	if err != nil {
		t.Fatal(err)
	}
	report := func(position, duration int64) WatchState {
		t.Helper()
		var p Progress
		body := fmt.Sprintf(`{"positionMs":%d,"durationMs":%d,"playId":%q,"state":"playing"}`, position, duration, play)
		if code := doJSON(t, srv.client, http.MethodPut, fmt.Sprintf("%s/api/files/%d/progress", srv.URL, f.ID), body, &p).StatusCode; code != http.StatusOK {
			t.Fatalf("progress -> %d", code)
		}
		return p.WatchState
	}
	// A duration far from the play's is worked out from it instead.
	if w := report(1500, 9000); w.Completed {
		t.Errorf("at 1.5 s of 9 s: %+v, want not watched", w)
	}
	if w := report(1500, 5001); !w.Completed {
		t.Errorf("at 1.5 s of the play's 5 s: %+v, want watched from the play's point", w)
	}
	if _, _, ok := tracker.WatchedAt(play, 2, f.ID); ok {
		t.Error("another user's report reads the play")
	}
}

// Marking writes nothing for a movie or episode that does not exist.
func TestMarkWatchedNeedsTheItem(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	for _, typ := range []string{"movie", "episode"} {
		if code := doJSON(t, srv.client, http.MethodPost, fmt.Sprintf("%s/api/watch/%s/424242/watched", srv.URL, typ), "", nil).StatusCode; code != http.StatusNotFound {
			t.Errorf("%s 424242 -> %d, want 404", typ, code)
		}
		if _, err := srv.q.GetWatchState(t.Context(), db.GetWatchStateParams{UserID: 1, ItemType: typ, ItemID: 424242}); err == nil {
			t.Errorf("watch state written for a missing %s", typ)
		}
	}
}
