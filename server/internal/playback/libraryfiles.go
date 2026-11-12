package playback

import (
	"errors"
	"os"
	"path/filepath"
)

var ErrOutsideLibrary = errors.New("file is outside its library")

func libraryRelative(root, path string) (string, error) {
	rel, err := filepath.Rel(root, path)
	if err != nil || !filepath.IsLocal(rel) {
		return "", ErrOutsideLibrary
	}
	return rel, nil
}

// OpenLibraryFile resolves links under the opened directory, including during
// concurrent renames. It cannot return a file outside the configured library.
func OpenLibraryFile(root, path string) (*os.File, error) {
	rel, err := libraryRelative(root, path)
	if err != nil {
		return nil, err
	}
	dir, err := os.OpenRoot(root)
	if err != nil {
		return nil, err
	}
	defer func() { _ = dir.Close() }()
	f, err := dir.Open(rel)
	if err != nil {
		return nil, err
	}
	st, err := f.Stat()
	if err != nil || !st.Mode().IsRegular() {
		_ = f.Close()
		return nil, errors.New("media is not a regular file")
	}
	return f, nil
}

// ValidateLibraryPath rejects stale database paths and links escaping the
// library before invoking a media parser. Missing files fail when opened.
func ValidateLibraryPath(root, path string) error {
	if _, err := libraryRelative(root, path); err != nil {
		return err
	}
	realRoot, err := filepath.EvalSymlinks(root)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	realPath, err := filepath.EvalSymlinks(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	_, err = libraryRelative(realRoot, realPath)
	return err
}
