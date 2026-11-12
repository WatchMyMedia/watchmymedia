package dvr

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/growing"
)

func TestLateCollisionPublishesBesideTheOtherFile(t *testing.T) {
	t.Parallel()
	chunks := make(chan []byte)
	f := setup(t, func(w http.ResponseWriter, r *http.Request) {
		for {
			select {
			case <-r.Context().Done():
				return
			case b := <-chunks:
				_, _ = w.Write(b)
				_ = http.NewResponseController(w).Flush()
			}
		}
	})
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
	defer cancel()
	start := unix(f.clock.Load())
	rec, err := f.s.Manual(ctx, f.actor, Manual{ChannelID: f.channels[0].ID, Start: start, End: start.Add(time.Minute), Repeat: "once", Zone: "UTC", Name: "Collision report"})
	if err != nil {
		t.Fatal(err)
	}
	f.s.tick(ctx, start)
	src, err := f.s.Source(ctx, rec.ID)
	if err != nil {
		t.Fatal(err)
	}
	reader, err := growing.Open(ctx, src.Path, 0, src.Done)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	packet := bytes.Repeat([]byte{0x47}, 188)
	select {
	case chunks <- packet:
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	got := make([]byte, len(packet))
	if _, err = io.ReadFull(reader, got); err != nil {
		t.Fatal(err)
	}
	raw, err := f.s.get(ctx, rec.ID)
	if err != nil {
		t.Fatal(err)
	}
	foreign := bytes.Repeat([]byte{'x'}, len(packet))
	if err = os.WriteFile(raw.Path, foreign, 0o600); err != nil {
		t.Fatal(err)
	}
	foreignTime := time.Unix(1_700_000_000, 0)
	if err = os.Chtimes(raw.Path, foreignTime, foreignTime); err != nil {
		t.Fatal(err)
	}
	f.clock.Store(start.Add(2 * time.Minute).Unix())
	f.s.tick(ctx, unix(f.clock.Load()))
	// A file that took the final name while this recorded stays the other
	// file's: the recording is published beside it under the next free name
	// (§1), instead of waiting hidden for a name that never frees.
	published, err := f.s.get(ctx, rec.ID)
	want := strings.TrimSuffix(filepath.Base(raw.Path), ".ts") + " (2).ts"
	if err != nil || published.Reason == "finish_pending" || filepath.Base(published.Path) != want {
		t.Fatal(published, err)
	}
	assertForeignUntouched := func() {
		t.Helper()
		b, err := os.ReadFile(raw.Path)
		if err != nil || !bytes.Equal(b, foreign) {
			t.Fatal("foreign file changed", err)
		}
	}
	assertForeignUntouched()
	source, err := f.s.Source(ctx, rec.ID)
	if err != nil || source.Path != published.Path {
		t.Fatal(source, err)
	}
	if b, err := os.ReadFile(source.Path); err != nil || !bytes.Equal(b, packet) {
		t.Fatal("published the wrong bytes", err)
	}
	f.s.Close()
	restarted := New(f.q, f.s.store, f.live, nil, f.s.opts)
	t.Cleanup(restarted.Close)
	restarted.tick(ctx, unix(f.clock.Load()))
	assertForeignUntouched()
	// Deleting the recording removes its own file, never the other one.
	if err = restarted.DeleteRecording(ctx, rec.ID, false); err != nil {
		t.Fatal(err)
	}
	if _, err = os.Stat(published.Path); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("kept the recording's own file", err)
	}
	assertForeignUntouched()
}

func TestCrashAfterAtomicPublishReconcilesOnlyTheSealedFingerprint(t *testing.T) {
	for _, matching := range []bool{true, false} {
		t.Run(map[bool]string{true: "own final", false: "foreign final"}[matching], func(t *testing.T) {
			f := setup(t, nil)
			ctx := t.Context()
			now := unix(f.clock.Load())
			path := filepath.Join(f.lib.Path, "Published.ts")
			partialPath := filepath.Join(f.lib.Path, ".Published.ts.partial")
			own := []byte("owned-broadcast")
			if err := os.WriteFile(partialPath, own, 0o600); err != nil {
				t.Fatal(err)
			}
			info, err := os.Stat(partialPath)
			if err != nil {
				t.Fatal(err)
			}
			r := record{ID: "publish-crash", UserID: &f.actor.UserID, State: "recording", Title: "Published", Start: now.Add(-time.Hour), End: now, Bytes: info.Size(), Gaps: []Gap{}, LibraryID: f.lib.ID, Path: path, PartialPath: partialPath, FileMtimeNano: info.ModTime().UnixNano(), CreatedAt: now.Unix()}
			// publishSealed commits finish_pending and the writer fingerprint before
			// rename. Deliberately omit the final state write to simulate the crash.
			if err = f.s.publishSealed(ctx, &r); err != nil {
				t.Fatal(err)
			}
			if !matching {
				if err = os.WriteFile(path, bytes.Repeat([]byte{'x'}, len(own)), 0o600); err != nil {
					t.Fatal(err)
				}
				stamp := time.Unix(1_700_000_000, 0)
				if err = os.Chtimes(path, stamp, stamp); err != nil {
					t.Fatal(err)
				}
			}
			f.s.Close()
			restarted := New(f.q, f.s.store, f.live, nil, f.s.opts)
			t.Cleanup(restarted.Close)
			restarted.tick(ctx, now)
			source, err := restarted.Source(ctx, r.ID)
			if matching {
				if err != nil || source.Path != path {
					t.Fatal(source, err)
				}
				body, err := os.ReadFile(source.Path)
				if err != nil || !bytes.Equal(body, own) {
					t.Fatal(string(body), err)
				}
			} else {
				if !errors.Is(err, ErrUnavailable) {
					t.Fatal("adopted a foreign final after crash", err)
				}
				durable, e := restarted.get(ctx, r.ID)
				if e != nil {
					t.Fatal(e)
				}
				restarted.finishRecovered(ctx, durable)
				if err = restarted.DeleteRecording(ctx, r.ID, false); !errors.Is(err, ErrInvalid) {
					t.Fatal("removed a foreign final after crash", err)
				}
				body, err := os.ReadFile(path)
				if err != nil || !bytes.Equal(body, bytes.Repeat([]byte{'x'}, len(own))) {
					t.Fatal("foreign final changed", err)
				}
			}
		})
	}
}
