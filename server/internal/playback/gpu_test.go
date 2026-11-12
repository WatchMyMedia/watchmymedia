package playback

import (
	"context"
	"io"
	"log/slog"
	"strings"
	"testing"
	"time"
)

// The CUDA, QSV and AMF chains cannot run here (no such GPUs), so their
// command lines are pinned down instead; Detect proves them on the machine.
func TestGPUPipelineArgs(t *testing.T) {
	t.Parallel()
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	base := Spec{Path: "/m/movie.mkv", Mode: ModeTranscode, Video: VideoPlan{Codec: "h264", Width: 1920, Height: 1080}, Audio: AudioPlan{Index: 1, Copy: true}, BurnIn: -1}
	hdr := base
	hdr.Video = VideoPlan{Codec: "h264", Width: 1920, Height: 1080, HDR: "hdr10", BitDepth: 10, ToneMap: true}
	keep := base
	keep.Video = VideoPlan{Codec: "hevc", Width: 3840, Height: 2160, HDR: "hdr10", BitDepth: 10}
	burn := hdr
	burn.BurnIn, burn.BurnInSub = 3, 1

	for _, tc := range []struct {
		hw, h264, hevc, device     string
		plain, toneMap, keep, burn string
	}{
		{
			hw: "nvenc", h264: "h264_nvenc", hevc: "hevc_nvenc",
			device:  "-hwaccel cuda -hwaccel_device cu -hwaccel_output_format cuda -init_hw_device cuda=cu -filter_hw_device cu -i /m/movie.mkv",
			plain:   "format=nv12|p010le|cuda,hwupload,scale_cuda=w=1920:h=1080:format=nv12",
			toneMap: "format=nv12|p010le|cuda,hwupload,scale_cuda=w=1920:h=1080,tonemap_cuda=tonemap=bt2390:desat=0:peak=100:t=bt709:m=bt709:p=bt709:format=nv12",
			keep:    "format=nv12|p010le|cuda,hwupload,scale_cuda=w=3840:h=2160:format=p010le",
			burn:    "tonemap_cuda=tonemap=bt2390:desat=0:peak=100:t=bt709:m=bt709:p=bt709:format=nv12,hwdownload,format=nv12,format=yuv420p,subtitles='/m/movie.mkv':si=1",
		},
		{
			hw: "qsv", h264: "h264_qsv", hevc: "hevc_qsv",
			device:  "-hwaccel qsv -hwaccel_device qs -hwaccel_output_format qsv -init_hw_device qsv=qs -filter_hw_device qs -i /m/movie.mkv",
			plain:   "format=nv12|p010le|qsv,hwupload=extra_hw_frames=64,vpp_qsv=w=1920:h=1080:format=nv12",
			toneMap: "format=nv12|p010le|qsv,hwupload=extra_hw_frames=64,vpp_qsv=w=1920:h=1080:format=nv12:tonemap=1:out_color_matrix=bt709:out_color_primaries=bt709:out_color_transfer=bt709",
			keep:    "format=nv12|p010le|qsv,hwupload=extra_hw_frames=64,vpp_qsv=w=3840:h=2160:format=p010le",
			burn:    "out_color_transfer=bt709,hwdownload,format=nv12,format=yuv420p,subtitles='/m/movie.mkv':si=1",
		},
		{
			hw: "vaapi", h264: "h264_vaapi", hevc: "hevc_vaapi",
			device:  "-hwaccel vaapi -hwaccel_device va -hwaccel_output_format vaapi -init_hw_device vaapi=va:/dev/dri/renderD128 -filter_hw_device va -i /m/movie.mkv",
			plain:   "format=nv12|p010le|vaapi,hwupload,scale_vaapi=w=1920:h=1080:format=nv12",
			toneMap: "format=nv12|p010le|vaapi,hwupload,scale_vaapi=w=1920:h=1080:format=p010,tonemap_vaapi=format=nv12:t=bt709:m=bt709:p=bt709",
			keep:    "format=nv12|p010le|vaapi,hwupload,scale_vaapi=w=3840:h=2160:format=p010le",
			burn:    "tonemap_vaapi=format=nv12:t=bt709:m=bt709:p=bt709,hwdownload,format=nv12,format=yuv420p,subtitles='/m/movie.mkv':si=1,format=nv12,hwupload",
		},
		{
			hw: "amf", h264: "h264_amf", hevc: "hevc_amf",
			device:  "-hwaccel d3d11va -hwaccel_device dx -hwaccel_output_format d3d11 -init_hw_device d3d11va=dx -filter_hw_device dx -i /m/movie.mkv",
			plain:   "format=nv12|p010le|d3d11,hwupload,hwmap=derive_device=opencl:mode=read,scale_opencl=w=1920:h=1080:format=nv12,hwmap=derive_device=d3d11va:mode=write:reverse=1",
			toneMap: "format=nv12|p010le|d3d11,hwupload,hwmap=derive_device=opencl:mode=read,scale_opencl=w=1920:h=1080,tonemap_opencl=tonemap=bt2390:desat=0:peak=100:t=bt709:m=bt709:p=bt709:format=nv12,hwmap=derive_device=d3d11va:mode=write:reverse=1",
			keep:    "format=nv12|p010le|d3d11,hwupload,hwmap=derive_device=opencl:mode=read,scale_opencl=w=3840:h=2160:format=p010le,hwmap=derive_device=d3d11va:mode=write:reverse=1",
			burn:    "hwmap=derive_device=d3d11va:mode=write:reverse=1,hwdownload,format=nv12,format=yuv420p,subtitles='/m/movie.mkv':si=1",
		},
	} {
		if tc.hw == "vaapi" && renderNode(nil) == "" {
			// The pipeline names the machine's first render node; this
			// machine has none, so ffmpeg's default device is asked for.
			tc.device = "-hwaccel vaapi -hwaccel_device va -hwaccel_output_format vaapi -init_hw_device vaapi=va -filter_hw_device va -i /m/movie.mkv"
		}
		caps := Capabilities{Encoder: tc.h264, HEVCEncoder: tc.hevc, HWAccel: tc.hw, GPUFilters: true, ToneMap: true, LibASS: true, gpuToneMap: true}
		m := NewManager("ffmpeg", t.TempDir(), caps, time.Minute, 1, log)
		args := func(spec Spec) (string, string) {
			a := m.args(spec, t.TempDir())
			for i, x := range a {
				if x == "-vf" {
					return strings.Join(a, " "), a[i+1]
				}
			}
			t.Fatalf("%s: no -vf in %v", tc.hw, a)
			return "", ""
		}
		all, vf := args(base)
		if !strings.Contains(all, tc.device) {
			t.Errorf("%s device args: %s", tc.hw, all)
		}
		if strings.Contains(all, " scale=") || strings.Contains(all, "zscale") {
			t.Errorf("%s: CPU filters in GPU path: %s", tc.hw, all)
		}
		if vf != tc.plain {
			t.Errorf("%s plain:\n got %s\nwant %s", tc.hw, vf, tc.plain)
		}
		if _, vf = args(hdr); vf != tc.toneMap {
			t.Errorf("%s tone map:\n got %s\nwant %s", tc.hw, vf, tc.toneMap)
		}
		spec := keep
		spec.Video.Encoder = tc.hevc
		all, vf = args(spec)
		if vf != tc.keep || !strings.Contains(all, "-c:v "+tc.hevc) || !strings.Contains(all, "-profile:v main10 -tag:v hvc1") {
			t.Errorf("%s hevc hdr:\n got %s\nwant %s", tc.hw, all, tc.keep)
		}
		if _, vf = args(burn); !strings.HasSuffix(vf, tc.burn) {
			t.Errorf("%s burn-in:\n got %s\nwant suffix %s", tc.hw, vf, tc.burn)
		}
	}
}

