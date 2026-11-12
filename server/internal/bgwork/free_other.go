//go:build !windows

package bgwork

import "golang.org/x/sys/unix"

// FreeBytes is the space free to the server on the disk that holds dir.
func FreeBytes(dir string) (uint64, error) {
	var st unix.Statfs_t
	if err := unix.Statfs(dir, &st); err != nil {
		return 0, err
	}
	return uint64(st.Bavail) * uint64(st.Bsize), nil //nolint:gosec,unconvert // G115: block counts and sizes are not negative; the types differ between systems
}
