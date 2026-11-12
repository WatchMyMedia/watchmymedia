package playback

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// FFmpeg publishes HLS playlists by renaming a temporary file over the old
// one. An HTTP reader must not prevent that replacement on Windows.
func TestPlaylistSnapshotDoesNotBlockReplacement(t *testing.T) {
	t.Parallel()
	path := filepath.Join(t.TempDir(), "Café's playlist.m3u8")
	if err := os.WriteFile(path, []byte("old playlist"), 0o600); err != nil {
		t.Fatal(err)
	}
	reader, err := openPlaylist(path)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	if err := os.WriteFile(path+".tmp", []byte("new playlist"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(path+".tmp", path); err != nil {
		t.Fatalf("an active playlist reader blocked replacement: %v", err)
	}
	old, err := io.ReadAll(reader)
	if err != nil || string(old) != "old playlist" {
		t.Fatalf("active reader lost its snapshot: %q, %v", old, err)
	}
	newContent, err := os.ReadFile(path)
	if err != nil || string(newContent) != "new playlist" {
		t.Fatalf("new readers lost the replacement: %q, %v", newContent, err)
	}
}

func TestEventPlaylistCachesTheLastSnapshot(t *testing.T) {
	t.Parallel()
	s := &Session{Dir: t.TempDir()}
	path := filepath.Join(s.Dir, PlaylistName)
	write := func(text string) {
		t.Helper()
		if err := os.WriteFile(path, []byte(text), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	write("first complete playlist")
	first, err := s.ReadPlaylist()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		t.Fatal(err)
	}
	// Windows has already claimed the file. Slow/repeated HTTP requests must
	// still see the complete last snapshot even after FFmpeg has exited.
	last, err := s.ReadPlaylist()
	if err != nil || string(last) != string(first) {
		t.Fatalf("missing cached playlist: %q, %v", last, err)
	}
	write("second complete playlist")
	second, err := s.ReadPlaylist()
	if err != nil || string(second) != "second complete playlist" || string(first) != "first complete playlist" {
		t.Fatalf("snapshot changed in place: first=%q second=%q err=%v", first, second, err)
	}
	entries, err := os.ReadDir(s.Dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range entries {
		if strings.Contains(entry.Name(), ".snapshot-") {
			t.Fatalf("private snapshot was not removed: %s", entry.Name())
		}
	}
}

func TestFinishedPlaylistRecoversFinalPublication(t *testing.T) {
	t.Parallel()
	const first = "#EXTM3U\n#EXTINF:4,\nseg00000.m4s\n"
	const final = first + "#EXTINF:2,\nseg00001.m4s\n#EXT-X-ENDLIST\n"
	for _, published := range []bool{false, true} {
		t.Run(fmt.Sprintf("published=%t", published), func(t *testing.T) {
			t.Parallel()
			s := &Session{Dir: t.TempDir()}
			path := filepath.Join(s.Dir, PlaylistName)
			write := func(path, text string) {
				t.Helper()
				if err := os.WriteFile(path, []byte(text), 0o600); err != nil {
					t.Fatal(err)
				}
			}
			write(path, first)
			cached, err := s.ReadPlaylist()
			if err != nil {
				t.Fatal(err)
			}
			// Model a final FFmpeg rename failure with either an old destination
			// still present or one already claimed by a Windows reader.
			if published {
				write(path, first)
			} else if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
				t.Fatal(err)
			}
			write(path+".tmp", final)
			active, err := s.ReadPlaylist()
			if err != nil || string(active) != first {
				t.Fatalf("read a temporary playlist while the writer was active: %q, %v", active, err)
			}
			if published {
				write(path, first)
			}
			if err := finishPlaylist(path); err != nil {
				t.Fatal(err)
			}
			got, err := s.ReadPlaylist()
			if err != nil || string(got) != final {
				t.Fatalf("lost final segments or end marker: %q, %v", got, err)
			}
			if string(cached) != first {
				t.Fatalf("changed an earlier HTTP reader's snapshot: %q", cached)
			}
			if _, err := os.Stat(path + ".tmp"); !os.IsNotExist(err) {
				t.Fatalf("left the recovered temporary file behind: %v", err)
			}
		})
	}
}

func TestFinishedPlaylistRejectsTruncatedPublication(t *testing.T) {
	t.Parallel()
	for _, partial := range []string{"", "#EXTM3U\n#EXTINF:4,\nseg00000.m4s\n", "#EXTM3U\n#EXT-X-ENDLIST"} {
		t.Run(fmt.Sprintf("bytes=%d", len(partial)), func(t *testing.T) {
			t.Parallel()
			path := filepath.Join(t.TempDir(), PlaylistName)
			const previous = "previous published playlist"
			if err := os.WriteFile(path, []byte(previous), 0o600); err != nil {
				t.Fatal(err)
			}
			// An ordinary successful publication leaves no .tmp to recover.
			if err := finishPlaylist(path); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(path+".tmp", []byte(partial), 0o600); err != nil {
				t.Fatal(err)
			}
			if err := finishPlaylist(path); err == nil {
				t.Fatal("accepted a truncated final playlist")
			}
			got, err := os.ReadFile(path)
			if err != nil || string(got) != previous {
				t.Fatalf("replaced the published playlist with a truncated write: %q, %v", got, err)
			}
		})
	}
}

func TestSharedSegmentCanBeRenamedAwayWhileReading(t *testing.T) {
	t.Parallel()
	path := filepath.Join(t.TempDir(), "segment.m4s")
	if err := os.WriteFile(path, []byte("segment bytes"), 0o600); err != nil {
		t.Fatal(err)
	}
	reader, err := OpenShared(path)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	if err := os.Rename(path, path+".old"); err != nil {
		t.Fatalf("immutable reader prevented rename away: %v", err)
	}
	b, err := io.ReadAll(reader)
	if err != nil || string(b) != "segment bytes" {
		t.Fatalf("renamed segment lost data: %q %v", b, err)
	}
}

func TestSharedReaderSupportsLongPaths(t *testing.T) {
	t.Parallel()
	dir := filepath.Join(t.TempDir(), strings.Repeat("folder-", 15), strings.Repeat("another-", 14), "Café's media")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "segment.m4s")
	if len(path) <= 260 {
		t.Fatalf("fixture is not a long path: %d", len(path))
	}
	if err := os.WriteFile(path, []byte("long-path segment"), 0o600); err != nil {
		t.Fatal(err)
	}
	b, err := ReadShared(path)
	if err != nil || string(b) != "long-path segment" {
		t.Fatalf("shared read lost long-path support: %q %v", b, err)
	}
}
