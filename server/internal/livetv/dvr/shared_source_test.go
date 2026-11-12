package dvr

import (
	"bytes"
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/hdhomerun"
	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
	"watchmymedia/server/internal/playback"
)

func TestSharedSessionStartsUseOneSource(t *testing.T) {
	t.Parallel()
	f := setup(t, hdhrtest.Broadcast("ffmpeg"))
	pm := playback.NewManager("ffmpeg", t.TempDir(), playback.Capabilities{Encoder: "libx264"}, time.Minute, 3, slog.New(slog.DiscardHandler))
	t.Cleanup(pm.StopAll)
	owner := livetv.Owner{UserID: f.actor.UserID, SignInID: "fictional-viewer", Name: f.actor.Name, Admin: true}
	sessions := livetv.NewSessions(f.live, pm, livetv.SessionOptions{FFmpeg: "ffmpeg", Dir: t.TempDir(), ShareSources: true,
		Validate: func(_ context.Context, id string) (livetv.Owner, error) {
			if id != owner.SignInID {
				return livetv.Owner{}, livetv.ErrOwner
			}
			return owner, nil
		}})
	t.Cleanup(sessions.Close)
	sessions.SetSharedSourceInput(f.s.JoinLive)
	sessions.SetRecordingInput(f.s.sharing, f.s.LiveInput)
	f.s.sessions = sessions
	ctx, cancel := context.WithTimeout(t.Context(), 35*time.Second)
	defer cancel()
	profile := &livetv.LiveProfile{SegmentFormats: []string{"mpegts"}, VideoCodecs: []string{"mpeg2video"},
		AudioCodecs: []string{"ac3"}, Interlaced: true, MaxHeight: 1080, MaxBitrate: 8_000_000}
	first, err := sessions.StartWithProfile(ctx, owner, f.channels[0].ID, "1080", "first", "Web", profile)
	if err != nil {
		t.Fatal(err)
	}
	second, err := sessions.Start(ctx, owner, f.channels[0].ID, "720", "second", "TV")
	if err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{first.ID, second.ID} {
		ready, err := sessions.WaitReady(ctx, owner, id)
		if err != nil || ready.State != "playing" {
			t.Fatalf("shared viewer %s did not play: %+v %v", id, ready, err)
		}
		if id == first.ID && ready.Mode != "original" || id == second.ID && ready.Mode != "video" {
			t.Fatalf("negotiated and legacy viewers selected wrong modes: %+v", ready)
		}
	}
	if active, peak, opens := f.fake.Counts(); active != 1 || peak != 1 || opens != 1 {
		t.Fatalf("two conversion outputs used %d physical sources (active %d, peak %d)", opens, active, peak)
	}
	if err := sessions.Stop(owner, first.ID); err != nil {
		t.Fatal(err)
	}
	if active, _, _ := f.fake.Counts(); active != 1 {
		t.Fatal("stopping one viewer stopped the other's source")
	}
	if err := sessions.Stop(owner, second.ID); err != nil {
		t.Fatal(err)
	}
}

