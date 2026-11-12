package trailers

import (
	"context"
	"database/sql"
	"fmt"
	"log/slog"
	"net/http"
	"reflect"
	"strconv"
	"strings"
	"testing"
	"testing/synctest"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/tmdb"
	"watchmymedia/server/internal/tmdb/tmdbtest"
)

type env struct {
	t    *testing.T
	conn *sql.DB
	q    *db.Queries
	lib  int64
	fake *tmdbtest.Server
	r    *Refresher
}

// newEnv is a movie library and a refresher of it against fake, which
// answers every movie's videos with one trailer keyed by its TMDB id.
func newEnv(t *testing.T, fake *tmdbtest.Server) *env {
	t.Helper()
	conn, q := dbtest.Open(t)
	lib, err := q.UpsertLibrary(t.Context(), db.UpsertLibraryParams{Type: "movie", Name: "Movies", Path: "/movies"})
	if err != nil {
		t.Fatal(err)
	}
	fake.Fallback(func(r *http.Request) any {
		id, ok := strings.CutSuffix(strings.TrimPrefix(r.URL.Path, "/movie/"), "/videos")
		if !ok {
			return nil
		}
		if got := r.URL.Query().Get("include_video_language"); got != "en,null" {
			t.Errorf("include_video_language = %q", got)
		}
		return map[string]any{"results": []any{map[string]any{"key": "trailer" + id, "name": "Official Trailer", "site": "YouTube", "type": "Trailer", "official": true, "iso_639_1": "en", "size": 1080}}}
	})
	return &env{t: t, conn: conn, q: q, lib: lib.ID, fake: fake, r: New(q, fake.Client(), slog.New(slog.DiscardHandler), true)}
}

// movie adds a movie released on release ("" for no date), last checked at
// checked (the zero time for never), with one file in status.
func (e *env) movie(tmdbID int, release string, checked time.Time, status string) int64 {
	e.t.Helper()
	ctx := e.t.Context()
	p := db.UpsertMovieParams{LibraryID: e.lib, TmdbID: int64(tmdbID), Title: "Film " + strconv.Itoa(tmdbID), Genres: "[]", Credits: "[]", Trailers: "[]"}
	if release != "" {
		p.ReleaseDate = &release
	}
	if !checked.IsZero() {
		p.TrailersCheckedAt = new(db.FormatTime(checked))
	}
	m, err := e.q.UpsertMovie(ctx, p)
	if err != nil {
		e.t.Fatal(err)
	}
	now := db.FormatTime(time.Now())
	f, err := e.q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: e.lib, Path: fmt.Sprintf("/movies/%d.mkv", tmdbID), Size: 1, Mtime: now, AddedAt: now, Parsed: "{}", LastSeenAt: now, SeenInScan: 1})
	if err != nil {
		e.t.Fatal(err)
	}
	if _, err := e.conn.ExecContext(ctx, "UPDATE media_files SET movie_id = ?, status = ? WHERE id = ?", m.ID, status, f.ID); err != nil {
		e.t.Fatal(err)
	}
	return m.ID
}

// asked is how often the fake was asked for the movie's videos.
func (e *env) asked(tmdbID int) int { return e.fake.Count(fmt.Sprintf("/movie/%d/videos", tmdbID)) }

func (e *env) stored(id int64) (trailers []Trailer, checked string) {
	e.t.Helper()
	m, err := e.q.GetMovie(e.t.Context(), id)
	if err != nil {
		e.t.Fatal(err)
	}
	if m.TrailersCheckedAt != nil {
		checked = *m.TrailersCheckedAt
	}
	return Decode(m.Trailers), checked
}

func day(t time.Time) string { return t.UTC().Format(time.DateOnly) }

