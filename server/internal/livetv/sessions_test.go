package livetv

import (
	"bytes"
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"

	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/livetv/hdhomerun"
	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/settings"
)

type liveTest struct {
	manager  *Sessions
	service  *Service
	tuner    *hdhrtest.Tuner
	owner    Owner
	channels []Channel
	revoked  atomic.Bool
	playback *playback.Manager
}

func newLiveTest(t *testing.T, paced bool, limit int) *liveTest {
	t.Helper()
	_, q := dbtest.Open(t)
	fake := hdhrtest.New(nil)
	if paced {
		fake.Stream = hdhrtest.Broadcast("ffmpeg")
	}
	server := httptest.NewServer(fake)
	t.Cleanup(server.Close)
	service := New(q, settings.New(q), func(a string) (*hdhomerun.Client, error) { return hdhomerun.New(a, hdhrtest.Transport(server.URL)) })
	if _, err := service.Add(t.Context(), "tuner.test", ""); err != nil {
		t.Fatal(err)
	}
	channels, err := service.Channels(t.Context(), false)
	if err != nil {
		t.Fatal(err)
	}
	pm := playback.NewManager("ffmpeg", t.TempDir(), playback.Capabilities{Encoder: "libx264"}, time.Minute, limit, slog.New(slog.DiscardHandler))
	env := &liveTest{service: service, tuner: fake, owner: Owner{UserID: 1, SignInID: "signin-1", Name: "Viewer", Admin: true}, channels: channels, playback: pm}
	env.manager = NewSessions(service, pm, SessionOptions{FFmpeg: "ffmpeg", Dir: t.TempDir(), Validate: func(_ context.Context, id string) (Owner, error) {
		if env.revoked.Load() || id != env.owner.SignInID {
			return Owner{}, ErrOwner
		}
		return env.owner, nil
	}})
	t.Cleanup(env.manager.Close)
	return env
}
func waitTuner(t *testing.T, fake *hdhrtest.Tuner, want int) {
	t.Helper()
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
	defer cancel()
	for {
		_, changed := fake.Idle()
		n, _, _ := fake.Counts()
		if n == want {
			return
		}
		select {
		case <-changed:
		case <-ctx.Done():
			t.Fatalf("tuner active=%d want=%d", n, want)
		}
	}
}
func TestLiveSessionLifecycle(t *testing.T) {
	t.Parallel()
	e := newLiveTest(t, true, 2)
	m := e.manager
	ctx, cancel := context.WithTimeout(t.Context(), 30*time.Second)
	defer cancel()
	v, err := m.Start(ctx, e.owner, e.channels[0].ID, "480", "request-one", "Browser")
	if err != nil {
		t.Fatal(err)
	}
	same, err := m.Start(ctx, e.owner, e.channels[0].ID, "480", "request-one", "Browser")
	if err != nil || same.ID != v.ID {
		t.Fatalf("idempotency: %+v %v", same, err)
	}
	if _, err := m.Start(ctx, e.owner, e.channels[1].ID, "480", "request-one", "Browser"); !errors.Is(err, ErrRequest) {
		t.Fatalf("changed retry: %v", err)
	}
	ready, err := m.WaitReady(ctx, e.owner, v.ID)
	if err != nil || ready.State != "playing" {
		t.Fatalf("ready=%+v err=%v", ready, err)
	}
	f, err := m.Media(ctx, e.owner, v.ID, "live.m3u8")
	if err != nil {
		t.Fatal(err)
	}
	b, err := io.ReadAll(f)
	_ = f.Close()
	if err != nil {
		t.Fatal(err)
	}
	if count := strings.Count(string(b), "#EXTINF:"); count < 3 {
		t.Fatalf("ready output has only %d complete two-second segments", count)
	}
	var segment string
	for line := range strings.SplitSeq(string(b), "\n") {
		if strings.HasSuffix(line, ".ts") {
			segment = line
			break
		}
	}
	f, err = m.Media(ctx, e.owner, v.ID, segment)
	if err != nil {
		t.Fatal(err)
	}
	segmentBytes, err := io.ReadAll(f)
	_ = f.Close()
	if err != nil || len(segmentBytes) == 0 {
		t.Fatalf("empty or unreadable segment: %v", err)
	}
	other := e.owner
	other.SignInID = "another-signin"
	if _, err := m.Media(ctx, other, v.ID, segment); !errors.Is(err, ErrOwner) {
		t.Fatalf("same account, different sign-in got media: %v", err)
	}
	for _, name := range []string{"../live.m3u8", "segment-000000000.ts.tmp", "/etc/passwd"} {
		if f, err := m.Media(ctx, e.owner, v.ID, name); err == nil {
			_ = f.Close()
			t.Fatalf("unsafe media name %q", name)
		}
	}
	if _, err := m.Heartbeat(ctx, e.owner, v.ID, true, false); err != nil {
		t.Fatal(err)
	}
	// A slow playlist response must not hold ffmpeg's publication path open.
	held, err := m.Media(ctx, e.owner, v.ID, "live.m3u8")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = held.Close() }()
	replacement := filepath.Join(m.opts.Dir, v.ID, "replacement.tmp")
	if err := os.WriteFile(replacement, b, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(replacement, filepath.Join(m.opts.Dir, v.ID, "live.m3u8")); err != nil {
		t.Fatalf("open media blocked atomic playlist update: %v", err)
	}
	heldSegment, err := m.Media(ctx, e.owner, v.ID, segment)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = heldSegment.Close() }()
	e.revoked.Store(true)
	m.sweep(time.Now())
	s, _ := m.find(e.owner, v.ID)
	select {
	case <-s.done:
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	_ = held.Close()
	_ = heldSegment.Close()
	waitTuner(t, e.tuner, 0)
	if e.playback.Running() != 0 {
		t.Fatal("encoder slot leaked")
	}
	if _, err := m.Media(ctx, e.owner, v.ID, segment); err == nil {
		t.Fatal("revoked media remained available")
	}
	if _, err := os.Stat(filepath.Join(m.opts.Dir, v.ID)); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("scratch left behind: %v", err)
	}
}

