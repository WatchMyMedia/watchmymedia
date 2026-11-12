package livetv

import (
	"context"
	"errors"
	"fmt"
	"maps"
	"math"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"time"

	"watchmymedia/server/internal/ffexec"
	"watchmymedia/server/internal/playback"
)

// StartLiveLadder is a private qualification path, not production negotiation.
// It prepares two software H.264 rungs and one continuous shared AAC track.
func (m *Sessions) StartLiveLadder(ctx context.Context, owner Owner, channel, quality, request, client string, profile *LiveProfile) (SessionView, error) {
	if profile == nil || !slices.Contains(profile.SegmentFormats, "fmp4") || !slices.Contains(profile.VideoCodecs, "h264") || !slices.Contains(profile.AudioCodecs, "aac") || profile.MaxAudioChannels == 1 {
		return SessionView{}, ErrProfile
	}
	return m.startPrivateOutput(ctx, owner, channel, quality, request, client, profile, "ladder")
}

type liveRung struct {
	name          string
	width, height int
	bitrate       int64
}

func (f *liveFamily) offer() *playback.AdaptiveStream {
	a := &playback.AdaptiveStream{}
	for _, r := range f.rungs {
		a.Renditions = append(a.Renditions, playback.AdaptiveRendition{Width: r.width, Height: r.height, Bitrate: playback.PeakBandwidth(r.bitrate)})
	}
	return a
}

// StartAdaptive retains Automatic's logical session and source anchor. Only
// converted video is eligible; compatible broadcast copying remains cheaper.
func (m *Sessions) StartAdaptive(ctx context.Context, owner Owner, channel, request, client string, profile *LiveProfile, initialBps int64, remote bool) (SessionView, error) {
	if profile == nil || validateLiveProfile(profile) != nil || !m.SupportsAutomatic() {
		return SessionView{}, ErrProfile
	}
	return m.startShared(ctx, owner, channel, "auto", request, client, profile, true, "", initialBps, remote, "adaptive")
}

type liveFamily struct {
	next      map[string][]byte
	updated   map[string]time.Time
	rungs     [2]liveRung
	pending   map[string][]byte
	playlists map[string][]byte // only validated immutable snapshots; Sessions.mu
	init      [2][]byte
	master    []byte
	last      int
	advanced  bool
}

func newLiveFamily(source SourceInfo, profile *LiveProfile, quality string) (*liveFamily, error) {
	// The private envelope deliberately excludes HDR, non-square pixels and
	// unknown source facts. Existing single-rendition mode remains the fallback.
	if !source.SquarePixels || !source.ScanKnown || source.HDRType != "" || source.Width < 16 || source.Height < 16 || source.Width > 7680 || source.Height > 4320 || !source.AudioPresent || source.AudioChannels <= 0 {
		return nil, ErrSourceUnknown
	}
	step := playback.StepByID(quality)
	height := min(source.Height, step.MaxHeight, 720, 1280*source.Height/source.Width)
	if profile.MaxHeight > 0 {
		height = min(height, profile.MaxHeight)
	}
	height = height / 2 * 2
	ceiling := step.MaxBitrate
	if profile.MaxBitrate > 0 {
		ceiling = min(ceiling, profile.MaxBitrate)
	}
	// Include VBV burst headroom and the common audio in the advertised cap.
	// A native player must not discover that every high rung exceeds its cap.
	rate := min(int64(3_000_000), playback.VideoRateWithin(ceiling))
	if height < 32 || rate < 300_000 {
		return nil, ErrProfile
	}
	if height <= 240 {
		rate = min(rate, int64(1_000_000))
	}
	width := func(h int) int { return max(2, int(float64(h)*float64(source.Width)/float64(source.Height))/2*2) }
	low := height / 4 * 2
	return &liveFamily{rungs: [2]liveRung{{"low", width(low), low, rate / 3}, {"high", width(height), height, rate}}, updated: map[string]time.Time{}, pending: map[string][]byte{}, playlists: map[string][]byte{}, last: -1}, nil
}

