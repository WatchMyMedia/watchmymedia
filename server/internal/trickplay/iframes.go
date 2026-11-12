package trickplay

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/ffexec"
	"watchmymedia/server/internal/playback"
)

// The I-frame track (PLAN-TRICKPLAY.md §7.3, T4): a version's tiles
// encoded once as a small all-intra H.264 MP4, one frame per tile, which
// VOD master playlists offer as an I-frame stream so that
// AVPlayerViewController's transport bar (the Apple TV app's) draws them.
// It is made from the sheets, without reading the video again, the first
// time a player asks for it, and kept beside them; the sweep and Delete
// thumbnails take it with the folder.

const (
	// IFramesName is a version's I-frame track in its folder.
	IFramesName = "iframes.mp4"
	// iframeTimeout bounds making one, which takes well under a second
	// for a film.
	iframeTimeout = 2 * time.Minute
	// iframeCacheSize is how many tracks' layouts are kept in memory.
	iframeCacheSize = 64
)

// iframeArgs is the ffmpeg command line that encodes a version's sheets
// (SheetName's names, from 000.jpg) into raw, one fragment per tile: untile
// cuts each sheet back into its tiles, trim drops the last sheet's black
// cells, and each tile is stamped at its time. H.264 Main at level 3.0
// (playback.IFrameCodecs), every frame a keyframe, at a quality close to
// the sheets'.
func (g *Generator) iframeArgs(dir string, t db.Trickplay, raw string) []string {
	return ffexec.FFmpegArgs("-threads", "2", "-framerate", "1", "-start_number", "0", "-i", filepath.Join(dir, "%03d.jpg"),
		"-vf", fmt.Sprintf("untile=%dx%d,trim=end_frame=%d,setpts=N*%s/TB,format=yuv420p", t.Columns, t.Rows, t.Tiles, ffexec.Seconds(t.IntervalMs)),
		"-frames:v", strconv.FormatInt(t.Tiles, 10), "-fps_mode", "passthrough", "-an",
		"-c:v", "libx264", "-preset", "veryfast", "-tune", "stillimage", "-crf", "26",
		"-profile:v", "main", "-level:v", "3.0", "-x264-params", "keyint=1:min-keyint=1:scenecut=0", "-bf", "0",
		"-video_track_timescale", "1000", "-movflags", "+frag_keyframe+empty_moov+default_base_moof+skip_trailer",
		"-f", "mp4", "-y", raw)
}

// IFrames returns a done version's I-frame track, making it from the
// sheets the first time. Tracks are made one at a time.
func (g *Generator) IFrames(ctx context.Context, t db.Trickplay) (*playback.IFrameTrack, error) {
	path := filepath.Join(FileDir(g.dir, t.MediaFileID, t.Version), IFramesName)
	g.iframeMu.Lock()
	defer g.iframeMu.Unlock()
	if tr, ok := g.iframeCache[path]; ok {
		if _, err := os.Stat(path); err == nil {
			return tr, nil
		}
		delete(g.iframeCache, path) // deleted since, with its folder
	}
	var tr playback.IFrameTrack
	b, err := os.ReadFile(path) //nolint:gosec // G304: the path is built from the file's ID and its recorded version
	if err == nil {
		tr, err = playback.ReadIFrames(b)
	}
	if err != nil {
		if tr, err = g.makeIFrames(ctx, path, t); err != nil {
			return nil, err
		}
	}
	tr.Path, tr.IntervalMs = path, t.IntervalMs
	if g.iframeCache == nil || len(g.iframeCache) >= iframeCacheSize {
		g.iframeCache = map[string]*playback.IFrameTrack{}
	}
	g.iframeCache[path] = &tr
	return &tr, nil
}

// makeIFrames encodes the track and puts it at path, written under another
// name and renamed when complete. A player that stops waiting does not stop
// it: the next one would only start again.
func (g *Generator) makeIFrames(ctx context.Context, path string, t db.Trickplay) (playback.IFrameTrack, error) {
	ctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), iframeTimeout)
	defer cancel()
	raw, part := path+".raw.part", path+".part"
	defer func() { _ = os.Remove(raw); _ = os.Remove(part) }()
	cmd := ffexec.Command(ctx, g.ffmpeg, g.iframeArgs(filepath.Dir(path), t, raw)...)
	if err := cmd.Run(); err != nil {
		if line := firstLine(cmd.Tail()); line != "" {
			return playback.IFrameTrack{}, fmt.Errorf("ffmpeg: %s", line)
		}
		return playback.IFrameTrack{}, fmt.Errorf("ffmpeg: %w", err)
	}
	b, err := os.ReadFile(raw) //nolint:gosec // G304: beside the version's sheets
	if err != nil {
		return playback.IFrameTrack{}, err
	}
	out, tr, err := playback.PackIFrames(b)
	if err != nil {
		return tr, err
	}
	if len(tr.Frames) != int(t.Tiles) {
		return tr, fmt.Errorf("made %d frames for %d tiles", len(tr.Frames), t.Tiles)
	}
	if err := os.WriteFile(part, out, 0o644); err != nil { //nolint:gosec // G306: thumbnails are not secret
		return tr, err
	}
	if err := os.Rename(part, path); err != nil {
		return tr, err
	}
	g.log.Info("seek thumbnails as an I-frame track", "file", t.MediaFileID, "frames", len(tr.Frames), "bytes", len(out))
	return tr, nil
}

// IFrameStream is how a VOD master playlist offers a done version's
// I-frame track for a file of durationMs: the track's own peak bit rate
// once it is made, else twice the sheets' average per tile, which the
// track's frames have so far stayed under.
func (g *Generator) IFrameStream(t db.Trickplay, durationMs int64) *playback.IFrameStream {
	s := &playback.IFrameStream{Codecs: playback.IFrameCodecs, Width: int(t.Width), Height: int(t.Height),
		Track: func(ctx context.Context) (*playback.IFrameTrack, error) { return g.IFrames(ctx, t) }}
	g.iframeMu.Lock()
	tr, ok := g.iframeCache[filepath.Join(FileDir(g.dir, t.MediaFileID, t.Version), IFramesName)]
	g.iframeMu.Unlock()
	if ok {
		s.Bandwidth, s.Codecs = tr.Bandwidth(durationMs), tr.Codecs
	} else if t.Tiles > 0 && t.IntervalMs > 0 {
		s.Bandwidth = max(2*t.Bytes/t.Tiles*8*1000/t.IntervalMs, 8_000)
	}
	return s
}
