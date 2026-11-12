package playback

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"time"

	"watchmymedia/server/internal/probe"
)

// Keyframes finds where a file's video keyframes are, which VOD playlists
// of copied video need: every segment must start on one, and the playlist
// lists every segment's length before any is made. Matroska files say
// where their keyframes are in the Cues index, which takes milliseconds to
// read; other files are scanned packet by packet with ffprobe, which reads
// the whole file, so that runs in the background and the play uses an
// event playlist meanwhile. Results are cached on disk per file version.
type Keyframes struct {
	probe *probe.Prober
	dir   string
	log   *slog.Logger

	mu       sync.Mutex
	mem      map[string][]int64
	scanning map[string]bool
	slots    chan struct{} // background scans at once

	// ctx is the background scans' parent, which Close ends.
	ctx    context.Context
	cancel context.CancelFunc
	scans  sync.WaitGroup
}

// ErrKeyframesPending means a background scan has started; ask again later.
var ErrKeyframesPending = errors.New("keyframe scan in progress")

// maxCueGap is the longest stretch without a listed keyframe that a Cues
// index may have before it is treated as too sparse for segments.
const maxCueGap = 20 * time.Second

func NewKeyframes(ffprobe, dir string, log *slog.Logger) *Keyframes {
	ctx, cancel := context.WithCancel(context.Background())
	return &Keyframes{probe: &probe.Prober{Path: ffprobe}, dir: dir, log: log, mem: map[string][]int64{}, scanning: map[string]bool{}, slots: make(chan struct{}, 1),
		ctx: ctx, cancel: cancel}
}

// Close stops the background scans and waits for them to end.
func (k *Keyframes) Close() {
	k.cancel()
	k.scans.Wait()
}

func keyframesKey(path string, size int64, mtime string) string {
	sum := sha256.Sum256([]byte(fmt.Sprintf("%s\x00%d\x00%s", path, size, mtime)))
	return hex.EncodeToString(sum[:12])
}

// Get returns the keyframe times of the file's first video stream in
// microseconds from the start of the file (as ffmpeg's -ss counts), or
// ErrKeyframesPending while a scan runs.
func (k *Keyframes) Get(ctx context.Context, path string, size int64, mtime string) ([]int64, error) {
	return k.get(ctx, path, size, mtime, nil)
}

// GetFrom is Get for a file whose start time (µs) is known from its
// stored probe, which saves running ffprobe for it.
func (k *Keyframes) GetFrom(ctx context.Context, path string, size int64, mtime string, startUs int64) ([]int64, error) {
	return k.get(ctx, path, size, mtime, &startUs)
}

func (k *Keyframes) get(ctx context.Context, path string, size int64, mtime string, startUs *int64) ([]int64, error) {
	key := keyframesKey(path, size, mtime)
	k.mu.Lock()
	if v, ok := k.mem[key]; ok {
		k.mu.Unlock()
		return v, nil
	}
	if k.scanning[key] {
		k.mu.Unlock()
		return nil, ErrKeyframesPending
	}
	k.mu.Unlock()
	if v, err := k.load(key); err == nil {
		k.remember(key, v)
		return v, nil
	}
	if strings.EqualFold(filepath.Ext(path), ".mkv") || strings.EqualFold(filepath.Ext(path), ".webm") {
		v, err := k.fromCues(ctx, path, startUs)
		if err == nil {
			k.remember(key, v)
			k.store(key, v)
			return v, nil
		}
		k.log.Info("no usable keyframe index; scanning the file", "path", path, "reason", err)
	}
	k.mu.Lock()
	if k.scanning[key] {
		k.mu.Unlock()
		return nil, ErrKeyframesPending
	}
	k.scanning[key] = true
	k.scans.Add(1)
	k.mu.Unlock()
	go k.scan(key, path)
	return nil, ErrKeyframesPending
}

func (k *Keyframes) remember(key string, v []int64) {
	k.mu.Lock()
	defer k.mu.Unlock()
	if len(k.mem) >= 64 {
		for old := range k.mem {
			delete(k.mem, old)
			break
		}
	}
	k.mem[key] = v
}

