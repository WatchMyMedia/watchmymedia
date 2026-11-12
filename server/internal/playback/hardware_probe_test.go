package playback

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"slices"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/probe"
)

type fakeHardwareProbe struct {
	fail       map[string]error
	cpuToneMap bool
	noHEVC     bool
	incomplete bool
	// startup comes before the 4K pipeline's first frame; fps is its rate
	// after that (96 when unset).
	startup time.Duration
	fps     float64
	calls   [][]string
}

func (f *fakeHardwareProbe) run(_ context.Context, a []string, _ time.Duration) hardwareProbeResult {
	f.calls = append(f.calls, slices.Clone(a))
	var result hardwareProbeResult
	has := func(s string) bool { return slices.Contains(a, s) }
	switch {
	case has("-encoders"):
		result.output = " V..... libx264 x264\n V..... h264_nvenc NVIDIA\n V..... hevc_nvenc NVIDIA\n V..... h264_qsv Intel\n V..... h264_amf AMD\n"
		if !f.noHEVC {
			result.output += " V..... libx265 HEVC\n"
		}
	case has("-filters"):
		result.output = " ... subtitles text\n ... overlay image\n"
		if f.cpuToneMap {
			result.output += " ... zscale scale\n ... tonemap map\n"
		}
	case has("-bsfs"):
		return result
	case has("matroska"):
		if err := f.fail["fixture"]; err != nil {
			result.err = err
			return result
		}
		result.err = os.WriteFile(a[len(a)-1], []byte("synthetic stand-in input"), 0o600)
	default:
		stage := "encode"
		if !has("lavfi") {
			stage = "decode-scale"
			if strings.Contains(strings.Join(a, " "), "tonemap_") {
				stage = "decode-tonemap"
			}
			for i, v := range a {
				if v == "-frames:v" && i+1 < len(a) && a[i+1] == "48" {
					stage = "4k-pipeline"
				}
			}
		}
		result.err = f.fail[stage]
		if stage == "4k-pipeline" {
			fps := f.fps
			if fps == 0 {
				fps = 96
			}
			result.output, result.at = progressOutput(f.startup, fps)
			if f.incomplete {
				result.output, result.at = "frame=0\nprogress=end\n", []time.Duration{time.Second, time.Second}
			}
		}
	}
	return result
}

// progressOutput is what ffmpeg's -progress writes every 50 ms (-stats_period)
// for 48 frames that start after startup and come at fps, with when each line
// arrived.
func progressOutput(startup time.Duration, fps float64) (string, []time.Duration) {
	var b strings.Builder
	var at []time.Duration
	block := func(t time.Duration, frames int, progress string) {
		fmt.Fprintf(&b, "frame=%d\nfps=0.00\nprogress=%s\n", frames, progress)
		at = append(at, t, t, t)
	}
	done := startup + time.Duration(48/fps*float64(time.Second))
	for t := 50 * time.Millisecond; t < done; t += 50 * time.Millisecond {
		frames := 0
		if t > startup {
			frames = int((t - startup).Seconds() * fps)
		}
		block(t, frames, "continue")
	}
	block(done, 48, "end")
	return b.String(), at
}

