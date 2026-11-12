package ffexec

import (
	"context"
	"errors"
	"os/exec"
	"strings"
	"testing"
)

func TestTailKeepsTheEnd(t *testing.T) {
	t.Parallel()
	var tail Tail
	for i := range 3000 {
		_, _ = tail.Write([]byte{byte('a' + i%26)})
	}
	if got := tail.String(); len(got) != 3000 {
		t.Fatalf("kept %d bytes of 3000", len(got))
	}
	_, _ = tail.Write([]byte(strings.Repeat("x", 3000) + "end"))
	got := tail.String()
	if len(got) != tailBytes || !strings.HasSuffix(got, "xend") {
		t.Errorf("kept %d bytes ending %q", len(got), got[len(got)-4:])
	}
	_, _ = tail.Write([]byte(strings.Repeat("y", 2*tailBytes)))
	if got := tail.Last(10); got != strings.Repeat("y", 10) {
		t.Errorf("last 10 = %q", got)
	}
}

func TestArgs(t *testing.T) {
	t.Parallel()
	if got := strings.Join(FFmpegArgs("-i", "x"), " "); got != "-nostdin -hide_banner -loglevel error -i x" {
		t.Errorf("args = %s", got)
	}
	for ms, want := range map[int64]string{0: "0.000", 1500: "1.500", 36000: "36.000", 7: "0.007"} {
		if got := Seconds(ms); got != want {
			t.Errorf("Seconds(%d) = %s, want %s", ms, got, want)
		}
	}
}

func TestExitError(t *testing.T) {
	t.Parallel()
	sh, err := exec.LookPath("sh")
	if err != nil {
		t.Skip("no sh")
	}
	cmd := Command(context.Background(), sh, "-c", "echo 'first line' >&2; echo 'bad input' >&2; exit 3")
	err = cmd.Run()
	var exit *ExitError
	if !errors.As(err, &exit) || exit.Tail != "first line\nbad input" || exit.Name != "sh" {
		t.Fatalf("err = %#v", err)
	}
	var execErr *exec.ExitError
	if !errors.As(err, &execErr) || execErr.ExitCode() != 3 {
		t.Errorf("exit code not reachable through %v", err)
	}
	if !strings.Contains(err.Error(), "sh exited: exit status 3: first line") {
		t.Errorf("message = %q", err)
	}
	// A program that does not start is not an exit.
	if err := Command(context.Background(), "/nonexistent/ffmpeg").Run(); err == nil || errors.As(err, &exit) {
		t.Errorf("missing program: %v", err)
	}
}
