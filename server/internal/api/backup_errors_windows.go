//go:build windows

package api

import (
	"errors"
	"syscall"
)

// Windows reports a full disk as ERROR_DISK_FULL or ERROR_HANDLE_DISK_FULL,
// which syscall.ENOSPC does not match.
const (
	errorHandleDiskFull syscall.Errno = 39
	errorDiskFull       syscall.Errno = 112
)

func diskFull(err error) bool {
	return errors.Is(err, errorDiskFull) || errors.Is(err, errorHandleDiskFull)
}
