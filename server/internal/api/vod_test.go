package api

import (
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/playback"
)

func TestVODPlaybackForDevices(t *testing.T) {
	t.Parallel()
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not installed")
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	mgr := playback.NewManager(ffmpeg, filepath.Join(t.TempDir(), "transcode"), playback.Capabilities{}, time.Minute, 8, log)
	t.Cleanup(mgr.StopAll)
	ffprobe := filepath.Join(filepath.Dir(ffmpeg), "ffprobe")
	srv := newTestServerWith(t, func(d *Deps) {
		d.Playback = mgr
		d.Keyframes = playback.NewKeyframes(ffprobe, t.TempDir(), log)
		// A scan writes its result into that folder after it answers: wait for
		// it before the folder goes.
		t.Cleanup(d.Keyframes.Close)
		d.Subtitles = playback.NewSubtitles(ffmpeg, t.TempDir())
	})
	ctx := t.Context()
	dir := t.TempDir()
	srt := filepath.Join(dir, "subs.srt")
	_ = os.WriteFile(srt, []byte("1\n00:00:01,000 --> 00:00:02,500\nOpening line\n\n2\n00:00:14,000 --> 00:00:15,000\nLater line\n"), 0o600)
	path := filepath.Join(dir, "Clip (2020).mkv")
	if b, err := exec.Command(ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=24:duration=20",
		"-f", "lavfi", "-i", "sine=frequency=440:duration=20", "-i", srt, "-map", "0:v", "-map", "1:a", "-map", "2:s",
		"-c:v", "mpeg4", "-g", "1000", "-force_key_frames", "0,3,7,9,13,17", "-c:a", "aac", "-c:s", "srt", "-metadata:s:s:0", "language=eng", path).CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg: %v\n%s", err, b)
	}
	st, _ := os.Stat(path)
	probeJSON := `{"container":"matroska","durationMs":20000,"bitRate":300000,"video":[{"index":0,"codec":"mpeg4","width":64,"height":64,"frameRate":24}],"audio":[{"index":1,"codec":"aac","channels":1,"default":true}],"subtitles":[{"index":2,"codec":"subrip","language":"eng"}]}`
	f, err := srv.q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: srv.fileLibrary(t, path), Path: path, Size: st.Size(), Mtime: db.FormatTime(st.ModTime()), AddedAt: db.FormatTime(time.Now()),
		Parsed: "{}", Probe: &probeJSON, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}

	tv := deviceLogin(t, srv.URL, "admin", "password1", "atv-1", "Living room Apple TV")
	var d playback.Decision
	body := `{"profile":{"name":"tvos","containers":["mp4"],"videoCodecs":["mpeg4"],"audioCodecs":["aac"],"subtitleFormats":["webvtt"]},"playlist":"vod",` +
		`"client":{"deviceId":"atv-1","product":"WatchMyMedia for Apple TV","platform":"tvos"}}`
	if res := doJSON(t, nil, http.MethodPost, srv.URL+fmt.Sprintf("/api/files/%d/playback", f.ID), body, &d, "Authorization", "Bearer "+tv.Token); res.StatusCode != 200 {
		t.Fatalf("playback -> %d", res.StatusCode)
	}
	if d.Mode != playback.ModeRemux || d.Playlist != "vod" || d.StartMs != 0 || !strings.HasSuffix(d.URL, "/master.m3u8") {
		t.Fatalf("decision = mode %s, playlist %q, start %d, url %s", d.Mode, d.Playlist, d.StartMs, d.URL)
	}
	// The player fetches media with a media token in the URL and no header.
	var mt MediaToken
	doJSON(t, nil, http.MethodPost, srv.URL+"/api/auth/media-token", "", &mt, "Authorization", "Bearer "+tv.Token)
	get := func(u string) (int, string, string) {
		t.Helper()
		res, err := http.Get(u)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		b, _ := io.ReadAll(res.Body)
		return res.StatusCode, res.Header.Get("Content-Type"), string(b)
	}
	base := srv.URL + strings.TrimSuffix(d.URL, "master.m3u8")
	q := "?access_token=" + mt.Token
	code, ct, master := get(srv.URL + d.URL + q)
	if code != 200 || ct != "application/vnd.apple.mpegurl" || !strings.Contains(master, "video.m3u8"+q) || !strings.Contains(master, `URI="sub-s2.m3u8`+q+`"`) || !strings.Contains(master, `NAME="English",LANGUAGE="en"`) {
		t.Fatalf("master -> %d %s:\n%s", code, ct, master)
	}
	code, _, media := get(base + "video.m3u8" + q)
	if code != 200 || !strings.Contains(media, `#EXT-X-MAP:URI="init.mp4`+q+`"`) || !strings.Contains(media, "v00000.m4s"+q) || !strings.Contains(media, "#EXT-X-PLAYLIST-TYPE:VOD") {
		t.Fatalf("media playlist -> %d:\n%s", code, media)
	}
	if code, ct, b := get(base + "init.mp4" + q); code != 200 || ct != "video/mp4" || !strings.Contains(b, "moov") {
		t.Errorf("init -> %d %s", code, ct)
	}
	if code, ct, b := get(base + "v00001.m4s" + q); code != 200 || ct != "video/mp4" || !strings.Contains(b, "moof") {
		t.Errorf("segment -> %d %s (%d bytes)", code, ct, len(b))
	}
	if code, _, _ := get(base + "v00001.m4s"); code != http.StatusUnauthorized {
		t.Errorf("segment without a token -> %d", code)
	}
	if code, _, _ := get(base + "v00099.m4s" + q); code != http.StatusNotFound {
		t.Errorf("segment past the end -> %d", code)
	}
	code, _, subs := get(base + "sub-s2.m3u8" + q)
	if code != 200 || !strings.Contains(subs, "sub-s2-00000.vtt"+q) {
		t.Fatalf("subtitle playlist -> %d:\n%s", code, subs)
	}
	code, ct, cue := get(base + "sub-s2-00000.vtt" + q)
	if code != 200 || !strings.HasPrefix(ct, "text/vtt") || !strings.Contains(cue, "X-TIMESTAMP-MAP=MPEGTS:900000") || !strings.Contains(cue, "Opening line") || strings.Contains(cue, "Later line") {
		t.Errorf("subtitle segment -> %d %s:\n%s", code, ct, cue)
	}
	// Someone else cannot read the session.
	doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/users", `{"name":"kid","password":"kidpass1"}`, nil)
	kid := deviceLogin(t, srv.URL, "kid", "kidpass1", "phone-1", "Kid's phone")
	if res := doJSON(t, nil, http.MethodGet, base+"video.m3u8", "", nil, "Authorization", "Bearer "+kid.Token); res.StatusCode != http.StatusNotFound {
		t.Errorf("another user's VOD playlist -> %d", res.StatusCode)
	}
	// Without the playlist field, the same request still gets an event playlist.
	var ev playback.Decision
	doJSON(t, nil, http.MethodPost, srv.URL+fmt.Sprintf("/api/files/%d/playback", f.ID), strings.Replace(body, `"playlist":"vod",`, "", 1), &ev, "Authorization", "Bearer "+tv.Token)
	if ev.Playlist != "event" || !strings.HasSuffix(ev.URL, "/index.m3u8") {
		t.Errorf("default decision = %q %s", ev.Playlist, ev.URL)
	}
	// A client that asks for separate audio gets it as a rendition of its own.
	var sep playback.Decision
	doJSON(t, nil, http.MethodPost, srv.URL+fmt.Sprintf("/api/files/%d/playback", f.ID), strings.Replace(body, `"subtitleFormats"`, `"separateAudio":true,"subtitleFormats"`, 1), &sep, "Authorization", "Bearer "+tv.Token)
	sbase := srv.URL + strings.TrimSuffix(sep.URL, "master.m3u8")
	code, _, master = get(srv.URL + sep.URL + q)
	if code != 200 || !strings.Contains(master, `#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Audio",CHANNELS="1",DEFAULT=YES,AUTOSELECT=YES,URI="audio.m3u8`+q+`"`) || !strings.Contains(master, `AUDIO="audio"`) {
		t.Fatalf("master with separate audio -> %d:\n%s", code, master)
	}
	code, _, media = get(sbase + "audio.m3u8" + q)
	if code != 200 || !strings.Contains(media, `#EXT-X-MAP:URI="audio-init.mp4`+q+`"`) || !strings.Contains(media, "a00001.m4s"+q) {
		t.Fatalf("audio playlist -> %d:\n%s", code, media)
	}
	if code, ct, b := get(sbase + "audio-init.mp4" + q); code != 200 || ct != "video/mp4" || !strings.Contains(b, "soun") || strings.Contains(b, "vide") {
		t.Errorf("audio init -> %d %s", code, ct)
	}
	if code, _, b := get(sbase + "a00001.m4s" + q); code != 200 || !strings.Contains(b, "moof") {
		t.Errorf("audio segment -> %d (%d bytes)", code, len(b))
	}
	if code, _, b := get(base + "audio.m3u8" + q); code != http.StatusNotFound {
		t.Errorf("audio playlist of a muxed session -> %d: %s", code, b)
	}
}