func (k *Keyframes) load(key string) ([]int64, error) {
	if k.dir == "" {
		return nil, os.ErrNotExist
	}
	b, err := os.ReadFile(filepath.Join(k.dir, key+".json"))
	if err != nil {
		return nil, err
	}
	var v []int64
	if err := json.Unmarshal(b, &v); err != nil || len(v) == 0 {
		return nil, errors.New("bad keyframe cache")
	}
	return v, nil
}

func (k *Keyframes) store(key string, v []int64) {
	if k.dir == "" {
		return
	}
	if err := os.MkdirAll(k.dir, 0o755); err != nil {
		return
	}
	b, _ := json.Marshal(v)
	// A temporary file of its own, so two scans of one file (two servers on
	// one data folder, or a scan and a Cues read) cannot write into one.
	tmp, err := os.CreateTemp(k.dir, key+".*.tmp")
	if err != nil {
		return
	}
	_, werr := tmp.Write(b)
	if cerr := tmp.Close(); werr == nil && cerr == nil && os.Rename(tmp.Name(), filepath.Join(k.dir, key+".json")) == nil {
		return
	}
	_ = os.Remove(tmp.Name())
}

func (k *Keyframes) scan(key, path string) {
	defer k.scans.Done()
	defer func() {
		k.mu.Lock()
		delete(k.scanning, key)
		k.mu.Unlock()
	}()
	select {
	case k.slots <- struct{}{}:
	case <-k.ctx.Done():
		return
	}
	defer func() { <-k.slots }()
	ctx, cancel := context.WithTimeout(k.ctx, 30*time.Minute)
	defer cancel()
	started := time.Now()
	v, err := k.fromPackets(ctx, path)
	if err != nil {
		if k.ctx.Err() == nil {
			k.log.Warn("keyframe scan failed", "path", path, "err", err)
		}
		return
	}
	k.remember(key, v)
	k.store(key, v)
	k.log.Info("keyframe scan finished", "path", path, "keyframes", len(v), "took", time.Since(started).Round(time.Millisecond))
}

// fromCues reads the Matroska Cues index, in file time: from startUs, or
// the start time ffprobe reads when that is nil.
func (k *Keyframes) fromCues(ctx context.Context, path string, startUs *int64) ([]int64, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer func() { _ = f.Close() }()
	cues, err := matroskaKeyframes(f)
	if err != nil {
		return nil, err
	}
	var start int64
	if startUs != nil {
		start = *startUs
	} else if start, err = k.probe.StartTime(ctx, path); err != nil {
		return nil, err
	}
	out := make([]int64, 0, len(cues))
	for _, ns := range cues {
		out = append(out, ns/1000-start)
	}
	return checkKeyframes(out, maxCueGap)
}

// checkKeyframes sorts and de-duplicates keyframe times and refuses lists
// with long gaps, which would make segments too long to seek in.
func checkKeyframes(v []int64, maxGap time.Duration) ([]int64, error) {
	slices.Sort(v)
	out := v[:0]
	for i, x := range v {
		if i == 0 || x != out[len(out)-1] {
			out = append(out, x)
		}
	}
	if len(out) < 2 {
		return nil, errors.New("fewer than two keyframes listed")
	}
	for i := 1; i < len(out); i++ {
		if time.Duration(out[i]-out[i-1])*time.Microsecond > maxGap {
			return nil, fmt.Errorf("no keyframe listed between %.1fs and %.1fs", float64(out[i-1])/1e6, float64(out[i])/1e6)
		}
	}
	return out, nil
}

// fromPackets reads every video packet's time and keyframe flag.
func (k *Keyframes) fromPackets(ctx context.Context, path string) ([]int64, error) {
	keys, err := k.probe.Keyframes(ctx, path)
	if err != nil {
		return nil, err
	}
	// A packet scan sees every keyframe, so long gaps are real; allow them.
	return checkKeyframes(keys, 24*time.Hour)
}

// --- Matroska ---

