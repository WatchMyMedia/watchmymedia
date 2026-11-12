package playback

import (
	"encoding/binary"
	"errors"
	"fmt"
	"slices"
)

// Fragmented MP4 helpers for VOD sessions. ffmpeg starts every run's
// timestamps near zero and records where the run really starts in its init
// segment's edit lists. Pieces from different runs only fit one playlist if
// their times are absolute, so the server strips the edit lists from one
// init segment and moves each fragment's decode time (tfdt) by its run's
// offset instead. vodTimeOffset is added to every track so that audio
// priming, which starts a few milliseconds before zero, stays positive;
// subtitle playlists say the same with X-TIMESTAMP-MAP.

// vodTimeOffset is where file time zero sits on the media timeline, in seconds.
const vodTimeOffset = 10

type mp4Box struct {
	typ        string
	start, end int // the whole box, header included
	body       int // start of the payload
}

// mp4Boxes lists the boxes in b[from:to].
func mp4Boxes(b []byte, from, to int) ([]mp4Box, error) {
	var out []mp4Box
	for off := from; off < to; {
		if to-off < 8 {
			return nil, errors.New("mp4: truncated box header")
		}
		size := int64(binary.BigEndian.Uint32(b[off:]))
		typ := string(b[off+4 : off+8])
		hdr := 8
		switch size {
		case 1:
			if to-off < 16 {
				return nil, errors.New("mp4: truncated large box header")
			}
			size = int64(binary.BigEndian.Uint64(b[off+8:]))
			hdr = 16
		case 0:
			size = int64(to - off)
		}
		if size < int64(hdr) || int64(off)+size > int64(to) {
			return nil, fmt.Errorf("mp4: box %q runs past its parent", typ)
		}
		out = append(out, mp4Box{typ: typ, start: off, end: off + int(size), body: off + hdr})
		off += int(size)
	}
	return out, nil
}

func childBox(b []byte, parent mp4Box, typ string) (mp4Box, bool) {
	kids, err := mp4Boxes(b, parent.body, parent.end)
	if err != nil {
		return mp4Box{}, false
	}
	for _, k := range kids {
		if k.typ == typ {
			return k, true
		}
	}
	return mp4Box{}, false
}

// fmp4Init is what a run's init segment says about its tracks.
type fmp4Init struct {
	// Stripped is the init segment without edit lists.
	Stripped []byte
	// Video is the video track's ID (0 when there is none).
	Video uint32
	// Audio is the first audio track's ID (0 when there is none).
	Audio  uint32
	Tracks map[uint32]fmp4Track
}

type fmp4Track struct {
	Timescale uint32
	// Offset is added to every tfdt of the track, in its timescale: where
	// the run's edit list places it, plus vodTimeOffset.
	Offset int64
	// StartUs is where the run's edit list starts the track, in file time
	// (microseconds, from a value stored in milliseconds).
	StartUs int64
}

// parseInit reads an init segment's tracks and edit lists.
func parseInit(b []byte) (fmp4Init, error) {
	top, err := mp4Boxes(b, 0, len(b))
	if err != nil {
		return fmp4Init{}, err
	}
	out := fmp4Init{Tracks: map[uint32]fmp4Track{}}
	var moov mp4Box
	found := false
	for _, x := range top {
		if x.typ == "moov" {
			moov, found = x, true
		}
	}
	if !found {
		return out, errors.New("mp4: init segment has no moov")
	}
	movieScale := uint32(1000)
	if mvhd, ok := childBox(b, moov, "mvhd"); ok && mvhd.end-mvhd.body >= 24 {
		if b[mvhd.body] == 1 {
			movieScale = binary.BigEndian.Uint32(b[mvhd.body+20:])
		} else {
			movieScale = binary.BigEndian.Uint32(b[mvhd.body+12:])
		}
	}
	kids, err := mp4Boxes(b, moov.body, moov.end)
	if err != nil {
		return out, err
	}
	for _, trak := range kids {
		if trak.typ != "trak" {
			continue
		}
		id, t, handler, err := parseTrak(b, trak, movieScale)
		if err != nil {
			return out, err
		}
		out.Tracks[id] = t
		if handler == "vide" && out.Video == 0 {
			out.Video = id
		}
		if handler == "soun" && out.Audio == 0 {
			out.Audio = id
		}
	}
	out.Stripped = stripEdits(b, top)
	return out, nil
}

// trakID reads a trak's track ID from its tkhd.
func trakID(b []byte, trak mp4Box) (uint32, bool) {
	tkhd, ok := childBox(b, trak, "tkhd")
	if !ok || tkhd.end-tkhd.body < 24 {
		return 0, false
	}
	idAt := tkhd.body + 12
	if b[tkhd.body] == 1 {
		idAt = tkhd.body + 20
	}
	if idAt+4 > tkhd.end {
		return 0, false
	}
	return binary.BigEndian.Uint32(b[idAt:]), true
}

