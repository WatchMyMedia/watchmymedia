package dvr

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
	"watchmymedia/server/internal/ffexec"

	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
	"watchmymedia/server/internal/playback"
)

func TestSharedOutputViewerIsolation(t *testing.T) {
	t.Parallel()
	f := setup(t, hdhrtest.Broadcast("ffmpeg"))
	pm := playback.NewManager("ffmpeg", t.TempDir(), playback.Capabilities{Encoder: "libx264"}, time.Minute, 1, slog.New(slog.DiscardHandler))
	t.Cleanup(pm.StopAll)
	dir := t.TempDir()
	var revoked atomic.Bool
	var admits, ends atomic.Int64
	sessions := livetv.NewSessions(f.live, pm, livetv.SessionOptions{FFmpeg: "ffmpeg", Dir: dir, ShareSources: true,
		Validate: func(_ context.Context, id string) (livetv.Owner, error) {
			if id == "viewer-0" && revoked.Load() {
				return livetv.Owner{}, livetv.ErrOwner
			}
			return livetv.Owner{UserID: f.actor.UserID, SignInID: id, Admin: true}, nil
		},
		OnAdmit: func(context.Context, string, string) error { admits.Add(1); return nil },
		OnEnd:   func(string) { ends.Add(1) },
	})
	t.Cleanup(sessions.Close)
	sessions.SetSharedSourceInput(f.s.JoinLive)
	pm.SetLiveCache(dir, sessions.StopForStorage)
	profile := &livetv.LiveProfile{SegmentFormats: []string{"fmp4"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}, MaxHeight: 720}
	ctx, cancel := context.WithTimeout(t.Context(), 35*time.Second)
	defer cancel()
	const viewers = 4
	views := make([]livetv.SessionView, viewers)
	owners := make([]livetv.Owner, viewers)
	errs := make([]error, viewers)
	var wg sync.WaitGroup
	for i := range viewers {
		owners[i] = livetv.Owner{UserID: f.actor.UserID, SignInID: fmt.Sprintf("viewer-%d", i), Admin: true}
		wg.Go(func() {
			// Capability lists describe sets: duplicates/order cannot create a
			// second producer or consume a full encoder slot.
			variant := *profile
			if i%2 == 0 {
				variant.SegmentFormats = []string{"fmp4", "fmp4"}
				variant.VideoCodecs = []string{"h264", "h264"}
			}
			views[i], errs[i] = sessions.StartSharedOutput(ctx, owners[i], f.channels[0].ID, "720", fmt.Sprintf("play-%d", i), "Fixture", &variant)
		})
	}
	wg.Wait()
	for i, err := range errs {
		if err != nil {
			t.Fatal(err)
		}
		ready, err := sessions.WaitReady(ctx, owners[i], views[i].ID)
		if err != nil || ready.State != "playing" {
			t.Fatalf("viewer %d: %+v %v", i, ready, err)
		}
	}
	if pm.Claimed() != 1 || admits.Load() != viewers {
		t.Fatalf("claims %d, viewer reservations %d", pm.Claimed(), admits.Load())
	}
	resources, err := pm.Resources()
	if err != nil || len(resources.Sessions) != 1 || resources.Sessions[0].Kind != "live" {
		t.Fatalf("output counted more than once: %+v %v", resources, err)
	}
	// Holding a response from a revoked viewer must not delete a peer's output.
	playlist, err := sessions.Media(ctx, owners[0], views[0].ID, "live.m3u8")
	if err != nil {
		t.Fatal(err)
	}
	b, err := io.ReadAll(playlist)
	_ = playlist.Close()
	if err != nil {
		t.Fatal(err)
	}
	var segment string
	for line := range strings.SplitSeq(string(b), "\n") {
		if strings.HasPrefix(line, "segment-") {
			segment = line
			break
		}
	}
	held, err := sessions.Media(ctx, owners[0], views[0].ID, segment)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := sessions.Media(ctx, owners[1], views[0].ID, "init.mp4"); !errors.Is(err, livetv.ErrOwner) {
		t.Fatalf("cross-sign-in media: %v", err)
	}
	revoked.Store(true)
	if _, err := sessions.Status(ctx, owners[0], views[0].ID); !errors.Is(err, livetv.ErrDisabled) {
		t.Fatalf("revocation: %v", err)
	}
	if err := sessions.Stop(owners[0], views[0].ID); err != nil {
		t.Fatal(err)
	}
	_ = held.Close()
	for _, name := range []string{"live.m3u8", "init.mp4", segment} {
		file, err := sessions.Media(ctx, owners[1], views[1].ID, name)
		if err != nil {
			t.Fatalf("peer lost %s: %v", name, err)
		}
		_ = file.Close()
	}
	if pm.Claimed() != 1 || ends.Load() != 1 {
		t.Fatalf("peer worker or reservation lost: %d %d", pm.Claimed(), ends.Load())
	}
	// A different contract cannot silently reuse the already-full encoder slot.
	distinct, err := sessions.StartSharedOutput(ctx, owners[1], f.channels[0].ID, "480", "different", "Fixture", profile)
	if err != nil {
		t.Fatal(err)
	}
	failed, err := sessions.WaitReady(ctx, owners[1], distinct.ID)
	if err != nil || failed.State != "failed" || failed.Reason != "server_busy" {
		t.Fatalf("different output reused worker: %+v %v", failed, err)
	}
	capProfile := *profile
	capProfile.MaxHeight = 360
	capped, err := sessions.StartSharedOutput(ctx, owners[1], f.channels[0].ID, "720", "different-cap", "Fixture", &capProfile)
	if err != nil {
		t.Fatal(err)
	}
	capState, err := sessions.WaitReady(ctx, owners[1], capped.ID)
	if err != nil || capState.State != "failed" || capState.Reason != "server_busy" {
		t.Fatalf("incompatible capability reused worker: %+v %v", capState, err)
	}
	for i := 1; i < viewers; i++ {
		if err := sessions.Stop(owners[i], views[i].ID); err != nil {
			t.Fatal(err)
		}
	}
	if pm.Claimed() != 0 || ends.Load() != viewers+2 {
		t.Fatalf("leaked claims/reservations: %d %d", pm.Claimed(), ends.Load())
	}
	entries, err := os.ReadDir(dir)
	if err != nil || len(entries) != 0 {
		t.Fatalf("output leak: %v %v", entries, err)
	}
	waitFictionalTunerIdle(t, ctx, f.fake)
	if active, peak, opens := f.fake.Counts(); active != 0 || peak != 1 || opens != 1 {
		t.Fatalf("capture counts: %d %d %d", active, peak, opens)
	}
}

