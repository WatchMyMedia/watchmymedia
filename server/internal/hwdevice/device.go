// Package hwdevice validates an explicit hardware adapter preference shared by
// config, settings and playback. Indices belong to an encoder backend, not to
// a universal list of GPUs: CUDA for NVENC, DirectX for Windows QSV and AMF.
package hwdevice

import (
	"fmt"
	"runtime"
)

// Index copies an explicit index. Nil and -1 select the backend's default.
func Index(device *int) *int {
	if device == nil || *device == -1 {
		return nil
	}
	n := *device
	return &n
}

func Validate(accel string, device *int) error { return validate(accel, device, runtime.GOOS) }

// ValidateOn is Validate for a platform (a GOOS value), for probes that
// stand in for another machine.
func ValidateOn(accel string, device *int, platform string) error {
	return validate(accel, device, platform)
}

func validate(accel string, device *int, platform string) error {
	switch accel {
	case "", "auto", "software", "videotoolbox", "nvenc", "qsv", "vaapi", "amf":
	default:
		return fmt.Errorf("unknown hardware backend %q", accel)
	}
	if device == nil || *device == -1 {
		return nil
	}
	if *device < 0 || *device > 2147483647 {
		return fmt.Errorf("hardware device must be -1 (default) or a nonnegative adapter index")
	}
	switch accel {
	case "nvenc":
		return nil
	case "amf":
		if platform == "windows" {
			return nil
		}
		return fmt.Errorf("explicit %s adapter indices are supported on Windows only", accel)
	case "qsv":
		// A DirectX adapter index on Windows, a render node number on Linux.
		if platform == "windows" || platform == "linux" {
			return nil
		}
		return fmt.Errorf("explicit %s adapter indices are supported on Windows and Linux only", accel)
	case "vaapi":
		// The render node number: N is /dev/dri/renderD128+N.
		if platform == "linux" {
			return nil
		}
		return fmt.Errorf("explicit %s render nodes are supported on Linux only", accel)
	default:
		return fmt.Errorf("an explicit adapter requires nvenc, qsv, vaapi or amf; choose a hardware backend before its device")
	}
}
