package api

import (
	"bufio"
	"context"
	"net/http"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/events"
)

func TestEventStreamStopsAfterLogout(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, srv.URL+"/api/events", nil)
	res, err := srv.client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		t.Fatalf("events: %d", res.StatusCode)
	}
	lines := bufio.NewScanner(res.Body)
	readUntil := func(event string) string {
		t.Helper()
		seen := false
		for lines.Scan() {
			line := lines.Text()
			if line == "event: "+event {
				seen = true
			}
			if seen && strings.HasPrefix(line, "data: ") {
				return line
			}
		}
		t.Fatalf("stream ended before event %s: %v", event, lines.Err())
		return ""
	}
	readUntil("ping")
	if code := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/auth/logout", "", nil).StatusCode; code != http.StatusNoContent {
		t.Fatalf("logout: %d", code)
	}
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/auth/me", "", nil).StatusCode; code != http.StatusUnauthorized {
		t.Fatalf("new request after logout: %d", code)
	}
	srv.bus.Publish(events.Event{Type: "watch.changed", Data: WatchChanged{ItemType: "movie", ItemIDs: []int64{991}}, To: &events.Audience{UserID: 1, Private: true}})
	for lines.Scan() {
		if strings.Contains(lines.Text(), "991") {
			t.Fatal("private event sent after logout")
		}
	}
	if err := lines.Err(); err != nil {
		t.Fatalf("stream failed to close promptly: %v", err)
	}
}
