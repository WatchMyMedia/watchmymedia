//go:build !windows

package platform

import "errors"

func current() (Host, error) {
	return Host{}, errors.New("desktop platform detection requires Windows")
}
