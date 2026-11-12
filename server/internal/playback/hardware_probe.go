package playback

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"math"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"time"

	"watchmymedia/server/internal/ffexec"
	"watchmymedia/server/internal/hwdevice"
)

// HardwareDiagnostic is kept for administrators, never in ordinary playback
// capabilities. Probes only read synthetic files owned by this detection.
type HardwareDiagnostic struct {
	Encoder string  `json:"encoder"`
	Stage   string  `json:"stage"`
	OK      bool    `json:"ok"`
	Device  *int    `json:"device,omitempty" required:"false"`
	Detail  string  `json:"detail,omitempty" required:"false"`
	FPS     float64 `json:"fps,omitempty" required:"false"`
}

type hardwareProbeResult struct {
	output string
	// at is when each line of output arrived, from the start of the run.
	at  []time.Duration
	err error
}
type hardwareProbeRun func(context.Context, []string, time.Duration) hardwareProbeResult

type hardwareProbe struct {
	ctx    context.Context
	ffmpeg string
	device *int
	log    *slog.Logger
	// goos is the platform probed, and node the render node for a device
	// index on it (renderNode); tests pass their own.
	goos string
	node func(*int) string
	// run runs ffmpeg at normal priority: the checks that take milliseconds
	// and the timed 4K pipeline. low runs it below normal priority: building
	// the 4K input (fixture), the one probe that is real CPU work.
	run, low hardwareProbeRun
	encoders string
	dir      string
	// fixtures is what building each synthetic input came to.
	fixtures    map[string]error
	diagnostics []HardwareDiagnostic
}

// platform is the GOOS probed: the one given, else this machine's.
func (p *hardwareProbe) platform() string {
	if p.goos == "" {
		return runtime.GOOS
	}
	return p.goos
}

// renderNodeFor is the render node for a device index on the platform
// probed: the function given, else this machine's nodes.
func (p *hardwareProbe) renderNodeFor(device *int) string {
	if p.node == nil {
		return renderNode(device)
	}
	return p.node(device)
}

// Concurrent detections in one process must not benchmark each other's load.
// Waiting is cancellable, including when a saved preference supersedes a probe.
var hardwareProbeSlot = make(chan struct{}, 1)

// probeRunner runs ffmpeg below normal priority when low is set, so that
// detection does not compete with playback.
func probeRunner(ffmpeg string, low bool) hardwareProbeRun {
	return func(ctx context.Context, args []string, timeout time.Duration) hardwareProbeResult {
		ctx, cancel := context.WithTimeout(ctx, timeout)
		defer cancel()
		cmd := ffexec.Command(ctx, ffmpeg, ffexec.FFmpegArgs(args...)...)
		cmd.LowPriority = low
		out := lineClock{start: time.Now()}
		cmd.Stdout = &out
		err := cmd.Run()
		if ctx.Err() != nil {
			err = ctx.Err()
		}
		return hardwareProbeResult{output: out.text.String(), at: out.at, err: err}
	}
}

// lineClock keeps what ffmpeg writes and when each line of it arrived.
type lineClock struct {
	start time.Time
	text  strings.Builder
	at    []time.Duration
}

func (w *lineClock) Write(b []byte) (int, error) {
	now := time.Since(w.start)
	for range bytes.Count(b, []byte("\n")) {
		w.at = append(w.at, now)
	}
	return w.text.Write(b)
}

func (p *hardwareProbe) record(encoder, stage string, r hardwareProbeResult, fps float64) bool {
	d := HardwareDiagnostic{Encoder: encoder, Stage: stage, OK: r.err == nil, Device: hwdevice.Index(p.device), FPS: fps}
	if r.err != nil {
		detail := r.err.Error()
		if p.dir != "" {
			detail = strings.ReplaceAll(detail, p.dir, "<hardware-probe>")
		}
		detail = strings.ReplaceAll(detail, p.ffmpeg, "ffmpeg")
		// ffmpeg says "Permission denied" opening a render node the server's
		// user may not read, the usual Docker failure; say what to do.
		if needsRenderNode(hwaccelOf(encoder), p.platform()) && strings.Contains(detail, "ermission denied") {
			if node := p.renderNodeFor(p.device); node != "" {
				detail += fmt.Sprintf(" (the server's user cannot open %s: it needs the group that owns the device, or in Docker pass --device /dev/dri and let the entrypoint add the group)", node)
			}
		}
		// Error tails are already bounded by ffexec. Also bound non-process errors.
		d.Detail = string([]rune(detail)[:min(len([]rune(detail)), 2000)])
	}
	p.diagnostics = append(p.diagnostics, d)
	return d.OK
}

