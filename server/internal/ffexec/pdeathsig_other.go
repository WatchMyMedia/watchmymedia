//go:build !linux && !windows

package ffexec

import "syscall"

// procAttr starts the process as the leader of a new process group. There
// is no parent-death signal here: Track and KillLeftovers stand in for it.
func procAttr(bool) *syscall.SysProcAttr {
	return &syscall.SysProcAttr{Setpgid: true}
}