func TestSharedOutputStorageFailureKeepsRecordingCapture(t *testing.T) {
	t.Parallel()
	f := setup(t, hdhrtest.Broadcast("ffmpeg"))
	pm := playback.NewManager("ffmpeg", t.TempDir(), playback.Capabilities{Encoder: "libx264"}, time.Minute, 2, slog.New(slog.DiscardHandler))
	t.Cleanup(pm.StopAll)
	owner := livetv.Owner{UserID: f.actor.UserID, SignInID: "viewer", Admin: true}
	dir := t.TempDir()
	sessions := livetv.NewSessions(f.live, pm, livetv.SessionOptions{FFmpeg: "ffmpeg", Dir: dir, ShareSources: true, Validate: func(context.Context, string) (livetv.Owner, error) { return owner, nil }})
	t.Cleanup(sessions.Close)
	sessions.SetSharedSourceInput(f.s.JoinLive)
	pm.SetLiveCache(dir, sessions.StopForStorage)
	ctx, cancel := context.WithTimeout(t.Context(), 35*time.Second)
	defer cancel()
	profile := &livetv.LiveProfile{SegmentFormats: []string{"mpegts"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}}
	view, err := sessions.StartSharedOutput(ctx, owner, f.channels[0].ID, "360", "play", "Fixture", profile)
	if err != nil {
		t.Fatal(err)
	}
	ready, err := sessions.WaitReady(ctx, owner, view.ID)
	if err != nil || ready.State != "playing" {
		t.Fatalf("startup: %+v %v", ready, err)
	}
	now := unix(f.clock.Load())
	recording, err := f.s.Manual(ctx, f.actor, Manual{ChannelID: f.channels[0].ID, Start: now, End: now.Add(time.Hour), Name: "Fictional report", Repeat: "once", Zone: "UTC"})
	if err != nil {
		t.Fatal(err)
	}
	f.s.tick(ctx, now)
	snapshot, err := pm.Resources()
	if err != nil || len(snapshot.Sessions) != 1 {
		t.Fatalf("storage: %+v %v", snapshot, err)
	}
	sessions.StopForStorage(snapshot.Sessions[0].ID)
	if err := sessions.Stop(owner, view.ID); err != nil {
		t.Fatal(err)
	}
	ended, err := sessions.Status(ctx, owner, view.ID)
	if err != nil || ended.Reason != "storage_limit" || ended.Mode != "video" {
		t.Fatalf("failure: %+v %v", ended, err)
	}
	if active, _, opens := f.fake.Counts(); active != 1 || opens != 1 {
		t.Fatalf("recording capture stopped: %d %d", active, opens)
	}
	f.s.mu.Lock()
	p := f.s.pipes[sourceKey{f.channels[0].SourceID, f.channels[0].ID}]
	out := f.s.running[recording.ID]
	f.s.mu.Unlock()
	if p == nil || out == nil {
		t.Fatal("recording lost its capture")
	}
	if err := f.s.Cancel(ctx, recording.ID, f.actor); err != nil {
		t.Fatal(err)
	}
}

