//go:build streamingacceptance

package dvr

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/ffexec"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
	"watchmymedia/server/internal/playback"
)

type liveQualification struct {
	fixture
	sessions *livetv.Sessions
	manager  *playback.Manager
	owner    livetv.Owner
	profile  *livetv.LiveProfile
	dir      string
	// guard, when set, receives the capacity guard that stopped an output
	// after it was ready, and the attempt is skipped instead of failed.
	guard *string
}

func qualifyLive(t *testing.T, stream http.HandlerFunc, slots int) liveQualification {
	t.Helper()
	f := setup(t, stream)
	f.s.opts.Log = slog.New(slog.NewTextHandler(os.Stderr, nil))
	manager := playback.NewManager("ffmpeg", t.TempDir(), playback.Capabilities{Encoder: "libx264"}, time.Minute, slots, slog.New(slog.DiscardHandler))
	t.Cleanup(manager.StopAll)
	owner := livetv.Owner{UserID: f.actor.UserID, SignInID: "acceptance-one", Admin: true}
	dir := t.TempDir()
	sessions := livetv.NewSessions(f.live, manager, livetv.SessionOptions{FFmpeg: "ffmpeg", Dir: dir, ShareSources: true, Log: slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelDebug})), Validate: func(_ context.Context, id string) (livetv.Owner, error) {
		copy := owner
		copy.SignInID = id
		return copy, nil
	}})
	t.Cleanup(sessions.Close)
	sessions.SetSharedSourceInput(f.s.JoinLive)
	manager.SetLiveCache(dir, sessions.StopForStorage)
	return liveQualification{f, sessions, manager, owner, &livetv.LiveProfile{SegmentFormats: []string{"fmp4"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}, MaxAudioChannels: 2}, dir, nil}
}
func (f liveQualification) start(t *testing.T, ctx context.Context, quality, request string, ladder bool) livetv.SessionView {
	t.Helper()
	view, guard := f.tryStart(t, ctx, quality, request, ladder)
	if guard != "" {
		t.Fatalf("prepare %s: %s", request, guard)
	}
	return view
}

// capacityGuard says which of the server's guards stopped an output because
// the host fell below real time, or "" for any other state: ladder_not_ready
// from the family, signal_lost from the bounded source queue.
func capacityGuard(view livetv.SessionView) string {
	if view.State == "failed" && (view.Reason == "ladder_not_ready" || view.Reason == "signal_lost") {
		return fmt.Sprintf("its guard stopped %s (%s)", view.ID, view.Reason)
	}
	return ""
}

// guardStopped says which capacity guard ended one of the outputs ids, or
// "". A guard fails the shared producer first; its viewers end with the same
// reason once the encoder exits, so this waits briefly for that rather than
// reading a viewer that is still "playing" for a moment. An output that keeps
// playing is not guarded and the caller's failure stands.
func (f liveQualification) guardStopped(ctx context.Context, ids ...string) string {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	guards := make(chan string, len(ids))
	for _, id := range ids {
		go func() {
			ended, err := f.sessions.WaitEnded(ctx, f.owner, id)
			if err != nil {
				guards <- ""
				return
			}
			guards <- capacityGuard(ended)
		}()
	}
	for range ids {
		if guard := <-guards; guard != "" {
			return guard
		}
	}
	return ""
}

// fatal fails the test, unless f.guard is set and a capacity guard stopped
// one of the outputs ids: a guard that trips while the test reads media (the
// server then answers "live session not found") ends the attempt as the
// guard, like one seen in setup or by a heartbeat.
func (f liveQualification) fatal(t *testing.T, ctx context.Context, ids []string, format string, args ...any) {
	t.Helper()
	if f.guard != nil {
		if guard := f.guardStopped(ctx, ids...); guard != "" {
			f.stopped(t, guard)
		}
	}
	t.Fatalf(format, args...)
}

// stopped records a capacity guard and ends the attempt without failing it.
func (f liveQualification) stopped(t *testing.T, guard string) {
	t.Helper()
	*f.guard = guard
	t.Skipf("the host fell below real time and %s", guard)
}