// The GPU path is left for the CPU one when the pipeline cannot do what the
// session needs, and for the session's own encoder when it fell back.
func TestGPUPathFallsBackToCPUFilters(t *testing.T) {
	t.Parallel()
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	spec := Spec{Path: "/m/movie.mkv", Mode: ModeTranscode, Video: VideoPlan{Codec: "h264", Width: 1920, Height: 1080, HDR: "hdr10", BitDepth: 10, ToneMap: true}, Audio: AudioPlan{Index: 1, Copy: true}, BurnIn: -1}
	// No GPU tone mapping: decode in software, tone map with zscale, encode on the GPU.
	m := NewManager("ffmpeg", t.TempDir(), Capabilities{Encoder: "h264_nvenc", HWAccel: "nvenc", GPUFilters: true, ToneMap: true, zscale: true}, time.Minute, 1, log)
	got := strings.Join(m.args(spec, t.TempDir()), " ")
	if strings.Contains(got, "-hwaccel") || !strings.Contains(got, "zscale=t=linear") || !strings.Contains(got, "-c:v h264_nvenc") {
		t.Errorf("no gpu tone map: %s", got)
	}
	// vpp_qsv needs both sides of the size.
	m = NewManager("ffmpeg", t.TempDir(), Capabilities{Encoder: "h264_qsv", HWAccel: "qsv", GPUFilters: true, gpuToneMap: true}, time.Minute, 1, log)
	sdr := spec
	sdr.Video = VideoPlan{Codec: "h264", Height: 720}
	if got = strings.Join(m.args(sdr, t.TempDir()), " "); strings.Contains(got, "vpp_qsv") || !strings.Contains(got, "scale=-2:'min(ih,720)'") {
		t.Errorf("qsv height only: %s", got)
	}
	// A session moved to libx264 stays off the GPU.
	sdr.Video = VideoPlan{Codec: "h264", Encoder: "libx264", Width: 1280, Height: 720}
	if got = strings.Join(m.args(sdr, t.TempDir()), " "); strings.Contains(got, "qsv") || !strings.Contains(got, "-c:v libx264") {
		t.Errorf("software session: %s", got)
	}
	// VAAPI without GPU tone mapping (AMD): software decode, CPU tone map,
	// then the frames go up to the named render node for the encoder, and
	// the node is named once, before the input.
	n := 1
	m = NewManager("ffmpeg", t.TempDir(), Capabilities{Encoder: "h264_vaapi", HWAccel: "vaapi", Device: &n, GPUFilters: true, ToneMap: true, zscale: true}, time.Minute, 1, log)
	got = strings.Join(m.args(spec, t.TempDir()), " ")
	if strings.Contains(got, "-hwaccel") || !strings.Contains(got, "zscale=t=linear") || !strings.HasSuffix(extractVF(got), "format=yuv420p,format=nv12,hwupload") ||
		strings.Count(got, "-init_hw_device vaapi=va:/dev/dri/renderD129") != 1 || !strings.Contains(got, "-filter_hw_device va -i ") || strings.Contains(got, "-vaapi_device") {
		t.Errorf("vaapi cpu tone map: %s", got)
	}
	// Image burn-in uploads after the overlay, not before.
	burn := spec
	burn.Video = VideoPlan{Codec: "h264", Width: 1280, Height: 720}
	burn.BurnIn, burn.BurnInImage = 2, true
	m.caps.Overlay = true
	if got = strings.Join(m.args(burn, t.TempDir()), " "); !strings.Contains(got, "overlay,format=nv12,hwupload[out]") || strings.Contains(got, "hwupload[v]") {
		t.Errorf("vaapi image burn-in: %s", got)
	}
}

