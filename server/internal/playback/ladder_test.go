package playback

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"
)

func ladderSpecs(src string) (Spec, Spec) {
	base := Spec{FileID: 1, PlayID: "fictional-play", UserID: 1, Path: src, Mode: ModeTranscode, BurnIn: -1,
		Video: VideoPlan{Codec: "h264", Encoder: "libx264", Width: 160, Height: 90},
		Audio: AudioPlan{Index: 1, Codec: "aac", Channels: 1}, MaxBitrate: 400_000,
		VOD: &VODOptions{DurationMs: 24_000, FrameRate: 24, Bandwidth: 1_100_000,
			VideoRange: "SDR", Codecs: "avc1.64000d,mp4a.40.2", SeparateAudio: true, AudioChannels: 1}}
	higher := base
	higher.Video.Width, higher.Video.Height = 320, 180
	higher.MaxBitrate = 1_000_000
	higher.VOD = &VODOptions{DurationMs: 24_000, FrameRate: 24, Bandwidth: 2_300_000,
		VideoRange: "SDR", Codecs: "avc1.64000d,mp4a.40.2", SeparateAudio: true, AudioChannels: 1}
	return base, higher
}

// startLadderOrSkip starts a family, or skips the test when the server
// refuses it with ErrLadderNotReady. Admission measures this host's software
// encoder against every configured slot (ladderPreparationReady): with four
// slots it needs five times real time, which a busy hosted VM may not reach.
// That refusal is the budget working, and these tests check what a family
// does once admitted, so there is nothing for them to check without one.
func startLadderOrSkip(t *testing.T, m *Manager, low, high Spec) *Session {
	t.Helper()
	s, err := m.StartVODLadder(t.Context(), low, high)
	if errors.Is(err, ErrLadderNotReady) {
		t.Skipf("host too slow to admit a family with %d configured slots: %v", m.limit, err)
	}
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func TestVODLadderUnsupportedOutputsRemainUnadvertised(t *testing.T) {
	for _, encoder := range []string{"h264_videotoolbox", "h264_nvenc", "h264_qsv", "h264_vaapi"} {
		t.Run(encoder, func(t *testing.T) {
			m := NewManager("unused", t.TempDir(), Capabilities{Encoder: encoder, Encoders: []string{encoder}}, time.Minute, 2, testLog)
			low, high := ladderSpecs("fictional.mp4")
			low.Video.Encoder, high.Video.Encoder = encoder, encoder
			if err := m.checkVODLadder(low, high); err == nil || !strings.Contains(err.Error(), "encoder is not yet validated") {
				t.Fatalf("unqualified shared hardware output was admitted: %v", err)
			}
		})
	}
	for _, tc := range []struct {
		name   string
		change func(*Spec)
	}{
		{"copied audio", func(s *Spec) { s.Audio.Copy = true }},
		{"surround audio", func(s *Spec) { s.Audio.Channels, s.VOD.AudioChannels = 6, 6 }},
		{"unknown channels", func(s *Spec) { s.Audio.Channels, s.VOD.AudioChannels = 0, 0 }},
		{"HDR", func(s *Spec) { s.Video.HDR, s.VOD.VideoRange = "hdr10", "PQ" }},
		{"burned subtitles", func(s *Spec) { s.BurnIn = 2 }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			m := NewManager("unused", t.TempDir(), Capabilities{Encoder: "libx264", Encoders: []string{"libx264"}}, time.Minute, 2, testLog)
			low, high := ladderSpecs("fictional.mp4")
			tc.change(&low)
			tc.change(&high)
			if err := m.checkVODLadder(low, high); err == nil {
				t.Fatal("unsupported output was admitted")
			}
		})
	}
}

