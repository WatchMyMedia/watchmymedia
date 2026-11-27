package playback

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// A negative shift moves cues later, as a burned-in track at a person's
// timing needs; nothing is dropped.
func TestShiftSubtitleLater(t *testing.T) {
	t.Parallel()
	vtt := "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nFirst\n\n00:59:59.500 --> 01:00:01.000 line:90%\nSpans the hour\n"
	got := string(ShiftSubtitle([]byte(vtt), "webvtt", -1_500))
	want := "WEBVTT\n\n00:00:02.500 --> 00:00:03.500\nFirst\n\n01:00:01.000 --> 01:00:02.500 line:90%\nSpans the hour\n\n"
	if got != want {
		t.Errorf("vtt:\n%q\nwant\n%q", got, want)
	}
	ass := "[Events]\nFormat: Layer, Start, End, Style, Text\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,First\n"
	if got := string(ShiftSubtitle([]byte(ass), "ass", -400)); !strings.Contains(got, "Dialogue: 0,0:00:01.40,0:00:02.40,Default,,0,0,0,,First") {
		t.Errorf("ass: %q", got)
	}
}

// The shifted copy a session burns in, written once while its content
// stays the same.
func TestShiftedCopy(t *testing.T) {
	t.Parallel()
	s := NewSubtitles("ffmpeg", filepath.Join(t.TempDir(), "subtitles"))
	defer s.Close()
	vtt := []byte("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nFirst\n")
	path, err := s.Shifted("7-x0", vtt, "webvtt", 2_000)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := os.ReadFile(path)
	if !strings.Contains(string(b), "00:00:03.000 --> 00:00:04.000") || filepath.Ext(path) != ".vtt" {
		t.Errorf("%s: %q", path, b)
	}
	st, _ := os.Stat(path)
	again, err := s.Shifted("7-x0", vtt, "webvtt", 2_000)
	st2, _ := os.Stat(again)
	if err != nil || again != path || !st2.ModTime().Equal(st.ModTime()) {
		t.Errorf("rewritten: %s %v", again, err)
	}
	if other, _ := s.Shifted("7-x0", vtt, "webvtt", -2_000); other == path {
		t.Error("another timing shares the file")
	}
}

// An image subtitle at a person's timing is read from a second input
// offset by it, starting that much earlier, composed with the lookback a
// seeked run already reads.
func TestImageBurnInTimingArgs(t *testing.T) {
	t.Parallel()
	m := NewManager("ffmpeg", t.TempDir(), Capabilities{Encoder: "libx264", HWAccel: "software", Overlay: true}, 0, 4, testLog)
	base := Spec{FileID: 1, Path: "/fictional/film.mkv", Mode: ModeTranscode, Video: VideoPlan{Codec: "h264", Width: 320, Height: 180},
		Audio: AudioPlan{Index: 1, Copy: true}, BurnIn: 3, BurnInImage: true}
	second := func(spec Spec) string {
		args := strings.Join(m.args(spec, t.TempDir()), " ")
		_, rest, _ := strings.Cut(args, "-i /fictional/film.mkv")
		between, _, ok := strings.Cut(rest, "-i /fictional/film.mkv")
		if !ok {
			return ""
		}
		if !strings.Contains(args, "[0:v:0]") || !strings.Contains(args, "[1:3]overlay") {
			t.Errorf("overlay doesn't read the second input: %s", args)
		}
		return strings.TrimSpace(between)
	}
	for _, c := range []struct {
		name            string
		start, timingMs int64
		vod             bool
		want            string
	}{
		{"event from the start, later", 0, 1_500, false, "-noaccurate_seek -ss 0.000 -itsoffset 1.500"},
		{"event from the start, earlier", 0, -2_000, false, "-noaccurate_seek -ss 0.000 -itsoffset -2.000"},
		{"event seeked, later", 60_000, 1_500, false, "-noaccurate_seek -ss 28.500 -itsoffset -30.000"},
		{"event seeked, earlier", 60_000, -2_000, false, "-noaccurate_seek -ss 32.000 -itsoffset -30.000"},
		{"event seeked, in step as before", 60_000, 0, false, "-noaccurate_seek -ss 30.000 -itsoffset -30.000"},
		{"vod from the start, later", 0, 1_500, true, "-itsoffset 1.500"},
		{"vod seeked, later", 60_000, 1_500, true, "-ss 28.500 -itsoffset 1.500"},
		{"vod seeked, earlier", 60_000, -2_000, true, "-ss 32.000 -itsoffset -2.000"},
		{"vod seeked, in step as before", 60_000, 0, true, "-ss 30.000"},
	} {
		spec := base
		spec.StartMs, spec.BurnInTimingMs = c.start, c.timingMs
		if c.vod {
			spec.VOD = &VODOptions{DurationMs: 120_000, FrameRate: 24}
		}
		if got := second(spec); got != c.want {
			t.Errorf("%s: second input %q, want %q", c.name, got, c.want)
		}
	}
	// In step from the start, the overlay reads the one input, as before.
	if args := strings.Join(m.args(base, t.TempDir()), " "); strings.Count(args, "-i /fictional/film.mkv") != 1 || !strings.Contains(args, "[0:3]overlay") {
		t.Errorf("in step from the start: %s", args)
	}
}

// ffmpeg draws an image subtitle at the timing: the sample's box is on
// screen from 1 s to 9 s in the file, so on a run's first frame at 9.5 s
// it is gone in step and still there 2 s later, and at 8 s it has gone
// 2 s earlier.
func TestImageBurnInTimingDrawsTheCue(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	src := pgsSample(t, ffmpeg)
	m := testManager(t, ffmpeg, "software")
	if !m.caps.Overlay {
		t.Skip("ffmpeg has no overlay filter")
	}
	first := func(start, timing int64) float64 {
		spec := Spec{FileID: 1, PlayID: "p1", UserID: 1, Path: src, Mode: ModeTranscode, StartMs: start,
			Video: VideoPlan{Codec: "h264", Width: 320, Height: 180}, Audio: AudioPlan{Index: 1, Copy: true}, BurnIn: 2, BurnInImage: true, BurnInTimingMs: timing}
		s, err := m.Start(context.Background(), spec)
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
		return boxLuma(t, ffmpeg, one)
	}
	if l := first(9_500, 0); l >= 128 {
		t.Errorf("in step, the box is on screen at 9.5 s (luma %.0f)", l)
	}
	if l := first(9_500, 2_000); l < 128 {
		t.Errorf("2 s later, no box at 9.5 s (luma %.0f)", l)
	}
	if l := first(8_000, -2_000); l >= 128 {
		t.Errorf("2 s earlier, the box is on screen at 8 s (luma %.0f)", l)
	}
}
