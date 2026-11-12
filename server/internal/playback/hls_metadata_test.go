package playback

import (
	"bytes"
	"encoding/binary"
	"os"
	"path/filepath"
	"testing"

	"watchmymedia/server/internal/ffexec"
)

func TestHLSVideoBoundaryRejectsDependentAndTruncatedMedia(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	args := ffexec.FFmpegArgs("-f", "lavfi", "-i", "testsrc2=size=160x90:rate=30:duration=4", "-c:v", "libx264", "-threads", "1", "-g", "60", "-sc_threshold", "0", "-an",
		"-f", "hls", "-hls_time", "2", "-hls_segment_type", "fmp4", "-hls_fmp4_init_filename", "init.mp4", "-hls_segment_filename", filepath.ToSlash(filepath.Join(dir, "segment-%d.m4s")), filepath.ToSlash(filepath.Join(dir, "live.m3u8")))
	if err := ffexec.Command(t.Context(), "ffmpeg", args...).Run(); err != nil {
		t.Fatal(err)
	}
	init, err := os.ReadFile(filepath.Join(dir, "init.mp4"))
	if err != nil {
		t.Fatal(err)
	}
	media, err := os.ReadFile(filepath.Join(dir, "segment-0.m4s"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := HLSVideoInit(init, 160, 90); err != nil {
		t.Fatal(err)
	}
	if _, err := HLSVideoInit(init, 320, 180); err == nil {
		t.Fatal("wrong dimensions accepted")
	}
	if err := HLSAudioInit(init, 2); err == nil {
		t.Fatal("video init accepted as common audio")
	}
	if _, err := HLSVideoBoundary(init, media); err != nil {
		t.Fatal(err)
	}
	for _, bad := range [][]byte{nil, media[:8], media[:len(media)-1]} {
		if _, err := HLSVideoBoundary(init, bad); err == nil {
			t.Fatal("truncated fragment accepted")
		}
	}
	dependent := bytes.Clone(media)
	boxes, err := mp4Boxes(dependent, 0, len(dependent))
	if err != nil {
		t.Fatal(err)
	}
	changed := false
	for _, box := range boxes {
		if box.typ != "moof" {
			continue
		}
		traf, ok := childBox(dependent, box, "traf")
		if !ok {
			t.Fatal("no traf")
		}
		trun, ok := childBox(dependent, traf, "trun")
		if !ok {
			t.Fatal("no trun")
		}
		flags := binary.BigEndian.Uint32(dependent[trun.body:]) & 0xffffff
		if flags&4 == 0 {
			t.Fatal("expected first-sample flags from test muxer")
		}
		offset := trun.body + 8
		if flags&1 != 0 {
			offset += 4
		}
		binary.BigEndian.PutUint32(dependent[offset:], 0x01010000)
		changed = true
	}
	if !changed {
		t.Fatal("no video fragment")
	}
	if _, err := HLSVideoBoundary(init, dependent); err == nil {
		t.Fatal("non-independent first frame accepted")
	}
}
