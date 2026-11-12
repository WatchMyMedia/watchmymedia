package plexsync

import (
	"fmt"
	"strconv"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/plex/plextest"
)

// TestLargeMatchedImportCompletes exercises the real service, matcher, SQL
// checkpoints and watchstate batches with multi-page matched libraries. Timings
// are diagnostic: shared CI load must not make this correctness regression flaky.
func TestLargeMatchedImportCompletes(t *testing.T) {
	if testing.Short() {
		t.Skip("large matched import; run without -short")
	}
	for _, size := range []int{3000, 10000} {
		t.Run(strconv.Itoa(size), func(t *testing.T) {
			e := newEnv(t)
			items := make([]string, size)
			if err := e.q.Transaction(t.Context(), func(q *db.Queries) error {
				for i := range items {
					tmdb := int64(2000000 + i)
					if _, err := q.UpsertMovie(t.Context(), db.UpsertMovieParams{LibraryID: e.movies, TmdbID: tmdb, Title: fmt.Sprintf("Fictional load film %d", i), Genres: "[]", Credits: "[]"}); err != nil {
						return err
					}
					items[i] = plextest.MovieJSON(strconv.Itoa(3000000+i), fmt.Sprintf("Fictional load film %d", i), strconv.FormatInt(tmdb, 10), "", 1)
				}
				return nil
			}); err != nil {
				t.Fatal(err)
			}
			e.f.SetItems("owner", "1", items...)
			c := e.connect()
			c = e.link(c, []PlexUserLink{{PlexUserID: 701, UserID: e.admin}}, []PlexLibraryLink{{SectionID: 1, LibraryID: e.movies}})
			start := time.Now()
			r := e.previewRun(c, false)
			previewCost := time.Since(start)
			start = time.Now()
			r = e.tickRun(c, r)
			importCost := time.Since(start)
			t.Logf("matched=%d preview=%s actual-import=%s marked=%d status=%s", size, previewCost, importCost, r.Counts.Marked, r.Status)
			if r.Status != "complete" || int(r.Counts.Marked) != size {
				t.Fatalf("import incomplete: %+v", r)
			}
			guards, err := e.q.ListRunWatchGuards(t.Context(), &r.ID)
			if err != nil || len(guards) != size || e.count("watch_state") != size || e.count("plays") != 0 {
				t.Fatalf("large import lost ownership or invented playback: guards=%d state=%d plays=%d error=%v", len(guards), e.count("watch_state"), e.count("plays"), err)
			}
			for _, guard := range guards {
				if guard.UserID != e.admin || guard.ItemType != "movie" || guard.Owner != "import" || guard.ImportRun == nil || *guard.ImportRun != r.ID {
					t.Fatalf("large import changed guard ownership: %+v", guard)
				}
			}
			snapshotReads := 0
			for _, path := range e.f.ReadsBy(plextest.OwnerDeviceToken) {
				if path == "/library/sections/1/all" {
					snapshotReads++
				}
			}
			// One approved preview and one fresh execution pass; item changes
			// must not turn into one provider snapshot per watchstate batch.
			expectedReads := 2 * ((size + 199) / 200)
			if snapshotReads != expectedReads {
				t.Fatalf("snapshot reads=%d want%d bounded by pages", snapshotReads, expectedReads)
			}
		})
	}
}