// parseTrak returns the track's ID, timing and handler type ("vide", "soun"…).
func parseTrak(b []byte, trak mp4Box, movieScale uint32) (uint32, fmp4Track, string, error) {
	var t fmp4Track
	id, ok := trakID(b, trak)
	if !ok {
		return 0, t, "", errors.New("mp4: track without tkhd")
	}
	mdia, ok := childBox(b, trak, "mdia")
	if !ok {
		return 0, t, "", errors.New("mp4: track without mdia")
	}
	mdhd, ok := childBox(b, mdia, "mdhd")
	if !ok || mdhd.end-mdhd.body < 24 {
		return 0, t, "", errors.New("mp4: track without mdhd")
	}
	if b[mdhd.body] == 1 {
		t.Timescale = binary.BigEndian.Uint32(b[mdhd.body+20:])
	} else {
		t.Timescale = binary.BigEndian.Uint32(b[mdhd.body+12:])
	}
	if t.Timescale == 0 || movieScale == 0 {
		return 0, t, "", errors.New("mp4: zero timescale")
	}
	handler := ""
	if hdlr, ok := childBox(b, mdia, "hdlr"); ok && hdlr.end-hdlr.body >= 12 {
		handler = string(b[hdlr.body+8 : hdlr.body+12])
	}
	// An edit list is empty edits (media time -1: nothing shown for that
	// long) and then the edit that plays the media from a media time.
	var emptyMovie int64
	var mediaTime int64
	if edts, ok := childBox(b, trak, "edts"); ok {
		if elst, ok := childBox(b, edts, "elst"); ok && elst.end-elst.body >= 8 {
			ver := b[elst.body]
			n := int(binary.BigEndian.Uint32(b[elst.body+4:]))
			off := elst.body + 8
			for range n {
				var dur, mt int64
				if ver == 1 {
					if off+20 > elst.end {
						break
					}
					dur, mt = int64(binary.BigEndian.Uint64(b[off:])), int64(binary.BigEndian.Uint64(b[off+8:]))
					off += 20
				} else {
					if off+12 > elst.end {
						break
					}
					dur, mt = int64(binary.BigEndian.Uint32(b[off:])), int64(int32(binary.BigEndian.Uint32(b[off+4:])))
					off += 12
				}
				if mt == -1 {
					emptyMovie += dur
					continue
				}
				mediaTime = mt
				break
			}
		}
	}
	ts := int64(t.Timescale)
	t.Offset = emptyMovie*ts/int64(movieScale) - mediaTime + vodTimeOffset*ts
	t.StartUs = emptyMovie * 1_000_000 / int64(movieScale)
	return id, t, handler, nil
}

// stripEdits returns b without the edts boxes inside moov/trak.
func stripEdits(b []byte, top []mp4Box) []byte {
	out := make([]byte, 0, len(b))
	for _, x := range top {
		if x.typ != "moov" {
			out = append(out, b[x.start:x.end]...)
			continue
		}
		out = append(out, rebuild(b, x, func(child mp4Box) []byte {
			if child.typ != "trak" {
				return b[child.start:child.end]
			}
			return rebuild(b, child, func(g mp4Box) []byte {
				if g.typ == "edts" {
					return nil
				}
				return b[g.start:g.end]
			})
		})...)
	}
	return out
}

// rebuild writes a container box with its children mapped by fn.
func rebuild(b []byte, box mp4Box, fn func(mp4Box) []byte) []byte {
	kids, err := mp4Boxes(b, box.body, box.end)
	if err != nil {
		return b[box.start:box.end]
	}
	var body []byte
	for _, k := range kids {
		body = append(body, fn(k)...)
	}
	out := make([]byte, 8, 8+len(body))
	binary.BigEndian.PutUint32(out, uint32(8+len(body)))
	copy(out[4:], box.typ)
	return append(out, body...)
}

// patchFragment returns a copy of a media segment with absolute decode
// times (see patchFragmentInPlace).
func patchFragment(b []byte, init fmp4Init) ([]byte, error) {
	out := slices.Clone(b)
	n, err := patchFragmentInPlace(out, init)
	if err != nil {
		return nil, err
	}
	return out[:n], nil
}

// patchFragmentInPlace gives a media segment absolute decode times: styp
// and sidx boxes are dropped (their times are the run's) and every tfdt
// moves by its track's offset. Fragments use default-base-is-moof, so
// nothing before the moof is referenced by offset. The segment is rewritten
// in b, which then holds it in its first n bytes.
func patchFragmentInPlace(b []byte, init fmp4Init) (n int, err error) {
	top, err := mp4Boxes(b, 0, len(b))
	if err != nil {
		return 0, err
	}
	for _, x := range top {
		switch x.typ {
		case "styp", "sidx":
			continue
		case "moof":
			if err := patchMoof(b[x.start:x.end], init); err != nil {
				return 0, err
			}
		}
		n += copy(b[n:], b[x.start:x.end])
	}
	return n, nil
}

