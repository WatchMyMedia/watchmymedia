//go:build darwin || linux

package dvr

import (
	"errors"
	"os"

	unixsys "golang.org/x/sys/unix"
)

// refusesNoReplace is a file system that can't do a no-replace rename at
// all, as opposed to one that refused because the name is taken.
func refusesNoReplace(err error) bool {
	return errors.Is(err, unixsys.EINVAL) || errors.Is(err, unixsys.ENOSYS) || errors.Is(err, unixsys.ENOTSUP) || errors.Is(err, unixsys.EOPNOTSUPP)
}

// linkPublish publishes where the no-replace rename isn't supported. A
// hard link fails if the name is taken, so it never replaces a file; then
// the hidden name goes. Without hard links (FAT, exFAT, some SMB servers)
// it renames only after finding the name free, which leaves a moment in
// which a file created there could be replaced: the best those allow.
func linkPublish(partial, final string) error {
	err := os.Link(partial, final)
	if err == nil {
		// Published. A hidden name left behind is skipped by the scanner.
		_ = os.Remove(partial)
		return nil
	}
	if errors.Is(err, os.ErrExist) {
		return err
	}
	if _, e := os.Lstat(final); e == nil {
		return os.ErrExist
	} else if !errors.Is(e, os.ErrNotExist) {
		return e
	}
	return os.Rename(partial, final)
}
