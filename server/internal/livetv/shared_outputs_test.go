package livetv

import (
	"context"
	"errors"
	"fmt"
	"io"
	"strings"
	"testing"
	"time"
)

type generationCapture struct {
	io.ReadCloser
	generation int64
}

func (c *generationCapture) CaptureIdentity() (string, int64) {
	return "fictional-capture", c.generation
}
func (c *generationCapture) Fork(context.Context) (io.ReadCloser, error) {
	return nil, errors.New("unused")
}
func TestSharedOutputContractAndGeneration(t *testing.T) {
	s := &session{outputKind: "single", view: SessionView{Quality: "720"}, profile: &LiveProfile{SegmentFormats: []string{"fmp4"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}}}
	key := liveOutputKey("capture", 1, s)
	for _, change := range []func(){
		func() { s.view.Quality = "360" }, func() { s.profile.MaxAudioChannels = 2 }, func() { s.profile.HDRTypes = []string{"hdr10"} }, func() { s.outputKind = "ladder" },
	} {
		change()
		next := liveOutputKey("capture", 1, s)
		if next == key {
			t.Fatal("distinct output contract reused a key")
		}
		key = next
	}
	if key == liveOutputKey("capture", 2, s) || key == liveOutputKey("other-capture", 1, s) {
		t.Fatal("capture identity missing from contract")
	}
	c := &generationCapture{ReadCloser: io.NopCloser(strings.NewReader("old then new")), generation: 1}
	r := &generationInput{liveCapture: c, id: "fictional-capture", generation: 1}
	b := make([]byte, 3)
	if n, err := r.Read(b); n != 3 || err != nil {
		t.Fatal(n, err)
	}
	c.generation = 2
	if n, err := r.Read(b); n != 0 || !errors.Is(err, errCaptureChanged) {
		t.Fatalf("new timeline leaked: %d %v", n, err)
	}
}

func TestPrivateLiveFamilyEnvelopeAndNames(t *testing.T) {
	source := SourceInfo{Width: 1280, Height: 720, SquarePixels: true, ScanKnown: true, AudioPresent: true, AudioChannels: 2}
	profile := &LiveProfile{}
	family, err := newLiveFamily(source, profile, "720")
	if err != nil {
		t.Fatal(err)
	}
	limited, err := newLiveFamily(source, &LiveProfile{MaxBitrate: 1_200_000}, "720")
	if err != nil {
		t.Fatal(err)
	}
	for _, rung := range limited.rungs {
		if 2*rung.bitrate+256_000 > 1_200_000 {
			t.Fatal("advertised bandwidth exceeds client cap")
		}
	}
	if family.rungs[0].height >= family.rungs[1].height {
		t.Fatal("duplicate ladder rungs")
	}
	for _, source := range []SourceInfo{{}, {Width: 1280, Height: 720, ScanKnown: true, AudioPresent: true, AudioChannels: 2}, {Width: 1280, Height: 720, SquarePixels: true, ScanKnown: true, AudioPresent: true, AudioChannels: 2, HDRType: "hdr10"}} {
		if _, err := newLiveFamily(source, profile, "720"); err == nil {
			t.Fatal("unqualified source admitted")
		}
	}
	for _, name := range []string{"low-live.m3u8", "high-init.mp4", "audio-segment-000000001.m4s"} {
		if !liveFamilyName(name) {
			t.Fatal(name)
		}
	}
	for _, name := range []string{"../low-init.mp4", "low/../init.mp4", "low-segment-000000001.ts", "audio-live.m3u8.tmp", "high-init.mp4/other", "../live.m3u8"} {
		if liveFamilyName(name) {
			t.Fatal(name)
		}
	}
}

func TestLiveFamilyWindowRejectsMalformedSegments(t *testing.T) {
	valid := "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:4\n#EXTINF:2.000000,\nlow-segment-000000004.m4s\n"
	if w, err := familyWindow([]byte(valid), "low"); err != nil || w.first != 4 || len(w.names) != 1 {
		t.Fatalf("%+v %v", w, err)
	}
	for _, bad := range []string{strings.ReplaceAll(valid, "2.000000", "NaN"), strings.ReplaceAll(valid, "000000004", "000000005"), strings.ReplaceAll(valid, "low-segment", "../low-segment"), valid + "#EXTINF:2,\n", strings.ReplaceAll(valid, "SEQUENCE:4", "SEQUENCE:-1")} {
		if _, err := familyWindow([]byte(bad), "low"); err == nil {
			t.Fatalf("accepted %s", bad)
		}
	}
}

func TestLiveFamilyWithholdsPartialAndStalledMembers(t *testing.T) {
	f := &liveFamily{pending: map[string][]byte{}, updated: map[string]time.Time{}, playlists: map[string][]byte{}, last: -1}
	for _, name := range []string{"low", "high", "audio"} {
		var b strings.Builder
		b.WriteString("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:0\n")
		count := 3
		if name == "high" {
			count = 2
		}
		for i := range count {
			fmt.Fprintf(&b, "#EXTINF:2.000000,\n%s-segment-%09d.m4s\n", name, i)
		}
		f.pending[name+"-live.m3u8"] = []byte(b.String())
	}
	if b, err := f.update(t.TempDir()); err != nil || len(b) != 0 || len(f.playlists) != 0 {
		t.Fatalf("partial family was published: %q %v", b, err)
	}
	f.master = []byte("previous immutable master")
	f.updated["audio-live.m3u8"] = time.Now().Add(-7 * time.Second)
	f.updated["low-live.m3u8"] = time.Now()
	f.updated["high-live.m3u8"] = time.Now()
	if _, err := f.update(t.TempDir()); err == nil {
		t.Fatal("advancing video concealed stalled common audio")
	}
}