// detectHardware runs the probes through run, at normal priority, except
// building the 4K input, which starts on low. The listings and one-frame
// checks take milliseconds, so they cannot hold up playback; below normal
// priority, on an M5 whose cores were all busy, listing the encoders took
// 18 s, past its timeout, and detection fell back to software.
func detectHardware(ctx context.Context, ffmpeg, pref string, device *int, log *slog.Logger, run, low hardwareProbeRun) Capabilities {
	return detectHardwareOn(ctx, ffmpeg, pref, device, log, run, low, runtime.GOOS, renderNode)
}

// detectHardwareOn is detectHardware for a platform, with node giving the
// render node for a device index on it.
func detectHardwareOn(ctx context.Context, ffmpeg, pref string, device *int, log *slog.Logger, run, low hardwareProbeRun, goos string, node func(*int) string) Capabilities {
	c := Capabilities{FFmpeg: ffmpeg, Encoders: []string{}, Encoder: "libx264", HWAccel: "software", MaxHeight: 1080, Detected: time.Now()}
	p := hardwareProbe{ctx: ctx, ffmpeg: ffmpeg, device: hwdevice.Index(device), log: log, run: run, low: low, goos: goos, node: node}
	defer func() {
		if p.dir != "" {
			_ = os.RemoveAll(p.dir)
		}
	}()
	if err := hwdevice.ValidateOn(pref, device, goos); err != nil {
		p.record(pref, "device", hardwareProbeResult{err: err}, 0)
		c.Diagnostics = p.diagnostics
		return c
	}
	listed := run(ctx, []string{"-encoders"}, 15*time.Second)
	p.encoders = listed.output
	if listed.err != nil {
		p.record("ffmpeg", "encoders", listed, 0)
	}
	for _, e := range videoEncoders {
		if ctx.Err() != nil {
			break
		}
		if !strings.Contains(p.encoders, " "+e.name+" ") {
			continue
		}
		// A CUDA index is not a DirectX index. Do not probe other backends on it.
		if p.device != nil && e.hwaccel != "software" && e.hwaccel != pref {
			continue
		}
		// A render node the machine lacks is the whole story for VAAPI
		// (and QSV on Linux): say so once per encoder instead of letting
		// ffmpeg fail to open a device nobody named.
		if needsRenderNode(e.hwaccel, p.platform()) && p.renderNodeFor(p.device) == "" {
			missing := "no /dev/dri render node is visible; in Docker start the container with --device /dev/dri"
			if p.device != nil {
				missing = fmt.Sprintf("/dev/dri/renderD%d does not exist; the render node number counts from 0 at renderD128", 128+*p.device)
			}
			p.record(e.name, "device", hardwareProbeResult{err: errors.New(missing)}, 0)
			continue
		}
		if e.hwaccel != "software" {
			r := run(ctx, p.encoderProbeArgs(e.name), 15*time.Second)
			if !p.record(e.name, "encode", r, 0) {
				log.Info("hardware encoder not usable", "encoder", e.name, "err", r.err)
				continue
			}
		}
		c.Encoders = append(c.Encoders, e.name)
	}
	filters := run(ctx, []string{"-filters"}, 15*time.Second)
	if filters.err != nil {
		p.record("ffmpeg", "filters", filters, 0)
	}
	c.zscale = strings.Contains(filters.output, " zscale ") && strings.Contains(filters.output, " tonemap ")
	c.ToneMap = c.zscale
	c.LibASS = strings.Contains(filters.output, " subtitles ")
	c.Overlay = strings.Contains(filters.output, " overlay ")
	if strings.Contains(filters.output, " tonemapx ") {
		help := run(ctx, []string{"-h", "filter=tonemapx"}, 15*time.Second)
		c.DoviToneMap = help.err == nil && strings.Contains(help.output, "apply_dovi")
	}
	bsfs := run(ctx, []string{"-bsfs"}, 15*time.Second)
	c.doviStrip = slices.Contains(strings.Fields(bsfs.output), "dovi_rpu")
	have := func(enc string) bool { return slices.Contains(c.Encoders, enc) }
	usable := func(hw string) bool {
		e, ok := encoderOn(hw, "h264")
		if ok && have(e.name) {
			c.Encoder, c.HWAccel = e.name, hw
			return true
		}
		return false
	}
	if pref != "" && pref != "auto" {
		if !usable(pref) && pref != "software" {
			log.Warn("requested hardware encoder unavailable; using software", "requested", pref)
			p.record(pref, "selection", hardwareProbeResult{err: errors.New("requested hardware backend is unavailable on the selected adapter")}, 0)
		}
	} else {
		order := []string{"nvenc", "qsv", "vaapi"}
		switch runtime.GOOS {
		case "darwin":
			order = []string{"videotoolbox"}
		case "windows":
			order = []string{"nvenc", "qsv", "amf"}
		}
		for _, hw := range order {
			if usable(hw) {
				break
			}
		}
	}
	if c.HWAccel == "software" && !have("libx264") {
		p.record("libx264", "selection", hardwareProbeResult{err: errors.New("FFmpeg has no usable H.264 encoder; transcoding is unavailable")}, 0)
		log.Warn("ffmpeg has no usable H.264 encoder")
	}
	if c.HWAccel != "software" && ctx.Err() == nil {
		select {
		case hardwareProbeSlot <- struct{}{}:
			defer func() { <-hardwareProbeSlot }()
		case <-ctx.Done():
			p.record(c.Encoder, "selection", hardwareProbeResult{err: ctx.Err()}, 0)
			c.Diagnostics = p.diagnostics
			return c
		}
		c.Device = hwdevice.Index(p.device)
		if path, ok := pipelineOnOS(c.HWAccel, c.Device, p.platform(), p.renderNodeFor); ok {
			c.GPUFilters = p.gpuWorks(c.Encoder, path, false)
			c.gpuToneMap = c.GPUFilters && p.gpuWorks(c.Encoder, path, true)
			c.ToneMap = c.ToneMap || c.gpuToneMap
		}
		if e, ok := encoderOn(c.HWAccel, "hevc"); ok && have(e.name) {
			c.HEVCEncoder = e.name
		}
		c.Speed4K = p.speed4K(c)
		if c.Speed4K >= fourKRealtime {
			c.MaxHeight = 2160
		}
	}
	c.Diagnostics = p.diagnostics
	return c
}

