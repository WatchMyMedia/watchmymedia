package api

import (
	"bytes"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"testing"

	"watchmymedia/server/internal/activity"
)

// connWriter stands in for net/http's response: it can ReadFrom, and keeps
// what each ReadFrom call was handed.
type connWriter struct {
	header  http.Header
	body    bytes.Buffer
	sources []io.Reader
	after   func() // runs after each ReadFrom
}

func (w *connWriter) Header() http.Header {
	if w.header == nil {
		w.header = http.Header{}
	}
	return w.header
}
func (w *connWriter) WriteHeader(int)             {}
func (w *connWriter) Write(b []byte) (int, error) { return w.body.Write(b) }
func (w *connWriter) ReadFrom(r io.Reader) (int64, error) {
	w.sources = append(w.sources, r)
	n, err := io.Copy(writerOnly{&w.body}, r)
	if w.after != nil {
		w.after()
	}
	return n, err
}

func countingSetup(t *testing.T, size int) (*activity.Tracker, string, *os.File) {
	t.Helper()
	tracker := activity.New(nil, nil, nil, activity.Options{})
	play, err := tracker.Begin(t.Context(), activity.Start{UserID: 1, FileID: 1, DurationMs: 1000})
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "video.mkv")
	if err := os.WriteFile(path, bytes.Repeat([]byte{7}, size), 0o600); err != nil {
		t.Fatal(err)
	}
	f, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = f.Close() })
	return tracker, play, f
}

func sentBytes(t *testing.T, tracker *activity.Tracker, play string) int64 {
	t.Helper()
	plays, _ := tracker.Live(activity.Viewer{UserID: 1, Admin: true})
	for _, p := range plays {
		if p.ID == play {
			return p.BytesSent
		}
	}
	t.Fatalf("play %s not live", play)
	return 0
}

// ServeContent's copy reaches the connection a chunk at a time, with the
// file one io.LimitedReader down so sendfile still finds it, and every
// byte is counted.
func TestCountingWriterReadFrom(t *testing.T) {
	t.Parallel()
	size := 2*readFromChunk + 1000
	tracker, play, f := countingSetup(t, size)
	conn := &connWriter{}
	w, done := countBytes(Deps{Activity: tracker}, conn, play)
	if _, ok := w.(io.ReaderFrom); !ok {
		t.Fatal("the counting writer hides ReadFrom")
	}
	if u, ok := w.(interface{ Unwrap() http.ResponseWriter }); !ok || u.Unwrap() != conn {
		t.Fatal("the counting writer does not unwrap to the connection")
	}
	src := &io.LimitedReader{R: f, N: int64(size - 10)}
	n, err := io.Copy(w, src)
	done()
	if err != nil || n != int64(size-10) || conn.body.Len() != size-10 || src.N != 0 {
		t.Fatalf("copied %d (%v), body %d, left %d; want %d", n, err, conn.body.Len(), src.N, size-10)
	}
	if len(conn.sources) != 3 {
		t.Errorf("%d ReadFrom calls, want 3 chunks", len(conn.sources))
	}
	for _, r := range conn.sources {
		if lr, ok := r.(*io.LimitedReader); !ok || lr.R != f {
			t.Errorf("chunk source %T, want an io.LimitedReader over the file", r)
		}
	}
	if got := sentBytes(t, tracker, play); got != int64(size-10) {
		t.Errorf("counted %d bytes, want %d", got, size-10)
	}
}

// An admin stopping the play stops the copy at the end of the chunk.
func TestCountingWriterStopsAStoppedPlay(t *testing.T) {
	t.Parallel()
	tracker, play, f := countingSetup(t, 3*readFromChunk)
	conn := &connWriter{}
	conn.after = func() { _ = tracker.Terminate(t.Context(), play, "bedtime") }
	w, _ := countBytes(Deps{Activity: tracker}, conn, play)
	n, err := io.Copy(w, f)
	if !errors.Is(err, errPlayStopped) || n != readFromChunk {
		t.Fatalf("copied %d (%v), want one chunk then errPlayStopped", n, err)
	}
	if _, err := w.Write([]byte("more")); !errors.Is(err, errPlayStopped) {
		t.Errorf("write after stop: %v", err)
	}
}

// Without ReadFrom underneath, the copy goes through Write and is counted.
func TestCountingWriterPlainWriter(t *testing.T) {
	t.Parallel()
	tracker, play, f := countingSetup(t, 300<<10)
	rec := &plainWriter{}
	w, done := countBytes(Deps{Activity: tracker}, rec, play)
	n, err := io.Copy(w, f)
	done()
	if err != nil || n != 300<<10 || rec.Len() != 300<<10 || sentBytes(t, tracker, play) != 300<<10 {
		t.Fatalf("copied %d (%v), body %d, counted %d", n, err, rec.Len(), sentBytes(t, tracker, play))
	}
}

type plainWriter struct{ bytes.Buffer }

func (w *plainWriter) Header() http.Header { return http.Header{} }
func (w *plainWriter) WriteHeader(int)     {}
