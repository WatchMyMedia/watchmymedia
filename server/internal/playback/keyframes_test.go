package playback

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

func TestMatroskaKeyframes(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	ffprobe := filepath.Join(filepath.Dir(ffmpeg), "ffprobe")
	src := keyedSample(t, ffmpeg, 20, "0,1.7,3.1,6.9,7.4,12,15.5")
	f, err := os.Open(src)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	cues, err := matroskaKeyframes(f)
	if err != nil {
		t.Fatal(err)
	}
	want := []int64{0, 1708, 3125, 6917, 7417, 12000, 15500} // frame times at 24 fps, in ms
	if len(cues) != len(want) {
		t.Fatalf("cues = %v", cues)
	}
	for i, ns := range cues {
		if ns/1_000_000 != want[i] {
			t.Errorf("cue %d = %dns, want %dms", i, ns, want[i])
		}
	}
	// Through the cache type, with the start time applied, and from disk next time.
	dir := t.TempDir()
	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelWarn}))
	k := NewKeyframes(ffprobe, dir, log)
	st, _ := os.Stat(src)
	got, err := k.Get(context.Background(), src, st.Size(), st.ModTime().String())
	if err != nil || len(got) != len(want) || got[3] != 6917000 {
		t.Fatalf("keyframes = %v %v", got, err)
	}
	k2 := NewKeyframes("/nonexistent/ffprobe", dir, log)
	if again, err := k2.Get(context.Background(), src, st.Size(), st.ModTime().String()); err != nil || len(again) != len(want) {
		t.Errorf("cached keyframes = %v %v", again, err)
	}
	// With the start time from the stored probe, the Cues are read without ffprobe.
	if fresh, err := NewKeyframes("/nonexistent/ffprobe", "", log).GetFrom(context.Background(), src, st.Size(), "other", 0); err != nil || len(fresh) != len(want) || fresh[3] != 6917000 {
		t.Errorf("keyframes from the stored start = %v %v", fresh, err)
	}
	// Not Matroska: a background packet scan, then the answer.
	mp4 := filepath.Join(t.TempDir(), "keyed.mp4")
	if b, err := exec.Command(ffmpeg, "-v", "error", "-y", "-i", src, "-c", "copy", mp4).CombinedOutput(); err != nil {
		t.Fatalf("%v %s", err, b)
	}
	st, _ = os.Stat(mp4)
	if _, err := k.Get(context.Background(), mp4, st.Size(), st.ModTime().String()); !errors.Is(err, ErrKeyframesPending) {
		t.Fatalf("mp4 first ask -> %v", err)
	}
	deadline := time.Now().Add(20 * time.Second)
	for {
		got, err := k.Get(context.Background(), mp4, st.Size(), st.ModTime().String())
		if err == nil {
			if len(got) != len(want) || got[5] != 12000000 {
				t.Errorf("scanned keyframes = %v", got)
			}
			break
		}
		if !errors.Is(err, ErrKeyframesPending) || time.Now().After(deadline) {
			t.Fatalf("scan -> %v", err)
		}
		time.Sleep(50 * time.Millisecond)
	}
}

func TestCheckKeyframes(t *testing.T) {
	t.Parallel()
	if v, err := checkKeyframes([]int64{4e6, 0, 2e6, 2e6}, time.Minute); err != nil || len(v) != 3 || v[0] != 0 || v[2] != 4e6 {
		t.Errorf("sorted = %v %v", v, err)
	}
	if _, err := checkKeyframes([]int64{0, 30e6}, maxCueGap); err == nil {
		t.Error("a 30 s gap passed")
	}
	if _, err := checkKeyframes([]int64{0}, maxCueGap); err == nil {
		t.Error("a single keyframe passed")
	}
}
