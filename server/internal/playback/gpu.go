package playback

import (
	"fmt"
	"strings"
)

// A pipeline keeps a transcode's frames on one GPU from the decoder through
// scaling and tone mapping to the encoder. Frames arrive in the decoder's
// hardware format, or in a software format when ffmpeg fell back to
// decoding in software (a codec or profile the GPU cannot decode), and the
// upload filters lift those; hwupload passes hardware frames through.
//
// Only VideoToolbox has been run on real hardware so far. The CUDA, QSV and
// AMF chains follow the filters the bundled jellyfin-ffmpeg builds carry,
// and Detect uses one only after a real encoded input has been decoded and
// carried through its filter chain. Hardware acceptance remains separate.
type pipeline struct {
	// decode asks for hardware decoding into GPU memory; devices creates
	// the device the filters use. Both go before -i.
	decode, devices []string
	// upload gets frames onto the GPU.
	upload string
	// sizeExpr is set when the scaler takes scale's size expressions (-2,
	// min(ih,N)); otherwise the GPU path needs both sides known.
	sizeExpr bool
	// filters scales to the spec's size, maps HDR to SDR when toneMap is
	// set, and leaves frames as out (nv12 or p010le). It returns those
	// filters and the software format hwdownload must ask for afterwards.
	filters func(spec Spec, toneMap bool, out string) (f []string, sw string)
}

// pipelines are the GPU paths by hwaccel. pipelineOn fills in the device:
// a render node for VAAPI (and QSV on Linux), an adapter index elsewhere.
var pipelines = map[string]pipeline{
	"videotoolbox": {
		decode:   []string{"-hwaccel", "videotoolbox", "-hwaccel_output_format", "videotoolbox_vld"},
		devices:  []string{"-init_hw_device", "videotoolbox=vt", "-filter_hw_device", "vt"},
		upload:   "format=nv12|p010le|videotoolbox_vld,hwupload",
		sizeExpr: true,
		filters: func(spec Spec, toneMap bool, _ string) ([]string, string) {
			// scale_vt keeps the source's depth, and hwdownload cannot
			// negotiate the format after it, so it is told that depth.
			sw := "nv12"
			if spec.Video.BitDepth > 8 {
				sw = "p010le"
			}
			o := sizeOptions(spec, true)
			if toneMap {
				// VTPixelTransferSession converts PQ/HLG to BT.709 with its own tone curve.
				o = append(o, "color_matrix=bt709", "color_primaries=bt709", "color_transfer=bt709")
			}
			if len(o) == 0 {
				return nil, sw
			}
			return []string{"scale_vt=" + strings.Join(o, ":")}, sw
		},
	},
	"nvenc": {
		decode:   []string{"-hwaccel", "cuda", "-hwaccel_device", "cu", "-hwaccel_output_format", "cuda"},
		devices:  []string{"-init_hw_device", "cuda=cu", "-filter_hw_device", "cu"},
		upload:   "format=nv12|p010le|cuda,hwupload",
		sizeExpr: true,
		filters: func(spec Spec, toneMap bool, out string) ([]string, string) {
			o := sizeOptions(spec, true)
			if !toneMap {
				return []string{"scale_cuda=" + strings.Join(append(o, "format="+out), ":")}, out
			}
			// tonemap_cuda (a jellyfin-ffmpeg filter) takes the scaled
			// 10-bit frames and writes 8-bit BT.709.
			var f []string
			if len(o) > 0 {
				f = append(f, "scale_cuda="+strings.Join(o, ":"))
			}
			return append(f, "tonemap_cuda="+toneMapOptions(out)), out
		},
	},
	"qsv": {
		decode:  []string{"-hwaccel", "qsv", "-hwaccel_device", "qs", "-hwaccel_output_format", "qsv"},
		devices: []string{"-init_hw_device", "qsv=qs", "-filter_hw_device", "qs"},
		upload:  "format=nv12|p010le|qsv,hwupload=extra_hw_frames=64",
		filters: func(spec Spec, toneMap bool, out string) ([]string, string) {
			o := append(sizeOptions(spec, false), "format="+out)
			if toneMap {
				o = append(o, "tonemap=1", "out_color_matrix=bt709", "out_color_primaries=bt709", "out_color_transfer=bt709")
			}
			return []string{"vpp_qsv=" + strings.Join(o, ":")}, out
		},
	},
	// VAAPI, Linux's path for AMD and for Intel GPUs too old or too new for
	// QSV: the render node (pipelineOn names it) decodes, scale_vaapi
	// scales, and tonemap_vaapi maps HDR to SDR where the driver implements
	// it (Intel's does; Mesa's for AMD does not, and Detect then leaves GPU
	// tone mapping off, so HDR sources take the CPU filters as before).
	"vaapi": {
		decode:   []string{"-hwaccel", "vaapi", "-hwaccel_device", "va", "-hwaccel_output_format", "vaapi"},
		devices:  []string{"-init_hw_device", "vaapi=va", "-filter_hw_device", "va"},
		upload:   "format=nv12|p010le|vaapi,hwupload",
		sizeExpr: true,
		filters: func(spec Spec, toneMap bool, out string) ([]string, string) {
			o := sizeOptions(spec, true)
			if !toneMap {
				return []string{"scale_vaapi=" + strings.Join(append(o, "format="+out), ":")}, out
			}
			// tonemap_vaapi takes 10-bit frames and writes 8-bit BT.709.
			var f []string
			if len(o) > 0 {
				f = append(f, "scale_vaapi="+strings.Join(append(o, "format=p010"), ":"))
			}
			return append(f, "tonemap_vaapi=format="+out+":t=bt709:m=bt709:p=bt709"), out
		},
	},
	// AMD's own scaler cannot tone map, so on AMF the frames are mapped to
	// OpenCL, which shares the D3D11 memory, and back for the encoder.
	"amf": {
		decode:  []string{"-hwaccel", "d3d11va", "-hwaccel_device", "dx", "-hwaccel_output_format", "d3d11"},
		devices: []string{"-init_hw_device", "d3d11va=dx", "-filter_hw_device", "dx"},
		upload:  "format=nv12|p010le|d3d11,hwupload",
		filters: func(spec Spec, toneMap bool, out string) ([]string, string) {
			f := []string{"hwmap=derive_device=opencl:mode=read"}
			o := sizeOptions(spec, false)
			if toneMap {
				if len(o) > 0 {
					f = append(f, "scale_opencl="+strings.Join(o, ":"))
				}
				f = append(f, "tonemap_opencl="+toneMapOptions(out))
			} else {
				f = append(f, "scale_opencl="+strings.Join(append(o, "format="+out), ":"))
			}
			return append(f, "hwmap=derive_device=d3d11va:mode=write:reverse=1"), out
		},
	},
}