func patchMoof(m []byte, init fmp4Init) error {
	top, err := mp4Boxes(m, 0, len(m))
	if err != nil || len(top) != 1 {
		return errors.New("mp4: bad moof")
	}
	kids, err := mp4Boxes(m, top[0].body, top[0].end)
	if err != nil {
		return err
	}
	for _, traf := range kids {
		if traf.typ != "traf" {
			continue
		}
		tfhd, ok := childBox(m, traf, "tfhd")
		if !ok || tfhd.end-tfhd.body < 8 {
			return errors.New("mp4: traf without tfhd")
		}
		track := binary.BigEndian.Uint32(m[tfhd.body+4:])
		t, ok := init.Tracks[track]
		if !ok {
			return fmt.Errorf("mp4: fragment for unknown track %d", track)
		}
		tfdt, ok := childBox(m, traf, "tfdt")
		if !ok {
			return errors.New("mp4: traf without tfdt")
		}
		// Version and flags, then the decode time: 8 bytes in version 1,
		// 4 in version 0.
		body := m[tfdt.body:tfdt.end]
		switch {
		case len(body) >= 12 && body[0] == 1:
			v := int64(binary.BigEndian.Uint64(body[4:])) + t.Offset
			if v < 0 {
				return errors.New("mp4: decode time before zero")
			}
			binary.BigEndian.PutUint64(body[4:], uint64(v))
		case len(body) >= 8 && body[0] == 0:
			v := int64(binary.BigEndian.Uint32(body[4:])) + t.Offset
			if v < 0 || v > 0xFFFFFFFF {
				return errors.New("mp4: decode time out of range for a version 0 tfdt")
			}
			binary.BigEndian.PutUint32(body[4:], uint32(v))
		default:
			return errors.New("mp4: short or unknown tfdt")
		}
	}
	return nil
}

// A demuxed rendition is cut from the muxed output: its init segment keeps
// one track, and each fragment that track's traf and the sample bytes its
// runs point at. Cast receivers need this for AC-3 and E-AC-3, which their
// Media Source Extensions take only in an audio/mp4 SourceBuffer of their own.

// splitInit returns the init segment with only track id: its trak, and its
// trex in mvex.
func splitInit(b []byte, id uint32) ([]byte, error) {
	top, err := mp4Boxes(b, 0, len(b))
	if err != nil {
		return nil, err
	}
	found := false
	out := make([]byte, 0, len(b))
	for _, x := range top {
		if x.typ != "moov" {
			out = append(out, b[x.start:x.end]...)
			continue
		}
		out = append(out, rebuild(b, x, func(c mp4Box) []byte {
			switch c.typ {
			case "trak":
				if tid, ok := trakID(b, c); !ok || tid != id {
					return nil
				}
				found = true
			case "mvex":
				return rebuild(b, c, func(g mp4Box) []byte {
					if g.typ == "trex" && (g.end-g.body < 8 || binary.BigEndian.Uint32(b[g.body+4:]) != id) {
						return nil
					}
					return b[g.start:g.end]
				})
			}
			return b[c.start:c.end]
		})...)
	}
	if !found {
		return nil, fmt.Errorf("mp4: init segment has no track %d", id)
	}
	return out, nil
}

// splitFragment returns a media segment with only track id's samples. Every
// moof must be followed by its mdat, as ffmpeg writes them.
func splitFragment(b []byte, id uint32) ([]byte, error) {
	top, err := mp4Boxes(b, 0, len(b))
	if err != nil {
		return nil, err
	}
	out := make([]byte, 0, len(b))
	for i := 0; i < len(top); i++ {
		x := top[i]
		switch x.typ {
		case "moof":
			if i+1 >= len(top) || top[i+1].typ != "mdat" {
				return nil, errors.New("mp4: moof without its mdat")
			}
			frag, err := splitMoof(b, x, top[i+1], id)
			if err != nil {
				return nil, err
			}
			out = append(out, frag...)
			i++
		case "mdat":
			return nil, errors.New("mp4: mdat without a moof")
		default:
			out = append(out, b[x.start:x.end]...)
		}
	}
	return out, nil
}

// tfhd and trun flags.
const (
	tfhdBaseDataOffset    = 0x000001
	tfhdSampleDescription = 0x000002
	tfhdDefaultDuration   = 0x000008
	tfhdDefaultSize       = 0x000010
	tfhdDefaultBaseIsMoof = 0x020000
	trunDataOffset        = 0x000001
	trunFirstSampleFlags  = 0x000004
	trunSampleDuration    = 0x000100
	trunSampleSize        = 0x000200
	trunSampleFlags       = 0x000400
	trunSampleCTO         = 0x000800
)

