package livetv

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"math"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/ffexec"
	"watchmymedia/server/internal/httpx"
	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
)

func TestOversizedPlaylistSnapshot(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "live.m3u8"), bytes.Repeat([]byte("x"), (64<<10)+1), 0o600); err != nil {
		t.Fatal(err)
	}
	if b, err := takePlaylist(dir); !errors.Is(err, httpx.ErrTooLarge) || b != nil {
		t.Fatalf("oversized playlist was not rejected: bytes=%d err=%v", len(b), err)
	}
	entries, err := os.ReadDir(dir)
	if err != nil || len(entries) != 0 {
		t.Fatalf("failed snapshot left files behind: %v %v", entries, err)
	}
}

func TestSingleInputRollingHLS(t *testing.T) {
	// Each case generates and converts 130 seconds of broadcast. Running
	// all three together oversubscribes shared CI encoders and can exhaust
	// the per-case deadline before the playlist assertions are reached.
	for _, tc := range []struct {
		codec string
		rate  int
		want  string
	}{{"mpeg2video", 30, "60/1"}, {"libx264", 30, "30/1"}, {"libx264", 60, "60/1"}} {
		t.Run(tc.codec+"_"+strconv.Itoa(tc.rate), func(t *testing.T) {
			codec := tc.codec
			for _, name := range []string{"ffmpeg", "ffprobe"} {
				if _, err := exec.LookPath(name); err != nil {
					t.Skip(err)
				}
			}
			ctx, cancel := context.WithTimeout(t.Context(), time.Minute)
			defer cancel()
			input := filepath.Join(t.TempDir(), "broadcast.ts")
			args := ffexec.FFmpegArgs("-y", "-f", "lavfi", "-i", "testsrc2=size=160x120:rate="+strconv.Itoa(tc.rate), "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=48000", "-map", "0:v", "-map", "1:a", "-map", "2:a", "-t", "130", "-c:v", codec, "-threads", "1", "-c:a", "ac3", "-metadata:s:a:0", "language=eng", "-metadata:s:a:1", "language=spa")
			if codec == "mpeg2video" {
				args = append(args, "-flags", "+ilme+ildct", "-top", "1", "-vf", "setsar=4/3")
			} else {
				args = append(args, "-preset", "ultrafast")
			}
			args = append(args, "-f", "mpegts", input)
			if err := ffexec.Command(ctx, "ffmpeg", args...).Run(); err != nil {
				t.Fatal(err)
			}
			broadcast, err := os.ReadFile(input)
			if err != nil {
				t.Fatal(err)
			}
			fake := hdhrtest.New(func(w http.ResponseWriter, r *http.Request) {
				_, _ = io.Copy(w, bytes.NewReader(broadcast))
				<-r.Context().Done()
			})
			server := httptest.NewServer(fake)
			defer server.Close()
			req, err := http.NewRequestWithContext(ctx, http.MethodGet, server.URL+"/auto/v7.1", nil)
			if err != nil {
				t.Fatal(err)
			}
			resp, err := http.DefaultClient.Do(req)
			if err != nil {
				t.Fatal(err)
			}
			defer resp.Body.Close()
			dir := t.TempDir()
			cmd := ffexec.Command(ctx, "ffmpeg", HLSArgs(dir, "libx264", 480, 2000)...)
			cmd.Stdin = io.LimitReader(resp.Body, int64(len(broadcast)))
			stopClose := context.AfterFunc(ctx, func() { _ = resp.Body.Close() })
			defer stopClose()
			started := time.Now()
			if err := cmd.Run(); err != nil {
				t.Fatalf("convert broadcast: %v (context: %v)", err, ctx.Err())
			}
			_ = resp.Body.Close()
			for {
				idle, changed := fake.Idle()
				if idle {
					break
				}
				select {
				case <-changed:
				case <-ctx.Done():
					t.Fatal(ctx.Err())
				}
			}
			active, peak, opens := fake.Counts()
			if active != 0 || peak != 1 || opens != 1 {
				t.Fatalf("tuner counts: active=%d peak=%d opens=%d", active, peak, opens)
			}
			list, err := os.ReadFile(filepath.Join(dir, "live.m3u8"))
			if err != nil {
				t.Fatal(err)
			}
			if strings.Contains(string(list), "PLAYLIST-TYPE") || strings.Contains(string(list), "ENDLIST") || strings.Contains(string(list), "MEDIA-SEQUENCE:0\n") {
				t.Fatalf("not a rolling live playlist: %s", list)
			}
			if n := strings.Count(string(list), "#EXTINF:"); n != WindowSegments {
				t.Fatalf("window contains %d segments", n)
			}
			entries, err := os.ReadDir(dir)
			if err != nil {
				t.Fatal(err)
			}
			var size int64
			segments := 0
			for _, e := range entries {
				info, err := e.Info()
				if err != nil {
					t.Fatal(err)
				}
				size += info.Size()
				if strings.HasSuffix(e.Name(), ".ts") {
					segments++
				}
			}
			if segments > WindowSegments+RetainedSegments || size > DiskLimit {
				t.Fatalf("unbounded output: %d files, %d bytes", segments, size)
			}
			var last string
			for line := range strings.SplitSeq(string(list), "\n") {
				if strings.HasSuffix(line, ".ts") {
					last = line
				}
			}
			data, err := ffexec.Command(ctx, "ffprobe", "-v", "error", "-show_streams", "-of", "json", filepath.Join(dir, last)).Output()
			if err != nil {
				t.Fatal(err)
			}
			var probe struct {
				Streams []struct {
					Width      int    `json:"width"`
					Height     int    `json:"height"`
					CodecName  string `json:"codec_name"`
					FieldOrder string `json:"field_order"`
					Rate       string `json:"r_frame_rate"`
				}
			}
			if err := json.Unmarshal(data, &probe); err != nil {
				t.Fatal(err)
			}
			if len(probe.Streams) != 2 || probe.Streams[0].CodecName != "h264" || probe.Streams[1].CodecName != "aac" || probe.Streams[0].FieldOrder != "progressive" {
				t.Fatalf("unexpected output: %s", data)
			}
			if probe.Streams[0].Rate != tc.want {
				t.Fatalf("frame cadence changed: got %s, want %s", probe.Streams[0].Rate, tc.want)
			}
			ratio := 4.0 / 3.0
			if codec == "mpeg2video" {
				ratio = 16.0 / 9.0
			}
			if math.Abs(float64(probe.Streams[0].Width)/float64(probe.Streams[0].Height)-ratio) > 0.025 {
				t.Fatalf("broadcast aspect changed: %s", data)
			}
			t.Logf("%s: 130 seconds converted in %s; %d retained segments, %d bytes; video rate %s", codec, time.Since(started).Round(time.Millisecond), segments, size, probe.Streams[0].Rate)
		})
	}
}