func (f *liveFamily) args(dir string) []string {
	// A joined broadcast can begin between video keyframes. Pad that initial
	// video gap on the shared input clock, as common audio already does, instead
	// of giving video and audio permanently different segment boundaries.
	// This family publishes 30 fps. Field-rate deinterlacing would compute 60
	// progressive frames only for fps to discard half, competing with other
	// live outputs. Keep frame-rate deinterlacing and bounded decode/filter work.
	filter := fmt.Sprintf("[0:v:0]sidedata=mode=delete:type=A53_CC,bwdif=mode=send_frame:parity=auto:deint=interlaced,fps=30:start_time=0,split=2[lo][hi];[lo]scale=%d:%d,setsar=1[low];[hi]scale=%d:%d,setsar=1[high]", f.rungs[0].width, f.rungs[0].height, f.rungs[1].width, f.rungs[1].height)
	a := ffexec.FFmpegArgs("-y", "-fflags", "+genpts+discardcorrupt", "-probesize", "5000000", "-analyzeduration", "3000000", "-drop_changed", "1", "-threads:v", "1", "-f", "mpegts", "-i", "pipe:0", "-filter_complex_threads", "1", "-filter_complex", filter,
		"-map", "[low]", "-map", "[high]", "-map", "0:a:0", "-sn", "-dn", "-c:v", "libx264", "-preset", "veryfast", "-threads:v", "1", "-pix_fmt", "yuv420p", "-sc_threshold", "0", "-g", "60", "-keyint_min", "60", "-flags:v", "+cgop", "-fps_mode:v", "passthrough")
	for i, rung := range f.rungs {
		n := strconv.Itoa(i)
		a = append(a, "-b:v:"+n, strconv.FormatInt(rung.bitrate, 10), "-maxrate:v:"+n, strconv.FormatInt(rung.bitrate, 10), "-bufsize:v:"+n, strconv.FormatInt(2*rung.bitrate, 10), "-force_key_frames:v:"+n, "expr:gte(t,n_forced*2)")
	}
	a = append(a, "-c:a", "aac", "-ac", "2", "-ar", "48000", "-b:a", "192k", "-af", "aresample=async=1:first_pts=0",
		"-f", "hls", "-hls_time", "2", "-hls_list_size", "30", "-hls_delete_threshold", "30", "-hls_flags", "delete_segments+temp_file+independent_segments+omit_endlist",
		"-hls_segment_type", "fmp4", "-hls_fmp4_init_filename", "%v-init.mp4", "-var_stream_map", "v:0,agroup:audio,name:low v:1,agroup:audio,name:high a:0,agroup:audio,default:yes,name:audio",
		"-hls_segment_filename", filepath.ToSlash(filepath.Join(dir, "%v-segment-%09d.m4s")), filepath.ToSlash(filepath.Join(dir, "%v-live.m3u8")))
	return a
}

func liveFamilyName(name string) bool {
	for _, prefix := range []string{"low-", "high-", "audio-"} {
		if rest, ok := strings.CutPrefix(name, prefix); ok {
			return rest == "live.m3u8" || rest == "init.mp4" || strings.HasSuffix(rest, ".m4s") && mediaName.MatchString(rest)
		}
	}
	return false
}

type liveWindow struct {
	first     int
	names     []string
	durations []float64
}

func familyWindow(b []byte, prefix string) (liveWindow, error) {
	var w liveWindow
	w.first = -1
	duration := 0.0
	for line := range strings.SplitSeq(string(b), "\n") {
		line = strings.TrimSpace(line)
		if value, ok := strings.CutPrefix(line, "#EXT-X-MEDIA-SEQUENCE:"); ok {
			n, err := strconv.Atoi(value)
			if err != nil || n < 0 {
				return w, ErrSourceUnknown
			}
			w.first = n
		}
		if value, ok := strings.CutPrefix(line, "#EXTINF:"); ok {
			value, _, _ = strings.Cut(value, ",")
			d, err := strconv.ParseFloat(value, 64)
			if err != nil || math.IsNaN(d) || math.IsInf(d, 0) || d <= 0 || d > 4 || duration != 0 {
				return w, ErrSourceUnknown
			}
			duration = d
		}
		if line != "" && !strings.HasPrefix(line, "#") {
			expected := fmt.Sprintf("%s-segment-%09d.m4s", prefix, w.first+len(w.names))
			if w.first < 0 || duration == 0 || line != expected || len(w.names) >= WindowSegments {
				return w, ErrSourceUnknown
			}
			w.names = append(w.names, line)
			w.durations = append(w.durations, duration)
			duration = 0
		}
	}
	if duration != 0 || w.first < 0 {
		return w, ErrSourceUnknown
	}
	return w, nil
}

