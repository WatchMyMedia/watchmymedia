package playback

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"watchmymedia/server/internal/ffexec"
)

// Subtitles converts subtitle tracks for clients and caches the results.
//
// Pulling one track out of a video reads the whole file (its packets are
// spread through it), which takes minutes for a large file on a slow disk.
// So conversions run in the background, one ffmpeg per file for all the
// tracks wanted, and requests wait for them without owning them: a client
// that gives up does not stop the work, and the next request finds it done.
// Those whole-file reads run at low priority, as trickplay's do, so they wait
// for playback's reads of the same disk rather than starving them.
type Subtitles struct {
	ffmpeg string
	dir    string
	// PrepareDelay holds back Prepare's extraction so it does not compete
	// with the start of playback for the disk.
	PrepareDelay time.Duration

	mu     sync.Mutex
	jobs   map[string]*extraction // by cache key, while running or waiting to run
	active int                    // includes delayed jobs: neither workers nor their queue can grow without bound

	// ctx is the extractions' parent, which Close ends.
	ctx    context.Context
	cancel context.CancelFunc
	runs   sync.WaitGroup
}

const maxSubtitleJobs = 2
const maxSubtitleBytes = 64 << 20

var ErrSubtitleBusy = errors.New("too many subtitle conversions; try again shortly")

func NewSubtitles(ffmpeg, dir string) *Subtitles {
	ctx, cancel := context.WithCancel(context.Background())
	return &Subtitles{ffmpeg: ffmpeg, dir: dir, PrepareDelay: 10 * time.Second, jobs: map[string]*extraction{}, ctx: ctx, cancel: cancel}
}

// Close stops the extractions and waits for them to end.
func (s *Subtitles) Close() {
	s.cancel()
	s.runs.Wait()
}

// extraction is one ffmpeg run writing one or more cached tracks.
type extraction struct {
	done  chan struct{}
	start chan struct{} // closed to run at once rather than after the delay
	once  sync.Once
	err   error
}

func (e *extraction) kick() { e.once.Do(func() { close(e.start) }) }

type output struct {
	key, mapping, format string
}

// Track is an embedded text track and the format a client will ask for.
type Track struct {
	Index  int
	Format string // webvtt or ass
}

func embeddedKey(fileID int64, index int, format string) string {
	return fmt.Sprintf("%d-s%d.%s", fileID, index, ext(format))
}

// Embedded extracts stream index from the video as WebVTT or ASS.
func (s *Subtitles) Embedded(ctx context.Context, fileID int64, videoPath string, streamIndex int, format string) ([]byte, error) {
	key := embeddedKey(fileID, streamIndex, format)
	return s.get(ctx, key, videoPath, "", []output{{key, fmt.Sprintf("0:%d", streamIndex), format}}, true)
}

// Prepare starts extracting a video's text tracks in the background, in one
// read of the file and after PrepareDelay, so a track is usually ready by
// the time someone picks it. Tracks already cached or on their way are
// skipped.
func (s *Subtitles) Prepare(fileID int64, videoPath string, tracks []Track) {
	var outs []output
	seen := map[string]bool{}
	for _, t := range tracks {
		if len(outs) >= 64 {
			break
		}
		key := embeddedKey(fileID, t.Index, t.Format)
		if seen[key] {
			continue
		}
		seen[key] = true
		if _, ok := s.fresh(key, videoPath); !ok {
			outs = append(outs, output{key, fmt.Sprintf("0:%d", t.Index), t.Format})
		}
	}
	if len(outs) > 0 {
		s.start(videoPath, "", outs, s.PrepareDelay, true)
	}
}

// Sidecar converts a subtitle file next to the video.
func (s *Subtitles) Sidecar(ctx context.Context, fileID int64, n int, sidecar Sidecar, format string) ([]byte, error) {
	return s.file(ctx, fmt.Sprintf("%d-x%d.%s", fileID, n, ext(format)), sidecar.Path, sidecar.Codec, format)
}

// Downloaded converts a subtitle downloaded from a provider (SRT).
func (s *Subtitles) Downloaded(ctx context.Context, id int64, path, format string) ([]byte, error) {
	return s.file(ctx, fmt.Sprintf("d%d.%s", id, ext(format)), path, "subrip", format)
}