// A run asks for movies never checked, recent and upcoming ones last
// checked over a week ago, and the rest last checked over a month ago, but
// only movies with a file on disk; a movie TMDB no longer has is stored
// with none.
func TestRunOnceAsksForDueMovies(t *testing.T) {
	t.Parallel()
	e := newEnv(t, tmdbtest.New(t))
	now := time.Now()
	ago := func(days int) time.Time { return now.Add(-time.Duration(days) * 24 * time.Hour) }
	recent, upcoming, old := day(now.AddDate(0, -2, 0)), day(now.AddDate(1, 0, 0)), "2001-05-04"
	ids := map[int]int64{
		101: e.movie(101, old, time.Time{}, "present"),
		102: e.movie(102, old, ago(10), "present"),
		103: e.movie(103, old, ago(40), "present"),
		104: e.movie(104, recent, ago(10), "present"),
		105: e.movie(105, recent, ago(3), "present"),
		106: e.movie(106, upcoming, ago(10), "present"),
		107: e.movie(107, old, time.Time{}, "missing"),
		108: e.movie(108, "", time.Time{}, "present"),
		109: e.movie(109, "", ago(10), "present"),
		110: e.movie(110, day(now.AddDate(0, -7, 0)), ago(10), "present"),
	}
	e.fake.Handle("/movie/103/videos", func(*http.Request) any { return nil }) // gone from TMDB
	more, err := e.r.RunOnce(t.Context())
	if err != nil || more {
		t.Fatalf("RunOnce = %v, %v", more, err)
	}
	wantAsked := map[int]bool{101: true, 103: true, 104: true, 106: true, 108: true}
	for tmdbID, id := range ids {
		if got := e.asked(tmdbID); got != map[bool]int{true: 1}[wantAsked[tmdbID]] {
			t.Errorf("movie %d asked %d times", tmdbID, got)
		}
		list, checked := e.stored(id)
		switch {
		case tmdbID == 103:
			if len(list) != 0 || checked < db.FormatTime(now) {
				t.Errorf("movie gone from TMDB: %+v, checked %q", list, checked)
			}
		case wantAsked[tmdbID]:
			if want := []Trailer{{Key: fmt.Sprint("trailer", tmdbID), Name: "Official Trailer", Type: "Trailer"}}; !reflect.DeepEqual(list, want) || checked < db.FormatTime(now) {
				t.Errorf("movie %d: %+v, checked %q", tmdbID, list, checked)
			}
		case len(list) != 0:
			t.Errorf("movie %d not asked but has %+v", tmdbID, list)
		}
	}
	// Nothing is due straight after.
	if more, err := e.r.RunOnce(t.Context()); err != nil || more || e.fake.Total() != 5 {
		t.Errorf("second run: %v, %v; %d requests in all", more, err, e.fake.Total())
	}
}

// A run asks for Batch movies at most, never checked first, then the
// longest since, and says there are more.
func TestRunOnceBatch(t *testing.T) {
	t.Parallel()
	e := newEnv(t, tmdbtest.New(t))
	e.r.Batch = 2
	long := time.Now().Add(-90 * 24 * time.Hour)
	e.movie(201, "2001-05-04", long.Add(time.Hour), "present")
	e.movie(202, "2001-05-04", long, "present")
	e.movie(203, "2001-05-04", time.Time{}, "present")
	if more, err := e.r.RunOnce(t.Context()); err != nil || !more {
		t.Fatalf("RunOnce = %v, %v", more, err)
	}
	if e.asked(203) != 1 || e.asked(202) != 1 || e.asked(201) != 0 {
		t.Errorf("asked 201:%d 202:%d 203:%d", e.asked(201), e.asked(202), e.asked(203))
	}
	if more, err := e.r.RunOnce(t.Context()); err != nil || more || e.asked(201) != 1 {
		t.Errorf("second run: %v, %v; 201 asked %d times", more, err, e.asked(201))
	}
}

// When TMDB fails, the run stops, and the movie it failed on and those
// after it wait for the next run.
func TestRunOnceStopsWhenTMDBFails(t *testing.T) {
	t.Parallel()
	e := newEnv(t, tmdbtest.New(t))
	a := e.movie(301, "2001-05-04", time.Time{}, "present")
	b := e.movie(302, "2001-05-04", time.Time{}, "present")
	c := e.movie(303, "2001-05-04", time.Time{}, "present")
	e.fake.Handle("/movie/302/videos", func(*http.Request) any {
		return tmdbtest.Reply{Status: http.StatusInternalServerError, Body: `{"status_message":"down"}`}
	})
	if _, err := e.r.RunOnce(t.Context()); err == nil {
		t.Fatal("no error")
	}
	if _, checked := e.stored(a); checked == "" {
		t.Error("the movie before the failure was not stored")
	}
	for _, id := range []int64{b, c} {
		if _, checked := e.stored(id); checked != "" {
			t.Errorf("movie %d checked at %s", id, checked)
		}
	}
	if e.asked(303) != 0 {
		t.Error("asked on after the failure")
	}
}

