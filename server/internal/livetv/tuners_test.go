package livetv

import (
	"context"
	"errors"
	"fmt"
	"net/http/httptest"
	"testing"
	"time"

	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/livetv/hdhomerun"
	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
	"watchmymedia/server/internal/settings"
)

func TestRecordingsWarnAndTakeNewestViewerBeforeOpening(t *testing.T) {
	t.Parallel()
	_, q := dbtest.Open(t)
	server := httptest.NewServer(hdhrtest.New(nil))
	defer server.Close()
	live := New(q, settings.New(q), func(a string) (*hdhomerun.Client, error) { return hdhomerun.New(a, hdhrtest.Transport(server.URL)) })
	src, err := live.Add(t.Context(), "tuner.test", "")
	if err != nil {
		t.Fatal(err)
	}
	manager := NewSessions(live, nil, SessionOptions{})
	defer manager.Close()
	makeViewer := func(id string, at time.Time) *session {
		ctx, cancel := context.WithCancel(context.Background())
		s := &session{view: SessionView{ID: id, StartedAt: at, State: "playing"}, owner: Owner{UserID: 1, SignInID: id}, sourceID: src.ID, ctx: ctx, cancel: cancel, done: make(chan struct{})}
		manager.items[id] = s
		go func() { <-ctx.Done(); manager.mu.Lock(); s.endedAt = time.Now(); manager.mu.Unlock(); close(s.done) }()
		return s
	}
	old := makeViewer("old", time.Now().Add(-time.Hour))
	newest := makeViewer("new", time.Now())
	at := time.Now().Add(time.Minute)
	userID := int64(1)
	manager.RecordingWarnings([]RecordingWarning{{SourceID: src.ID, ChannelID: "first-channel", ID: "first", Title: "News", At: at, UserID: &userID}})
	if newest.view.TakeoverRecordingID != "first" || old.view.TakeoverAt != nil || !newest.view.TakeoverCanCancel {
		t.Fatal(old.view, newest.view)
	}
	release1, err := live.ReserveRecording(t.Context(), "first", src.ID)
	if err != nil {
		t.Fatal(err)
	}
	defer release1()
	manager.RecordingWarnings(nil)
	if newest.view.TakeoverRecordingID != "first" {
		t.Fatal("terminal takeover target was cleared")
	}
	if newest.view.Reason != "recording" || newest.endedAt.IsZero() || !old.endedAt.IsZero() {
		t.Fatal(old.view, newest.view)
	}
	release2, err := live.ReserveRecording(t.Context(), "second", src.ID, RecordingWarning{ID: "second-recording", Title: "Weather", At: at})
	if err != nil {
		t.Fatal(err)
	}
	defer release2()
	manager.RecordingWarnings(nil)
	if old.view.TakeoverRecordingID != "second-recording" {
		t.Fatal("an immediate takeover lost its target")
	}
	if old.view.Reason != "recording" || old.endedAt.IsZero() {
		t.Fatal(old.view)
	}
	if _, err := live.ReserveRecording(t.Context(), "third", src.ID); !errors.Is(err, hdhomerun.Busy) {
		t.Fatal(err)
	}
}