func TestNegotiatedLiveCopyUsesOneUnsharedTuner(t *testing.T) {
	t.Parallel()
	e := newLiveTest(t, true, 2)
	ctx, cancel := context.WithTimeout(t.Context(), 30*time.Second)
	defer cancel()
	profile := &LiveProfile{SegmentFormats: []string{"mpegts"}, VideoCodecs: []string{"mpeg2video"},
		AudioCodecs: []string{"ac3"}, Interlaced: true, MaxHeight: 1080, MaxBitrate: 8_000_000}
	v, err := e.manager.StartWithProfile(ctx, e.owner, e.channels[0].ID, "1080", "copy-one", "Test", profile)
	if err != nil {
		t.Fatal(err)
	}
	if again, err := e.manager.StartWithProfile(ctx, e.owner, e.channels[0].ID, "1080", "copy-one", "Test", profile); err != nil || again.ID != v.ID {
		t.Fatalf("profile retry: %+v %v", again, err)
	}
	otherProfile := *profile
	otherProfile.Interlaced = false
	if _, err := e.manager.StartWithProfile(ctx, e.owner, e.channels[0].ID, "1080", "copy-one", "Test", &otherProfile); !errors.Is(err, ErrRequest) {
		t.Fatalf("changed profile reused request: %v", err)
	}
	ready, err := e.manager.WaitReady(ctx, e.owner, v.ID)
	if err != nil || ready.Mode != "original" || ready.State != "playing" {
		t.Fatalf("negotiated live copy: %+v %v", ready, err)
	}
	_, peak, opens := e.tuner.Counts()
	if peak != 1 || opens != 1 {
		t.Fatalf("copy opened %d tuners (peak %d)", opens, peak)
	}
	if err := e.manager.Stop(e.owner, v.ID); err != nil {
		t.Fatal(err)
	}
}

func TestNegotiatedLiveFMP4ServesInitFromOwnedDirectory(t *testing.T) {
	t.Parallel()
	e := newLiveTest(t, true, 2)
	ctx, cancel := context.WithTimeout(t.Context(), 30*time.Second)
	defer cancel()
	profile := &LiveProfile{SegmentFormats: []string{"fmp4"}, VideoCodecs: []string{"h264"},
		AudioCodecs: []string{"aac"}, MaxHeight: 480, MaxBitrate: 3_000_000}
	v, err := e.manager.StartWithProfile(ctx, e.owner, e.channels[0].ID, "480", "fmp4-one", "Test", profile)
	if err != nil {
		t.Fatal(err)
	}
	ready, err := e.manager.WaitReady(ctx, e.owner, v.ID)
	if err != nil || ready.State != "playing" {
		t.Fatalf("fMP4 ready: %+v %v", ready, err)
	}
	for _, name := range []string{"live.m3u8", "init.mp4"} {
		f, err := e.manager.Media(ctx, e.owner, v.ID, name)
		if err != nil {
			t.Fatalf("serve %s: %v", name, err)
		}
		b, readErr := io.ReadAll(f)
		closeErr := f.Close()
		if readErr != nil || closeErr != nil {
			t.Fatalf("read %s: %v, close: %v", name, readErr, closeErr)
		}
		if name == "live.m3u8" && !bytes.Contains(b, []byte(`#EXT-X-MAP:URI="init.mp4"`)) ||
			name == "init.mp4" && !bytes.Contains(b, []byte("ftyp")) {
			t.Fatalf("invalid %s output", name)
		}
	}
	if err := e.manager.Stop(e.owner, v.ID); err != nil {
		t.Fatal(err)
	}
	waitTuner(t, e.tuner, 0)
}

