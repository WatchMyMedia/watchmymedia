package arr

import (
	"context"
	"net/http"
	"testing"

	"watchmymedia/server/internal/integrations/arrtest"
)

func TestQueueReadsEveryPageUpToTwenty(t *testing.T) {
	app := arrtest.New(t, "radarr")
	add := func(n int) {
		app.Lock()
		defer app.Unlock()
		for range n {
			id := len(app.Queue) + 1
			app.Queue = append(app.Queue, map[string]any{"id": id, "movieId": id, "title": "Download", "size": 10, "sizeleft": 5})
		}
	}
	c, err := New(Radarr, app.URL, app.Key, nil)
	if err != nil {
		t.Fatal(err)
	}
	add(600)
	items, err := c.Queue(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 600 || items[599].ID != 600 {
		t.Errorf("read %d items", len(items))
	}
	if n := len(app.CallsTo(http.MethodGet, "/queue")); n != 3 {
		t.Errorf("asked for %d pages, want 3 of 250", n)
	}

	// A queue past twenty pages is read up to them, not without end.
	add(21*250 - 600)
	items, err = c.Queue(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 20*250 {
		t.Errorf("read %d items, want twenty pages' worth", len(items))
	}
	if n := len(app.CallsTo(http.MethodGet, "/queue")); n != 3+20 {
		t.Errorf("asked for %d pages in all, want 23", n)
	}
}