func TestAutomaticLiveKeepsOneLogicalViewerAndTuner(t *testing.T) {
	t.Parallel()
	f := setup(t, hdhrtest.Broadcast("ffmpeg"))
	pm := playback.NewManager("ffmpeg", t.TempDir(), playback.Capabilities{Encoder: "libx264"}, time.Minute, 2, slog.New(slog.DiscardHandler))
	t.Cleanup(pm.StopAll)
	owner := livetv.Owner{UserID: f.actor.UserID, SignInID: "fictional-auto-viewer", Name: f.actor.Name, Admin: true}
	sessions := livetv.NewSessions(f.live, pm, livetv.SessionOptions{FFmpeg: "ffmpeg", Dir: t.TempDir(), ShareSources: true,
		Validate: func(_ context.Context, id string) (livetv.Owner, error) {
			if id != owner.SignInID {
				return livetv.Owner{}, livetv.ErrOwner
			}
			return owner, nil
		}})
	t.Cleanup(sessions.Close)
	sessions.SetSharedSourceInput(f.s.JoinLive)
	sessions.SetRecordingInput(f.s.sharing, f.s.LiveInput)
	f.s.sessions = sessions
	profile := &livetv.LiveProfile{SegmentFormats: []string{"mpegts"}, VideoCodecs: []string{"mpeg2video"},
		AudioCodecs: []string{"ac3"}, Interlaced: true, MaxHeight: 1080, MaxBitrate: 8_000_000}
	ctx, cancel := context.WithTimeout(t.Context(), 35*time.Second)
	defer cancel()
	started, err := sessions.StartAutomatic(ctx, owner, f.channels[0].ID, "auto-first", "Web", profile, 50_000_000, false)
	if err != nil {
		t.Fatal(err)
	}
	if again, err := sessions.StartAutomatic(ctx, owner, f.channels[0].ID, "auto-first", "Web", profile, 50_000_000, false); err != nil || again.ID != started.ID {
		t.Fatalf("automatic retry: %+v %v", again, err)
	}
	ready, err := sessions.WaitReady(ctx, owner, started.ID)
	if err != nil || ready.State != "playing" || !ready.Automatic || ready.ActualQuality != "original" || ready.OutputGeneration != 1 || ready.Mode != "original" {
		t.Fatalf("automatic output: %+v %v", ready, err)
	}
	if len(sessions.Activity(owner, false)) != 1 {
		t.Fatal("internal output appeared as a separate viewer")
	}
	if active, peak, opens := f.fake.Counts(); active != 1 || peak != 1 || opens != 1 {
		t.Fatalf("automatic output retuned: active %d peak %d opens %d", active, peak, opens)
	}
	if err := sessions.Stop(owner, started.ID); err != nil {
		t.Fatal(err)
	}
	waitFictionalTunerIdle(t, ctx, f.fake)
	if active, _, _ := f.fake.Counts(); active != 0 || pm.Running() != 0 {
		t.Fatalf("automatic stop kept tuner or process: active %d running %d", active, pm.Running())
	}
}

