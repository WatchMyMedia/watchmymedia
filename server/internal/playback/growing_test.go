package playback

import (
	"bytes"
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"watchmymedia/server/internal/ffexec"
)

type blockedTail struct {
	*bytes.Reader
	closed chan struct{}
	once   atomic.Bool
}

func (r *blockedTail) Read(b []byte) (int, error) {
	n, err := r.Reader.Read(b)
	if err == io.EOF {
		<-r.closed
	}
	return n, err
}
func (r *blockedTail) Close() error {
	if r.once.CompareAndSwap(false, true) {
		close(r.closed)
	}
	return nil
}

// A growing source may wait indefinitely at its current EOF. A bounded
// ffmpeg window must release the input copier and start the next window.
func TestGrowingEventResumesAndClosesWaitingInput(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	t.Parallel()
	ctx, cancel := context.WithTimeout(t.Context(), 30*time.Second)
	defer cancel()
	path := filepath.Join(t.TempDir(), "recording.ts")
	cmd := ffexec.Command(ctx, ffmpeg, ffexec.FFmpegArgs("-y", "-f", "lavfi", "-i", "testsrc2=size=160x96:rate=24:duration=40", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=40", "-c:v", "mpeg2video", "-threads", "1", "-c:a", "ac3", "-f", "mpegts", path)...)
	if err := cmd.Run(); err != nil {
		t.Fatal(err)
	}
	body, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	m := NewManager(ffmpeg, t.TempDir(), Capabilities{Encoder: "libx264", Encoders: []string{"libx264"}}, time.Minute, 2, testLog)
	m.eventAhead, m.eventResume = 12*time.Second, 4*time.Second
	var first *blockedTail
	spec := Spec{PlayID: "growing-long", UserID: 1, Path: path, Mode: ModeTranscode,
		Video: VideoPlan{Codec: "h264", Width: 160, Height: 96, Encoder: "libx264"}, Audio: AudioPlan{Index: 1, Codec: "aac", Channels: 2}, BurnIn: -1}
	spec.Growing = func(_ context.Context, startMs int64) (GrowingInput, error) {
		effective := min(startMs, int64(32_000))
		offset := int64(float64(len(body))*float64(effective)/40_000) / 188 * 188
		reader := bytes.NewReader(body[offset:])
		if startMs == 0 {
			first = &blockedTail{Reader: reader, closed: make(chan struct{})}
			return GrowingInput{Reader: first, StartMs: effective}, nil
		}
		return GrowingInput{Reader: io.NopCloser(reader), StartMs: effective}, nil
	}
	s, err := m.Start(ctx, spec)
	if err != nil {
		t.Fatal(err)
	}
	defer m.Stop(s.ID)
	eventually(t, "first growing run did not pause", func() bool { return !s.event.running() && s.event.preparedMs.Load() >= 11_000 })
	if first == nil || !first.once.Load() {
		t.Fatal("waiting growing input was not closed after bounded run")
	}
	for range 12 {
		b, readErr := s.ReadPlaylist()
		if readErr != nil {
			t.Fatal(readErr)
		}
		if strings.Contains(string(b), "#EXT-X-ENDLIST") {
			break
		}
		p := parseEventPlaylist(b)
		var last string
		for name, end := range p.segments {
			if end == p.endMs {
				last = name
			}
		}
		if last == "" {
			t.Fatal("no growing segment")
		}
		s.EventSegmentRequested(last)
		eventually(t, "growing continuation did not advance", func() bool { return s.event.preparedMs.Load() >= p.endMs+3000 || s.Finished() })
	}
	select {
	case <-s.done:
	case <-ctx.Done():
		t.Fatal("growing event did not finalize")
	}
	if err := s.Err(); err != nil {
		t.Fatal(err)
	}
	if end := s.event.preparedMs.Load(); end < 34_000 || end > 48_000 {
		t.Fatalf("growing prepared end=%d", end)
	}
}

// This exercises the production stdin/event path with a real MPEG-TS, not
// just the follower. Temporary EOF behavior and rename are tested by growing.
func TestGrowingTransportProducesEventThenEndList(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	ctx, cancel := context.WithTimeout(t.Context(), 30*time.Second)
	defer cancel()
	path := filepath.Join(t.TempDir(), "broadcast.ts")
	cmd := ffexec.Command(ctx, ffmpeg, ffexec.FFmpegArgs("-y", "-f", "lavfi", "-i", "testsrc2=size=160x96:rate=24:duration=12", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=12", "-c:v", "mpeg2video", "-threads", "1", "-c:a", "ac3", "-f", "mpegts", path)...)
	if err := cmd.Run(); err != nil {
		t.Fatalf("broadcast: %v", err)
	}
	body, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	allowEOF := make(chan struct{})
	m := NewManager(ffmpeg, t.TempDir(), Capabilities{Encoder: "libx264", Encoders: []string{"libx264"}}, time.Minute, 2, testLog)
	defer m.StopAll()
	spec := Spec{FileID: 0, PlayID: "recordingplay", UserID: 1, Path: path, Mode: ModeTranscode, Video: VideoPlan{Codec: "h264", Width: 160, Height: 96, Encoder: "libx264", Deinterlace: true}, Audio: AudioPlan{Index: 1, Codec: "aac", Channels: 2}, BurnIn: -1, Growing: func(ctx context.Context, _ int64) (GrowingInput, error) {
		r, w := io.Pipe()
		go func() {
			if _, err := w.Write(body); err == nil {
				select {
				case <-allowEOF:
				case <-ctx.Done():
				}
			}
			_ = w.Close()
		}()
		return GrowingInput{Reader: r}, nil
	}}
	session, err := m.Start(ctx, spec)
	if err != nil {
		t.Fatal(err)
	}
	first, err := session.ReadPlaylist()
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(first), "#EXT-X-ENDLIST") {
		t.Fatal("temporary end was published as final")
	}
	close(allowEOF)
	select {
	case <-session.done:
	case <-ctx.Done():
		t.Fatal("session did not finish")
	}
	if session.Err() != nil {
		t.Fatal(session.Err())
	}
	final, err := session.ReadPlaylist()
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(final), "#EXT-X-ENDLIST") {
		t.Fatalf("missing end list: %s", final)
	}
}

// closeRecorder is a growing input that notes being closed.
type closeRecorder struct {
	io.Reader
	closed bool
}

func (c *closeRecorder) Close() error { c.closed = true; return nil }

// Opening a growing recording reads the recorder's state and the disk. It
// must not hold the manager's lock, which every other session's playlist
// and segment requests take (Get); and a play stopped meanwhile must not
// start, nor leave the recording open.
func TestGrowingInputOpensOutsideTheManagerLock(t *testing.T) {
	t.Parallel()
	m := NewManager("unused", t.TempDir(), Capabilities{Encoder: "libx264", Encoders: []string{"libx264"}}, time.Minute, 2, testLog)
	defer m.StopAll()
	spec := Spec{PlayID: "recordingplay", UserID: 1, Mode: ModeTranscode, Video: VideoPlan{Codec: "h264", Width: 160, Height: 96, Encoder: "libx264"},
		Audio: AudioPlan{Index: 1, Codec: "aac", Channels: 2}, BurnIn: -1}
	// unlocked reports whether the manager's lock is free while the
	// recording opens, and then that Get answers.
	unlocked := func() bool {
		if !m.mu.TryLock() {
			t.Error("the growing input opened under the manager's lock")
			return false
		}
		m.mu.Unlock()
		if _, ok := m.Get("another-session"); ok {
			t.Error("Get found a session that does not exist")
		}
		return true
	}

	finishing := errors.New("the recording is finishing")
	spec.Growing = func(context.Context, int64) (GrowingInput, error) {
		unlocked()
		return GrowingInput{}, finishing
	}
	if _, err := m.Start(t.Context(), spec); !errors.Is(err, finishing) {
		t.Fatalf("start = %v, want the open's error", err)
	}
	if n := len(m.Sessions()); n != 0 {
		t.Fatalf("%d sessions left after a failed open", n)
	}

	input := &closeRecorder{Reader: strings.NewReader("")}
	spec.Growing = func(context.Context, int64) (GrowingInput, error) {
		if unlocked() {
			// A seek replaced this play while its recording opened.
			m.StopPlay(spec.PlayID, spec.UserID)
		}
		return GrowingInput{Reader: input}, nil
	}
	if _, err := m.Start(t.Context(), spec); !errors.Is(err, ErrStopped) {
		t.Fatalf("start = %v, want ErrStopped", err)
	}
	if !input.closed {
		t.Error("the recording was left open")
	}
	if n := len(m.Sessions()); n != 0 {
		t.Fatalf("%d sessions left after a stopped open", n)
	}
}
