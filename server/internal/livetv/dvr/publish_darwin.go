package dvr

import unixsys "golang.org/x/sys/unix"

// Rename without replacing a destination created while the capture ran.
// Unlike link/unlink this also works on removable filesystems without links.
func publishFile(partial, final string) error {
	err := unixsys.RenameatxNp(unixsys.AT_FDCWD, partial, unixsys.AT_FDCWD, final, unixsys.RENAME_EXCL)
	if refusesNoReplace(err) {
		// SMB and NFS mounts can refuse the exclusive rename itself.
		return linkPublish(partial, final)
	}
	return err
}
