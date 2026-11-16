package plex

import "embed"

// The fixtures are compiled into the test binary rather than read from disk:
// Go's test cache checks files a test opens by modification time, which a
// fresh checkout resets, so a package that opens testdata/ re-runs on every
// CI run. Embedded files are checked by content.
//
//go:embed testdata
var fixtures embed.FS
