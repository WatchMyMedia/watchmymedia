package winui

import (
	"encoding/base64"
	"fmt"
	"golang.org/x/sys/windows"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"syscall"
	"unsafe"
)

// Set only by a signed release build. With either missing, no DLL is loaded
// and no background network traffic is introduced by the development app.
var updateFeedURL, updatePublicKey string

type updater struct{ dll *windows.DLL }

func cstr(s string) *byte { p, _ := windows.BytePtrFromString(s); return p }
func buildNumber(version string) (string, error) {
	m := regexp.MustCompile(`^v?(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+))?$`).FindStringSubmatch(version)
	if m == nil {
		return "", fmt.Errorf("not a release version")
	}
	major, _ := strconv.Atoi(m[1])
	minor, _ := strconv.Atoi(m[2])
	patch, _ := strconv.Atoi(m[3])
	n, _ := strconv.Atoi(m[5])
	last := 99
	if m[4] != "" {
		last = map[string]int{"alpha": 0, "beta": 30, "rc": 60}[m[4]] + n
	}
	if major > 209 || minor > 99 || patch > 999 || n > 29 {
		return "", fmt.Errorf("release version out of range")
	}
	return strconv.Itoa(major*10000000 + minor*100000 + patch*100 + last), nil
}
func newUpdater(version string, shutdown func()) (*updater, error) {
	if updateFeedURL == "" || updatePublicKey == "" {
		return nil, nil
	}
	u, e := url.Parse(updateFeedURL)
	if e != nil || u.Scheme != "https" || u.Host == "" || u.User != nil {
		return nil, fmt.Errorf("Windows update feed must use HTTPS")
	}
	key, e := base64.StdEncoding.DecodeString(updatePublicKey)
	if e != nil || len(key) != 32 {
		return nil, fmt.Errorf("invalid Windows update public key")
	}
	build, e := buildNumber(version)
	if e != nil {
		return nil, e
	}
	exe, e := os.Executable()
	if e != nil {
		return nil, e
	}
	// This also restricts dependency resolution; never search the current directory.
	h, e := windows.LoadLibraryEx(filepath.Join(filepath.Dir(exe), "WinSparkle.dll"), 0, windows.LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR|windows.LOAD_LIBRARY_SEARCH_SYSTEM32)
	if e != nil {
		return nil, e
	}
	d := &windows.DLL{Name: "WinSparkle.dll", Handle: h}
	names := []string{"win_sparkle_set_eddsa_public_key", "win_sparkle_set_appcast_url", "win_sparkle_set_app_details", "win_sparkle_set_app_build_version", "win_sparkle_set_automatic_check_for_updates", "win_sparkle_set_shutdown_request_callback", "win_sparkle_init", "win_sparkle_cleanup", "win_sparkle_check_update_with_ui"}
	procs := map[string]*windows.Proc{}
	for _, name := range names {
		p, e := d.FindProc(name)
		if e != nil {
			d.Release()
			return nil, e
		}
		procs[name] = p
	}
	r, _, _ := procs[names[0]].Call(uintptr(unsafe.Pointer(cstr(updatePublicKey))))
	if r != 1 {
		d.Release()
		return nil, fmt.Errorf("WinSparkle rejected the update public key")
	}
	procs[names[1]].Call(uintptr(unsafe.Pointer(cstr(updateFeedURL))))
	procs[names[2]].Call(uintptr(unsafe.Pointer(u16("Eldwick Software LLC"))), uintptr(unsafe.Pointer(u16("WatchMyMedia"))), uintptr(unsafe.Pointer(u16(version))))
	procs[names[3]].Call(uintptr(unsafe.Pointer(u16(build))))
	// Manual checks for now, matching the existing Settings action. The DLL
	// verifies the installer EdDSA signature before offering installation.
	procs[names[4]].Call(0)
	procs[names[5]].Call(syscall.NewCallback(func() uintptr { shutdown(); return 0 }))
	procs[names[6]].Call()
	return &updater{dll: d}, nil
}
func (u *updater) check() {
	if u != nil {
		u.dll.MustFindProc("win_sparkle_check_update_with_ui").Call()
	}
}
func (u *updater) close() {
	if u != nil {
		u.dll.MustFindProc("win_sparkle_cleanup").Call()
		u.dll.Release()
	}
}
