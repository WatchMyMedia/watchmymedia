package ffexec

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"

	"golang.org/x/sys/unix"
)

// trackDir is where running processes leave pid files, once Track is called.
var trackDir atomic.Pointer[string]

// Track has every process started from now on keep a pid file in dir while
// it runs, so that a server killed before it could stop them kills them
// when it starts again (KillLeftovers): macOS has no parent-death signal.
func Track(dir string) error {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	trackDir.Store(&dir)
	return nil
}

func pidFile(dir string, pid int) string { return filepath.Join(dir, strconv.Itoa(pid)+".pid") }

// startTime is when the process started, which with its pid names it: a
// pid alone may since belong to another process.
func startTime(pid int) (string, error) {
	k, err := unix.SysctlKinfoProc("kern.proc.pid", pid)
	if err != nil {
		return "", err
	}
	if int(k.Proc.P_pid) != pid {
		return "", os.ErrProcessDone
	}
	return fmt.Sprintf("%d.%06d", k.Proc.P_starttime.Sec, k.Proc.P_starttime.Usec), nil
}

// started records the process. It cannot have been reaped yet, so its pid
// is still its own.
func started(p *os.Process) {
	dir := trackDir.Load()
	if dir == nil {
		return
	}
	at, err := startTime(p.Pid)
	if err != nil {
		return
	}
	_ = os.WriteFile(pidFile(*dir, p.Pid), []byte(at+"\n"), 0o600)
}

func exited(p *os.Process) {
	if dir := trackDir.Load(); dir != nil {
		_ = os.Remove(pidFile(*dir, p.Pid))
	}
}

// KillLeftovers kills the processes whose pid files are in dir, which an
// earlier server started and did not live to stop, and removes the files.
// A pid that now belongs to another process (its start time differs) is
// left alone. It returns how many it killed.
func KillLeftovers(dir string) int {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return 0
	}
	n := 0
	for _, e := range entries {
		name, ok := strings.CutSuffix(e.Name(), ".pid")
		pid, err := strconv.Atoi(name)
		path := filepath.Join(dir, e.Name())
		if !ok || err != nil || pid <= 1 || pid == os.Getpid() {
			continue
		}
		b, _ := os.ReadFile(path)
		if at, err := startTime(pid); err == nil && at == strings.TrimSpace(string(b)) {
			_ = unix.Kill(-pid, unix.SIGKILL)
			_ = unix.Kill(pid, unix.SIGKILL)
			n++
		}
		_ = os.Remove(path)
	}
	return n
}