// tryStart is start, except that a capacity guard stopping the output is
// returned rather than failing the test.
func (f liveQualification) tryStart(t *testing.T, ctx context.Context, quality, request string, ladder bool) (livetv.SessionView, string) {
	t.Helper()
	start := f.sessions.StartSharedOutput
	if ladder {
		start = f.sessions.StartLiveLadder
	}
	view, err := start(ctx, f.owner, f.channels[0].ID, quality, request, "Acceptance", f.profile)
	if err != nil {
		t.Fatal(err)
	}
	ready, err := f.sessions.WaitReady(ctx, f.owner, view.ID)
	if err == nil {
		if guard := capacityGuard(ready); guard != "" {
			return ready, guard
		}
	}
	if err != nil || ready.State != "playing" {
		t.Fatalf("prepare %s: %+v %v", request, ready, err)
	}
	return ready, ""
}
func (f liveQualification) media(t *testing.T, ctx context.Context, id, name string) []byte {
	t.Helper()
	file, err := f.sessions.Media(ctx, f.owner, id, name)
	if err != nil {
		f.fatal(t, ctx, []string{id}, "media %s: %v", name, err)
	}
	defer file.Close()
	b, err := io.ReadAll(io.LimitReader(file, 8<<20))
	if err != nil {
		t.Fatal(err)
	}
	return b
}
func qualificationSegments(b []byte) []string {
	var names []string
	for line := range strings.SplitSeq(string(b), "\n") {
		if line != "" && !strings.HasPrefix(line, "#") {
			names = append(names, line)
		}
	}
	return names
}
func (f liveQualification) record(t *testing.T, ctx context.Context) string {
	t.Helper()
	now := unix(f.clock.Load())
	r, err := f.s.Manual(ctx, f.actor, Manual{ChannelID: f.channels[0].ID, Start: now, End: now.Add(time.Hour), Name: "Qualification report", Repeat: "once", Zone: "UTC"})
	if err != nil {
		t.Fatal(err)
	}
	f.s.tick(ctx, now)
	return r.ID
}
func (f liveQualification) recordingBytes(t *testing.T, id string) int64 {
	t.Helper()
	f.s.mu.Lock()
	out := f.s.running[id]
	f.s.mu.Unlock()
	if out == nil {
		t.Fatal("recording lost its capture")
	}
	out.mu.Lock()
	defer out.mu.Unlock()
	return out.r.Bytes
}

type packetWindow struct {
	First, End float64
	Packets    int
}

func qualifyPackets(t *testing.T, ctx context.Context, media []byte) packetWindow {
	t.Helper()
	cmd := ffexec.Command(ctx, "ffprobe", "-v", "error", "-f", "mp4", "-i", "pipe:0", "-show_packets", "-show_entries", "packet=pts_time,dts_time,duration_time", "-of", "json")
	cmd.Stdin = bytes.NewReader(media)
	b, err := cmd.Output()
	if err != nil {
		t.Fatal(err)
	}
	var result struct {
		Packets []struct {
			PTS      string `json:"pts_time"`
			DTS      string `json:"dts_time"`
			Duration string `json:"duration_time"`
		} `json:"packets"`
	}
	if err := json.Unmarshal(b, &result); err != nil {
		t.Fatal(err)
	}
	w := packetWindow{First: math.Inf(1), End: math.Inf(-1), Packets: len(result.Packets)}
	previousEnd := math.NaN()
	for _, p := range result.Packets {
		pts, e1 := strconv.ParseFloat(p.PTS, 64)
		dts, e2 := strconv.ParseFloat(p.DTS, 64)
		duration, e3 := strconv.ParseFloat(p.Duration, 64)
		if e1 != nil || e2 != nil || e3 != nil || duration <= 0 {
			t.Fatalf("incomplete packet timestamps: %+v", p)
		}
		if !math.IsNaN(previousEnd) && math.Abs(previousEnd-dts) > .002 {
			t.Fatalf("decode timeline gap %.6f -> %.6f", previousEnd, dts)
		}
		previousEnd = dts + duration
		w.First = min(w.First, pts)
		w.End = max(w.End, pts+duration)
	}
	if w.Packets < 3 {
		t.Fatalf("insufficient timeline evidence: %+v", w)
	}
	return w
}
func (f liveQualification) timelines(t *testing.T, ctx context.Context, id string) int {
	t.Helper()
	low := qualificationSegments(f.media(t, ctx, id, "low-live.m3u8"))
	high := qualificationSegments(f.media(t, ctx, id, "high-live.m3u8"))
	if len(low) < 3 || len(high) < 3 || len(low) > livetv.WindowSegments || len(high) > livetv.WindowSegments {
		t.Fatal("unbounded or incomplete rolling window")
	}
	var index int
	if _, err := fmt.Sscanf(low[len(low)-2], "low-segment-%d.m4s", &index); err != nil {
		t.Fatal(err)
	}
	windows := make([]packetWindow, 3)
	var boundary int64
	for i, name := range []string{"low", "high", "audio"} {
		init := f.media(t, ctx, id, name+"-init.mp4")
		joined := bytes.Clone(init)
		for segment := index - 1; segment <= index; segment++ {
			media := f.media(t, ctx, id, fmt.Sprintf("%s-segment-%09d.m4s", name, segment))
			if i < 2 && segment == index {
				at, err := playback.HLSVideoBoundary(init, media)
				if err != nil {
					t.Fatal(err)
				}
				if i == 0 {
					boundary = at
				} else if at != boundary {
					t.Fatalf("rung boundaries differ: %d %d", boundary, at)
				}
			}
			joined = append(joined, media...)
		}
		windows[i] = qualifyPackets(t, ctx, joined)
		cmd := ffexec.Command(ctx, "ffmpeg", ffexec.FFmpegArgs("-threads", "1", "-f", "mp4", "-i", "pipe:0", "-filter_threads", "1", "-f", "null", "-")...)
		cmd.Stdin = bytes.NewReader(joined)
		if err := cmd.Run(); err != nil {
			t.Fatalf("%s sample decode: %v", name, err)
		}
	}
	for _, video := range windows[:2] {
		if math.Abs(video.First-windows[2].First) > .1 || math.Abs(video.End-windows[2].End) > .1 {
			t.Fatalf("A/V timeline drift: video %+v audio %+v", video, windows[2])
		}
	}
	t.Logf("segment %d: video [%.3f,%.3f], audio [%.3f,%.3f]", index, windows[1].First, windows[1].End, windows[2].First, windows[2].End)
	return index
}

