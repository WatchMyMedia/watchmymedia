// Package ffexectest helps tests that run a script in place of ffmpeg or
// ffprobe.
package ffexectest

import (
	"os"
	"syscall"
)

// WriteScript writes an executable script for a test to run.
//
// Linux refuses to run a file that any process has open for writing ("text
// file busy"). A process started while the script is open here keeps a copy
// of its descriptor until that process's exec, so with tests running in
// parallel the script could still be busy just after it was written and
// closed: on CI, "fork/exec .../ffmpeg: text file busy". Holding the fork
// lock while the file is open keeps this process from starting another
// meanwhile.
func WriteScript(path, script string) error {
	syscall.ForkLock.RLock()
	defer syscall.ForkLock.RUnlock()
	return os.WriteFile(path, []byte(script), 0o700) //nolint:gosec // G306: a script, which must be executable
}
