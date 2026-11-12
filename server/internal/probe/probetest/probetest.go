// Package probetest makes media files for tests that real libraries have
// and the fixture does not.
package probetest

import (
	"bytes"
	"encoding/binary"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

// DolbyVision describes a synthetic Dolby Vision clip.
type DolbyVision struct {
	Profile, Level, Compat int
	// Entry is the MP4 sample entry: hvc1 (the default) or dvh1.
	Entry string
	// MKV remuxes the clip into Matroska, which keeps the record in its
	// block addition mapping.
	MKV bool
}

// DolbyVisionClip renders a three-second 10-bit PQ HEVC clip with AAC
// audio and gives it a DOVI configuration record, or skips the test when
// ffmpeg has no libx265. The picture carries no RPU: ffprobe, the muxers
// and the bitstream filters see Dolby Vision, but a decoder shows the
// plain HEVC picture. x265 and dovi_tool would make a real one; neither
// is needed here.
func DolbyVisionClip(t *testing.T, ffmpeg string, dv DolbyVision) string {
	t.Helper()
	dir := t.TempDir()
	base := filepath.Join(dir, "base.mp4")
	if b, err := exec.CommandContext(t.Context(), ffmpeg, "-v", "error", "-y",
		"-f", "lavfi", "-i", "testsrc2=size=640x360:rate=24:duration=3",
		"-f", "lavfi", "-i", "sine=frequency=440:duration=3",
		"-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le", "-g", "24",
		"-x265-params", "colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:log-level=error",
		"-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc",
		"-tag:v", "hvc1", "-c:a", "aac", "-shortest", base).CombinedOutput(); err != nil {
		t.Skipf("cannot make an HEVC Main 10 clip (libx265?): %v\n%s", err, b)
	}
	raw, err := os.ReadFile(base) //nolint:gosec // G304: the test's own file
	if err != nil {
		t.Fatal(err)
	}
	if dv.Entry == "" {
		dv.Entry = "hvc1"
	}
	out := filepath.Join(dir, "dv.mp4")
	if err := os.WriteFile(out, addRecord(t, raw, dv), 0o600); err != nil { //nolint:gosec // G703: the test's own temporary folder
		t.Fatal(err)
	}
	if !dv.MKV {
		return out
	}
	mkv := filepath.Join(dir, "dv.mkv")
	if b, err := exec.CommandContext(t.Context(), ffmpeg, "-v", "error", "-y", "-i", out, "-c", "copy", mkv).CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg mkv: %v\n%s", err, b)
	}
	return mkv
}

// addRecord puts a dvcC (profiles up to 7) or dvvC box at the end of the
// file's HEVC sample entry, renames the entry to dv.Entry, and grows the
// boxes around it. ffmpeg writes the moov after the media data, so no
// chunk offset moves.
func addRecord(t *testing.T, b []byte, dv DolbyVision) []byte {
	t.Helper()
	path := []string{"moov", "trak", "mdia", "minf", "stbl", "stsd", "hvc1"}
	var chain []int // offsets of each box on the path
	start, end := 0, len(b)
	for depth := 0; depth < len(path); {
		found := false
		for i := start; i+8 <= end; {
			size := int(binary.BigEndian.Uint32(b[i:]))
			if size < 8 || i+size > end {
				t.Fatalf("bad box at %d", i)
			}
			if string(b[i+4:i+8]) == path[depth] {
				chain = append(chain, i)
				header := 8
				if path[depth] == "stsd" {
					header = 16 // full box and entry count
				}
				start, end, found = i+header, i+size, true
				break
			}
			i += size
		}
		if !found {
			t.Fatalf("no %s box", path[depth])
		}
		depth++
	}
	rpu, el, bl := 1, 0, 1
	rec := make([]byte, 24)
	rec[0], rec[1] = 1, 0 // version 1.0
	rec[2] = byte(dv.Profile<<1 | dv.Level>>5)
	rec[3] = byte((dv.Level&0x1f)<<3 | rpu<<2 | el<<1 | bl)
	rec[4] = byte(dv.Compat << 4)
	name := "dvvC"
	if dv.Profile <= 7 {
		name = "dvcC"
	}
	box := binary.BigEndian.AppendUint32(nil, uint32(8+len(rec)))
	box = append(append(box, name...), rec...)
	entry := chain[len(chain)-1]
	at := entry + int(binary.BigEndian.Uint32(b[entry:]))
	out := bytes.Clone(b[:at])
	out = append(append(out, box...), b[at:]...)
	for _, i := range chain {
		binary.BigEndian.PutUint32(out[i:], binary.BigEndian.Uint32(out[i:])+uint32(len(box)))
	}
	copy(out[entry+4:entry+8], dv.Entry)
	return out
}
