package playback

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestPlans(t *testing.T) {
	t.Parallel()
	keys := []int64{0, 1_708_000, 3_125_000, 6_917_000, 7_417_000, 12_000_000, 15_500_000, 16_000_000, 21_333_000, 27_000_000}
	got := copyPlan(keys, 30_000_000, 6_000_000)
	want := []int64{0, 6_917_000, 15_500_000, 27_000_000, 30_000_000}
	if fmt.Sprint(got) != fmt.Sprint(want) {
		t.Errorf("copy plan = %v", got)
	}
	// A keyframe just before the end does not make a sliver of a segment.
	if got := copyPlan([]int64{0, 6_000_000, 11_500_000}, 12_000_000, 6_000_000); fmt.Sprint(got) != "[0 6000000 12000000]" {
		t.Errorf("tail plan = %v", got)
	}
	if got := gridPlan(30_000_000, 6_000_000); fmt.Sprint(got) != "[0 6000000 12000000 18000000 24000000 30000000]" {
		t.Errorf("grid = %v", got)
	}
	if got := gridPlan(31_000_000, 6_000_000); fmt.Sprint(got) != "[0 6000000 12000000 18000000 24000000 31000000]" {
		t.Errorf("grid with a short tail = %v", got)
	}
}

// packets lists a stream's packets as (pts, keyframe) from ffprobe.
func packets(t *testing.T, ffmpeg, file, stream string) ([]float64, []bool) {
	t.Helper()
	out, err := exec.Command(filepath.Join(filepath.Dir(ffmpeg), "ffprobe"), "-v", "error", "-select_streams", stream, "-show_entries", "packet=pts_time,flags", "-of", "csv=p=0", file).Output()
	if err != nil {
		t.Fatalf("ffprobe %s: %v", file, err)
	}
	var pts []float64
	var keys []bool
	for l := range strings.FieldsSeq(string(out)) {
		parts := strings.Split(l, ",")
		v, _ := strconv.ParseFloat(parts[0], 64)
		pts = append(pts, v)
		keys = append(keys, len(parts) > 1 && strings.HasPrefix(parts[1], "K"))
	}
	return pts, keys
}

// fetchAll joins the init segment and every segment into one file.
func fetchAll(t *testing.T, s *Session, order []int) (string, [][]byte) {
	t.Helper()
	ctx := context.Background()
	init, _, err := s.VODFile(ctx, VODInit)
	if err != nil {
		t.Fatal(err)
	}
	segs := make([][]byte, s.vod.Segments())
	for _, k := range order {
		b, ct, err := s.VODFile(ctx, fmt.Sprintf("v%05d.m4s", k))
		if err != nil || ct != "video/mp4" || len(b) == 0 {
			t.Fatalf("segment %d: %v (%s, %d bytes)", k, err, ct, len(b))
		}
		segs[k] = b
	}
	stream := append([]byte{}, init...)
	for _, b := range segs {
		stream = append(stream, b...)
	}
	f := filepath.Join(t.TempDir(), "joined.mp4")
	if err := os.WriteFile(f, stream, 0o600); err != nil {
		t.Fatal(err)
	}
	return f, segs
}

func checkContinuous(t *testing.T, pts []float64, from, to float64) {
	t.Helper()
	sort.Float64s(pts)
	if len(pts) == 0 || abs(pts[0]-(vodTimeOffset+from)) > 0.002 || pts[len(pts)-1] < vodTimeOffset+to-0.1 {
		t.Fatalf("video runs %v .. %v", pts[0], pts[len(pts)-1])
	}
	for i := 1; i < len(pts); i++ {
		if d := pts[i] - pts[i-1]; d < 0.03 || d > 0.05 {
			t.Fatalf("gap of %.3fs at %.3f", d, pts[i]-vodTimeOffset)
		}
	}
}

