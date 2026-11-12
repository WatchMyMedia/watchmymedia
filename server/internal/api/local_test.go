package api

import (
	"encoding/json"
	"net/http"
	"testing"
)

// The menu bar app's status route needs no login from loopback and lists
// the running sessions.
func TestLocalStatus(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, func(d *Deps) { d.Version = "t1" })
	res, err := http.Get(srv.URL + "/api/playback/local")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode != http.StatusOK {
		t.Fatalf("status %d", res.StatusCode)
	}
	var out LocalStatus
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		t.Fatal(err)
	}
	if out.Version != "t1" || out.Sessions == nil || len(out.Sessions) != 0 {
		t.Errorf("status = %+v", out)
	}
}
