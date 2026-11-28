package playback

import (
	"fmt"
	"strconv"
	"strings"

	"watchmymedia/server/internal/probe"
)

// What a converted track is encoded at.
const (
	// dolbyBitrate is E-AC-3's and AC-3's: a common rate for 5.1 Dolby
	// Digital Plus, and AC-3's ceiling (ffmpeg writes 640k when asked for more).
	dolbyBitrate = 640_000
	// aacChannelBitrate is AAC's per channel above stereo: 384 kbps for 5.1.
	aacChannelBitrate = 64_000
	// surroundFloor is the smallest video cap a play gets converted
	// surround at. A lower quality pick is for a slow link and keeps stereo.
	surroundFloor = 4_000_000
	// maxDolbyChannels: ffmpeg's E-AC-3 and AC-3 encoders stop at 5.1.
	maxDolbyChannels = 6
	// maxAACChannels: AAC is sent at 5.1 at most, as before.
	maxAACChannels = 6
	// maxFLACChannels: what the players' FLAC decoders take.
	maxFLACChannels = 8
)

// lossless says whether a is lossless: TrueHD, MLP, FLAC, ALAC, PCM, the
// other lossless codecs ffmpeg names, and DTS-HD Master Audio (ffprobe's
// "DTS-HD MA" profile, also "DTS-HD MA + DTS:X").
func lossless(a probe.AudioStream) bool {
	switch {
	case strings.HasPrefix(a.Codec, "pcm_"):
		return true
	case a.Codec == "dts":
		return strings.HasPrefix(a.Profile, "DTS-HD MA")
	}
	switch a.Codec {
	case "truehd", "mlp", "flac", "alac", "wavpack", "tta", "ape":
		return true
	}
	return false
}

// audioTarget is what a track that can't be copied is converted to,
// in this order:
//
//  1. FLAC, for a lossless source, when the video is copied, the play has
//     no rate cap and FLAC isn't ruled out (a download, or the person's
//     sound settings, which change the sound), and the result keeps every
//     channel or at least six of them: the source's own audio.
//  2. E-AC-3 (or AC-3 where only that is listed), for more than two
//     channels, when the play may spend the rate and the device takes six
//     channels as the format: what a receiver on HDMI or ARC takes as a
//     bitstream.
//  3. AAC, within the channels the output takes as PCM, stereo below the
//     surround floor.
//
// The plan keeps a's index. Its Bitrate is the encoder's rate, or for FLAC
// an estimate.
func audioTarget(a probe.AudioStream, p Profile, videoCopied, noFLAC bool) AudioPlan {
	n := a.Channels
	if n <= 0 {
		n = 2
	}
	pcmMax := p.MaxAudioChannels
	if p.MaxPcmChannels > 0 && (pcmMax == 0 || p.MaxPcmChannels < pcmMax) {
		pcmMax = p.MaxPcmChannels
	}
	within := func(limit, most int) int {
		c := min(n, most)
		if limit > 0 {
			c = min(c, limit)
		}
		return c
	}
	plan := AudioPlan{Index: a.Index}
	if lossless(a) && has(p.AudioCodecs, "flac") && videoCopied && p.MaxBitrate == 0 && !noFLAC {
		if c := within(pcmMax, maxFLACChannels); c == n || c >= 6 {
			plan.Codec, plan.Channels = "flac", c
			plan.Bitrate = flacBitrate(a, c)
			return plan
		}
	}
	roomy := p.MaxBitrate == 0 || p.MaxBitrate >= surroundFloor
	bitMax := p.MaxAudioChannels
	if n > 2 && roomy && (bitMax == 0 || bitMax >= 6) {
		for _, codec := range []string{"eac3", "ac3"} {
			if has(p.AudioCodecs, codec) {
				plan.Codec, plan.Channels, plan.Bitrate = codec, within(bitMax, maxDolbyChannels), dolbyBitrate
				return plan
			}
		}
	}
	plan.Codec, plan.Channels = "aac", within(pcmMax, maxAACChannels)
	if !roomy {
		plan.Channels = min(plan.Channels, 2)
	}
	plan.Bitrate = aacBitrate(plan.Channels)
	return plan
}

// AudioBitrate is about what the decision's audio needs: a converted
// track's encoder rate, else the copied track's own; 0 when unknown.
func (d Decision) AudioBitrate(info *probe.MediaInfo) int64 {
	if !d.Audio.Copy {
		return d.Audio.Bitrate
	}
	for _, a := range info.Audio {
		if a.Index == d.Audio.Index {
			return a.BitRate
		}
	}
	return 0
}

// aacBitrate is 192 kbps up to stereo, as before, and 64 kbps a channel above.
func aacBitrate(channels int) int64 {
	if channels <= 2 {
		return transcodeAudioBitrate
	}
	return int64(channels) * aacChannelBitrate
}

// flacBitrate estimates FLAC's rate: the source's when it is lossless at the
// same channels, else 60% of the PCM's (24-bit, 48 kHz unless the probe
// says otherwise), which FLAC typically reaches on film sound.
func flacBitrate(a probe.AudioStream, channels int) int64 {
	if a.BitRate > 0 && channels == a.Channels {
		return a.BitRate
	}
	rate := a.SampleRate
	if rate <= 0 {
		rate = 48_000
	}
	return int64(channels) * int64(rate) * 24 * 6 / 10
}

// audioReason says why a converted track was sent as it was, for the
// decision's reasons; empty for AAC, as before.
func audioReason(plan AudioPlan) string {
	switch plan.Codec {
	case "flac":
		return fmt.Sprintf("audio converted to FLAC %s, which keeps it lossless", channelLayout(plan.Channels))
	case "eac3", "ac3":
		return fmt.Sprintf("audio converted to %s %s, which keeps it surround", strings.ToUpper(plan.Codec), channelLayout(plan.Channels))
	}
	return ""
}

func channelLayout(channels int) string {
	switch channels {
	case 1:
		return "mono"
	case 2:
		return "stereo"
	case 6:
		return "5.1"
	case 8:
		return "7.1"
	}
	return strconv.Itoa(channels) + " channels"
}

// audioEncodeArgs are ffmpeg's arguments for a converted track: the
// effects' filters first, when the person has one on.
func audioEncodeArgs(plan AudioPlan) []string {
	var a []string
	if chain := plan.effectsFilter(); chain != "" {
		a = append(a, "-af", chain)
	}
	switch plan.Codec {
	case "flac":
		// Sample format and depth follow the source: TrueHD and DTS-HD MA
		// decode to 24-bit s32, which FLAC keeps, downmixed or not.
		a = append(a, "-c:a", "flac")
	case "eac3", "ac3":
		a = append(a, "-c:a", plan.Codec, "-b:a", strconv.FormatInt(dolbyBitrate/1000, 10)+"k")
	default:
		rate := plan.Bitrate
		if rate <= 0 {
			rate = transcodeAudioBitrate
		}
		a = append(a, "-c:a", "aac", "-b:a", strconv.FormatInt(rate/1000, 10)+"k")
	}
	if plan.Channels > 0 {
		a = append(a, "-ac", strconv.Itoa(plan.Channels))
	}
	return a
}
