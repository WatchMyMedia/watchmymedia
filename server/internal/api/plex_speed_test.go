package api

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"sync"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/watchstate"
)

// TestHomeSpeedDuringPlexImport exercises the browser's real Home request burst
// while the same database accepts import batches. Reading must not wait for a
// background write transaction, and importing must not overwhelm page latency.
func TestHomeSpeedDuringPlexImport(t *testing.T) {
	if testing.Short() || raceEnabled {
		t.Skip("seeds a large library; timing budgets require an uninstrumented run")
	}
	var conn *sql.DB
	srv := newTestServerWith(t, func(d *Deps) { conn = d.Conn })
	seedHomeLibrary(t, conn, time.Now())
	// Every browser reads this before it can start an import. The first call
	// creates a server ID; subsequent page loads are reads of that saved ID.
	getOK(t, srv.client, srv.URL+"/api/server")
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	movies, err := srv.q.ListMovies(ctx, db.ListMoviesParams{LibraryID: 1, Limit: 3000})
	if err != nil {
		t.Fatal(err)
	}
	targets := make([]watchstate.Target, len(movies))
	for i, row := range movies {
		m := row.Movie
		targets[i] = watchstate.Target{ItemType: watchstate.Movie, ItemID: m.ID, Identity: watchstate.Identity{LibraryID: m.LibraryID, TMDBID: m.TmdbID}}
	}
	if len(targets) != 2850 {
		t.Fatalf("import workload has %d movies, want 2850", len(targets))
	}
	watch := watchstate.New(srv.q, nil)
	entered := make(chan struct{})
	release := make(chan struct{})
	var unblockOnce sync.Once
	unblock := func() { unblockOnce.Do(func() { close(release) }) }
	defer unblock()
	done := make(chan error, 1)
	go func() {
		for offset := 0; offset < len(targets); offset += 100 {
			check := func(*db.Queries) error { return nil }
			if offset == 0 {
				check = func(*db.Queries) error {
					close(entered)
					select {
					case <-release:
						return nil
					case <-ctx.Done():
						return ctx.Err()
					}
				}
			}
			outcomes, err := watch.ImportChecked(ctx, 1, 1, targets[offset:min(offset+100, len(targets))], check, nil)
			if err != nil {
				done <- err
				return
			}
			for _, outcome := range outcomes {
				if outcome != watchstate.Applied {
					done <- fmt.Errorf("import workload did not write its mark: %s", outcome)
					return
				}
			}
		}
		done <- nil
	}()
	select {
	case <-entered:
	case err := <-done:
		t.Fatalf("import stopped before its first transaction: %v", err)
	}
	burst := func() {
		var wg sync.WaitGroup
		for _, path := range []string{"/api/home", "/api/libraries", "/api/server", "/api/activity"} {
			wg.Go(func() { getOK(t, srv.client, srv.URL+path) })
		}
		wg.Wait()
	}
	strict := os.Getenv("WMM_HOME_STRICT") == "1"
	// These bursts complete before the worker can release its write lock.
	report(t, strict, "writer held", medianOf(t, burst), 150*time.Millisecond)
	unblock()
	report(t, strict, "import burst", medianOf(t, burst), 150*time.Millisecond)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}
