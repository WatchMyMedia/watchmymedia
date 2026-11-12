//go:build !windows

package playback

import "os"

// OpenShared opens a file ffmpeg may replace while it is open. Outside
// Windows a file can be renamed over while open, so this is os.Open.
func OpenShared(path string) (*os.File, error) { return os.Open(path) }

func openPlaylist(path string) (playlistFile, error) { return OpenShared(path) }
