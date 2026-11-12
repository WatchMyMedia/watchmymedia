package hwdevice

import (
	"fmt"
	"runtime"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

func nativeAdapters(namespace string) ([]Adapter, error) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	if namespace == "cuda" {
		return cudaAdapters()
	}
	return directXAdapters()
}

// SDK dxgi.h: IDXGIFactory's EnumAdapters is slot 7; IDXGIAdapter1's
// GetDesc1 is slot 10. Use EnumAdapters, as FFmpeg's D3D11 device path does.
type dxgiObject struct{ vtable *[12]uintptr }

func (o *dxgiObject) release() { _, _, _ = syscall.SyscallN(o.vtable[2], uintptr(unsafe.Pointer(o))) }

type adapterDescription1 struct {
	Description                                   [128]uint16
	VendorID, DeviceID, SubSysID, Revision        uint32
	DedicatedVideo, DedicatedSystem, SharedSystem uintptr
	LUID                                          windows.LUID
	Flags                                         uint32
}

func directXAdapters() ([]Adapter, error) {
	dll := windows.NewLazySystemDLL("dxgi.dll")
	// Match the pinned FFmpeg's CreateDXGIFactory1 + IDXGIFactory2 request,
	// including its older entry-point fallback. EnumAdapters is inherited.
	create := dll.NewProc("CreateDXGIFactory1")
	if err := create.Find(); err != nil {
		create = dll.NewProc("CreateDXGIFactory")
		if err := create.Find(); err != nil {
			return nil, fmt.Errorf("load DXGI: %w", err)
		}
	}
	iidFactory := windows.GUID{Data1: 0x50c83a1c, Data2: 0xe072, Data3: 0x4c48, Data4: [8]byte{0x87, 0xb0, 0x36, 0x30, 0xfa, 0x36, 0xa6, 0xd0}}
	var factory *dxgiObject
	hr, _, _ := create.Call(uintptr(unsafe.Pointer(&iidFactory)), uintptr(unsafe.Pointer(&factory)))
	if int32(hr) < 0 || factory == nil {
		return nil, fmt.Errorf("create DXGI factory: HRESULT %#x", uint32(hr))
	}
	defer factory.release()
	return collectDirectX(func(index int) (directXDescription, error) {
		var adapter *dxgiObject
		hr, _, _ := syscall.SyscallN(factory.vtable[7], uintptr(unsafe.Pointer(factory)), uintptr(index), uintptr(unsafe.Pointer(&adapter)))
		if uint32(hr) == 0x887a0002 {
			return directXDescription{}, errNoMoreAdapters
		}
		if int32(hr) < 0 || adapter == nil {
			return directXDescription{}, fmt.Errorf("enumerate DirectX adapter %d: HRESULT %#x", index, uint32(hr))
		}
		defer adapter.release()
		iidAdapter1 := windows.GUID{Data1: 0x29038f61, Data2: 0x3839, Data3: 0x4626, Data4: [8]byte{0x91, 0xfd, 0x08, 0x68, 0x79, 0x01, 0x1a, 0x05}}
		var adapter1 *dxgiObject
		hr, _, _ = syscall.SyscallN(adapter.vtable[0], uintptr(unsafe.Pointer(adapter)), uintptr(unsafe.Pointer(&iidAdapter1)), uintptr(unsafe.Pointer(&adapter1)))
		if int32(hr) < 0 || adapter1 == nil {
			return directXDescription{}, fmt.Errorf("query DirectX adapter %d: HRESULT %#x", index, uint32(hr))
		}
		defer adapter1.release()
		var d adapterDescription1
		hr, _, _ = syscall.SyscallN(adapter1.vtable[10], uintptr(unsafe.Pointer(adapter1)), uintptr(unsafe.Pointer(&d)))
		if int32(hr) < 0 {
			return directXDescription{}, fmt.Errorf("describe DirectX adapter %d: HRESULT %#x", index, uint32(hr))
		}
		return directXDescription{Name: windows.UTF16ToString(d.Description[:]), VendorID: d.VendorID, Software: d.Flags&2 != 0}, nil
	})
}

func cudaAdapters() ([]Adapter, error) {
	dll := windows.NewLazySystemDLL("nvcuda.dll")
	names := []string{"cuInit", "cuDeviceGetCount", "cuDeviceGet", "cuDeviceGetName"}
	procs := make([]*windows.LazyProc, len(names))
	for n, name := range names {
		procs[n] = dll.NewProc(name)
		if err := procs[n].Find(); err != nil {
			return nil, fmt.Errorf("load CUDA driver %s: %w", name, err)
		}
	}
	code, _, _ := procs[0].Call(0)
	if code != 0 {
		return nil, fmt.Errorf("initialize CUDA driver: error %d", code)
	}
	var count int32
	code, _, _ = procs[1].Call(uintptr(unsafe.Pointer(&count)))
	if code != 0 {
		return nil, fmt.Errorf("count CUDA devices: error %d", code)
	}
	if count < 0 || count > maxAdapters {
		return nil, fmt.Errorf("invalid CUDA device count %d", count)
	}
	adapters := []Adapter{}
	for index := 0; index < int(count); index++ {
		var device int32
		code, _, _ = procs[2].Call(uintptr(unsafe.Pointer(&device)), uintptr(index))
		if code != 0 {
			return nil, fmt.Errorf("read CUDA device %d: error %d", index, code)
		}
		var name [256]byte
		code, _, _ = procs[3].Call(uintptr(unsafe.Pointer(&name[0])), uintptr(len(name)), uintptr(device))
		if code != 0 {
			return nil, fmt.Errorf("name CUDA device %d: error %d", index, code)
		}
		adapters = append(adapters, Adapter{Namespace: "cuda", Index: index, Name: adapterName(windows.ByteSliceToString(name[:]), "cuda", index), VendorID: 0x10de})
	}
	return adapters, nil
}
