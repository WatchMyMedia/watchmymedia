package playback

import (
	"context"
	"errors"
	"log/slog"
	"testing"
	"time"
)

// A start over the limit says so, for the API to answer 503 and retry.
func TestStartOverTheLimit(t *testing.T) {
	t.Parallel()
	m := NewManager("/nonexistent/ffmpeg", t.TempDir(), Capabilities{Encoder: "libx264"}, time.Minute, 1, slog.New(slog.DiscardHandler))
	fakeSession(t, m, "busy", false, time.Now())
	for _, spec := range []Spec{vodTranscodeSpec("/m/a.mkv"), {FileID: 2, Path: "/m/b.mkv", Mode: ModeRemux, Video: VideoPlan{Copy: true}, BurnIn: -1}} {
		if _, err := m.Start(context.Background(), spec); !errors.Is(err, ErrTooManySessions) {
			t.Errorf("vod %v: %v", spec.VOD != nil, err)
		}
	}
}

// A start stopped while it waits for an ffmpeg slot, or while ffmpeg is
// launched, fails with ErrStopped like any replaced start, not with the
// cancellation the stop caused. CI once saw "start ffmpeg: context canceled"
// from TestStartReplacedWhileWaiting when its replacement landed there.
func TestEventStartStoppedBeforeFFmpegIsStopped(t *testing.T) {
	t.Parallel()
	m := NewManager(fakeVODFFmpeg(t, ""), t.TempDir(), Capabilities{Encoder: "libx264"}, time.Minute, 4, slog.New(slog.DiscardHandler))
	for range cap(m.processes.slots) {
		m.processes.slots <- struct{}{} // every ffmpeg slot busy: the start waits
	}
	spec := Spec{FileID: 1, PlayID: "p1", UserID: 1, Path: "/m/a.mkv", Mode: ModeTranscode, Video: VideoPlan{Codec: "h264", Height: 144}, Audio: AudioPlan{Index: 1, Copy: true}, BurnIn: -1}
	result := make(chan error, 1)
	go func() {
		_, err := m.Start(context.Background(), spec)
		result <- err
	}()
	eventually(t, "the start did not wait for a slot", func() bool { return len(m.Sessions()) == 1 })
	m.StopPlay("p1", 1)
	select {
	case err := <-result:
		if !errors.Is(err, ErrStopped) {
			t.Fatalf("stopped start: %v", err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("the stopped start did not return")
	}
}

// A start replaced by a newer request for the same play while it waited
// for its first segment fails with ErrStopped, not a dead session.
func TestStartReplacedWhileWaiting(t *testing.T) {
	t.Parallel()
	bin := fakeVODFFmpeg(t, "") // writes nothing
	m := NewManager(bin, t.TempDir(), Capabilities{Encoder: "libx264"}, time.Minute, 4, slog.New(slog.DiscardHandler))
	spec := Spec{FileID: 1, PlayID: "p1", UserID: 1, Path: "/m/a.mkv", Mode: ModeTranscode, Video: VideoPlan{Codec: "h264", Height: 144}, Audio: AudioPlan{Index: 1, Copy: true}, BurnIn: -1}
	first := make(chan error, 1)
	go func() {
		_, err := m.Start(context.Background(), spec)
		first <- err
	}()
	eventually(t, "the first session did not start", func() bool { return len(m.Sessions()) == 1 })
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	_, _ = m.Start(ctx, spec) // replaces the first, then gives up waiting itself
	select {
	case err := <-first:
		if !errors.Is(err, ErrStopped) {
			t.Errorf("replaced start: %v", err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("the replaced start did not return")
	}
}
