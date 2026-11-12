package livetv

import (
	"fmt"
	"math"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"watchmymedia/server/internal/ffexec"
	"watchmymedia/server/internal/httpx"
	"watchmymedia/server/internal/playback"
)

// Claim the completed playlist before reading it. Windows cannot replace an
// open destination even with FILE_SHARE_DELETE. Only this private snapshot is
// opened; ffmpeg can always publish the next live.m3u8, and slow HTTP readers
// use the immutable bytes kept on the session instead of holding a file.
func takePlaylist(dir string) ([]byte, error) {
	return takeNamedPlaylist(dir, "live.m3u8")
}

func takeNamedPlaylist(dir, name string) ([]byte, error) {
	snapshot := filepath.Join(dir, name+".snapshot")
	if err := os.Rename(filepath.Join(dir, name), snapshot); err != nil {
		return nil, err
	}
	defer func() { _ = os.Remove(snapshot) }()
	f, err := os.Open(snapshot)
	if err != nil {
		return nil, err
	}
	defer func() { _ = f.Close() }()
	return httpx.ReadLimited(f, 64<<10)
}

const (
	SegmentSeconds   = 2
	WindowSegments   = 30
	RetainedSegments = 30
	// Give players six seconds of complete media before publishing readiness,
	// normally three of our two-second segments. This leaves room to start
	// behind the edge and replenish the buffer as new segments arrive.
	liveStartupSeconds = 3 * SegmentSeconds
	// DiskLimit includes the advertised minute and another minute for clients
	// holding an older playlist, plus the segment being written.
	DiskLimit int64 = 160 << 20
)

func liveStartupReady(playlist []byte) bool {
	var buffered float64
	pending := -1.0
	for line := range strings.SplitSeq(string(playlist), "\n") {
		line = strings.TrimSpace(line)
		if value, ok := strings.CutPrefix(line, "#EXTINF:"); ok {
			if pending >= 0 {
				return false
			}
			value, _, _ = strings.Cut(value, ",")
			seconds, err := strconv.ParseFloat(value, 64)
			if err != nil || math.IsNaN(seconds) || math.IsInf(seconds, 0) || seconds <= 0 {
				return false
			}
			pending = seconds
		} else if line != "" && !strings.HasPrefix(line, "#") {
			if pending < 0 {
				return false
			}
			buffered += pending
			pending = -1
		}
	}
	return pending < 0 && buffered >= liveStartupSeconds
}

// FFmpeg's target duration describes the actual completed output segments,
// including longer copied GOPs. Zero leaves recovery on its conservative
// fallback instead of guessing from the nominal two-second encoder target.
func liveTargetDurationMs(playlist []byte) int64 {
	for line := range strings.SplitSeq(string(playlist), "\n") {
		if value, ok := strings.CutPrefix(strings.TrimSpace(line), "#EXT-X-TARGETDURATION:"); ok {
			seconds, err := strconv.ParseInt(value, 10, 64)
			if err != nil || seconds <= 0 || seconds > 60 {
				return 0
			}
			return seconds * 1000
		}
	}
	return 0
}

// HLSArgs normalizes a single MPEG-TS input to progressive H.264/AAC. Never
// pass a tuner URL to ffmpeg: the HTTP adapter owns the one upstream socket.
// Explicit first audio mapping avoids choosing a secondary surround track.
func HLSArgs(dir, encoder string, height, kbps int) []string {
	return HLSArgsOnDevice(dir, encoder, height, kbps, nil)
}

// HLSArgsOnDevice keeps Live TV's CPU deinterlacing on its selected encoder.
func HLSArgsOnDevice(dir, encoder string, height, kbps int, device *int) []string {
	return hlsVideoArgs(dir, encoder, height, kbps, device, "mpegts", "")
}

