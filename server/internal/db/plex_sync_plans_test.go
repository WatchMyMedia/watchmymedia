package db

import (
	"strings"
	"testing"
)

// Scope checkpoints, history pages, exclusions and lifecycle cleanup must look
// up their selected run/user/item. The server never runs ANALYZE.
func TestPlexImportQueriesUseScopedIndexes(t *testing.T) {
	t.Parallel()
	conn, _ := openTest(t)
	for name, query := range map[string]string{
		"ready work":                nextPlexRun,
		"active work":               listActivePlexRuns,
		"approved import":           hasApprovedPlexRun,
		"scope checkpoint":          setPlexScopeOutcome,
		"history rotation cursor":   setPlexHistoryCursor,
		"source identity":           getPlexSourceFingerprint,
		"history page":              listPlexHistory,
		"exclusion display":         listPlexExclusionDisplay,
		"run ownership":             validPlexRun,
		"undo marks":                listRunWatchGuards,
		"movie history cleanup":     `UPDATE plex_history_events SET item_id=NULL WHERE item_type='movie' AND item_id=?`,
		"episode exclusion cleanup": `DELETE FROM plex_exclusions WHERE item_type='episode' AND item_id=?`,
		"exclusion rematch":         movePlexExclusions,
	} {
		plan := queryPlan(t, conn, name, query)
		for _, step := range plan {
			if strings.HasPrefix(step, "SCAN ") && step != "SCAN CONSTANT ROW" {
				t.Errorf("%s scans unselected rows: %s", name, strings.Join(plan, "; "))
			}
		}
		t.Logf("%s: %s", name, strings.Join(plan, "; "))
	}
}
