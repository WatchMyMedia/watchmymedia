package platform

import (
	"fmt"

	"golang.org/x/sys/windows"
)

func current() (Host, error) {
	// Ignore manifest/compatibility version virtualization. ProductType from
	// the extended version structure distinguishes workstation from Server.
	major, minor, build := windows.RtlGetNtVersionNumbers()
	v := windows.RtlGetVersion()
	var process, native uint16
	if err := windows.IsWow64Process2(windows.CurrentProcess(), &process, &native); err != nil {
		return Host{}, fmt.Errorf("read native Windows architecture: %w", err)
	}
	return Host{Major: major, Minor: minor, Build: build, ProductType: v.ProductType, NativeMachine: native}, nil
}
