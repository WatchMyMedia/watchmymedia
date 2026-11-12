package playback

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/ffexec"
)

func TestEventPreparationStopsAndResumesFromMediaRequests(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	t.Parallel()
	src := keyedSampleAt(t, ffmpeg, "24", 40, "0")
	m := testManager(t, ffmpeg, "software")
	m.eventAhead, m.eventResume = 12*time.Second, 4*time.Second
	m.resources.EventKeepBehind = time.Hour
	spec := vodTranscodeSpec(src)
	spec.VOD = nil
	s, err := m.Start(context.Background(), spec)
	if err != nil {
		t.Fatal(err)
	}
	defer m.Stop(s.ID)
	e := s.event
	eventually(t, "first event run did not pause", func() bool { return !e.running() && e.preparedMs.Load() >= 11_000 })
	first := e.preparedMs.Load()
	if first > 14_000 || m.Running() != 0 {
		t.Fatalf("first prepared=%d, running=%d", first, m.Running())
	}
	// A playlist poll does not advance preparation when no media is fetched.
	for range 3 {
		_, _ = s.ReadPlaylist()
	}
	if e.running() || e.preparedMs.Load() != first {
		t.Fatal("playlist polling restarted preparation")
	}
	for range 12 {
		b, err := s.ReadPlaylist()
		if err != nil {
			t.Fatal(err)
		}
		p := parseEventPlaylist(b)
		if strings.Contains(string(b), "#EXT-X-ENDLIST") {
			break
		}
		var last string
		for name, end := range p.segments {
			if end == p.endMs {
				last = name
			}
		}
		if last == "" {
			t.Fatal("no final listed segment")
		}
		if _, err := os.Stat(filepath.Join(s.Dir, last)); err != nil {
			t.Fatal(err)
		}
		s.EventSegmentRequested(last)
		want := p.endMs + 3000
		eventually(t, "next event run did not prepare", func() bool {
			b, _ := s.ReadPlaylist()
			return parseEventPlaylist(b).endMs >= want || s.Finished()
		})
	}
	select {
	case <-s.done:
	case <-time.After(20 * time.Second):
		t.Fatal("event did not finalize")
	}
	if err := s.Err(); err != nil {
		t.Fatal(err)
	}
	b, err := s.ReadPlaylist()
	if err != nil || !strings.Contains(string(b), "#EXT-X-ENDLIST") {
		t.Fatalf("final playlist: %v: %s", err, b)
	}
	// HTTP serves these immutable bytes. Windows has already claimed the
	// mutable filename, so decode a copy beside its relative media pieces.
	path := filepath.Join(s.Dir, "verification.m3u8")
	if err := os.WriteFile(path, b, 0o600); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(t.Context(), 20*time.Second)
	defer cancel()
	cmd := ffexec.Command(ctx, ffmpeg, ffexec.FFmpegArgs("-allowed_extensions", "ALL", "-i", path, "-map", "0:v:0", "-frames:v", "3", "-f", "null", "-")...)
	if err := cmd.Run(); err != nil {
		t.Fatalf("event output does not decode: %v", err)
	}
}

func TestEventPlaylistPrunesOnlyServedHistory(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	s := &Session{Dir: dir}
	e := &eventState{s: s, keepBehind: 4 * time.Second, m: NewManager("unused", t.TempDir(), Capabilities{}, time.Minute, 1, testLog)}
	s.event = e
	for i := range 4 {
		if err := os.WriteFile(filepath.Join(dir, "seg"+fmt.Sprintf("%05d.m4s", i)), []byte("piece"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	playlist := []byte("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:EVENT\n#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXT-X-DISCONTINUITY\n#EXTINF:4.000000,\nseg00000.m4s\n#EXTINF:4.000000,\nseg00001.m4s\n#EXT-X-DISCONTINUITY\n#EXTINF:4.000000,\nseg00002.m4s\n#EXTINF:4.000000,\nseg00003.m4s\n")
	if err := os.WriteFile(filepath.Join(dir, PlaylistName), playlist, 0o600); err != nil {
		t.Fatal(err)
	}
	e.servedMs.Store(12_000)
	e.prune(parseEventPlaylist(playlist))
	if e.evictedMs.Load() != 8_000 {
		t.Fatalf("evicted through %d", e.evictedMs.Load())
	}
	for i := range 2 {
		if _, err := os.Stat(filepath.Join(dir, fmt.Sprintf("seg%05d.m4s", i))); !os.IsNotExist(err) {
			t.Fatalf("old segment %d remains: %v", i, err)
		}
	}
	for i := 2; i < 4; i++ {
		if _, err := os.Stat(filepath.Join(dir, fmt.Sprintf("seg%05d.m4s", i))); err != nil {
			t.Fatal(err)
		}
	}
	filtered, err := s.EventPlaylist()
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(filtered), "seg00000") || strings.Contains(string(filtered), "PLAYLIST-TYPE:EVENT") || !strings.Contains(string(filtered), "#EXT-X-MEDIA-SEQUENCE:2\n") || !strings.Contains(string(filtered), "#EXT-X-DISCONTINUITY-SEQUENCE:1\n") {
		t.Fatalf("invalid sliding playlist:\n%s", filtered)
	}
	e.compactPlaylist()
	raw, err := s.ReadPlaylist()
	if err != nil {
		t.Fatal(err)
	}
	if e.baseMs.Load() != 8_000 || parseEventPlaylistAt(raw, e.baseMs.Load()).endMs != 16_000 || strings.Contains(string(raw), "seg00000") {
		t.Fatalf("compacted playlist base=%d, raw=%s", e.baseMs.Load(), raw)
	}
}

func TestEventAppendSourceSurvivesPlaylistClaim(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	s := &Session{Dir: dir}
	e := &eventState{s: s}
	path := filepath.Join(dir, PlaylistName)
	s.playlist = []byte("#EXTM3U\n#EXTINF:4,\nseg00000.m4s\n")
	if err := e.ensureAppendSource(); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(path)
	if err != nil || string(b) != string(s.playlist) {
		t.Fatalf("restored playlist: %v, %q", err, b)
	}
}
