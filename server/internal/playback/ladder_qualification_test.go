//go:build streamingacceptance

package playback

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	"watchmymedia/server/internal/ffexec"
)

// Measure every fragment of complete, varied HD sources, including common
// audio. This is an explicit software-output qualification, not a benchmark
// or a claim about arbitrary library sources or hardware encoders.
func TestVODLadderFullFileQualification(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	for _, tc := range []struct {
		name, rate, filter, video, audio string
		fps                              float64
	}{
		{"motion-stereo", "24", "null", "libx264", "aac", 24},
		{"noise-fractional", "30000/1001", "noise=alls=32:allf=t+u", "libx264", "aac", 30000.0 / 1001},
		{"hevc-eac3", "24000/1001", "null", "libx265", "eac3", 24000.0 / 1001},
	} {
		t.Run(tc.name, func(t *testing.T) {
			const duration = 36
			ctx, cancel := context.WithTimeout(t.Context(), 4*time.Minute)
			defer cancel()
			src := filepath.Join(t.TempDir(), "fictional.mkv")
			args := ffexec.FFmpegArgs("-f", "lavfi", "-i", "testsrc2=size=1920x1080:rate="+tc.rate,
				"-f", "lavfi", "-i", "anoisesrc=color=pink:sample_rate=48000:amplitude=0.2",
				"-t", fmt.Sprint(duration), "-vf", tc.filter, "-c:v", tc.video, "-preset", "ultrafast", "-crf", "20",
				"-c:a", tc.audio, "-b:a", "384k", "-ac", "2")
			if tc.video == "libx265" {
				args = append(args, "-x265-params", "pools=2:frame-threads=2:log-level=error")
			}
			args = append(args, src)
			if err := ffexec.Command(ctx, ffmpeg, args...).Run(); err != nil {
				t.Fatal(err)
			}
			low, high := ladderSpecs(src)
			low.Video.Width, low.Video.Height, low.MaxBitrate = 1280, 720, 1_800_000
			high.Video.Width, high.Video.Height, high.MaxBitrate = 1920, 1080, 4_000_000
			for _, spec := range []*Spec{&low, &high} {
				spec.SharedOutput, spec.ReserveSlot, spec.PreservePrevious = true, true, true
				spec.Audio = AudioPlan{Index: 1, Codec: "aac", Channels: 2}
				spec.VOD.DurationMs, spec.VOD.FrameRate = duration*1000, tc.fps
				spec.VOD.AudioChannels = 2
				spec.VOD.Bandwidth = 2*spec.MaxBitrate + 256_000
			}
			m := NewManager(ffmpeg, t.TempDir(), detectedCaps(ffmpeg, "software"), time.Minute, 2, testLog)
			defer m.StopAll()
			first, err := m.StartVODLadder(ctx, low, high)
			var members []*Session
			if errors.Is(err, ErrLadderNotReady) {
				// Output correctness and admission are separate gates. A busy
				// or slower CI host must refuse the master; its controlled
				// single outputs still need complete metadata/decode checks.
				t.Logf("host retained single-rendition fallback: %v", err)
				m.StopAll()
				first, err = m.Start(ctx, low)
				if err != nil {
					t.Fatal(err)
				}
				second, err := m.Start(ctx, high)
				if err != nil {
					t.Fatal(err)
				}
				members = []*Session{first, second}
				if _, err := ladderOutputCodecs(ctx, members); err != nil {
					t.Fatal(err)
				}
			} else if err == nil {
				members = first.ladder.Load().members
			}
			if err != nil {
				t.Fatal(err)
			}
			for _, member := range members {
				init, _, err := member.VODFile(ctx, VODInit)
				if err != nil {
					t.Fatal(err)
				}
				joined := append([]byte(nil), init...)
				audioInit, _, err := first.VODFile(ctx, VODAudioInit)
				if err != nil {
					t.Fatal(err)
				}
				joinedAudio := append([]byte(nil), audioInit...)
				var peak, total int64
				for k := range member.Segments() {
					video, _, err := member.VODFile(ctx, fmt.Sprintf("v%05d.m4s", k))
					if err != nil {
						t.Fatal(err)
					}
					audio, _, err := first.VODFile(ctx, fmt.Sprintf("a%05d.m4s", k))
					if err != nil {
						t.Fatal(err)
					}
					joined = append(joined, video...)
					joinedAudio = append(joinedAudio, audio...)
					size := int64(len(video) + len(audio))
					total += size
					peak = max(peak, size*8_000_000/(member.vod.bounds[k+1]-member.vod.bounds[k]))
				}
				if peak > member.vod.opts.Bandwidth {
					t.Fatalf("%dp peak %d exceeds declared %d", member.Video.Height, peak, member.vod.opts.Bandwidth)
				}
				file := filepath.Join(t.TempDir(), "complete.mp4")
				if err := os.WriteFile(file, joined, 0o600); err != nil {
					t.Fatal(err)
				}
				if err := ffexec.Command(ctx, ffmpeg, ffexec.FFmpegArgs("-xerror", "-i", file, "-f", "null", "-")...).Run(); err != nil {
					t.Fatalf("complete %dp output did not decode: %v", member.Video.Height, err)
				}
				audioFile := filepath.Join(t.TempDir(), "complete-audio.mp4")
				if err := os.WriteFile(audioFile, joinedAudio, 0o600); err != nil {
					t.Fatal(err)
				}
				if err := ffexec.Command(ctx, ffmpeg, ffexec.FFmpegArgs("-xerror", "-i", audioFile, "-f", "null", "-")...).Run(); err != nil {
					t.Fatalf("complete AAC output did not decode: %v", err)
				}
				t.Logf("%s %dp: peak=%d average=%d declared=%d bps, all %d segments decoded",
					tc.name, member.Video.Height, peak, total*8/duration, member.vod.opts.Bandwidth, member.Segments())
			}
			m.StopAll()
			if m.Claimed() != 0 {
				t.Fatal("full-file qualification leaked capacity")
			}
		})
	}
}

