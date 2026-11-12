// Package ffexec runs ffmpeg, ffprobe and our native adapter workers. Every
// process it starts dies with the server: in a process group of its own
// that is killed when its context ends, with the parent-death signal on
// Linux, in a job object that closes with the server on Windows, and on
// macOS, which has neither, through pid files that the next start reads
// (Track, KillLeftovers). ffmpeg ignores SIGPIPE, so a transcode left
// behind otherwise runs to the end of the file. Only the end of what a
// process writes to stderr is kept.
package ffexec

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// base is what every ffmpeg run starts with: no reading from stdin (ffmpeg
// takes keys from it otherwise), no banner, and errors only.
var base = []string{"-nostdin", "-hide_banner", "-loglevel", "error"}

// FFmpegArgs is ffmpeg's usual options followed by args.
func FFmpegArgs(args ...string) []string {
	out := make([]string, 0, len(base)+len(args))
	return append(append(out, base...), args...)
}

// Seconds is ms as ffmpeg's time options (-ss, -t, -itsoffset) take it.
func Seconds(ms int64) string {
	return strconv.FormatFloat(float64(ms)/1000, 'f', 3, 64)
}

// waitDelay is how long Wait waits for a killed process's output to close.
const waitDelay = 5 * time.Second

// Cmd is one ffmpeg or ffprobe process.
type Cmd struct {
	// Dir is the working directory; Stdout gets what the process writes
	// there (nothing is kept when it is nil).
	Dir    string
	Stdout io.Writer
	// Stdin feeds a single already-open input. Its owner must close any
	// blocking reader when the command context ends, before waiting.
	Stdin io.Reader
	// LowPriority runs the process below normal priority, under nice
	// outside Windows, and in the lowest I/O class where the OS has one
	// (ionice on Linux, taskpolicy on macOS), so it does not compete with
	// playback for the CPU or the disk. On macOS that is the background
	// policy (taskpolicy -b), which does more than wait its turn: the process
	// keeps to the efficiency cores and gets almost no CPU while every core
	// is busy at normal priority (a transcode that takes 0.2 s took 74 s on
	// a 10-core M5 with 14 busy loops; nice alone made no difference). It is
	// for jobs nobody waits for; see Awaited for the ones someone does.
	LowPriority bool
	// Awaited says someone is waiting for what a LowPriority process makes,
	// as for an offline download. It changes macOS only: the process runs
	// under the utility clamp with its disk reads in the throttled tier
	// (taskpolicy -c utility -d throttle), not the background policy. It
	// still gives way to playback, less completely: beside a libx264 play
	// running flat out on a 10-core M5 the play was 3.7% slower than alone
	// (2.4% beside a background process) and no slower to start, and the
	// process ran twice as fast as in the background. With every core busy at
	// normal priority it keeps a few frames a second where the background
	// policy gives it none, so what waits on it must still be able to give
	// up and ask again, as a download's requests do, and a test that waits
	// on one still slows with the machine's load.
	Awaited bool

	ctx  context.Context
	bin  string
	args []string
	cmd  *exec.Cmd
	tail Tail
}

// Command returns the command to run bin with args. When ctx ends, the
// process and anything it started are killed.
func Command(ctx context.Context, bin string, args ...string) *Cmd {
	return &Cmd{ctx: ctx, bin: bin, args: args}
}

func (c *Cmd) prepare() *exec.Cmd {
	if c.cmd != nil {
		return c.cmd
	}
	bin, args := c.bin, c.args
	if c.LowPriority {
		bin, args = lowPriority(bin, args, c.Awaited)
	}
	cmd := exec.CommandContext(c.ctx, bin, args...)
	cmd.Dir = c.Dir
	cmd.Stdout = c.Stdout
	cmd.Stdin = c.Stdin
	cmd.Stderr = &c.tail
	cmd.SysProcAttr = procAttr(c.LowPriority)
	cmd.Cancel = func() error { return kill(cmd.Process) }
	cmd.WaitDelay = waitDelay
	c.cmd = cmd
	return cmd
}

