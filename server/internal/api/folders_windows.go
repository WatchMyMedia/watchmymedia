package api

import (
	"io/fs"
	"os"
	"path/filepath"
	"syscall"

	"golang.org/x/sys/windows"
)

// folderRoots are where the folder browser starts on Windows: every drive
// that is ready (an empty card reader or disc drive is left out), then the
// home and Videos folders. Network shares are typed as \\server\share.
func folderRoots() []BrowseEntry {
	var roots []BrowseEntry
	drives, _ := windows.GetLogicalDrives()
	for i := range 26 {
		if drives&(1<<i) == 0 {
			continue
		}
		d := string(rune('A'+i)) + `:\`
		if _, err := os.Stat(d); err == nil {
			roots = append(roots, BrowseEntry{Name: d, Path: d})
		}
	}
	if home, err := os.UserHomeDir(); err == nil {
		roots = append(roots, BrowseEntry{Name: home, Path: home})
		videos := filepath.Join(home, "Videos")
		if st, err := os.Stat(videos); err == nil && st.IsDir() {
			roots = append(roots, BrowseEntry{Name: videos, Path: videos})
		}
	}
	return roots
}

// hiddenFolder reports the folders Explorer hides: anything marked hidden
// or system, which covers $RECYCLE.BIN and System Volume Information.
func hiddenFolder(e fs.DirEntry) bool {
	info, err := e.Info()
	if err != nil {
		return false
	}
	a, ok := info.Sys().(*syscall.Win32FileAttributeData)
	return ok && a.FileAttributes&(syscall.FILE_ATTRIBUTE_HIDDEN|syscall.FILE_ATTRIBUTE_SYSTEM) != 0
}
