package trickplay

import (
	"context"
	"image"
	"image/color"
	"image/jpeg"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/ffexec"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/probe/probetest"
)

// The command line decodes keyframes only, seeks to the keyframe at or
// before a sheet's start, and tone maps HDR after scaling.
func TestSheetArgs(t *testing.T) {
	t.Parallel()
	g := New(nil, nil, nil, slog.New(slog.DiscardHandler), Options{})
	args := strings.Join(g.sheetArgs(sheetJob{path: "/m/a.mkv", sheet: 0, tiles: 100, out: "/t/000.jpg"}), " ")
	for _, want := range []string{"-skip_frame nokey -i /m/a.mkv", "-map 0:v:0 -an -sn -dn",
		"fps=fps=1000/10000:start_time=0:round=up,scale=320:trunc(ow/dar/2)*2,setsar=1,tpad=stop=100:stop_mode=clone,trim=end_frame=100,tpad=stop=100:stop_mode=add:color=black,tile=10x10",
		"-frames:v 1 -q:v 5 -f image2 -update 1 -y /t/000.jpg.part"} {
		if !strings.Contains(args, want) {
			t.Errorf("sheet 0 args lack %q:\n%s", want, args)
		}
	}
	if strings.Contains(args, "-ss") || strings.Contains(args, "zscale") {
		t.Errorf("sheet 0 of an SDR file seeks or tone maps:\n%s", args)
	}
	args = strings.Join(g.sheetArgs(sheetJob{path: "/m/a.mkv", sheet: 3, tiles: 24, tone: ToneZscale, out: "/t/003.jpg"}), " ")
	for _, want := range []string{"-noaccurate_seek -ss 3000.000 -i /m/a.mkv", "setsar=1,zscale=t=linear", "tonemap=tonemap=hable",
		"format=yuv420p,tpad=stop=24:stop_mode=clone,trim=end_frame=24,tpad=stop=100:stop_mode=add:color=black,tile=10x10"} {
		if !strings.Contains(args, want) {
			t.Errorf("sheet 3 of an HDR file lacks %q:\n%s", want, args)
		}
	}
	// Dolby Vision 5 as transcodes do it: tonemapx before scaling, or the
	// base layer tagged HDR10 for zscale.
	vf := func(tone string) string {
		a := g.sheetArgs(sheetJob{path: "/m/a.mkv", tiles: 100, tone: tone, out: "/t/000.jpg"})
		for i := range a {
			if a[i] == "-vf" {
				v, _, _ := strings.Cut(a[i+1], ",tpad=")
				return v
			}
		}
		return ""
	}
	const fps, scale = "fps=fps=1000/10000:start_time=0:round=up", "scale=320:trunc(ow/dar/2)*2,setsar=1"
	for tone, want := range map[string]string{
		ToneSDR:      fps + "," + scale,
		TonePlain:    fps + "," + scale,
		ToneZscale:   fps + "," + scale + "," + strings.Join(playback.ToneMapFilters(), ",") + ",format=yuv420p",
		ToneDVBase:   fps + "," + scale + "," + playback.DVBaseAsHDR10Filter + "," + strings.Join(playback.ToneMapFilters(), ",") + ",format=yuv420p",
		ToneTonemapx: fps + "," + playback.DoviToneMapFilter + "," + scale,
	} {
		if got := vf(tone); got != want {
			t.Errorf("%q:\n got %s\nwant %s", tone, got, want)
		}
	}
}

