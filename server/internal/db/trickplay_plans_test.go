package db

import (
	"database/sql"
	"fmt"
	"strings"
	"testing"
	"time"
)

// The seek thumbnails' work list and status walk the index that holds what
// they read, in order, rather than every media_files row with its probe
// (kilobytes on overflow pages), and look up each file's thumbnails by key.
// The server never runs ANALYZE, so these are the plans real libraries get.
func TestTrickplayQueriesUseTheIndex(t *testing.T) {
	t.Parallel()
	conn, q := openTest(t)
	ctx := t.Context()
	lib, err := q.UpsertLibrary(ctx, UpsertLibraryParams{Type: "movie", Name: "Movies", Path: "/m"})
	if err != nil {
		t.Fatal(err)
	}
	probe := `{"container":"matroska","durationMs":2700000,"video":[{"codec":"h264"}],"padding":"` + strings.Repeat("x", 6000) + `"}`
	h264, dur := "h264", int64(2_700_000)
	now := FormatTime(time.Now())
	for i := range 500 {
		if _, err := q.UpsertMediaFile(ctx, UpsertMediaFileParams{LibraryID: lib.ID, Path: fmt.Sprintf("/m/f%d.mkv", i), Size: int64(i), Mtime: now, AddedAt: now,
			Parsed: "{}", Probe: &probe, VideoCodec: &h264, DurationMs: &dur, LastSeenAt: now, SeenInScan: 1}); err != nil {
			t.Fatal(err)
		}
	}
	for name, c := range map[string]struct {
		query string
		args  []any
	}{
		"ListFilesNeedingTrickplay": {listFilesNeedingTrickplay, []any{int64(1), true, true, "\uffff", int64(1 << 62), int64(50)}},
		"CountTrickplay":            {countTrickplay, []any{true, true, int64(1)}},
	} {
		plan := explainWithArgs(t, conn, c.query, c.args...)
		joined := strings.Join(plan, "\n  ")
		if !strings.Contains(joined, "media_files_trickplay_idx (status=?") || strings.Contains(joined, "TEMP B-TREE") ||
			!strings.Contains(joined, "SEARCH t USING INTEGER PRIMARY KEY") {
			t.Errorf("%s:\n  %s", name, joined)
		}
		t.Logf("%s:\n  %s", name, joined)
	}
}

// explainWithArgs is EXPLAIN QUERY PLAN's steps for query with these arguments.
func explainWithArgs(t *testing.T, conn *sql.DB, query string, args ...any) []string {
	t.Helper()
	rows, err := conn.QueryContext(t.Context(), "EXPLAIN QUERY PLAN "+query, args...)
	if err != nil {
		t.Fatal(err)
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