// splitMoof returns one fragment of the moof and mdat with only track id: an
// mfhd, the track's traf with its runs' data offsets pointing into a new
// mdat, and that mdat. A fragment without the track yields nothing.
func splitMoof(b []byte, moof, mdat mp4Box, id uint32) ([]byte, error) {
	kids, err := mp4Boxes(b, moof.body, moof.end)
	if err != nil {
		return nil, err
	}
	var mfhd, traf mp4Box
	var haveMfhd, haveTraf bool
	for _, k := range kids {
		switch k.typ {
		case "mfhd":
			mfhd, haveMfhd = k, true
		case "traf":
			tfhd, ok := childBox(b, k, "tfhd")
			if !ok || tfhd.end-tfhd.body < 8 {
				return nil, errors.New("mp4: traf without tfhd")
			}
			if binary.BigEndian.Uint32(b[tfhd.body+4:]) == id {
				traf, haveTraf = k, true
			}
		}
	}
	if !haveMfhd {
		return nil, errors.New("mp4: moof without mfhd")
	}
	if !haveTraf {
		return nil, nil
	}
	tfhd, _ := childBox(b, traf, "tfhd")
	flags := binary.BigEndian.Uint32(b[tfhd.body:]) & 0xFFFFFF
	if flags&tfhdBaseDataOffset != 0 || flags&tfhdDefaultBaseIsMoof == 0 {
		return nil, errors.New("mp4: fragment offsets are not relative to the moof")
	}
	defaultSize, haveDefaultSize := uint32(0), false
	if flags&tfhdDefaultSize != 0 {
		at := tfhd.body + 8
		if flags&tfhdSampleDescription != 0 {
			at += 4
		}
		if flags&tfhdDefaultDuration != 0 {
			at += 4
		}
		if at+4 > tfhd.end {
			return nil, errors.New("mp4: short tfhd")
		}
		defaultSize, haveDefaultSize = binary.BigEndian.Uint32(b[at:]), true
	}

	// The traf as it was, then each run's data offset rewritten once the new
	// moof's size is known.
	newTraf := append([]byte(nil), b[traf.start:traf.end]...)
	tkids, err := mp4Boxes(b, traf.body, traf.end)
	if err != nil {
		return nil, err
	}
	type run struct {
		offsetAt   int // in newTraf
		from, size int // the samples, in b
	}
	var runs []run
	for _, k := range tkids {
		if k.typ != "trun" {
			continue
		}
		if k.end-k.body < 12 {
			return nil, errors.New("mp4: short trun")
		}
		tf := binary.BigEndian.Uint32(b[k.body:]) & 0xFFFFFF
		if tf&trunDataOffset == 0 {
			return nil, errors.New("mp4: trun without a data offset")
		}
		count := int(binary.BigEndian.Uint32(b[k.body+4:]))
		offset := int(int32(binary.BigEndian.Uint32(b[k.body+8:])))
		at := k.body + 12
		if tf&trunFirstSampleFlags != 0 {
			at += 4
		}
		per := 0
		for _, f := range []uint32{trunSampleDuration, trunSampleSize, trunSampleFlags, trunSampleCTO} {
			if tf&f != 0 {
				per += 4
			}
		}
		if count < 0 || at+count*per > k.end {
			return nil, errors.New("mp4: short trun")
		}
		size := 0
		for i := range count {
			s := at + i*per
			if tf&trunSampleDuration != 0 {
				s += 4
			}
			switch {
			case tf&trunSampleSize != 0:
				size += int(binary.BigEndian.Uint32(b[s:]))
			case haveDefaultSize:
				size += int(defaultSize)
			default:
				return nil, errors.New("mp4: sample sizes are not in the fragment")
			}
		}
		from := moof.start + offset
		if from < mdat.body || from+size > mdat.end {
			return nil, errors.New("mp4: run outside its mdat")
		}
		runs = append(runs, run{offsetAt: k.body + 8 - traf.start, from: from, size: size})
	}

	moofSize := 8 + (mfhd.end - mfhd.start) + len(newTraf)
	var data []byte
	for _, r := range runs {
		binary.BigEndian.PutUint32(newTraf[r.offsetAt:], uint32(moofSize+8+len(data)))
		data = append(data, b[r.from:r.from+r.size]...)
	}
	out := make([]byte, 0, moofSize+8+len(data))
	out = binary.BigEndian.AppendUint32(out, uint32(moofSize))
	out = append(out, "moof"...)
	out = append(out, b[mfhd.start:mfhd.end]...)
	out = append(out, newTraf...)
	out = binary.BigEndian.AppendUint32(out, uint32(8+len(data)))
	out = append(out, "mdat"...)
	return append(out, data...), nil
}
