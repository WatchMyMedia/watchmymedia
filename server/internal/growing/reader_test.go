package growing

import (
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"
	"testing"
	"testing/synctest"
)

func TestFollowsAppendAndRenameThenDrains(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		path := filepath.Join(t.TempDir(), ".recording.partial")
		if err := os.WriteFile(path, []byte("first"), 0600); err != nil {
			t.Fatal(err)
		}
		done := make(chan struct{})
		r, err := Open(t.Context(), path, 0, done)
		if err != nil {
			t.Fatal(err)
		}
		defer r.Close()
		result := make(chan string, 1)
		go func() {
			b, e := io.ReadAll(r)
			if e != nil {
				result <- e.Error()
				return
			}
			result <- string(b)
		}()
		synctest.Wait()
		select {
		case b := <-result:
			t.Fatalf("temporary EOF ended reader: %q", b)
		default:
		}
		out, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0600)
		if err != nil {
			t.Fatal(err)
		}
		if _, err = out.WriteString(" second"); err != nil {
			t.Fatal(err)
		}
		if err = out.Close(); err != nil {
			t.Fatal(err)
		}
		if err = os.Rename(path, filepath.Join(filepath.Dir(path), "final.ts")); err != nil {
			t.Fatal(err)
		}
		close(done)
		if got := <-result; got != "first second" {
			t.Fatalf("got %q", got)
		}
	})
}
func TestCancelAtTemporaryEOF(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "partial")
		if err := os.WriteFile(path, nil, 0600); err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithCancel(t.Context())
		r, err := Open(ctx, path, 0, make(chan struct{}))
		if err != nil {
			t.Fatal(err)
		}
		defer r.Close()
		result := make(chan error, 1)
		go func() { _, e := r.Read(make([]byte, 1)); result <- e }()
		synctest.Wait()
		cancel()
		if e := <-result; !errors.Is(e, context.Canceled) {
			t.Fatalf("got %v", e)
		}
	})
}
func TestPacketAlignedOffset(t *testing.T) {
	if got := Offset(10000, 8_000_000); got != 9_999_908 {
		t.Fatal(got)
	}
	if Offset(-1, 8_000_000) != 0 || Offset(1, 0) != 0 {
		t.Fatal("invalid estimate")
	}
}
func TestReadAtOffset(t *testing.T) {
	path := filepath.Join(t.TempDir(), "done")
	if err := os.WriteFile(path, []byte("abcdef"), 0600); err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	close(done)
	r, err := Open(t.Context(), path, 3, done)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	b, err := io.ReadAll(r)
	if err != nil || string(b) != "def" {
		t.Fatalf("%q %v", b, err)
	}
}

func TestGrowingSeekStaysBehindActualWrittenBytes(t *testing.T) {
	for _, tc := range []struct{ pos, elapsed, size int64 }{{59000, 60000, 188 * 20}, {999999, 60000, 12_000_000}, {30000, 60000, 12_000_000}, {5000, 6000, 1000000}, {-1, 60000, 1000000}} {
		got := SeekOffset(tc.pos, tc.elapsed, tc.size)
		if got < 0 || got >= tc.size || got%188 != 0 {
			t.Fatalf("%+v: %d", tc, got)
		}
	}
	if SeekOffset(10000, 6000, 1000000) != 0 {
		t.Fatal("a short recording must seek from the start")
	}
}