func TestLiveLadderRollingHDQualification(t *testing.T) {
	// Exercise the full configured budget, not a universal host capacity claim.
	// The hosted Windows runner qualifies one family plus a single fallback;
	// the default five-claim profile adds a distinct second adaptive family.
	slots := 5
	switch os.Getenv("WMM_LIVE_QUALIFICATION_CLAIMS") {
	case "", "5":
	case "3":
		slots = 3
	default:
		t.Fatal("WMM_LIVE_QUALIFICATION_CLAIMS must be 3 or 5")
	}
	// A shared hosted VM's encoder speed varies. When it falls below real
	// time, the server's capacity guards stop the family before it publishes
	// invalid output (ladder_not_ready, or signal_lost from the bounded
	// source queue): correct behaviour, but no qualification. One fresh
	// attempt follows; a host that cannot sustain the budget twice fails, as
	// does any other failure. On Windows CI one of two repetitions tripped a
	// guard in three runs, and the other qualified.
	// A guard can trip at any point once the outputs play, including while
	// the test reads their media, so the attempt watches for one throughout.
	for attempt := 1; attempt <= 2; attempt++ {
		guarded := ""
		t.Run(fmt.Sprintf("attempt %d", attempt), func(t *testing.T) { rollingHDQualification(t, slots, &guarded) })
		if t.Failed() || guarded == "" {
			return
		}
		t.Logf("attempt %d: the host fell below real time and %s", attempt, guarded)
	}
	t.Fatal("the host could not sustain the configured budget in two attempts")
}

