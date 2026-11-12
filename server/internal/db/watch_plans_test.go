package db

import (
	"context"
	"database/sql"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"testing"
)

// The queries that find watch state and guards by item, and the delete
// triggers, look up each item rather than reading every user's rows (a
// search on item_type alone reads all of them too). The server never runs
// ANALYZE, so these are the plans real libraries get.
func TestWatchQueriesUseIndexes(t *testing.T) {
	ctx := context.Background()
	conn, err := Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = conn.Close() }()
	if err := Migrate(ctx, conn); err != nil {
		t.Fatal(err)
	}
	for name, q := range map[string]string{
		"GetWatchGuard":            getWatchGuard,
		"ListItemWatchUsers":       listItemWatchUsers,
		"DeleteWatchGuardsForItem": deleteWatchGuardsForItem,
		"DeleteWatchStatesForItem": deleteWatchStatesForItem,
		"CountMovieWatchStates":    countMovieWatchStates,
		"ListShowWatchedEpisodes":  listShowWatchedEpisodes,
		"ListShowGuardedEpisodes":  listShowGuardedEpisodes,
		"GetEpisodeIdentity":       getEpisodeIdentity,
		"ClaimSeasonWatchGuards":   claimSeasonWatchGuards,
		"ClaimShowWatchGuards":     claimShowWatchGuards,
		"movies_delete_watch":      `DELETE FROM watch_state WHERE item_type = 'movie' AND item_id = ?`,
		"episodes_delete_watch":    `DELETE FROM watch_guards WHERE item_type = 'episode' AND item_id = ?`,
	} {
		plan := queryPlan(t, conn, name, q)
		for _, step := range plan {
			watch := strings.Contains(step, "watch_state") || strings.Contains(step, "watch_guards")
			if strings.HasPrefix(step, "SCAN ") || watch && !strings.Contains(step, "item_id=?") {
				t.Errorf("%s does not look up one item:\n  %s", name, strings.Join(plan, "\n  "))
				break
			}
		}
	}
}

// queryPlan is EXPLAIN QUERY PLAN's steps for q, with 1 for every
// parameter, on a database that has never been analyzed.
func queryPlan(t *testing.T, conn *sql.DB, name, q string) []string {
	t.Helper()
	n := strings.Count(q, "?")
	if m := regexp.MustCompile(`\?(\d+)`).FindAllStringSubmatch(q, -1); m != nil {
		n = 0
		for _, s := range m {
			i, _ := strconv.Atoi(s[1])
			n = max(n, i)
		}
	}
	args := make([]any, n)
	for i := range args {
		args[i] = int64(1)
	}
	rows, err := conn.QueryContext(t.Context(), "EXPLAIN QUERY PLAN "+q, args...)
	if err != nil {
		t.Fatalf("%s: %v", name, err)
	}
	defer func() { _ = rows.Close() }()
	var plan []string
	for rows.Next() {
		var id, parent, unused int
		var detail string
		if err := rows.Scan(&id, &parent, &unused, &detail); err != nil {
			t.Fatal(err)
		}
		plan = append(plan, detail)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	return plan
}
