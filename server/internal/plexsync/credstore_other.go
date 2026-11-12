//go:build !windows

package plexsync

import "os"

// securePath leaves path to the account the server runs as: 0700 for the
// folder, 0600 for a file, tightened if it was made wider.
func securePath(path string, dir bool) error {
	if dir {
		//nolint:gosec // G302: a folder, which needs its x bit; 0700 is its owner alone.
		return os.Chmod(path, 0o700)
	}
	return os.Chmod(path, 0o600)
}
