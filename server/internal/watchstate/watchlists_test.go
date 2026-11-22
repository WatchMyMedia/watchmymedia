package watchstate

import (
	"fmt"
	"testing"
	"time"

	"watchmymedia/server/internal/lists"
)

// onDisk gives episodes a present file each, as a show's card counts them.
func (f *fixture) onDisk(t *testing.T, eps []Target) {
	t.Helper()
	for _, e := range eps {
		res, err := f.conn.ExecContext(f.ctx, `INSERT INTO media_files (library_id, path, size, mtime, status, parsed, added_at, match_status)
			VALUES (?, ?, 1, '2020-01-01T00:00:00.000Z', 'present', '{}', '2020-01-01T00:00:00.000Z', 'matched')`, f.tv.ID, fmt.Sprintf("/t/%d.mkv", e.ItemID))
		if err != nil {
			t.Fatal(err)
		}
		file, _ := res.LastInsertId()
		if _, err := f.conn.ExecContext(f.ctx, `INSERT INTO episode_files (episode_id, media_file_id) VALUES (?, ?)`, e.ItemID, file); err != nil {
			t.Fatal(err)
		}
	}
}

// What each kind of change does to the watchlists that hold a title: it
// leaves when the change finishes it after it was saved, going by the
// watch's own time, and the person's devices hear of each watchlist it
// left.
func TestFinishedTitlesLeaveWatchlists(t *testing.T) {
	t.Parallel()
	// Saved an hour ago; the changes below happen now unless they say when.
	saved := time.Now().UTC().Add(-time.Hour).Truncate(time.Millisecond)
	type title struct {
		movie     Target   // a movie, else a show of two episodes
		show      int64    // the show
		season    int64    // its season
		eps       []Target // its episodes
		mediaType string
		tmdbID    int64
		other     Target // another movie, for Move
	}
	finish := func(f *fixture, item Target) error {
		_, err := f.s.Report(f.ctx, Progress{UserID: f.user, ItemType: item.ItemType, ItemIDs: []int64{item.ItemID}, FileID: f.file,
			DurationMs: 600_000, Completed: true})
		return err
	}
	for _, c := range []struct {
		name   string
		show   bool
		keep   bool // the watchlist keeps watched titles
		lists  int
		change func(f *fixture, x title) error
		leaves bool
	}{
		{name: "a player finishes the movie", leaves: true, change: func(f *fixture, x title) error { return finish(f, x.movie) }},
		{name: "Mark watched", leaves: true, change: func(f *fixture, x title) error { return f.s.MarkWatched(f.ctx, f.user, Movie, x.movie.ItemID) }},
		{name: "an offline play from before it was saved", change: func(f *fixture, x title) error {
			_, err := f.s.ReportAt(f.ctx, Progress{UserID: f.user, ItemType: Movie, ItemIDs: []int64{x.movie.ItemID}, FileID: f.file, Completed: true}, saved.Add(-time.Minute))
			return err
		}},
		{name: "an offline mark from after it was saved", leaves: true, change: func(f *fixture, x title) error {
			_, err := f.s.MarkAt(f.ctx, f.user, Movie, []int64{x.movie.ItemID}, true, saved.Add(time.Minute))
			return err
		}},
		{name: "an import, as of when it ran", leaves: true, change: func(f *fixture, x title) error {
			_, err := f.s.Import(f.ctx, f.user, 1, []Target{x.movie})
			return err
		}},
		{name: "watched before it was saved, then again", leaves: true, change: func(f *fixture, x title) error {
			if _, err := f.s.MarkAt(f.ctx, f.user, Movie, []int64{x.movie.ItemID}, true, saved.Add(-time.Minute)); err != nil {
				return err
			}
			return finish(f, x.movie)
		}},
		{name: "the watchlist keeps watched titles", keep: true, change: func(f *fixture, x title) error { return f.s.MarkWatched(f.ctx, f.user, Movie, x.movie.ItemID) }},
		{name: "on two watchlists", lists: 2, leaves: true, change: func(f *fixture, x title) error { return finish(f, x.movie) }},
		{name: "a finished play moved onto it in a rescan", change: func(f *fixture, x title) error {
			if err := f.s.MarkWatched(f.ctx, f.user, Movie, x.other.ItemID); err != nil {
				return err
			}
			return f.s.Move(f.ctx, Movie, []Move{{From: x.other.ItemID, To: x.movie.ItemID}})
		}},
		{name: "one episode of two", show: true, change: func(f *fixture, x title) error { return finish(f, x.eps[0]) }},
		{name: "the show's last episode", show: true, leaves: true, change: func(f *fixture, x title) error {
			if err := f.s.MarkWatched(f.ctx, f.user, Episode, x.eps[0].ItemID); err != nil {
				return err
			}
			return finish(f, x.eps[1])
		}},
		{name: "Mark season", show: true, leaves: true, change: func(f *fixture, x title) error { return f.s.MarkSeason(f.ctx, f.user, x.season, true) }},
		{name: "Mark show", show: true, leaves: true, change: func(f *fixture, x title) error { return f.s.MarkShow(f.ctx, f.user, x.show, true) }},
		{name: "a show finished offline before it was saved", show: true, change: func(f *fixture, x title) error {
			_, err := f.s.MarkAt(f.ctx, f.user, Episode, []int64{x.eps[0].ItemID, x.eps[1].ItemID}, true, saved.Add(-time.Minute))
			return err
		}},
	} {
		t.Run(c.name, func(t *testing.T) {
			t.Parallel()
			f := setup(t)
			var x title
			if c.show {
				id, seasons, eps := f.show(t, 2)
				x = title{show: id, season: seasons[0], eps: eps[0], mediaType: "tv", tmdbID: f.tmdb}
				f.onDisk(t, x.eps)
			} else {
				x.other = f.movie(t)
				x.movie = f.movie(t)
				x.mediaType, x.tmdbID = "movie", f.tmdb
			}
			saves := lists.New(lists.Options{Queries: f.q, Conn: f.conn})
			saves.Now = func() time.Time { return saved }
			for i := range max(c.lists, 1) {
				l, err := saves.CreateWatchlist(f.ctx, f.user, []string{"Watchlist", "Halloween"}[i], !c.keep)
				if err != nil {
					t.Fatal(err)
				}
				if _, err := saves.SaveToWatchlist(f.ctx, l, lists.Title{MediaType: x.mediaType, TMDBID: x.tmdbID}); err != nil {
					t.Fatal(err)
				}
			}
			heard, stop := f.bus.Subscribe()
			defer stop()
			if err := c.change(f, x); err != nil {
				t.Fatal(err)
			}
			left := 0
			for len(heard) > 0 {
				if e := <-heard; e.Type == lists.ChangedEvent && e.To != nil && e.To.UserID == f.user {
					left++
				}
			}
			rest, err := f.q.ListWatchlistEntries(f.ctx, &f.user)
			if err != nil {
				t.Fatal(err)
			}
			want := max(c.lists, 1)
			if c.leaves {
				if len(rest) != 0 || left != want {
					t.Errorf("left %d watchlists (%d entries still there), want %d", left, len(rest), want)
				}
			} else if len(rest) != want || left != 0 {
				t.Errorf("%d entries still there and %d lists.changed, want %d and none", len(rest), left, want)
			}
		})
	}
}
