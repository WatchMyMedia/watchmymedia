//go:build !darwin && !linux && !windows

package dvr

import "os"

func publishFile(partial, final string) error {
	if err := os.Link(partial, final); err != nil {
		return err
	}
	return os.Remove(partial)
}
