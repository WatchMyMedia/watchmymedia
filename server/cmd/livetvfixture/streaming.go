package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/bgwork"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/ffexec"
	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/probe"
	"watchmymedia/server/internal/settings"
	"watchmymedia/server/internal/streamtest"
)

const streamingMarker = "streaming-fixture-v1"

type streamingFile struct {
	ID         int64  `json:"id"`
	Kind       string `json:"kind"`
	Title      string `json:"title"`
	DurationMs int64  `json:"durationMs"`
	Bitrate    int64  `json:"bitrate"`
	Video      string `json:"video"`
	Audio      string `json:"audio"`
	Width      int    `json:"width"`
	Height     int    `json:"height"`
}

type streamingManifest struct {
	Fixture    string          `json:"fixture"`
	BaseURL    string          `json:"baseUrl"`
	ServerID   string          `json:"serverId"`
	ControlKey string          `json:"controlKey"`
	PID        int             `json:"pid"`
	Files      []streamingFile `json:"files"`
	Accounts   []string        `json:"accounts"`
	FFmpeg     string          `json:"ffmpegVersion"`
	FFprobe    string          `json:"ffprobeVersion"`
	Notes      []string        `json:"qualifications"`
}

type streamingFixture struct {
	manifest streamingManifest
	shaper   *streamtest.Shaper
	ffmpeg   string
	decoders chan struct{}
}

// streamingTitles says which generated titles a streaming fixture has.
type streamingTitles struct {
	seconds    int  // length of the generated titles, except originalHD's
	extended   bool // MPEG-4 adaptive and HEVC Main 10 HDR clips
	originalHD bool // a long direct-playable 720p H.264/AAC MP4
}

// originalHDSeconds outlasts Automatic's ten-minute wait before it retries a
// step that failed, so a play can step down from Original and come back.
const originalHDSeconds = 960