func TestLiveLadderSharesAlignedRungs(t *testing.T) {
	t.Parallel()
	f := setup(t, hdhrtest.Broadcast("ffmpeg"))
	pm := playback.NewManager("ffmpeg", t.TempDir(), playback.Capabilities{Encoder: "libx264"}, time.Minute, 2, slog.New(slog.DiscardHandler))
	t.Cleanup(pm.StopAll)
	owner := livetv.Owner{UserID: f.actor.UserID, SignInID: "ladder-viewer", Admin: true}
	sessions := livetv.NewSessions(f.live, pm, livetv.SessionOptions{FFmpeg: "ffmpeg", Dir: t.TempDir(), ShareSources: true, Log: slog.New(slog.NewTextHandler(os.Stderr, nil)), Validate: func(context.Context, string) (livetv.Owner, error) { return owner, nil }})
	t.Cleanup(sessions.Close)
	sessions.SetSharedSourceInput(f.s.JoinLive)
	ctx, cancel := context.WithTimeout(t.Context(), 35*time.Second)
	defer cancel()
	profile := &livetv.LiveProfile{SegmentFormats: []string{"fmp4"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}, MaxAudioChannels: 2}
	first, err := sessions.StartLiveLadder(ctx, owner, f.channels[0].ID, "720", "one", "Fixture", profile)
	if err != nil {
		t.Fatal(err)
	}
	initial, err := sessions.WaitReady(ctx, owner, first.ID)
	if err != nil || initial.State != "playing" {
		t.Fatalf("first viewer: %+v %v", initial, err)
	}
	second, err := sessions.StartLiveLadder(ctx, owner, f.channels[0].ID, "720", "two", "Fixture", profile)
	if err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{first.ID, second.ID} {
		ready, err := sessions.WaitReady(ctx, owner, id)
		if err != nil || ready.State != "playing" || ready.ReadyAt == nil || ready.ReadyAt.Before(ready.StartedAt) {
			t.Fatalf("ladder: %+v %v", ready, err)
		}
	}
	if pm.Claimed() != 2 {
		t.Fatalf("expected two encoder claims, got %d", pm.Claimed())
	}
	media := func(id, name string) []byte {
		t.Helper()
		file, err := sessions.Media(ctx, owner, id, name)
		if err != nil {
			t.Fatal(err)
		}
		defer file.Close()
		b, err := io.ReadAll(file)
		if err != nil {
			t.Fatal(err)
		}
		return b
	}
	master := string(media(first.ID, "live.m3u8"))
	if strings.Count(master, "#EXT-X-STREAM-INF:") != 2 || !strings.Contains(master, `URI="audio-live.m3u8"`) {
		t.Fatalf("bad master: %s", master)
	}
	for _, name := range []string{"low", "high", "audio"} {
		playlist := string(media(first.ID, name+"-live.m3u8"))
		init := media(first.ID, name+"-init.mp4")
		var segment string
		for line := range strings.SplitSeq(playlist, "\n") {
			if strings.HasPrefix(line, name+"-segment-") {
				segment = line
				break
			}
		}
		received := append(init, media(first.ID, segment)...)
		cmd := ffexec.Command(ctx, "ffmpeg", ffexec.FFmpegArgs("-f", "mp4", "-i", "pipe:0", "-t", "0.1", "-f", "null", "-")...)
		cmd.Stdin = bytes.NewReader(received)
		if err := cmd.Run(); err != nil {
			t.Fatalf("%s failed decoding: %v", name, err)
		}
	}
	if err := sessions.Stop(owner, first.ID); err != nil {
		t.Fatal(err)
	}
	media(second.ID, "high-live.m3u8")
	if err := sessions.Stop(owner, second.ID); err != nil {
		t.Fatal(err)
	}
	if pm.Claimed() != 0 {
		t.Fatal("ladder leaked claims")
	}
	waitFictionalTunerIdle(t, ctx, f.fake)
}