func TestVODCopySession(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	src := keyedSample(t, ffmpeg, 30, "0,1.7,3.1,6.9,7.4,12,15.5,16,21.3,27")
	k := NewKeyframes(filepath.Join(filepath.Dir(ffmpeg), "ffprobe"), "", testLog)
	st, _ := os.Stat(src)
	keys, err := k.Get(context.Background(), src, st.Size(), "x")
	if err != nil {
		t.Fatal(err)
	}
	m := testManager(t, ffmpeg, "software")
	vtt := "WEBVTT\n\n00:00:02.000 --> 00:00:04.000\nEarly\n\n00:00:16.000 --> 00:00:18.000\nLater\n"
	spec := Spec{FileID: 1, PlayID: "p1", UserID: 1, Path: src, Mode: ModeRemux, BurnIn: -1, StartMs: 28000,
		Video: VideoPlan{Codec: "mpeg4", Copy: true, Width: 320, Height: 180}, Audio: AudioPlan{Index: 1, Copy: true},
		VOD: &VODOptions{DurationMs: 30000, Keyframes: keys, FrameRate: 24, VideoRange: "SDR", Bandwidth: 2_000_000, Codecs: "mp4v.20.9,mp4a.40.2",
			Subtitles: []VODSubtitle{{ID: "s2", Name: "English", Language: "en"}, {ID: "x0", Name: "English"}, {ID: "d7", Name: "English"}},
			Subtitle:  func(_ context.Context, id string) ([]byte, error) { return []byte(vtt), nil }}}
	s, err := m.Start(context.Background(), spec)
	if err != nil {
		t.Fatal(err)
	}
	if !s.IsVOD() || s.vod.Segments() != 4 {
		t.Fatalf("segments = %d", s.vod.Segments())
	}
	master, ct, _ := s.VODFile(context.Background(), VODMaster)
	for _, want := range []string{"#EXT-X-STREAM-INF:BANDWIDTH=2000000,CODECS=\"mp4v.20.9,mp4a.40.2\",RESOLUTION=320x180,FRAME-RATE=24.000,VIDEO-RANGE=SDR,SUBTITLES=\"subs\"\nvideo.m3u8",
		`NAME="English",LANGUAGE="en",DEFAULT=NO,AUTOSELECT=YES,FORCED=NO,URI="sub-s2.m3u8"`, `NAME="English (2)"`} {
		if !strings.Contains(string(master), want) || ct != "application/vnd.apple.mpegurl" {
			t.Errorf("master playlist lacks %q:\n%s", want, master)
		}
	}
	media, _, _ := s.VODFile(context.Background(), VODMedia)
	for _, want := range []string{"#EXT-X-PLAYLIST-TYPE:VOD", "#EXT-X-TARGETDURATION:12", "#EXT-X-MAP:URI=\"init.mp4\"", "#EXTINF:6.917000,\nv00000.m4s", "#EXTINF:3.000000,\nv00003.m4s", "#EXT-X-ENDLIST"} {
		if !strings.Contains(string(media), want) {
			t.Errorf("media playlist lacks %q:\n%s", want, media)
		}
	}
	// The session started near the end; the start of the file needs a new run.
	f, _ := fetchAll(t, s, []int{3, 0, 1, 2})
	if s.vod.runs != 2 {
		t.Errorf("runs = %d, want 2", s.vod.runs)
	}
	pts, keyflags := packets(t, ffmpeg, f, "v:0")
	checkContinuous(t, pts, 0, 29.9)
	if !keyflags[0] {
		t.Error("stream does not start on a keyframe")
	}
	if a, _ := packets(t, ffmpeg, f, "a:0"); len(a) < 1300 {
		t.Errorf("audio packets = %d", len(a))
	}
	// Subtitles follow the same segments, in file time.
	pl, _, _ := s.VODFile(context.Background(), "sub-s2.m3u8")
	if !strings.Contains(string(pl), "sub-s2-00002.vtt") || strings.Contains(string(pl), "EXT-X-MAP") {
		t.Errorf("subtitle playlist:\n%s", pl)
	}
	seg, ct, err := s.VODFile(context.Background(), "sub-s2-00002.vtt")
	if err != nil || !strings.Contains(string(seg), "00:00:16.000 --> 00:00:18.000\nLater") || strings.Contains(string(seg), "Early") || !strings.HasPrefix(ct, "text/vtt") {
		t.Errorf("subtitle segment 2 (%v):\n%s", err, seg)
	}
	// A downloaded subtitle is a rendition like the others.
	if seg, _, err := s.VODFile(context.Background(), "sub-d7-00002.vtt"); err != nil || !strings.Contains(string(seg), "Later") {
		t.Errorf("downloaded subtitle segment (%v):\n%s", err, seg)
	}
	for _, name := range []string{"sub-s9.m3u8", "v00004.m4s", "index.m3u8", "sub-s2-00009.vtt", "audio.m3u8", "audio-init.mp4", "a00000.m4s"} {
		if _, _, err := s.VODFile(context.Background(), name); !errors.Is(err, os.ErrNotExist) {
			t.Errorf("%s -> %v", name, err)
		}
	}
	// Idle: ffmpeg and the pieces go, the playlist stays and refills.
	s.vod.suspend()
	// Suspension detaches the current run before its process exits.
	eventually(t, "still running after suspend", func() bool { return !s.vod.running() })
	if b, _, err := s.VODFile(context.Background(), "v00001.m4s"); err != nil || len(b) == 0 {
		t.Errorf("segment after suspend: %v", err)
	}
	m.Stop(s.ID)
	<-s.done
}