func seedStreaming(ctx context.Context, q *db.Queries, store *settings.Store, accounts *auth.Service, prober *probe.Prober, ffmpeg, dir string, titles streamingTitles) (*streamingFixture, error) {
	seconds := titles.seconds
	s := &streamingFixture{shaper: streamtest.NewShaper(), ffmpeg: ffmpeg, decoders: make(chan struct{}, 1)}
	key := make([]byte, 24)
	if _, err := rand.Read(key); err != nil {
		return nil, err
	}
	id, err := store.ServerID(ctx)
	if err != nil {
		return nil, err
	}
	s.manifest = streamingManifest{Fixture: streamingMarker, ServerID: id, ControlKey: hex.EncodeToString(key), PID: os.Getpid(),
		Notes: []string{"Generated 320x180 media with an artificial 8 Mbps video rate; concurrency correctness, not a 4K capacity claim.", "HTTP model observations are not decoded-frame, native-player, HDR-display or physical-tuner evidence."}}
	version, err := ffexec.Command(ctx, ffmpeg, "-version").Output()
	if err != nil {
		return nil, err
	}
	s.manifest.FFmpeg, _, _ = strings.Cut(string(version), "\n")
	probePath := prober.Path
	if probePath == "" {
		probePath = "ffprobe"
	}
	version, err = ffexec.Command(ctx, probePath, "-version").Output()
	if err != nil {
		return nil, err
	}
	s.manifest.FFprobe, _, _ = strings.Cut(string(version), "\n")
	for i := 1; i <= 17; i++ {
		name := fmt.Sprintf("Stream Viewer %d", i)
		if _, err := accounts.CreateUser(ctx, name, "fixture-password", false); err != nil {
			return nil, err
		}
		s.manifest.Accounts = append(s.manifest.Accounts, name)
	}
	mediaDir := filepath.Join(dir, "Movies")
	if err := os.MkdirAll(mediaDir, 0o700); err != nil {
		return nil, err
	}
	library, err := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "movie", Name: "Streaming fixture films", Path: mediaDir})
	if err != nil {
		return nil, err
	}
	path := filepath.Join(mediaDir, "Cedar Lanterns.mp4")
	args := ffexec.FFmpegArgs("-f", "lavfi", "-i", "testsrc2=size=320x180:rate=24", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
		"-t", fmt.Sprint(seconds), "-c:v", "libx264", "-preset", "ultrafast", "-threads", "1", "-pix_fmt", "yuv420p", "-g", "48",
		"-b:v", "8M", "-minrate", "8M", "-maxrate", "8M", "-bufsize", "16M", "-x264-params", "nal-hrd=cbr:force-cfr=1", "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", path)
	if err := generateStreaming(ctx, ffmpeg, args, path); err != nil {
		return nil, err
	}
	variants := []struct{ title, kind, path string }{{"Cedar Lanterns", "baseline", path}}
	alternate := filepath.Join(mediaDir, "Riverglass.mp4")
	if err := os.Link(path, alternate); err != nil {
		return nil, err
	}
	variants = append(variants, struct{ title, kind, path string }{"Riverglass", "alternate", alternate})
	eac3 := filepath.Join(mediaDir, "Willow Crossing.mkv")
	if err := generateStreaming(ctx, ffmpeg, ffexec.FFmpegArgs("-i", path, "-map", "0:v:0", "-map", "0:a:0", "-c:v", "copy", "-c:a", "eac3", "-b:a", "192k", eac3), eac3); err != nil {
		return nil, err
	}
	variants = append(variants, struct{ title, kind, path string }{"Willow Crossing", "eac3", eac3})
	if titles.originalHD {
		// Direct-playable for web and Cast profiles and large enough for a
		// two-rung family once Automatic steps down from it.
		hd := filepath.Join(mediaDir, "Harbor Lights.mp4")
		args := ffexec.FFmpegArgs("-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30", "-f", "lavfi", "-i", "sine=frequency=330:sample_rate=48000",
			"-t", fmt.Sprint(originalHDSeconds), "-c:v", "libx264", "-preset", "ultrafast", "-threads", "4", "-pix_fmt", "yuv420p", "-g", "60",
			"-b:v", "3M", "-maxrate", "3M", "-bufsize", "6M", "-c:a", "aac", "-b:a", "128k", "-ac", "2", "-movflags", "+faststart", hd)
		if err := generateStreaming(ctx, ffmpeg, args, hd); err != nil {
			return nil, err
		}
		variants = append(variants, struct{ title, kind, path string }{"Harbor Lights", "original-hd", hd})
	}
	if titles.extended {
		adaptive := filepath.Join(mediaDir, "Cedar Heights.mkv")
		if err := generateStreaming(ctx, ffmpeg, ffexec.FFmpegArgs("-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", fmt.Sprint(seconds), "-c:v", "mpeg4", "-threads", "1", "-b:v", "8M", "-c:a", "aac", "-b:a", "192k", adaptive), adaptive); err != nil {
			return nil, err
		}
		variants = append(variants, struct{ title, kind, path string }{"Cedar Heights", "adaptive", adaptive})
		hdr := filepath.Join(mediaDir, "Meadow Skies.mkv")
		args := ffexec.FFmpegArgs("-f", "lavfi", "-i", "testsrc2=size=320x180:rate=24", "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=48000", "-t", "12",
			"-c:v", "libx265", "-threads", "1", "-pix_fmt", "yuv420p10le", "-preset", "ultrafast", "-x265-params", "pools=1:frame-threads=1:log-level=error:hdr10=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc",
			"-c:a", "eac3", "-b:a", "192k", hdr)
		if err := generateStreaming(ctx, ffmpeg, args, hdr); err != nil {
			return nil, fmt.Errorf("requested HEVC fixture unavailable: %w", err)
		}
		variants = append(variants, struct{ title, kind, path string }{"Meadow Skies", "hevc-hdr", hdr})
	} else {
		s.manifest.Notes = append(s.manifest.Notes, "HEVC Main 10 HDR not generated; use -streaming-extended. Image subtitle/burn-in and native playback remain separate coverage.")
	}
	for index, variant := range variants {
		info, err := prober.Probe(ctx, variant.path)
		if err != nil {
			return nil, err
		}
		if len(info.Video) != 1 || len(info.Audio) != 1 {
			return nil, fmt.Errorf("streaming fixture needs one video and one audio stream")
		}
		encoded, err := probe.Encode(info)
		if err != nil {
			return nil, err
		}
		stat, err := os.Stat(variant.path)
		if err != nil {
			return nil, err
		}
		movie, err := q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: library.ID, TmdbID: 920001 + int64(index), Title: variant.title, Genres: "[]", Credits: "[]", Trailers: "[]", Overview: "A generated fictional film for streaming tests."})
		if err != nil {
			return nil, err
		}
		stored, now := string(encoded), db.FormatTime(time.Now())
		file, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: library.ID, Path: variant.path, Size: stat.Size(), Mtime: db.FormatTime(stat.ModTime()), AddedAt: now, Parsed: "{}", Probe: &stored, DurationMs: &info.DurationMs, LastSeenAt: now, SeenInScan: 1})
		if err != nil {
			return nil, err
		}
		if err := q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{ID: file.ID, MovieID: &movie.ID, MatchStatus: "matched", MatchLocked: 1}); err != nil {
			return nil, err
		}
		s.manifest.Files = append(s.manifest.Files, streamingFile{ID: file.ID, Kind: variant.kind, Title: variant.title, DurationMs: info.DurationMs, Bitrate: info.BitRate, Video: info.Video[0].Codec, Audio: info.Audio[0].Codec, Width: info.Video[0].Width, Height: info.Video[0].Height})
	}
	if err := os.WriteFile(filepath.Join(mediaDir, "Cedar Lanterns.en.srt"), []byte("1\n00:00:00,000 --> 00:00:09,000\nFictional streaming subtitle\n"), 0o600); err != nil {
		return nil, err
	}
	return s, nil
}

