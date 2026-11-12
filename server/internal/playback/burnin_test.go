package playback

import (
	"context"
	"encoding/binary"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

// The PGS box: white, 100×40 at (110, 120) in 320×180 video.
const boxX, boxY, boxW, boxH = 110, 120, 100, 40

// pgsSample renders 20 s of black 320×180 video and a PGS track whose one
// cue, the box, shows from 1 s to 9 s.
func pgsSample(t *testing.T, ffmpeg string) string {
	t.Helper()
	dir := t.TempDir()
	// A blank display set at 0 keeps the track's start there when muxed.
	sup := append(pgsClear(0, 0), pgsBox(1, 90_000)...)
	sup = append(sup, pgsClear(2, 9*90_000)...)
	supPath := filepath.Join(dir, "box.sup")
	if err := os.WriteFile(supPath, sup, 0o600); err != nil {
		t.Fatal(err)
	}
	out := filepath.Join(dir, "pgs.mkv")
	cmd := exec.Command(ffmpeg, "-v", "error", "-y",
		"-f", "lavfi", "-i", "color=c=black:s=320x180:r=24:d=20",
		"-f", "lavfi", "-i", "sine=frequency=440:duration=20",
		"-i", supPath,
		"-map", "0:v", "-map", "1:a", "-map", "2:s",
		"-c:v", "mpeg4", "-q:v", "4", "-g", "24", "-c:a", "aac", "-c:s", "copy", out)
	if b, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg pgs sample: %v\n%s", err, b)
	}
	return out
}

func pgsSegment(pts uint32, kind byte, data []byte) []byte {
	b := binary.BigEndian.AppendUint32([]byte("PG"), pts)
	b = binary.BigEndian.AppendUint32(b, 0)
	b = append(b, kind)
	b = binary.BigEndian.AppendUint16(b, uint16(len(data)))
	return append(b, data...)
}

// pgsComposition is a PCS: epoch start with the box's object, or a normal
// composition with none, which clears the screen.
func pgsComposition(n uint16, box bool) []byte {
	b := binary.BigEndian.AppendUint16(nil, 320)
	b = binary.BigEndian.AppendUint16(b, 180)
	b = append(b, 0x10)
	b = binary.BigEndian.AppendUint16(b, n)
	if !box {
		return append(b, 0x00, 0, 0, 0)
	}
	b = append(b, 0x80, 0, 0, 1)
	b = binary.BigEndian.AppendUint16(b, 0)
	b = append(b, 0, 0)
	b = binary.BigEndian.AppendUint16(b, boxX)
	return binary.BigEndian.AppendUint16(b, boxY)
}

func pgsWindow() []byte {
	b := []byte{1, 0}
	for _, v := range []uint16{boxX, boxY, boxW, boxH} {
		b = binary.BigEndian.AppendUint16(b, v)
	}
	return b
}

func pgsBox(n uint16, pts uint32) []byte {
	// Palette entry 1: white (Y, Cr, Cb, alpha).
	palette := []byte{0, 0, 1, 235, 128, 128, 255}
	var rle []byte
	for range boxH {
		rle = append(rle, 0, 0xC0|byte(boxW>>8), byte(boxW&0xFF), 1, 0, 0)
	}
	body := binary.BigEndian.AppendUint16(nil, boxW)
	body = binary.BigEndian.AppendUint16(body, boxH)
	body = append(body, rle...)
	object := []byte{0, 0, 0, 0xC0, byte(len(body) >> 16), byte(len(body) >> 8), byte(len(body))}
	b := pgsSegment(pts, 0x16, pgsComposition(n, true))
	b = append(b, pgsSegment(pts, 0x17, pgsWindow())...)
	b = append(b, pgsSegment(pts, 0x14, palette)...)
	b = append(b, pgsSegment(pts, 0x15, append(object, body...))...)
	return append(b, pgsSegment(pts, 0x80, nil)...)
}

func pgsClear(n uint16, pts uint32) []byte {
	b := pgsSegment(pts, 0x16, pgsComposition(n, false))
	b = append(b, pgsSegment(pts, 0x17, pgsWindow())...)
	return append(b, pgsSegment(pts, 0x80, nil)...)
}

