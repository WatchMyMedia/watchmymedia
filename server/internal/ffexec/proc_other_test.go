//go:build !windows

package ffexec

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

// alive reports whether pid is a process that has not exited.
func alive(pid int) bool {
	return syscall.Kill(pid, 0) == nil
}

// Ending the context kills the process and what it started, and Wait does
// not hang on output a child still holds open.
func TestCancelKillsTheGroup(t *testing.T) {
	t.Parallel()
	child := filepath.Join(t.TempDir(), "child")
	ctx, cancel := context.WithCancel(context.Background())
	cmd := Command(ctx, "/bin/sh", "-c", "sleep 60 & echo $! > "+child+"; wait")
	var out strings.Builder
	cmd.Stdout = &out
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	var pid int
	for deadline := time.Now().Add(10 * time.Second); pid == 0; {
		if b, err := os.ReadFile(child); err == nil && len(b) > 1 {
			pid, _ = strconv.Atoi(strings.TrimSpace(string(b)))
		} else if time.Now().After(deadline) {
			t.Fatal("the child never started")
		} else {
			time.Sleep(10 * time.Millisecond)
		}
	}
	cancel()
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case <-done:
	case <-time.After(waitDelay + 5*time.Second):
		t.Fatal("Wait did not return after cancel")
	}
	for deadline := time.Now().Add(5 * time.Second); alive(pid); {
		if time.Now().After(deadline) {
			_ = syscall.Kill(pid, syscall.SIGKILL)
			t.Fatal("the child outlived the cancel")
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// ownNiceness is the niceness these tests run at, which a low-priority
// process adds 15 to. It is 0 at a terminal and -10 on GitHub's macOS runners.
func ownNiceness(t *testing.T) int {
	t.Helper()
	out, err := exec.Command("ps", "-o", "nice=", "-p", strconv.Itoa(os.Getpid())).Output()
	if err != nil {
		t.Fatal(err)
	}
	n, err := strconv.Atoi(strings.TrimSpace(string(out)))
	if err != nil {
		t.Fatalf("niceness %q: %v", out, err)
	}
	return n
}

func TestLowPriority(t *testing.T) {
	t.Parallel()
	if nicePath() == "" {
		t.Skip("no nice")
	}
	// Niceness stops at 19 on Linux and 20 on macOS.
	want := min(ownNiceness(t)+15, 19)
	for _, awaited := range []bool{false, true} {
		cmd := Command(context.Background(), "/bin/sh", "-c", "ps -o nice= -p $$")
		cmd.LowPriority, cmd.Awaited = true, awaited
		out, err := cmd.Output()
		if err != nil {
			t.Fatal(err)
		}
		if n, _ := strconv.Atoi(strings.TrimSpace(string(out))); n < want {
			t.Errorf("awaited %v: niceness %q, want %d", awaited, out, want)
		}
	}
}

// Low priority also puts the process in the lowest I/O class where the OS
// has one, and the wrappers exec it: it keeps its pid. On macOS a process
// someone waits for is under the utility clamp with throttled disk reads,
// and only one nobody waits for is under the background policy.
func TestLowPriorityIOClass(t *testing.T) {
	t.Parallel()
	for _, awaited := range []bool{false, true} {
		bin, args := lowPriority("ffmpeg", []string{"-i", "x"}, awaited)
		line := strings.Join(append([]string{bin}, args...), " ")
		if !strings.HasSuffix(line, "ffmpeg -i x") {
			t.Fatalf("awaited %v: command %q does not end with ffmpeg's", awaited, line)
		}
		io := ioPolicy(awaited)
		var want string
		switch {
		case runtime.GOOS == "darwin" && awaited:
			want = "taskpolicy -c utility -d throttle ffmpeg"
		case runtime.GOOS == "darwin":
			want = "taskpolicy -b ffmpeg"
		case runtime.GOOS == "linux" && io != nil:
			want = "ionice -c3 -t ffmpeg"
		}
		if !strings.Contains(line, want) {
			t.Errorf("awaited %v: command %q is not under %q", awaited, line, want)
		}
		if io == nil {
			continue
		}
		var out strings.Builder
		cmd := Command(context.Background(), "/bin/sh", "-c", "echo $$")
		cmd.LowPriority, cmd.Awaited = true, awaited
		cmd.Stdout = &out
		if err := cmd.Start(); err != nil {
			t.Fatal(err)
		}
		pid := cmd.cmd.Process.Pid
		if err := cmd.Wait(); err != nil {
			t.Fatal(err)
		}
		if got := strings.TrimSpace(out.String()); got != strconv.Itoa(pid) {
			t.Errorf("awaited %v: the shell ran as pid %s, not the %d started", awaited, got, pid)
		}
	}
}

// Awaited changes nothing for a process at normal priority.
func TestAwaitedNeedsLowPriority(t *testing.T) {
	t.Parallel()
	cmd := Command(context.Background(), "/bin/sh", "-c", "true")
	cmd.Awaited = true
	if got := cmd.prepare().Path; got != "/bin/sh" {
		t.Errorf("an awaited command at normal priority runs %q, not the shell", got)
	}
}

// A wrapper that cannot start ffmpeg is not ffmpeg failing on the file.
func TestRanOnFileSeesWrapperFailures(t *testing.T) {
	t.Parallel()
	err := Command(context.Background(), "/bin/sh", "-c", "exit 66").Run()
	if RanOnFile(err, "taskpolicy: posix_spawn: No such file or directory") {
		t.Error("taskpolicy failing to start ffmpeg counts as ffmpeg reading the file")
	}
	if !RanOnFile(err, "Invalid data found when processing input") {
		t.Error("ffmpeg failing on the file does not count")
	}
}
