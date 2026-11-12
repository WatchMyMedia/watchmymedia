package dvr

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/settings"
)

// The browser can load all four recording sections and a guide window in
// one burst. Keep the actual query and mapping cost bounded at that load,
// including a page deep in years of history, without planner statistics.
func TestDVRSpeed(t *testing.T) {
	if testing.Short() {
		t.Skip("speed budgets run without the race detector via make bench")
	}
	conn, q := dbtest.Open(t)
	store := settings.New(q)
	live := livetv.New(q, store, nil)
	s := New(q, store, live, nil, Options{})
	defer s.Close()
	config := Settings{Enabled: true, Access: "admins"}
	data, _ := json.Marshal(config)
	if err := store.Set(t.Context(), settingsKey, string(data)); err != nil {
		t.Fatal(err)
	}
	ids := []string{}
	start := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	err := q.Transaction(t.Context(), func(tx *db.Queries) error {
		for i := range 10_000 {
			state := []string{"scheduled", "conflict", "recording", "done"}[i%4]
			id := fmt.Sprintf("%05d", i)
			r := record{ID: id, ProgrammeID: "programme-" + id, ChannelID: "channel", SourceID: "source", Number: "7.1", Start: start.Add(time.Duration(i) * time.Hour), End: start.Add(time.Duration(i+1) * time.Hour), Title: "Inspector Vane", EpisodeTitle: "The orchard", State: state, Gaps: []Gap{}, CreatedAt: start.Unix(), Priority: int64(i)}
			if i < 300 {
				ids = append(ids, r.ProgrammeID)
			}
			if err := put(t.Context(), tx, r, start.Unix()); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	assertPlan(t, conn, "live_tv_recording_section", "SELECT * FROM live_tv_recordings INDEXED BY live_tv_recording_section WHERE section=? AND start_at>=? AND (start_at>? OR id>?) ORDER BY start_at,id LIMIT 21", "recorded", start.Unix(), start.Unix(), "")
	marks := strings.TrimSuffix(strings.Repeat("?,", len(ids)), ",")
	args := make([]any, len(ids))
	for i, id := range ids {
		args[i] = id
	}
	assertPlan(t, conn, "live_tv_recording_programme", "SELECT * FROM live_tv_recordings INDEXED BY live_tv_recording_programme WHERE programme_id IN ("+marks+")", args...)
	assertPlan(t, conn, "live_tv_programme_series", "SELECT * FROM live_tv_programmes WHERE series_key=? AND end_at>? ORDER BY start_at,channel_id", "series", start.Unix())
	runs := []time.Duration{}
	for range 30 {
		before := time.Now()
		var wg sync.WaitGroup
		errs := make(chan error, 5)
		for _, sect := range []string{"now", "conflicts", "upcoming", "recorded"} {
			wg.Go(func() {
				page, err := s.List(t.Context(), Actor{Admin: true}, sect, "")
				if err == nil && len(page.Items) != 20 {
					err = fmt.Errorf("%s has %d rows", sect, len(page.Items))
				}
				errs <- err
			})
		}
		wg.Go(func() {
			marks, err := s.Marks(t.Context(), ids, Actor{Admin: true})
			if err == nil && len(marks) != 300 {
				err = fmt.Errorf("guide marks=%d", len(marks))
			}
			errs <- err
		})
		wg.Wait()
		close(errs)
		for err := range errs {
			if err != nil {
				t.Fatal(err)
			}
		}
		runs = append(runs, time.Since(before))
	}
	slices.Sort(runs)
	p95 := runs[len(runs)*95/100]
	budget := 250 * time.Millisecond
	if os.Getenv("WMM_DVR_STRICT") == "1" {
		budget = 50 * time.Millisecond
	}
	t.Logf("10,000 recordings, four section pages + 300 guide marks: p95 %s (budget %s)", p95, budget)
	if p95 > budget {
		t.Fatalf("DVR page burst exceeded budget: %s > %s", p95, budget)
	}
}
func assertPlan(t testing.TB, conn *sql.DB, index, query string, args ...any) {
	t.Helper()
	rows, err := conn.QueryContext(context.Background(), "EXPLAIN QUERY PLAN "+query, args...)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = rows.Close() }()
	details := []string{}
	for rows.Next() {
		var id, parent, unused int
		var detail string
		if err := rows.Scan(&id, &parent, &unused, &detail); err != nil {
			t.Fatal(err)
		}
		details = append(details, detail)
	}
	if err = rows.Err(); err != nil {
		t.Fatal(err)
	}
	found := false
	for _, d := range details {
		if strings.Contains(d, "TEMP B-TREE") {
			t.Fatalf("query sorts outside its index: %v", details)
		}
		if strings.Contains(d, "SEARCH") && strings.Contains(d, index) {
			found = true
		}
	}
	if !found {
		t.Fatalf("query lost its indexed range: %v", details)
	}
	t.Logf("%s: %s", index, strings.Join(details, "; "))
}
