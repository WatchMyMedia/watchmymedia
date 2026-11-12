package api

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/ffexec"
	"watchmymedia/server/internal/playback"
)

// The two variant URLs point at separate authorized sessions, while the
// manifest's relative links and media token survive HTTP URL resolution.
func TestPrivateVODLadderRoutesAuthorizeEachVariant(t *testing.T) {
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not installed")
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	caps := playback.Capabilities{Encoder: "libx264", Encoders: []string{"libx264"}}
	mgr := playback.NewManager(ffmpeg, filepath.Join(t.TempDir(), "transcode"), caps, time.Minute, 2, log)
	t.Cleanup(mgr.StopAll)
	srv := newTestServerWith(t, func(d *Deps) { d.Playback = mgr })
	admin, err := srv.q.GetUserByName(t.Context(), "admin")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := srv.q.CreateUser(t.Context(), db.CreateUserParams{Name: "other", PasswordHash: adminHash()}); err != nil {
		t.Fatal(err)
	}
	src := filepath.Join(t.TempDir(), "fictional.mkv")
	if err := ffexec.Command(t.Context(), ffmpeg, ffexec.FFmpegArgs("-f", "lavfi", "-i", "testsrc2=size=320x180:rate=24:duration=12",
		"-f", "lavfi", "-i", "sine=frequency=440:duration=12", "-map", "0:v", "-map", "1:a",
		"-c:v", "mpeg4", "-c:a", "aac", src)...).Run(); err != nil {
		t.Fatalf("fictional sample: %v", err)
	}
	low := playback.Spec{FileID: 1, PlayID: "fictional-play", UserID: admin.ID, Path: src, Mode: playback.ModeTranscode, BurnIn: -1,
		Video: playback.VideoPlan{Codec: "h264", Encoder: "libx264", Width: 160, Height: 90},
		Audio: playback.AudioPlan{Index: 1, Codec: "aac", Channels: 1}, MaxBitrate: 400_000,
		VOD: &playback.VODOptions{DurationMs: 12_000, FrameRate: 24, Bandwidth: 1_100_000,
			VideoRange: "SDR", Codecs: "avc1.64000d,mp4a.40.2", SeparateAudio: true, AudioChannels: 1}}
	high := low
	high.Video.Width, high.Video.Height = 320, 180
	high.MaxBitrate = 1_000_000
	high.VOD = &playback.VODOptions{DurationMs: 12_000, FrameRate: 24, Bandwidth: 2_300_000,
		VideoRange: "SDR", Codecs: "avc1.64000d,mp4a.40.2", SeparateAudio: true, AudioChannels: 1}
	s, err := mgr.StartVODLadder(t.Context(), low, high)
	if errors.Is(err, playback.ErrLadderNotReady) {
		// Admission measures this host's software encoder against every
		// configured slot; a busy CI machine can be refused. That is the
		// budget working, and without a family there are no routes to check.
		t.Skipf("host too slow to admit a family: %v", err)
	}
	if err != nil {
		t.Fatal(err)
	}
	owner := deviceLogin(t, srv.URL, "admin", "password1", "ladder-owner", "Fictional owner")
	other := deviceLogin(t, srv.URL, "other", "password1", "ladder-other", "Fictional other")
	mediaToken := func(signin SignIn) string {
		t.Helper()
		var token MediaToken
		doJSON(t, nil, http.MethodPost, srv.URL+"/api/auth/media-token", "", &token, "Authorization", "Bearer "+signin.Token)
		return token.Token
	}
	get := func(u string) (int, string) {
		t.Helper()
		res, err := http.Get(u)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		b, _ := io.ReadAll(res.Body)
		return res.StatusCode, string(b)
	}
	base, _ := url.Parse(srv.URL + "/api/playback/" + s.ID + "/master.m3u8")
	base.RawQuery = "access_token=" + url.QueryEscape(mediaToken(owner))
	status, master := get(base.String())
	if status != http.StatusOK || strings.Count(master, "#EXT-X-STREAM-INF:") != 2 ||
		!strings.Contains(master, `#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio"`) ||
		strings.Count(master, `AUDIO="audio"`) != 2 {
		t.Fatalf("owner master -> %d:\n%s", status, master)
	}
	seen := 0
	otherToken := mediaToken(other)
	for line := range strings.SplitSeq(master, "\n") {
		if strings.HasPrefix(line, "#EXT-X-MEDIA:TYPE=AUDIO,") {
			_, raw, ok := strings.Cut(line, `URI="`)
			if !ok || !strings.HasSuffix(raw, `"`) {
				t.Fatalf("audio group has no URI: %s", line)
			}
			audioURI, err := url.Parse(strings.TrimSuffix(raw, `"`))
			if err != nil {
				t.Fatal(err)
			}
			audio := base.ResolveReference(audioURI)
			if status, body := get(audio.String()); status != http.StatusOK || !strings.Contains(body, "#EXT-X-MAP") {
				t.Fatalf("owner audio -> %d: %s", status, body)
			}
			audio.RawQuery = "access_token=" + url.QueryEscape(otherToken)
			if status, _ := get(audio.String()); status != http.StatusNotFound {
				t.Fatalf("another user fetched audio: %d", status)
			}
		}
		if !strings.HasPrefix(line, "../") {
			continue
		}
		seen++
		uri, err := url.Parse(line)
		if err != nil {
			t.Fatal(err)
		}
		variant := base.ResolveReference(uri)
		if status, body := get(variant.String()); status != http.StatusOK || !strings.Contains(body, "#EXT-X-MAP") {
			t.Fatalf("owner variant %s -> %d: %s", variant.Path, status, body)
		}
		variant.RawQuery = "access_token=" + url.QueryEscape(otherToken)
		if status, _ := get(variant.String()); status != http.StatusNotFound {
			t.Fatalf("another user fetched %s: %d", variant.Path, status)
		}
	}
	if seen != 2 {
		t.Fatalf("master named %d variants, want 2", seen)
	}
	decodeCtx, cancel := context.WithTimeout(t.Context(), 20*time.Second)
	defer cancel()
	if err := ffexec.Command(decodeCtx, ffmpeg, ffexec.FFmpegArgs("-i", base.String(),
		"-f", "null", "-")...).Run(); err != nil {
		t.Fatal("HTTP ladder did not decode through an HLS client") // ffmpeg's error can include the token-bearing URL
	}
	if status, _ := get(base.ResolveReference(&url.URL{Path: "../" + s.ID + "/v00000.m4s"}).String()); status != http.StatusUnauthorized {
		t.Fatalf("unauthed segment -> %d", status)
	}
}