func generateStreaming(ctx context.Context, ffmpeg string, args []string, path string) error {
	bounded, cancel := context.WithTimeout(ctx, 2*time.Minute)
	defer cancel()
	if err := ffexec.Command(bounded, ffmpeg, args...).Run(); err != nil {
		return fmt.Errorf("generate fictional media: %w", err)
	}
	// Decode generated source frames rather than trusting a declared codec.
	return ffexec.Command(bounded, ffmpeg, ffexec.FFmpegArgs("-i", path, "-frames:v", "3", "-f", "null", "-")...).Run()
}

func (s *streamingFixture) register(router chi.Router, manager *playback.Manager, tuner *hdhrtest.Tuner, tracker *activity.Tracker, q *db.Queries, dir string) {
	write := func(w http.ResponseWriter, v any) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		_ = json.NewEncoder(w).Encode(v)
	}
	authorized := func(w http.ResponseWriter, r *http.Request) bool {
		if subtle.ConstantTimeCompare([]byte(r.Header.Get("X-WMM-Fixture-Key")), []byte(s.manifest.ControlKey)) != 1 {
			http.Error(w, "fixture control key required", http.StatusForbidden)
			return false
		}
		return true
	}
	router.Get("/_fixture/status", func(w http.ResponseWriter, _ *http.Request) { write(w, s.manifest) })
	// This route exists only on the loopback fictional fixture. Ordinary API
	// playback still offers one rendition until native cold-switch gates pass.
	router.Post("/_fixture/streaming/ladder", func(w http.ResponseWriter, r *http.Request) {
		if !authorized(w, r) {
			return
		}
		var in struct {
			Viewer string `json:"viewer"`
		}
		decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 2048))
		decoder.DisallowUnknownFields()
		if decoder.Decode(&in) != nil || in.Viewer == "" || decoder.Decode(&struct{}{}) != io.EOF {
			http.Error(w, "invalid ladder viewer", http.StatusBadRequest)
			return
		}
		user, err := q.GetUserByName(r.Context(), in.Viewer)
		if err != nil {
			http.Error(w, "fixture viewer not found", http.StatusNotFound)
			return
		}
		var file streamingFile
		for _, candidate := range s.manifest.Files {
			if candidate.Kind == "baseline" {
				file = candidate
				break
			}
		}
		if file.ID == 0 {
			http.Error(w, "fixture baseline not found", http.StatusNotFound)
			return
		}
		row, err := q.GetMediaFile(r.Context(), file.ID)
		if err != nil {
			http.Error(w, "fixture file not found", http.StatusNotFound)
			return
		}
		playID := fmt.Sprintf("fixture-ladder-%d", time.Now().UnixNano())
		low := playback.Spec{FileID: file.ID, PlayID: playID, UserID: user.ID, Path: row.Path, Mode: playback.ModeTranscode, BurnIn: -1,
			Video: playback.VideoPlan{Codec: "h264", Encoder: "libx264", Width: 160, Height: 90},
			Audio: playback.AudioPlan{Index: 1, Codec: "aac", Channels: 1}, MaxBitrate: 400_000,
			VOD: &playback.VODOptions{DurationMs: file.DurationMs, FrameRate: 24, Bandwidth: 1_100_000,
				VideoRange: "SDR", Codecs: "avc1.64000d,mp4a.40.2", SeparateAudio: true, AudioChannels: 1}}
		high := low
		high.Video.Width, high.Video.Height = 320, 180
		high.MaxBitrate = 1_000_000
		high.VOD = &playback.VODOptions{DurationMs: file.DurationMs, FrameRate: 24, Bandwidth: 2_300_000,
			VideoRange: "SDR", Codecs: "avc1.64000d,mp4a.40.2", SeparateAudio: true, AudioChannels: 1}
		prepared, err := manager.StartVODLadder(r.Context(), low, high)
		if err != nil {
			if errors.Is(err, playback.ErrLadderNotReady) {
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusServiceUnavailable)
				_, _ = w.Write([]byte(`{"detail":"ladder_not_ready"}`))
				return
			}
			http.Error(w, err.Error(), http.StatusServiceUnavailable)
			return
		}
		write(w, map[string]any{"playId": playID, "url": "/api/playback/" + prepared.ID + "/" + playback.VODMaster})
	})
	router.Post("/_fixture/streaming/ladder/stop", func(w http.ResponseWriter, r *http.Request) {
		if !authorized(w, r) {
			return
		}
		var in struct {
			PlayID string `json:"playId"`
			Viewer string `json:"viewer"`
		}
		decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 2048))
		decoder.DisallowUnknownFields()
		if decoder.Decode(&in) != nil || !strings.HasPrefix(in.PlayID, "fixture-ladder-") || in.Viewer == "" || decoder.Decode(&struct{}{}) != io.EOF {
			http.Error(w, "invalid ladder stop", http.StatusBadRequest)
			return
		}
		user, err := q.GetUserByName(r.Context(), in.Viewer)
		if err != nil {
			http.Error(w, "fixture viewer not found", http.StatusNotFound)
			return
		}
		manager.StopPlay(in.PlayID, user.ID)
		write(w, map[string]any{"stopped": true})
	})
	router.Post("/_fixture/streaming/decode", func(w http.ResponseWriter, r *http.Request) {
		if !authorized(w, r) {
			return
		}
		_ = http.NewResponseController(w).SetReadDeadline(time.Now().Add(15 * time.Second))
		defer func() { _ = http.NewResponseController(w).SetReadDeadline(time.Time{}) }()
		select {
		case s.decoders <- struct{}{}:
			defer func() { <-s.decoders }()
		default:
			http.Error(w, "fixture decoder busy", http.StatusTooManyRequests)
			return
		}
		var in struct {
			Media []byte `json:"media"`
		}
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 24<<20)).Decode(&in); err != nil || len(in.Media) == 0 || len(in.Media) > 16<<20 {
			http.Error(w, "invalid bounded media sample", http.StatusBadRequest)
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
		defer cancel()
		cmd := ffexec.Command(ctx, s.ffmpeg, ffexec.FFmpegArgs("-protocol_whitelist", "pipe", "-i", "pipe:0", "-frames:v", "3", "-progress", "pipe:1", "-f", "null", "-")...)
		cmd.Stdin = bytes.NewReader(in.Media)
		out, err := cmd.Output()
		frames := 0
		for line := range strings.SplitSeq(string(out), "\n") {
			if value, ok := strings.CutPrefix(line, "frame="); ok {
				n, _ := strconv.Atoi(strings.TrimSpace(value))
				frames = max(frames, n)
			}
		}
		if err != nil || frames < 3 {
			http.Error(w, "received media sample did not decode three video frames", http.StatusUnprocessableEntity)
			return
		}
		write(w, map[string]int{"decodedSampleFrames": frames})
	})
	router.Post("/_fixture/streaming/profile", func(w http.ResponseWriter, r *http.Request) {
		if !authorized(w, r) {
			return
		}
		var p streamtest.Profile
		decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192))
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&p); err != nil {
			http.Error(w, "invalid bandwidth profile", http.StatusBadRequest)
			return
		}
		if err := decoder.Decode(&struct{}{}); err != io.EOF {
			http.Error(w, "invalid trailing bandwidth profile", http.StatusBadRequest)
			return
		}
		if err := s.shaper.Update(p); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		write(w, s.shaper.Snapshot())
	})
	router.Get("/_fixture/streaming/metrics", func(w http.ResponseWriter, r *http.Request) {
		if !authorized(w, r) {
			return
		}
		active, peak, opens := tuner.Counts()
		var memory runtime.MemStats
		runtime.ReadMemStats(&memory)
		type session struct {
			Mode        playback.Mode        `json:"mode"`
			PositionMs  *int64               `json:"ffmpegPositionMs"`
			Speed       float64              `json:"speed"`
			Preparation playback.Preparation `json:"preparation"`
		}
		sessions := []session{}
		for _, v := range manager.Sessions() {
			speed, at := v.Progress()
			var position *int64
			prepared := v.Preparation()
			if prepared.ProgressSeen {
				position = &at
			}
			sessions = append(sessions, session{v.Mode, position, speed, prepared})
		}
		_, summary := tracker.Live(activity.Viewer{Admin: true})
		cacheBytes, cacheFiles, scanErr := streamingDisk(filepath.Join(dir, "transcode"))
		liveBytes, _, liveErr := streamingDisk(filepath.Join(dir, "live"))
		free, freeErr := bgwork.FreeBytes(dir)
		cache, cacheErr := manager.Resources()
		write(w, map[string]any{"traffic": s.shaper.Snapshot(), "playbackReservations": manager.Running(), "playbackClaims": manager.Claimed(), "playbackLimit": manager.Limit(),
			"renditions": manager.RenditionStats(),
			"sessions":   sessions, "plays": summary, "tuners": map[string]int{"active": active, "peak": peak, "opens": opens},
			"playbackCache": cache, "playbackCacheMeasured": cacheErr == nil,
			"temporaryBytes": cacheBytes + liveBytes, "playbackCacheFiles": cacheFiles, "diskScanComplete": scanErr == nil && liveErr == nil,
			"temporaryVolumeFreeBytes": free, "freeSpaceMeasured": freeErr == nil,
			"goHeapBytes": memory.HeapAlloc, "goroutines": runtime.NumGoroutine(), "os": runtime.GOOS, "arch": runtime.GOARCH})
	})
}

func streamingDisk(dir string) (bytes int64, files int, err error) {
	err = filepath.WalkDir(dir, func(path string, d fs.DirEntry, e error) error {
		if os.IsNotExist(e) {
			return nil
		} // concurrent session cleanup
		if e != nil {
			return e
		}
		if d.IsDir() {
			return nil
		}
		if files >= 100_000 {
			return fmt.Errorf("fixture disk scan limit")
		}
		info, e := d.Info()
		if os.IsNotExist(e) {
			return nil
		}
		if e != nil {
			return e
		}
		bytes += info.Size()
		files++
		return nil
	})
	return
}