// file converts a subtitle file. One already in the wanted format and in
// UTF-8 is served as it is; others that are not UTF-8 are read as
// Windows-1252, the usual code page of older subtitle files, since ffmpeg
// otherwise refuses them.
func (s *Subtitles) file(ctx context.Context, key, path, codec, format string) ([]byte, error) {
	data, err := readSubtitle(path)
	if err != nil {
		return nil, err
	}
	charset := ""
	if !utf8.Valid(data) && !bytes.HasPrefix(data, []byte{0xff, 0xfe}) && !bytes.HasPrefix(data, []byte{0xfe, 0xff}) {
		charset = "CP1252"
	}
	if charset == "" && ((format == "webvtt" && codec == "webvtt") || (format == "ass" && codec == "ass")) {
		return data, nil
	}
	return s.get(ctx, key, path, charset, []output{{key, "0:s:0", format}}, false)
}

func ext(format string) string {
	if format == "ass" {
		return "ass"
	}
	return "vtt"
}

// fresh returns the cached track when it is newer than its source.
func (s *Subtitles) fresh(key, source string) ([]byte, bool) {
	if !s.cached(key, source) {
		return nil, false
	}
	b, err := readSubtitle(filepath.Join(s.dir, key))
	return b, err == nil
}

// cached reports whether the cached track is there and newer than its source.
func (s *Subtitles) cached(key, source string) bool {
	st, err := os.Stat(filepath.Join(s.dir, key))
	if err != nil {
		return false
	}
	src, err := os.Stat(source)
	return err == nil && !src.ModTime().After(st.ModTime())
}

// get returns the cached track, else waits (as long as ctx allows) for the
// background run that makes it, starting one if none is on its way. low
// runs ffmpeg at low priority: for a source that is the video itself.
func (s *Subtitles) get(ctx context.Context, key, source, charset string, outs []output, low bool) ([]byte, error) {
	if b, ok := s.fresh(key, source); ok {
		return b, nil
	}
	e := s.start(source, charset, outs, 0, low)
	e.kick()
	select {
	case <-e.done:
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	if e.err != nil {
		return nil, e.err
	}
	return readSubtitle(filepath.Join(s.dir, key))
}

func readSubtitle(path string) ([]byte, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer func() { _ = f.Close() }()
	b, err := io.ReadAll(io.LimitReader(f, maxSubtitleBytes+1))
	if len(b) > maxSubtitleBytes {
		return nil, errors.New("subtitle track too large")
	}
	return b, err
}

// start returns the run that makes outs[0], starting one for every output
// neither cached nor already on its way. delay holds a new run back unless
// someone kicks it. The cache is looked at again here, under the lock: a
// run renames its files into the cache before it leaves s.jobs, so a run
// that finished since the caller looked is not started again. low runs
// ffmpeg at low priority.
func (s *Subtitles) start(input, charset string, outs []output, delay time.Duration, low bool) *extraction {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.jobs == nil {
		s.jobs = map[string]*extraction{}
	}
	var todo []output
	for _, o := range outs {
		if _, ok := s.jobs[o.key]; !ok && !s.cached(o.key, input) {
			todo = append(todo, o)
		}
	}
	if len(todo) > 0 {
		if s.active >= maxSubtitleJobs {
			if existing := s.jobs[outs[0].key]; existing != nil {
				return existing
			}
			e := &extraction{done: make(chan struct{}), start: make(chan struct{}), err: ErrSubtitleBusy}
			close(e.done)
			return e
		}
		e := &extraction{done: make(chan struct{}), start: make(chan struct{})}
		s.active++
		for _, o := range todo {
			s.jobs[o.key] = e
		}
		s.runs.Add(1)
		go s.run(e, input, charset, todo, delay, low)
	}
	if e := s.jobs[outs[0].key]; e != nil {
		return e
	}
	// Cached meanwhile: nothing to wait for.
	e := &extraction{done: make(chan struct{}), start: make(chan struct{})}
	close(e.done)
	return e
}

func (s *Subtitles) run(e *extraction, input, charset string, outs []output, delay time.Duration, low bool) {
	defer s.runs.Done()
	if delay > 0 {
		t := time.NewTimer(delay)
		select {
		case <-t.C:
		case <-e.start:
		case <-s.ctx.Done():
		}
		t.Stop()
	}
	e.err = s.convert(input, charset, outs, low)
	s.mu.Lock()
	s.active--
	for _, o := range outs {
		if s.jobs[o.key] == e {
			delete(s.jobs, o.key)
		}
	}
	s.mu.Unlock()
	close(e.done)
}

// convert runs ffmpeg once for every output, writing each to the cache.
// It has a minute and a half plus a minute for every 2 GB of input: a
// whole-file read at about 35 MB/s, slower than any disk worth streaming from.
// low runs it at low CPU and disk priority (ffexec.Cmd.LowPriority): reading a
// large video end to end at full speed on a spinning disk starved the stream
// of the same file, so playback buffered until it gave up.
func (s *Subtitles) convert(input, charset string, outs []output, low bool) error {
	timeout := 90 * time.Second
	if st, err := os.Stat(input); err == nil {
		timeout += time.Duration(st.Size()/(2<<30)) * time.Minute
	}
	ctx, cancel := context.WithTimeout(s.ctx, timeout)
	defer cancel()
	if err := os.MkdirAll(s.dir, 0o755); err != nil {
		return err
	}
	args := ffexec.FFmpegArgs("-y")
	if charset != "" {
		args = append(args, "-sub_charenc", charset)
	}
	args = append(args, "-i", input)
	tmps := make([]string, len(outs))
	for i, o := range outs {
		tmps[i] = filepath.Join(s.dir, "."+o.key+"."+strconv.FormatInt(time.Now().UnixNano(), 36)+".tmp")
		args = append(args, "-map", o.mapping, "-f", o.format, tmps[i])
	}
	defer func() {
		for _, t := range tmps {
			_ = os.Remove(t)
		}
	}()
	cmd := ffexec.Command(ctx, s.ffmpeg, args...)
	cmd.LowPriority = low
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("convert subtitles: %w", err)
	}
	for i, o := range outs {
		if st, err := os.Stat(tmps[i]); err != nil {
			return err
		} else if st.Size() > 64<<20 {
			return fmt.Errorf("subtitle track too large")
		}
		if err := os.Rename(tmps[i], filepath.Join(s.dir, o.key)); err != nil {
			return err
		}
	}
	return nil
}

