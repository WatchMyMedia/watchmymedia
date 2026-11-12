package api

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"testing"

	"watchmymedia/server/internal/db"
)

// Ignoring a selection bigger than one query's chunk checks every file
// first and changes all of them or none.
func TestIgnoreFilesInBulk(t *testing.T) {
	t.Parallel()
	var conn *sql.DB
	srv := newTestServerWith(t, func(d *Deps) { conn = d.Conn })
	ctx := t.Context()
	tv, err := srv.q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: "/t"})
	if err != nil {
		t.Fatal(err)
	}
	var ids []int64
	err = srv.q.Transaction(ctx, func(q *db.Queries) error {
		for i := range 2*chunkSize + 7 {
			f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: tv.ID, Path: fmt.Sprintf("/t/Extras/clip %04d.mkv", i), Size: 1,
				Mtime: "2026-01-01T00:00:00Z", AddedAt: "2026-01-01T00:00:00Z", LastSeenAt: "2026-01-01T00:00:00Z", Parsed: "{}"})
			if err != nil {
				return err
			}
			if err := q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "unmatched", ID: f.ID}); err != nil {
				return err
			}
			ids = append(ids, f.ID)
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	ignored := func() (n int) {
		t.Helper()
		if err := conn.QueryRowContext(t.Context(), `SELECT COUNT(*) FROM media_files WHERE library_id = ? AND match_ignored = 1`, tv.ID).Scan(&n); err != nil {
			t.Fatal(err)
		}
		return n
	}
	send := func(ids []int64, ignored bool) int {
		t.Helper()
		b, _ := json.Marshal(map[string]any{"fileIds": ids, "ignored": ignored})
		return doJSON(t, srv.client, http.MethodPost, fmt.Sprintf("%s/api/libraries/%d/ignore-files", srv.URL, tv.ID), string(b), nil).StatusCode
	}
	// The movie library's file, last, fails the whole selection.
	if code := send(append(append([]int64{}, ids...), 1), true); code != http.StatusUnprocessableEntity || ignored() != 0 {
		t.Fatalf("with a file of another library -> %d, %d ignored; want 422 and none", code, ignored())
	}
	if code := send(append(ids, ids[0]), true); code != http.StatusNoContent || ignored() != len(ids) {
		t.Fatalf("ignore -> %d, %d ignored; want 204 and %d", code, ignored(), len(ids))
	}
	if code := send(ids[:chunkSize+1], false); code != http.StatusNoContent || ignored() != len(ids)-chunkSize-1 {
		t.Fatalf("bring back -> %d, %d ignored", code, ignored())
	}
}