// extractVF is the -vf argument of a joined ffmpeg command line.
func extractVF(joined string) string {
	_, after, ok := strings.Cut(joined, " -vf ")
	if !ok {
		return ""
	}
	vf, _, _ := strings.Cut(after, " ")
	return vf
}

func TestSoftwareSpec(t *testing.T) {
	t.Parallel()
	m := NewManager("ffmpeg", t.TempDir(), Capabilities{Encoders: []string{"libx264", "hevc_nvenc"}}, time.Minute, 1, slog.New(slog.NewTextHandler(io.Discard, nil)))
	spec := Spec{Video: VideoPlan{Codec: "hevc", Encoder: "hevc_nvenc", Width: 3840, Height: 1606, HDR: "hdr10", BitDepth: 10}}
	got, ok := m.softwareSpec(spec)
	want := VideoPlan{Codec: "h264", Encoder: "libx264", Width: 2582, Height: 1080, HDR: "hdr10", BitDepth: 10, ToneMap: true}
	if !ok || got.Video != want {
		t.Errorf("software spec = %+v, %v", got.Video, ok)
	}
	if spec.Video.Encoder != "hevc_nvenc" {
		t.Error("softwareSpec changed its argument")
	}
	m.caps.Encoders = []string{"hevc_nvenc"}
	if _, ok := m.softwareSpec(spec); ok {
		t.Error("software spec without libx264")
	}
}

