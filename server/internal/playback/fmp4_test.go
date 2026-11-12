package playback

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"testing"
)

// keyedSample renders a clip whose keyframes fall at uneven times, as in
// real encodes, with AAC audio (which starts a little before zero).
func keyedSample(t *testing.T, ffmpeg string, seconds int, keys string) string {
	return keyedSampleAt(t, ffmpeg, "24", seconds, keys)
}

// keyedSampleAt is keyedSample at another frame rate, such as 24000/1001.
func keyedSampleAt(t *testing.T, ffmpeg, rate string, seconds int, keys string) string {
	t.Helper()
	out := filepath.Join(t.TempDir(), "keyed.mkv")
	cmd := exec.Command(ffmpeg, "-v", "error", "-y",
		"-f", "lavfi", "-i", fmt.Sprintf("testsrc2=size=320x180:rate=%s:duration=%d", rate, seconds),
		"-f", "lavfi", "-i", fmt.Sprintf("sine=frequency=440:sample_rate=48000:duration=%d", seconds),
		"-map", "0:v", "-map", "1:a", "-c:v", "mpeg4", "-q:v", "5", "-g", "1000", "-force_key_frames", keys,
		"-c:a", "aac", out)
	if b, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg sample: %v\n%s", err, b)
	}
	return out
}

// hlsPieces runs ffmpeg the way VOD runs do, from a keyframe, and returns
// the init segment and the pieces in order.
func hlsPieces(t *testing.T, ffmpeg, src, dir string, from string) ([]byte, [][]byte) {
	t.Helper()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	args := []string{"-v", "error", "-y"}
	if from != "" {
		args = append(args, "-ss", from)
	}
	args = append(args, "-copyts", "-start_at_zero", "-i", src, "-map", "0:v:0", "-c:v", "copy", "-map", "0:a:0", "-c:a", "copy",
		"-avoid_negative_ts", "disabled", "-f", "hls", "-hls_time", "0.001", "-hls_playlist_type", "event", "-hls_list_size", "0",
		"-hls_segment_type", "fmp4", "-hls_fmp4_init_filename", "init.mp4", "-hls_segment_filename", filepath.Join(dir, "p%05d.m4s"),
		filepath.Join(dir, "index.m3u8"))
	cmd := exec.CommandContext(t.Context(), ffmpeg, args...)
	cmd.Dir = dir // as for sessions: on Windows the init segment lands in the working directory
	if b, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg hls: %v\n%s", err, b)
	}
	init, err := os.ReadFile(filepath.Join(dir, "init.mp4"))
	if err != nil {
		t.Fatal(err)
	}
	names, _ := filepath.Glob(filepath.Join(dir, "p*.m4s"))
	sort.Strings(names)
	var pieces [][]byte
	for _, n := range names {
		b, err := os.ReadFile(n)
		if err != nil {
			t.Fatal(err)
		}
		pieces = append(pieces, b)
	}
	return init, pieces
}

// tfdts reads each track's decode time from a media segment.
func tfdts(t *testing.T, b []byte) map[uint32]uint64 {
	t.Helper()
	out := map[uint32]uint64{}
	top, _ := mp4Boxes(b, 0, len(b))
	for _, x := range top {
		if x.typ != "moof" {
			continue
		}
		kids, _ := mp4Boxes(b, x.body, x.end)
		for _, traf := range kids {
			if traf.typ != "traf" {
				continue
			}
			tfhd, _ := childBox(b, traf, "tfhd")
			tfdt, _ := childBox(b, traf, "tfdt")
			id := binary.BigEndian.Uint32(b[tfhd.body+4:])
			if b[tfdt.body] == 1 {
				out[id] = binary.BigEndian.Uint64(b[tfdt.body+4:])
			} else {
				out[id] = uint64(binary.BigEndian.Uint32(b[tfdt.body+4:]))
			}
		}
	}
	return out
}

