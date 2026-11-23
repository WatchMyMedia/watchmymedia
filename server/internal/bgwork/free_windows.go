package bgwork

import "golang.org/x/sys/windows"

// FreeBytes is the space free to the server on the disk that holds dir.
func FreeBytes(dir string) (uint64, error) {
	p, err := windows.UTF16PtrFromString(dir)
	if err != nil {
		return 0, err
	}
	var free uint64
	if err := windows.GetDiskFreeSpaceEx(p, &free, nil, nil); err != nil {
		return 0, err
	}
	return free, nil
}

// TotalBytes is the size of the disk that holds dir.
func TotalBytes(dir string) (uint64, error) {
	p, err := windows.UTF16PtrFromString(dir)
	if err != nil {
		return 0, err
	}
	var total uint64
	if err := windows.GetDiskFreeSpaceEx(p, nil, &total, nil); err != nil {
		return 0, err
	}
	return total, nil
}