const (
	mkvEBML           = 0x1A45DFA3
	mkvSegment        = 0x18538067
	mkvSeekHead       = 0x114D9B74
	mkvSeek           = 0x4DBB
	mkvSeekID         = 0x53AB
	mkvSeekPosition   = 0x53AC
	mkvInfo           = 0x1549A966
	mkvTimestampScale = 0x2AD7B1
	mkvTracks         = 0x1654AE6B
	mkvTrackEntry     = 0xAE
	mkvTrackNumber    = 0xD7
	mkvTrackType      = 0x83
	mkvCues           = 0x1C53BB6B
	mkvCuePoint       = 0xBB
	mkvCueTime        = 0xB3
	mkvCueTrackPos    = 0xB7
	mkvCueTrack       = 0xF7
	mkvCluster        = 0x1F43B675
	mkvUnknownSize    = -1
)

// readVint reads an EBML variable-length integer. id keeps the length
// marker bits (as element IDs are written); sizes clear them. An all-ones
// size means unknown.
func readVint(r io.Reader, id bool) (int64, int, error) {
	var first [1]byte
	if _, err := io.ReadFull(r, first[:]); err != nil {
		return 0, 0, err
	}
	n := 1
	for mask := byte(0x80); n <= 8 && first[0]&mask == 0; mask >>= 1 {
		n++
	}
	if n > 8 || (id && n > 4) {
		return 0, 0, errors.New("mkv: bad variable-length integer")
	}
	buf := make([]byte, n)
	buf[0] = first[0]
	if _, err := io.ReadFull(r, buf[1:]); err != nil {
		return 0, 0, err
	}
	if !id {
		buf[0] &^= 0x80 >> (n - 1)
	}
	var v int64
	allOnes := true
	for i, b := range buf {
		v = v<<8 | int64(b)
		want := byte(0xFF)
		if i == 0 {
			want = 0xFF >> n
		}
		if b != want {
			allOnes = false
		}
	}
	if !id && allOnes {
		return mkvUnknownSize, n, nil
	}
	return v, n, nil
}

type mkvElem struct {
	id        int64
	size      int64 // mkvUnknownSize when unknown
	dataStart int64
}

func readElem(r io.ReadSeeker) (mkvElem, error) {
	pos, err := r.Seek(0, io.SeekCurrent)
	if err != nil {
		return mkvElem{}, err
	}
	id, n1, err := readVint(r, true)
	if err != nil {
		return mkvElem{}, err
	}
	size, n2, err := readVint(r, false)
	if err != nil {
		return mkvElem{}, err
	}
	return mkvElem{id: id, size: size, dataStart: pos + int64(n1+n2)}, nil
}

// children reads the elements in a master element's payload.
func children(data []byte) ([]mkvElem, [][]byte, error) {
	var elems []mkvElem
	var bodies [][]byte
	r := bytes.NewReader(data)
	for r.Len() > 0 {
		e, err := readElem(r)
		if err != nil {
			return nil, nil, err
		}
		if e.size < 0 || e.dataStart+e.size > int64(len(data)) {
			return nil, nil, errors.New("mkv: element runs past its parent")
		}
		elems = append(elems, e)
		bodies = append(bodies, data[e.dataStart:e.dataStart+e.size])
		if _, err := r.Seek(e.dataStart+e.size, io.SeekStart); err != nil {
			return nil, nil, err
		}
	}
	return elems, bodies, nil
}

func mkvUint(b []byte) int64 {
	var v int64
	for _, x := range b {
		v = v<<8 | int64(x)
	}
	return v
}

// maxMkvElement caps how much of a header element is read into memory.
const maxMkvElement = 64 << 20

