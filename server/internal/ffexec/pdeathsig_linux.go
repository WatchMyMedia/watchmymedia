package ffexec

import "syscall"

// procAttr starts the process as the leader of a new process group, and
// has the kernel kill it when the server dies, however it dies. (The
// signal follows the thread that started the process; the Go runtime only
// ends threads that a goroutine locked and left, which the server does not
// do.)
func procAttr(bool) *syscall.SysProcAttr {
	return &syscall.SysProcAttr{Setpgid: true, Pdeathsig: syscall.SIGKILL}
}