// rollingHDQualification runs the full budget once. When a capacity guard
// stops an output it records why in guarded and skips the attempt.
func rollingHDQualification(t *testing.T, slots int, guarded *string) {
	f := qualifyLive(t, qualificationBroadcastHD(t), slots)
	f.guard = guarded
	ctx, cancel := context.WithTimeout(t.Context(), 4*time.Minute)
	defer cancel()
	var views, families []livetv.SessionView
	for _, out := range []struct {
		quality, request string
		family           bool
	}{{"720", "primary", true}, {"720", "peer", true}, {"360", "distinct", true}, {"360", "fallback", false}} {
		if out.request == "distinct" && slots != 5 {
			continue
		}
		view, guard := f.tryStart(t, ctx, out.quality, out.request, out.family)
		if guard != "" {
			f.stopped(t, guard)
		}
		views = append(views, view)
		if out.family && out.request != "peer" {
			families = append(families, view)
		}
	}
	primary, peer := views[0], views[1]
	ids := make([]string, len(views))
	for i, v := range views {
		ids[i] = v.ID
	}
	if f.manager.Claimed() != slots {
		for _, v := range views {
			status, err := f.sessions.Status(ctx, f.owner, v.ID)
			t.Logf("output %+v %v", status, err)
		}
		f.fatal(t, ctx, ids, "full budget: %d", f.manager.Claimed())
	}
	recording := f.record(t, ctx)
	beforeBytes := f.recordingBytes(t, recording)
	first := f.timelines(t, ctx, primary.ID)
	heldName := qualificationSegments(f.media(t, ctx, primary.ID, "high-live.m3u8"))[0]
	held, err := f.sessions.Media(ctx, f.owner, primary.ID, heldName)
	if err != nil {
		f.fatal(t, ctx, ids[:1], "media %s: %v", heldName, err)
	}
	defer held.Close()
	heldInit, err := f.sessions.Media(ctx, f.owner, primary.ID, "high-init.mp4")
	if err != nil {
		f.fatal(t, ctx, ids[:1], "media high-init.mp4: %v", err)
	}
	defer heldInit.Close()
	started := time.Now()
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	nextSample := time.Now().Add(25 * time.Second)
	for time.Since(started) < 130*time.Second {
		select {
		case <-ctx.Done():
			t.Fatal(ctx.Err())
		case <-ticker.C:
		}
		for _, view := range views {
			status, err := f.sessions.Heartbeat(ctx, f.owner, view.ID, false, true)
			if err != nil || status.State != "playing" {
				f.fatal(t, ctx, []string{view.ID}, "full-budget output failed: %+v %v", status, err)
			}
		}
		resources, err := f.manager.Resources()
		if err != nil || len(resources.Sessions) != len(families)+1 || f.manager.Claimed() != slots {
			f.fatal(t, ctx, ids, "resource accounting: %+v %v claims %d", resources, err, f.manager.Claimed())
		}
		for _, out := range resources.Sessions {
			if out.Bytes > livetv.DiskLimit {
				t.Fatalf("unbounded output: %+v", out)
			}
		}
		if time.Now().After(nextSample) {
			for _, family := range families {
				f.timelines(t, ctx, family.ID)
			}
			nextSample = time.Now().Add(25 * time.Second)
		}
	}
	last := f.timelines(t, ctx, primary.ID)
	if last-first < 60 {
		t.Fatalf("live window did not advance: %d -> %d", first, last)
	}
	if file, err := f.sessions.Media(ctx, f.owner, peer.ID, heldName); err == nil {
		_ = file.Close()
		t.Fatal("old rolling media did not evict")
	} else if !errors.Is(err, os.ErrNotExist) && !(runtime.GOOS == "windows" && errors.Is(err, os.ErrPermission)) {
		// DeleteFile leaves an open Windows file delete-pending; new opens
		// report access denied until the held reader closes. Both OS results
		// mean evicted bytes are unavailable to a new response.
		f.fatal(t, ctx, ids[1:2], "evicted %s: %v", heldName, err)
	}
	if b, err := io.ReadAll(held); err != nil || len(b) == 0 {
		t.Fatalf("evicted held reader broke: %d %v", len(b), err)
	}
	if f.recordingBytes(t, recording) <= beforeBytes {
		t.Fatal("recording did not advance")
	}
	if active, peak, opens := f.fake.Counts(); active != 1 || peak != 1 || opens != 1 {
		t.Fatalf("capture duplicated: %d %d %d", active, peak, opens)
	}
	for _, view := range views {
		if err := f.sessions.Stop(f.owner, view.ID); err != nil {
			t.Fatal(err)
		}
	}
	if f.manager.Claimed() != 0 {
		t.Fatal("encoder claims leaked")
	}
	if active, _, _ := f.fake.Counts(); active != 1 {
		t.Fatal("stopping outputs stopped recording")
	}
	_ = held.Close()
	_ = heldInit.Close()
	entries, err := os.ReadDir(f.dir)
	if err != nil || len(entries) != 0 {
		t.Fatalf("held-reader cleanup left output: %v %v", entries, err)
	}
	if err := f.s.Cancel(ctx, recording, f.actor); err != nil {
		t.Fatal(err)
	}
	f.s.tick(ctx, unix(f.clock.Load()))
	waitFictionalTunerIdle(t, ctx, f.fake)
	t.Logf("qualified 720p input, %d encoder claims, %d families, %d viewers, held readers and recording for %s", slots, len(families), len(views), time.Since(started).Round(time.Second))
}

