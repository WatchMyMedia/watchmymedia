// Package winpath prepares paths passed directly to Windows file APIs.
package winpath

import (
	"path/filepath"
	"strings"
)

// Extended preserves short-path rules and adds extended prefixes to cleaned
// absolute drive/UNC paths which raw Windows APIs would otherwise reject.
// Go's os package does this itself; CreateFile and MoveFileEx callers must too.
func Extended(path string) (string, error) {
	if strings.HasPrefix(path, `\\?\`) || strings.HasPrefix(path, `\\.\`) {
		return path, nil
	}
	abs, err := filepath.Abs(path)
	if err != nil {
		return "", err
	}
	if len(abs) < 248 {
		return path, nil
	}
	if strings.HasPrefix(abs, `\\`) {
		return `\\?\UNC\` + abs[2:], nil
	}
	return `\\?\` + abs, nil
}
