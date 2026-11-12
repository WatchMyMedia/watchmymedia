package hwdevice

import (
	"testing"
	"unsafe"
)

func TestDXGIDescriptionMatchesTheWindowsSDK(t *testing.T) {
	if unsafe.Sizeof(uintptr(0)) != 8 {
		t.Skip("64-bit Windows ABI")
	}
	var d adapterDescription1
	if unsafe.Sizeof(d) != 312 || unsafe.Offsetof(d.VendorID) != 256 || unsafe.Offsetof(d.LUID) != 296 || unsafe.Offsetof(d.Flags) != 304 {
		t.Fatalf("DXGI_ADAPTER_DESC1 does not match SDK: size=%d flags=%d", unsafe.Sizeof(d), unsafe.Offsetof(d.Flags))
	}
}