// streamTypes lists a file's streams by codec type, as "video,audio".
func streamTypes(t *testing.T, ffmpeg, file string) string {
	t.Helper()
	out, err := exec.Command(filepath.Join(filepath.Dir(ffmpeg), "ffprobe"), "-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0", file).Output()
	if err != nil {
		t.Fatalf("ffprobe %s: %v", file, err)
	}
	return strings.Join(strings.Fields(string(out)), ",")
}

// With SeparateAudio the audio is its own rendition, cut from the same
// segments: the video's files carry no audio, the audio's no video, and
// between them nothing is lost.
func TestVODSeparateAudio(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	ctx := context.Background()
	src := keyedSample(t, ffmpeg, 30, "0,1.7,3.1,6.9,7.4,12,15.5,16,21.3,27")
	k := NewKeyframes(filepath.Join(filepath.Dir(ffmpeg), "ffprobe"), "", testLog)
	st, _ := os.Stat(src)
	keys, err := k.Get(ctx, src, st.Size(), "x")
	if err != nil {
		t.Fatal(err)
	}
	m := testManager(t, ffmpeg, "software")
	spec := Spec{FileID: 1, PlayID: "p1", UserID: 1, Path: src, Mode: ModeRemux, BurnIn: -1,
		Video: VideoPlan{Codec: "mpeg4", Copy: true, Width: 320, Height: 180}, Audio: AudioPlan{Index: 1, Copy: true},
		VOD: &VODOptions{DurationMs: 30000, Keyframes: keys, FrameRate: 24, Bandwidth: 2_000_000, Codecs: "mp4v.20.9,mp4a.40.2",
			SeparateAudio: true, AudioChannels: 2, AudioLanguage: "en"}}
	s, err := m.Start(ctx, spec)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		m.Stop(s.ID)
		<-s.done
	}()

	master, _, _ := s.VODFile(ctx, VODMaster)
	for _, want := range []string{
		"#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Audio\",LANGUAGE=\"en\",CHANNELS=\"2\",DEFAULT=YES,AUTOSELECT=YES,URI=\"audio.m3u8\"\n",
		"CODECS=\"mp4v.20.9,mp4a.40.2\"",
		",AUDIO=\"audio\"\nvideo.m3u8",
	} {
		if !strings.Contains(string(master), want) {
			t.Errorf("master playlist lacks %q:\n%s", want, master)
		}
	}
	video, _, _ := s.VODFile(ctx, VODMedia)
	audio, ct, err := s.VODFile(ctx, VODAudio)
	if err != nil || ct != "application/vnd.apple.mpegurl" {
		t.Fatalf("audio playlist: %v (%s)", err, ct)
	}
	// The same segments under other names.
	want := strings.NewReplacer("init.mp4", "audio-init.mp4", "\nv0", "\na0").Replace(string(video))
	if string(audio) != want {
		t.Errorf("audio playlist:\n%s\nwant:\n%s", audio, want)
	}

	f, _ := fetchAll(t, s, []int{0, 1, 2, 3})
	if got := streamTypes(t, ffmpeg, f); got != "video" {
		t.Errorf("video rendition streams = %s", got)
	}
	pts, _ := packets(t, ffmpeg, f, "v:0")
	checkContinuous(t, pts, 0, 29.9)

	stream, _, err := s.VODFile(ctx, VODAudioInit)
	if err != nil {
		t.Fatal(err)
	}
	for k := 0; k < s.vod.Segments(); k++ {
		b, ct, err := s.VODFile(ctx, fmt.Sprintf("a%05d.m4s", k))
		if err != nil || ct != "video/mp4" || len(b) == 0 {
			t.Fatalf("audio segment %d: %v (%s, %d bytes)", k, err, ct, len(b))
		}
		stream = append(stream, b...)
	}
	af := filepath.Join(t.TempDir(), "audio.mp4")
	if err := os.WriteFile(af, stream, 0o600); err != nil {
		t.Fatal(err)
	}
	if got := streamTypes(t, ffmpeg, af); got != "audio" {
		t.Errorf("audio rendition streams = %s", got)
	}
	apts, _ := packets(t, ffmpeg, af, "a:0")
	sort.Float64s(apts)
	if len(apts) < 1300 || apts[0] > vodTimeOffset+0.05 || apts[len(apts)-1] < vodTimeOffset+29.8 {
		t.Errorf("audio packets = %d, from %v to %v", len(apts), apts[0], apts[len(apts)-1])
	}
	for i := 1; i < len(apts); i++ {
		if d := apts[i] - apts[i-1]; d > 0.05 {
			t.Fatalf("audio gap of %.3fs at %.3f", d, apts[i]-vodTimeOffset)
		}
	}
}

