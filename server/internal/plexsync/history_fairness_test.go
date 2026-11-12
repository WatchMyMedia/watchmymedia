package plexsync

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/plex"
	"watchmymedia/server/internal/plex/plextest"
)

func TestHistoryBudgetVisitsEveryMappedScope(t *testing.T) {
	e := newEnv(t)
	sections := make([]map[string]string, 126)
	libraries := make([]PlexLibraryLink, 126)
	for i := range sections {
		id := int64(i + 1)
		sections[i] = map[string]string{"key": strconv.FormatInt(id, 10), "uuid": fmt.Sprintf("fair-section-%d", id), "title": fmt.Sprintf("Fictional library %d", id), "type": "movie"}
		library, err := e.q.UpsertLibrary(t.Context(), db.UpsertLibraryParams{Type: "movie", Name: fmt.Sprintf("fair-%d", id), Path: fmt.Sprintf("/fictional-fairness/%d", id)})
		if err != nil {
			t.Fatal(err)
		}
		libraries[i] = PlexLibraryLink{SectionID: id, LibraryID: library.ID}
	}
	payload, err := json.Marshal(map[string]any{"MediaContainer": map[string]any{"size": len(sections), "Directory": sections}})
	if err != nil {
		t.Fatal(err)
	}
	event := historyEvent("991", 706, plex.Item{RatingKey: "999991", Type: "movie", LibrarySectionID: "126", Title: "Fictional final-scope film", ExternalIDs: plex.ExternalIDs{TMDB: "999991"}})
	e.f.Set(func(f *plextest.Fake) {
		f.Sections = string(payload)
		f.HistoryEvents["owner"] = map[string][]plex.HistoryEvent{"126": {event}}
		f.HistoryEvents["synthetic-server-706"] = map[string][]plex.HistoryEvent{"126": {event}}
	})
	c := e.connect()
	c = e.link(c, []PlexUserLink{{PlexUserID: 701, UserID: e.admin}, {PlexUserID: 706, UserID: e.viewer}}, libraries)
	if e.svc.historyPages != 250 {
		t.Fatalf("not using production page budget: %d", e.svc.historyPages)
	}
	for attempt := 1; attempt <= 3; attempt++ {
		started := time.Now()
		r := e.tickRun(c, e.previewRun(c, false))
		var checkpoints int
		if err := e.conn.QueryRowContext(t.Context(), "SELECT COUNT(*) FROM plex_history_checkpoints").Scan(&checkpoints); err != nil {
			t.Fatal(err)
		}
		t.Logf("run %d: status=%s history=%s checkpoints=%d/252 imported=%d elapsed=%s", attempt, r.Status, r.History, checkpoints, r.Counts.HistoryImported, time.Since(started))
	}
	_, err = e.q.GetPlexHistoryCheckpoint(t.Context(), db.GetPlexHistoryCheckpointParams{ConnectionID: c.ID, UserID: e.viewer, SectionID: 126})
	if errors.Is(err, sql.ErrNoRows) {
		t.Fatal("last mapped scope is still never visited after three runs with production 250-page budget; completed earlier scopes consume the budget again")
	}
	if err != nil {
		t.Fatal(err)
	}
	history, err := e.svc.History(t.Context(), e.viewer, 50, 0)
	if err != nil || len(history.Items) != 1 {
		t.Fatalf("last scoped event not imported: %+v %v", history, err)
	}
}

func TestHistoryBudgetRotatesBackfillAndIncrementalReadsAcrossRestart(t *testing.T) {
	e, c := runEnv(t)
	now := time.Now().UTC().Add(2 * time.Minute)
	e.svc.now = func() time.Time { return now }
	e.svc.historyPages = 1
	owner := make([]plex.HistoryEvent, 201)
	for i := range owner {
		owner[i] = historyEvent(strconv.Itoa(20000+i), 1, plex.Item{RatingKey: "101", Type: "movie", LibrarySectionID: "1", Title: "Beacon", ExternalIDs: plex.ExternalIDs{TMDB: "810001", IMDb: "tt0810001"}})
	}
	viewer := historyEvent("30000", 706, plex.Item{RatingKey: "108", Type: "movie", LibrarySectionID: "1", Title: "Copper", ExternalIDs: plex.ExternalIDs{TMDB: "810003"}})
	e.f.Set(func(f *plextest.Fake) {
		f.HistoryEvents["owner"] = map[string][]plex.HistoryEvent{"1": append(append([]plex.HistoryEvent(nil), owner...), viewer)}
		f.HistoryEvents["synthetic-server-706"] = map[string][]plex.HistoryEvent{"1": {viewer}}
	})
	first := e.tickRun(c, e.previewRun(c, false))
	if first.Counts.HistoryImported != 200 {
		t.Fatalf("first page %+v", first)
	}
	// The last-served scope survives process restart. The next run must visit the
	// other person instead of spending its only page on the same backfill again.
	e.svc = e.open()
	e.svc.now = func() time.Time { return now }
	e.svc.historyPages = 1
	second := e.tickRun(c, e.previewRun(c, false))
	if second.Counts.HistoryImported != 1 {
		t.Fatalf("other person did not progress after restart: %+v", second)
	}
	third := e.tickRun(c, e.previewRun(c, false))
	if third.Counts.HistoryImported != 1 {
		t.Fatalf("original backfill did not resume: %+v", third)
	}
	checkpoint, err := e.q.GetPlexHistoryCheckpoint(t.Context(), db.GetPlexHistoryCheckpointParams{ConnectionID: c.ID, UserID: e.admin, SectionID: 1})
	if err != nil || checkpoint.Offset != 201 || checkpoint.Complete != 1 {
		t.Fatalf("backfill checkpoint %+v %v", checkpoint, err)
	}
	now = now.Add(15 * time.Minute)
	next := historyEvent("30001", 706, viewer.Item)
	next.ViewedAt = now.Add(-time.Second)
	e.f.Set(func(f *plextest.Fake) {
		f.HistoryEvents["owner"]["1"] = append(f.HistoryEvents["owner"]["1"], next)
		f.HistoryEvents["synthetic-server-706"]["1"] = append(f.HistoryEvents["synthetic-server-706"]["1"], next)
	})
	fourth := e.tickRun(c, e.previewRun(c, false))
	if fourth.Counts.HistoryImported != 1 {
		t.Fatalf("completed scope did not receive an incremental check: %+v", fourth)
	}
	before, err := e.q.GetPlexConnection(t.Context(), c.ID)
	if err != nil || before.HistoryCursorUser != 706 || before.HistoryCursorSection != 1 {
		t.Fatalf("committed cursor %+v %v", before, err)
	}
	c = e.link(c, []PlexUserLink{{PlexUserID: 701, UserID: e.admin}, {PlexUserID: 706, UserID: e.viewer}}, []PlexLibraryLink{{SectionID: 1, LibraryID: e.movies}})
	after, err := e.q.GetPlexConnection(t.Context(), c.ID)
	if err != nil || after.HistoryCursorUser != 0 || after.HistoryCursorSection != 0 {
		t.Fatalf("new mapping generation retained old cursor %+v %v", after, err)
	}
	n, err := e.q.SetPlexHistoryCursor(t.Context(), db.SetPlexHistoryCursorParams{PlexUserID: 706, SectionID: 1, ID: c.ID, Generation: before.Generation})
	if err != nil || n != 0 {
		t.Fatalf("old generation changed cursor: %d %v", n, err)
	}
}