func TestHardwareProbesGateTheActualPipeline(t *testing.T) {
	t.Parallel()
	for _, tc := range []struct {
		name      string
		f         fakeHardwareProbe
		gpu, tone bool
		height    int
	}{
		{"complete GPU pipeline", fakeHardwareProbe{}, true, true, 2160},
		{"decoder fails", fakeHardwareProbe{fail: map[string]error{"decode-scale": errors.New("decoder unavailable")}}, false, false, 1080},
		{"tone mapper fails", fakeHardwareProbe{fail: map[string]error{"decode-tonemap": errors.New("tone map unavailable")}}, true, false, 1080},
		{"CPU filters measured too", fakeHardwareProbe{cpuToneMap: true, fail: map[string]error{"decode-tonemap": errors.New("no GPU tone map")}}, true, false, 2160},
		{"slow full pipeline", fakeHardwareProbe{fps: 24}, true, true, 1080},
		{"just below the bar", fakeHardwareProbe{fps: 47}, true, true, 1080},
		{"slow start-up, fast frames", fakeHardwareProbe{startup: 3 * time.Second, fps: 60}, true, true, 2160},
		{"quick start-up, slow frames", fakeHardwareProbe{startup: 10 * time.Millisecond, fps: 40}, true, true, 1080},
		{"not all frames encoded", fakeHardwareProbe{incomplete: true}, true, true, 1080},
		{"HEVC input unavailable", fakeHardwareProbe{noHEVC: true}, true, false, 1080},
		{"benchmark timeout", fakeHardwareProbe{fail: map[string]error{"4k-pipeline": context.DeadlineExceeded}}, true, true, 1080},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var low [][]string
			lowRun := func(ctx context.Context, a []string, d time.Duration) hardwareProbeResult {
				low = append(low, a)
				return tc.f.run(ctx, a, d)
			}
			c := detectHardware(t.Context(), "fake-ffmpeg", "nvenc", nil, slog.New(slog.DiscardHandler), tc.f.run, lowRun)
			if c.Encoder != "h264_nvenc" || c.GPUFilters != tc.gpu || c.gpuToneMap != tc.tone || c.MaxHeight != tc.height {
				t.Fatalf("caps=%+v", c)
			}
			// Only building the 4K input runs below normal priority.
			for _, a := range low {
				if !building4KInput(a) {
					t.Fatalf("ran below normal priority: %v", a)
				}
			}
			if len(c.Diagnostics) == 0 {
				t.Fatal("probe results missing")
			}
			for _, a := range tc.f.calls {
				if slices.Contains(a, "48") && slices.Contains(a, "-progress") {
					if slices.Contains(a, "lavfi") || !strings.Contains(strings.Join(a, " "), "hevc10-hdr.mkv") {
						t.Fatalf("benchmark did not decode the real input: %v", a)
					}
				}
			}
		})
	}
}

func building4KInput(a []string) bool {
	return slices.Contains(a, "matroska") && slices.Contains(a, "libx265")
}

// A Mac whose cores are all busy starves a build below normal priority: it
// is built again at normal priority, and only when it timed out.
func TestStarved4KInputIsBuiltAgainAtNormalPriority(t *testing.T) {
	t.Parallel()
	for _, tc := range []struct {
		name string
		// low and normal are what the build comes to at each priority.
		low, normal error
		// over: detection itself ends during the low build.
		over    bool
		rebuilt bool
		height  int
	}{
		{"starved below normal priority", context.DeadlineExceeded, nil, false, true, 2160},
		{"times out at normal priority too", context.DeadlineExceeded, context.DeadlineExceeded, false, true, 1080},
		{"ffmpeg fails", errors.New("ffmpeg exited: exit status 1"), nil, false, false, 1080},
		{"detection ends", context.DeadlineExceeded, nil, true, false, 1080},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ctx, cancel := context.WithCancel(t.Context())
			defer cancel()
			f := fakeHardwareProbe{}
			// A build cut short leaves part of a file, which must not be
			// mistaken for the input.
			partial := func(a []string, err error) hardwareProbeResult {
				if werr := os.WriteFile(a[len(a)-1], []byte("part of"), 0o600); werr != nil {
					t.Error(werr)
				}
				return hardwareProbeResult{err: err}
			}
			var lows, rebuilds int
			low := func(_ context.Context, a []string, _ time.Duration) hardwareProbeResult {
				if !building4KInput(a) {
					t.Errorf("ran below normal priority: %v", a)
				}
				lows++
				if tc.over {
					cancel()
				}
				return partial(a, tc.low)
			}
			run := func(ctx context.Context, a []string, d time.Duration) hardwareProbeResult {
				if building4KInput(a) {
					rebuilds++
					if tc.normal != nil {
						return partial(a, tc.normal)
					}
				}
				return f.run(ctx, a, d)
			}
			c := detectHardware(ctx, "fake-ffmpeg", "nvenc", nil, slog.New(slog.DiscardHandler), run, low)
			want := 0
			if tc.rebuilt {
				want = 1
			}
			if lows != 1 || rebuilds != want {
				t.Fatalf("built %d times below normal priority, %d at normal priority", lows, rebuilds)
			}
			if c.Encoder != "h264_nvenc" || !c.GPUFilters || c.gpuToneMap != (tc.height == 2160) || c.MaxHeight != tc.height {
				t.Fatalf("caps=%+v", c)
			}
			var builds []HardwareDiagnostic
			for _, d := range c.Diagnostics {
				if d.Encoder == "libx265" {
					builds = append(builds, d)
				}
			}
			if len(builds) != 1 || builds[0].OK != (tc.height == 2160) {
				t.Fatalf("4K input diagnostics=%+v", builds)
			}
			for _, a := range f.calls {
				if slices.Contains(a, "-progress") && tc.height != 2160 {
					t.Fatalf("benchmarked an input that was not built: %v", a)
				}
			}
		})
	}
}