// boxLuma is the mean luma inside the box on the first frame of file.
func boxLuma(t *testing.T, ffmpeg, file string) float64 {
	t.Helper()
	raw, err := exec.Command(ffmpeg, "-v", "error", "-i", file, "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "gray", "-").Output()
	if err != nil || len(raw) < 320*180 {
		t.Fatalf("decode %s: %v (%d bytes)", file, err, len(raw))
	}
	sum := 0
	for y := boxY + 5; y < boxY+boxH-5; y++ {
		for x := boxX + 5; x < boxX+boxW-5; x++ {
			sum += int(raw[y*320+x])
		}
	}
	return float64(sum) / float64((boxW-10)*(boxH-10))
}

// A run that starts inside an image subtitle's cue draws that cue from its
// first frame (a seek to the run's start skips the cue's only packet), and
// the video keeps its timing.
func TestImageBurnInDrawsTheCueOnScreenAtTheStart(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	src := pgsSample(t, ffmpeg)
	m := testManager(t, ffmpeg, "software")
	if !m.caps.Overlay {
		t.Skip("ffmpeg has no overlay filter")
	}
	ctx := context.Background()
	base := Spec{FileID: 1, PlayID: "p1", UserID: 1, Path: src, Mode: ModeTranscode, StartMs: 6000,
		Video: VideoPlan{Codec: "h264", Width: 320, Height: 180}, Audio: AudioPlan{Index: 1, Copy: true}, BurnIn: 2, BurnInImage: true}

	t.Run("vod", func(t *testing.T) {
		spec := base
		spec.VOD = &VODOptions{DurationMs: 20000, FrameRate: 24}
		s, err := m.Start(ctx, spec)
		if err != nil {
			t.Fatal(err)
		}
		defer m.Stop(s.ID)
		init, _, err := s.VODFile(ctx, VODInit)
		if err != nil {
			t.Fatal(err)
		}
		seg, _, err := s.VODFile(ctx, "v00001.m4s")
		if err != nil {
			t.Fatal(err)
		}
		one := filepath.Join(t.TempDir(), "one.mp4")
		if err := os.WriteFile(one, append(append([]byte{}, init...), seg...), 0o600); err != nil {
			t.Fatal(err)
		}
		if l := boxLuma(t, ffmpeg, one); l < 128 {
			t.Errorf("no box on the run's first frame (luma %.0f)", l)
		}
		pts, keys := packets(t, ffmpeg, one, "v:0")
		if len(pts) == 0 || !keys[0] || math.Abs(pts[0]-vodTimeOffset-6) > 0.002 {
			t.Errorf("segment 1 starts at %v (key %v)", pts[:min(len(pts), 3)], keys[:min(len(keys), 1)])
		}
	})

	t.Run("event", func(t *testing.T) {
		first := func(spec Spec) (string, float64) {
			s, err := m.Start(ctx, spec)
			if err != nil {
				t.Fatal(err)
			}
			<-s.done
			if s.Err() != nil {
				t.Fatal(s.Err())
			}
			init, _ := os.ReadFile(filepath.Join(s.Dir, "init.mp4"))
			seg, _ := os.ReadFile(filepath.Join(s.Dir, "seg00000.m4s"))
			one := filepath.Join(t.TempDir(), "one.mp4")
			if err := os.WriteFile(one, append(init, seg...), 0o600); err != nil { //nolint:gosec // G703: both parts are this test's own files
				t.Fatal(err)
			}
			pts, _ := packets(t, ffmpeg, one, "v:0")
			if len(pts) == 0 {
				t.Fatal("no video in the first segment")
			}
			return one, pts[0]
		}
		one, start := first(base)
		if l := boxLuma(t, ffmpeg, one); l < 128 {
			t.Errorf("no box on the run's first frame (luma %.0f)", l)
		}
		plain := base
		plain.BurnIn, plain.BurnInImage = -1, false
		if _, want := first(plain); math.Abs(start-want) > 0.002 {
			t.Errorf("burn-in moved the video: starts at %.3f, %.3f without", start, want)
		}
	})
}
