package server

import (
	"fmt"
	"golang.org/x/sys/windows"
	"syscall"
	"unsafe"
)

func (p *process) launch() error {
	job, e := windows.CreateJobObject(nil, nil)
	if e != nil {
		return e
	}
	info := windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION{BasicLimitInformation: windows.JOBOBJECT_BASIC_LIMIT_INFORMATION{LimitFlags: windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}}
	if _, e = windows.SetInformationJobObject(job, windows.JobObjectExtendedLimitInformation, uintptr(unsafe.Pointer(&info)), uint32(unsafe.Sizeof(info))); e != nil {
		windows.CloseHandle(job)
		return e
	}
	// Suspend before the first instruction so no server/FFmpeg process can
	// escape the job between Start and AssignProcessToJobObject.
	p.cmd.SysProcAttr = &syscall.SysProcAttr{CreationFlags: windows.CREATE_NO_WINDOW | windows.CREATE_SUSPENDED}
	if e = p.cmd.Start(); e != nil {
		windows.CloseHandle(job)
		return e
	}
	fail := func(err error) error {
		_ = p.cmd.Process.Kill()
		_ = p.cmd.Wait()
		windows.CloseHandle(job)
		return err
	}
	h, e := windows.OpenProcess(windows.PROCESS_SET_QUOTA|windows.PROCESS_TERMINATE, false, uint32(p.cmd.Process.Pid))
	if e != nil {
		return fail(e)
	}
	e = windows.AssignProcessToJobObject(job, h)
	windows.CloseHandle(h)
	if e != nil {
		return fail(e)
	}
	if e = resume(uint32(p.cmd.Process.Pid)); e != nil {
		return fail(e)
	}
	p.kill = func() { _ = windows.TerminateJobObject(job, 1) }
	p.release = func() { _ = windows.CloseHandle(job) }
	return nil
}
func resume(pid uint32) error {
	snapshot, e := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPTHREAD, 0)
	if e != nil {
		return e
	}
	defer windows.CloseHandle(snapshot)
	t := windows.ThreadEntry32{Size: uint32(unsafe.Sizeof(windows.ThreadEntry32{}))}
	for e = windows.Thread32First(snapshot, &t); e == nil; e = windows.Thread32Next(snapshot, &t) {
		if t.OwnerProcessID != pid {
			continue
		}
		h, e := windows.OpenThread(windows.THREAD_SUSPEND_RESUME, false, t.ThreadID)
		if e != nil {
			return e
		}
		defer windows.CloseHandle(h)
		_, e = windows.ResumeThread(h)
		return e
	}
	return fmt.Errorf("find suspended server thread: %w", e)
}
