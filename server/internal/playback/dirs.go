package playback

import (
	"os"
	"path/filepath"
	"regexp"
)

// SessionsDir is the folder inside transcode.dir that the server owns. It
// keeps every session's files there and clears that folder, and nothing
// else, at startup: people point transcode.dir at /tmp or /dev/shm, which
// other programs use too.
const SessionsDir = "watchmymedia-sessions"

// Clean removes what an earlier run of the server left behind: the sessions
// folder's contents, and session folders that servers before SessionsDir
// wrote straight into transcode.dir, which are only taken when every file in
// them is one a session writes. Call it before the first session starts.
func (m *Manager) Clean() error {
	if err := os.RemoveAll(m.root); err != nil {
		return err
	}
	if err := os.MkdirAll(m.root, 0o755); err != nil {
		return err
	}
	entries, err := os.ReadDir(filepath.Dir(m.root))
	if err != nil {
		return err
	}
	for _, e := range entries {
		dir := filepath.Join(filepath.Dir(m.root), e.Name())
		if e.IsDir() && legacySessionRe.MatchString(e.Name()) && onlySessionFiles(dir) {
			m.removeDir(dir)
		}
	}
	return nil
}

var (
	// legacySessionRe is a session ID (newID) as a folder name.
	legacySessionRe = regexp.MustCompile(`^[0-9a-f]{16}$`)
	// sessionFileRe matches every file an event or VOD session writes,
	// ffmpeg's temporary files included.
	sessionFileRe = regexp.MustCompile(`^(index\.m3u8|init\.mp4|seg\d{5}\.m4s|r\d+(\.m3u8|-init\.mp4|-\d{5}\.m4s))(\.tmp)?$`)
)

// onlySessionFiles reports whether dir holds files a session writes and
// nothing else.
func onlySessionFiles(dir string) bool {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return false
	}
	for _, e := range entries {
		if !e.Type().IsRegular() || !sessionFileRe.MatchString(e.Name()) {
			return false
		}
	}
	return true
}
