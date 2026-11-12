package playback

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strings"
)

// An I-frame playlist for VOD sessions (PLAN-TRICKPLAY.md §7.3, T4): a
// file's seek thumbnails as a small all-intra H.264 track, one frame per
// tile, listed by byte range. AVPlayerViewController draws thumbnails on
// its transport bar only from one, which is how the Apple TV app shows
// them; other players ignore the stream.

// File names of the I-frame playlist and its track in a VOD session.
const (
	VODIFrames     = "iframes.m3u8"
	VODIFramesData = "iframes.mp4"
)

// IFrameCodecs is the track's CODECS as the trickplay package encodes it:
// H.264 Main at level 3.0, which holds any tile 320 pixels wide.
const IFrameCodecs = "avc1.4D401E"

// IFrameStream offers a file's seek thumbnails as the master playlist's
// I-frame stream.
type IFrameStream struct {
	// Bandwidth is the stream's peak bit rate (EXT-X-I-FRAME-STREAM-INF),
	// estimated from the tiles until the track is made.
	Bandwidth int64
	// Codecs is IFrameCodecs, or what a track already made says.
	Codecs        string
	Width, Height int
	// Track returns the track, made from the tiles the first time a
	// player asks for it.
	Track func(ctx context.Context) (*IFrameTrack, error)
}

// IFrameTrack is a fragmented MP4 of seek thumbnails: its init segment is
// its first InitSize bytes, then one fragment (moof and mdat) per tile,
// tile i at file time i × IntervalMs, placed on the media timeline as VOD
// sessions place file time (vodTimeOffset).
type IFrameTrack struct {
	Path       string
	InitSize   int64
	Frames     []ByteRange
	IntervalMs int64
	Codecs     string
}

// ByteRange is a part of a file.
type ByteRange struct{ Offset, Size int64 }

// PackIFrames makes ffmpeg's fragmented MP4 of tiles (one fragment per
// frame, times from zero, as the trickplay package encodes it) into an
// I-frame track that lines up with VOD sessions: the init segment without
// edit lists and every fragment's decode time moved to file time on the
// media timeline, as a session's runs are (patchFragment). b is rewritten;
// the track's bytes are returned with its layout, for the caller to give a
// Path and IntervalMs.
func PackIFrames(b []byte) ([]byte, IFrameTrack, error) {
	var t IFrameTrack
	top, err := mp4Boxes(b, 0, len(b))
	if err != nil {
		return nil, t, err
	}
	first := -1
	for i, x := range top {
		if x.typ == "moof" {
			first = i
			break
		}
	}
	if first <= 0 {
		return nil, t, errors.New("mp4: no init segment and fragments")
	}
	init, err := parseInit(b[:top[first].start])
	if err != nil {
		return nil, t, err
	}
	if init.Video == 0 {
		return nil, t, errors.New("mp4: no video track")
	}
	out := append([]byte(nil), init.Stripped...)
	for i := first; i < len(top); i++ {
		if top[i].typ != "moof" {
			continue
		}
		if i+1 >= len(top) || top[i+1].typ != "mdat" {
			return nil, t, errors.New("mp4: fragment without mdat")
		}
		moof, mdat := top[i], top[i+1]
		if err := patchMoof(b[moof.start:moof.end], init); err != nil {
			return nil, t, err
		}
		t.Frames = append(t.Frames, ByteRange{Offset: int64(len(out)), Size: int64(mdat.end - moof.start)})
		out = append(out, b[moof.start:mdat.end]...)
	}
	if len(t.Frames) == 0 {
		return nil, t, errors.New("mp4: no frames")
	}
	t.InitSize, t.Codecs = int64(len(init.Stripped)), avcConfigCodecs(init.Stripped)
	return out, t, nil
}

// ReadIFrames reads a packed track's layout, for the caller to give a Path
// and IntervalMs.
func ReadIFrames(b []byte) (IFrameTrack, error) {
	var t IFrameTrack
	top, err := mp4Boxes(b, 0, len(b))
	if err != nil {
		return t, err
	}
	for i, x := range top {
		if x.typ != "moof" {
			continue
		}
		if i+1 >= len(top) || top[i+1].typ != "mdat" {
			return t, errors.New("mp4: fragment without mdat")
		}
		if t.InitSize == 0 {
			t.InitSize = int64(x.start)
		}
		t.Frames = append(t.Frames, ByteRange{Offset: int64(x.start), Size: int64(top[i+1].end - x.start)})
	}
	if t.InitSize == 0 || len(t.Frames) == 0 {
		return t, errors.New("mp4: not an I-frame track")
	}
	t.Codecs = avcConfigCodecs(b[:t.InitSize])
	return t, nil
}

// avcConfigCodecs is the CODECS of the first H.264 configuration in an
// init segment ("avc1.4D401E"), or IFrameCodecs when there is none to read.
func avcConfigCodecs(init []byte) string {
	i := strings.Index(string(init), "avcC")
	if i < 0 || i+8 > len(init) {
		return IFrameCodecs
	}
	return fmt.Sprintf("avc1.%02X%02X%02X", init[i+5], init[i+6], init[i+7])
}

// frameMs is how long frame i shows: until the next tile, the last until
// the end of the file.
func (t *IFrameTrack) frameMs(i int, durationMs int64) int64 {
	if i == len(t.Frames)-1 {
		if rest := durationMs - int64(i)*t.IntervalMs; rest > 0 {
			return rest
		}
	}
	return t.IntervalMs
}

// Bandwidth is the track's peak bit rate, frame by frame.
func (t *IFrameTrack) Bandwidth(durationMs int64) int64 {
	var peak int64
	for i, f := range t.Frames {
		peak = max(peak, f.Size*8*1000/max(t.frameMs(i, durationMs), 1))
	}
	return peak
}

// Playlist is the I-frame playlist for a file of durationMs: every frame
// of the track by byte range, at its tile's time.
func (t *IFrameTrack) Playlist(durationMs int64) []byte {
	var b strings.Builder
	longest := int64(0)
	for i := range t.Frames {
		longest = max(longest, t.frameMs(i, durationMs))
	}
	fmt.Fprintf(&b, "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:%d\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-I-FRAMES-ONLY\n",
		(longest+999)/1000)
	fmt.Fprintf(&b, "#EXT-X-MAP:URI=%q,BYTERANGE=\"%d@0\"\n", VODIFramesData, t.InitSize)
	for i, f := range t.Frames {
		fmt.Fprintf(&b, "#EXTINF:%.3f,\n#EXT-X-BYTERANGE:%d@%d\n%s\n", float64(t.frameMs(i, durationMs))/1000, f.Size, f.Offset, VODIFramesData)
	}
	b.WriteString("#EXT-X-ENDLIST\n")
	return []byte(b.String())
}

// IFrameTrack is a VOD session's I-frame track, made the first time it is
// asked for; os.ErrNotExist when the session offers none.
func (s *Session) IFrameTrack(ctx context.Context) (*IFrameTrack, error) {
	if s.vod == nil || s.vod.opts.IFrames == nil {
		return nil, os.ErrNotExist
	}
	return s.vod.opts.IFrames.Track(ctx)
}
