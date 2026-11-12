package lists

import (
	"context"
	"log/slog"
	"net/http"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/tmdb/tmdbtest"
)

// The job fetches a trending list a day after its last fetch, keeps its
// entries when TMDB fails and backs off an hour, then six, and tells
// Changed only when the entries differ. A list nobody has looked at for
// two weeks waits until someone does.
func TestRefreshSchedule(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		conn, q := dbtest.Open(t)
		fake := tmdbtest.NewTest(t)
		var status atomic.Int64
		var first atomic.Int64
		first.Store(1)
		fake.Handle("/trending/movie/week", func(*http.Request) any {
			if s := status.Load(); s != 0 {
				return tmdbtest.Reply{Status: int(s), Body: `{"status_message":"down"}`}
			}
			return map[string]any{"page": 1, "total_pages": 1, "results": []any{tmdbtest.Movie(int(first.Load()), "Lantern", "1999-01-01"), tmdbtest.Movie(2, "Quay", "2001-01-01")}}
		})
		var changed atomic.Int64
		s := New(Options{Queries: q, Conn: conn, TMDB: fake.Client(), Log: slog.New(slog.DiscardHandler), Changed: func(db.MediaList) { changed.Add(1) }})
		s.Delay, s.Every = time.Minute, 15*time.Minute
		start := time.Now()
		p := Params{Chart: ChartTrendingWeek}
		l, err := s.Create(t.Context(), db.CreateMediaListParams{Screen: "home", Name: "Trending", SourceKind: KindTMDB, Params: p.Encode(), MediaType: "movie", EntryLimit: 50,
			FetchedAt: new(db.FormatTime(start)), NextFetchAt: NextFetch(KindTMDB, p, start), WantedAt: new(db.FormatTime(start))}, []Entry{{Rank: 1, MediaType: "movie", TMDBID: 1, Title: "Lantern", Year: 1999}})
		if err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithCancel(t.Context())
		done := make(chan struct{})
		go func() { s.Run(ctx); close(done) }()
		at := func(d time.Duration) {
			t.Helper()
			time.Sleep(time.Until(start.Add(d)))
			synctest.Wait()
		}
		get := func() (db.MediaList, []Entry) {
			t.Helper()
			l, err := q.GetMediaList(t.Context(), l.ID)
			if err != nil {
				t.Fatal(err)
			}
			e, err := s.Entries(t.Context(), l.ID)
			if err != nil {
				t.Fatal(err)
			}
			return l, e
		}
		at(23 * time.Hour)
		if fake.Total() != 0 {
			t.Fatalf("fetched %d times before it was due", fake.Total())
		}
		at(24*time.Hour + 15*time.Minute)
		got, entries := get()
		if fake.Total() != 1 || len(entries) != 2 || changed.Load() != 1 || got.Error != nil {
			t.Fatalf("after a day: %d requests, entries %+v, changed %d, error %v", fake.Total(), entries, changed.Load(), got.Error)
		}
		// TMDB fails: the entries stay, the list says why and waits an hour.
		status.Store(http.StatusUnauthorized)
		at(48*time.Hour + 15*time.Minute)
		got, entries = get()
		if got.Error == nil || *got.Error != "TMDB refused the server's key" || got.Failures != 1 || len(entries) != 2 {
			t.Fatalf("after a failure: error %v, failures %d, entries %d", got.Error, got.Failures, len(entries))
		}
		if next := db.ParseTime(*got.NextFetchAt); next.Sub(db.ParseTime(*got.ErrorAt)) != time.Hour {
			t.Errorf("next fetch %v after the failure", next.Sub(db.ParseTime(*got.ErrorAt)))
		}
		at(49*time.Hour + 30*time.Minute)
		got, _ = get()
		if got.Failures != 2 || db.ParseTime(*got.NextFetchAt).Sub(db.ParseTime(*got.ErrorAt)) != 6*time.Hour {
			t.Errorf("after the second failure: failures %d, next %v", got.Failures, *got.NextFetchAt)
		}
		// It works again with the same entries: nothing changed to tell.
		status.Store(0)
		at(56 * time.Hour)
		got, _ = get()
		if got.Error != nil || got.Failures != 0 || changed.Load() != 1 {
			t.Errorf("after it works again: error %v, failures %d, changed %d", got.Error, got.Failures, changed.Load())
		}
		// Two weeks on nobody has looked at it: it is not fetched, until a
		// screen shows it again.
		at(14 * 24 * time.Hour)
		before := fake.Total()
		at(16 * 24 * time.Hour)
		if fake.Total() != before {
			t.Errorf("a list nobody looked at was fetched %d times", fake.Total()-before)
		}
		first.Store(3)
		s.Want(t.Context(), got)
		time.Sleep(time.Minute)
		synctest.Wait()
		if _, entries := get(); fake.Total() != before+1 || entries[0].TMDBID != 3 || changed.Load() != 2 {
			t.Errorf("after it was shown: %d requests, first %+v, changed %d", fake.Total()-before, entries[0], changed.Load())
		}
		cancel()
		<-done
	})
}

// A fetch while another of the same list runs is refused; lists.enabled
// off fetches nothing from outside.
func TestFetchGuards(t *testing.T) {
	t.Parallel()
	conn, q := dbtest.Open(t)
	fake := tmdbtest.New(t)
	started, release := make(chan struct{}), make(chan struct{})
	fake.Handle("/movie/popular", func(*http.Request) any {
		close(started)
		<-release
		return map[string]any{"page": 1, "total_pages": 1, "results": []any{tmdbtest.Movie(1, "Lantern", "1999-01-01")}}
	})
	s := New(Options{Queries: q, Conn: conn, TMDB: fake.Client()})
	p := Params{Chart: ChartPopular}
	l, err := s.Create(t.Context(), db.CreateMediaListParams{Screen: "home", Name: "Popular", SourceKind: KindTMDB, Params: p.Encode(), MediaType: "movie", EntryLimit: 50}, nil)
	if err != nil {
		t.Fatal(err)
	}
	errs := make(chan error, 1)
	go func() { _, err := s.Fetch(t.Context(), l.ID); errs <- err }()
	<-started
	if !s.Fetching(l.ID) {
		t.Error("the fetch doesn't show as running")
	}
	if _, err := s.Fetch(t.Context(), l.ID); err != ErrFetching { //nolint:errorlint // the sentinel itself
		t.Errorf("a second fetch = %v", err)
	}
	close(release)
	if err := <-errs; err != nil {
		t.Fatal(err)
	}

	off := New(Options{Queries: q, Conn: conn, TMDB: fake.Client(), Disabled: true})
	if _, err := off.Fetch(t.Context(), l.ID); err != ErrDisabled { //nolint:errorlint // the sentinel itself
		t.Errorf("fetch with lists off = %v", err)
	}
	if ok, _ := off.Ready(KindTMDB); ok {
		t.Error("TMDB is ready with lists off")
	}
	if ok, _ := off.Ready(KindImport); !ok {
		t.Error("imports aren't ready with lists off")
	}
}