func TestAutomaticLiveSwitchesDownAndBackWithoutRetuning(t *testing.T) {
	t.Parallel()
	f := setup(t, hdhrtest.Broadcast("ffmpeg"))
	pm := playback.NewManager("ffmpeg", t.TempDir(), playback.Capabilities{Encoder: "libx264"}, time.Minute, 2, slog.New(slog.DiscardHandler))
	t.Cleanup(pm.StopAll)
	owner := livetv.Owner{UserID: f.actor.UserID, SignInID: "fictional-switch-viewer", Name: f.actor.Name, Admin: true}
	base := time.Now()
	var offset atomic.Int64
	sessions := livetv.NewSessions(f.live, pm, livetv.SessionOptions{FFmpeg: "ffmpeg", Dir: t.TempDir(), ShareSources: true,
		Clock: func() time.Time { return base.Add(time.Duration(offset.Load())) },
		Validate: func(_ context.Context, id string) (livetv.Owner, error) {
			if id != owner.SignInID {
				return livetv.Owner{}, livetv.ErrOwner
			}
			return owner, nil
		}})
	t.Cleanup(sessions.Close)
	sessions.SetSharedSourceInput(f.s.JoinLive)
	sessions.SetRecordingInput(f.s.sharing, f.s.LiveInput)
	f.s.sessions = sessions
	profile := &livetv.LiveProfile{SegmentFormats: []string{"mpegts"}, VideoCodecs: []string{"mpeg2video"},
		AudioCodecs: []string{"ac3"}, Interlaced: true, MaxHeight: 1080, MaxBitrate: 8_000_000}
	ctx, cancel := context.WithTimeout(t.Context(), 45*time.Second)
	defer cancel()
	started, err := sessions.StartAutomatic(ctx, owner, f.channels[0].ID, "auto-switch", "Web", profile, 50_000_000, false)
	if err != nil {
		t.Fatal(err)
	}
	ready, err := sessions.WaitReady(ctx, owner, started.ID)
	if err != nil || ready.ActualQuality != "original" {
		t.Fatalf("initial automatic output: %+v %v", ready, err)
	}
	age := int64(0)
	stalls := 0
	beat := func(state string, buffer, speed int64, generation uint64) livetv.SessionView {
		t.Helper()
		view, err := sessions.HeartbeatAutomatic(ctx, owner, started.ID, false, true,
			livetv.LiveMeasurement{State: state, BufferedMs: buffer, ThroughputBps: speed,
				ThroughputAgeMs: &age, Stalls: &stalls, SwitchGeneration: generation})
		if err != nil {
			t.Fatal(err)
		}
		return view
	}
	waitDirective := func() *livetv.LiveDirective {
		t.Helper()
		for {
			view, err := sessions.Status(ctx, owner, started.ID)
			if err != nil {
				t.Fatal(err)
			}
			if view.Directive != nil {
				return view.Directive
			}
			select {
			case <-ctx.Done():
				t.Fatal("live output directive was not prepared")
			case <-time.After(100 * time.Millisecond):
			}
		}
	}
	offset.Store(int64(10 * time.Second))
	beat("playing", 6_000, 50_000_000, 0)
	offset.Store(int64(30 * time.Second))
	stalls = 1
	beat("buffering", 1_000, 500_000, 0)
	offset.Store(int64(36 * time.Second))
	beat("buffering", 1_000, 500_000, 0)
	down := waitDirective()
	if down.From != "original" || down.To == "original" || down.Generation != 2 {
		t.Fatalf("down directive: %+v", down)
	}
	stalls = 0
	offset.Store(int64(37 * time.Second))
	lowered := beat("playing", 6_000, 500_000, down.Generation)
	if lowered.ID != started.ID || lowered.OutputGeneration != 2 || lowered.ActualQuality == "original" || lowered.Mode != "video" {
		t.Fatalf("lowered output changed logical viewer: %+v", lowered)
	}
	offset.Store(int64(4 * time.Minute))
	beat("playing", 6_000, 50_000_000, 0)
	offset.Store(int64(5*time.Minute + time.Second))
	beat("playing", 6_000, 50_000_000, 0)
	up := waitDirective()
	if up.Generation != 3 || up.To == down.To {
		t.Fatalf("recovery directive: %+v", up)
	}
	offset.Store(int64(5*time.Minute + 2*time.Second))
	recovered := beat("playing", 6_000, 50_000_000, up.Generation)
	if up.To != "original" {
		offset.Store(int64(11 * time.Minute))
		beat("playing", 6_000, 50_000_000, 0)
		offset.Store(int64(12*time.Minute + time.Second))
		beat("playing", 6_000, 50_000_000, 0)
		back := waitDirective()
		if back.To != "original" || back.Generation != 4 {
			t.Fatalf("direct recovery directive: %+v", back)
		}
		offset.Store(int64(12*time.Minute + 2*time.Second))
		recovered = beat("playing", 6_000, 50_000_000, back.Generation)
	}
	if recovered.ID != started.ID || recovered.ActualQuality != "original" || recovered.Mode != "original" {
		t.Fatalf("recovered output changed logical viewer: %+v", recovered)
	}
	if active, peak, opens := f.fake.Counts(); active != 1 || peak != 1 || opens != 1 {
		t.Fatalf("quality switches retuned: active %d peak %d opens %d", active, peak, opens)
	}
	if err := sessions.Stop(owner, started.ID); err != nil {
		t.Fatal(err)
	}
}

