package ffexec

import (
	"os"
	"sync"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

// lowPriority needs no wrapper on Windows: procAttr sets the priority class,
// the same one whether or not someone waits for the process (Cmd.Awaited).
// A nice found on PATH (Git for Windows ships one) is not used: it would run
// a Windows program through a Unix shim.
func lowPriority(bin string, args []string, _ bool) (string, []string) { return bin, args }

func procAttr(low bool) *syscall.SysProcAttr {
	if low {
		return &syscall.SysProcAttr{CreationFlags: windows.BELOW_NORMAL_PRIORITY_CLASS}
	}
	return nil
}

func kill(p *os.Process) error { return p.Kill() }

// job is a job object that kills every process in it when its last handle
// closes. The server never closes its handle, so Windows closes it when the
// server exits, however it exits, and the processes go with it.
var job = sync.OnceValues(func() (windows.Handle, error) {
	h, err := windows.CreateJobObject(nil, nil)
	if err != nil {
		return 0, err
	}
	info := windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION{
		BasicLimitInformation: windows.JOBOBJECT_BASIC_LIMIT_INFORMATION{LimitFlags: windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE},
	}
	if _, err := windows.SetInformationJobObject(h, windows.JobObjectExtendedLimitInformation,
		uintptr(unsafe.Pointer(&info)), uint32(unsafe.Sizeof(info))); err != nil {
		_ = windows.CloseHandle(h)
		return 0, err
	}
	return h, nil
})

// started puts the process in the job. ffmpeg starts no processes of its
// own, so nothing escapes between its start and this.
func started(p *os.Process) {
	j, err := job()
	if err != nil {
		return
	}
	h, err := windows.OpenProcess(windows.PROCESS_SET_QUOTA|windows.PROCESS_TERMINATE, false, uint32(p.Pid))
	if err != nil {
		return
	}
	defer func() { _ = windows.CloseHandle(h) }()
	_ = windows.AssignProcessToJobObject(j, h)
}

func exited(*os.Process) {}

// Track does nothing on Windows: the job object kills what is left.
func Track(string) error { return nil }

// KillLeftovers does nothing on Windows: the job object killed them.
func KillLeftovers(string) int { return 0 }
