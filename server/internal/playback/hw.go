package playback

import (
	"context"
	"log/slog"
	"os"
	"strconv"
	"time"
)

// fourKRealtime is the minimum measured decode/filter/encode rate for 4K.
// Twice a 24 fps film leaves headroom; it does not assert a concurrency limit.
const fourKRealtime = 48.0

// Capabilities is what the local ffmpeg build can do.
type Capabilities struct {
	FFmpeg   string   `json:"ffmpeg"`
	Encoders []string `json:"encoders" nullable:"false" doc:"Video encoders available."`
	// Encoder is the H.264 encoder chosen for transcodes.
	Encoder string `json:"encoder" example:"h264_videotoolbox"`
	// HEVCEncoder is the HEVC encoder on the same hardware path, for clients
	// that decode HEVC; empty when there is none (software HEVC is too slow).
	HEVCEncoder string `json:"hevcEncoder,omitempty" example:"hevc_videotoolbox"`
	// HWAccel names the hardware path behind Encoder, or "software".
	HWAccel string `json:"hwaccel" enum:"software,videotoolbox,nvenc,qsv,vaapi,amf"`
	// MaxHeight caps output: 2160 only after the real-input 4K HDR-to-SDR
	// decode/filter/encode benchmark reaches fourKRealtime, otherwise 1080.
	MaxHeight int     `json:"maxHeight" doc:"Tallest transcode output, 1080 or 2160."`
	Speed4K   float64 `json:"speed4k,omitempty" doc:"Frames per second of the 4K HEVC Main 10 HDR-to-SDR decode/filter/encode benchmark; 0 when unavailable or failed."`
	Device    *int    `json:"device,omitempty" required:"false" doc:"Selected adapter: a CUDA index for NVENC, the render node number for QSV and VAAPI on Linux (N is /dev/dri/renderD128+N), a DirectX index for QSV/AMF on Windows; absent uses the backend default."`
	// Diagnostics are exposed only in admin Settings, and kept separately in the cache.
	Diagnostics []HardwareDiagnostic `json:"-"`
	// GPUFilters means decoded frames stay on the GPU through scaling
	// instead of a round trip to RAM, on the HWAccel's pipeline (gpu.go).
	GPUFilters bool      `json:"gpuFilters" doc:"Scaling runs on the GPU, and tone mapping too where the GPU can."`
	ToneMap    bool      `json:"toneMap" doc:"HDR sources can be mapped to SDR (zscale and tonemap filters, or on the GPU)."`
	LibASS     bool      `json:"libass" doc:"subtitles filter present; text subtitles can be burned in."`
	Overlay    bool      `json:"overlay"`
	Detected   time.Time `json:"detected"`

	// DoviToneMap is jellyfin-ffmpeg's tonemapx filter with its apply_dovi
	// option: it reshapes Dolby Vision through the RPU as it maps to SDR,
	// which profile 5 needs (its base layer is not a picture any plain
	// decoder shows right). Debian's and Homebrew's builds lack it.
	DoviToneMap bool `json:"doviToneMap" required:"false" doc:"Dolby Vision profile 5 is converted to SDR through its RPU, so its colours come out right; without it, its transcodes play with the wrong colours. From apiLevel 8."`

	// zscale means the CPU tone mapping filters are there; gpuToneMap that
	// the GPU pipeline tone maps too; doviStrip that the dovi_rpu bitstream
	// filter (FFmpeg 7.1) can take Dolby Vision out of a copy.
	zscale, gpuToneMap, doviStrip bool
}

// CPUToneMap reports whether ffmpeg has the filters that map HDR to SDR on
// the CPU (ToneMapFilters).
func (c Capabilities) CPUToneMap() bool { return c.zscale }

// ToneMapFilters map HDR frames to SDR BT.709 on the CPU, as software
// transcodes do; they need zscale (CPUToneMap). Scale first: they are slow
// on large frames.
func ToneMapFilters() []string {
	return []string{"zscale=t=linear:npl=100", "format=gbrpf32le", "zscale=p=bt709", "tonemap=tonemap=hable:desat=0", "zscale=t=bt709:m=bt709:r=tv"}
}

// DoviToneMapFilter maps Dolby Vision profile 5 to SDR BT.709 through its
// RPU (jellyfin-ffmpeg's tonemapx, applying the RPU by default), where
// DoviToneMap says ffmpeg has it. It runs before scaling, on the frames the
// RPU was written for.
const DoviToneMapFilter = "tonemapx=tonemap=bt2390:desat=0:peak=100:t=bt709:m=bt709:p=bt709:format=yuv420p"

// DVBaseAsHDR10Filter tags Dolby Vision profile 5's base layer as HDR10 so
// that ToneMapFilters run on it where there is no DoviToneMapFilter; its
// colours come out wrong.
const DVBaseAsHDR10Filter = "setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc"

// Detect probes the backend's default adapter.
func Detect(ctx context.Context, ffmpeg, pref string, log *slog.Logger) Capabilities {
	return DetectOnDevice(ctx, ffmpeg, pref, nil, log)
}

// DetectOnDevice probes and uses the same explicit adapter for encoding,
// decoding and filters. An unavailable target falls back to software.
func DetectOnDevice(ctx context.Context, ffmpeg, pref string, device *int, log *slog.Logger) Capabilities {
	// Probes run at normal priority; building the 4K input starts below it (fixture).
	return detectHardware(ctx, ffmpeg, pref, device, log, probeRunner(ffmpeg, false), probeRunner(ffmpeg, true))
}

// renderNode is the DRM render node for a device index on Linux: index N is
// /dev/dri/renderD<128+N>, the kernel's numbering, and no index means the
// first node that exists (renderD128 on a one-GPU machine). Empty when the
// machine, or the container, has none: a container sees one only when it
// was started with --device /dev/dri.
func renderNode(device *int) string {
	if device != nil {
		return "/dev/dri/renderD" + strconv.Itoa(128+*device)
	}
	for n := 128; n < 136; n++ {
		d := "/dev/dri/renderD" + strconv.Itoa(n)
		if _, err := os.Stat(d); err == nil {
			return d
		}
	}
	return ""
}

// needsRenderNode reports whether a hardware path runs on a DRM render node
// on this platform: VAAPI everywhere it exists, and QSV on Linux, where the
// oneVPL runtime opens one.
func needsRenderNode(hw, goos string) bool {
	return hw == "vaapi" || (hw == "qsv" && goos == "linux")
}
