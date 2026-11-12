package trickplay

import (
	"bytes"
	"encoding/binary"
	"image"
	"image/color"
	"image/jpeg"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/playback"
)

// writeSheets puts a version's sheets in dir, as the job leaves them:
// tiles of w × h, columns × rows to a sheet, tile i a flat grey of
// grey(i), black after the last.
func writeSheets(t *testing.T, dir string, tr db.Trickplay, grey func(i int) uint8) int64 {
	t.Helper()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	var total int64
	w, h, per := int(tr.Width), int(tr.Height), int(tr.Columns*tr.Rows)
	for s := range int(tr.Sheets) {
		img := image.NewGray(image.Rect(0, 0, w*int(tr.Columns), h*int(tr.Rows)))
		for c := range per {
			if i := s*per + c; i < int(tr.Tiles) {
				x, y := c%int(tr.Columns)*w, c/int(tr.Columns)*h
				for py := y; py < y+h; py++ {
					for px := x; px < x+w; px++ {
						img.SetGray(px, py, color.Gray{Y: grey(i)})
					}
				}
			}
		}
		var b bytes.Buffer
		if err := jpeg.Encode(&b, img, &jpeg.Options{Quality: 90}); err != nil {
			t.Fatal(err)
		}
		total += int64(b.Len())
		if err := os.WriteFile(filepath.Join(dir, SheetName(s)), b.Bytes(), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	return total
}

// The I-frame track is made once from the sheets: one keyframe per tile in
// tile order, black cells left out, each fragment at its tile's time on a
// VOD session's media timeline (file time + 10 s), H.264 Main 3.0 as the
// master playlist says before it is made. Later calls read it back rather
// than run ffmpeg again.
func TestRealIFrames(t *testing.T) {
	t.Parallel()
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not installed")
	}
	if !strings.Contains(run(t, ffmpeg, "-hide_banner", "-encoders"), " libx264 ") {
		t.Skip("no libx264")
	}
	log := slog.New(slog.DiscardHandler)
	root := t.TempDir()
	g := New(nil, nil, nil, log, Options{FFmpeg: ffmpeg, Dir: root})
	tr := db.Trickplay{MediaFileID: 7, Version: Version(1000, "2026-11-01T10:00:00Z", ToneSDR), Status: "done", AlgoVersion: AlgoVersion,
		IntervalMs: 2000, Width: 64, Height: 36, Columns: 3, Rows: 2, Tiles: 8, Sheets: 2}
	grey := func(i int) uint8 { return uint8(30 + 25*i) }
	tr.Bytes = writeSheets(t, FileDir(root, tr.MediaFileID, tr.Version), tr, grey)
	const durationMs = 15_000

	// Before it is made, the master playlist estimates from the sheets.
	s := g.IFrameStream(tr, durationMs)
	if s.Codecs != playback.IFrameCodecs || s.Width != 64 || s.Height != 36 || s.Bandwidth != max(2*tr.Bytes/8*8*1000/2000, 8_000) {
		t.Fatalf("stream before the track: %+v", s)
	}

	track, err := s.Track(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if track.Path != filepath.Join(FileDir(root, 7, tr.Version), IFramesName) || track.IntervalMs != 2000 || len(track.Frames) != 8 ||
		track.InitSize <= 0 || track.Codecs != playback.IFrameCodecs {
		t.Fatalf("track: %+v", track)
	}
	b, err := os.ReadFile(track.Path)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(b[:track.InitSize], []byte("elst")) || !bytes.Contains(b[:track.InitSize], []byte("moov")) {
		t.Error("the init segment keeps an edit list, or has no moov")
	}
	end := track.InitSize
	for i, f := range track.Frames {
		frag := b[f.Offset : f.Offset+f.Size]
		if f.Offset != end || string(frag[4:8]) != "moof" {
			t.Fatalf("frame %d at %d (want %d) starts %q", i, f.Offset, end, frag[4:8])
		}
		end = f.Offset + f.Size
		k := bytes.Index(frag, []byte("tfdt"))
		var at uint64
		if frag[k+4] == 1 {
			at = binary.BigEndian.Uint64(frag[k+8:])
		} else {
			at = uint64(binary.BigEndian.Uint32(frag[k+8:]))
		}
		if want := uint64(10_000 + 2000*i); at != want {
			t.Errorf("frame %d decodes at %d ms, want %d", i, at, want)
		}
	}
	if end != int64(len(b)) {
		t.Errorf("frames end at %d of %d bytes", end, len(b))
	}
	// Each frame is its tile, in order.
	cmd := exec.Command(ffmpeg, "-v", "error", "-i", track.Path, "-vf", "scale=1:1", "-f", "rawvideo", "-pix_fmt", "gray", "-") //nolint:gosec // G204: the test's own ffmpeg
	px, err := cmd.Output()
	if err != nil || len(px) != 8 {
		t.Fatalf("decoding the track: %v (%d frames)", err, len(px))
	}
	for i, y := range px {
		if d := int(y) - int(grey(i)); d < -12 || d > 12 {
			t.Errorf("frame %d is grey %d, want about %d", i, y, grey(i))
		}
	}

	// Made once: the next ask, even from a generator without ffmpeg, reads it.
	g.ffmpeg = filepath.Join(root, "no-ffmpeg")
	if again, err := g.IFrames(t.Context(), tr); err != nil || again != track {
		t.Errorf("second ask: %v, same track %v", err, again == track)
	}
	fresh := New(nil, nil, nil, log, Options{FFmpeg: filepath.Join(root, "no-ffmpeg"), Dir: root})
	if read, err := fresh.IFrames(t.Context(), tr); err != nil || read.InitSize != track.InitSize || len(read.Frames) != 8 || read.Frames[7] != track.Frames[7] {
		t.Errorf("read back: %v %+v", err, read)
	}
	if s := g.IFrameStream(tr, durationMs); s.Bandwidth != track.Bandwidth(durationMs) {
		t.Errorf("stream after the track: bandwidth %d, want the track's %d", s.Bandwidth, track.Bandwidth(durationMs))
	}

	// Gone with its folder: made again, which fails without ffmpeg.
	if err := os.RemoveAll(FileDir(root, 7, tr.Version)); err != nil {
		t.Fatal(err)
	}
	if _, err := g.IFrames(t.Context(), tr); err == nil {
		t.Error("a deleted track came back from the cache")
	}
}

// The command line untiles the sheets in order, drops the black cells and
// stamps each tile at its time.
func TestIFrameArgs(t *testing.T) {
	t.Parallel()
	g := New(nil, nil, nil, slog.New(slog.DiscardHandler), Options{})
	tr := db.Trickplay{IntervalMs: 10_000, Columns: 10, Rows: 10, Tiles: 724}
	args := strings.Join(g.iframeArgs("/t/7-abcd", tr, "/t/7-abcd/iframes.mp4.raw.part"), " ")
	for _, want := range []string{"-start_number 0 -i " + filepath.Join("/t/7-abcd", "%03d.jpg"),
		"untile=10x10,trim=end_frame=724,setpts=N*10.000/TB,format=yuv420p", "-frames:v 724",
		"-c:v libx264", "-profile:v main -level:v 3.0", "keyint=1:min-keyint=1:scenecut=0",
		"+frag_keyframe+empty_moov+default_base_moof+skip_trailer -f mp4 -y /t/7-abcd/iframes.mp4.raw.part"} {
		if !strings.Contains(args, want) {
			t.Errorf("args lack %q:\n%s", want, args)
		}
	}
}
