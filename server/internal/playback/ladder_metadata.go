package playback

import (
	"encoding/binary"
	"errors"
	"fmt"
)

// Read the encoded sample entry, rather than finding a codec-looking string
// in unrelated MP4 metadata. These private ladders support ffmpeg's ordinary
// avc1 and version-zero mp4a entries only.
func ladderSampleEntry(init []byte, kind string) (mp4Box, error) {
	top, err := mp4Boxes(init, 0, len(init))
	if err != nil {
		return mp4Box{}, err
	}
	for _, moov := range top {
		if moov.typ != "moov" {
			continue
		}
		tracks, err := mp4Boxes(init, moov.body, moov.end)
		if err != nil {
			return mp4Box{}, err
		}
		for _, track := range tracks {
			if track.typ != "trak" {
				continue
			}
			parent, ok := track, true
			for _, name := range []string{"mdia", "minf", "stbl", "stsd"} {
				parent, ok = childBox(init, parent, name)
				if !ok {
					break
				}
			}
			if !ok || parent.end-parent.body < 8 {
				continue
			}
			entries, err := mp4Boxes(init, parent.body+8, parent.end)
			if err != nil {
				return mp4Box{}, err
			}
			for _, entry := range entries {
				if entry.typ == kind {
					return entry, nil
				}
			}
		}
	}
	return mp4Box{}, fmt.Errorf("VOD ladder has no encoded %s sample entry", kind)
}

func ladderVideoCodec(init []byte, width, height int) (string, error) {
	entry, err := ladderSampleEntry(init, "avc1")
	if err != nil {
		return "", err
	}
	if entry.end-entry.body < 78 || int(binary.BigEndian.Uint16(init[entry.body+24:])) != width ||
		int(binary.BigEndian.Uint16(init[entry.body+26:])) != height {
		return "", errors.New("VOD ladder encoded resolution does not match its contract")
	}
	entry.body += 78
	config, ok := childBox(init, entry, "avcC")
	if !ok || config.end-config.body < 4 || init[config.body] != 1 {
		return "", errors.New("VOD ladder H.264 configuration is unavailable")
	}
	return fmt.Sprintf("avc1.%02X%02X%02X", init[config.body+1], init[config.body+2], init[config.body+3]), nil
}

// An MPEG-4 descriptor has a tag and at most four bytes of base-128 length.
func ladderDescriptor(data []byte, tag byte) ([]byte, error) {
	if len(data) < 2 || data[0] != tag {
		return nil, errors.New("VOD ladder audio descriptor is unavailable")
	}
	n := 0
	for i := 1; i <= 4 && i < len(data); i++ {
		n = n<<7 | int(data[i]&0x7f)
		if data[i]&0x80 == 0 {
			if n <= len(data)-i-1 {
				return data[i+1 : i+1+n], nil
			}
			break
		}
	}
	return nil, errors.New("VOD ladder audio descriptor is truncated")
}

func ladderAudioChannels(init []byte) (int, error) {
	entry, err := ladderSampleEntry(init, "mp4a")
	if err != nil {
		return 0, err
	}
	if entry.end-entry.body < 28 || binary.BigEndian.Uint16(init[entry.body+8:]) != 0 {
		return 0, errors.New("VOD ladder audio sample entry version is unsupported")
	}
	entry.body += 28
	esds, ok := childBox(init, entry, "esds")
	if !ok || esds.end-esds.body < 4 {
		return 0, errors.New("VOD ladder AAC configuration is unavailable")
	}
	es, err := ladderDescriptor(init[esds.body+4:esds.end], 3)
	if err != nil || len(es) < 3 {
		return 0, errors.New("VOD ladder audio ES descriptor is unavailable")
	}
	offset := 3
	if es[2]&0x80 != 0 {
		offset += 2
	}
	if es[2]&0x40 != 0 {
		if offset >= len(es) {
			return 0, errors.New("VOD ladder audio URL descriptor is truncated")
		}
		offset += 1 + int(es[offset])
	}
	if es[2]&0x20 != 0 {
		offset += 2
	}
	if offset > len(es) {
		return 0, errors.New("VOD ladder audio ES descriptor is truncated")
	}
	decoder, err := ladderDescriptor(es[offset:], 4)
	if err != nil || len(decoder) < 13 || decoder[0] != 0x40 || decoder[1]>>2 != 5 {
		return 0, errors.New("VOD ladder common audio is not MPEG-4 AAC")
	}
	config, err := ladderDescriptor(decoder[13:], 5)
	if err != nil {
		return 0, err
	}
	return ladderAACChannels(config)
}

func ladderAACChannels(config []byte) (int, error) {
	if len(config) < 2 || len(config) > 256 || config[0]>>3 != 2 {
		return 0, errors.New("VOD ladder common audio must be AAC-LC")
	}
	rate := int(config[0]&7)<<1 | int(config[1]>>7)
	channels := int(config[1]>>3) & 15
	if rate >= 13 || channels == 0 || channels > 7 {
		return 0, errors.New("VOD ladder AAC rate or channel configuration is unsupported")
	}
	// AAC-LC can carry an implicit SBR extension. Do not label that HE-AAC
	// output as LC even when the leading audio object type is two.
	bits := func(at, count int) int {
		n := 0
		for i := at; i < at+count; i++ {
			n = n<<1 | int(config[i/8]>>uint(7-i%8)&1)
		}
		return n
	}
	for i := 13; i+17 <= len(config)*8; i++ {
		if bits(i, 11) == 0x2b7 && bits(i+11, 5) == 5 && bits(i+16, 1) == 1 {
			return 0, errors.New("VOD ladder common audio has an unsupported SBR extension")
		}
	}
	if channels == 7 {
		return 8, nil
	}
	return channels, nil
}
