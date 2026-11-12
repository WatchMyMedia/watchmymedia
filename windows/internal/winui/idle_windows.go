package winui

import (
	"fmt"
	"golang.org/x/sys/windows"
	"os"
	"path/filepath"
	"strings"
	"unsafe"
)

// Installation is per machine but each user's app has its own mutex. Do not
// replace/remove files while another signed-in user is still using this install.
func CheckInstallIdle() error {
	exe, e := os.Executable()
	if e != nil {
		return e
	}
	dir := filepath.Dir(exe)
	snapshot, e := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPPROCESS, 0)
	if e != nil {
		return e
	}
	defer windows.CloseHandle(snapshot)
	entry := windows.ProcessEntry32{Size: uint32(unsafe.Sizeof(windows.ProcessEntry32{}))}
	for e = windows.Process32First(snapshot, &entry); e == nil; e = windows.Process32Next(snapshot, &entry) {
		if entry.ProcessID == uint32(os.Getpid()) {
			continue
		}
		name := windows.UTF16ToString(entry.ExeFile[:])
		if !strings.EqualFold(name, "WatchMyMedia.exe") && !strings.EqualFold(name, "watchmymedia-server.exe") {
			continue
		}
		h, e := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, entry.ProcessID)
		if e == windows.ERROR_INVALID_PARAMETER {
			continue
		} // exited during enumeration
		if e != nil {
			return fmt.Errorf("cannot check a running WatchMyMedia process: %w", e)
		}
		buffer := make([]uint16, 32768)
		n := uint32(len(buffer))
		e = windows.QueryFullProcessImageName(h, 0, &buffer[0], &n)
		windows.CloseHandle(h)
		if e != nil {
			return fmt.Errorf("cannot check a running WatchMyMedia path: %w", e)
		}
		if strings.EqualFold(filepath.Dir(windows.UTF16ToString(buffer[:n])), dir) {
			return fmt.Errorf("WatchMyMedia is still running in another signed-in session; quit it there before continuing")
		}
	}
	if e != windows.ERROR_NO_MORE_FILES {
		return e
	}
	return nil
}