func TestVODLadderRejectsUnboundedCopiedAudio(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	src := filepath.Join(t.TempDir(), "fictional-surround.mkv")
	args := ffexec.FFmpegArgs("-f", "lavfi", "-i", "testsrc2=size=320x180:rate=24:duration=12",
		"-f", "lavfi", "-i", "aevalsrc=0.1*random(1)|0.1*random(2)|0.1*random(3)|0.1*random(4)|0.1*random(5)|0.1*random(6)|0.1*random(7)|0.1*random(8):s=48000:c=7.1:d=12",
		"-c:v", "mpeg4", "-c:a", "aac", "-b:a", "1536k", src)
	if err := ffexec.Command(t.Context(), ffmpeg, args...).Run(); err != nil {
		t.Fatal(err)
	}
	low, high := ladderSpecs(src)
	for _, spec := range []*Spec{&low, &high} {
		spec.Audio = AudioPlan{Index: 1, Copy: true}
		spec.VOD.DurationMs, spec.VOD.AudioChannels = 12_000, 8
	}
	m := NewManager(ffmpeg, t.TempDir(), detectedCaps(ffmpeg, "software"), time.Minute, 2, testLog)
	defer m.StopAll()
	first, err := m.StartVODLadder(t.Context(), low, high)
	if err == nil {
		video, _, _ := first.VODFile(t.Context(), "v00000.m4s")
		audio, _, _ := first.VODFile(t.Context(), "a00000.m4s")
		t.Fatalf("unbounded copied audio was advertised: first segment=%d bps declared=%d", int64(len(video)+len(audio))*8/6, low.VOD.Bandwidth)
	}
	if m.Claimed() != 0 || len(m.Sessions()) != 0 {
		t.Fatal("rejected audio retained capacity")
	}
}