func TestAutomaticLiveAtFullLimitDowngradesButKeepsWorkingOutputOnUpgrade(t *testing.T) {
	t.Parallel()
	f := setup(t, hdhrtest.Broadcast("ffmpeg"))
	pm := playback.NewManager("ffmpeg", t.TempDir(), playback.Capabilities{Encoder: "libx264"}, time.Minute, 1, slog.New(slog.DiscardHandler))
	t.Cleanup(pm.StopAll)
	owner := livetv.Owner{UserID: f.actor.UserID, SignInID: "fictional-full-viewer", Name: f.actor.Name, Admin: true}
	base := time.Now()
	var offset atomic.Int64
	sessions := livetv.NewSessions(f.live, pm, livetv.SessionOptions{FFmpeg: "ffmpeg", Dir: t.TempDir(), ShareSources: true,
		Clock: func() time.Time { return base.Add(time.Duration(offset.Load())) },
		Validate: func(_ context.Context, id string) (livetv.Owner, error) {
			if id != owner.SignInID {
				return livetv.Owner{}, livetv.ErrOwner
			}
			return owner, nil
		}})
	t.Cleanup(sessions.Close)
	sessions.SetSharedSourceInput(f.s.JoinLive)
	sessions.SetRecordingInput(f.s.sharing, f.s.LiveInput)
	f.s.sessions = sessions
	profile := &livetv.LiveProfile{SegmentFormats: []string{"mpegts"}, VideoCodecs: []string{"mpeg2video"},
		AudioCodecs: []string{"ac3"}, Interlaced: true, MaxHeight: 1080, MaxBitrate: 8_000_000}
	ctx, cancel := context.WithTimeout(t.Context(), 40*time.Second)
	defer cancel()
	started, err := sessions.StartAutomatic(ctx, owner, f.channels[0].ID, "auto-full", "Web", profile, 50_000_000, false)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := sessions.WaitReady(ctx, owner, started.ID); err != nil {
		t.Fatal(err)
	}
	age := int64(0)
	stalls := 0
	beat := func(state string, buffer, speed int64, generation uint64) livetv.SessionView {
		t.Helper()
		v, err := sessions.HeartbeatAutomatic(ctx, owner, started.ID, false, true,
			livetv.LiveMeasurement{State: state, BufferedMs: buffer, ThroughputBps: speed,
				ThroughputAgeMs: &age, Stalls: &stalls, SwitchGeneration: generation})
		if err != nil {
			t.Fatal(err)
		}
		return v
	}
	offset.Store(int64(10 * time.Second))
	beat("playing", 30_000, 50_000_000, 0)
	offset.Store(int64(30 * time.Second))
	stalls = 1
	beat("buffering", 1_000, 500_000, 0)
	offset.Store(int64(36 * time.Second))
	beat("buffering", 1_000, 500_000, 0)
	var down *livetv.LiveDirective
	for down == nil {
		v, err := sessions.Status(ctx, owner, started.ID)
		if err != nil {
			t.Fatal(err)
		}
		down = v.Directive
		if down == nil {
			select {
			case <-ctx.Done():
				t.Fatal("full-budget downgrade was not prepared")
			case <-time.After(100 * time.Millisecond):
			}
		}
	}
	stalls = 0
	offset.Store(int64(37 * time.Second))
	lowered := beat("playing", 30_000, 500_000, down.Generation)
	if lowered.ActualQuality == "original" || lowered.Mode != "video" || lowered.OutputGeneration != 2 {
		t.Fatalf("full-budget downgrade: %+v", lowered)
	}
	offset.Store(int64(4 * time.Minute))
	beat("playing", 30_000, 50_000_000, 0)
	offset.Store(int64(5*time.Minute + time.Second))
	beat("playing", 30_000, 50_000_000, 0)
	for {
		v, err := sessions.Status(ctx, owner, started.ID)
		if err != nil {
			t.Fatal(err)
		}
		if v.SwitchStatus == "waiting_capacity" {
			if v.Directive != nil || v.OutputGeneration != 2 || v.ActualQuality != lowered.ActualQuality || v.State != "playing" {
				t.Fatalf("upgrade displaced working output: %+v", v)
			}
			break
		}
		select {
		case <-ctx.Done():
			t.Fatal("full-budget upgrade did not report capacity")
		case <-time.After(100 * time.Millisecond):
		}
	}
	if active, peak, opens := f.fake.Counts(); active != 1 || peak != 1 || opens != 1 || pm.Running() != 1 {
		t.Fatalf("full-budget switches changed resources: tuner %d/%d/%d process %d", active, peak, opens, pm.Running())
	}
	if err := sessions.Stop(owner, started.ID); err != nil {
		t.Fatal(err)
	}
}