func (p *hardwareProbe) encoderProbeArgs(encoder string) []string {
	// VAAPI names its render node and uploads to it even without an
	// explicit index (EncoderDeviceArgs, EncoderUploadFilter).
	input, output := encoderDeviceArgsOn(encoder, p.device, p.platform(), p.renderNodeFor)
	ten := strings.HasPrefix(encoder, "hevc_")
	vf := "format=nv12"
	if ten {
		vf = "format=p010le"
	}
	if upload := EncoderUploadFilter(encoder, p.device, ten); upload != "" {
		vf = upload
	}
	a := append(input, "-f", "lavfi", "-i", "color=black:s=256x256:d=0.1", "-frames:v", "1", "-vf", vf, "-c:v", encoder)
	if ten {
		a = append(a, "-profile:v", "main10")
	}
	a = append(a, output...)
	return append(a, "-f", "null", "-")
}

// fixture makes an encoded input, not a raw lavfi input masquerading as a
// decoder probe. The larger one is HEVC Main 10 with HDR10 colour metadata.
func (p *hardwareProbe) fixture(hdr bool) (string, error) {
	codec, name, source, frames := "libx264", "h264.mkv", "color=black:s=512x288:r=24", 2
	if hdr {
		codec, name, source, frames = "libx265", "hevc10-hdr.mkv", "testsrc2=s=3840x2160:r=24", 12
	}
	if !strings.Contains(p.encoders, " "+codec+" ") {
		return "", fmt.Errorf("%s is unavailable; cannot create the synthetic decoder input", codec)
	}
	if err, built := p.fixtures[name]; built {
		if err != nil {
			return "", err
		}
		return filepath.Join(p.dir, name), nil
	}
	if p.dir == "" {
		var err error
		p.dir, err = os.MkdirTemp("", "watchmymedia-hardware-")
		if err != nil {
			return "", err
		}
	}
	path := filepath.Join(p.dir, name)
	args := []string{"-y", "-f", "lavfi", "-i", source, "-frames:v", strconv.Itoa(frames), "-an", "-c:v", codec, "-preset", "ultrafast"}
	if hdr {
		args = append(args, "-vf", "format=yuv420p10le,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc", "-pix_fmt", "yuv420p10le", "-x265-params", "pools=1:frame-threads=1:log-level=error:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc", "-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc")
	} else {
		args = append(args, "-pix_fmt", "yuv420p")
	}
	args = append(args, "-f", "matroska", path)
	// The H.264 input takes milliseconds. The 4K one is CPU work even for a
	// hardware probe (1.4 s of one core on an M5), so it starts below normal
	// priority, and is built again at normal priority when that times out:
	// on an M5 whose cores were all busy, the low build had not finished in
	// a minute, kept to the busy efficiency cores, while a normal one took
	// 5 s. Without the input there is no 4K and no GPU tone mapping.
	var r hardwareProbeResult
	if hdr {
		r = p.low(p.ctx, args, 20*time.Second)
		if errors.Is(r.err, context.DeadlineExceeded) && p.ctx.Err() == nil {
			p.log.Info("4K probe input timed out below normal priority; building it at normal priority")
			r = p.run(p.ctx, args, 60*time.Second)
		}
	} else {
		r = p.run(p.ctx, args, 15*time.Second)
	}
	if r.err == nil {
		if st, err := os.Stat(path); err != nil {
			r.err = err
		} else if !st.Mode().IsRegular() || st.Size() == 0 {
			r.err = errors.New("synthetic input was not written")
		}
	}
	if r.err != nil {
		// A build that was cut short can leave part of a file behind.
		_ = os.Remove(path)
	}
	if p.fixtures == nil {
		p.fixtures = map[string]error{}
	}
	// gpuWorks and speed4K both want the 4K input; one that failed is not
	// built again.
	p.fixtures[name] = r.err
	if !p.record(codec, "fixture", r, 0) {
		return "", r.err
	}
	return path, nil
}