func TestVODLadderPinsDeclaredFrameRate(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	src := keyedSample(t, ffmpeg, 240, "0,3,6,9") // source is 24 fps
	m := NewManager(ffmpeg, t.TempDir(), detectedCaps(ffmpeg, "software"), time.Minute, 2, testLog)
	defer m.StopAll()
	low, high := ladderSpecs(src)
	low.VOD.DurationMs, high.VOD.DurationMs = 240_000, 240_000
	low.VOD.FrameRate, high.VOD.FrameRate = 30, 30
	s := startLadderOrSkip(t, m, low, high)
	file, _ := fetchAll(t, s, []int{0, 1})
	pts, _ := packets(t, ffmpeg, file, "v:0")
	if len(pts) < 355 || len(pts) > 365 {
		t.Fatalf("declared 30 fps, got %d frames in 12 seconds", len(pts))
	}
	sort.Float64s(pts) // H.264 B-frames arrive in decode order.
	for i := 1; i < len(pts); i++ {
		if delta := pts[i] - pts[i-1]; abs(delta-1.0/30) > 0.002 {
			t.Fatalf("declared 30 fps, frame interval=%f", delta)
		}
	}
	s.vod.suspend()
	cold, _ := fetchAll(t, s, []int{30})
	pts, _ = packets(t, ffmpeg, cold, "v:0")
	sort.Float64s(pts)
	if len(pts) < 179 || len(pts) > 181 {
		t.Fatalf("cold seek at declared 30 fps returned %d frames", len(pts))
	}
	if abs(pts[0]-(180+vodTimeOffset)) > 0.04 || abs(pts[len(pts)-1]-(185.967+vodTimeOffset)) > 0.04 {
		t.Fatalf("cold seek did not preserve the declared rate and timeline: %d frames, %.3f..%.3f", len(pts), pts[0], pts[len(pts)-1])
	}
}

// A manifest with a cold variant must retain enough process capacity for
// that variant, including after its initial worker pauses. Every switch point
// has to begin on a decodable IDR and stay on the same media timeline.
func TestVODLadderColdRungAdmissionAndSwitches(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	src := keyedSample(t, ffmpeg, 24, "0,3,6.5,9,12,18,21")
	low, high := ladderSpecs(src)
	m := NewManager(ffmpeg, t.TempDir(), detectedCaps(ffmpeg, "software"), time.Minute, 2, testLog)
	t.Cleanup(m.StopAll)
	first := startLadderOrSkip(t, m, low, high)
	family := first.ladder.Load()
	if family == nil || len(family.members) != 2 {
		t.Fatal("both variants were not committed as a family")
	}
	second := family.members[1]
	master, ct, err := first.VODFile(t.Context(), VODMaster)
	if err != nil || ct != "application/vnd.apple.mpegurl" ||
		!strings.Contains(string(master), "BANDWIDTH=1100000") ||
		!strings.Contains(string(master), "BANDWIDTH=2300000") ||
		!strings.Contains(string(master), `#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Audio",CHANNELS="1"`) ||
		!strings.Contains(string(master), "../"+first.ID+"/video.m3u8") ||
		!strings.Contains(string(master), "../"+second.ID+"/video.m3u8") {
		t.Fatalf("invalid ladder manifest: %v\n%s", err, master)
	}
	for _, s := range family.members {
		init, _, err := s.VODFile(t.Context(), VODInit)
		if err != nil {
			t.Fatal(err)
		}
		actual := avcConfigCodecs(init) + ",mp4a.40.2"
		if !strings.Contains(string(master), `CODECS="`+actual+`"`) {
			t.Fatalf("ladder advertised a codec other than its encoded init %s:\n%s", actual, master)
		}
	}
	second.vod.suspend()
	m.mu.Lock()
	claims := m.claimedLocked(nil)
	m.mu.Unlock()
	if claims != 2 {
		t.Fatalf("cold family claims = %d, want 2", claims)
	}
	if release, err := m.ReserveLive(t.Context()); !errors.Is(err, ErrTooManySessions) {
		if release != nil {
			release()
		}
		t.Fatalf("live playback stole an advertised rung's slot: %v", err)
	}
	var video, audio []float64
	for k, s := range []*Session{first, second, first, second} {
		init, _, err := s.VODFile(t.Context(), VODInit)
		if err != nil {
			t.Fatal(err)
		}
		seg, _, err := s.VODFile(t.Context(), fmt.Sprintf("v%05d.m4s", k))
		if err != nil {
			t.Fatalf("cold switch to variant %d at segment %d: %v", k%2, k, err)
		}
		file := filepath.Join(t.TempDir(), fmt.Sprintf("switched-%d.mp4", k))
		if err := os.WriteFile(file, append(init, seg...), 0o600); err != nil {
			t.Fatal(err)
		}
		pts, key := packets(t, ffmpeg, file, "v:0")
		if len(pts) == 0 || !key[0] {
			t.Fatalf("variant %d segment %d lacks a decodable IDR", k%2, k)
		}
		video = append(video, pts...)
		if types := streamTypes(t, ffmpeg, file); types != "video" {
			t.Fatalf("video rendition contained %s", types)
		}
		audioInit, _, err := first.VODFile(t.Context(), VODAudioInit)
		if err != nil {
			t.Fatal(err)
		}
		audioSegment, _, err := first.VODFile(t.Context(), fmt.Sprintf("a%05d.m4s", k))
		if err != nil {
			t.Fatal(err)
		}
		audioFile := filepath.Join(t.TempDir(), fmt.Sprintf("audio-%d.mp4", k))
		if err := os.WriteFile(audioFile, append(audioInit, audioSegment...), 0o600); err != nil {
			t.Fatal(err)
		}
		if types := streamTypes(t, ffmpeg, audioFile); types != "audio" {
			t.Fatalf("audio rendition contained %s", types)
		}
		apts, _ := packets(t, ffmpeg, audioFile, "a:0")
		audio = append(audio, apts...)
	}
	checkContinuous(t, video, 0, 23.9)
	sort.Float64s(audio)
	if len(audio) < 1000 {
		t.Fatalf("switched audio has only %d samples", len(audio))
	}
	if audio[0] > vodTimeOffset+0.05 || audio[len(audio)-1] < vodTimeOffset+23.8 {
		t.Fatalf("switched audio has %d samples from %.3f to %.3f", len(audio), audio[0], audio[len(audio)-1])
	}
	for i := 1; i < len(audio); i++ {
		if gap := audio[i] - audio[i-1]; gap > 0.05 {
			t.Fatalf("audio gap %.3fs after switching at %.3fs", gap, audio[i]-vodTimeOffset)
		}
	}
	// Check every segment in this complete fictional file, rather than only
	// the four switch samples. BANDWIDTH includes the shared audio rendition.
	for _, s := range family.members {
		var peak, total int64
		for k := range s.Segments() {
			video, _, err := s.VODFile(t.Context(), fmt.Sprintf("v%05d.m4s", k))
			if err != nil {
				t.Fatal(err)
			}
			audio, _, err := first.VODFile(t.Context(), fmt.Sprintf("a%05d.m4s", k))
			if err != nil {
				t.Fatal(err)
			}
			length := int64(len(video) + len(audio))
			total += length
			segmentMs := min(int64(vodSegmentSeconds*1000), s.vod.opts.DurationMs-int64(k*vodSegmentSeconds*1000))
			peak = max(peak, length*8_000/segmentMs)
		}
		average := total * 8_000 / s.vod.opts.DurationMs
		if peak > s.vod.opts.Bandwidth {
			t.Fatalf("complete fixture rung %dx%d peak %d exceeds declared %d (average %d)",
				s.Video.Width, s.Video.Height, peak, s.vod.opts.Bandwidth, average)
		}
		t.Logf("complete fixture rung %dx%d peak=%d average=%d declared=%d bps",
			s.Video.Width, s.Video.Height, peak, average, s.vod.opts.Bandwidth)
	}
	m.Stop(second.ID)
	if _, ok := m.Get(first.ID); ok {
		t.Fatal("stopped family retained its first variant")
	}
	if _, ok := m.Get(second.ID); ok {
		t.Fatal("stopped family retained its second variant")
	}
	m.mu.Lock()
	claims = m.claimedLocked(nil)
	m.mu.Unlock()
	if claims != 0 {
		t.Fatalf("stopped family leaked %d capacity claims", claims)
	}
}

