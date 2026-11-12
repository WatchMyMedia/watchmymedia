package playback

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"testing"
	"time"
)

// Download sessions run within their own limit, inside the manager's, and
// a play that finds every slot taken stops the newest download's ffmpeg
// (PLAN-DOWNLOADS.md §3.2).
func TestDownloadSessionsGiveWay(t *testing.T) {
	t.Parallel()
	m := NewManager("ffmpeg", t.TempDir(), Capabilities{}, time.Minute, 2, testLog)
	m.SetDownloadLimit(1)
	play := fakeSession(t, m, "play", true, time.Now())
	older := fakeSession(t, m, "o-older", true, time.Now())
	older.Download, older.Started = true, time.Now().Add(-time.Minute)
	waiting := fakeSession(t, m, "o-waiting", true, time.Now())
	waiting.Download = true
	waiting.vod.live.Store(0)
	waiting.vod.run = nil

	// Over the download limit, a download waits even with a slot free.
	m.mu.Lock()
	m.sessions = map[string]*Session{"o-older": older, "o-waiting": waiting}
	m.mu.Unlock()
	if m.reserveRun(waiting) {
		t.Fatal("a second download started over the download limit")
	}
	if got := m.DownloadsRunning(); got != 1 || m.Running() != 1 {
		t.Errorf("downloads running = %d of %d", got, m.Running())
	}

	// Every slot taken: a play's new session stops the download.
	m.mu.Lock()
	m.sessions["play"] = play
	second, _, err := m.newSessionLocked(Spec{FileID: 2, PlayID: "p2", UserID: 1}, "libx264")
	if err == nil {
		second.starting.Store(true)
		m.sessions[second.ID] = second
	}
	m.mu.Unlock()
	if err != nil {
		t.Fatalf("play refused: %v", err)
	}
	eventually(t, "the download's ffmpeg was not stopped", func() bool { return !hasRun(older) })
	if !older.yielding.Load() || m.DownloadsRunning() != 0 {
		t.Errorf("yielding %v, downloads running %d", older.yielding.Load(), m.DownloadsRunning())
	}
	// The download cannot take the slot back while the plays hold them.
	if m.reserveRun(older) {
		t.Error("the download took a slot from a play")
	}
	// A play never stops another play.
	m.mu.Lock()
	_, _, err = m.newSessionLocked(Spec{FileID: 3, PlayID: "p3", UserID: 1}, "libx264")
	m.mu.Unlock()
	if !errors.Is(err, ErrTooManySessions) {
		t.Errorf("third play = %v, want too many", err)
	}
	// Once a play ends, the download goes on.
	m.Stop("play")
	if !m.reserveRun(older) || older.yielding.Load() {
		t.Error("the download could not go on in the freed slot")
	}
}

// A download session is made from its spec without starting ffmpeg, found
// again by its ID, runs ffmpeg at low priority when its segments are
// fetched in order, and made again later gives the same init segment.
func TestDownloadSession(t *testing.T) {
	// This process deliberately yields CPU and I/O. Run it before this
	// package's parallel foreground transcodes, which otherwise starve it
	// beyond the normal request-readiness timeout on shared runners.
	ffmpeg := needFFmpeg(t)
	src := keyedSample(t, ffmpeg, 13, "0,3,6.5,9,12")
	m := testManager(t, ffmpeg, "software")
	t.Cleanup(m.StopAll)
	spec := Spec{FileID: 1, UserID: 1, SessionID: "o-test", Path: src, Mode: ModeTranscode, BurnIn: -1,
		Video: VideoPlan{Codec: "h264", Width: 160, Height: 90}, Audio: AudioPlan{Index: 1, Copy: true},
		VOD: &VODOptions{DurationMs: 13000, FrameRate: 24}}
	s, err := m.OpenDownload(spec)
	if err != nil {
		t.Fatal(err)
	}
	if !s.Download || s.ID != "o-test" || s.vod.running() || m.DownloadsRunning() != 0 {
		t.Fatalf("session %+v, running %v", s, s.vod.running())
	}
	again, err := m.OpenDownload(spec)
	if err != nil || again != s {
		t.Fatalf("opened again: %v, same %v", err, again == s)
	}
	ctx := context.Background()
	init, _, err := s.VODFile(ctx, VODInit)
	if err != nil {
		t.Fatal(err)
	}
	s.vod.mu.Lock()
	low := s.vod.run != nil && s.vod.run.cmd.LowPriority
	awaited := s.vod.run != nil && s.vod.run.cmd.Awaited
	s.vod.mu.Unlock()
	if !low {
		t.Error("the download's ffmpeg is not at low priority")
	}
	if !awaited {
		t.Error("the download's ffmpeg is not marked as awaited: on macOS it would be in the background policy")
	}
	for k := range s.vod.Segments() {
		if b, _, err := s.VODFile(ctx, fmt.Sprintf("v%05d.m4s", k)); err != nil || len(b) == 0 {
			t.Fatalf("segment %d: %v", k, err)
		}
	}
	// Gone after VODKeep, or a restart: made again from the same spec. This
	// time at normal priority, as the tests of what a download serves run it.
	m.Stop(s.ID)
	<-s.done
	m.DownloadsAtNormalPriority()
	s2, err := m.OpenDownload(spec)
	if err != nil || s2 == s {
		t.Fatalf("made again: %v", err)
	}
	init2, _, err := s2.VODFile(ctx, VODInit)
	if err != nil {
		t.Fatal(err)
	}
	s2.vod.mu.Lock()
	low = s2.vod.run == nil || s2.vod.run.cmd.LowPriority
	s2.vod.mu.Unlock()
	if low {
		t.Error("the download's ffmpeg is still at low priority")
	}
	if !bytes.Equal(init, init2) {
		t.Error("the remade session's init segment differs")
	}
}
