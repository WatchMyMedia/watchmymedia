package ffexec

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// A process a killed server left running is killed at the next start; a
// pid file whose pid now names another process is only removed.
func TestKillLeftovers(t *testing.T) {
	dir := t.TempDir()
	if err := Track(dir); err != nil {
		t.Fatal(err)
	}
	defer trackDir.Store(nil)
	cmd := Command(context.Background(), "/bin/sleep", "60")
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	pid := cmd.cmd.Process.Pid
	if _, err := os.Stat(pidFile(dir, pid)); err != nil {
		t.Fatalf("no pid file: %v", err)
	}
	// This test's own process stands in for one that took a dead
	// process's pid: its start time is not the one recorded.
	other := os.Getppid()
	if err := os.WriteFile(pidFile(dir, other), []byte("12345.000000\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if n := KillLeftovers(dir); n != 1 {
		t.Errorf("killed %d, want 1", n)
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		if err == nil {
			t.Error("sleep exited cleanly")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("the leftover was not killed")
	}
	if !alive(other) {
		t.Error("killed a process the pid file did not name")
	}
	if left, _ := filepath.Glob(filepath.Join(dir, "*.pid")); len(left) != 0 {
		t.Errorf("pid files left: %v", left)
	}
	// A process that exits takes its pid file with it.
	if err := Command(context.Background(), "/usr/bin/true").Run(); err != nil {
		t.Fatal(err)
	}
	if left, _ := filepath.Glob(filepath.Join(dir, "*.pid")); len(left) != 0 {
		t.Errorf("pid files after a clean exit: %v", left)
	}
}
