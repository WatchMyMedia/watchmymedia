package playback

import (
	"bytes"
	"testing"
	"time"
)

func TestLadderAACConfigurationRejectsMisleadingProfiles(t *testing.T) {
	for _, tc := range []struct {
		name     string
		config   []byte
		channels int
	}{
		{"48 kHz mono LC", []byte{0x11, 0x88}, 1},
		{"44.1 kHz stereo LC", []byte{0x12, 0x10}, 2},
		{"eight-channel configuration", []byte{0x11, 0xb8}, 8},
		{"LC with disabled SBR extension", []byte{0x11, 0x88, 0x56, 0xe5, 0}, 1},
		{"implicit SBR is not LC", []byte{0x11, 0x88, 0x56, 0xe5, 0x80}, 0},
		{"HE-AAC is not LC", []byte{0x29, 0x88}, 0},
		{"HE-AACv2 is not LC", []byte{0xe9, 0x88}, 0},
		{"unknown PCE channels", []byte{0x11, 0x80}, 0},
		{"explicit frequency is unsupported", []byte{0x17, 0x88}, 0},
		{"truncated", []byte{0x11}, 0},
	} {
		t.Run(tc.name, func(t *testing.T) {
			channels, err := ladderAACChannels(tc.config)
			if tc.channels == 0 {
				if err == nil {
					t.Fatal("unsupported configuration would be advertised as AAC-LC")
				}
			} else if err != nil || channels != tc.channels {
				t.Fatalf("configuration channels = %d, %v, want %d", channels, err, tc.channels)
			}
		})
	}
}

func TestLadderAudioDescriptorsAreBounded(t *testing.T) {
	if data, err := ladderDescriptor([]byte{3, 0x80, 0x80, 0x80, 2, 1, 2}, 3); err != nil || !bytes.Equal(data, []byte{1, 2}) {
		t.Fatalf("four-byte descriptor length: %x %v", data, err)
	}
	for _, data := range [][]byte{{}, {3}, {4, 0}, {3, 0x80}, {3, 3, 1}, {3, 0xff, 0xff, 0xff, 0xff, 1}} {
		if _, err := ladderDescriptor(data, 3); err == nil {
			t.Fatalf("malformed descriptor accepted: %x", data)
		}
	}
}

func TestLadderMetadataIgnoresCodecStringsOutsideSampleEntries(t *testing.T) {
	// An MP4 can contain arbitrary text metadata. Codec-looking bytes in a
	// valid unrelated box must not authorize a master playlist.
	data := append([]byte{0, 0, 0, 32, 'f', 'r', 'e', 'e'}, []byte("avcC\x01\x64\x00\x1fmp4a.40.2esds")...)
	data = append(data, make([]byte, 32-len(data))...)
	if _, err := ladderVideoCodec(data, 160, 90); err == nil {
		t.Fatal("unrelated metadata supplied an H.264 configuration")
	}
	if _, err := ladderAudioChannels(data); err == nil {
		t.Fatal("unrelated metadata supplied an AAC configuration")
	}
}

func TestVODLadderRejectsFalseAudioChannelsAndReleasesClaims(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	src := keyedSample(t, ffmpeg, 24, "0,3,6,12,18,21")
	low, high := ladderSpecs(src)
	// The real generated source is mono, even when a caller says stereo.
	low.VOD.AudioChannels, high.VOD.AudioChannels = 2, 2
	m := NewManager(ffmpeg, t.TempDir(), detectedCaps(ffmpeg, "software"), time.Minute, 2, testLog)
	t.Cleanup(m.StopAll)
	if _, err := m.StartVODLadder(t.Context(), low, high); err == nil {
		t.Fatal("a master with false channel metadata was published")
	}
	if m.Claimed() != 0 || len(m.Sessions()) != 0 {
		t.Fatal("audio metadata rejection retained a session or capacity claim")
	}
}