// One real run per kind of file: H.264, HEVC and HDR10 HEVC, each 70 s
// long with a keyframe every 2 s, made into tiles 64 px wide every 2 s, 3 ×
// 2 to a sheet: 35 tiles on 6 sheets, the last with 5. The HDR file's tiles
// are tone mapped where ffmpeg can, and come out neither black nor blown
// out.
func TestRealSheets(t *testing.T) {
	t.Parallel()
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not installed")
	}
	encoders := run(t, ffmpeg, "-hide_banner", "-encoders")
	filters := run(t, ffmpeg, "-hide_banner", "-filters")
	toneMap := strings.Contains(filters, " zscale ") && strings.Contains(filters, " tonemap ")
	layout := Layout{IntervalMs: 2000, Width: 64, Columns: 3, Rows: 2, Quality: 5}
	x265 := "log-level=error:hdr10=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:" +
		"master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1):max-cll=1000,400"
	type clip struct {
		name, hdr string
		args      []string
	}
	var clips []clip
	switch {
	case strings.Contains(encoders, " libx264 "):
		clips = append(clips, clip{"h264.mkv", "", []string{"-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p"}})
	default:
		clips = append(clips, clip{"mpeg4.mkv", "", []string{"-c:v", "mpeg4"}})
	}
	if strings.Contains(encoders, " libx265 ") {
		clips = append(clips,
			clip{"hevc.mkv", "", []string{"-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-x265-params", "log-level=error"}},
			clip{"hdr10.mkv", "hdr10", []string{"-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le", "-x265-params", x265}})
	} else {
		t.Log("no libx265: HEVC and HDR10 not checked")
	}
	dir := t.TempDir()
	var wg sync.WaitGroup
	for _, c := range clips {
		wg.Go(func() {
			args := append([]string{"-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=10", "-t", "70", "-g", "20"},
				c.args...)
			if b, err := exec.Command(ffmpeg, append(args, filepath.Join(dir, c.name))...).CombinedOutput(); err != nil { //nolint:gosec // G204: the test's own ffmpeg
				t.Errorf("make %s: %v\n%s", c.name, err, b)
			}
		})
	}
	wg.Wait()
	if t.Failed() {
		t.FailNow()
	}

	l := newTestLib(t)
	l.g = New(l.q, l.conn, events.NewBus(), slog.New(slog.DiscardHandler), Options{FFmpeg: ffmpeg, Dir: filepath.Join(t.TempDir(), "trickplay"),
		Enabled: true, Layout: layout, ToneMapping: func() ToneMapping { return ToneMapping{CPU: toneMap} }})
	l.g.free = func(string) (uint64, error) { return 100 << 30, nil }
	for i, c := range clips {
		f := l.add(c.name, 70_000, time.Now().Add(time.Duration(-i)*time.Hour), withHDR(c.hdr, 0))
		f.Path = filepath.Join(dir, c.name)
		if _, err := l.conn.ExecContext(t.Context(), `UPDATE media_files SET path = ? WHERE id = ?`, f.Path, f.ID); err != nil {
			t.Fatal(err)
		}
	}
	start := time.Now()
	if err := l.g.RunOnce(t.Context()); err != nil {
		t.Fatal(err)
	}
	t.Logf("%d files in %s", len(clips), time.Since(start).Round(time.Millisecond))
	for _, c := range clips {
		f, err := l.q.GetMediaFileByPath(t.Context(), filepath.Join(dir, c.name))
		if err != nil {
			t.Fatal(err)
		}
		r, _ := l.row(f.ID)
		if r.Status != "done" || r.Tiles != 35 || r.Sheets != 6 || r.Width != 64 || r.Height != 36 {
			t.Errorf("%s: %+v", c.name, r)
			continue
		}
		folder := FileDir(l.g.dir, f.ID, r.Version)
		for s := range 6 {
			img := decode(t, filepath.Join(folder, SheetName(s)))
			if b := img.Bounds(); b.Dx() != 192 || b.Dy() != 72 {
				t.Errorf("%s sheet %d is %d × %d, want 192 × 72", c.name, s, b.Dx(), b.Dy())
			}
		}
		// The last sheet has its 5 tiles, the last one a picture, and its
		// sixth cell black.
		last := decode(t, filepath.Join(folder, SheetName(5)))
		if y := meanLuma(last, image.Rect(64, 36, 128, 72)); y < 30 {
			t.Errorf("%s: the last tile is dark (mean luma %.0f)", c.name, y)
		}
		if y := meanLuma(last, image.Rect(128, 36, 192, 72)); y > 20 {
			t.Errorf("%s: the cell after the last tile is not black (mean luma %.0f)", c.name, y)
		}
		if c.hdr == "" || !toneMap {
			continue
		}
		// The first tile of the first sheet, tone mapped.
		if y := meanLuma(decode(t, filepath.Join(folder, SheetName(0))), image.Rect(0, 0, 64, 36)); y < 60 || y > 180 {
			t.Errorf("%s: tone mapped tile's mean luma %.0f, want 60 to 180", c.name, y)
		}
	}
	if slices.ContainsFunc(clips, func(c clip) bool { return c.hdr != "" }) && !toneMap {
		t.Log("ffmpeg has no zscale: HDR tone mapping not checked")
	}
}

