//go:build !windows

package ffexec

import (
	"errors"
	"os"
	"os/exec"
	"runtime"
	"sync"
	"syscall"
)

// nicePath is nice's path, or "" when there is none.
var nicePath = sync.OnceValue(func() string {
	p, _ := exec.LookPath("nice")
	return p
})

// ioTool is the path of the program that sets a process's I/O class:
// ionice on Linux, taskpolicy on macOS. "" elsewhere, or when it is not there.
var ioTool = sync.OnceValue(func() string {
	var name, fallback string
	switch runtime.GOOS {
	case "linux":
		name, fallback = "ionice", "/usr/bin/ionice"
	case "darwin":
		name, fallback = "taskpolicy", "/usr/sbin/taskpolicy"
	default:
		return ""
	}
	p, err := exec.LookPath(name)
	if err != nil {
		if _, err := os.Stat(fallback); err != nil {
			return ""
		}
		p = fallback
	}
	return p
})

// ioPolicy is the command that runs a program in the lowest I/O class,
// where the OS has one, so that background reads wait for playback's. Nil
// when the tool is not there.
//
// On Linux it is ionice's idle class (-t runs the program even where the
// class cannot be set, as in some containers), awaited or not.
//
// On macOS taskpolicy sets how the CPU is shared as well. A job nobody waits
// for runs under the background policy (-b): efficiency cores only, and
// almost no CPU while every core is busy. One someone waits for (awaited)
// runs under the utility clamp with its disk reads in the throttled tier (-c
// utility -d throttle): it still gives way to playback, on any core, and
// keeps a share of a busy Mac. -d throttle is the lowest disk tier, one below
// the tier the background policy reads in.
func ioPolicy(awaited bool) []string {
	p := ioTool()
	switch {
	case p == "":
		return nil
	case runtime.GOOS == "linux":
		return []string{p, "-c3", "-t"}
	case awaited:
		return []string{p, "-c", "utility", "-d", "throttle"}
	default:
		return []string{p, "-b"}
	}
}

// lowPriority runs bin under nice -n 15 and in the lowest I/O class
// (ioPolicy), with whichever of the two exists. Both exec what they run, so
// the process keeps its pid and process group.
func lowPriority(bin string, args []string, awaited bool) (string, []string) {
	var wrap []string
	if nice := nicePath(); nice != "" {
		wrap = append(wrap, nice, "-n", "15")
	}
	wrap = append(wrap, ioPolicy(awaited)...)
	if len(wrap) == 0 {
		return bin, args
	}
	return wrap[0], append(append(wrap[1:], bin), args...)
}

// kill kills the process's group: the process was started as its leader
// (procAttr), so this reaches anything it started too.
func kill(p *os.Process) error {
	err := syscall.Kill(-p.Pid, syscall.SIGKILL)
	if errors.Is(err, syscall.ESRCH) {
		return os.ErrProcessDone
	}
	return err
}
