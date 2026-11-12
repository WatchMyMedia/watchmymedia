package playback

import (
	"context"
	"slices"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestAdapterIsBoundThroughEveryFilterPath(t *testing.T) {
	t.Parallel()
	n := 1
	for _, tc := range []struct{ hw, encoder, init string }{
		{"nvenc", "h264_nvenc", "cuda=cu:1"}, {"qsv", "h264_qsv", "qsv=qs:hw,child_device=1,child_device_type=d3d11va"}, {"amf", "h264_amf", "d3d11va=dx:1"},
	} {
		c := Capabilities{Encoder: tc.encoder, HWAccel: tc.hw, Device: &n, GPUFilters: true, gpuToneMap: true, LibASS: true, Overlay: true}
		m := NewManager("unused", t.TempDir(), c, time.Minute, 1, testLog)
		base := Spec{Path: "movie.mkv", Mode: ModeTranscode, Video: VideoPlan{Codec: "h264", Width: 1280, Height: 720}, BurnIn: -1}
		for _, mode := range []string{"gpu", "cpu", "text", "image"} {
			spec := base
			m.caps = c
			switch mode {
			case "cpu":
				m.caps.GPUFilters = false
			case "text":
				spec.BurnIn = 1
			case "image":
				spec.BurnIn = 1
				spec.BurnInImage = true
			}
			a := m.args(spec, t.TempDir())
			joined := strings.Join(a, " ")
			if tc.hw != "nvenc" || mode != "cpu" {
				if !slices.Contains(a, tc.init) {
					t.Fatalf("%s/%s lost its adapter: %s", tc.hw, mode, joined)
				}
			}
			if tc.hw == "nvenc" && !strings.Contains(joined, "-gpu 1") {
				t.Fatalf("NVENC output lost its device: %s", joined)
			}
			if tc.hw != "nvenc" && mode != "gpu" && !strings.Contains(joined, "hwupload") {
				t.Fatalf("CPU frames lost their upload: %s", joined)
			}
			if mode == "image" && tc.hw != "nvenc" && !strings.Contains(joined, "overlay,format=nv12,hwupload") {
				t.Fatalf("uploaded before image burn-in: %s", joined)
			}
		}
	}
	p, _ := pipelineOn("nvenc", nil)
	if p.devices[1] != "cuda=cu" {
		t.Fatal("per-session adapter mutated the shared pipeline")
	}
}

// On Linux a QSV or VAAPI device index is a render node, /dev/dri/renderD128
// plus the index; on Windows QSV keeps its DirectX child device. VAAPI
// names its node even with no index, the machine's first.
func TestLinuxDeviceIndexIsARenderNode(t *testing.T) {
	t.Parallel()
	node := func(d *int) string {
		if d == nil {
			return "/dev/dri/renderD128"
		}
		return "/dev/dri/renderD" + strconv.Itoa(128+*d)
	}
	one := 1
	for _, tc := range []struct {
		hw, goos string
		device   *int
		init     string
	}{
		{"vaapi", "linux", nil, "vaapi=va:/dev/dri/renderD128"},
		{"vaapi", "linux", &one, "vaapi=va:/dev/dri/renderD129"},
		{"qsv", "linux", nil, "qsv=qs"},
		{"qsv", "linux", &one, "qsv=qs:hw,child_device=/dev/dri/renderD129"},
		{"qsv", "windows", &one, "qsv=qs:hw,child_device=1,child_device_type=d3d11va"},
		{"nvenc", "linux", &one, "cuda=cu:1"},
	} {
		p, ok := pipelineOnOS(tc.hw, tc.device, tc.goos, node)
		if !ok || p.devices[1] != tc.init {
			t.Errorf("%s on %s device %v: %q", tc.hw, tc.goos, tc.device, p.devices[1])
		}
	}
	for _, p := range []string{"vaapi", "qsv"} {
		if pipelines[p].devices[1] != map[string]string{"vaapi": "vaapi=va", "qsv": "qsv=qs"}[p] {
			t.Fatalf("%s: shared pipeline mutated: %v", p, pipelines[p].devices)
		}
	}
	if !needsRenderNode("vaapi", "linux") || !needsRenderNode("qsv", "linux") || needsRenderNode("qsv", "windows") || needsRenderNode("nvenc", "linux") {
		t.Fatal("needsRenderNode")
	}
	if got := renderNode(&one); got != "/dev/dri/renderD129" {
		t.Fatalf("renderNode(1) = %q", got)
	}
}

func TestDecisionAndDownloadKeepTheirAdapter(t *testing.T) {
	t.Parallel()
	n := 1
	c := Capabilities{Encoder: "h264_nvenc", HWAccel: "nvenc", Device: &n, GPUFilters: true}
	m := NewManager("unused", t.TempDir(), c, time.Minute, 1, testLog)
	spec := Spec{SessionID: "download", Mode: ModeTranscode, Video: VideoPlan{Codec: "h264", Width: 1280, Height: 720}, BurnIn: -1, VOD: &VODOptions{DurationMs: 12000, FrameRate: 24}}
	// API sessionSpec binds the decision snapshot before a concurrent preference changes.
	d := Decision{caps: &c}
	spec = d.BindCapabilities(spec)
	newer := 0
	m.caps = Capabilities{Encoder: "h264_qsv", HWAccel: "qsv", Device: &newer}
	s, err := m.OpenDownload(spec)
	if err != nil {
		t.Fatal(err)
	}
	if got := strings.Join(m.args(s.vod.spec, t.TempDir()), " "); !strings.Contains(got, "cuda=cu:1") || !strings.Contains(got, "-gpu 1") {
		t.Fatal("download moved to a later adapter: " + got)
	}
	m.StopAll()
}

func TestNewDetectionCancelsAndRejectsTheObsoleteGeneration(t *testing.T) {
	t.Parallel()
	m := NewManager("unused", t.TempDir(), Capabilities{}, time.Minute, 1, testLog)
	ctx, cancel := context.WithCancel(t.Context())
	m.capsCancel = cancel
	older := m.newDetection()
	newer := m.newDetection()
	if ctx.Err() == nil {
		t.Fatal("obsolete probes were left running")
	}
	if !m.setCaps(newer, Capabilities{Encoder: "h264_nvenc"}) || m.setCaps(older, Capabilities{Encoder: "libx264"}) {
		t.Fatal("obsolete detection was published")
	}
}