// Requiring the hardware pixel format before hwupload makes silent software
// decode fallback fail this probe; it does not add a GPU/CPU frame round trip.
func strictDecodeFilter(p pipeline) string {
	for i, a := range p.decode {
		if a == "-hwaccel_output_format" && i+1 < len(p.decode) {
			return "format=" + p.decode[i+1]
		}
	}
	return "null"
}

func (p *hardwareProbe) gpuWorks(encoder string, path pipeline, toneMap bool) bool {
	stage := "decode-scale"
	if toneMap {
		stage = "decode-tonemap"
	}
	input, err := p.fixture(toneMap)
	if err != nil {
		return p.record(encoder, stage, hardwareProbeResult{err: err}, 0)
	}
	spec := Spec{Video: VideoPlan{Codec: "h264", Width: 256, Height: 144, BitDepth: 8}}
	if toneMap {
		spec.Video.HDR, spec.Video.BitDepth, spec.Video.ToneMap = "hdr10", 10, true
	}
	a := append(append([]string(nil), path.decode...), path.devices...)
	a = append(a, "-i", input, "-frames:v", "1", "-an", "-vf", strictDecodeFilter(path)+","+strings.Join(gpuChain(path, spec, false), ","), "-c:v", encoder)
	_, output := encoderDeviceArgsOn(encoder, p.device, p.platform(), p.renderNodeFor)
	a = append(a, output...)
	a = append(a, "-f", "null", "-")
	return p.record(encoder, stage, p.run(p.ctx, a, 15*time.Second), 0)
}