func TestVODTranscodeSession(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	// At 23.976 fps the grid falls between frames: keyframes land up to a
	// frame after each 6-second mark.
	src := keyedSampleAt(t, ffmpeg, "24000/1001", 40, "0,1.7,3.1,6.9,7.4,12,15.5,16,21.3,27")
	m := testManager(t, ffmpeg, "software")
	spec := Spec{FileID: 1, PlayID: "p1", UserID: 1, Path: src, Mode: ModeTranscode, BurnIn: -1, StartMs: 13000,
		Video: VideoPlan{Codec: "h264", Width: 320, Height: 180}, Audio: AudioPlan{Index: 1, Copy: true},
		VOD: &VODOptions{DurationMs: 40000, FrameRate: 23.976}}
	s, err := m.Start(context.Background(), spec)
	if err != nil {
		t.Fatal(err)
	}
	if s.vod.Segments() != 7 || s.Encoder == "copy" {
		t.Fatalf("segments = %d, encoder %s", s.vod.Segments(), s.Encoder)
	}
	f, segs := fetchAll(t, s, []int{2, 3, 4, 5, 6, 0, 1})
	pts, _ := packets(t, ffmpeg, f, "v:0")
	checkContinuous(t, pts, 0, 39.9)
	// Every segment starts with a keyframe on the 6-second grid.
	init, _, _ := s.VODFile(context.Background(), VODInit)
	for k, b := range segs {
		one := filepath.Join(t.TempDir(), "one.mp4")
		_ = os.WriteFile(one, append(append([]byte{}, init...), b...), 0o600)
		p, keyflags := packets(t, ffmpeg, one, "v:0")
		if len(p) == 0 || !keyflags[0] || p[0]-vodTimeOffset < float64(6*k)-0.002 || p[0]-vodTimeOffset > float64(6*k)+0.042 {
			t.Errorf("segment %d starts at %v (key %v)", k, p, keyflags)
		}
	}
	m.Stop(s.ID)
}