// ShiftSubtitle moves every cue of a WebVTT or ASS track ms earlier,
// dropping cues that end before zero: an event playlist's time starts at
// the session's start, while tracks are in file time.
func ShiftSubtitle(data []byte, format string, ms int64) []byte {
	if ms <= 0 {
		return data
	}
	if format == "ass" {
		return shiftASS(data, ms)
	}
	var b strings.Builder
	b.WriteString("WEBVTT\n\n")
	us := ms * 1000
	for _, c := range parseVTT(data) {
		if c.end-us <= 0 {
			continue
		}
		b.WriteString(formatVTTTime(max(c.start-us, 0)))
		b.WriteString(" --> ")
		b.WriteString(formatVTTTime(c.end - us))
		if c.settings != "" {
			b.WriteString(" " + c.settings)
		}
		b.WriteString("\n" + c.text + "\n\n")
	}
	return []byte(b.String())
}

// shiftASS moves the Start and End of each Dialogue line (H:MM:SS.cc).
func shiftASS(data []byte, ms int64) []byte {
	lines := strings.SplitAfter(string(data), "\n")
	var b strings.Builder
	cs := ms / 10
	for _, line := range lines {
		rest, ok := strings.CutPrefix(line, "Dialogue:")
		if !ok {
			b.WriteString(line)
			continue
		}
		f := strings.SplitN(rest, ",", 4)
		if len(f) < 4 {
			b.WriteString(line)
			continue
		}
		start, ok1 := parseASSTime(f[1])
		end, ok2 := parseASSTime(f[2])
		if !ok1 || !ok2 {
			b.WriteString(line)
			continue
		}
		if end-cs <= 0 {
			continue
		}
		b.WriteString("Dialogue:" + f[0] + "," + formatASSTime(max(start-cs, 0)) + "," + formatASSTime(end-cs) + "," + f[3])
	}
	return []byte(b.String())
}

// parseASSTime reads H:MM:SS.cc as centiseconds.
func parseASSTime(s string) (int64, bool) {
	parts := strings.Split(strings.TrimSpace(s), ":")
	if len(parts) != 3 {
		return 0, false
	}
	h, err1 := strconv.ParseInt(parts[0], 10, 64)
	m, err2 := strconv.ParseInt(parts[1], 10, 64)
	sec, err3 := strconv.ParseFloat(parts[2], 64)
	if err1 != nil || err2 != nil || err3 != nil {
		return 0, false
	}
	return (h*3600+m*60)*100 + int64(sec*100+0.5), true
}

func formatASSTime(cs int64) string {
	return fmt.Sprintf("%d:%02d:%02d.%02d", cs/360000, cs/6000%60, cs/100%60, cs%100)
}
