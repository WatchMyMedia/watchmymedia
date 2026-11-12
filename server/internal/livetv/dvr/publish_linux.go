package dvr

import unixsys "golang.org/x/sys/unix"

func publishFile(partial, final string) error {
	err := unixsys.Renameat2(unixsys.AT_FDCWD, partial, unixsys.AT_FDCWD, final, unixsys.RENAME_NOREPLACE)
	if refusesNoReplace(err) {
		// NFS, SMB/CIFS and some FUSE mounts refuse the flag itself.
		return linkPublish(partial, final)
	}
	return err
}