// Render the fictional tuner before measuring the playback encoders.
// Generating HD testsrc2 and encoding another MPEG-2 stream in real time adds
// fixture CPU work that a physical tuner would perform off the server. Looping
// a paced remux keeps the broadcast continuous without adding a source encode.
func qualificationBroadcastHD(t *testing.T) http.HandlerFunc {
	t.Helper()
	path := filepath.Join(t.TempDir(), "broadcast.ts")
	ctx, cancel := context.WithTimeout(t.Context(), time.Minute)
	defer cancel()
	cmd := ffexec.Command(ctx, "ffmpeg", ffexec.FFmpegArgs("-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30000/1001", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
		"-map", "0:v:0", "-map", "1:a:0", "-t", "12", "-c:v", "mpeg2video", "-threads", "1", "-flags", "+ilme+ildct", "-top", "1", "-b:v", "5000k", "-c:a", "ac3", "-ac", "2", "-b:a", "192k", "-f", "mpegts", path)...)
	if err := cmd.Run(); err != nil {
		t.Fatalf("render fictional tuner: %v", err)
	}
	return func(w http.ResponseWriter, r *http.Request) {
		cmd := ffexec.Command(r.Context(), "ffmpeg", ffexec.FFmpegArgs("-re", "-stream_loop", "-1", "-i", path, "-map", "0", "-c", "copy", "-f", "mpegts", "pipe:1")...)
		cmd.Stdout = w
		if err := cmd.Run(); err != nil && r.Context().Err() == nil {
			t.Errorf("fictional tuner ended unexpectedly: %v", err)
		}
	}
}

func TestLiveLadderRetainedFallbackQualification(t *testing.T) {
	f := qualifyLive(t, hdhrtest.Broadcast("ffmpeg"), 3)
	ctx, cancel := context.WithTimeout(t.Context(), time.Minute)
	defer cancel()
	fallback := f.start(t, ctx, "360", "stable", false)
	candidate := f.start(t, ctx, "720", "candidate", true)
	before := f.media(t, ctx, fallback.ID, "live.m3u8")
	roots, err := os.ReadDir(f.dir)
	if err != nil {
		t.Fatal(err)
	}
	var producer string
	for _, entry := range roots {
		if _, err := os.Stat(filepath.Join(f.dir, entry.Name(), "high-init.mp4")); err == nil {
			producer = entry.Name()
		}
	}
	if producer == "" {
		t.Fatal("missing family output")
	}
	recording := f.record(t, ctx)
	f.sessions.StopForStorage(producer)
	if err := f.sessions.Stop(f.owner, candidate.ID); err != nil {
		t.Fatal(err)
	}
	if f.manager.Claimed() != 1 {
		t.Fatal("family failure consumed the fallback claim")
	}
	after := f.media(t, ctx, fallback.ID, "live.m3u8")
	if len(before) == 0 || len(after) == 0 {
		t.Fatal("fallback lost playlist")
	}
	resumed := f.start(t, ctx, "720", "replacement", true)
	f.timelines(t, ctx, resumed.ID)
	if active, _, opens := f.fake.Counts(); active != 1 || opens != 1 {
		t.Fatalf("fallback retuned: %d %d", active, opens)
	}
	// Restart the ephemeral playback service while the independent recording
	// retains capture. Old media IDs cannot resurrect output in the new service.
	f.sessions.Close()
	if f.manager.Claimed() != 0 {
		t.Fatal("restart leaked encoder claims")
	}
	f.sessions = livetv.NewSessions(f.live, f.manager, livetv.SessionOptions{FFmpeg: "ffmpeg", Dir: f.dir, ShareSources: true,
		Validate: func(context.Context, string) (livetv.Owner, error) { return f.owner, nil }})
	t.Cleanup(f.sessions.Close)
	f.sessions.SetSharedSourceInput(f.s.JoinLive)
	f.manager.SetLiveCache(f.dir, f.sessions.StopForStorage)
	if _, err := f.sessions.Media(ctx, f.owner, resumed.ID, "live.m3u8"); !errors.Is(err, livetv.ErrOwner) {
		t.Fatalf("restart retained old media authority: %v", err)
	}
	restarted := f.start(t, ctx, "360", "restart-fallback", false)
	if restarted.ID == fallback.ID {
		t.Fatal("restart reused an old media generation")
	}
	if active, _, opens := f.fake.Counts(); active != 1 || opens != 1 {
		t.Fatalf("playback restart interrupted recording capture: %d %d", active, opens)
	}
	if err := f.sessions.Stop(f.owner, restarted.ID); err != nil {
		t.Fatal(err)
	}
	if err := f.s.Cancel(ctx, recording, f.actor); err != nil {
		t.Fatal(err)
	}
	f.s.tick(ctx, unix(f.clock.Load()))
	waitFictionalTunerIdle(t, ctx, f.fake)
}

