package playback

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
)

type playlistFile interface {
	io.ReadSeekCloser
	io.ReaderAt
}

// finishPlaylist runs after FFmpeg exits successfully, with playlist readers
// excluded. Its HLS muxer ignores the result of the final temporary-file
// rename: on Windows a competing rename can leave a complete .tmp behind
// while FFmpeg still exits zero. Publish that actual final playlist before
// marking the writer done. Never infer completion from a cached snapshot.
func finishPlaylist(path string) error {
	tmp := path + ".tmp"
	f, err := OpenShared(tmp)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	// Do not promote a truncated write, even after a successful process exit.
	const end = "\n#EXT-X-ENDLIST\n"
	info, err := f.Stat()
	var tail [len(end)]byte
	if err == nil && info.Size() >= int64(len(tail)) {
		_, err = f.ReadAt(tail[:], info.Size()-int64(len(tail)))
	}
	closeErr := f.Close() // no open source/destination handles during rename
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if !bytes.Equal(tail[:], []byte(end)) {
		return fmt.Errorf("unfinished HLS playlist %s", filepath.Base(tmp))
	}
	return os.Rename(tmp, path)
}

// ReadPlaylist returns the latest immutable event playlist. Windows claims a
// private snapshot before opening it so FFmpeg can publish at the original
// name. Cache the last snapshot for concurrent requests and after FFmpeg exits.
// Callers must treat the returned bytes as read-only.
func (s *Session) ReadPlaylist() ([]byte, error) {
	s.playlistMu.Lock()
	defer s.playlistMu.Unlock()
	f, err := openPlaylist(filepath.Join(s.Dir, PlaylistName))
	if err != nil {
		if errors.Is(err, os.ErrNotExist) && s.playlist != nil {
			if s.event != nil {
				s.event.observePlaylist(s.playlist)
			}
			return s.playlist, nil
		}
		return nil, err
	}
	defer func() { _ = f.Close() }()
	b, err := io.ReadAll(f)
	if err != nil {
		return nil, err
	}
	s.playlist = b
	if s.event != nil {
		s.event.observePlaylist(b)
	}
	return b, nil
}

// ReadShared reads an immutable init segment or media piece with shared-delete
// access. Mutable playlists use openPlaylist/Session.ReadPlaylist instead.
func ReadShared(path string) ([]byte, error) {
	f, err := OpenShared(path)
	if err != nil {
		return nil, err
	}
	defer func() { _ = f.Close() }()
	return io.ReadAll(f)
}