func TestVODLadderRejectedVariantsLeaveNoClaims(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	src := keyedSample(t, ffmpeg, 24, "0,3,6.5,9,12,18,21")
	low, high := ladderSpecs(src)
	m := NewManager(ffmpeg, t.TempDir(), detectedCaps(ffmpeg, "software"), time.Minute, 1, testLog)
	t.Cleanup(m.StopAll)
	if _, err := m.StartVODLadder(t.Context(), low, high); !errors.Is(err, ErrTooManySessions) {
		t.Fatalf("second rung should fail under one slot: %v", err)
	}
	if len(m.Sessions()) != 0 {
		t.Fatal("failed family left a prepared rung")
	}
	high.VOD.DurationMs++
	if _, err := m.StartVODLadder(context.Background(), low, high); err == nil || len(m.Sessions()) != 0 {
		t.Fatalf("mismatched timeline was admitted: %v", err)
	}
	high.VOD.DurationMs = low.VOD.DurationMs
	high.VOD.Bandwidth = 1_000_000
	if _, err := m.StartVODLadder(context.Background(), low, high); err == nil || len(m.Sessions()) != 0 {
		t.Fatalf("underdeclared peak bandwidth was admitted: %v", err)
	}
	high.VOD.Bandwidth = 2_300_000
	low.VOD.Codecs, high.VOD.Codecs = "avc1.64000d,mp4a.40.5", "avc1.64000d,mp4a.40.5"
	if _, err := m.StartVODLadder(context.Background(), low, high); err == nil || len(m.Sessions()) != 0 {
		t.Fatalf("unsupported common audio codec was admitted: %v", err)
	}
}