func TestTwoTunersAndExternalConsumer(t *testing.T) {
	fake := hdhrtest.New(nil)
	server := httptest.NewServer(fake)
	defer server.Close()
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	open := func() *http.Response {
		t.Helper()
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, server.URL+"/auto/v7.1", nil)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		return resp
	}
	external := open()
	defer external.Body.Close()
	viewer := open()
	defer viewer.Body.Close()
	third := open()
	defer third.Body.Close()
	if third.StatusCode != 503 || third.Header.Get("X-HDHomeRun-Error") != "805 All Tuners In Use" {
		t.Fatalf("third viewer: %d", third.StatusCode)
	}
	_, changed := fake.Idle()
	_ = viewer.Body.Close()
	select {
	case <-changed:
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	replacement := open()
	defer replacement.Body.Close()
	if replacement.StatusCode != 200 {
		t.Fatalf("tuner was not released: %d", replacement.StatusCode)
	}
	if active, peak, opens := fake.Counts(); active != 2 || peak != 2 || opens != 3 {
		t.Fatalf("counts=%d/%d/%d", active, peak, opens)
	}
}

func TestLiveTVKeepsItsSelectedAdapter(t *testing.T) {
	t.Parallel()
	n := 2
	// On Linux QSV sits on the render node (playback's pipelineOnOS).
	qsv := "qsv=qs:hw,child_device=2,child_device_type=d3d11va"
	if runtime.GOOS == "linux" {
		qsv = "qsv=qs:hw,child_device=/dev/dri/renderD130"
	}
	for _, tc := range []struct{ encoder, want string }{
		{"h264_nvenc", "-gpu 2"}, {"h264_amf", "d3d11va=dx:2"}, {"h264_qsv", qsv},
	} {
		got := strings.Join(HLSArgsOnDevice(t.TempDir(), tc.encoder, 1080, 8000, &n), " ")
		if !strings.Contains(got, tc.want) {
			t.Fatalf("%s lost adapter: %s", tc.encoder, got)
		}
		if tc.encoder != "h264_nvenc" && !strings.Contains(got, "setsar=1,format=nv12,hwupload") {
			t.Fatalf("software deinterlacing was not uploaded to the device: %s", got)
		}
		if tc.encoder != "h264_nvenc" && strings.Contains(got, "-pix_fmt yuv420p") {
			t.Fatalf("hardware frames were forced back to a CPU pixel format: %s", got)
		}
	}
}