func readFamilyFile(dir, name string, limit int64) ([]byte, error) {
	f, err := playback.OpenShared(filepath.Join(dir, name))
	if err != nil {
		return nil, err
	}
	defer func() { _ = f.Close() }()
	info, err := f.Stat()
	if err != nil {
		return nil, err
	}
	if info.Size() <= 0 || info.Size() > limit {
		return nil, ErrSourceUnknown
	}
	b := make([]byte, info.Size())
	_, err = f.ReadAt(b, 0)
	return b, err
}

func (f *liveFamily) update(dir string) ([]byte, error) {
	f.advanced = false
	for _, name := range []string{"low", "high", "audio"} {
		playlist := name + "-live.m3u8"
		b, err := takeNamedPlaylist(dir, playlist)
		if err == nil {
			if string(f.pending[playlist]) != string(b) {
				f.updated[playlist] = time.Now()
			}
			f.pending[playlist] = b
		} else if !errors.Is(err, os.ErrNotExist) {
			return nil, err
		}
	}
	if f.master != nil {
		for _, at := range f.updated {
			if time.Since(at) > 3*SegmentSeconds*time.Second {
				return nil, errors.New("live family member stopped advancing")
			}
		}
	}
	var windows [3]liveWindow
	for i, name := range []string{"low", "high", "audio"} {
		b := f.pending[name+"-live.m3u8"]
		if !liveStartupReady(b) {
			return nil, nil
		}
		w, err := familyWindow(b, name)
		if err != nil {
			return nil, err
		}
		windows[i] = w
	}
	low, high := windows[0], windows[1]
	// Publish one common video window atomically. If a muxer is mid-publication,
	// keep the prior immutable pair instead of advertising a missing segment.
	if low.first != high.first || len(low.names) != len(high.names) {
		return nil, nil
	}
	for i, d := range low.durations {
		if math.Abs(d-high.durations[i]) > 0.000001 {
			return nil, fmt.Errorf("live rung durations differ at %d: %f vs %f", i, d, high.durations[i])
		}
	}
	last := low.first + len(low.names) - 1
	if last <= f.last {
		return f.master, nil
	}
	if f.master == nil {
		audio, err := readFamilyFile(dir, "audio-init.mp4", 1<<20)
		if err != nil {
			return nil, err
		}
		if err := playback.HLSAudioInit(audio, 2); err != nil {
			return nil, err
		}
		var master strings.Builder
		master.WriteString("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Main\",DEFAULT=YES,AUTOSELECT=YES,CHANNELS=\"2\",URI=\"audio-live.m3u8\"\n")
		for i, rung := range f.rungs {
			init, err := readFamilyFile(dir, rung.name+"-init.mp4", 1<<20)
			if err != nil {
				return nil, err
			}
			codec, err := playback.HLSVideoInit(init, rung.width, rung.height)
			if err != nil {
				return nil, err
			}
			f.init[i] = init
			fmt.Fprintf(&master, "#EXT-X-STREAM-INF:BANDWIDTH=%d,RESOLUTION=%dx%d,FRAME-RATE=30.000,CODECS=\"%s,mp4a.40.2\",AUDIO=\"audio\",VIDEO-RANGE=SDR\n%s-live.m3u8\n", playback.PeakBandwidth(rung.bitrate), rung.width, rung.height, codec, rung.name)
		}
		f.master = []byte(master.String())
	}
	for index := max(low.first, f.last+1); index <= last; index++ {
		var boundary int64
		for i, rung := range f.rungs {
			media, err := readFamilyFile(dir, fmt.Sprintf("%s-segment-%09d.m4s", rung.name, index), 8<<20)
			if err != nil {
				return nil, err
			}
			at, err := playback.HLSVideoBoundary(f.init[i], media)
			if err != nil {
				return nil, err
			}
			if i == 0 {
				boundary = at
			} else if at != boundary {
				return nil, fmt.Errorf("live rung boundaries differ at %d: %d vs %d", index, at, boundary)
			}
		}
	}
	f.next = maps.Clone(f.pending)
	f.last, f.advanced = last, true
	return f.master, nil
}
