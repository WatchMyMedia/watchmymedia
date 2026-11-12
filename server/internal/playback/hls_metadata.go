package playback

import (
	"encoding/binary"
	"errors"
	"math"
)

// HLSVideoInit checks the actual encoded dimensions and returns its codec tag.
// Live and VOD private ladders use the same conservative H.264 sample contract.
func HLSVideoInit(init []byte, width, height int) (string, error) {
	return ladderVideoCodec(init, width, height)
}

// HLSAudioInit checks that the shared audio is the promised AAC-LC layout.
func HLSAudioInit(init []byte, channels int) error {
	actual, err := ladderAudioChannels(init)
	if err != nil {
		return err
	}
	if actual != channels {
		return errors.New("HLS audio channel layout differs from contract")
	}
	return nil
}

// HLSVideoBoundary validates a complete single-video fragment and its first
// independent sample, returning decode time in microseconds. It never rewrites
// live timestamps: both encoders must have produced the same capture boundary.
func HLSVideoBoundary(init, media []byte) (int64, error) {
	bad := errors.New("HLS video fragment has no independent capture boundary")
	if err := validateRenditionFragment(media); err != nil {
		return 0, err
	}
	info, err := parseInit(init)
	if err != nil || info.Video == 0 || len(info.Tracks) != 1 {
		return 0, bad
	}
	boxes, err := mp4Boxes(media, 0, len(media))
	if err != nil {
		return 0, err
	}
	for _, moof := range boxes {
		if moof.typ != "moof" {
			continue
		}
		traf, ok := childBox(media, moof, "traf")
		if !ok {
			return 0, bad
		}
		tfhd, ok := childBox(media, traf, "tfhd")
		if !ok || tfhd.end-tfhd.body < 8 {
			return 0, bad
		}
		flags := binary.BigEndian.Uint32(media[tfhd.body:]) & 0xffffff
		if binary.BigEndian.Uint32(media[tfhd.body+4:]) != info.Video {
			return 0, bad
		}
		offset := tfhd.body + 8
		for _, field := range []struct {
			flag uint32
			size int
		}{{1, 8}, {2, 4}, {8, 4}, {16, 4}} {
			if flags&field.flag != 0 {
				offset += field.size
			}
		}
		sampleFlags := uint32(0x10000)
		if flags&32 != 0 {
			if offset+4 > tfhd.end {
				return 0, bad
			}
			sampleFlags = binary.BigEndian.Uint32(media[offset:])
		}
		trun, ok := childBox(media, traf, "trun")
		if !ok || trun.end-trun.body < 8 || binary.BigEndian.Uint32(media[trun.body+4:]) == 0 {
			return 0, bad
		}
		flags = binary.BigEndian.Uint32(media[trun.body:]) & 0xffffff
		offset = trun.body + 8
		if flags&1 != 0 {
			offset += 4
		}
		if flags&4 != 0 {
			if offset+4 > trun.end {
				return 0, bad
			}
			sampleFlags = binary.BigEndian.Uint32(media[offset:])
		} else if flags&0x400 != 0 {
			if flags&0x100 != 0 {
				offset += 4
			}
			if flags&0x200 != 0 {
				offset += 4
			}
			if offset+4 > trun.end {
				return 0, bad
			}
			sampleFlags = binary.BigEndian.Uint32(media[offset:])
		}
		if sampleFlags&0x10000 != 0 || sampleFlags>>24&3 != 2 {
			return 0, bad
		}
		tfdt, ok := childBox(media, traf, "tfdt")
		if !ok || tfdt.end-tfdt.body < 8 {
			return 0, bad
		}
		ticks := uint64(binary.BigEndian.Uint32(media[tfdt.body+4:]))
		if media[tfdt.body] == 1 {
			if tfdt.end-tfdt.body < 12 {
				return 0, bad
			}
			ticks = binary.BigEndian.Uint64(media[tfdt.body+4:])
		}
		scale := info.Tracks[info.Video].Timescale
		if scale == 0 || ticks > math.MaxInt64/1_000_000 {
			return 0, bad
		}
		return int64(ticks * 1_000_000 / uint64(scale)), nil
	}
	return 0, bad
}