func TestAutomaticLiveFailedSwitchAtFullLimitReleasesSource(t *testing.T) {
	t.Parallel()
	f := setup(t, hdhrtest.Broadcast("ffmpeg"))
	pm := playback.NewManager("ffmpeg", t.TempDir(), playback.Capabilities{Encoder: "libx264"}, time.Minute, 1, slog.New(slog.DiscardHandler))
	t.Cleanup(pm.StopAll)
	owner := livetv.Owner{UserID: f.actor.UserID, SignInID: "fictional-failed-switch", Name: f.actor.Name, Admin: true}
	base := time.Now()
	var offset atomic.Int64
	sessions := livetv.NewSessions(f.live, pm, livetv.SessionOptions{FFmpeg: "ffmpeg", Dir: t.TempDir(), ShareSources: true,
		Clock: func() time.Time { return base.Add(time.Duration(offset.Load())) },
		Validate: func(_ context.Context, id string) (livetv.Owner, error) {
			if id != owner.SignInID {
				return livetv.Owner{}, livetv.ErrOwner
			}
			return owner, nil
		}})
	t.Cleanup(sessions.Close)
	sessions.SetSharedSourceInput(f.s.JoinLive)
	sessions.SetRecordingInput(f.s.sharing, f.s.LiveInput)
	f.s.sessions = sessions
	profile := &livetv.LiveProfile{SegmentFormats: []string{"mpegts"}, VideoCodecs: []string{"mpeg2video"},
		AudioCodecs: []string{"ac3"}, Interlaced: true, MaxHeight: 1080, MaxBitrate: 8_000_000}
	ctx, cancel := context.WithTimeout(t.Context(), 40*time.Second)
	defer cancel()
	started, err := sessions.StartAutomatic(ctx, owner, f.channels[0].ID, "auto-failed", "Web", profile, 50_000_000, false)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := sessions.WaitReady(ctx, owner, started.ID); err != nil {
		t.Fatal(err)
	}
	age := int64(0)
	stalls := 0
	beat := func(state string, buffer, speed int64) {
		t.Helper()
		if _, err := sessions.HeartbeatAutomatic(ctx, owner, started.ID, false, true,
			livetv.LiveMeasurement{State: state, BufferedMs: buffer, ThroughputBps: speed,
				ThroughputAgeMs: &age, Stalls: &stalls}); err != nil {
			t.Fatal(err)
		}
	}
	offset.Store(int64(10 * time.Second))
	beat("playing", 30_000, 50_000_000)
	offset.Store(int64(30 * time.Second))
	stalls = 1
	beat("buffering", 1_000, 500_000)
	offset.Store(int64(36 * time.Second))
	beat("buffering", 1_000, 500_000)
	var directive *livetv.LiveDirective
	for directive == nil {
		view, err := sessions.Status(ctx, owner, started.ID)
		if err != nil {
			t.Fatal(err)
		}
		directive = view.Directive
		if directive == nil {
			select {
			case <-ctx.Done():
				t.Fatal("full-limit replacement was not prepared")
			case <-time.After(100 * time.Millisecond):
			}
		}
	}
	view, err := sessions.HeartbeatAutomatic(ctx, owner, started.ID, false, true,
		livetv.LiveMeasurement{SwitchFailedGeneration: directive.Generation})
	if err != nil || view.State != "failed" || view.Reason != "switch_failed" || view.OutputGeneration != 1 {
		t.Fatalf("failed replacement was not reported: %+v %v", view, err)
	}
	if err := sessions.Stop(owner, started.ID); err != nil {
		t.Fatal(err)
	}
	waitFictionalTunerIdle(t, ctx, f.fake)
	if active, peak, _ := f.fake.Counts(); active != 0 || peak != 1 || pm.Running() != 0 {
		t.Fatalf("failed replacement leaked tuner/process: %d/%d/%d", active, peak, pm.Running())
	}
}

