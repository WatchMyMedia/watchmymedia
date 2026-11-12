package api

import (
	"bufio"
	"context"
	"net/http"
	"net/http/cookiejar"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/plexsync"
)

// Exercise Huma's typed SSE serializer, not just the bus audience predicate:
// publishing an unregistered Go payload type otherwise panics the stream.
func TestPlexEventsSerializeAndKeepImportedHistoryPrivate(t *testing.T) {
	t.Parallel()
	srv, _ := newPlexTestServer(t, nil)
	var viewer struct {
		ID int64 `json:"id"`
	}
	if code := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/users", `{"name":"event-viewer","password":"viewerpass1"}`, &viewer).StatusCode; code != http.StatusCreated {
		t.Fatal(code)
	}
	jar, _ := cookiejar.New(nil)
	client := &http.Client{Jar: jar}
	if code := doJSON(t, client, http.MethodPost, srv.URL+"/api/auth/login", `{"name":"event-viewer","password":"viewerpass1"}`, nil).StatusCode; code != http.StatusOK {
		t.Fatal(code)
	}
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	open := func(client *http.Client) *bufio.Scanner {
		t.Helper()
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, srv.URL+"/api/events", nil)
		res, err := client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = res.Body.Close() })
		if res.StatusCode != http.StatusOK {
			t.Fatal(res.StatusCode)
		}
		lines := bufio.NewScanner(res.Body)
		for lines.Scan() {
			if strings.HasPrefix(lines.Text(), "data: ") {
				return lines
			}
		}
		t.Fatalf("no connect ping: %v", lines.Err())
		return nil
	}
	admin, member := open(srv.client), open(client)
	srv.bus.Publish(events.Event{Type: "plex.changed", Data: plexsync.PlexChanged{ConnectionID: 17}, To: &events.Audience{UserID: 1, Private: true}})
	srv.bus.Publish(events.Event{Type: "plex.history.changed", Data: plexsync.PlexHistoryChanged{}, To: &events.Audience{UserID: viewer.ID, Private: true}})
	srv.bus.Publish(events.Event{Type: "plex.history.changed", Data: plexsync.PlexHistoryChanged{DeletedIDs: []int64{17}}, To: &events.Audience{UserID: viewer.ID, Private: true}})
	srv.bus.Publish(events.Event{Type: "plex.history.changed", Data: plexsync.PlexHistoryChanged{Cleared: true}, To: &events.Audience{UserID: viewer.ID, Private: true}})
	for _, user := range []int64{1, viewer.ID} {
		srv.bus.Publish(events.Event{Type: "watch.changed", Data: WatchChanged{ItemType: "movie", ItemIDs: []int64{991}}, To: &events.Audience{UserID: user, Private: true}})
	}
	read := func(lines *bufio.Scanner) string {
		t.Helper()
		var seen strings.Builder
		for lines.Scan() {
			line := lines.Text()
			seen.WriteString(line)
			seen.WriteByte('\n')
			if strings.Contains(line, "991") {
				return seen.String()
			}
		}
		t.Fatalf("stream ended before marker: %v; %s", lines.Err(), seen.String())
		return ""
	}
	a, m := read(admin), read(member)
	if !strings.Contains(a, "event: plex.changed") || strings.Contains(a, "event: plex.history.changed") || !strings.Contains(a, `"connectionId":17`) {
		t.Fatalf("admin stream %s", a)
	}
	if !strings.Contains(m, "event: plex.history.changed") || strings.Contains(m, "event: plex.changed") || !strings.Contains(m, `"deletedIds":[17]`) || !strings.Contains(m, `"cleared":true`) {
		t.Fatalf("member stream %s", m)
	}
}
