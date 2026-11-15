//go:build !windows

package api

// diskFull is true for the platform's own out-of-space errors beyond ENOSPC;
// elsewhere than Windows there are none.
func diskFull(error) bool { return false }
