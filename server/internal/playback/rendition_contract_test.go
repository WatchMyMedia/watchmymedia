package playback

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestRenditionContractSeparatesOutputAndSourceRevision(t *testing.T) {
	path := filepath.Join(t.TempDir(), "fictional.mp4")
	if err := os.WriteFile(path, []byte("fictional source bytes"), 0o600); err != nil {
		t.Fatal(err)
	}
	spec := Spec{FileID: 11, Path: path, Mode: ModeTranscode, Video: VideoPlan{Codec: "h264", Width: 1280, Height: 720, Encoder: "libx264"},
		Audio: AudioPlan{Index: 1, Codec: "aac", Channels: 2}, BurnIn: -1,
		VOD: &VODOptions{DurationMs: 30_000, FrameRate: 30, Bandwidth: 4_500_000}}
	base, err := renditionContract(spec)
	if err != nil {
		t.Fatal(err)
	}
	otherViewer := spec
	otherViewer.PlayID, otherViewer.UserID, otherViewer.StartMs = "another-play", 77, 20_000
	if got, err := renditionContract(otherViewer); err != nil || got != base {
		t.Fatalf("viewer or seek changed media identity: %q, %v", got, err)
	}
	for name, change := range map[string]func(*Spec){
		"height":              func(s *Spec) { s.Video.Height = 480 },
		"audio":               func(s *Spec) { s.Audio.Index = 2 },
		"hdr":                 func(s *Spec) { s.Video.HDR = "hdr10" },
		"adapter":             func(s *Spec) { s.caps = &Capabilities{Encoder: "h264_videotoolbox", HWAccel: "videotoolbox"} },
		"burn-in":             func(s *Spec) { s.BurnIn = 3 },
		"constant frame rate": func(s *Spec) { s.ReserveSlot = true },
		"timeline": func(s *Spec) {
			s.VOD = &VODOptions{DurationMs: 30_000, FrameRate: 30, Bandwidth: 4_500_000,
				Keyframes: []int64{0, 6_000_000, 12_000_000}}
		},
	} {
		t.Run(name, func(t *testing.T) {
			changed := spec
			change(&changed)
			key, err := renditionContract(changed)
			if err != nil || key == base {
				t.Fatalf("contract collision: %q, %v", key, err)
			}
		})
	}
	when := time.Now().Add(time.Hour)
	if err := os.Chtimes(path, when, when); err != nil {
		t.Fatal(err)
	}
	if key, err := renditionContract(spec); err != nil || key == base {
		t.Fatalf("source revision collision: %q, %v", key, err)
	}
}

func TestRenditionContractTracksBurnInSidecarRevision(t *testing.T) {
	dir := t.TempDir()
	path, sidecar := filepath.Join(dir, "movie.mp4"), filepath.Join(dir, "captions.srt")
	for _, file := range []string{path, sidecar} {
		if err := os.WriteFile(file, []byte("fictional"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	spec := Spec{Path: path, BurnInFile: sidecar, VOD: &VODOptions{DurationMs: 1000}}
	before, err := renditionContract(spec)
	if err != nil {
		t.Fatal(err)
	}
	when := time.Now().Add(time.Hour)
	if err := os.Chtimes(sidecar, when, when); err != nil {
		t.Fatal(err)
	}
	if after, err := renditionContract(spec); err != nil || after == before {
		t.Fatalf("sidecar revision collision: %q, %v", after, err)
	}
}
