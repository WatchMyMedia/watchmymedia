package dvr

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"os/exec"
	"testing"
	"time"

	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
	"watchmymedia/server/internal/playback"
)

func TestAdaptiveLiveFallbackKeepsLogicalSessionCaptureAndRecording(t *testing.T) {
	for _, slots := range []int{2, 3} {
		t.Run(fmt.Sprint(slots), func(t *testing.T) { adaptiveLiveFallback(t, slots) })
	}
}

func adaptiveLiveFallback(t *testing.T, slots int) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not installed")
	}
	if _, err := exec.LookPath("ffprobe"); err != nil {
		t.Skip("ffprobe not installed")
	}
	f := setup(t, hdhrtest.Broadcast("ffmpeg"))
	pm := playback.NewManager("ffmpeg", t.TempDir(), playback.Capabilities{Encoder: "libx264"}, time.Minute, slots, slog.New(slog.DiscardHandler))
	t.Cleanup(pm.StopAll)
	owner := livetv.Owner{UserID: f.actor.UserID, SignInID: "adaptive-owner", Admin: true}
	sessions := livetv.NewSessions(f.live, pm, livetv.SessionOptions{FFmpeg: "ffmpeg", Dir: t.TempDir(), ShareSources: true,
		Validate: func(_ context.Context, id string) (livetv.Owner, error) {
			current := owner
			current.SignInID = id
			return current, nil
		}})
	t.Cleanup(sessions.Close)
	sessions.SetSharedSourceInput(f.s.JoinLive)
	ctx, cancel := context.WithTimeout(t.Context(), time.Minute)
	defer cancel()
	profile := &livetv.LiveProfile{SegmentFormats: []string{"fmp4"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}, MaxAudioChannels: 2}
	start, err := sessions.StartAdaptive(ctx, owner, f.channels[0].ID, "adaptive", "Fixture", profile, 0, false)
	if err != nil {
		t.Fatal(err)
	}
	ready, err := sessions.WaitReady(ctx, owner, start.ID)
	if err != nil || ready.State != "playing" || ready.Adaptive == nil || pm.Claimed() != 2 {
		t.Fatalf("family: %+v %v claims %d", ready, err, pm.Claimed())
	}
	media, err := sessions.Media(ctx, owner, ready.ID, "live.m3u8")
	if err != nil {
		t.Fatal(err)
	}
	_, _ = io.ReadAll(media)
	_ = media.Close()
	peerOwner := owner
	peerOwner.SignInID = "adaptive-peer"
	var peer livetv.SessionView
	if slots == 3 {
		peer, err = sessions.StartAdaptive(ctx, peerOwner, f.channels[0].ID, "peer", "Fixture", profile, 0, false)
		if err != nil {
			t.Fatal(err)
		}
		peer, err = sessions.WaitReady(ctx, peerOwner, peer.ID)
		if err != nil || peer.Adaptive == nil || pm.Claimed() != 2 {
			t.Fatalf("peer failed to share family: %+v %v claims %d", peer, err, pm.Claimed())
		}
	}
	now := unix(f.clock.Load())
	recording, err := f.s.Manual(ctx, f.actor, Manual{ChannelID: f.channels[0].ID, Start: now, End: now.Add(time.Hour), Name: "Adaptive qualification", Repeat: "once", Zone: "UTC"})
	if err != nil {
		t.Fatal(err)
	}
	f.s.tick(ctx, now)
	rung := ready.Adaptive.Renditions[0]
	beat, err := sessions.HeartbeatAutomatic(ctx, owner, ready.ID, false, false, livetv.LiveMeasurement{State: "playing", RenditionHeight: rung.Height, RenditionBitrate: rung.Bitrate})
	if err != nil || beat.RenditionHeight != rung.Height || beat.Directive != nil {
		t.Fatalf("observed rung: %+v %v", beat, err)
	}
	_, err = sessions.HeartbeatAutomatic(ctx, owner, ready.ID, false, false, livetv.LiveMeasurement{AdaptiveFailed: true})
	if err != nil {
		t.Fatal(err)
	}
	ticker := time.NewTicker(20 * time.Millisecond)
	defer ticker.Stop()
	for beat.Directive == nil {
		select {
		case <-ctx.Done():
			t.Fatal(ctx.Err())
		case <-ticker.C:
		}
		beat, err = sessions.Status(ctx, owner, ready.ID)
		if err != nil || (beat.State != "playing" && beat.State != "starting") {
			t.Fatalf("fallback lost parent: %+v %v", beat, err)
		}
	}
	beat, err = sessions.HeartbeatAutomatic(ctx, owner, ready.ID, false, false, livetv.LiveMeasurement{SwitchGeneration: beat.Directive.Generation})
	if err != nil || beat.ID != ready.ID || beat.Adaptive != nil || pm.Claimed() != (slots-2)*2+1 {
		t.Fatalf("fallback acknowledgement: %+v %v claims %d", beat, err, pm.Claimed())
	}
	if active, _, opens := f.fake.Counts(); active != 1 || opens != 1 {
		t.Fatalf("fallback retuned capture: %d %d", active, opens)
	}
	if err := sessions.Stop(owner, ready.ID); err != nil {
		t.Fatal(err)
	}
	if active, _, opens := f.fake.Counts(); active != 1 || opens != 1 {
		t.Fatalf("stopping viewer stopped recording: %d %d", active, opens)
	}
	f.s.mu.Lock()
	out := f.s.running[recording.ID]
	f.s.mu.Unlock()
	if out == nil {
		t.Fatal("recording lost capture")
	}
	out.mu.Lock()
	bytes := out.r.Bytes
	out.mu.Unlock()
	if bytes <= 0 {
		t.Fatal("recording did not advance during fallback")
	}
	if err := f.s.Cancel(ctx, recording.ID, f.actor); err != nil {
		t.Fatal(err)
	}
	f.s.tick(ctx, unix(f.clock.Load()))
	if peer.ID != "" {
		current, err := sessions.Status(ctx, peerOwner, peer.ID)
		if err != nil || current.Adaptive == nil || current.State != "playing" {
			t.Fatalf("fallback stopped peer: %+v %v", current, err)
		}
		media, err := sessions.Media(ctx, peerOwner, peer.ID, "live.m3u8")
		if err != nil {
			t.Fatal(err)
		}
		_ = media.Close()
		if err := sessions.Stop(peerOwner, peer.ID); err != nil {
			t.Fatal(err)
		}
	}
	if pm.Claimed() != 0 {
		t.Fatal("fallback leaked claims")
	}
}
