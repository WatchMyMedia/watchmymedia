package playback

import (
	"os"
	"syscall"

	"watchmymedia/server/internal/winpath"
)

// OpenShared permits renaming away/deleting an immutable file while it is read.
// This does not permit MoveFileEx to replace an open destination; mutable
// playlists must first be claimed through openPlaylist.
func OpenShared(path string) (*os.File, error) {
	native, err := sharedPath(path)
	if err != nil {
		return nil, &os.PathError{Op: "open", Path: path, Err: err}
	}
	p, err := syscall.UTF16PtrFromString(native)
	if err != nil {
		return nil, &os.PathError{Op: "open", Path: path, Err: err}
	}
	h, err := syscall.CreateFile(p, syscall.GENERIC_READ,
		syscall.FILE_SHARE_READ|syscall.FILE_SHARE_WRITE|syscall.FILE_SHARE_DELETE,
		nil, syscall.OPEN_EXISTING, syscall.FILE_ATTRIBUTE_NORMAL, 0)
	if err != nil {
		return nil, &os.PathError{Op: "open", Path: path, Err: err}
	}
	return os.NewFile(uintptr(h), path), nil
}

// os.Open adds extended prefixes for long paths, but raw CreateFile does not.
// Prefix a cleaned absolute drive/UNC path without changing short-path rules.
func sharedPath(path string) (string, error) {
	return winpath.Extended(path)
}

// Match Live TV's claim-before-open approach. A unique destination allows
// independent readers and leaves FFmpeg's publish destination unopened.
func openPlaylist(path string) (playlistFile, error) {
	snapshot := path + ".snapshot-" + newID()
	if err := os.Rename(path, snapshot); err != nil {
		return nil, err
	}
	f, err := OpenShared(snapshot)
	if err != nil {
		_ = os.Remove(snapshot)
		return nil, err
	}
	return &playlistSnapshot{File: f, path: snapshot}, nil
}

type playlistSnapshot struct {
	*os.File
	path string
}

func (f *playlistSnapshot) Close() error {
	err := f.File.Close()
	removeErr := os.Remove(f.path)
	if err != nil {
		return err
	}
	return removeErr
}