// x265 cuts open GOPs by default: frames shown just before each keyframe
// are stored after it, so a piece's earliest frame comes before the
// keyframe it starts at. Segments must still line up on the keyframes.
func TestVODCopyOpenGOP(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	if out, _ := exec.Command(ffmpeg, "-hide_banner", "-encoders").Output(); !strings.Contains(string(out), " libx265 ") {
		t.Skip("no libx265")
	}
	src := filepath.Join(t.TempDir(), "open.mkv")
	if b, err := exec.Command(ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=25:duration=40",
		"-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=40", "-c:v", "libx265", "-preset", "ultrafast",
		"-x265-params", "log-level=error:keyint=250:min-keyint=250:open-gop=1:bframes=4:scenecut=0", "-c:a", "aac", src).CombinedOutput(); err != nil {
		t.Fatalf("%v %s", err, b)
	}
	k := NewKeyframes(filepath.Join(filepath.Dir(ffmpeg), "ffprobe"), "", testLog)
	keys, err := k.Get(context.Background(), src, 1, "x")
	if err != nil {
		t.Fatal(err)
	}
	m := testManager(t, ffmpeg, "software")
	s, err := m.Start(context.Background(), Spec{FileID: 1, PlayID: "p1", UserID: 1, Path: src, Mode: ModeRemux, BurnIn: -1, StartMs: 21000,
		Video: VideoPlan{Codec: "hevc", Copy: true, Width: 320, Height: 180}, Audio: AudioPlan{Index: 1, Copy: true},
		VOD: &VODOptions{DurationMs: 40000, Keyframes: keys, FrameRate: 25}})
	if err != nil {
		t.Fatal(err)
	}
	if s.vod.Segments() != 4 {
		t.Fatalf("segments = %d (keyframes %v)", s.vod.Segments(), keys)
	}
	f, _ := fetchAll(t, s, []int{2, 3, 0, 1})
	if a, _ := packets(t, ffmpeg, f, "a:0"); len(a) < 1800 {
		t.Errorf("audio packets = %d", len(a))
	}
	// Every picture is there once, in order. Times are only loosely
	// checked: ffmpeg's muxer moves each piece's keyframe to the earliest
	// presentation time in the piece (event playlists do the same), and a
	// run that starts at an open-GOP keyframe squeezes the leading frames'
	// decode times, which leaves a step of a few frames where it joins
	// another run's pieces.
	out, err := exec.Command(filepath.Join(filepath.Dir(ffmpeg), "ffprobe"), "-v", "error", "-select_streams", "v:0", "-show_entries", "packet=dts_time", "-of", "csv=p=0", f).Output()
	if err != nil {
		t.Fatal(err)
	}
	var prev float64
	lines := strings.Fields(string(out))
	for i, l := range lines {
		d, _ := strconv.ParseFloat(strings.TrimSuffix(l, ","), 64)
		if i > 0 && (d <= prev || d-prev > 0.25) {
			t.Fatalf("decode time goes from %.3f to %.3f", prev, d)
		}
		prev = d
	}
	if len(lines) != 1000 {
		t.Errorf("video packets = %d, want 1000", len(lines))
	}
	m.Stop(s.ID)
}