// Without a TMDB key, or with YouTube trailers off, a run asks nothing.
func TestRunOnceWithoutKeyOrYouTube(t *testing.T) {
	t.Parallel()
	e := newEnv(t, tmdbtest.New(t))
	e.movie(401, "2001-05-04", time.Time{}, "present")
	e.r.SetYouTube(false)
	if more, err := e.r.RunOnce(t.Context()); more || err != nil {
		t.Errorf("off: %v, %v", more, err)
	}
	e.r.SetYouTube(true)
	e.r.tmdb = tmdb.New("", e.fake.URL)
	if more, err := e.r.RunOnce(t.Context()); more || err != nil {
		t.Errorf("no key: %v, %v", more, err)
	}
	if n := e.fake.Total(); n != 0 {
		t.Errorf("%d requests", n)
	}
	var r *Refresher
	if r.YouTube() {
		t.Error("a nil refresher says YouTube trailers are on")
	}
	r.SetYouTube(true) // and doesn't panic
}

// The job first runs Delay after it starts, again Delay later while runs
// fill their batch, then every Every; a recent movie is asked again a week
// after it was last asked, at the next run after that.
func TestRunSchedule(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		e := newEnv(t, tmdbtest.NewTest(t))
		e.r.Delay, e.r.Every, e.r.Batch = 5*time.Minute, 6*time.Hour, 2
		start := time.Now() // 2000-01-01 in the bubble
		for id := 501; id <= 503; id++ {
			e.movie(id, day(start.AddDate(-20, 0, 0)), time.Time{}, "present")
		}
		e.movie(504, day(start.AddDate(0, -1, 0)), time.Time{}, "present")
		ctx, cancel := context.WithCancel(t.Context())
		done := make(chan struct{})
		go func() { e.r.Run(ctx); close(done) }()
		total := func(at string, want int) {
			t.Helper()
			synctest.Wait()
			if got := e.fake.Total(); got != want {
				t.Fatalf("%s: %d requests, want %d", at, got, want)
			}
		}
		total("at start", 0)
		time.Sleep(5*time.Minute - time.Second)
		total("before Delay", 0)
		time.Sleep(time.Second)
		total("after Delay", 2)
		time.Sleep(5 * time.Minute)
		total("Delay after a full batch", 4)
		time.Sleep(5 * time.Minute) // a run with nothing due
		total("Delay after that", 4)
		// Runs every 6 hours from 15 minutes in; the recent movie, asked
		// at 10 minutes, is due a week later and asked at the run after.
		time.Sleep(time.Until(start.Add(15*time.Minute+28*6*time.Hour)) - time.Second)
		total("a week on, before the run", 4)
		time.Sleep(time.Second)
		total("a week on", 5)
		if e.asked(504) != 2 {
			t.Errorf("the recent movie was asked %d times", e.asked(504))
		}
		cancel()
		<-done
	})
}

// Turning YouTube trailers on runs the job Delay later rather than at the
// next 6-hour run.
func TestTurningYouTubeOnRunsSoon(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		e := newEnv(t, tmdbtest.NewTest(t))
		e.r.Delay, e.r.Every = 5*time.Minute, 6*time.Hour
		e.r.SetYouTube(false)
		e.movie(601, "2001-05-04", time.Time{}, "present")
		ctx, cancel := context.WithCancel(t.Context())
		done := make(chan struct{})
		go func() { e.r.Run(ctx); close(done) }()
		time.Sleep(time.Hour)
		synctest.Wait()
		if n := e.fake.Total(); n != 0 {
			t.Fatalf("%d requests while off", n)
		}
		e.r.SetYouTube(true)
		time.Sleep(5 * time.Minute)
		synctest.Wait()
		if e.asked(601) != 1 {
			t.Errorf("asked %d times Delay after turning on", e.asked(601))
		}
		cancel()
		<-done
	})
}