func TestLiveAdmissionRefusesBeforeTuningAndReleasesOnStop(t *testing.T) {
	e := newLiveTest(t, true, 2)
	m := e.manager
	var admitted, ended atomic.Int32
	m.opts.OnAdmit = func(_ context.Context, _, quality string) error {
		if quality == "1080" {
			return errors.New("upload limit")
		}
		admitted.Add(1)
		return nil
	}
	m.opts.OnEnd = func(string) { ended.Add(1) }
	if _, err := m.Start(t.Context(), e.owner, e.channels[0].ID, "1080", "refused", "Browser"); err == nil {
		t.Fatal("upload refusal was ignored")
	}
	if n, _, _ := e.tuner.Counts(); n != 0 {
		t.Fatalf("refusal tuned %d streams", n)
	}
	v, err := m.Start(t.Context(), e.owner, e.channels[0].ID, "480", "accepted", "Browser")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m.Start(t.Context(), e.owner, e.channels[0].ID, "480", "accepted", "Browser"); err != nil {
		t.Fatal(err)
	}
	if admitted.Load() != 1 {
		t.Fatalf("idempotent retry admitted %d times", admitted.Load())
	}
	if err := m.Stop(e.owner, v.ID); err != nil {
		t.Fatal(err)
	}
	if ended.Load() != 1 {
		t.Fatalf("reservation released %d times", ended.Load())
	}
}

func TestLiveCapacityLeaseAndPause(t *testing.T) {
	t.Parallel()
	e := newLiveTest(t, false, 2)
	m := e.manager
	one, err := m.Start(t.Context(), e.owner, e.channels[0].ID, "480", "one", "test")
	if err != nil {
		t.Fatal(err)
	}
	two, err := m.Start(t.Context(), e.owner, e.channels[1].ID, "480", "two", "test")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m.Start(t.Context(), e.owner, e.channels[0].ID, "480", "three", "test"); !errors.Is(err, hdhomerun.Busy) {
		t.Fatalf("third session: %v", err)
	}
	waitTuner(t, e.tuner, 2)
	if err := m.Stop(e.owner, one.ID); err != nil {
		t.Fatal(err)
	}
	waitTuner(t, e.tuner, 1)
	if err := m.Stop(e.owner, one.ID); err != nil {
		t.Fatal("stop must be idempotent", err)
	}
	if _, err := m.Heartbeat(t.Context(), e.owner, two.ID, true, false); err != nil {
		t.Fatal(err)
	}
	// Heartbeats/media cannot prolong a paused tuner beyond the allowance.
	m.mu.Lock()
	s := m.items[two.ID]
	s.pausedAt = time.Now().Add(-PauseAllowance - time.Second)
	m.mu.Unlock()
	m.sweep(time.Now())
	<-s.done
	waitTuner(t, e.tuner, 0)
	m.mu.Lock()
	reason := s.view.Reason
	m.mu.Unlock()
	if reason != "pause_expired" {
		t.Fatal(reason)
	}
	three, err := m.Start(t.Context(), e.owner, e.channels[0].ID, "480", "four", "test")
	if err != nil {
		t.Fatal(err)
	}
	waitTuner(t, e.tuner, 1)
	m.sweep(time.Now().Add(Lease + time.Second))
	s, _ = m.find(e.owner, three.ID)
	<-s.done
	waitTuner(t, e.tuner, 0)
	if e.playback.Running() != 0 {
		t.Fatal("abandoned session kept capacity")
	}
}