// sizeOptions are the w and h options for the spec's output size: both
// sides when known, else (with expr) the height cap keeping the aspect.
func sizeOptions(spec Spec, expr bool) []string {
	switch {
	case spec.Video.Width > 0 && spec.Video.Height > 0:
		return []string{fmt.Sprintf("w=%d", spec.Video.Width), fmt.Sprintf("h=%d", spec.Video.Height)}
	case expr && spec.Video.Height > 0:
		return []string{"w=-2", fmt.Sprintf("h='min(ih,%d)'", spec.Video.Height)}
	}
	return nil
}

// toneMapOptions configure tonemap_cuda and tonemap_opencl, which share
// their options: BT.2390 to SDR BT.709 in the given 8-bit format.
func toneMapOptions(out string) string {
	return "tonemap=bt2390:desat=0:peak=100:t=bt709:m=bt709:p=bt709:format=" + out
}

// gpuChain is the filter chain for a transcode on pipeline p: frames up
// onto the GPU, scaled and tone mapped there, and pulled back only when
// burn-in needs them in RAM, which is still far cheaper than scaling a 4K
// frame on the CPU.
func gpuChain(p pipeline, spec Spec, burn bool) []string {
	out := "nv12"
	if keepsHDR(spec) {
		out = "p010le"
	}
	scale, sw := p.filters(spec, spec.Video.ToneMap, out)
	f := append([]string{p.upload}, scale...)
	if burn {
		final := "yuv420p"
		if keepsHDR(spec) {
			// The burn-in filters do not take p010; ffmpeg converts back for the encoder.
			final = "yuv420p10le"
		}
		f = append(f, "hwdownload", "format="+sw, "format="+final)
	}
	return f
}
