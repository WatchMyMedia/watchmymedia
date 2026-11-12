package plexsync

import (
	"database/sql"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/plex"
	"watchmymedia/server/internal/plex/plextest"
	"watchmymedia/server/internal/watchstate"
)

func runEnv(t *testing.T) (*env, PlexConnection) {
	t.Helper()
	e := newEnv(t)
	e.f.SetItems("owner", "1", plextest.MovieJSON("101", "Beacon", "810001", "tt0810001", 1))
	e.f.SetItems("synthetic-server-706", "1", plextest.MovieJSON("108", "Copper", "810003", "", 1))
	c := e.connect()
	c = e.link(c, []PlexUserLink{{PlexUserID: 701, UserID: e.admin}, {PlexUserID: 706, UserID: e.viewer}}, []PlexLibraryLink{{SectionID: 1, LibraryID: e.movies}})
	return e, c
}
func (e *env) previewRun(c PlexConnection, enable bool) PlexRun {
	e.t.Helper()
	p, err := e.svc.StartPreviewFor(e.t.Context(), c.ID, e.who)
	if err != nil {
		e.t.Fatal(err)
	}
	p = e.waitPreview(c, p.ID)
	if p.Status != "complete" {
		e.t.Fatalf("preview %+v", p)
	}
	r, err := e.svc.StartRun(e.t.Context(), c.ID, e.who, PlexRunStart{PreviewID: p.ID, EnableSync: enable})
	if err != nil {
		e.t.Fatal(err)
	}
	return r
}
func (e *env) tickRun(c PlexConnection, r PlexRun) PlexRun {
	e.t.Helper()
	if err := e.svc.tick(e.t.Context()); err != nil {
		e.t.Fatal(err)
	}
	out, err := e.svc.GetRun(e.t.Context(), c.ID, r.ID)
	if err != nil {
		e.t.Fatal(err)
	}
	return out
}
func (e *env) watchRow(user, id int64) *db.WatchState {
	e.t.Helper()
	v, err := e.q.GetWatchState(e.t.Context(), db.GetWatchStateParams{UserID: user, ItemType: "movie", ItemID: id})
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		e.t.Fatal(err)
	}
	return &v
}
func TestRunAppliesOnlyEachMappedPersonsOwnMarksAndReplaysSafely(t *testing.T) {
	e, c := runEnv(t)
	ctx := t.Context()
	if err := e.watch.MarkUnwatched(ctx, e.viewer, "movie", e.ids["beacon"]); err != nil {
		t.Fatal(err)
	}
	r := e.previewRun(c, true)
	r = e.tickRun(c, r)
	if r.Status != "complete" || r.Counts.Marked != 2 {
		t.Fatalf("run %+v", r)
	}
	if e.watchRow(e.admin, e.ids["beacon"]) == nil || e.watchRow(e.viewer, e.ids["copper"]) == nil {
		t.Fatal("expected independent marks")
	}
	if e.watchRow(e.viewer, e.ids["beacon"]) != nil || e.watchRow(e.admin, e.ids["copper"]) != nil {
		t.Fatal("marks crossed people")
	}
	before := e.watchRow(e.admin, e.ids["beacon"]).UpdatedAt
	c2, _ := e.svc.Connections(ctx)
	if !c2[0].SyncEnabled {
		t.Fatal("explicit recurring choice was lost")
	}
	next := e.tickRun(c, e.previewRun(c, false))
	if next.Counts.Marked != 0 || next.Counts.AlreadyWatched != 2 {
		t.Fatalf("replay %+v", next)
	}
	if e.watchRow(e.admin, e.ids["beacon"]).UpdatedAt != before {
		t.Fatal("replay churned timestamp")
	}
	if e.count("plays") != 0 {
		t.Fatal("import invented measured playback")
	}
}
func TestRunRequiresFreshBoundSingleUsePreview(t *testing.T) {
	e, c := runEnv(t)
	ctx := t.Context()
	p, _ := e.svc.StartPreviewFor(ctx, c.ID, e.who)
	p = e.waitPreview(c, p.ID)
	if _, err := e.svc.StartRun(ctx, c.ID, Requester{UserID: e.admin, Session: "another-session"}, PlexRunStart{PreviewID: p.ID}); err == nil {
		t.Fatal("changed session accepted")
	}
	if _, err := e.svc.StartRun(ctx, c.ID, e.who, PlexRunStart{PreviewID: p.ID}); err != nil {
		t.Fatal(err)
	}
	if _, err := e.svc.StartRun(ctx, c.ID, e.who, PlexRunStart{PreviewID: p.ID}); err == nil {
		t.Fatal("preview replay accepted")
	}
}
func TestRunDoesNotExpandApprovedSnapshotAndRechecksCurrentWatchedState(t *testing.T) {
	for _, unwatched := range []bool{false, true} {
		t.Run(strconv.FormatBool(unwatched), func(t *testing.T) {
			e, c := runEnv(t)
			r := e.previewRun(c, false)
			count := 1
			if unwatched {
				count = 0
			}
			e.f.SetItems("owner", "1", plextest.MovieJSON("101", "Beacon", "810001", "tt0810001", count), plextest.MovieJSON("108", "Copper", "810003", "", 1))
			e.tickRun(c, r)
			if e.watchRow(e.admin, e.ids["copper"]) != nil {
				t.Fatal("newly watched item silently entered approved preview")
			}
			if unwatched && e.watchRow(e.admin, e.ids["beacon"]) != nil {
				t.Fatal("stale watched fact applied")
			}
		})
	}
}
func TestRunPreservesLocalChoiceArrivingImmediatelyBeforeCommit(t *testing.T) {
	e, c := runEnv(t)
	r := e.previewRun(c, false)
	var once sync.Once
	e.svc.beforeImportCommit = func() {
		once.Do(func() {
			if err := e.watch.MarkUnwatched(t.Context(), e.admin, "movie", e.ids["beacon"]); err != nil {
				t.Fatal(err)
			}
		})
	}
	r = e.tickRun(c, r)
	if e.watchRow(e.admin, e.ids["beacon"]) != nil || r.Counts.LocalKept != 1 {
		t.Fatalf("local choice lost: %+v", r)
	}
}
func TestRunParentRematchOrOrderChangeCannotMarkApprovedOldEpisode(t *testing.T) {
	for _, replacement := range []string{`{"ratingKey":"301","type":"show","episodeOrdering":"tmdbAiring","Guid":[{"id":"tmdb://999999"}]}`, `{"ratingKey":"301","type":"show","episodeOrdering":"dvd","Guid":[{"id":"tmdb://820001"}]}`} {
		t.Run(replacement, func(t *testing.T) {
			e := newEnv(t)
			e.f.SetItems("owner", "2", plextest.EpisodeJSON("201", "Pilot", "301", 1, 1, 1))
			e.f.Set(func(f *plextest.Fake) {
				f.Shows["301"] = `{"ratingKey":"301","type":"show","episodeOrdering":"tmdbAiring","Guid":[{"id":"tmdb://820001"}]}`
			})
			c := e.connect()
			c = e.link(c, []PlexUserLink{{PlexUserID: 701, UserID: e.admin}}, []PlexLibraryLink{{SectionID: 2, LibraryID: e.tv}})
			r := e.previewRun(c, false)
			e.f.Set(func(f *plextest.Fake) { f.Shows["301"] = replacement })
			r = e.tickRun(c, r)
			if r.Status != "failed" || r.Reason != "source_identity_changed" {
				t.Fatalf("changed parent %+v", r)
			}
			if e.count("watch_state") != 0 {
				t.Fatal("changed source marked old episode")
			}
		})
	}
}
func TestPauseBetweenExecuteAndFinalizeCannotReenableSync(t *testing.T) {
	e, c := runEnv(t)
	r := e.previewRun(c, true)
	e.svc.beforeFinalize = func() {
		if _, err := e.svc.SetSync(t.Context(), c.ID, PlexSyncUpdate{Generation: c.Generation, Enabled: false}); err != nil {
			t.Fatal(err)
		}
	}
	r = e.tickRun(c, r)
	connections, _ := e.svc.Connections(t.Context())
	if r.Status != "cancelled" || connections[0].SyncEnabled {
		t.Fatalf("pause lost: %+v %+v", r, connections)
	}
}
func TestUndoAcrossPeopleIsAtomicAndFailedAttemptKeepsPreview(t *testing.T) {
	e, c := runEnv(t)
	r := e.tickRun(c, e.previewRun(c, false))
	p, err := e.svc.PreviewUndo(t.Context(), c.ID, r.ID, e.who)
	if err != nil {
		t.Fatal(err)
	}
	_, err = e.conn.ExecContext(t.Context(), fmt.Sprintf("CREATE TRIGGER fail_second_undo BEFORE DELETE ON watch_state WHEN OLD.user_id=%d BEGIN SELECT RAISE(ABORT,'synthetic undo failure'); END", e.viewer))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := e.svc.Undo(t.Context(), c.ID, r.ID, e.who, p.ID); err == nil {
		t.Fatal("injected failure not observed")
	}
	if e.watchRow(e.admin, e.ids["beacon"]) == nil || e.watchRow(e.viewer, e.ids["copper"]) == nil {
		t.Fatal("failed undo partially committed")
	}
	if _, err := e.conn.ExecContext(t.Context(), "DROP TRIGGER fail_second_undo"); err != nil {
		t.Fatal(err)
	}
	result, err := e.svc.Undo(t.Context(), c.ID, r.ID, e.who, p.ID)
	if err != nil || result.Undone != 2 {
		t.Fatalf("retry %+v %v", result, err)
	}
	if _, err := e.svc.Undo(t.Context(), c.ID, r.ID, e.who, p.ID); err == nil {
		t.Fatal("undo token replay accepted")
	}
	if e.watchRow(e.admin, e.ids["beacon"]) != nil || e.watchRow(e.viewer, e.ids["copper"]) != nil {
		t.Fatal("undo left marks")
	}
	next := e.tickRun(c, e.previewRun(c, false))
	if next.Counts.Marked != 0 || next.Counts.LocalKept != 2 {
		t.Fatalf("undo was reimported %+v", next)
	}
}
func TestExclusionsAgreeWithPreviewAndSurviveRematch(t *testing.T) {
	e, c := runEnv(t)
	ctx := t.Context()
	exclusions, err := e.svc.SaveExclusions(ctx, c.ID, PlexExclusions{Generation: c.Generation, Items: []PlexExclusion{{UserID: e.admin, ItemType: "movie", ItemID: e.ids["beacon"]}}})
	if err != nil {
		t.Fatal(err)
	}
	c.Generation = exclusions.Generation
	p, _ := e.svc.StartPreviewFor(ctx, c.ID, e.who)
	p = e.waitPreview(c, p.ID)
	for _, u := range p.Users {
		if u.UserID == e.admin && (u.Counts.WillMark != 0 || u.Counts.LocalKept != 1) {
			t.Fatalf("exclusion preview %+v", u)
		}
	}
	r, err := e.svc.StartRun(ctx, c.ID, e.who, PlexRunStart{PreviewID: p.ID})
	if err != nil {
		t.Fatal(err)
	}
	r = e.tickRun(c, r)
	if e.watchRow(e.admin, e.ids["beacon"]) != nil || r.Counts.LocalKept != 1 {
		t.Fatalf("exclusion ignored %+v", r)
	}
	if err := e.watch.Move(ctx, "movie", []watchstate.Move{{From: e.ids["beacon"], To: e.ids["harbor"]}}); err != nil {
		t.Fatal(err)
	}
	rows, _ := e.svc.Exclusions(ctx, c.ID)
	found := false
	for _, v := range rows.Items {
		if v.ItemID == e.ids["harbor"] {
			found = true
		}
	}
	if !found {
		t.Fatal("rematch lost guard-only exclusion")
	}
}
func TestDemotionThenRepromotionCannotReviveIntegration(t *testing.T) {
	e, c := runEnv(t)
	r := e.previewRun(c, true)
	if _, err := e.conn.ExecContext(t.Context(), "UPDATE users SET is_admin=0 WHERE id=?", e.admin); err != nil {
		t.Fatal(err)
	}
	if _, err := e.conn.ExecContext(t.Context(), "UPDATE users SET is_admin=1 WHERE id=?", e.admin); err != nil {
		t.Fatal(err)
	}
	r = e.tickRun(c, r)
	connections, _ := e.svc.Connections(t.Context())
	if connections[0].Status != "needs_authorization" || connections[0].SyncEnabled || r.Status != "cancelled" {
		t.Fatalf("revived %+v %+v", connections, r)
	}
	if e.count("watch_state") != 0 {
		t.Fatal("revoked grant committed")
	}
}
func TestExpiredLeaseAndCancellationFenceStaleWorker(t *testing.T) {
	e, c := runEnv(t)
	run := e.previewRun(c, false)
	ctx := t.Context()
	old, err := e.q.ClaimPlexRun(ctx, db.ClaimPlexRunParams{ID: run.ID, Now: stamp(e.svc.now()), LeaseUntil: stamp(e.svc.now().Add(-time.Second))})
	if err != nil {
		t.Fatal(err)
	}
	if err := e.svc.validRun(ctx, e.q, old); err == nil {
		t.Fatal("expired owner remained authorized")
	}
	fresh, err := e.q.ClaimPlexRun(ctx, db.ClaimPlexRunParams{ID: run.ID, Now: stamp(e.svc.now()), LeaseUntil: stamp(e.svc.now().Add(runLease))})
	if err != nil || fresh.Fence <= old.Fence {
		t.Fatalf("claim %+v %v", fresh, err)
	}
	if err := e.svc.validRun(ctx, e.q, old); err == nil {
		t.Fatal("stale fence remained authorized")
	}
	if _, err := e.svc.CancelRun(ctx, c.ID, run.ID); err != nil {
		t.Fatal(err)
	}
	if err := e.svc.validRun(ctx, e.q, fresh); err == nil {
		t.Fatal("cancelled worker remained authorized")
	}
}
func historyEvent(key string, account int64, item plex.Item) plex.HistoryEvent {
	return plex.HistoryEvent{HistoryKey: "/status/sessions/history/" + key, AccountID: account, ViewedAt: time.Now().Add(-time.Hour).Truncate(time.Second), Item: item}
}
func (e *env) seedHistory() {
	e.t.Helper()
	event := historyEvent("901", 1, plex.Item{RatingKey: "101", Type: "movie", LibrarySectionID: "1", Title: "Beacon", ExternalIDs: plex.ExternalIDs{TMDB: "810001", IMDb: "tt0810001"}})
	e.f.Set(func(f *plextest.Fake) { f.HistoryEvents["owner"] = map[string][]plex.HistoryEvent{"1": {event}} })
}
func TestHistorySuppressionCutoffAndRecordingPolicyWinDuringFetch(t *testing.T) {
	e, c := runEnv(t)
	e.seedHistory()
	ctx := t.Context()
	r := e.tickRun(c, e.previewRun(c, false))
	list, err := e.svc.History(ctx, e.admin, 50, 0)
	if err != nil || len(list.Items) != 1 || r.Counts.HistoryImported != 1 {
		t.Fatalf("history %+v %+v %v", list, r, err)
	}
	if err := e.svc.DeleteHistory(ctx, e.admin, list.Items[0].ID); err != nil {
		t.Fatal(err)
	}
	if _, err := e.conn.ExecContext(ctx, "DELETE FROM plex_history_checkpoints"); err != nil {
		t.Fatal(err)
	}
	e.tickRun(c, e.previewRun(c, false))
	list, _ = e.svc.History(ctx, e.admin, 50, 0)
	if len(list.Items) != 0 {
		t.Fatal("deleted event returned")
	}
	if err := e.svc.ClearHistory(ctx, e.admin); err != nil {
		t.Fatal(err)
	}
	if _, err := e.conn.ExecContext(ctx, "DELETE FROM plex_history_checkpoints; DELETE FROM plex_history_suppressions"); err != nil {
		t.Fatal(err)
	}
	e.tickRun(c, e.previewRun(c, false))
	list, _ = e.svc.History(ctx, e.admin, 50, 0)
	if len(list.Items) != 0 {
		t.Fatal("clear cutoff ignored")
	}
}
func TestHistoryInFlightPrivacyAndTargetDeletion(t *testing.T) {
	for _, privacy := range []bool{true, false} {
		t.Run(strconv.FormatBool(privacy), func(t *testing.T) {
			e, c := runEnv(t)
			e.seedHistory()
			var once sync.Once
			e.svc.beforeHistoryCommit = func() {
				once.Do(func() {
					var err error
					if privacy {
						err = e.q.SetUserRecordHistory(t.Context(), db.SetUserRecordHistoryParams{RecordHistory: 0, ID: e.admin})
					} else {
						_, err = e.conn.ExecContext(t.Context(), "DELETE FROM movies WHERE id=?", e.ids["beacon"])
					}
					if err != nil {
						t.Fatal(err)
					}
				})
			}
			e.tickRun(c, e.previewRun(c, false))
			list, err := e.svc.History(t.Context(), e.admin, 50, 0)
			if err != nil {
				t.Fatal(err)
			}
			if privacy && len(list.Items) != 0 {
				t.Fatal("privacy change lost to in-flight page")
			}
			if !privacy && (len(list.Items) != 1 || list.Items[0].ItemID != nil || list.Items[0].Title != "Beacon") {
				t.Fatalf("stale target retained %+v", list)
			}
		})
	}
}
func TestNoHistoryRetainsOnlyOperationalCountsAfterTerminalRun(t *testing.T) {
	e, c := runEnv(t)
	ctx := t.Context()
	for _, user := range []int64{e.admin, e.viewer} {
		if err := e.q.SetUserRecordHistory(ctx, db.SetUserRecordHistoryParams{RecordHistory: 0, ID: user}); err != nil {
			t.Fatal(err)
		}
	}
	r := e.tickRun(c, e.previewRun(c, false))
	if r.Status != "complete" || r.Counts.Marked != 2 {
		t.Fatalf("run %+v", r)
	}
	if e.count("plex_run_scope") != 0 || e.count("plex_run_history_coverage") != 0 {
		t.Fatal("private item details survived terminal run")
	}
	guards, _ := e.q.ListRunWatchGuards(ctx, &r.ID)
	if len(guards) != 2 {
		t.Fatal("privacy cleanup removed minimal undo provenance")
	}
}
func TestLargeRunCountersAdvanceOncePerCommittedCheckpoint(t *testing.T) {
	e, c := runEnv(t)
	r := e.previewRun(c, false)
	ctx := t.Context()
	const n = 3000
	if err := e.q.Transaction(ctx, func(q *db.Queries) error {
		for i := range n {
			v := scopeCandidate{UserID: e.admin, PlexUserID: 701, Kind: "owner", SectionID: "1", SectionUUID: "fictional-movies", RemoteKey: strconv.Itoa(10000 + i), Fingerprint: "synthetic", Found: found{category: "unmatched", example: PlexExample{ItemType: "movie"}}}
			if err := insertScope(ctx, q, r.ID, v); err != nil {
				return err
			}
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	scope, _ := e.q.ListPlexScope(ctx, r.ID)
	if err := e.q.Transaction(ctx, func(q *db.Queries) error {
		for _, v := range scope {
			if err := e.svc.recordOutcome(ctx, q, v, "unmatched"); err != nil {
				return err
			}
			if err := e.svc.recordOutcome(ctx, q, v, "unmatched"); err != nil {
				return err
			}
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	saved, _ := e.q.GetPlexRun(ctx, r.ID)
	if saved.Unmatched != int64(len(scope)) {
		t.Fatalf("replay counts %d want %d", saved.Unmatched, len(scope))
	}
	rows, err := e.conn.QueryContext(ctx, "EXPLAIN QUERY PLAN UPDATE plex_run_scope SET outcome='unmatched' WHERE run_id=? AND user_id=? AND section_id=? AND remote_key=? AND outcome=''", r.ID, e.admin, 1, "10000")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = rows.Close() }()
	var detail string
	for rows.Next() {
		var a, b, d int
		if err := rows.Scan(&a, &b, &d, &detail); err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(detail, "SEARCH plex_run_scope USING INDEX") {
			t.Fatalf("unindexed checkpoint %s", detail)
		}
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
}

// Ensure queued work can be consumed by a fresh Service after a process restart.
func TestRunRestartResumesTheSameDurableIdentity(t *testing.T) {
	e, c := runEnv(t)
	r := e.previewRun(c, false)
	e.svc = e.open()
	r = e.tickRun(c, r)
	if r.Status != "complete" || r.Counts.Marked != 2 {
		t.Fatalf("restart %+v", r)
	}
}

func TestBoundedHistoryBackfillContinuesAfterWeekAndHonorsEnableChoice(t *testing.T) {
	e := newEnv(t)
	e.svc.historyPages = 1
	e.f.SetItems("owner", "1", plextest.MovieJSON("101", "Beacon", "810001", "tt0810001", 1))
	events := make([]plex.HistoryEvent, 201)
	for i := range events {
		events[i] = historyEvent(strconv.Itoa(10000+i), 1, plex.Item{RatingKey: "101", Type: "movie", LibrarySectionID: "1", Title: "Beacon", ExternalIDs: plex.ExternalIDs{TMDB: "810001", IMDb: "tt0810001"}})
	}
	e.f.Set(func(f *plextest.Fake) { f.HistoryEvents["owner"] = map[string][]plex.HistoryEvent{"1": events} })
	c := e.connect()
	c = e.link(c, []PlexUserLink{{PlexUserID: 701, UserID: e.admin}}, []PlexLibraryLink{{SectionID: 1, LibraryID: e.movies}})
	r := e.tickRun(c, e.previewRun(c, true))
	if r.Status != "partial" || r.Counts.HistoryImported != 200 {
		t.Fatalf("first bounded run %+v", r)
	}
	connections, _ := e.svc.Connections(t.Context())
	if !connections[0].SyncEnabled {
		t.Fatal("history continuation discarded explicit enable choice")
	}
	cp, err := e.q.GetPlexHistoryCheckpoint(t.Context(), db.GetPlexHistoryCheckpointParams{ConnectionID: c.ID, UserID: e.admin, SectionID: 1})
	if err != nil || cp.Offset != 200 || cp.Complete != 0 || cp.LastFull != "" {
		t.Fatalf("checkpoint %+v %v", cp, err)
	}
	now := e.svc.now().Add(8 * 24 * time.Hour)
	e.svc.now = func() time.Time { return now }
	if _, err := e.conn.ExecContext(t.Context(), "UPDATE plex_connections SET next_sync_at='' WHERE id=?", c.ID); err != nil {
		t.Fatal(err)
	}
	if err := e.svc.tick(t.Context()); err != nil {
		t.Fatal(err)
	}
	cp, err = e.q.GetPlexHistoryCheckpoint(t.Context(), db.GetPlexHistoryCheckpointParams{ConnectionID: c.ID, UserID: e.admin, SectionID: 1})
	if err != nil || cp.Offset != 201 || cp.Complete != 1 || cp.LastFull != stamp(now) {
		t.Fatalf("continued checkpoint %+v %v", cp, err)
	}
	if e.count("plex_history_events") != 201 {
		t.Fatal("backfill repeated or lost events")
	}
	runs, err := e.svc.Runs(t.Context(), c.ID)
	if err != nil || len(runs) < 2 || runs[0].Counts.HistoryImported != 1 || runs[0].Status != "complete" || len(runs[0].HistoryCoverage) != 1 || !runs[0].HistoryCoverage[0].Complete {
		t.Fatalf("coverage %+v %v", runs, err)
	}
}
func TestHistoryInsertRejectsUnmappedScopeAndDemotionAtCommit(t *testing.T) {
	for _, removeLibrary := range []bool{false, true} {
		t.Run(strconv.FormatBool(removeLibrary), func(t *testing.T) {
			e, c := runEnv(t)
			e.seedHistory()
			var once sync.Once
			e.svc.beforeHistoryCommit = func() {
				once.Do(func() {
					var err error
					if removeLibrary {
						err = e.q.DeleteLibrary(t.Context(), e.movies)
					} else {
						_, err = e.conn.ExecContext(t.Context(), "UPDATE users SET is_admin=0 WHERE id=?", e.admin)
					}
					if err != nil {
						t.Fatal(err)
					}
				})
			}
			r := e.tickRun(c, e.previewRun(c, false))
			if r.Status != "cancelled" || e.count("plex_history_events") != 0 {
				t.Fatalf("invalidated page %+v", r)
			}
		})
	}
}

func TestDifferentEnablingAdminDeletionOrDemotionRevokesSchedule(t *testing.T) {
	for _, deleted := range []bool{false, true} {
		t.Run(strconv.FormatBool(deleted), func(t *testing.T) {
			e, c := runEnv(t)
			ctx := t.Context()
			operator, err := e.q.CreateUser(ctx, db.CreateUserParams{Name: "operator", PasswordHash: "x", IsAdmin: 1})
			if err != nil {
				t.Fatal(err)
			}
			who := Requester{UserID: operator.ID, Session: "operator-session"}
			p, err := e.svc.StartPreviewFor(ctx, c.ID, who)
			if err != nil {
				t.Fatal(err)
			}
			p = e.waitPreview(c, p.ID)
			r, err := e.svc.StartRun(ctx, c.ID, who, PlexRunStart{PreviewID: p.ID, EnableSync: true})
			if err != nil {
				t.Fatal(err)
			}
			r = e.tickRun(c, r)
			if r.Status != "complete" {
				t.Fatalf("operator run %+v", r)
			}
			saved, err := e.q.GetPlexConnection(ctx, c.ID)
			if err != nil || saved.EnabledBy == nil || *saved.EnabledBy != operator.ID || saved.AuthorizedBy == nil || *saved.AuthorizedBy != e.admin || saved.SyncEnabled != 1 {
				t.Fatalf("actor binding %+v %v", saved, err)
			}
			if deleted {
				err = e.q.DeleteUser(ctx, operator.ID)
			} else {
				_, err = e.conn.ExecContext(ctx, "UPDATE users SET is_admin=0 WHERE id=?", operator.ID)
				if err == nil {
					_, err = e.conn.ExecContext(ctx, "UPDATE users SET is_admin=1 WHERE id=?", operator.ID)
				}
			}
			if err != nil {
				t.Fatal(err)
			}
			connections, err := e.svc.Connections(ctx)
			if err != nil || connections[0].Status != "needs_authorization" || connections[0].SyncEnabled {
				t.Fatalf("revocation %+v %v", connections, err)
			}
		})
	}
}
func TestInitiatingAdminDemotionCannotBeUndoneByQuickRepromotion(t *testing.T) {
	e, c := runEnv(t)
	ctx := t.Context()
	operator, err := e.q.CreateUser(ctx, db.CreateUserParams{Name: "operator", PasswordHash: "x", IsAdmin: 1})
	if err != nil {
		t.Fatal(err)
	}
	who := Requester{UserID: operator.ID, Session: "operator-session"}
	p, err := e.svc.StartPreviewFor(ctx, c.ID, who)
	if err != nil {
		t.Fatal(err)
	}
	p = e.waitPreview(c, p.ID)
	r, err := e.svc.StartRun(ctx, c.ID, who, PlexRunStart{PreviewID: p.ID, EnableSync: true})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := e.conn.ExecContext(ctx, "UPDATE users SET is_admin=0 WHERE id=?", operator.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := e.conn.ExecContext(ctx, "UPDATE users SET is_admin=1 WHERE id=?", operator.ID); err != nil {
		t.Fatal(err)
	}
	r = e.tickRun(c, r)
	if r.Status != "cancelled" || e.count("watch_state") != 0 {
		t.Fatalf("revoked actor resumed %+v", r)
	}
}

func TestOldRetryIsNotStarvedByRecentRunPaginationAndCanBeCancelled(t *testing.T) {
	for _, cancelled := range []bool{false, true} {
		t.Run(strconv.FormatBool(cancelled), func(t *testing.T) {
			e, c := runEnv(t)
			ctx := t.Context()
			r := e.previewRun(c, false)
			if _, err := e.conn.ExecContext(ctx, "UPDATE plex_sync_runs SET status='failed' WHERE id=?", r.ID); err != nil {
				t.Fatal(err)
			}
			saved, err := e.q.GetPlexConnection(ctx, c.ID)
			if err != nil {
				t.Fatal(err)
			}
			for range 31 {
				newer, err := e.q.InsertPlexRun(ctx, db.InsertPlexRunParams{ConnectionID: &c.ID, Generation: c.Generation, MachineIdentifier: saved.MachineIdentifier, SourceEpoch: saved.SourceEpoch, ActorID: &e.admin, StartedAt: stamp(e.svc.now())})
				if err != nil {
					t.Fatal(err)
				}
				if _, err := e.conn.ExecContext(ctx, "UPDATE plex_sync_runs SET status='complete',finished_at=? WHERE id=?", stamp(e.svc.now()), newer.ID); err != nil {
					t.Fatal(err)
				}
			}
			if _, err := e.svc.RetryRun(ctx, c.ID, r.ID); err != nil {
				t.Fatal(err)
			}
			if _, err := e.q.SetPlexSync(ctx, db.SetPlexSyncParams{SyncEnabled: 1, ID: c.ID, Generation: c.Generation}); err != nil {
				t.Fatal(err)
			}
			if cancelled {
				if _, err := e.svc.SetSync(ctx, c.ID, PlexSyncUpdate{Generation: c.Generation, Enabled: false}); err != nil {
					t.Fatal(err)
				}
				got, err := e.svc.GetRun(ctx, c.ID, r.ID)
				if err != nil || got.Status != "cancelled" {
					t.Fatalf("old active run escaped pause %+v %v", got, err)
				}
			} else {
				got := e.tickRun(c, r)
				if got.Status != "complete" || got.Counts.Marked != 2 {
					t.Fatalf("old retry starved %+v", got)
				}
			}
			first, err := e.svc.RunPage(ctx, c.ID, 30, 0)
			if err != nil || first.Next == nil || len(first.Items) != 30 {
				t.Fatalf("first page %+v %v", first, err)
			}
			second, err := e.svc.RunPage(ctx, c.ID, 30, *first.Next)
			if err != nil || len(second.Items) != 2 || second.Items[1].ID != r.ID {
				t.Fatalf("old import no longer discoverable %+v %v", second, err)
			}
		})
	}
}

func TestChangedMappingsStopExistingScheduleUntilNewPreview(t *testing.T) {
	e, c := runEnv(t)
	r := e.tickRun(c, e.previewRun(c, true))
	if r.Status != "complete" {
		t.Fatal(r)
	}
	c2, err := e.svc.SaveLinks(t.Context(), c.ID, PlexLinks{Generation: c.Generation, Users: []PlexUserLink{{PlexUserID: 701, UserID: e.admin}}, Libraries: []PlexLibraryLink{{SectionID: 1, LibraryID: e.movies}}})
	if err != nil {
		t.Fatal(err)
	}
	if c2.SyncEnabled || c2.Generation <= c.Generation {
		t.Fatalf("new scope inherited old schedule %+v", c2)
	}
	if _, err := e.svc.SetSync(t.Context(), c.ID, PlexSyncUpdate{Generation: c2.Generation, Enabled: true}); err == nil {
		t.Fatal("new mapping skipped preview/import gate")
	}
}
func TestQueuedImportAndPreviewDoNotPerformConcurrentSourceReads(t *testing.T) {
	e, c := runEnv(t)
	e.previewRun(c, false)
	if _, err := e.svc.StartPreviewFor(t.Context(), c.ID, e.who); err == nil {
		t.Fatal("preview started beside queued import")
	}
}

func TestUnresolvedParentCanBecomeMatchedOnLaterReconciliation(t *testing.T) {
	e := newEnv(t)
	ctx := t.Context()
	e.f.SetItems("owner", "2", plextest.EpisodeJSON("201", "Pilot", "301", 1, 1, 1))
	c := e.connect()
	c = e.link(c, []PlexUserLink{{PlexUserID: 701, UserID: e.admin}}, []PlexLibraryLink{{SectionID: 2, LibraryID: e.tv}})
	first := e.tickRun(c, e.previewRun(c, true))
	if first.Status != "complete" || first.Counts.Unmatched != 1 {
		t.Fatalf("first unresolved run %+v", first)
	}
	if e.count("plex_source_items") != 0 {
		t.Fatal("unproved parent was stored as a canonical source identity")
	}
	e.f.Set(func(f *plextest.Fake) {
		f.Shows["301"] = `{"ratingKey":"301","type":"show","title":"Signal Keepers","episodeOrdering":"tmdbAiring","Guid":[{"id":"tmdb://820001"}]}`
	})
	if _, err := e.q.SetPlexSync(ctx, db.SetPlexSyncParams{ID: c.ID, Generation: c.Generation, SyncEnabled: 1}); err != nil {
		t.Fatal(err)
	}
	if err := e.svc.tick(ctx); err != nil {
		t.Fatal(err)
	}
	runs, err := e.svc.Runs(ctx, c.ID)
	if err != nil || runs[0].Status != "complete" || runs[0].Counts.Marked != 1 {
		t.Fatalf("resolved source treated as collision %+v %v", runs, err)
	}
}

func TestCompletedWatchScopesDoNotAccumulateAcrossReconciliations(t *testing.T) {
	e, c := runEnv(t)
	for range 5 {
		r := e.tickRun(c, e.previewRun(c, false))
		if r.Status != "complete" {
			t.Fatal(r)
		}
		saved, err := e.q.GetPlexRun(t.Context(), r.ID)
		if err != nil || saved.WatchComplete != 1 {
			t.Fatalf("checkpoint %+v %v", saved, err)
		}
		if e.count("plex_run_scope") != 0 {
			t.Fatal("completed full snapshot retained duplicate per-item detail")
		}
	}
	if e.count("watch_state") != 2 || e.count("plex_source_items") != 2 {
		t.Fatal("bounded detail cleanup changed durable marks or canonical source facts")
	}
}
func TestHistoryOnlyRetryCannotExpandAnAlreadyCompletedWatchScope(t *testing.T) {
	e := newEnv(t)
	e.svc.historyPages = 1
	ctx := t.Context()
	e.f.SetItems("owner", "1", plextest.MovieJSON("101", "Beacon", "810001", "tt0810001", 1))
	events := make([]plex.HistoryEvent, 201)
	for i := range events {
		events[i] = historyEvent(strconv.Itoa(20000+i), 1, plex.Item{RatingKey: "101", Type: "movie", LibrarySectionID: "1", Title: "Beacon", ExternalIDs: plex.ExternalIDs{TMDB: "810001", IMDb: "tt0810001"}})
	}
	e.f.Set(func(f *plextest.Fake) { f.HistoryEvents["owner"] = map[string][]plex.HistoryEvent{"1": events} })
	c := e.connect()
	c = e.link(c, []PlexUserLink{{PlexUserID: 701, UserID: e.admin}}, []PlexLibraryLink{{SectionID: 1, LibraryID: e.movies}})
	r := e.tickRun(c, e.previewRun(c, false))
	if r.Status != "partial" || e.count("plex_run_scope") != 0 {
		t.Fatalf("partial history %+v", r)
	}
	e.f.SetItems("owner", "1", plextest.MovieJSON("101", "Beacon", "810001", "tt0810001", 1), plextest.MovieJSON("108", "Copper", "810003", "", 1))
	if _, err := e.svc.RetryRun(ctx, c.ID, r.ID); err != nil {
		t.Fatal(err)
	}
	r = e.tickRun(c, r)
	if r.Status != "complete" || r.Counts.Marked != 1 || r.Counts.HistoryImported != 201 || e.watchRow(e.admin, e.ids["copper"]) != nil {
		t.Fatalf("history retry changed initial watch scope %+v", r)
	}
}