func TestLivePolicyStopsAndShutdown(t *testing.T) {
	t.Parallel()
	for _, action := range []string{"source", "channel", "access", "shutdown"} {
		t.Run(action, func(t *testing.T) {
			e := newLiveTest(t, false, 1)
			m := e.manager
			v, err := m.Start(t.Context(), e.owner, e.channels[0].ID, "480", "one", "test")
			if err != nil {
				t.Fatal(err)
			}
			waitTuner(t, e.tuner, 1)
			switch action {
			case "source":
				err = e.service.Remove(t.Context(), e.channels[0].SourceID)
			case "channel":
				err = e.service.EnableChannel(t.Context(), e.channels[0].ID, false)
			case "access":
				err = e.service.SetAccess(t.Context(), "none")
			case "shutdown":
				m.Close()
			}
			if err != nil {
				t.Fatal(err)
			}
			waitTuner(t, e.tuner, 0)
			s, _ := m.find(e.owner, v.ID)
			select {
			case <-s.done:
			default:
				t.Fatal("mutation returned before cleanup")
			}
			if e.playback.Running() != 0 {
				t.Fatal("mutation leaked capacity")
			}
		})
	}
}

func TestLiveStorageLimit(t *testing.T) {
	t.Parallel()
	e := newLiveTest(t, true, 1)
	m := e.manager
	ctx, cancel := context.WithTimeout(t.Context(), 30*time.Second)
	defer cancel()
	v, err := m.Start(ctx, e.owner, e.channels[0].ID, "480", "storage", "test")
	if err != nil {
		t.Fatal(err)
	}
	if v, err := m.WaitReady(ctx, e.owner, v.ID); err != nil || v.State != "playing" {
		t.Fatalf("ready: %+v %v", v, err)
	}
	f, err := os.Create(filepath.Join(m.opts.Dir, v.ID, "oversized.tmp"))
	if err != nil {
		t.Fatal(err)
	}
	err = f.Truncate(DiskLimit + 1)
	_ = f.Close()
	if err != nil {
		t.Fatal(err)
	}
	s, _ := m.find(e.owner, v.ID)
	select {
	case <-s.done:
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	m.mu.Lock()
	reason := s.view.Reason
	m.mu.Unlock()
	if reason != "storage_limit" {
		t.Fatal(reason)
	}
	waitTuner(t, e.tuner, 0)
	if e.playback.Running() != 0 {
		t.Fatal("disk failure kept encoder slot")
	}
}

func TestSharedStorageMonitorStopsLiveSession(t *testing.T) {
	t.Parallel()
	e := newLiveTest(t, true, 1)
	m := e.manager
	e.playback.SetLiveCache(m.opts.Dir, m.StopForStorage)
	ctx, cancel := context.WithTimeout(t.Context(), 30*time.Second)
	defer cancel()
	v, err := m.Start(ctx, e.owner, e.channels[0].ID, "480", "shared-storage", "test")
	if err != nil {
		t.Fatal(err)
	}
	if ready, err := m.WaitReady(ctx, e.owner, v.ID); err != nil || ready.State != "playing" {
		t.Fatalf("ready: %+v %v", ready, err)
	}
	limits := playback.DefaultResourceLimits()
	limits.MinFreeBytes = 0
	limits.MaxSessionBytes = 1024
	e.playback.SetResourceLimits(limits)
	runDone := make(chan struct{})
	go func() { e.playback.Run(ctx); close(runDone) }()
	s, _ := m.find(e.owner, v.ID)
	select {
	case <-s.done:
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	m.mu.Lock()
	reason := s.view.Reason
	m.mu.Unlock()
	if reason != "storage_limit" {
		t.Fatalf("reason=%q", reason)
	}
	waitTuner(t, e.tuner, 0)
	if e.playback.Running() != 0 {
		t.Fatal("storage stop kept encoder slot")
	}
	cancel()
	<-runDone
}

func TestLiveStartRejectsLowFreeSpaceBeforeTuning(t *testing.T) {
	t.Parallel()
	e := newLiveTest(t, false, 1)
	limits := playback.DefaultResourceLimits()
	limits.Free = func(string) (uint64, error) { return 0, nil }
	e.playback.SetResourceLimits(limits)
	if _, err := e.manager.Start(t.Context(), e.owner, e.channels[0].ID, "480", "low-disk", "test"); !errors.Is(err, playback.ErrStorageBudget) {
		t.Fatalf("start error=%v", err)
	}
	if len(e.manager.items) != 0 || e.playback.Running() != 0 {
		t.Fatal("low-disk start reserved a session")
	}
	if active, _, _ := e.tuner.Counts(); active != 0 {
		t.Fatalf("low-disk start opened %d tuners", active)
	}
}

func TestLiveEncoderStartFailureReleasesTuner(t *testing.T) {
	t.Parallel()
	e := newLiveTest(t, false, 1)
	m := e.manager
	m.opts.FFmpeg = filepath.Join(t.TempDir(), "no-encoder")
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
	defer cancel()
	v, err := m.Start(ctx, e.owner, e.channels[0].ID, "480", "failure", "test")
	if err != nil {
		t.Fatal(err)
	}
	failed, err := m.WaitReady(ctx, e.owner, v.ID)
	if err != nil || failed.Reason != "encoder_failed" {
		t.Fatalf("failure: %+v %v", failed, err)
	}
	waitTuner(t, e.tuner, 0)
	if e.playback.Running() != 0 {
		t.Fatal("failed encoder kept slot")
	}
}

func TestLiveEncoderExitReleasesBlockedInput(t *testing.T) {
	t.Parallel()
	e := newLiveTest(t, false, 1)
	var logs bytes.Buffer
	e.manager.opts.Log = slog.New(slog.NewTextHandler(&logs, nil))
	// The test binary rejects ffmpeg flags and exits immediately, while the
	// tuner has sent headers but no input. Wait must not wait for that input.
	bin, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	e.manager.opts.FFmpeg = bin
	ctx, cancel := context.WithTimeout(t.Context(), 3*time.Second)
	defer cancel()
	v, err := e.manager.Start(ctx, e.owner, e.channels[0].ID, "480", "exit", "test")
	if err != nil {
		t.Fatal(err)
	}
	v, err = e.manager.WaitReady(ctx, e.owner, v.ID)
	if err != nil || v.State != "failed" || v.Reason != "encoder_failed" {
		t.Fatalf("early encoder exit: %+v %v", v, err)
	}
	waitTuner(t, e.tuner, 0)
	if e.playback.Running() != 0 {
		t.Fatal("encoder exit kept capacity")
	}
	if !strings.Contains(logs.String(), "live encoder failed") || !strings.Contains(logs.String(), "err=") {
		t.Fatalf("encoder failure has no server diagnostic: %s", logs.String())
	}
}

func TestLiveTuneDeadlineIncludesStartup(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		pm := playback.NewManager("unused", t.TempDir(), playback.Capabilities{}, time.Minute, 1, slog.New(slog.DiscardHandler))
		m := NewSessions(New(nil, nil, nil), pm, SessionOptions{Dir: t.TempDir(), Validate: func(ctx context.Context, _ string) (Owner, error) {
			// No encoder has launched or stream opened yet. The overall tune
			// deadline must still stop this blocked startup operation.
			<-ctx.Done()
			return Owner{}, ctx.Err()
		}})
		t.Cleanup(m.Close)
		ctx, cancel := context.WithCancel(m.ctx)
		s := &session{ctx: ctx, cancel: cancel, done: make(chan struct{}), ready: make(chan struct{}), view: SessionView{ID: "tuning", State: "starting", StartedAt: time.Now()}}
		m.items[s.view.ID] = s
		m.wg.Go(func() { m.run(s) })
		<-s.done
		if s.view.State != "failed" || s.view.Reason != "stream_stalled" || time.Since(s.view.StartedAt) != TuneTimeout {
			t.Fatalf("startup deadline: %+v", s.view)
		}
		if pm.Running() != 0 {
			t.Fatal("startup deadline kept encoder reservation")
		}
	})
}