func TestRecordingWarnsAndReleasesNewestSharedViewerGroup(t *testing.T) {
	t.Parallel()
	_, q := dbtest.Open(t)
	server := httptest.NewServer(hdhrtest.New(nil))
	defer server.Close()
	live := New(q, settings.New(q), func(a string) (*hdhomerun.Client, error) { return hdhomerun.New(a, hdhrtest.Transport(server.URL)) })
	src, err := live.Add(t.Context(), "tuner.test", "")
	if err != nil {
		t.Fatal(err)
	}
	manager := NewSessions(live, nil, SessionOptions{})
	defer manager.Close()
	releaseGroup, err := live.ReserveViewerSource(t.Context(), "live-group", src.ID)
	if err != nil {
		t.Fatal(err)
	}
	defer releaseGroup()
	makeViewer := func(id, channel string, shared bool, at time.Time) *session {
		ctx, cancel := context.WithCancel(context.Background())
		manager.startOrder++
		v := &session{shared: shared, view: SessionView{ID: id, Channel: Channel{ID: channel}, StartedAt: at, State: "playing"},
			owner: Owner{UserID: 1, SignInID: id}, sourceID: src.ID, ctx: ctx, cancel: cancel, done: make(chan struct{}), startOrder: manager.startOrder}
		manager.items[id] = v
		go func() {
			<-ctx.Done()
			manager.mu.Lock()
			v.endedAt = time.Now()
			manager.mu.Unlock()
			close(v.done)
		}()
		return v
	}
	// A coarse clock can give every start exactly the same timestamp. The
	// newest admitted member still selects its entire shared source group.
	at := time.Now()
	older := makeViewer("older", "other", false, at)
	members := []*session{
		makeViewer("one", "shared", true, at),
		makeViewer("two", "shared", true, at),
		makeViewer("three", "shared", true, at),
	}
	live.BorrowedRecording("live-group", "shared", func() {
		manager.StopSharedChannel("shared", "recording")
		releaseGroup()
	})
	w := RecordingWarning{SourceID: src.ID, ChannelID: "other-recording", ID: "recording", Title: "Fixture recording", At: time.Now().Add(time.Minute)}
	manager.RecordingWarnings([]RecordingWarning{w})
	for _, member := range members {
		if member.view.TakeoverRecordingID != w.ID || member.view.TakeoverAt == nil {
			t.Fatalf("group member was not warned: %+v", member.view)
		}
	}
	if older.view.TakeoverAt != nil {
		t.Fatalf("older group was warned: %+v", older.view)
	}
	release, err := live.ReserveRecording(t.Context(), w.ID, src.ID, w)
	if err != nil {
		t.Fatal(err)
	}
	defer release()
	for _, member := range members {
		if member.view.Reason != "recording" || member.endedAt.IsZero() {
			t.Fatalf("group member kept the tuner: %+v", member.view)
		}
	}
	if !older.endedAt.IsZero() {
		t.Fatal("older viewer was stopped")
	}
}

func TestConcurrentRecordingsClaimDifferentViewers(t *testing.T) {
	t.Parallel()
	_, q := dbtest.Open(t)
	server := httptest.NewServer(hdhrtest.New(nil))
	defer server.Close()
	live := New(q, settings.New(q), func(a string) (*hdhomerun.Client, error) { return hdhomerun.New(a, hdhrtest.Transport(server.URL)) })
	src, err := live.Add(t.Context(), "tuner.test", "")
	if err != nil {
		t.Fatal(err)
	}
	manager := NewSessions(live, nil, SessionOptions{})
	defer manager.Close()
	allowClose := make(chan struct{})
	viewers := make([]*session, 0, 2)
	for i := range 2 {
		ctx, cancel := context.WithCancel(context.Background())
		v := &session{view: SessionView{ID: fmt.Sprint(i), StartedAt: time.Now().Add(time.Duration(i) * time.Second), State: "playing"}, sourceID: src.ID, ctx: ctx, cancel: cancel, done: make(chan struct{})}
		manager.items[v.view.ID] = v
		viewers = append(viewers, v)
		go func() {
			<-ctx.Done()
			<-allowClose
			manager.mu.Lock()
			v.endedAt = time.Now()
			manager.mu.Unlock()
			close(v.done)
		}()
	}
	type result struct {
		release func()
		err     error
	}
	results := make(chan result, 2)
	for i := range 2 {
		go func() {
			release, err := live.ReserveRecording(t.Context(), fmt.Sprint("recording-", i), src.ID)
			results <- result{release, err}
		}()
	}
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	for _, v := range viewers {
		select {
		case <-v.ctx.Done():
		case <-ctx.Done():
			close(allowClose)
			t.Fatal("both recorders selected the same viewer")
		}
	}
	close(allowClose)
	for range 2 {
		r := <-results
		if r.err != nil {
			t.Fatal(r.err)
		}
		defer r.release()
	}
	if _, err := live.ReserveRecording(t.Context(), "third", src.ID); !errors.Is(err, hdhomerun.Busy) {
		t.Fatal(err)
	}
}
func TestTwoUpcomingRecordingsWarnWhenOneTunerWasSpare(t *testing.T) {
	t.Parallel()
	_, q := dbtest.Open(t)
	server := httptest.NewServer(hdhrtest.New(nil))
	defer server.Close()
	live := New(q, settings.New(q), func(a string) (*hdhomerun.Client, error) { return hdhomerun.New(a, hdhrtest.Transport(server.URL)) })
	src, err := live.Add(t.Context(), "tuner.test", "")
	if err != nil {
		t.Fatal(err)
	}
	manager := NewSessions(live, nil, SessionOptions{})
	defer manager.Close()
	ctx, cancel := context.WithCancel(context.Background())
	v := &session{view: SessionView{ID: "viewer", StartedAt: time.Now(), State: "playing"}, sourceID: src.ID, ctx: ctx, cancel: cancel, done: make(chan struct{})}
	manager.items["viewer"] = v
	go func() { <-ctx.Done(); manager.mu.Lock(); v.endedAt = time.Now(); manager.mu.Unlock(); close(v.done) }()
	at := time.Now().Add(time.Minute)
	manager.RecordingWarnings([]RecordingWarning{{SourceID: src.ID, ChannelID: "a", ID: "first", At: at}, {SourceID: src.ID, ChannelID: "b", ID: "second", At: at}})
	if v.view.TakeoverRecordingID != "second" {
		t.Fatalf("the second reservation needs the viewer's tuner: %+v", v.view)
	}
}