func hlsVideoArgs(dir, encoder string, height, kbps int, device *int, format, hdrType string) []string {
	// Use display aspect (including MPEG-2 sample aspect), then square the
	// pixels. Capping stored width and discarding SAR would squeeze 480i.
	// Captions need their own supported output path. Leaving A53 side data
	// attached can also fail VideoToolbox's SEI insertion on real broadcasts.
	filter := fmt.Sprintf("sidedata=mode=delete:type=A53_CC,%s,scale=w='max(2,trunc(oh*dar/2)*2)':h='max(2,trunc(min(min(ih,%d),%d/dar)/2)*2)',setsar=1", playback.BroadcastDeinterlace, height, height*16/9)
	if hdrType != "" {
		filter += "," + strings.Join(playback.ToneMapFilters(), ",")
	}
	input, output := playback.EncoderDeviceArgs(encoder, device)
	upload := playback.EncoderUploadFilter(encoder, device, false)
	if upload != "" {
		filter += "," + upload
	}
	a := ffexec.FFmpegArgs(input...)
	a = append(a, "-y", "-fflags", "+genpts+discardcorrupt", "-probesize", "5000000", "-analyzeduration", "3000000", "-f", "mpegts", "-i", "pipe:0",
		"-map", "0:v:0", "-map", "0:a:0?", "-sn", "-dn", "-vf", filter, "-c:v", encoder)
	// Uploaded QSV/D3D11 frames keep their hardware pixel format. Forcing
	// yuv420p here would make ffmpeg insert a CPU conversion after hwupload.
	if upload == "" {
		a = append(a, "-pix_fmt", "yuv420p")
	}
	a = append(a, output...)
	if encoder == "libx264" {
		a = append(a, "-sc_threshold", "0")
	}
	a = append(a, playback.H264RateArgs(encoder, int64(kbps)*1000)...)

	// bwdif advertises field rate even when it passes progressive frames
	// through. Preserve their timestamps instead of duplicating 60p to 120p.
	a = append(a,
		"-fps_mode", "vfr",
		"-force_key_frames", "expr:gte(t,n_forced*2)", "-c:a", "aac", "-ac", "2", "-b:a", "192k", "-af", "aresample=async=1:first_pts=0")
	return appendHLSOutput(a, dir, format)
}

// HLSArgsForPlan keeps the same authorized rolling playlist in every mode.
// Copy and remux plans never decode video; audio mode converts only audio.
func HLSArgsForPlan(dir string, plan LivePlan, encoder string, height, kbps int, device *int) []string {
	if plan.Mode == "video" {
		return hlsVideoArgs(dir, encoder, height, kbps, device, plan.SegmentFormat, plan.hdrType)
	}
	a := ffexec.FFmpegArgs()
	a = append(a, "-y", "-fflags", "+genpts+discardcorrupt", "-probesize", "5000000", "-analyzeduration", "3000000", "-f", "mpegts", "-i", "pipe:0",
		"-map", "0:v:0", "-map", "0:a:0?", "-sn", "-dn", "-c:v", "copy")
	if plan.Mode == "audio" {
		a = append(a, "-c:a", "aac", "-ac", "2", "-b:a", "192k", "-af", "aresample=async=1:first_pts=0")
	} else {
		a = append(a, "-c:a", "copy")
		if plan.SegmentFormat == "fmp4" && plan.sourceAudio == "aac" {
			// MPEG-TS carries AAC as ADTS; MP4 needs an AudioSpecificConfig.
			a = append(a, "-bsf:a", "aac_adtstoasc")
		}
	}
	return appendHLSOutput(a, dir, plan.SegmentFormat)
}

func appendHLSOutput(a []string, dir, format string) []string {
	// FFmpeg's HLS muxer finds the init segment's directory using '/',
	// even on Windows. Native backslashes put init.mp4 in the server's
	// working directory instead of beside the authorized playlist.
	a = append(a, "-f", "hls", "-hls_time", "2", "-hls_list_size", "30", "-hls_delete_threshold", "30",
		"-hls_flags", "delete_segments+temp_file+independent_segments+omit_endlist")
	if format == "fmp4" {
		a = append(a, "-hls_segment_type", "fmp4", "-hls_fmp4_init_filename", "init.mp4",
			"-hls_segment_filename", filepath.ToSlash(filepath.Join(dir, "segment-%09d.m4s")))
	} else {
		a = append(a, "-hls_segment_filename", filepath.ToSlash(filepath.Join(dir, "segment-%09d.ts")))
	}
	return append(a, filepath.ToSlash(filepath.Join(dir, "live.m3u8")))
}