func TestSelectedAdapterScopesProbesAndDiagnostics(t *testing.T) {
	t.Parallel()
	n := 2
	f := fakeHardwareProbe{fail: map[string]error{"encode": errors.New(strings.Repeat("driver unavailable ", 500))}}
	c := detectHardware(t.Context(), "fake-ffmpeg", "nvenc", &n, slog.New(slog.DiscardHandler), f.run, f.run)
	if c.HWAccel != "software" || c.Device != nil || c.MaxHeight != 1080 {
		t.Fatalf("failed adapter was selected: %+v", c)
	}
	for _, d := range c.Diagnostics {
		if d.Device == nil || *d.Device != 2 || len([]rune(d.Detail)) > 2000 {
			t.Fatalf("diagnostic=%+v", d)
		}
	}
	for _, a := range f.calls {
		if slices.Contains(a, "h264_qsv") || slices.Contains(a, "h264_amf") {
			t.Fatal("CUDA index was applied to another backend")
		}
		if slices.Contains(a, "h264_nvenc") && !slices.Contains(a, "-gpu") {
			t.Fatalf("encoder omitted adapter: %v", a)
		}
	}
}

func TestWaitingHardwareProbeStopsWithItsContext(t *testing.T) {
	// Sequential: parallel probes have not started while this owns the slot.
	hardwareProbeSlot <- struct{}{}
	defer func() { <-hardwareProbeSlot }()
	ctx, cancel := context.WithTimeout(t.Context(), 20*time.Millisecond)
	defer cancel()
	f := fakeHardwareProbe{}
	c := detectHardware(ctx, "fake-ffmpeg", "nvenc", nil, slog.New(slog.DiscardHandler), f.run, f.run)
	if ctx.Err() == nil || c.GPUFilters || c.MaxHeight != 1080 {
		t.Fatalf("waiting probe ignored cancellation: %+v", c)
	}
	last := c.Diagnostics[len(c.Diagnostics)-1]
	if last.Stage != "selection" || last.OK || !strings.Contains(last.Detail, "deadline") {
		t.Fatalf("missing cancellation diagnostic: %+v", last)
	}
}

