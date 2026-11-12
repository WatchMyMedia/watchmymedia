//go:build !windows

package api

import (
	"io/fs"
	"os"
)

// folderRoots are where the folder browser starts: the root, the usual
// mount points and Docker volume paths that exist, and the home folder.
func folderRoots() []BrowseEntry {
	roots := []BrowseEntry{{Name: "/", Path: "/"}}
	for _, extra := range []string{"/Volumes", "/mnt", "/media", "/movies", "/tv", "/data"} {
		if st, err := os.Stat(extra); err == nil && st.IsDir() {
			roots = append(roots, BrowseEntry{Name: extra, Path: extra})
		}
	}
	if home, err := os.UserHomeDir(); err == nil {
		roots = append(roots, BrowseEntry{Name: home, Path: home})
	}
	return roots
}

// hiddenFolder reports folders to leave out beyond dot-folders; there are
// none outside Windows.
func hiddenFolder(fs.DirEntry) bool { return false }