func TestLiveConcurrentRetryAndStop(t *testing.T) {
	t.Parallel()
	e := newLiveTest(t, false, 1)
	var wg sync.WaitGroup
	start := make(chan struct{})
	ids := make(chan string, 24)
	for range cap(ids) {
		wg.Go(func() {
			<-start
			v, err := e.manager.Start(t.Context(), e.owner, e.channels[0].ID, "480", "same-request", "Browser")
			if err != nil {
				t.Error(err)
				return
			}
			ids <- v.ID
		})
	}
	close(start)
	wg.Wait()
	close(ids)
	var id string
	for got := range ids {
		if id != "" && got != id {
			t.Fatalf("concurrent retry made another session: %s != %s", got, id)
		}
		id = got
	}
	if id == "" {
		t.Fatal("no session was created")
	}
	waitTuner(t, e.tuner, 1)
	for range cap(ids) {
		wg.Go(func() {
			if err := e.manager.Stop(e.owner, id); err != nil {
				t.Error(err)
			}
		})
	}
	wg.Wait()
	waitTuner(t, e.tuner, 0)
	if _, peak, opens := e.tuner.Counts(); peak != 1 || opens != 1 || e.playback.Running() != 0 {
		t.Fatalf("retry/stop leaked capacity: peak=%d opens=%d running=%d", peak, opens, e.playback.Running())
	}
}
