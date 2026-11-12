package dvr

import (
	"golang.org/x/sys/windows"
	"watchmymedia/server/internal/winpath"
)

func publishFile(partial, final string) error {
	source, err := winpath.Extended(partial)
	if err != nil {
		return err
	}
	destination, err := winpath.Extended(final)
	if err != nil {
		return err
	}
	src, err := windows.UTF16PtrFromString(source)
	if err != nil {
		return err
	}
	dst, err := windows.UTF16PtrFromString(destination)
	if err != nil {
		return err
	}
	// No MOVEFILE_REPLACE_EXISTING: recording never owns a late-created file.
	return windows.MoveFileEx(src, dst, 0)
}
