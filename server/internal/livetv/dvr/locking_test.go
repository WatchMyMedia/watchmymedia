package dvr

import (
	"context"
	"io"
	"log/slog"
	"testing"
	"time"

	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/playback"
)

func fixturePipeline(t *testing.T, f fixture, s *Service) *pipeline {
	t.Helper()
	ctx, cancel := context.WithCancel(t.Context())
	p := &pipeline{ctx: ctx, cancel: cancel, key: sourceKey{f.channels[0].SourceID, f.channels[0].ID}, outputs: map[string]*output{}, subscribers: map[*liveReader]bool{}, done: make(chan struct{})}
	s.pipes[p.key] = p
	s.shared.Store(f.channels[0].ID, p)
	return p
}

func TestLiveCallbacksDoNotAcquireTheDVRScheduleMutex(t *testing.T) {
	t.Parallel()
	f := setup(t, nil)
	p := fixturePipeline(t, f, f.s)
	f.s.mu.Lock()
	type result struct {
		reader io.ReadCloser
		err    error
	}
	opened := make(chan result, 1)
	go func() { reader, err := f.s.LiveInput(t.Context(), f.channels[0].ID); opened <- result{reader, err} }()
	ctx, cancel := context.WithTimeout(t.Context(), 3*time.Second)
	defer cancel()
	var reader io.ReadCloser
	select {
	case result := <-opened:
		if result.err != nil {
			f.s.mu.Unlock()
			t.Fatal(result.err)
		}
		reader = result.reader
	case <-ctx.Done():
		f.s.mu.Unlock()
		result := <-opened
		if result.reader != nil {
			_ = result.reader.Close()
		}
		t.Fatal("LiveInput waited for the DVR schedule mutex")
	}
	changed := make(chan error, 1)
	go func() { changed <- f.live.SetAccess(t.Context(), "none") }()
	select {
	case err := <-changed:
		if err != nil {
			f.s.mu.Unlock()
			t.Fatal(err)
		}
	case <-ctx.Done():
		f.s.mu.Unlock()
		<-changed
		_ = reader.Close()
		t.Fatal("source listener waited for the DVR schedule mutex")
	}
	// Who may watch is the viewers' policy: the recording keeps its tuner.
	if p.ctx.Err() != nil || p.reopen.Load() {
		f.s.mu.Unlock()
		t.Fatal("an access change touched the recording's stream")
	}
	// A tuner change ends only the current stream, to be reopened, and
	// still never waits for the schedule mutex.
	go func() {
		_, err := f.live.Update(t.Context(), f.channels[0].SourceID, "tuner.test", "Kitchen", false)
		changed <- err
	}()
	select {
	case err := <-changed:
		if err != nil {
			f.s.mu.Unlock()
			t.Fatal(err)
		}
	case <-ctx.Done():
		f.s.mu.Unlock()
		<-changed
		_ = reader.Close()
		t.Fatal("source listener waited for the DVR schedule mutex")
	}
	f.s.mu.Unlock()
	defer reader.Close()
	if p.ctx.Err() != nil || !p.reopen.Load() {
		t.Fatal("a tuner change should reopen the stream, not end the recordings")
	}
	if f.live.Allowed(t.Context(), true) {
		t.Fatal("source access change did not remain synchronous")
	}
}

func TestSourceMutationCanStopAStartingSharedViewerWhileScheduling(t *testing.T) {
	t.Parallel()
	f := setup(t, nil)
	log := slog.New(slog.DiscardHandler)
	pm := playback.NewManager("missing-encoder-for-locking-test", t.TempDir(), playback.Capabilities{Encoder: "libx264"}, time.Minute, 1, log)
	t.Cleanup(pm.StopAll)
	owner := livetv.Owner{UserID: f.actor.UserID, SignInID: "fictional-viewer", Name: f.actor.Name, Admin: true}
	sessions := livetv.NewSessions(f.live, pm, livetv.SessionOptions{FFmpeg: "missing-encoder-for-locking-test", Dir: t.TempDir(), Log: log, Validate: func(context.Context, string) (livetv.Owner, error) { return owner, nil }})
	t.Cleanup(sessions.Close)
	service := New(f.q, f.s.store, f.live, sessions, f.s.opts)
	t.Cleanup(service.Close)
	p := fixturePipeline(t, f, service)
	entered := make(chan struct{})
	sessions.SetRecordingInput(service.sharing, func(ctx context.Context, channel string) (io.ReadCloser, error) {
		// Signal once the viewer has joined the pipeline, still under the
		// held schedule mutex. Signalled on the way in, the source change
		// could stop the viewer before it joined: the join was then refused,
		// nothing held this fixture's pipeline, and nothing cancelled it.
		reader, err := service.LiveInput(ctx, channel)
		close(entered)
		return reader, err
	})
	service.mu.Lock()
	if _, err := sessions.Start(t.Context(), owner, f.channels[0].ID, "480", "starting-shared-viewer", "Test"); err != nil {
		service.mu.Unlock()
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	select {
	case <-entered:
	case <-ctx.Done():
		service.mu.Unlock()
		t.Fatal("shared viewer did not enter its recording input callback")
	}
	changed := make(chan error, 1)
	go func() { changed <- f.live.SetAccess(t.Context(), "none") }()
	select {
	case err := <-changed:
		service.mu.Unlock()
		if err != nil {
			t.Fatal(err)
		}
	case <-ctx.Done():
		service.mu.Unlock()
		<-changed
		t.Fatal("source mutation deadlocked behind a starting shared viewer")
	}
	if p.ctx.Err() == nil {
		t.Fatal("source mutation returned before cancelling its pipeline")
	}
}
