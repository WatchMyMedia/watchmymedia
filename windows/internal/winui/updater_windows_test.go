package winui

import (
	"encoding/base64"
	"golang.org/x/sys/windows"
	"os"
	"testing"
	"unsafe"
)

func TestUpdateBuildOrder(t *testing.T) {
	for v, want := range map[string]string{"v0.5.3": "500399", "0.5.3-alpha.1": "500301", "0.5.3-beta.1": "500331", "0.5.3-rc.1": "500361"} {
		got, e := buildNumber(v)
		if e != nil || got != want {
			t.Errorf("%s: %s %v", v, got, e)
		}
	}
	for _, v := range []string{"development", "v1.0.0-rc.30", "1.100.0", "210.0.0", "1.0.1000"} {
		if _, e := buildNumber(v); e == nil {
			t.Errorf("accepted %s", v)
		}
	}
}
func TestUpdatesStayOffAndRejectInvalidConfig(t *testing.T) {
	oldURL, oldKey := updateFeedURL, updatePublicKey
	t.Cleanup(func() { updateFeedURL, updatePublicKey = oldURL, oldKey })
	updateFeedURL, updatePublicKey = "", ""
	u, e := newUpdater("development", func() { t.Fatal("shutdown") })
	if u != nil || e != nil {
		t.Fatalf("development updater: %v %v", u, e)
	}
	for _, cfg := range [][2]string{{"http://example.invalid/feed", "key"}, {"https://example.invalid/feed", "invalid"}, {"https://example.invalid/feed", base64.StdEncoding.EncodeToString(make([]byte, 32))}} {
		updateFeedURL, updatePublicKey = cfg[0], cfg[1]
		// Development version is rejected before loading a DLL or starting workers.
		if _, e := newUpdater("development", func() {}); e == nil {
			t.Fatal("accepted unusable configuration")
		}
	}
}
func TestPinnedUpdaterDLL(t *testing.T) {
	path := os.Getenv("WMM_WINSPARKLE_TEST_DLL")
	if path == "" {
		t.Skip("build the app to verify its pinned DLL")
	}
	h, e := windows.LoadLibraryEx(path, 0, windows.LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR|windows.LOAD_LIBRARY_SEARCH_SYSTEM32)
	if e != nil {
		t.Fatal(e)
	}
	d := &windows.DLL{Name: path, Handle: h}
	defer d.Release()
	for _, name := range []string{"win_sparkle_set_eddsa_public_key", "win_sparkle_set_appcast_url", "win_sparkle_set_app_details", "win_sparkle_set_app_build_version", "win_sparkle_set_automatic_check_for_updates", "win_sparkle_set_shutdown_request_callback", "win_sparkle_init", "win_sparkle_cleanup", "win_sparkle_check_update_with_ui"} {
		if _, e := d.FindProc(name); e != nil {
			t.Fatal(e)
		}
	}
	p := d.MustFindProc("win_sparkle_set_eddsa_public_key")
	// Ed25519 public key from the RFC 8032 test vector; not a signing secret.
	key := base64.StdEncoding.EncodeToString([]byte{0xd7, 0x5a, 0x98, 0x01, 0x82, 0xb1, 0x0a, 0xb7, 0xd5, 0x4b, 0xfe, 0xd3, 0xc9, 0x64, 0x07, 0x3a, 0x0e, 0xe1, 0x72, 0xf3, 0xda, 0xa6, 0x23, 0x25, 0xaf, 0x02, 0x1a, 0x68, 0xf7, 0x07, 0x51, 0x1a})
	if r, _, _ := p.Call(uintptr(unsafe.Pointer(cstr(key)))); r != 1 {
		t.Fatal("valid Ed25519 public key rejected")
	}
	if r, _, _ := p.Call(uintptr(unsafe.Pointer(cstr("not-a-key")))); r != 0 {
		t.Fatal("invalid update key accepted")
	}
}
