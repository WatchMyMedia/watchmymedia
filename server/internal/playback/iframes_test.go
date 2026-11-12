package playback

import (
	"context"
	"errors"
	"os"
	"strings"
	"testing"
)

// The I-frame playlist lists every frame by byte range at its tile's time,
// the last until the end of the file; the peak bit rate is the largest
// frame for the time it shows.
func TestIFramePlaylist(t *testing.T) {
	t.Parallel()
	tr := &IFrameTrack{Path: "/t/iframes.mp4", InitSize: 700, IntervalMs: 10_000,
		Frames: []ByteRange{{700, 5000}, {5700, 6000}, {11_700, 2000}}}
	want := `#EXTM3U
#EXT-X-VERSION:7
#EXT-X-TARGETDURATION:10
#EXT-X-MEDIA-SEQUENCE:0
#EXT-X-PLAYLIST-TYPE:VOD
#EXT-X-I-FRAMES-ONLY
#EXT-X-MAP:URI="iframes.mp4",BYTERANGE="700@0"
#EXTINF:10.000,
#EXT-X-BYTERANGE:5000@700
iframes.mp4
#EXTINF:10.000,
#EXT-X-BYTERANGE:6000@5700
iframes.mp4
#EXTINF:2.500,
#EXT-X-BYTERANGE:2000@11700
iframes.mp4
#EXT-X-ENDLIST
`
	if got := string(tr.Playlist(22_500)); got != want {
		t.Errorf("playlist:\n%s\nwant:\n%s", got, want)
	}
	// 2000 bytes shown for 2.5 s is the peak: 6400 bit/s.
	if bw := tr.Bandwidth(22_500); bw != 6400 {
		t.Errorf("bandwidth %d, want 6400", bw)
	}
	// A duration the tiles overrun gives the last its interval.
	if !strings.Contains(string(tr.Playlist(19_000)), "#EXTINF:10.000,\n#EXT-X-BYTERANGE:2000@11700") {
		t.Error("the last frame's time without a duration past it")
	}
}

// The master playlist offers the tiles as an SDR I-frame stream, whatever
// the video; a session without them offers none and has no such files.
func TestVODMasterIFrameStream(t *testing.T) {
	t.Parallel()
	track := &IFrameTrack{InitSize: 10, IntervalMs: 10_000, Frames: []ByteRange{{10, 20}}}
	v := &vodState{spec: Spec{Video: VideoPlan{Width: 3840, Height: 2160}}, opts: VODOptions{DurationMs: 8000, VideoRange: "PQ", IFrames: &IFrameStream{
		Bandwidth: 9000, Codecs: IFrameCodecs, Width: 320, Height: 180,
		Track: func(context.Context) (*IFrameTrack, error) { return track, nil },
	}}}
	master := string(v.Master())
	if !strings.HasSuffix(master, "video.m3u8\n#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=9000,CODECS=\"avc1.4D401E\",RESOLUTION=320x180,VIDEO-RANGE=SDR,URI=\"iframes.m3u8\"\n") {
		t.Errorf("master:\n%s", master)
	}
	s := &Session{vod: v}
	b, ct, err := s.VODFile(t.Context(), VODIFrames)
	if err != nil || ct != "application/vnd.apple.mpegurl" || !strings.Contains(string(b), "#EXTINF:8.000,\n#EXT-X-BYTERANGE:20@10\niframes.mp4") {
		t.Errorf("iframes.m3u8: %v %s\n%s", err, ct, b)
	}

	v.opts.IFrames = nil
	if strings.Contains(string(v.Master()), "I-FRAME") {
		t.Error("a session without thumbnails offers an I-frame stream")
	}
	if _, _, err := s.VODFile(t.Context(), VODIFrames); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("iframes.m3u8 without thumbnails: %v", err)
	}
	if _, err := s.IFrameTrack(t.Context()); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("I-frame track without thumbnails: %v", err)
	}
}