func TestLiveLadderRollsBackPartialAdmission(t *testing.T) {
	t.Parallel()
	for _, tc := range []struct {
		name, reason string
		slots        int
		storage      bool
	}{
		{"encoder limit", "server_busy", 1, false},
		{"storage limit", "storage_limit", 2, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := setup(t, hdhrtest.Broadcast("ffmpeg"))
			pm := playback.NewManager("ffmpeg", t.TempDir(), playback.Capabilities{Encoder: "libx264"}, time.Minute, tc.slots, slog.New(slog.DiscardHandler))
			t.Cleanup(pm.StopAll)
			if tc.storage {
				var checks atomic.Int64
				pm.SetResourceLimits(playback.ResourceLimits{MinFreeBytes: 1, Free: func(string) (uint64, error) {
					if checks.Add(1) >= 3 {
						return 0, nil
					}
					return 1 << 30, nil
				}})
			}
			owner := livetv.Owner{UserID: f.actor.UserID, SignInID: "limited-viewer", Admin: true}
			sessions := livetv.NewSessions(f.live, pm, livetv.SessionOptions{FFmpeg: "ffmpeg", Dir: t.TempDir(), ShareSources: true, Validate: func(context.Context, string) (livetv.Owner, error) { return owner, nil }})
			t.Cleanup(sessions.Close)
			sessions.SetSharedSourceInput(f.s.JoinLive)
			ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
			defer cancel()
			profile := &livetv.LiveProfile{SegmentFormats: []string{"fmp4"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}}
			view, err := sessions.StartLiveLadder(ctx, owner, f.channels[0].ID, "720", "limited", "Fixture", profile)
			if err != nil {
				t.Fatal(err)
			}
			terminal, err := sessions.WaitReady(ctx, owner, view.ID)
			if err != nil || terminal.State != "failed" || terminal.Reason != tc.reason {
				t.Fatalf("partial admission: %+v %v", terminal, err)
			}
			if pm.Claimed() != 0 {
				t.Fatal("partial ladder admission leaked the first claim")
			}
			if media, err := sessions.Media(ctx, owner, view.ID, "live.m3u8"); err == nil {
				_ = media.Close()
				t.Fatal("unprepared master was published")
			}
			if err := sessions.Stop(owner, view.ID); err != nil {
				t.Fatal(err)
			}
			waitFictionalTunerIdle(t, ctx, f.fake)
		})
	}
}