// A hardware encoder that makes ffmpeg exit before anything is served must
// cost the play a software transcode, not the play itself.
func TestHardwareFailureFallsBackToSoftware(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	m := testManager(t, ffmpeg, "software")
	if !strings.Contains(strings.Join(m.caps.Encoders, ","), "libx264") {
		t.Skip("libx264 not available")
	}
	// No ffmpeg has this encoder, so it fails the way a GPU out of
	// sessions does: ffmpeg exits as soon as it opens the encoder.
	m.caps.Encoder, m.caps.HWAccel = "fake_nvenc", "nvenc"
	clip := sample(t, ffmpeg)
	vod := keyedSampleAt(t, ffmpeg, "24", 12, "0,6")
	for _, spec := range []Spec{
		{FileID: 1, PlayID: "p1", UserID: 1, Path: clip, Mode: ModeTranscode, Video: VideoPlan{Codec: "h264", Height: 144}, Audio: AudioPlan{Index: 1, Codec: "aac", Channels: 2}, BurnIn: -1},
		{FileID: 2, PlayID: "p2", UserID: 1, Path: vod, Mode: ModeTranscode, Video: VideoPlan{Codec: "h264", Width: 320, Height: 180}, Audio: AudioPlan{Index: 1, Copy: true}, BurnIn: -1,
			VOD: &VODOptions{DurationMs: 12000, FrameRate: 24}},
	} {
		s, err := m.Start(context.Background(), spec)
		if err != nil {
			t.Fatalf("file %d: %v", spec.FileID, err)
		}
		if s.Encoder != "libx264" || s.Video.Encoder != "libx264" || !strings.Contains(s.Fallback, "fake_nvenc") {
			t.Errorf("file %d: encoder %s, video %+v, fallback %q", spec.FileID, s.Encoder, s.Video, s.Fallback)
		}
		if spec.VOD != nil {
			if _, err := s.vod.Segment(context.Background(), 1); err != nil {
				t.Errorf("vod segment after fallback: %v", err)
			}
		}
		m.Stop(s.ID)
	}
	if n := len(m.Sessions()); n != 0 {
		t.Errorf("%d sessions left", n)
	}
}

func TestHwaccelOf(t *testing.T) {
	t.Parallel()
	for enc, want := range map[string]string{"h264_videotoolbox": "videotoolbox", "hevc_nvenc": "nvenc", "h264_qsv": "qsv", "hevc_vaapi": "vaapi", "h264_amf": "amf", "libx264": "software", "copy": "software"} {
		if got := hwaccelOf(enc); got != want {
			t.Errorf("hwaccelOf(%s) = %s, want %s", enc, got, want)
		}
	}
}