// StdoutPipe is exec.Cmd's StdoutPipe: read it to the end before Wait.
func (c *Cmd) StdoutPipe() (io.ReadCloser, error) { return c.prepare().StdoutPipe() }

// StdinPipe feeds a process without making Wait depend on the input reader.
// The caller must close/join its input copier after Wait or cancellation.
func (c *Cmd) StdinPipe() (io.WriteCloser, error) { return c.prepare().StdinPipe() }

// Start starts the process.
func (c *Cmd) Start() error {
	cmd := c.prepare()
	if err := cmd.Start(); err != nil {
		return err
	}
	started(cmd.Process)
	return nil
}

// Wait waits for the process to exit. An exit with an error status comes
// back as an *ExitError with the end of stderr.
func (c *Cmd) Wait() error {
	err := c.cmd.Wait()
	exited(c.cmd.Process)
	if _, ok := errors.AsType[*exec.ExitError](err); ok {
		return &ExitError{Name: name(c.bin), Err: err, Tail: c.tail.Last(exitTail)}
	}
	return err
}

// Run starts the process and waits for it.
func (c *Cmd) Run() error {
	if err := c.Start(); err != nil {
		return err
	}
	return c.Wait()
}

// Output runs the process and returns what it wrote to stdout.
func (c *Cmd) Output() ([]byte, error) {
	var out bytes.Buffer
	c.Stdout = &out
	err := c.Run()
	return out.Bytes(), err
}

// Tail is the end of what the process has written to stderr so far.
func (c *Cmd) Tail() string { return c.tail.String() }

// name is the program's name for messages: "ffmpeg", "ffprobe".
func name(bin string) string {
	return strings.TrimSuffix(filepath.Base(bin), ".exe")
}

// RanOnFile reports whether a process that failed got as far as reading
// its input, so that the failure says something about the file. It did not
// when it never started (no pipes or process to be had, no binary), when
// nice, ionice or taskpolicy could not start it (126 and 127, or
// taskpolicy's own message), or when the system was out of open files;
// stderr is what it wrote there.
func RanOnFile(err error, stderr string) bool {
	exitErr, ok := errors.AsType[*exec.ExitError](err)
	if !ok {
		return false
	}
	if code := exitErr.ExitCode(); code == 126 || code == 127 {
		return false
	}
	return !strings.Contains(stderr, "Too many open files") && !strings.HasPrefix(stderr, "taskpolicy: ")
}

// exitTail is how much of stderr an ExitError carries.
const exitTail = 2000

// ExitError is a process exiting with an error status.
type ExitError struct {
	Name string // "ffmpeg" or "ffprobe"
	Err  error  // the *exec.ExitError
	Tail string // the end of what it wrote to stderr
}

func (e *ExitError) Error() string {
	if e.Tail == "" {
		return fmt.Sprintf("%s exited: %v", e.Name, e.Err)
	}
	return fmt.Sprintf("%s exited: %v: %s", e.Name, e.Err, e.Tail)
}

func (e *ExitError) Unwrap() error { return e.Err }

// tailBytes is how much of stderr a Tail keeps.
const tailBytes = 4096

// Tail keeps the last few kilobytes written to it, so a process that
// writes without end costs nothing; it may be read while it is written.
type Tail struct {
	mu  sync.Mutex
	buf []byte
}

func (t *Tail) Write(p []byte) (int, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	n := len(p)
	if n >= tailBytes {
		t.buf = append(t.buf[:0], p[n-tailBytes:]...)
		return n, nil
	}
	if over := len(t.buf) + n - tailBytes; over > 0 {
		t.buf = t.buf[:copy(t.buf, t.buf[over:])]
	}
	t.buf = append(t.buf, p...)
	return n, nil
}

// String is everything kept, trimmed.
func (t *Tail) String() string { return t.Last(tailBytes) }

// Last is at most the last n bytes kept, trimmed.
func (t *Tail) Last(n int) string {
	t.mu.Lock()
	defer t.mu.Unlock()
	b := t.buf
	if len(b) > n {
		b = b[len(b)-n:]
	}
	return strings.TrimSpace(string(b))
}
