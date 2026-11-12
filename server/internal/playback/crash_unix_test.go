//go:build (darwin || linux) && streamingacceptance

package playback

import (
	"context"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"watchmymedia/server/internal/ffexec"
)

// Exercise the production startup primitives across a real parent SIGKILL,
// rather than treating a context cancellation as a server crash.
func TestPlaybackAfterKilledParent(t *testing.T) {
	if testing.Short() {
		t.Skip("real parent crash and two real-time media starts")
	}
	ffmpeg := needFFmpeg(t)
	src := keyedSample(t, ffmpeg, 24, "0,3,6,9,12,18,21")
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "crash-owner"), []byte(strconv.Itoa(os.Getpid())), 0o600); err != nil {
		t.Fatal(err)
	}
	// Force real-time input so the encoder is still alive at the crash.
	bin := filepath.Join(dir, "slow-ffmpeg")
	if err := os.WriteFile(bin, []byte("#!/bin/sh\nexec '"+strings.ReplaceAll(ffmpeg, "'", "'\\''")+"' -re \"$@\"\n"), 0o700); err != nil { //nolint:gosec // executable test wrapper
		t.Fatal(err)
	}
	keep := filepath.Join(dir, "other-app.bin")
	if err := os.WriteFile(keep, []byte("preserve"), 0o600); err != nil {
		t.Fatal(err)
	}
	start := func(ready string) (*exec.Cmd, string) {
		t.Helper()
		cmd := exec.Command(os.Args[0], "-test.run=^TestPlaybackCrashHelper$")
		cmd.Env = append(os.Environ(), "WMM_TEST_CRASH_ROOT="+dir, "WMM_TEST_CRASH_SOURCE="+src,
			"WMM_TEST_CRASH_FFMPEG="+bin, "WMM_TEST_CRASH_READY="+ready)
		output, err := os.Create(filepath.Join(dir, ready+".log"))
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = output.Close() })
		cmd.Stdout, cmd.Stderr = output, output
		if err := cmd.Start(); err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = cmd.Process.Kill(); ffexec.KillLeftovers(filepath.Join(dir, "processes")) })
		for deadline := time.Now().Add(20 * time.Second); ; {
			if b, err := os.ReadFile(filepath.Join(dir, ready)); err == nil {
				return cmd, strings.TrimSpace(string(b))
			}
			if time.Now().After(deadline) {
				b, _ := os.ReadFile(output.Name())
				t.Fatalf("crash helper never prepared media: %s", b)
			}
			time.Sleep(20 * time.Millisecond)
		}
	}
	first, oldID := start("first-ready")
	children, err := exec.Command("pgrep", "-P", strconv.Itoa(first.Process.Pid)).Output()
	if err != nil || len(strings.Fields(string(children))) != 1 {
		t.Fatalf("expected one live ffmpeg child before crash: %s %v", children, err)
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(children)))
	if err != nil {
		t.Fatal(err)
	}
	if err := first.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	_ = first.Wait()
	second, newID := start("second-ready")
	if oldID == newID {
		t.Fatal("restart resurrected the ephemeral session")
	}
	for deadline := time.Now().Add(5 * time.Second); syscall.Kill(pid, 0) == nil; {
		if time.Now().After(deadline) {
			t.Fatal("the crashed parent's ffmpeg survived startup recovery")
		}
		time.Sleep(20 * time.Millisecond)
	}
	if _, err := os.Stat(filepath.Join(dir, SessionsDir, oldID)); !os.IsNotExist(err) {
		t.Fatalf("stale session cache survived restart: %v", err)
	}
	if b, err := os.ReadFile(keep); err != nil || string(b) != "preserve" {
		t.Fatalf("startup changed another application's file: %v", err)
	}
	init, err := os.ReadFile(filepath.Join(dir, SessionsDir, newID, "init.mp4"))
	if err != nil {
		t.Fatal(err)
	}
	seg, err := os.ReadFile(filepath.Join(dir, SessionsDir, newID, "seg00000.m4s"))
	if err != nil {
		t.Fatal(err)
	}
	media := filepath.Join(dir, "recovered.mp4")
	if err := os.WriteFile(media, append(init, seg...), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := ffexec.Command(t.Context(), ffmpeg, ffexec.FFmpegArgs("-i", media, "-frames:v", "3", "-f", "null", "-")...).Run(); err != nil {
		t.Fatalf("media after restart did not decode: %v", err)
	}
	if err := second.Process.Signal(syscall.SIGTERM); err != nil {
		t.Fatal(err)
	}
	if err := second.Wait(); err != nil {
		t.Fatalf("recovered helper shutdown: %v", err)
	}
}

func TestPlaybackCrashHelper(t *testing.T) {
	dir := os.Getenv("WMM_TEST_CRASH_ROOT")
	if dir == "" {
		return
	}
	owner, err := os.ReadFile(filepath.Join(dir, "crash-owner"))
	if err != nil || string(owner) != strconv.Itoa(os.Getppid()) {
		t.Fatal("crash helper requires its parent test's owned directory")
	}
	ffexec.KillLeftovers(filepath.Join(dir, "processes"))
	if err := ffexec.Track(filepath.Join(dir, "processes")); err != nil {
		t.Fatal(err)
	}
	bin := os.Getenv("WMM_TEST_CRASH_FFMPEG")
	m := NewManager(bin, dir, Capabilities{Encoder: "libx264", Encoders: []string{"libx264"}, HWAccel: "software"}, time.Minute, 1, testLog)
	if err := m.Clean(); err != nil {
		t.Fatal(err)
	}
	s, err := m.Start(t.Context(), Spec{FileID: 1, UserID: 1, PlayID: "fictional-crash", Path: os.Getenv("WMM_TEST_CRASH_SOURCE"),
		Mode: ModeRemux, BurnIn: -1, Video: VideoPlan{Codec: "h264", Copy: true}, Audio: AudioPlan{Index: 1, Copy: true}})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, os.Getenv("WMM_TEST_CRASH_READY")), []byte(s.ID), 0o600); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGTERM)
	defer cancel()
	<-ctx.Done()
	m.StopAll()
	if m.Running() != 0 || len(m.Sessions()) != 0 {
		t.Fatalf("restart cleanup left %d sessions", len(m.Sessions()))
	}
}