// Dolby Vision 5 tiles, from H1's test clip (10-bit PQ HEVC with a profile
// 5 record and no RPU): through tonemapx where ffmpeg has it, else from the
// base layer tagged HDR10, as transcodes do. Either way a picture, neither
// black nor blown out.
func TestRealDolbyVision5Sheets(t *testing.T) {
	t.Parallel()
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not installed")
	}
	clip := probetest.DolbyVisionClip(t, ffmpeg, probetest.DolbyVision{Profile: 5, Level: 6, Compat: 0, Entry: "dvh1"})
	filters := run(t, ffmpeg, "-hide_banner", "-filters")
	have := map[string]bool{
		ToneDVBase:   strings.Contains(filters, " zscale ") && strings.Contains(filters, " tonemap "),
		ToneTonemapx: strings.Contains(filters, " tonemapx ") && strings.Contains(run(t, ffmpeg, "-hide_banner", "-h", "filter=tonemapx"), "apply_dovi"),
	}
	five := int64(5)
	for tone, ok := range have {
		if !ok {
			t.Logf("ffmpeg cannot map Dolby Vision 5 as %s: not checked", tone)
			continue
		}
		l := newTestLib(t)
		l.g = New(l.q, l.conn, events.NewBus(), slog.New(slog.DiscardHandler), Options{FFmpeg: ffmpeg, Dir: filepath.Join(t.TempDir(), "trickplay"),
			Enabled: true, Layout: Layout{IntervalMs: 1000, Width: 64, Columns: 2, Rows: 2, Quality: 5}})
		l.g.free = func(string) (uint64, error) { return 100 << 30, nil }
		f := l.add("dv5.mp4", 3000, time.Now(), withHDR("dolbyvision", 5))
		row := db.ListFilesNeedingTrickplayRow{ID: f.ID, Path: clip, Size: f.Size, Mtime: f.Mtime, DurationMs: f.DurationMs, Hdr: "dolbyvision", DvProfile: &five}
		tm := ToneMapping{CPU: true, Dovi: tone == ToneTonemapx}
		start := time.Now()
		if err := l.g.file(t.Context(), row, tm); err != nil {
			t.Fatalf("%s: %v", tone, err)
		}
		t.Logf("%s: a 3-tile sheet in %s", tone, time.Since(start).Round(time.Millisecond))
		r, _ := l.row(f.ID)
		if r.Status != "done" || r.ToneMap != tone || r.Tiles != 3 || r.Version != Version(f.Size, f.Mtime, tone) {
			t.Fatalf("%s: %+v", tone, r)
		}
		sheet := decode(t, filepath.Join(FileDir(l.g.dir, f.ID, r.Version), SheetName(0)))
		if y := meanLuma(sheet, image.Rect(0, 0, 64, 36)); y < 40 || y > 200 {
			t.Errorf("%s: first tile's mean luma %.0f, want 40 to 200", tone, y)
		}
	}
}

func run(t *testing.T, bin string, args ...string) string {
	t.Helper()
	out, err := ffexec.Command(context.Background(), bin, args...).Output()
	if err != nil {
		t.Fatal(err)
	}
	return string(out)
}

func decode(t *testing.T, path string) image.Image {
	t.Helper()
	f, err := os.Open(path) //nolint:gosec // G304: the test's own file
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = f.Close() }()
	img, err := jpeg.Decode(f)
	if err != nil {
		t.Fatal(err)
	}
	return img
}

func meanLuma(img image.Image, r image.Rectangle) float64 {
	var sum float64
	for y := r.Min.Y; y < r.Max.Y; y++ {
		for x := r.Min.X; x < r.Max.X; x++ {
			sum += float64(color.GrayModel.Convert(img.At(x, y)).(color.Gray).Y)
		}
	}
	return sum / float64(r.Dx()*r.Dy())
}