func waitFictionalTunerIdle(t *testing.T, ctx context.Context, tuner *hdhrtest.Tuner) {
	t.Helper()
	// Source reservation release is synchronous; its canceled HTTP handler
	// may observe the close a moment later under the race detector.
	for {
		idle, changed := tuner.Idle()
		if idle {
			return
		}
		select {
		case <-changed:
		case <-ctx.Done():
			t.Fatal("fictional tuner did not close")
		}
	}
}

func TestRecordingPreemptsWholeNewestViewerSource(t *testing.T) {
	t.Parallel()
	f := setup(t, hdhrtest.Broadcast("ffmpeg"))
	pm := playback.NewManager("ffmpeg", t.TempDir(), playback.Capabilities{Encoder: "libx264"}, time.Minute, 4, slog.New(slog.DiscardHandler))
	t.Cleanup(pm.StopAll)
	owner := livetv.Owner{UserID: f.actor.UserID, SignInID: "fictional-viewer", Name: f.actor.Name, Admin: true}
	sessions := livetv.NewSessions(f.live, pm, livetv.SessionOptions{FFmpeg: "ffmpeg", Dir: t.TempDir(), ShareSources: true,
		Validate: func(_ context.Context, id string) (livetv.Owner, error) {
			if id != owner.SignInID {
				return livetv.Owner{}, livetv.ErrOwner
			}
			return owner, nil
		}})
	t.Cleanup(sessions.Close)
	sessions.SetSharedSourceInput(f.s.JoinLive)
	sessions.SetRecordingInput(f.s.sharing, f.s.LiveInput)
	f.s.sessions = sessions
	ctx, cancel := context.WithTimeout(t.Context(), 40*time.Second)
	defer cancel()
	other, err := sessions.Start(ctx, owner, f.channels[1].ID, "360", "older-group", "Web")
	if err != nil {
		t.Fatal(err)
	}
	first, err := sessions.Start(ctx, owner, f.channels[0].ID, "360", "newer-one", "Phone")
	if err != nil {
		t.Fatal(err)
	}
	second, err := sessions.Start(ctx, owner, f.channels[0].ID, "360", "newer-two", "TV")
	if err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{other.ID, first.ID, second.ID} {
		if _, err := sessions.WaitReady(ctx, owner, id); err != nil {
			t.Fatal(err)
		}
	}
	if active, peak, opens := f.fake.Counts(); active != 2 || peak != 2 || opens != 2 {
		t.Fatalf("two physical groups: active %d, peak %d, opens %d", active, peak, opens)
	}
	warning := livetv.RecordingWarning{SourceID: f.channels[0].SourceID, ChannelID: "different-channel",
		ID: "priority-recording", Title: "Fixture report", At: time.Now().Add(time.Minute)}
	sessions.RecordingWarnings([]livetv.RecordingWarning{warning})
	for _, id := range []string{first.ID, second.ID} {
		view, err := sessions.Status(ctx, owner, id)
		if err != nil || view.TakeoverRecordingID != warning.ID {
			t.Fatalf("shared viewer did not receive warning: %+v %v", view, err)
		}
	}
	if view, err := sessions.Status(ctx, owner, other.ID); err != nil || view.TakeoverAt != nil {
		t.Fatalf("other group received warning: %+v %v", view, err)
	}
	release, err := f.live.ReserveRecording(ctx, warning.ID, warning.SourceID, warning)
	if err != nil {
		t.Fatal(err)
	}
	defer release()
	for _, id := range []string{first.ID, second.ID} {
		view, err := sessions.Status(ctx, owner, id)
		if err != nil || view.State != "stopped" || view.Reason != "recording" {
			t.Fatalf("shared member kept tuner: %+v %v", view, err)
		}
	}
	if view, err := sessions.Status(ctx, owner, other.ID); err != nil || view.State != "playing" {
		t.Fatalf("older group was interrupted: %+v %v", view, err)
	}
	if err := sessions.Stop(owner, other.ID); err != nil {
		t.Fatal(err)
	}
}

