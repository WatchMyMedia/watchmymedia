package metadata

import (
	"slices"
	"testing"
)

// A write that fails part way leaves nothing half done: no show stored with
// only some of its seasons, and no file matched with only some of its
// episodes linked.
func TestFailedWritesLeaveNothingHalfDone(t *testing.T) {
	e := setup(t)
	f := e.file(e.tv, "/t/Breaking Bad/Season 01/Breaking Bad - S01E01.mkv", `{"kind":"episode","title":"Breaking Bad","season":1,"episodes":[1]}`)
	count := func() (shows, seasons, episodes int) {
		t.Helper()
		err := e.conn.QueryRowContext(e.ctx, `SELECT (SELECT count(*) FROM shows), (SELECT count(*) FROM seasons), (SELECT count(*) FROM episodes)`).
			Scan(&shows, &seasons, &episodes)
		if err != nil {
			t.Fatal(err)
		}
		return shows, seasons, episodes
	}

	// Storing season 2's only episode fails, as on a full disk, after the
	// show and season 1 were written.
	if _, err := e.conn.ExecContext(e.ctx, `CREATE TRIGGER fail_episode BEFORE INSERT ON episodes
		WHEN NEW.title = 'Seven Thirty-Seven' BEGIN SELECT RAISE(ABORT, 'disk I/O error'); END`); err != nil {
		t.Fatal(err)
	}
	sum, err := e.m.MatchLibrary(e.ctx, e.tv, false)
	if err != nil {
		t.Fatal(err)
	}
	if sum.Failed != 1 {
		t.Errorf("summary = %+v, want the file failed", sum)
	}
	if shows, seasons, episodes := count(); shows+seasons+episodes != 0 {
		t.Errorf("left %d shows, %d seasons, %d episodes", shows, seasons, episodes)
	}

	if _, err := e.conn.ExecContext(e.ctx, `DROP TRIGGER fail_episode`); err != nil {
		t.Fatal(err)
	}
	if sum, err := e.m.MatchLibrary(e.ctx, e.tv, true); err != nil || sum.Matched != 1 {
		t.Fatalf("second run = %+v, %v", sum, err)
	}
	if shows, seasons, episodes := count(); shows != 1 || seasons != 2 || episodes != 3 {
		t.Errorf("stored %d shows, %d seasons, %d episodes", shows, seasons, episodes)
	}
	linked, _ := e.q.ListEpisodeIDsForFile(e.ctx, f.ID)
	if len(linked) != 1 {
		t.Fatalf("linked episodes = %v", linked)
	}

	// Recording the file again with an episode that can't be linked fails
	// as a whole: the file keeps its match, its lock state and its episode.
	if err := e.m.record(e.ctx, f.ID, StatusUnmatched, nil, []int64{linked[0], 999999}, nil, new("x"), true); err == nil {
		t.Fatal("linking an episode that does not exist worked")
	}
	row, _ := e.q.GetMediaFile(e.ctx, f.ID)
	if row.MatchStatus != StatusMatched || row.MatchLocked != 0 || row.MatchError != nil {
		t.Errorf("file after a failed record = %s locked=%d err=%v", row.MatchStatus, row.MatchLocked, deref(row.MatchError))
	}
	if now, _ := e.q.ListEpisodeIDsForFile(e.ctx, f.ID); !slices.Equal(now, linked) {
		t.Errorf("linked episodes = %v, want %v", now, linked)
	}
}
