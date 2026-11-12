// Package platform holds the desktop support policy. The portable server does
// not import it. Keep the installer and tray on this one native check.
package platform

import "os"

const (
	MinimumBuild    = 26200 // Windows 11 25H2
	UnsupportedExit = 1633  // ERROR_INSTALL_PLATFORM_UNSUPPORTED
	Requirements    = "WatchMyMedia requires Windows 11 25H2 or later on an x64 PC.\n\nUpdate Windows or use a supported PC to run the desktop app."
	amd64           = 0x8664
)

// Set only by the separate development CI build, never by a runtime flag.
var ciServerHost string

type Host struct {
	Major         uint32 `json:"major"`
	Minor         uint32 `json:"minor"`
	Build         uint32 `json:"build"`
	ProductType   byte   `json:"productType"` // workstation=1, DC=2, server=3
	NativeMachine uint16 `json:"nativeMachine"`
}

func (h Host) DesktopSupported() bool {
	return h.NativeMachine == amd64 && h.ProductType == 1 &&
		(h.Major > 10 || h.Major == 10 && h.Build >= MinimumBuild)
}

func allowed(h Host, ciBuild bool, getenv func(string) string) bool {
	if h.DesktopSupported() {
		return true
	}
	// Hosted Server tests are software coverage, not desktop acceptance. This
	// exception cannot admit older client Windows or ARM64 emulation.
	return ciBuild && h.NativeMachine == amd64 && h.Major >= 10 &&
		(h.ProductType == 2 || h.ProductType == 3) &&
		getenv("GITHUB_ACTIONS") == "true" &&
		getenv("RUNNER_ENVIRONMENT") == "github-hosted" &&
		getenv("RUNNER_OS") == "Windows"
}

// Report is local diagnostic output; it contains no account or library data.
type Report struct {
	Host             Host   `json:"host"`
	DesktopSupported bool   `json:"desktopSupported"`
	CIOnly           bool   `json:"ciOnly"`
	Allowed          bool   `json:"allowed"`
	Reason           string `json:"reason,omitempty"`
}

func Inspect() Report {
	h, err := current()
	r := Report{Host: h, CIOnly: ciServerHost == "true"}
	if err != nil {
		r.Reason = "Could not check this PC: " + err.Error() + "\n\n" + Requirements
		return r
	}
	r.DesktopSupported = h.DesktopSupported()
	r.Allowed = allowed(h, r.CIOnly, os.Getenv)
	if !r.Allowed {
		r.Reason = Requirements
	}
	return r
}
