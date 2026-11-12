//go:build !windows

package hwdevice

import "errors"

func nativeAdapters(string) ([]Adapter, error) {
	return nil, errors.New("native adapter inventory is supported on Windows only")
}
