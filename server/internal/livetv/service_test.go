package livetv

import (
	"context"
	"errors"
	"net/http/httptest"
	"testing"
	"time"

	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/livetv/hdhomerun"
	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
	"watchmymedia/server/internal/settings"
)

func TestLineupReconciliation(t *testing.T) {
	t.Parallel()
	_, q := dbtest.Open(t)
	fake := hdhrtest.New(nil)
	server := httptest.NewServer(fake)
	defer server.Close()
	svc := New(q, settings.New(q), func(address string) (*hdhomerun.Client, error) {
		return hdhomerun.New(address, hdhrtest.Transport(server.URL))
	})
	source, err := svc.Add(t.Context(), "tuner.test", "")
	if err != nil {
		t.Fatal(err)
	}
	before, err := svc.Channels(t.Context(), true)
	if err != nil || len(before) != 2 {
		t.Fatalf("channels=%v err=%v", before, err)
	}
	if err := svc.EnableChannel(t.Context(), before[0].ID, false); err != nil {
		t.Fatal(err)
	}
	fake.Channels[0].GuideName = "Meadow Evening"
	if _, err := svc.Refresh(t.Context(), source.ID); err != nil {
		t.Fatal(err)
	}
	after, _ := svc.Channels(t.Context(), true)
	if after[0].ID != before[0].ID || after[0].Enabled || after[0].Name != "Meadow Evening" {
		t.Fatalf("lost identity/selection: %+v", after[0])
	}
	fake.Malformed = true
	if _, err := svc.Refresh(t.Context(), source.ID); !errors.Is(err, hdhomerun.ErrLineup) {
		t.Fatal(err)
	}
	after, _ = svc.Channels(t.Context(), true)
	if len(after) != 2 || !after[1].Available {
		t.Fatal("failed refresh replaced good lineup")
	}
	fake.Malformed = false
	old := fake.Channels
	fake.Channels = []hdhrtest.Channel{}
	if _, err := svc.Refresh(t.Context(), source.ID); err != nil {
		t.Fatal(err)
	}
	after, _ = svc.Channels(t.Context(), true)
	if after[0].Available || after[1].Available {
		t.Fatal("empty lineup was not applied")
	}
	fake.Channels = old
	if _, err := svc.Refresh(t.Context(), source.ID); err != nil {
		t.Fatal(err)
	}
	after, _ = svc.Channels(t.Context(), true)
	if after[0].ID != before[0].ID || after[0].Enabled || !after[0].Available {
		t.Fatal("reappearing channel lost identity/selection")
	}
	fake.DeviceID = "87654321"
	if _, err := svc.Refresh(t.Context(), source.ID); !errors.Is(err, hdhomerun.ErrIdentity) {
		t.Fatalf("device substitution: %v", err)
	}
	if err := svc.Remove(t.Context(), source.ID); err != nil {
		t.Fatal(err)
	}
	after, _ = svc.Channels(t.Context(), true)
	if len(after) != 0 {
		t.Fatal("source deletion left channels")
	}
}

func TestChannelOrderAndPrivatePolicy(t *testing.T) {
	t.Parallel()
	conn, q := dbtest.Open(t)
	fake := hdhrtest.New(nil)
	fake.Channels = []hdhrtest.Channel{
		{GuideNumber: "10.2", GuideName: "Ten", URL: "http://tuner.test:5004/auto/v10.2"},
		{GuideNumber: "2.10", GuideName: "Two Ten", URL: "http://tuner.test:5004/auto/v2.10"},
		{GuideNumber: "2.2", GuideName: "Two Two", URL: "http://tuner.test:5004/auto/v2.2"},
		{GuideNumber: "1.1", GuideName: "Protected", URL: "http://tuner.test:5004/auto/v1.1", DRM: 1},
	}
	server := httptest.NewServer(fake)
	defer server.Close()
	svc := New(q, settings.New(q), func(a string) (*hdhomerun.Client, error) { return hdhomerun.New(a, hdhrtest.Transport(server.URL)) })
	if _, err := svc.Add(t.Context(), "tuner.test", ""); err != nil {
		t.Fatal(err)
	}
	channels, err := svc.Channels(t.Context(), false)
	if err != nil {
		t.Fatal(err)
	}
	if len(channels) != 3 || channels[0].Number != "2.2" || channels[1].Number != "2.10" || channels[2].Number != "10.2" {
		t.Fatalf("sort/filter: %+v", channels)
	}
	if svc.Allowed(t.Context(), false) || !svc.Allowed(t.Context(), true) {
		t.Fatal("initial access is not admins")
	}
	if err := svc.SetAccess(t.Context(), "everyone"); err != nil {
		t.Fatal(err)
	}
	if !svc.Allowed(t.Context(), false) {
		t.Fatal("member access missing")
	}
	// No ANALYZE: the ordering index must serve the actual list query.
	rows, err := conn.QueryContext(t.Context(), "EXPLAIN QUERY PLAN SELECT * FROM live_tv_channels ORDER BY major, minor, id")
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	for rows.Next() {
		var a, b, c int
		var detail string
		if err := rows.Scan(&a, &b, &c, &detail); err != nil {
			t.Fatal(err)
		}
		t.Log(detail)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
}

// TestLineupHookRunsWithoutTheLock: the listings' hook may wait on a
// recording start, which takes the tuner lock, so Add and Refresh call it
// only after releasing that lock.
func TestLineupHookRunsWithoutTheLock(t *testing.T) {
	t.Parallel()
	_, q := dbtest.Open(t)
	fake := hdhrtest.New(nil)
	server := httptest.NewServer(fake)
	defer server.Close()
	svc := New(q, settings.New(q), func(address string) (*hdhomerun.Client, error) {
		return hdhomerun.New(address, hdhrtest.Transport(server.URL))
	})
	calls := 0
	svc.OnLineup(func(context.Context) {
		calls++
		svc.ProtectRecording("dvr:test")
	})
	run := func(what string, fn func() error) {
		t.Helper()
		done := make(chan error, 1)
		go func() { done <- fn() }()
		select {
		case err := <-done:
			if err != nil {
				t.Fatalf("%s: %v", what, err)
			}
		case <-time.After(10 * time.Second):
			t.Fatalf("%s called the lineup hook holding the tuner lock", what)
		}
	}
	var source Source
	run("Add", func() (err error) { source, err = svc.Add(t.Context(), "tuner.test", ""); return err })
	run("Refresh", func() error { _, err := svc.Refresh(t.Context(), source.ID); return err })
	if calls != 2 {
		t.Fatalf("lineup hook ran %d times, want 2", calls)
	}
}