// The viewer warned about a recording is the one who gives way, even when a
// newer viewer started after the warning; a viewer who starts or stops
// while the warning stands moves the warning to whoever now gives way.
func TestTheWarnedViewerGivesWay(t *testing.T) {
	t.Parallel()
	_, q := dbtest.Open(t)
	server := httptest.NewServer(hdhrtest.New(nil))
	defer server.Close()
	live := New(q, settings.New(q), func(a string) (*hdhomerun.Client, error) { return hdhomerun.New(a, hdhrtest.Transport(server.URL)) })
	src, err := live.Add(t.Context(), "tuner.test", "")
	if err != nil {
		t.Fatal(err)
	}
	manager := NewSessions(live, nil, SessionOptions{})
	defer manager.Close()
	makeViewer := func(id string, at time.Time) *session {
		ctx, cancel := context.WithCancel(context.Background())
		s := &session{view: SessionView{ID: id, StartedAt: at, State: "playing"}, owner: Owner{UserID: 1, SignInID: id}, sourceID: src.ID, ctx: ctx, cancel: cancel, done: make(chan struct{})}
		manager.mu.Lock()
		manager.items[id] = s
		manager.mu.Unlock()
		go func() { <-ctx.Done(); manager.mu.Lock(); s.endedAt = time.Now(); manager.mu.Unlock(); close(s.done) }()
		return s
	}
	first := makeViewer("first", time.Now().Add(-time.Hour))
	warned := makeViewer("warned", time.Now().Add(-time.Minute))
	w := RecordingWarning{SourceID: src.ID, ChannelID: "news", ID: "news", Title: "News", At: time.Now().Add(time.Minute)}
	manager.RecordingWarnings([]RecordingWarning{w})
	if warned.view.TakeoverRecordingID != "news" || first.view.TakeoverAt != nil {
		t.Fatal(first.view, warned.view)
	}
	// The oldest viewer leaves and a newer one starts without a re-warning
	// (as between two scheduler ticks): the warned viewer still gives way.
	first.cancel()
	<-first.done
	late := makeViewer("late", time.Now())
	release, err := live.ReserveRecording(t.Context(), "news", src.ID, w)
	if err != nil {
		t.Fatal(err)
	}
	defer release()
	if warned.endedAt.IsZero() || !late.endedAt.IsZero() {
		t.Fatalf("stopped the wrong viewer: warned %+v, late %+v", warned.view, late.view)
	}

	// With the warning kept, a viewer who leaves moves it on.
	other := makeViewer("other", time.Now().Add(time.Second))
	w2 := RecordingWarning{SourceID: src.ID, ChannelID: "weather", ID: "weather", Title: "Weather", At: time.Now().Add(time.Minute)}
	manager.RecordingWarnings([]RecordingWarning{w2})
	if other.view.TakeoverRecordingID != "weather" || late.view.TakeoverAt != nil {
		t.Fatal(late.view, other.view)
	}
	other.cancel()
	<-other.done
	manager.rewarn()
	if late.view.TakeoverRecordingID != "weather" {
		t.Fatalf("the warning didn't move to the viewer who now gives way: %+v", late.view)
	}
}
