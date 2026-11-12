package plexsync

import "testing"

func TestConnectionApprovalSurvivesRunPaginationAndResetsWithMappings(t *testing.T) {
	e, c := runEnv(t)
	if c.HasApprovedImport {
		t.Fatal("connecting or mapping alone approved recurring imports")
	}
	r := e.tickRun(c, e.previewRun(c, false))
	if r.Status != "complete" {
		t.Fatalf("approved import did not complete: %+v", r)
	}
	// An outage can leave more than one page of failed scheduled checks after
	// the approved import. None of them revokes its current mapping approval.
	for range 31 {
		_, err := e.conn.ExecContext(t.Context(), `INSERT INTO plex_sync_runs(connection_id,generation,machine_identifier,source_epoch,actor_id,status,started_at,finished_at)
			SELECT connection_id,generation,machine_identifier,source_epoch,actor_id,'failed',started_at,finished_at FROM plex_sync_runs WHERE id=?`, r.ID)
		if err != nil {
			t.Fatal(err)
		}
	}
	page, err := e.svc.RunPage(t.Context(), c.ID, 30, 0)
	if err != nil || len(page.Items) != 30 || page.Next == nil {
		t.Fatalf("run page %+v %v", page, err)
	}
	for _, run := range page.Items {
		if run.Status != "failed" {
			t.Fatalf("old approval still fits on first page: %+v", run)
		}
	}
	connections, err := e.svc.Connections(t.Context())
	if err != nil || len(connections) != 1 || !connections[0].HasApprovedImport {
		t.Fatalf("older approval disappeared: %+v %v", connections, err)
	}
	resumed, err := e.svc.SetSyncFor(t.Context(), c.ID, e.who, PlexSyncUpdate{Generation: c.Generation, Enabled: true})
	if err != nil || !resumed.SyncEnabled || !resumed.HasApprovedImport {
		t.Fatalf("approved schedule could not resume after an outage: %+v %v", resumed, err)
	}
	c = e.link(c, []PlexUserLink{{PlexUserID: 701, UserID: e.admin}}, []PlexLibraryLink{{SectionID: 1, LibraryID: e.movies}})
	if c.HasApprovedImport {
		t.Fatal("old mapping approval survived a generation change")
	}
	_, err = e.svc.SetSyncFor(t.Context(), c.ID, e.who, PlexSyncUpdate{Generation: c.Generation, Enabled: true})
	if reasonCode(err) != "import_first" {
		t.Fatalf("changed mappings resumed without approval: %v", err)
	}
}