func TestConcurrentViewersJoinOnePhysicalSource(t *testing.T) {
	t.Parallel()
	feed := newFeed()
	f := setup(t, feed.serve)
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
	defer cancel()
	const viewers = 8
	readers := make([]io.ReadCloser, viewers)
	errs := make([]error, viewers)
	var wg sync.WaitGroup
	for i := range viewers {
		wg.Go(func() {
			readers[i], errs[i] = f.s.JoinLive(ctx, f.channels[0].SourceID, f.channels[0].ID)
		})
	}
	wg.Wait()
	for _, err := range errs {
		if err != nil {
			t.Fatal(err)
		}
	}
	select {
	case <-feed.started:
	case <-ctx.Done():
		t.Fatal("tuner did not open")
	}
	if active, peak, opens := f.fake.Counts(); active != 1 || peak != 1 || opens != 1 {
		t.Fatalf("same-channel viewers opened %d tuners (active %d, peak %d)", opens, active, peak)
	}
	for i := range viewers - 1 {
		_ = readers[i].Close()
	}
	if active, _, _ := f.fake.Counts(); active != 1 {
		t.Fatal("closing one viewer stopped the group")
	}
	_ = readers[viewers-1].Close()
	readers[viewers-1].(interface{ WaitReleased() }).WaitReleased()
	// A new channel may take the released reservation immediately, even if
	// the fake HTTP handler has not yet observed its canceled request.
	other, err := f.s.JoinLive(ctx, f.channels[0].SourceID, f.channels[1].ID)
	if err != nil {
		t.Fatal(err)
	}
	_ = other.Close()
	other.(interface{ WaitReleased() }).WaitReleased()
}

