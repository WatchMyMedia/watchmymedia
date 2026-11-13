package api

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/netip"
	"os"
	"path/filepath"
	"testing"
	"time"

	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/netguard"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/upload"
)

// Rolling enforcement back to observation must wake an already paced HTTP
// response through the real admin settings route without losing media bytes.
func TestUploadRollbackDrainsActiveMediaResponse(t *testing.T) {
	l := upload.New(8_000, true)
	admin, err := netguard.Parse([]string{"local"})
	if err != nil {
		t.Fatal(err)
	}
	srv := newTestServerWith(t, func(d *Deps) {
		d.Net = netguard.New([]netip.Prefix{netip.MustParsePrefix("127.0.0.0/8")}, admin)
		d.Upload = l
		d.Activity = activity.New(d.Conn, d.Bus, nil, activity.Options{OnEnd: l.Release})
	})
	data := bytes.Repeat([]byte("fictional transport sample"), 4096)
	path := filepath.Join(t.TempDir(), "Rollback.mp4")
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	probeJSON := `{"container":"mov","durationMs":600000,"video":[{"index":0,"codec":"h264","width":640,"height":360}],"audio":[{"index":1,"codec":"aac","channels":2}]}`
	f, err := srv.q.UpsertMediaFile(t.Context(), db.UpsertMediaFileParams{LibraryID: srv.fileLibrary(t, path), Path: path,
		Size: int64(len(data)), Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()), Parsed: "{}",
		Probe: &probeJSON, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	var decision playback.Decision
	if response := doJSON(t, srv.client, "POST", fmt.Sprintf("%s/api/files/%d/playback", srv.URL, f.ID),
		`{"profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"]},"quality":"original"}`, &decision); response.StatusCode != 200 {
		t.Fatalf("local start: %d %s", response.StatusCode, response.Body)
	}
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, "GET", srv.URL+decision.URL, nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("X-Forwarded-For", "198.51.100.7")
	response, err := srv.client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != 200 || l.Snapshot().ActiveViews != 1 {
		t.Fatalf("media did not enter pacing: %d %+v", response.StatusCode, l.Snapshot())
	}
	first := make([]byte, upload.BurstBytes)
	if _, err := io.ReadFull(response.Body, first); err != nil {
		t.Fatal(err)
	}
	if updated := doJSON(t, srv.client, "PUT", srv.URL+"/api/settings", `{"upload":{"enforce":false}}`, nil); updated.StatusCode != 200 {
		t.Fatalf("rollback setting: %d %s", updated.StatusCode, updated.Body)
	}
	rest, err := io.ReadAll(response.Body)
	if err != nil || !bytes.Equal(append(first, rest...), data) {
		t.Fatalf("active response did not drain intact after rollback: %v (%d bytes)", err, len(first)+len(rest))
	}
	// The response stops counting as a view when its handler returns, which
	// can be a moment after the client has read the last byte (a CI runner
	// saw ActiveViews still 1). One that never ends still fails.
	for deadline := time.Now().Add(5 * time.Second); l.Snapshot().ActiveViews != 0 && time.Now().Before(deadline); {
		time.Sleep(5 * time.Millisecond)
	}
	if snapshot := l.Snapshot(); snapshot.Enforcing || snapshot.ActiveViews != 0 || snapshot.PacedBytes == 0 {
		t.Fatalf("rollback left a paced response: %+v", snapshot)
	}
}

func TestRemoteFixedStartsRespectUploadReservation(t *testing.T) {
	l := upload.New(2_000_000, true)
	var tracker *activity.Tracker
	admin, err := netguard.Parse([]string{"local"})
	if err != nil {
		t.Fatal(err)
	}
	srv := newTestServerWith(t, func(d *Deps) {
		d.Net = netguard.New([]netip.Prefix{netip.MustParsePrefix("127.0.0.0/8")}, admin)
		d.Upload = l
		tracker = activity.New(d.Conn, d.Bus, nil, activity.Options{OnEnd: l.Release})
		d.Activity = tracker
	})
	path := filepath.Join(t.TempDir(), "Fictional Clip (2020).mp4")
	if err := os.WriteFile(path, []byte("fictional media bytes"), 0o600); err != nil {
		t.Fatal(err)
	}
	probeJSON := `{"container":"mov","durationMs":5000,"bitRate":1000000,"video":[{"index":0,"codec":"h264","width":640,"height":360}],"audio":[{"index":1,"codec":"aac","channels":2,"default":true}]}`
	f, err := srv.q.UpsertMediaFile(t.Context(), db.UpsertMediaFileParams{LibraryID: srv.fileLibrary(t, path), Path: path, Size: 20, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()), Parsed: "{}", Probe: &probeJSON, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	url := fmt.Sprintf("%s/api/files/%d/playback", srv.URL, f.ID)
	body := `{"profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"]},"quality":"original"}`
	first := doJSON(t, srv.client, "POST", url, body, nil, "X-Forwarded-For", "198.51.100.7")
	if first.StatusCode != 200 {
		t.Fatalf("first start: %d %s", first.StatusCode, first.Body)
	}
	second := doJSON(t, srv.client, "POST", url, body, nil, "X-Forwarded-For", "198.51.100.8")
	if second.StatusCode != 429 {
		t.Fatalf("limit-plus-one: %d %s", second.StatusCode, second.Body)
	}
	if got := l.Snapshot().ReservedBps; got < 1_000_000 || got > 2_000_000 {
		t.Fatalf("reservation after refusal = %d", got)
	}
	var decision playback.Decision
	if err := json.Unmarshal(first.Body, &decision); err != nil {
		t.Fatal(err)
	}
	stream, err := http.NewRequestWithContext(t.Context(), http.MethodGet, srv.URL+decision.URL, nil)
	if err != nil {
		t.Fatal(err)
	}
	stream.Header.Set("X-Forwarded-For", "198.51.100.7")
	response, err := srv.client.Do(stream)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = io.Copy(io.Discard, response.Body)
	_ = response.Body.Close()
	plays, _ := tracker.Live(activity.Viewer{Admin: true})
	if len(plays) != 1 || !plays[0].ServerPaced || plays[0].UploadGeneration == 0 {
		t.Fatalf("pacing provenance = %+v", plays)
	}
	local := doJSON(t, srv.client, "POST", url, body, nil)
	if local.StatusCode != 200 {
		t.Fatalf("local start: %d %s", local.StatusCode, local.Body)
	}
	if got := l.Snapshot().ReservedBps; got < 1_000_000 || got > 2_000_000 {
		t.Fatalf("local start reserved remote capacity: %d", got)
	}
	stopBody := fmt.Sprintf(`{"positionMs":1000,"playId":%q,"state":"stopped"}`, decision.PlayID)
	stopped := doJSON(t, srv.client, http.MethodPut, fmt.Sprintf("%s/api/files/%d/progress", srv.URL, f.ID), stopBody, nil)
	if stopped.StatusCode != 200 || l.Snapshot().ReservedBps != 0 {
		t.Fatalf("stop leaked reservation: %d, %+v", stopped.StatusCode, l.Snapshot())
	}
	retry := doJSON(t, srv.client, http.MethodPost, url, body, nil, "X-Forwarded-For", "198.51.100.8")
	if retry.StatusCode != 200 {
		t.Fatalf("retry after release = %d %s", retry.StatusCode, retry.Body)
	}
}

func TestAdminUploadSettingPersistsAndUpdatesLimiter(t *testing.T) {
	l := upload.New(40_000_000, false)
	var guard *netguard.Guard
	srv := newTestServerWith(t, func(d *Deps) { d.Upload = l; remoteAdminDeps(d); guard = d.Net })
	res := doJSON(t, srv.client, "PUT", srv.URL+"/api/settings", `{"upload":{"bps":12000000,"enforce":true}}`, nil)
	if res.StatusCode != 200 {
		t.Fatalf("update = %d %s", res.StatusCode, res.Body)
	}
	if got := l.Snapshot(); got.ConfiguredBps != 12_000_000 || !got.Enforcing {
		t.Fatalf("limiter = %+v", got)
	}
	staleRevision := l.Snapshot().PolicyRevision
	p, err := srv.q.GetSetting(t.Context(), "network.upload")
	if err != nil || p != `{"bps":12000000,"enforce":true}` {
		t.Fatalf("stored = %q, %v", p, err)
	}
	res = doJSON(t, srv.client, "PUT", srv.URL+"/api/settings", `{"upload":{"bps":8000000}}`, nil)
	if res.StatusCode != 200 {
		t.Fatalf("partial update = %d %s", res.StatusCode, res.Body)
	}
	if got := l.Snapshot(); got.ConfiguredBps != 8_000_000 || !got.Enforcing {
		t.Fatalf("partial update = %+v", got)
	}
	res = doJSON(t, srv.client, "PUT", srv.URL+"/api/settings", fmt.Sprintf(`{"upload":{"bps":10000000,"expectedPolicyRevision":%d}}`, staleRevision), nil)
	if res.StatusCode != 409 || l.Snapshot().ConfiguredBps != 8_000_000 {
		t.Fatalf("stale edit = %d %s", res.StatusCode, res.Body)
	}
	var shown Settings
	res = doJSON(t, srv.client, "GET", srv.URL+"/api/settings", "", &shown)
	if res.StatusCode != 200 || shown.Upload == nil || shown.Upload.ConfiguredBps != 8_000_000 || !shown.Upload.Enforcing {
		t.Fatalf("settings read = %d %+v", res.StatusCode, shown.Upload)
	}
	var activity LiveActivity
	res = doJSON(t, srv.client, "GET", srv.URL+"/api/activity", "", &activity)
	if res.StatusCode != 200 || activity.Upload == nil || activity.Upload.ConfiguredBps != 8_000_000 {
		t.Fatalf("admin activity = %d %+v", res.StatusCode, activity.Upload)
	}
	doJSON(t, srv.client, "POST", srv.URL+"/api/users", `{"name":"viewer","password":"viewerpass1"}`, nil)
	jar, _ := cookiejar.New(nil)
	viewer := &http.Client{Jar: jar}
	res = doJSON(t, viewer, "POST", srv.URL+"/api/auth/login", `{"name":"viewer","password":"viewerpass1"}`, nil)
	if res.StatusCode != 200 {
		t.Fatalf("viewer login = %d", res.StatusCode)
	}
	var viewerActivity LiveActivity
	res = doJSON(t, viewer, "GET", srv.URL+"/api/activity", "", &viewerActivity)
	if res.StatusCode != 200 || viewerActivity.Upload != nil {
		t.Fatalf("viewer upload visibility = %d %+v", res.StatusCode, viewerActivity.Upload)
	}
	res = doJSON(t, srv.client, "PUT", srv.URL+"/api/settings", `{"upload":{"bps":-1}}`, nil)
	if res.StatusCode != 422 || l.Snapshot().ConfiguredBps != 8_000_000 {
		t.Fatalf("invalid update = %d %s", res.StatusCode, res.Body)
	}
	guard.SetRemoteAdmin(true)
	res = doJSON(t, srv.client, "PUT", srv.URL+"/api/settings", `{"upload":{"bps":1000000}}`, nil, remoteHTTPS...)
	if res.StatusCode != 403 || l.Snapshot().ConfiguredBps != 8_000_000 {
		t.Fatalf("unconfirmed remote edit = %d %s", res.StatusCode, res.Body)
	}
	res = doJSON(t, srv.client, "POST", srv.URL+"/api/auth/confirm", `{"password":"password1"}`, nil, remoteHTTPS...)
	if res.StatusCode != 204 {
		t.Fatalf("remote confirmation = %d %s", res.StatusCode, res.Body)
	}
	res = doJSON(t, srv.client, "PUT", srv.URL+"/api/settings", `{"upload":{"bps":1000000}}`, nil, remoteHTTPS...)
	if res.StatusCode != 200 || l.Snapshot().ConfiguredBps != 1_000_000 {
		t.Fatalf("confirmed remote edit = %d %s", res.StatusCode, res.Body)
	}
}
