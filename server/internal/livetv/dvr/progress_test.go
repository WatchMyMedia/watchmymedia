package dvr

import (
	"database/sql"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/watchstate"
)

func TestProgressTransferRetainsFailuresAndPreservesNewerLocalPlay(t *testing.T) {
	t.Parallel()
	f := setup(t, nil)
	ctx := t.Context()
	watch := watchstate.New(f.q, nil)
	f.s.opts.Watch = watch
	res, err := f.conn.ExecContext(ctx, "INSERT INTO movies(library_id,tmdb_id,title) VALUES (?,123,'The orchard')", f.lib.ID)
	if err != nil {
		t.Fatal(err)
	}
	movieID, err := res.LastInsertId()
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(f.lib.Path, "The orchard.ts")
	res, err = f.conn.ExecContext(ctx, "INSERT INTO media_files(library_id,path,size,mtime,status,parsed,movie_id) VALUES (?,?,188,?,'present','{}',?)", f.lib.ID, path, db.FormatTime(time.Now()), movieID)
	if err != nil {
		t.Fatal(err)
	}
	fileID, err := res.LastInsertId()
	if err != nil {
		t.Fatal(err)
	}
	now := unix(f.clock.Load())
	r := record{ID: "progress", UserID: &f.actor.UserID, State: "done", Start: now.Add(-time.Hour), End: now, Gaps: []Gap{}, LibraryID: f.lib.ID, Path: path, CreatedAt: now.Unix()}
	if err = put(ctx, f.q, r, now.Unix()); err != nil {
		t.Fatal(err)
	}
	if err = f.s.ReportProgress(ctx, f.actor.UserID, r.ID, 1000, 60_000); err != nil {
		t.Fatal(err)
	}
	// Linking before a metadata match must retain the private checkpoint.
	r.MediaFileID = &fileID
	if err = put(ctx, f.q, r, now.Unix()); err != nil {
		t.Fatal(err)
	}
	f.s.link(ctx)
	// The library's progress is against the whole recording (here its hour,
	// the file having no probed length), not the length recorded when the
	// report was made: stopping near "now" mustn't read as nearly watched.
	pos, dur, err := f.s.Progress(ctx, f.actor.UserID, r.ID)
	if err != nil || pos != 1000 || dur != 3_600_000 {
		t.Fatal(pos, dur, err)
	}
	if _, err = f.q.GetDVRProgress(ctx, db.GetDVRProgressParams{RecordingID: r.ID, UserID: f.actor.UserID}); !errors.Is(err, sql.ErrNoRows) {
		t.Fatal("successfully transferred checkpoint was not retired", err)
	}
	// An already newer library play wins over a delayed recording report.
	if _, err = watch.ReportAt(ctx, watchstate.Progress{UserID: f.actor.UserID, ItemType: watchstate.Movie, ItemIDs: []int64{movieID}, FileID: fileID, PositionMs: 10_000, DurationMs: 60_000}, now.Add(10*time.Second)); err != nil {
		t.Fatal(err)
	}
	if err = f.s.ReportProgress(ctx, f.actor.UserID, r.ID, 2000, 60_000); err != nil {
		t.Fatal(err)
	}
	pos, _, err = f.s.Progress(ctx, f.actor.UserID, r.ID)
	if err != nil || pos != 10_000 {
		t.Fatal(pos, err)
	}
	// A failed application leaves the durable checkpoint for an event-driven
	// retry, and the report handler never acknowledges a discarded position.
	_, err = f.conn.ExecContext(ctx, "CREATE TRIGGER refuse_dvr_progress BEFORE INSERT ON watch_state BEGIN SELECT RAISE(ABORT,'injected transfer failure'); END")
	if err != nil {
		t.Fatal(err)
	}
	f.clock.Add(20)
	if err = f.s.ReportProgress(ctx, f.actor.UserID, r.ID, 20_000, 60_000); err == nil {
		t.Fatal("injected transfer succeeded")
	}
	pending, err := f.q.GetDVRProgress(ctx, db.GetDVRProgressParams{RecordingID: r.ID, UserID: f.actor.UserID})
	if err != nil || pending.PositionMs != 20_000 {
		t.Fatal(pending, err)
	}
	if _, err = f.conn.ExecContext(ctx, "DROP TRIGGER refuse_dvr_progress"); err != nil {
		t.Fatal(err)
	}
	f.s.link(ctx)
	pos, _, err = f.s.Progress(ctx, f.actor.UserID, r.ID)
	if err != nil || pos != 20_000 {
		t.Fatal(pos, err)
	}
	// Retiring one version cannot erase a report written after it was read.
	old := db.PutDVRProgressParams{RecordingID: r.ID, UserID: f.actor.UserID, PositionMs: 30_000, DurationMs: 60_000, UpdatedAt: now.UnixMilli() + 30_000}
	if err = f.q.PutDVRProgress(ctx, old); err != nil {
		t.Fatal(err)
	}
	latest := old
	latest.PositionMs = 40_000
	latest.UpdatedAt++
	if err = f.q.PutDVRProgress(ctx, latest); err != nil {
		t.Fatal(err)
	}
	if err = f.q.DeleteDVRProgressVersion(ctx, db.DeleteDVRProgressVersionParams{RecordingID: old.RecordingID, UserID: old.UserID, UpdatedAt: old.UpdatedAt, PositionMs: old.PositionMs, DurationMs: old.DurationMs}); err != nil {
		t.Fatal(err)
	}
	checkpoint, err := f.q.GetDVRProgress(ctx, db.GetDVRProgressParams{RecordingID: r.ID, UserID: f.actor.UserID})
	if err != nil || checkpoint.PositionMs != 40_000 {
		t.Fatal(checkpoint, err)
	}
}