func TestSlowViewerDetachesWithoutBlockingSharedSource(t *testing.T) {
	t.Parallel()
	feed := newFeed()
	f := setup(t, feed.serve)
	ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
	defer cancel()
	slow, err := f.s.JoinLive(ctx, f.channels[0].SourceID, f.channels[0].ID)
	if err != nil {
		t.Fatal(err)
	}
	fast, err := f.s.JoinLive(ctx, f.channels[0].SourceID, f.channels[0].ID)
	if err != nil {
		t.Fatal(err)
	}
	defer slow.Close()
	defer fast.Close()
	select {
	case <-feed.started:
	case <-ctx.Done():
		t.Fatal("shared tuner did not start")
	}
	// A joined source lease is dormant until it reads. Activate both consumers
	// before this one-shot feed: starting a goroutine does not synchronize its
	// first Read, and the intentionally inactive lease must drop earlier bytes.
	slow.(*liveReader).active.Store(true)
	fast.(*liveReader).active.Store(true)
	slowRead := make(chan error, 1)
	go func() {
		_, err := io.ReadFull(slow, make([]byte, len(packet)))
		slowRead <- err
	}()
	fastBytes := make(chan int, 1)
	fastDone := make(chan struct{})
	go func() {
		defer close(fastDone)
		buffer := make([]byte, 64<<10)
		for {
			if n, err := fast.Read(buffer); n > 0 {
				select {
				case fastBytes <- n:
				case <-ctx.Done():
					return
				}
			} else if err != nil {
				return
			}
		}
	}()
	select {
	case feed.chunks <- packet:
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	select {
	case err := <-slowRead:
		if err != nil {
			t.Fatal(err)
		}
	case <-ctx.Done():
		t.Fatal("first slow-viewer read stalled")
	}
	received, sent := 0, len(packet)
	awaitFast := func() {
		for received < sent {
			select {
			case n := <-fastBytes:
				received += n
			case <-ctx.Done():
				t.Fatal("fast viewer stopped receiving source bytes")
			}
		}
	}
	awaitFast()
	// More than 64 distinct 64 KiB sends fill only the slow subscriber's
	// bounded queue. The fast reader continues to drain the same source.
	large := bytes.Repeat(packet, (64<<10)/len(packet)+1)
	for range 72 {
		select {
		case feed.chunks <- large:
			sent += len(large)
		case <-ctx.Done():
			t.Fatal("slow viewer blocked capture")
		}
		// Do not manufacture a second slow viewer by flooding the fast reader
		// while the test leaves its consumer unscheduled.
		awaitFast()
	}
	select {
	case <-slow.(*liveReader).ctx.Done():
		if !errors.Is(context.Cause(slow.(*liveReader).ctx), errLiveReaderBacklog) {
			t.Fatal("slow viewer ended for a reason other than its full queue")
		}
	case <-ctx.Done():
		t.Fatal("slow viewer was not detached")
	}
	if received != sent {
		t.Fatalf("fast viewer lost bytes: %d of %d", received, sent)
	}
	_ = slow.Close()
	_ = fast.Close()
	<-fastDone
	fast.(*liveReader).WaitReleased()
}

func TestRecordingJoinsViewerSourceAndViewerSurvivesRecordingEnd(t *testing.T) {
	t.Parallel()
	feed := newFeed()
	f := setup(t, feed.serve)
	ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
	defer cancel()
	viewer, err := f.s.JoinLive(ctx, f.channels[0].SourceID, f.channels[0].ID)
	if err != nil {
		t.Fatal(err)
	}
	defer viewer.Close()
	select {
	case <-feed.started:
	case <-ctx.Done():
		t.Fatal("viewer source did not open")
	}
	now := unix(f.clock.Load())
	recording, err := f.s.Manual(ctx, f.actor, Manual{ChannelID: f.channels[0].ID, Start: now,
		End: now.Add(time.Minute), Repeat: "once", Zone: "UTC", Name: "Shared source report"})
	if err != nil {
		t.Fatal(err)
	}
	recordOnePacket(t, ctx, f, feed.chunks, recording.ID)
	if active, peak, opens := f.fake.Counts(); active != 1 || peak != 1 || opens != 1 {
		t.Fatalf("recording retuned a shared channel: active %d, peak %d, opens %d", active, peak, opens)
	}
	if err := f.s.Cancel(ctx, recording.ID, f.actor); err != nil {
		t.Fatal(err)
	}
	f.s.tick(ctx, now)
	if !f.s.sharing(f.channels[0].ID) {
		t.Fatal("ending recording stopped its viewer source")
	}
	_ = viewer.Close()
	viewer.(interface{ WaitReleased() }).WaitReleased()
	if f.s.sharing(f.channels[0].ID) {
		t.Fatal("final viewer left a ghost source group")
	}
	// The receiver's admission remains a tuner concern, not a conversion slot.
	again, err := f.s.JoinLive(ctx, f.channels[0].SourceID, f.channels[0].ID)
	if errors.Is(err, hdhomerun.Busy) {
		t.Fatal("the last viewer did not free the physical tuner")
	}
	if err != nil {
		t.Fatal(err)
	}
	_ = again.Close()
	again.(interface{ WaitReleased() }).WaitReleased()
}