func TestPiecesFromTwoRunsLineUp(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	src := keyedSample(t, ffmpeg, 20, "0,1.7,3.1,6.9,7.4,12,15.5")
	dir := t.TempDir()
	init0, all := hlsPieces(t, ffmpeg, src, filepath.Join(dir, "r0"), "")
	// Keyframes fall on frame times: 6.9 is 6.917 at 24 fps. Runs seek a
	// millisecond past the keyframe they start at, as VOD sessions do.
	init1, late := hlsPieces(t, ffmpeg, src, filepath.Join(dir, "r1"), "6.918")
	if len(all) != 7 || len(late) != 4 {
		t.Fatalf("pieces: %d from the start, %d from 6.9", len(all), len(late))
	}
	i0, err := parseInit(init0)
	if err != nil {
		t.Fatal(err)
	}
	i1, err := parseInit(init1)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(i0.Stripped, i1.Stripped) {
		t.Error("init segments differ once their edit lists are gone")
	}
	if bytes.Contains(i0.Stripped, []byte("edts")) || i0.Video != 1 || len(i0.Tracks) != 2 {
		t.Errorf("init = video %d, tracks %+v", i0.Video, i0.Tracks)
	}
	if _, err := parseInit(init0[:20]); err == nil {
		t.Error("truncated init accepted")
	}
	// The same piece from either run starts at the same file time and,
	// once patched, carries the same decode times.
	for k, want := range []float64{6.917, 7.417, 12, 15.5} {
		a, ok1 := sidxStart(all[k+3], i0.Video)
		b, ok2 := sidxStart(late[k], i1.Video)
		if !ok1 || !ok2 || abs(a-want) > 0.002 || abs(b-want) > 0.002 {
			t.Errorf("piece at %.1f starts at %.4f / %.4f", want, a, b)
		}
		pa, err := patchFragment(all[k+3], i0)
		if err != nil {
			t.Fatal(err)
		}
		pb, err := patchFragment(late[k], i1)
		if err != nil {
			t.Fatal(err)
		}
		ta, tb := tfdts(t, pa), tfdts(t, pb)
		for id, tr := range i0.Tracks {
			// Within a millisecond: the edit list stores the run's start in ms.
			if d := int64(ta[id]) - int64(tb[id]); d < -int64(tr.Timescale)/1000-1 || d > int64(tr.Timescale)/1000+1 {
				t.Errorf("piece at %.1f, track %d: tfdt %d vs %d", want, id, ta[id], tb[id])
			}
		}
		if bytes.Contains(pa, []byte("sidx")) || bytes.Contains(pa, []byte("styp")) {
			t.Error("patched piece still has sidx or styp")
		}
	}
	// One stream made of both runs plays straight through: video times
	// rise by one frame at every join and start at the offset.
	stream := append([]byte{}, i0.Stripped...)
	for _, p := range all[:3] {
		x, _ := patchFragment(p, i0)
		stream = append(stream, x...)
	}
	for _, p := range late {
		x, _ := patchFragment(p, i1)
		stream = append(stream, x...)
	}
	f := filepath.Join(dir, "joined.mp4")
	if err := os.WriteFile(f, stream, 0o600); err != nil {
		t.Fatal(err)
	}
	out, err := exec.Command(filepath.Join(filepath.Dir(ffmpeg), "ffprobe"), "-v", "error", "-select_streams", "v:0", "-show_entries", "packet=pts_time", "-of", "csv=p=0", f).Output()
	if err != nil {
		t.Skipf("ffprobe: %v", err)
	}
	var pts []float64
	for l := range strings.FieldsSeq(string(out)) {
		v, _ := strconv.ParseFloat(strings.TrimSuffix(l, ","), 64)
		pts = append(pts, v)
	}
	sort.Float64s(pts)
	if len(pts) < 470 || abs(pts[0]-vodTimeOffset) > 0.001 {
		t.Fatalf("%d frames, first at %.3f", len(pts), pts[0])
	}
	for i := 1; i < len(pts); i++ {
		if d := pts[i] - pts[i-1]; d < 0.03 || d > 0.05 {
			t.Fatalf("frame %d: gap of %.3fs at %.3f", i, d, pts[i])
		}
	}
}

func abs(x float64) float64 {
	if x < 0 {
		return -x
	}
	return x
}

// sidxStart reads a piece's earliest presentation time from its sidx box,
// which ffmpeg writes in file time. (With closed GOPs, as here, that is the
// keyframe the piece starts at.)
func sidxStart(b []byte, track uint32) (float64, bool) {
	top, err := mp4Boxes(b, 0, len(b))
	if err != nil {
		return 0, false
	}
	for _, x := range top {
		if x.typ != "sidx" || binary.BigEndian.Uint32(b[x.body+4:]) != track {
			continue
		}
		scale := binary.BigEndian.Uint32(b[x.body+8:])
		if b[x.body] == 1 {
			return float64(binary.BigEndian.Uint64(b[x.body+12:])) / float64(scale), true
		}
		return float64(binary.BigEndian.Uint32(b[x.body+12:])) / float64(scale), true
	}
	return 0, false
}
