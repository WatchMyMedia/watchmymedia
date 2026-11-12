package plexsync

import (
	"testing"

	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/plex"
	"watchmymedia/server/internal/plex/plextest"
)

func TestHistoryDeletionEventsDescribeOnlyCommittedOwnerChanges(t *testing.T) {
	e, c := runEnv(t)
	e.seedHistory()
	viewing := historyEvent("902", 706, plex.Item{RatingKey: "108", Type: "movie", LibrarySectionID: "1", Title: "Copper", ExternalIDs: plex.ExternalIDs{TMDB: "810003"}})
	e.f.Set(func(f *plextest.Fake) {
		f.HistoryEvents["owner"]["1"] = append(f.HistoryEvents["owner"]["1"], viewing)
		f.HistoryEvents["synthetic-server-706"] = map[string][]plex.HistoryEvent{"1": {viewing}}
	})
	e.tickRun(c, e.previewRun(c, false))
	e.svc.bus = events.NewBus()
	changes, stop := e.svc.bus.Subscribe()
	defer stop()
	owner, err := e.svc.History(t.Context(), e.admin, 50, 0)
	if err != nil || len(owner.Items) != 1 {
		t.Fatalf("owner history %+v %v", owner, err)
	}
	viewer, err := e.svc.History(t.Context(), e.viewer, 50, 0)
	if err != nil || len(viewer.Items) != 1 {
		t.Fatalf("viewer history %+v %v", viewer, err)
	}
	read := func(user int64) PlexHistoryChanged {
		t.Helper()
		select {
		case event := <-changes:
			if event.Type != "plex.history.changed" || event.To == nil || event.To.UserID != user || !event.To.Private || event.For(999, true, true) {
				t.Fatalf("history change exposed another person's data: %+v", event)
			}
			change, ok := event.Data.(PlexHistoryChanged)
			if !ok {
				t.Fatalf("unregistered history payload: %T", event.Data)
			}
			return change
		default:
			t.Fatal("successful change published no event")
			return PlexHistoryChanged{}
		}
	}
	if err := e.svc.DeleteHistory(t.Context(), e.admin, owner.Items[0].ID); err != nil {
		t.Fatal(err)
	}
	deleted := read(e.admin)
	if deleted.Cleared || len(deleted.DeletedIDs) != 1 || deleted.DeletedIDs[0] != owner.Items[0].ID {
		t.Fatalf("deleted identity missing: %+v", deleted)
	}
	if remaining, err := e.svc.History(t.Context(), e.admin, 50, 0); err != nil || len(remaining.Items) != 0 {
		t.Fatalf("event preceded its committed deletion: %+v %v", remaining, err)
	}
	if err := e.svc.DeleteHistory(t.Context(), e.admin, viewer.Items[0].ID); reasonCode(err) != "history_not_found" {
		t.Fatalf("another person's event was deleted: %v", err)
	}
	select {
	case event := <-changes:
		t.Fatalf("failed change published an event: %+v", event)
	default:
	}
	if err := e.svc.ClearHistory(t.Context(), e.viewer); err != nil {
		t.Fatal(err)
	}
	if cleared := read(e.viewer); !cleared.Cleared || len(cleared.DeletedIDs) != 0 {
		t.Fatalf("clear event missing: %+v", cleared)
	}
}
