package playback

import (
	"context"
	"errors"
	"log/slog"
	"slices"
	"strings"
	"testing"
	"time"
)

// fakeLinuxProbe is an ffmpeg on Linux with VAAPI and QSV encoders.
type fakeLinuxProbe struct {
	fakeHardwareProbe
	encodeErr error
}

func (f *fakeLinuxProbe) run(ctx context.Context, a []string, d time.Duration) hardwareProbeResult {
	if slices.Contains(a, "-encoders") {
		f.calls = append(f.calls, slices.Clone(a))
		return hardwareProbeResult{output: " V..... libx264 x264\n V..... libx265 HEVC\n V..... h264_qsv Intel\n V..... hevc_qsv Intel\n V..... h264_vaapi VAAPI\n V..... hevc_vaapi VAAPI\n"}
	}
	if f.encodeErr != nil && slices.Contains(a, "lavfi") && !slices.Contains(a, "matroska") {
		f.calls = append(f.calls, slices.Clone(a))
		return hardwareProbeResult{err: f.encodeErr}
	}
	return f.fakeHardwareProbe.run(ctx, a, d)
}

// Without a render node, VAAPI and QSV are not probed on Linux: each gets a
// diagnostic that says what is missing, and software is used.
func TestLinuxWithoutRenderNodeExplainsItself(t *testing.T) {
	t.Parallel()
	none := func(*int) string { return "" }
	f := fakeLinuxProbe{}
	c := detectHardwareOn(t.Context(), "fake-ffmpeg", "auto", nil, slog.New(slog.DiscardHandler), f.run, f.run, "linux", none)
	if c.HWAccel != "software" || c.Encoder != "libx264" {
		t.Fatalf("caps=%+v", c)
	}
	var devices []HardwareDiagnostic
	for _, d := range c.Diagnostics {
		if d.Stage == "device" {
			devices = append(devices, d)
		}
	}
	if len(devices) != 4 {
		t.Fatalf("device diagnostics: %+v", c.Diagnostics)
	}
	for _, d := range devices {
		if d.OK || !strings.Contains(d.Detail, "--device /dev/dri") {
			t.Fatalf("diagnostic=%+v", d)
		}
	}
	for _, a := range f.calls {
		if slices.Contains(a, "h264_vaapi") || slices.Contains(a, "h264_qsv") {
			t.Fatalf("probed without a node: %v", a)
		}
	}
	// An index that names a node the machine lacks says which.
	one := 1
	f = fakeLinuxProbe{}
	c = detectHardwareOn(t.Context(), "fake-ffmpeg", "vaapi", &one, slog.New(slog.DiscardHandler), f.run, f.run, "linux", none)
	if c.HWAccel != "software" || !strings.Contains(c.Diagnostics[0].Detail, "renderD129 does not exist") {
		t.Fatalf("caps=%+v diagnostics=%+v", c, c.Diagnostics)
	}
}

// A render node the server's user may not open fails every VAAPI probe with
// ffmpeg's "Permission denied"; the diagnostic names the node and the fix.
func TestRenderNodePermissionDeniedIsExplained(t *testing.T) {
	t.Parallel()
	node := func(*int) string { return "/dev/dri/renderD128" }
	f := fakeLinuxProbe{encodeErr: errors.New("exit status 1: Failed to open /dev/dri/renderD128: Permission denied")}
	c := detectHardwareOn(t.Context(), "fake-ffmpeg", "vaapi", nil, slog.New(slog.DiscardHandler), f.run, f.run, "linux", node)
	if c.HWAccel != "software" {
		t.Fatalf("caps=%+v", c)
	}
	var found bool
	for _, d := range c.Diagnostics {
		if d.Encoder == "h264_vaapi" && d.Stage == "encode" {
			found = true
			if d.OK || !strings.Contains(d.Detail, "cannot open /dev/dri/renderD128") || !strings.Contains(d.Detail, "--device /dev/dri") {
				t.Fatalf("diagnostic=%+v", d)
			}
		}
	}
	if !found {
		t.Fatalf("no encode diagnostic: %+v", c.Diagnostics)
	}
	// The probe named the node for the encoder and uploaded to it.
	for _, a := range f.calls {
		if slices.Contains(a, "h264_vaapi") {
			joined := strings.Join(a, " ")
			if !strings.Contains(joined, "-init_hw_device vaapi=va:/dev/dri/renderD128 -filter_hw_device va") || !strings.Contains(joined, "format=nv12,hwupload") || strings.Contains(joined, "-vaapi_device") {
				t.Fatalf("vaapi probe: %s", joined)
			}
			return
		}
	}
	t.Fatal("h264_vaapi was not probed")
}

// A working VAAPI node on Linux gets the GPU pipeline proved like the others:
// the decode-scale and decode-tonemap probes run the vaapi chain on the node,
// and a QSV index on Linux is a render node too.
func TestLinuxVAAPIPipelineIsProved(t *testing.T) {
	t.Parallel()
	node := func(d *int) string {
		if d != nil && *d == 1 {
			return "/dev/dri/renderD129"
		}
		return "/dev/dri/renderD128"
	}
	f := fakeLinuxProbe{}
	c := detectHardwareOn(t.Context(), "fake-ffmpeg", "vaapi", nil, slog.New(slog.DiscardHandler), f.run, f.run, "linux", node)
	if c.HWAccel != "vaapi" || c.Encoder != "h264_vaapi" || c.HEVCEncoder != "hevc_vaapi" || !c.GPUFilters || !c.gpuToneMap || !c.ToneMap {
		t.Fatalf("caps=%+v", c)
	}
	var scale, tone bool
	for _, a := range f.calls {
		joined := strings.Join(a, " ")
		if strings.Contains(joined, "scale_vaapi") && strings.Contains(joined, "-hwaccel vaapi -hwaccel_device va -hwaccel_output_format vaapi -init_hw_device vaapi=va:/dev/dri/renderD128") {
			if strings.Contains(joined, "tonemap_vaapi=format=nv12") {
				tone = true
			} else {
				scale = true
			}
		}
	}
	if !scale || !tone {
		t.Fatalf("pipeline probes missing (scale %v, tone %v): %v", scale, tone, f.calls)
	}
	// Mesa (AMD) has no tonemap_vaapi: GPU scaling stays, GPU tone mapping goes.
	f = fakeLinuxProbe{fakeHardwareProbe{cpuToneMap: true, fail: map[string]error{"decode-tonemap": errors.New("Function not implemented")}}, nil}
	c = detectHardwareOn(t.Context(), "fake-ffmpeg", "vaapi", nil, slog.New(slog.DiscardHandler), f.run, f.run, "linux", node)
	if c.HWAccel != "vaapi" || !c.GPUFilters || c.gpuToneMap || !c.ToneMap {
		t.Fatalf("amd caps=%+v", c)
	}
	one := 1
	f = fakeLinuxProbe{}
	c = detectHardwareOn(t.Context(), "fake-ffmpeg", "qsv", &one, slog.New(slog.DiscardHandler), f.run, f.run, "linux", node)
	if c.HWAccel != "qsv" || c.Device == nil || *c.Device != 1 {
		t.Fatalf("qsv caps=%+v", c)
	}
	for _, a := range f.calls {
		if slices.Contains(a, "h264_qsv") && !slices.Contains(a, "qsv=qs:hw,child_device=/dev/dri/renderD129") {
			t.Fatalf("qsv probe lost its render node: %v", a)
		}
	}
}