func TestLiveLadderFormatChangeQualification(t *testing.T) {
	change := make(chan struct{})
	source := func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		oldCtx, cancel := context.WithCancel(r.Context())
		defer cancel()
		go func() {
			select {
			case <-change:
				cancel()
			case <-oldCtx.Done():
			}
		}()
		run := func(ctx context.Context, size string, offset float64) {
			args := ffexec.FFmpegArgs("-re", "-f", "lavfi", "-i", "testsrc2=size="+size+":rate=30", "-re", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
				"-map", "0:v:0", "-map", "1:a:0", "-c:v", "mpeg2video", "-threads", "1", "-g", "15", "-b:v", "2000k", "-c:a", "ac3", "-ac", "2", "-b:a", "192k", "-output_ts_offset", fmt.Sprintf("%.3f", offset), "-f", "mpegts", "pipe:1")
			cmd := ffexec.Command(ctx, "ffmpeg", args...)
			cmd.Stdout = w
			_ = cmd.Run()
		}
		run(oldCtx, "640x360", 0)
		if r.Context().Err() == nil {
			run(r.Context(), "320x180", time.Since(started).Seconds())
		}
	}
	f := qualifyLive(t, source, 2)
	ctx, cancel := context.WithTimeout(t.Context(), 70*time.Second)
	defer cancel()
	current := f.start(t, ctx, "720", "old-format", true)
	recording := f.record(t, ctx)
	before := f.recordingBytes(t, recording)
	close(change)
	failedCtx, stop := context.WithTimeout(ctx, 15*time.Second)
	ended, err := f.sessions.WaitEnded(failedCtx, f.owner, current.ID)
	stop()
	if err != nil || ended.State != "failed" {
		t.Fatalf("changed format remained advertised: %+v %v", ended, err)
	}
	if f.recordingBytes(t, recording) <= before {
		t.Fatal("format failure stopped recording capture")
	}
	// A fresh sample advances the source generation. The first replacement may
	// reject its old generation; one explicit retry must use the new inspection.
	var resumed livetv.SessionView
	for attempt := range 2 {
		view, err := f.sessions.StartLiveLadder(ctx, f.owner, f.channels[0].ID, "720", fmt.Sprintf("new-format-%d", attempt), "Acceptance", f.profile)
		if err != nil {
			t.Fatal(err)
		}
		resumed, err = f.sessions.WaitReady(ctx, f.owner, view.ID)
		if err != nil {
			t.Fatal(err)
		}
		if resumed.State == "playing" {
			break
		}
		if resumed.Reason != "source_unsupported" {
			t.Fatalf("unexpected format refusal: %+v", resumed)
		}
	}
	if resumed.State != "playing" {
		t.Fatalf("new format did not recover: %+v", resumed)
	}
	f.timelines(t, ctx, resumed.ID)
	if active, _, opens := f.fake.Counts(); active != 1 || opens != 1 {
		t.Fatalf("format switch reopened the HTTP capture: %d %d", active, opens)
	}
	if err := f.sessions.Stop(f.owner, resumed.ID); err != nil {
		t.Fatal(err)
	}
	if err := f.s.Cancel(ctx, recording, f.actor); err != nil {
		t.Fatal(err)
	}
	f.s.tick(ctx, unix(f.clock.Load()))
	waitFictionalTunerIdle(t, ctx, f.fake)
}
