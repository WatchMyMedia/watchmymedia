package winui

import (
	"fmt"
	"golang.org/x/sys/windows"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"time"
	"unsafe"
)

// DesktopAction runs only our own executable under the desktop shell's token.
// An installer elevated with another account must not use its own HKCU/AppData.
// Fail closed if no interactive shell/token exists; never start the server elevated.
func DesktopAction(action string, startAtLogin bool) error {
	exe, e := os.Executable()
	if e != nil {
		return e
	}
	args := []string{exe, "-no-browser"}
	switch action {
	case "start":
		args = []string{exe}
		if startAtLogin {
			args = append(args, "-enable-login")
		}
	case "startup":
		args = append(args, "-configure-login")
		if startAtLogin {
			args = append(args, "-enable-login")
		} else {
			args = append(args, "-disable-login")
		}
	case "stop":
		args = append(args, "-quit")
	case "uninstall":
		args = append(args, "-disable-login", "-quit")
	case "remove-data":
		args = append(args, "-disable-login", "-quit", "-remove-data")
	default:
		return fmt.Errorf("unknown desktop action %q", action)
	}
	shell := call(user32.NewProc("GetShellWindow"))
	if shell == 0 {
		return fmt.Errorf("no signed-in Windows desktop; start WatchMyMedia from its Start menu shortcut")
	}
	var pid uint32
	user32.NewProc("GetWindowThreadProcessId").Call(shell, uintptr(unsafe.Pointer(&pid)))
	process, e := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, pid)
	if e != nil {
		return e
	}
	defer windows.CloseHandle(process)
	var token windows.Token
	if e = windows.OpenProcessToken(process, windows.TOKEN_QUERY|windows.TOKEN_DUPLICATE|windows.TOKEN_ASSIGN_PRIMARY, &token); e != nil {
		return e
	}
	defer token.Close()
	if token.IsElevated() && action == "start" {
		return fmt.Errorf("the Windows desktop is elevated; refusing to start an administrator server")
	}
	// When the caller already is the desktop user, no token transition is needed
	// for stop/configuration. This also supports administration on hosted CI.
	currentUser, e := windows.GetCurrentProcessToken().GetTokenUser()
	if e != nil {
		return e
	}
	desktopUser, e := token.GetTokenUser()
	if e != nil {
		return e
	}
	if currentUser.User.Sid.String() == desktopUser.User.Sid.String() && action != "start" {
		cmd := exec.Command(exe, args[1:]...)
		cmd.SysProcAttr = &syscall.SysProcAttr{CreationFlags: windows.CREATE_NO_WINDOW}
		return cmd.Run()
	}
	var primary windows.Token
	if e = windows.DuplicateTokenEx(token, windows.TOKEN_ALL_ACCESS, nil, windows.SecurityImpersonation, windows.TokenPrimary, &primary); e != nil {
		return e
	}
	defer primary.Close()
	var environment *uint16
	if e = windows.CreateEnvironmentBlock(&environment, primary, false); e != nil {
		return e
	}
	defer windows.DestroyEnvironmentBlock(environment)
	si := windows.StartupInfo{Desktop: u16(`winsta0\default`)}
	si.Cb = uint32(unsafe.Sizeof(si))
	var pi windows.ProcessInformation
	command := windows.ComposeCommandLine(args)
	r, _, e := windows.NewLazySystemDLL("advapi32.dll").NewProc("CreateProcessWithTokenW").Call(uintptr(primary), 1, uintptr(unsafe.Pointer(u16(exe))), uintptr(unsafe.Pointer(u16(command))), windows.CREATE_UNICODE_ENVIRONMENT, uintptr(unsafe.Pointer(environment)), uintptr(unsafe.Pointer(u16(filepath.Dir(exe)))), uintptr(unsafe.Pointer(&si)), uintptr(unsafe.Pointer(&pi)))
	if r == 0 {
		return fmt.Errorf("launch as desktop user: %w", e)
	}
	windows.CloseHandle(pi.Thread)
	defer windows.CloseHandle(pi.Process)
	if action != "start" {
		result, e := windows.WaitForSingleObject(pi.Process, 15000)
		if e != nil {
			return e
		}
		if result == uint32(windows.WAIT_TIMEOUT) {
			return fmt.Errorf("desktop action timed out")
		}
		var code uint32
		if e = windows.GetExitCodeProcess(pi.Process, &code); e != nil {
			return e
		}
		if code != 0 {
			return fmt.Errorf("desktop action failed (exit %d)", code)
		}
	}
	return nil
}

// Wait for the instance handle to disappear, not just for the quit signal to
// be accepted. The installer can then replace the executable without a race.
func waitForQuit() error {
	name, e := objectName("instance")
	if e != nil {
		return e
	}
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		h, e := windows.OpenMutex(windows.SYNCHRONIZE, false, name)
		if e == windows.ERROR_FILE_NOT_FOUND {
			return nil
		}
		if e != nil {
			return e
		}
		windows.CloseHandle(h)
		time.Sleep(50 * time.Millisecond)
	}
	return fmt.Errorf("WatchMyMedia did not finish shutting down")
}

func ConfigureLogin(on bool) error { return setLogin(on) }
