package winui

import (
	"testing"
	"unsafe"
)

func TestWin32Layouts(t *testing.T) {
	// The tray is built for amd64. These sizes match the Windows SDK ABI;
	// a missing field/padding silently corrupts callbacks and notification data.
	if unsafe.Sizeof(uintptr(0)) != 8 {
		t.Skip("x64 tray")
	}
	for name, pair := range map[string][2]uintptr{"NOTIFYICONDATAW": {unsafe.Sizeof(iconData{}), 976}, "WNDCLASSEXW": {unsafe.Sizeof(windowClass{}), 80}, "MSG": {unsafe.Sizeof(msg{}), 48}} {
		if pair[0] != pair[1] {
			t.Errorf("%s: size %d, want %d", name, pair[0], pair[1])
		}
	}
	if got := menuText("A&B\tC"); got != "A&&B C" {
		t.Fatal(got)
	}
}
