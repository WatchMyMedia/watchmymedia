package playback

import (
	"runtime"
	"strconv"
)

// pipelineOn keeps a named decoder/filter device on the selected adapter.
// Never mutate the shared pipeline slices: sessions can use different adapters.
func pipelineOn(hw string, device *int) (pipeline, bool) {
	return pipelineOnOS(hw, device, runtime.GOOS, renderNode)
}

// pipelineOnOS is pipelineOn for a platform, with node giving the render
// node for a device index (renderNode; tests pass their own).
func pipelineOnOS(hw string, device *int, goos string, node func(*int) string) (pipeline, bool) {
	p, ok := pipelines[hw]
	if !ok {
		return p, ok
	}
	var init string
	switch {
	case hw == "vaapi":
		// Always named: the decoder and the filters share the node. With
		// no node known, ffmpeg's own default.
		init = "vaapi=va"
		if n := node(device); n != "" {
			init += ":" + n
		}
	case device == nil:
		return p, ok
	case hw == "nvenc":
		init = "cuda=cu:" + strconv.Itoa(*device)
	case hw == "qsv" && goos == "linux":
		// On Linux the oneVPL runtime sits on a VAAPI render node.
		init = "qsv=qs:hw,child_device=" + node(device)
	case hw == "qsv":
		init = "qsv=qs:hw,child_device=" + strconv.Itoa(*device) + ",child_device_type=d3d11va"
	case hw == "amf":
		init = "d3d11va=dx:" + strconv.Itoa(*device)
	default:
		return p, ok
	}
	p.devices = append([]string(nil), p.devices...)
	p.devices[1] = init
	return p, ok
}

// EncoderDeviceArgs binds encoding to the selected adapter even when filters
// run on the CPU. Input options go before -i; output options after -c:v.
// VAAPI always names its device: its encoder takes only frames uploaded to
// one.
func EncoderDeviceArgs(encoder string, device *int) (input, output []string) {
	return encoderDeviceArgsOn(encoder, device, runtime.GOOS, renderNode)
}

// encoderDeviceArgsOn is EncoderDeviceArgs for a platform (pipelineOnOS).
func encoderDeviceArgsOn(encoder string, device *int, goos string, node func(*int) string) (input, output []string) {
	hw := hwaccelOf(encoder)
	if device == nil && hw != "vaapi" {
		return nil, nil
	}
	p, ok := pipelineOnOS(hw, device, goos, node)
	if !ok || hw == "software" {
		return nil, nil
	}
	if hw == "nvenc" {
		return nil, []string{"-gpu", strconv.Itoa(*device)}
	}
	return p.devices, nil
}

// EncoderUploadFilter reattaches software frames (including subtitle burn-in)
// to a selected QSV/AMF device, and always to VAAPI's, whose encoder cannot
// take them from RAM. NVENC has its own -gpu output option.
func EncoderUploadFilter(encoder string, device *int, tenBit bool) string {
	hw := hwaccelOf(encoder)
	if device == nil && hw != "vaapi" {
		return ""
	}
	format := "nv12"
	if tenBit {
		format = "p010le"
	}
	switch hw {
	case "qsv":
		return "format=" + format + ",hwupload=extra_hw_frames=64"
	case "amf", "vaapi":
		return "format=" + format + ",hwupload"
	}
	return ""
}