// In many recordings the audio begins before the video, so the video's
// first frame comes after the start of the file. Segment 0 still has to be
// made, and the segments after it stay on their grid, whether a session
// starts at the beginning or comes back to it.
func TestVODVideoStartsLate(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	src := filepath.Join(t.TempDir(), "late.mkv")
	if b, err := exec.Command(ffmpeg, "-v", "error", "-y", "-itsoffset", "0.5", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=24:duration=20",
		"-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=20",
		"-map", "0:v", "-map", "1:a", "-c:v", "mpeg4", "-q:v", "5", "-g", "48", "-c:a", "aac", src).CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg sample: %v\n%s", err, b)
	}
	sw := testManager(t, ffmpeg, "software")
	keys, err := NewKeyframes(filepath.Join(filepath.Dir(ffmpeg), "ffprobe"), "", sw.log).Get(context.Background(), src, 1, "x")
	if err != nil {
		t.Fatal(err)
	}
	if keys[0] < 400_000 {
		t.Fatalf("the sample's video starts at %dµs", keys[0])
	}
	type videoCase struct {
		name  string
		m     *Manager
		video VideoPlan
		// first is where the video starts in the joined segments: a
		// transcode shows the first frame from zero, copied video where
		// the file has it.
		first float64
	}
	cases := []videoCase{
		{"software", sw, VideoPlan{Codec: "h264", Width: 320, Height: 180}, 0},
		{"copy", sw, VideoPlan{Codec: "mpeg4", Copy: true, Width: 320, Height: 180}, float64(keys[0]) / 1e6},
	}
	// A hardware HEVC encoder, as on the Mac where this first failed.
	if hw := testManager(t, ffmpeg, "auto"); hw.caps.HEVCEncoder != "" {
		cases = append(cases, videoCase{hw.caps.HEVCEncoder, hw, VideoPlan{Codec: "hevc", Encoder: hw.caps.HEVCEncoder, Width: 320, Height: 180}, 0})
	}
	for _, tc := range cases {
		for _, startMs := range []int64{0, 13000} {
			spec := Spec{FileID: 1, PlayID: "p1", UserID: 1, Path: src, Mode: ModeTranscode, BurnIn: -1, StartMs: startMs,
				Video: tc.video, Audio: AudioPlan{Index: 1, Copy: true}, VOD: &VODOptions{DurationMs: 20500, FrameRate: 24}}
			if tc.video.Copy {
				spec.Mode, spec.VOD.Keyframes = ModeRemux, keys
			}
			s, err := tc.m.Start(context.Background(), spec)
			if err != nil {
				t.Fatalf("%s, start %dms: %v", tc.name, startMs, err)
			}
			order := []int{0, 1, 2, 3}
			if startMs > 0 {
				order = []int{2, 3, 0, 1}
			}
			f, _ := fetchAll(t, s, order)
			pts, _ := packets(t, ffmpeg, f, "v:0")
			checkContinuous(t, pts, tc.first, 20.4)
			tc.m.Stop(s.ID)
		}
	}
}

// A run that has gone past a segment without making it is not waited on
// for vodWait: one that started at the segment fails the request at once,
// and one whose pieces of it were trimmed (a seek back) is replaced.
func TestVODRunPastSegment(t *testing.T) {
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	for _, tc := range []struct {
		name string
		run  *vodRun
		k    int
		want string
	}{
		// Its pieces start a second after the segment does.
		{"started there", &vodRun{seg: 2, startUs: 13e6, reached: 31e6}, 2, "segment 2 could not be made"},
		{"trimmed", &vodRun{seg: 0, reached: 150e6, trimmed: true}, 1, "start ffmpeg"},
	} {
		m := NewManager(filepath.Join(t.TempDir(), "no-ffmpeg"), t.TempDir(), Capabilities{}, time.Minute, 4, log)
		v := &vodState{m: m, s: &Session{ID: "s", Dir: t.TempDir()}, ctx: context.Background(),
			spec: Spec{BurnIn: -1, VOD: &VODOptions{}}, bounds: gridPlan(180e6, 6e6), opts: VODOptions{FrameRate: 24}}
		r := tc.run
		r.done = make(chan struct{})
		r.cancel = sync.OnceFunc(func() { close(r.done) })
		if !r.trimmed {
			for at := r.startUs; at < r.reached; at += 6e6 {
				v.pieces = append(v.pieces, &vodPiece{start: at, end: at + 6e6, run: r})
			}
		}
		v.run = r
		start := time.Now()
		_, err := v.Segment(context.Background(), tc.k)
		if err == nil || !strings.Contains(err.Error(), tc.want) {
			t.Errorf("%s: err = %v, want %q", tc.name, err, tc.want)
		}
		if d := time.Since(start); d > time.Second {
			t.Errorf("%s: took %v", tc.name, d)
		}
	}
}