// matroskaKeyframes returns the Cues index's times for the first video
// track, in nanoseconds of segment time.
func matroskaKeyframes(r io.ReadSeeker) ([]int64, error) {
	head, err := readElem(r)
	if err != nil || head.id != mkvEBML || head.size < 0 {
		return nil, errors.New("mkv: not an EBML file")
	}
	if _, err := r.Seek(head.dataStart+head.size, io.SeekStart); err != nil {
		return nil, err
	}
	seg, err := readElem(r)
	if err != nil || seg.id != mkvSegment {
		return nil, errors.New("mkv: no segment")
	}
	read := func(e mkvElem) ([]byte, error) {
		if e.size < 0 || e.size > maxMkvElement {
			return nil, errors.New("mkv: header element too large or of unknown size")
		}
		b := make([]byte, e.size)
		if _, err := r.Seek(e.dataStart, io.SeekStart); err != nil {
			return nil, err
		}
		_, err := io.ReadFull(r, b)
		return b, err
	}
	found := map[int64][]byte{}
	seekTo := map[int64]int64{}
	parseSeekHead := func(b []byte) {
		elems, bodies, err := children(b)
		if err != nil {
			return
		}
		for i, e := range elems {
			if e.id != mkvSeek {
				continue
			}
			ks, kb, err := children(bodies[i])
			if err != nil {
				continue
			}
			var id, pos int64 = 0, -1
			for j, k := range ks {
				switch k.id {
				case mkvSeekID:
					id = mkvUint(kb[j])
				case mkvSeekPosition:
					pos = mkvUint(kb[j])
				}
			}
			if id != 0 && pos >= 0 {
				if _, ok := seekTo[id]; !ok {
					seekTo[id] = pos
				}
			}
		}
	}
	// Walk the top level up to the first cluster.
	pos := seg.dataStart
	for {
		if _, err := r.Seek(pos, io.SeekStart); err != nil {
			return nil, err
		}
		e, err := readElem(r)
		if err != nil || e.id == mkvCluster || e.size < 0 {
			break
		}
		switch e.id {
		case mkvSeekHead, mkvInfo, mkvTracks, mkvCues:
			b, err := read(e)
			if err != nil {
				return nil, err
			}
			if e.id == mkvSeekHead {
				parseSeekHead(b)
			} else {
				found[e.id] = b
			}
		}
		pos = e.dataStart + e.size
	}
	// The rest, usually Cues at the end, through the SeekHead.
	for _, want := range []int64{mkvInfo, mkvTracks, mkvCues} {
		if _, ok := found[want]; ok {
			continue
		}
		at, ok := seekTo[want]
		if !ok {
			continue
		}
		if _, err := r.Seek(seg.dataStart+at, io.SeekStart); err != nil {
			return nil, err
		}
		e, err := readElem(r)
		if err != nil || e.id != want {
			continue
		}
		b, err := read(e)
		if err != nil {
			return nil, err
		}
		found[want] = b
	}
	cues, ok := found[mkvCues]
	if !ok {
		return nil, errors.New("mkv: no Cues index")
	}
	scale := int64(1_000_000)
	if b, ok := found[mkvInfo]; ok {
		elems, bodies, _ := children(b)
		for i, e := range elems {
			if e.id == mkvTimestampScale {
				if v := mkvUint(bodies[i]); v > 0 {
					scale = v
				}
			}
		}
	}
	video := int64(-1)
	if b, ok := found[mkvTracks]; ok {
		elems, bodies, _ := children(b)
		for i, e := range elems {
			if e.id != mkvTrackEntry || video >= 0 {
				continue
			}
			ks, kb, err := children(bodies[i])
			if err != nil {
				continue
			}
			var num, typ int64 = -1, 0
			for j, k := range ks {
				switch k.id {
				case mkvTrackNumber:
					num = mkvUint(kb[j])
				case mkvTrackType:
					typ = mkvUint(kb[j])
				}
			}
			if typ == 1 {
				video = num
			}
		}
	}
	if video < 0 {
		return nil, errors.New("mkv: no video track")
	}
	elems, bodies, err := children(cues)
	if err != nil {
		return nil, err
	}
	var out []int64
	for i, e := range elems {
		if e.id != mkvCuePoint {
			continue
		}
		ks, kb, err := children(bodies[i])
		if err != nil {
			continue
		}
		t := int64(-1)
		forVideo := false
		for j, k := range ks {
			switch k.id {
			case mkvCueTime:
				t = mkvUint(kb[j])
			case mkvCueTrackPos:
				ps, pb, err := children(kb[j])
				if err != nil {
					continue
				}
				for n, p := range ps {
					if p.id == mkvCueTrack && mkvUint(pb[n]) == video {
						forVideo = true
					}
				}
			}
		}
		if t >= 0 && forVideo {
			out = append(out, t*scale)
		}
	}
	if len(out) == 0 {
		return nil, errors.New("mkv: Cues list no video keyframes")
	}
	return out, nil
}