func TestEncodedProbeFixture(t *testing.T) {
	// Keep this CPU-heavy functional check ahead of the parallel session tests.
	ffmpeg := needFFmpeg(t)
	p := hardwareProbe{ctx: t.Context(), ffmpeg: ffmpeg, log: slog.New(slog.DiscardHandler), run: probeRunner(ffmpeg, false), low: probeRunner(ffmpeg, true)}
	p.encoders = p.run(t.Context(), []string{"-encoders"}, time.Second*15).output
	if !strings.Contains(p.encoders, " libx265 ") {
		t.Skip("libx265 unavailable")
	}
	input, err := p.fixture(true)
	defer func() {
		if p.dir != "" {
			_ = os.RemoveAll(p.dir)
		}
	}()
	if err != nil {
		t.Fatal(err)
	}
	info, err := (&probe.Prober{Path: "ffprobe"}).Probe(t.Context(), input)
	if err != nil || len(info.Video) != 1 {
		t.Fatalf("probe: %v, %v", info, err)
	}
	v := info.Video[0]
	if v.Codec != "hevc" || v.BitDepth != 10 || v.Width != 3840 || v.Height != 2160 || v.HDR != "hdr10" {
		t.Fatalf("fixture is not HEVC Main 10 4K HDR: %+v", v)
	}
	// A real decoder must read all 48 frames of the generated HEVC Main 10 input.
	r := p.run(t.Context(), []string{"-stream_loop", "-1", "-i", input, "-vf", "format=yuv420p10le", "-frames:v", "48", "-progress", "pipe:1", "-f", "null", "-"}, time.Second*30)
	if r.err != nil || !strings.Contains(r.output, "frame=48") {
		t.Fatalf("fixture could not be decoded: %v, %s", r.err, r.output)
	}
	filters := p.run(t.Context(), []string{"-filters"}, 15*time.Second).output
	if strings.Contains(filters, " zscale ") && strings.Contains(filters, " tonemap ") {
		c := Capabilities{Encoder: "libx264", HWAccel: "software", zscale: true}
		r = probeRunner(ffmpeg, false)(t.Context(), p.pipelineBenchmarkArgs(c, input), 2*time.Minute)
		if r.err != nil || !strings.Contains(r.output, "frame=48") {
			t.Fatalf("real HDR-to-SDR decode/filter/encode failed: %v, %s", r.err, r.output)
		}
		// Real progress blocks come often enough to time the frames apart from start-up.
		if fps, err := pipelineFPS(r); err != nil || fps <= 0 {
			t.Fatalf("frames were not timed: %v, %v, %s", fps, err, r.output)
		}
	}
}

func TestPipelineFPSTimesOnlyTheFrames(t *testing.T) {
	t.Parallel()
	block := func(frames int, progress string) string {
		return fmt.Sprintf("frame=%d\nout_time_us=0\nprogress=%s\n", frames, progress)
	}
	stamps := func(ts ...time.Duration) []time.Duration {
		var at []time.Duration
		for _, t := range ts {
			at = append(at, t, t, t)
		}
		return at
	}
	for _, tc := range []struct {
		name   string
		output string
		at     []time.Duration
		fps    float64
		err    bool
	}{
		// Two seconds of start-up, then 24 frames in half a second.
		{"start-up not counted", block(0, "continue") + block(24, "continue") + block(48, "end"),
			stamps(time.Second, 2*time.Second, 2*time.Second+500*time.Millisecond), 48, false},
		{"below the bar does not round up", block(0, "continue") + block(24, "continue") + block(48, "end"),
			stamps(time.Second, 2*time.Second, 2*time.Second+500*time.Millisecond+time.Microsecond), 47.9, false},
		{"all frames in the last block", block(0, "continue") + block(48, "end"),
			stamps(time.Second, 2*time.Second), 0, true},
		{"no end", block(0, "continue") + block(48, "continue"),
			stamps(time.Second, 2*time.Second), 0, true},
		{"frames missing", block(10, "continue") + block(40, "end"),
			stamps(time.Second, 2*time.Second), 0, true},
		{"no arrival times", block(10, "continue") + block(48, "end"), nil, 0, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			fps, err := pipelineFPS(hardwareProbeResult{output: tc.output, at: tc.at})
			if fps != tc.fps || (err != nil) != tc.err {
				t.Fatalf("fps=%v err=%v, want %v (error %v)", fps, err, tc.fps, tc.err)
			}
		})
	}
}