func (p *hardwareProbe) pipelineBenchmarkArgs(c Capabilities, input string) []string {
	path, gpu := pipelineOnOS(c.HWAccel, c.Device, p.platform(), p.renderNodeFor)
	gpu = gpu && c.GPUFilters && c.gpuToneMap
	var a, f []string
	if gpu {
		a = append(append(a, path.decode...), path.devices...)
		spec := Spec{Video: VideoPlan{Codec: "h264", Width: 3840, Height: 2160, HDR: "hdr10", BitDepth: 10, ToneMap: true}}
		f = append([]string{strictDecodeFilter(path)}, gpuChain(path, spec, false)...)
	} else {
		init, _ := encoderDeviceArgsOn(c.Encoder, c.Device, p.platform(), p.renderNodeFor)
		a = append(a, init...)
		f = append([]string{"scale=3840:2160"}, ToneMapFilters()...)
		f = append(f, "format=yuv420p")
		if upload := EncoderUploadFilter(c.Encoder, c.Device, false); upload != "" {
			f = append(f, upload)
		}
	}
	a = append(a, "-stream_loop", "-1", "-i", input, "-an", "-vf", strings.Join(f, ","), "-c:v", c.Encoder)
	a = append(a, encoderArgs(c.Encoder, 20_000_000, 2160, false)...)
	_, output := encoderDeviceArgsOn(c.Encoder, c.Device, p.platform(), p.renderNodeFor)
	a = append(a, output...)
	return append(a, "-frames:v", "48", "-g", "120", "-progress", "pipe:1", "-stats_period", "0.05", "-nostats", "-f", "null", "-")
}

// speed4K runs at normal priority, as playback sessions do. Below it (nice,
// and macOS's background policy, which keeps a process to the efficiency
// cores), a Mac busy with other work converted at as little as a tenth of
// the rate a session got.
func (p *hardwareProbe) speed4K(c Capabilities) float64 {
	input, err := p.fixture(true)
	if err == nil && !c.gpuToneMap && !c.zscale {
		err = errors.New("no HDR-to-SDR filter path is available")
	}
	if err != nil {
		p.record(c.Encoder, "4k-pipeline", hardwareProbeResult{err: err}, 0)
		return 0
	}
	r := p.run(p.ctx, p.pipelineBenchmarkArgs(c, input), 30*time.Second)
	fps := 0.0
	if r.err == nil {
		fps, r.err = pipelineFPS(r)
	}
	p.record(c.Encoder, "4k-pipeline", r, fps)
	return fps
}

// pipelineFPS times the frames alone: from the first progress block that
// reports frames to the end of the run. Starting ffmpeg, opening the input
// and creating the decoder and encoder sessions come before the first frame,
// once per session; on a busy machine they can take longer than the 48 frames.
func pipelineFPS(r hardwareProbeResult) (float64, error) {
	frames, first, ended := 0, -1, false
	var from, to time.Duration
	for i, line := range strings.Split(r.output, "\n") {
		line = strings.TrimSpace(line)
		if v, ok := strings.CutPrefix(line, "frame="); ok {
			frames, _ = strconv.Atoi(strings.TrimSpace(v))
		}
		v, ok := strings.CutPrefix(line, "progress=")
		if !ok || i >= len(r.at) {
			continue
		}
		if first < 0 && frames > 0 {
			first, from = frames, r.at[i]
		}
		if v == "end" {
			ended, to = true, r.at[i]
		}
	}
	if frames != 48 || !ended {
		return 0, errors.New("4K pipeline did not report all 48 encoded frames")
	}
	if first == frames || to <= from {
		return 0, errors.New("4K pipeline reported all 48 frames at once, so they could not be timed")
	}
	return math.Floor(float64(frames-first)/(to-from).Seconds()*10) / 10, nil
}