func TestVODLadderTwoViewersShareWarmOutputButNotLifecycle(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	src := keyedSample(t, ffmpeg, 24, "0,3,6.5,9,12,18,21")
	low, high := ladderSpecs(src)
	m := NewManager(ffmpeg, t.TempDir(), detectedCaps(ffmpeg, "software"), time.Minute, 4, testLog)
	t.Cleanup(m.StopAll)
	first := startLadderOrSkip(t, m, low, high)
	low.UserID, high.UserID = 2, 2
	low.PlayID, high.PlayID = "other-fictional-play", "other-fictional-play"
	second := startLadderOrSkip(t, m, low, high)
	if second.ID == first.ID || second.ladder.Load().members[1].ID == first.ladder.Load().members[1].ID {
		t.Fatal("two viewers share a session rather than only rendition bytes")
	}
	for _, member := range second.ladder.Load().members {
		member.vod.mu.Lock()
		runs := member.vod.runs
		member.vod.mu.Unlock()
		if runs != 0 {
			t.Fatalf("warm join started a duplicate encoder for %s", member.ID)
		}
	}
	for _, member := range second.ladder.Load().members {
		for _, name := range []string{VODInit, VODAudioInit, "v00000.m4s", "a00000.m4s"} {
			if b, _, err := member.VODFile(t.Context(), name); err != nil || len(b) == 0 {
				t.Fatalf("warm join could not split %s: %v", name, err)
			}
		}
	}
	m.Stop(first.ID)
	for _, member := range second.ladder.Load().members {
		if _, ok := m.Get(member.ID); !ok {
			t.Fatal("one viewer's stop removed the other's rung")
		}
		if b, _, err := member.VODFile(t.Context(), "v00001.m4s"); err != nil || len(b) == 0 {
			t.Fatalf("other viewer lost its next segment: %v", err)
		}
	}
}

func TestVODLadderPrewarmsHigherVideoFromAudioTimeline(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	src := keyedSample(t, ffmpeg, 24, "0,3,6.5,9,12,18,21")
	low, high := ladderSpecs(src)
	m := NewManager(ffmpeg, t.TempDir(), detectedCaps(ffmpeg, "software"), time.Minute, 2, testLog)
	t.Cleanup(m.StopAll)
	first := startLadderOrSkip(t, m, low, high)
	family := first.ladder.Load()
	higher := family.members[1]
	higher.vod.suspend()
	if _, _, err := first.VODFile(t.Context(), "a00000.m4s"); err != nil {
		t.Fatal(err)
	}
	higher.vod.mu.Lock()
	key := fmt.Sprintf("%s/init/%s/segment/1", higher.vod.spec.sharedKey, higher.vod.startupHash)
	higher.vod.mu.Unlock()
	deadline := time.Now().Add(5 * time.Second)
	ready := false
	for time.Now().Before(deadline) {
		m.renditions.mu.Lock()
		entry := m.renditions.entries[key]
		ready = entry != nil && entry.completed && entry.err == nil
		m.renditions.mu.Unlock()
		if ready {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if !ready {
		t.Fatal("the alternate video segment did not warm as audio advanced")
	}
	if b, _, err := higher.VODFile(t.Context(), "v00001.m4s"); err != nil || len(b) == 0 {
		t.Fatalf("prewarmed alternate segment unavailable: %v", err)
	}
	m.Stop(first.ID)
	select {
	case <-family.warmDone:
	case <-time.After(2 * time.Second):
		t.Fatal("ladder warming worker remained after stop")
	}
}

// A family needs both slots. With one, nothing starts: before, the low rung
// encoded for its startup deadline and was then thrown away.
func TestVODLadderNeedsBothSlotsBeforeAnyStart(t *testing.T) {
	low, high := ladderSpecs(filepath.Join(t.TempDir(), "missing.mp4"))
	one := NewManager("unused", t.TempDir(), Capabilities{Encoder: "libx264", Encoders: []string{"libx264"}}, time.Minute, 1, testLog)
	if _, err := one.StartVODLadder(t.Context(), low, high); !errors.Is(err, ErrTooManySessions) {
		t.Fatalf("one free slot: %v", err)
	}
	// With two free slots the ladder goes on to the source (missing here).
	two := NewManager("unused", t.TempDir(), Capabilities{Encoder: "libx264", Encoders: []string{"libx264"}}, time.Minute, 2, testLog)
	if _, err := two.StartVODLadder(t.Context(), low, high); err == nil || errors.Is(err, ErrTooManySessions) {
		t.Fatalf("two free slots: %v", err)
	}
}
