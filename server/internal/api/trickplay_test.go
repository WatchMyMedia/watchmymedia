package api

import (
	"bytes"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
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
	"watchmymedia/server/internal/settings"
	"watchmymedia/server/internal/trickplay"
)

// newTrickplayServer is a test server that serves seek thumbnails from its
// data folder; nothing makes them, so tests put them in place.
func newTrickplayServer(t *testing.T) (*testEnv, *trickplay.Generator) {
	t.Helper()
	var g *trickplay.Generator
	srv := newTestServerWith(t, func(d *Deps) {
		g = trickplay.New(d.Queries, d.Conn, d.Bus, slog.New(slog.DiscardHandler), trickplay.Options{Dir: filepath.Join(d.DataDir, "trickplay"), Enabled: true})
		d.Trickplay = g
	})
	return srv, g
}

// madeThumbnails puts a done set of three sheets in place for f, as the
// job leaves them, and returns its version.
func madeThumbnails(t *testing.T, srv *testEnv, g *trickplay.Generator, f db.MediaFile) string {
	t.Helper()
	version := trickplay.Version(f.Size, f.Mtime, trickplay.ToneSDR)
	dir := trickplay.FileDir(g.Dir(), f.ID, version)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	for s := range 3 {
		if err := os.WriteFile(filepath.Join(dir, trickplay.SheetName(s)), []byte(fmt.Sprintf("jpeg %d", s)), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(dir, "manifest.json"), []byte("{}"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := srv.q.UpsertTrickplay(t.Context(), db.UpsertTrickplayParams{MediaFileID: f.ID, Version: version, AlgoVersion: trickplay.AlgoVersion,
		FileSize: f.Size, FileMtime: f.Mtime, Status: "done", IntervalMs: 10_000, Width: 320, Height: 180, Columns: 10, Rows: 10,
		Tiles: 60, Sheets: 1, Bytes: 6000}); err != nil {
		t.Fatal(err)
	}
	return version
}

// The decision and GET /files/{id}/trickplay carry the manifest only once
// the thumbnails are made for the file as it is; sheets need a signed-in
// user or a media token, are cached for good, and an old version's are 404.
func TestTrickplayManifestAndSheets(t *testing.T) {
	t.Parallel()
	srv, g := newTrickplayServer(t)
	f := securityTestFile(t, srv)
	base := srv.URL + "/api"
	manifestURL := fmt.Sprintf("%s/files/%d/trickplay", base, f.ID)

	if d := securityTestPlay(t, srv.client, srv.URL, f, "Laptop"); d.Trickplay != nil {
		t.Fatalf("decision before any thumbnails: %+v", d.Trickplay)
	}
	if code := doJSON(t, srv.client, http.MethodGet, manifestURL, "", nil).StatusCode; code != http.StatusNotFound {
		t.Fatalf("manifest before any thumbnails -> %d", code)
	}

	version := madeThumbnails(t, srv, g, f)
	wantURL := fmt.Sprintf("/api/files/%d/trickplay/%s/{sheet}.jpg", f.ID, version)
	d := securityTestPlay(t, srv.client, srv.URL, f, "Laptop")
	if m := d.Trickplay; m == nil || m.URL != wantURL || m.Count != 60 || m.Sheets != 1 || m.Width != 320 || m.Height != 180 ||
		m.Columns != 10 || m.Rows != 10 || m.IntervalMs != 10_000 {
		t.Fatalf("decision's thumbnails %+v, want url %s", d.Trickplay, wantURL)
	}
	var m playback.Trickplay
	if code := doJSON(t, srv.client, http.MethodGet, manifestURL, "", &m).StatusCode; code != 200 || m != *d.Trickplay {
		t.Fatalf("manifest -> %d %+v", code, m)
	}

	sheet := srv.URL + strings.Replace(wantURL, "{sheet}", "0", 1)
	r := doJSON(t, srv.client, http.MethodGet, sheet, "", nil)
	if r.StatusCode != 200 || string(r.Body) != "jpeg 0" || r.Header.Get("Content-Type") != "image/jpeg" ||
		r.Header.Get("Cache-Control") != "private, max-age=31536000, immutable" || r.Header.Get("ETag") == "" {
		t.Fatalf("sheet -> %d %q %v", r.StatusCode, r.Body, r.Header)
	}
	if code := doJSON(t, srv.client, http.MethodGet, sheet, "", nil, "If-None-Match", r.Header.Get("ETag")).StatusCode; code != http.StatusNotModified {
		t.Errorf("sheet with its ETag -> %d, want 304", code)
	}
	if code := doJSON(t, srv.client, http.MethodHead, sheet, "", nil).StatusCode; code != 200 {
		t.Errorf("HEAD sheet -> %d", code)
	}
	if code := doJSON(t, nil, http.MethodGet, sheet, "", nil).StatusCode; code != http.StatusUnauthorized {
		t.Errorf("sheet signed out -> %d, want 401", code)
	}
	var mt MediaToken
	doJSON(t, srv.client, http.MethodPost, base+"/auth/media-token", "", &mt)
	if r := doJSON(t, nil, http.MethodGet, sheet+"?access_token="+mt.Token, "", nil); r.StatusCode != 200 || string(r.Body) != "jpeg 0" {
		t.Errorf("sheet with a media token -> %d", r.StatusCode)
	}
	member, _ := securityTestUser(t, srv, "viewer")
	if code := doJSON(t, member, http.MethodGet, sheet, "", nil).StatusCode; code != 200 {
		t.Errorf("sheet for a member -> %d", code)
	}
	for name, u := range map[string]string{
		"an old version":         fmt.Sprintf("/api/files/%d/trickplay/%s/0.jpg", f.ID, trickplay.Version(1, "old", trickplay.ToneSDR)),
		"a sheet it has not got": strings.Replace(wantURL, "{sheet}", "1", 1),
		"not a jpg":              strings.Replace(wantURL, "{sheet}.jpg", "0.png", 1),
		"another file":           fmt.Sprintf("/api/files/%d/trickplay/%s/0.jpg", f.ID+1000, version),
		"not a version":          fmt.Sprintf("/api/files/%d/trickplay/..%%2F..%%2F/0.jpg", f.ID),
	} {
		if code := doJSON(t, srv.client, http.MethodGet, srv.URL+u, "", nil).StatusCode; code != http.StatusNotFound {
			t.Errorf("%s -> %d, want 404", name, code)
		}
	}

	// Once the file changes, its thumbnails are for another version: the
	// decision no longer carries them until they are made again.
	if _, err := srv.conn.ExecContext(t.Context(), `UPDATE media_files SET size = 17 WHERE id = ?`, f.ID); err != nil {
		t.Fatal(err)
	}
	if d := securityTestPlay(t, srv.client, srv.URL, f, "Laptop"); d.Trickplay != nil {
		t.Errorf("decision for a changed file carries %+v", d.Trickplay)
	}
	if code := doJSON(t, srv.client, http.MethodGet, manifestURL, "", nil).StatusCode; code != http.StatusNotFound {
		t.Errorf("manifest for a changed file -> %d", code)
	}
}

// Settings shows the job's progress and switch; turning it off is kept
// and stops the job; Delete thumbnails is for admins.
func TestTrickplaySettings(t *testing.T) {
	t.Parallel()
	srv, g := newTrickplayServer(t)
	f := securityTestFile(t, srv)
	// The scanner sets the video codec from the probe; files without one
	// get no thumbnails.
	if _, err := srv.conn.ExecContext(t.Context(), `UPDATE media_files SET video_codec = 'h264' WHERE id = ?`, f.ID); err != nil {
		t.Fatal(err)
	}
	madeThumbnails(t, srv, g, f)
	base := srv.URL + "/api"

	var s Settings
	if code := doJSON(t, srv.client, http.MethodGet, base+"/settings", "", &s).StatusCode; code != 200 || s.Trickplay == nil {
		t.Fatalf("settings -> %d %+v", code, s.Trickplay)
	}
	if tp := s.Trickplay; !tp.Enabled || tp.Files != 1 || tp.Done != 1 || tp.Pending != 0 || tp.Bytes != 6000 {
		t.Errorf("settings' thumbnails %+v", tp)
	}
	if code := doJSON(t, srv.client, http.MethodPut, base+"/settings", `{"trickplayEnabled":false}`, &s).StatusCode; code != 200 || s.Trickplay.Enabled || g.Enabled() {
		t.Fatalf("turning off -> %d %+v (generator on: %v)", code, s.Trickplay, g.Enabled())
	}
	if v, _ := settings.New(srv.q).Get(t.Context(), settings.KeyTrickplayEnabled, ""); v != "0" {
		t.Errorf("stored setting %q, want 0", v)
	}
	if !TrickplayEnabled(t.Context(), settings.New(newTestServer(t).q)) {
		t.Error("seek thumbnails are not on by default")
	}

	var st trickplay.Status
	if code := doJSON(t, srv.client, http.MethodGet, base+"/trickplay", "", &st).StatusCode; code != 200 || st.Enabled || st.Done != 1 {
		t.Errorf("status -> %d %+v", code, st)
	}
	member, _ := securityTestUser(t, srv, "viewer")
	for _, method := range []string{http.MethodGet, http.MethodDelete} {
		if code := doJSON(t, member, method, base+"/trickplay", "", nil).StatusCode; code != http.StatusForbidden {
			t.Errorf("%s /trickplay as a member -> %d, want 403", method, code)
		}
	}
	if code := doJSON(t, srv.client, http.MethodDelete, base+"/trickplay", "", nil).StatusCode; code != http.StatusNoContent {
		t.Fatalf("delete -> %d", code)
	}
	if _, err := srv.q.GetTrickplay(t.Context(), f.ID); err == nil {
		t.Error("a row outlived Delete thumbnails")
	}
	if entries, _ := os.ReadDir(g.Dir()); len(entries) != 0 {
		t.Errorf("Delete thumbnails left %d entries", len(entries))
	}
	doJSON(t, srv.client, http.MethodGet, base+"/trickplay", "", &st)
	if st.Bytes != 0 || st.Done != 0 || st.Pending != 1 {
		t.Errorf("status after delete %+v", st)
	}
}

// The event a made file sends is in the event stream's schema, so streams
// can send it.
func TestTrickplayReadyEventIsDocumented(t *testing.T) {
	t.Parallel()
	_, api := New(Deps{Version: "test"})
	b, err := json.Marshal(api.OpenAPI())
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{`"trickplay.ready"`, `"TrickplayReady"`, `"TrickplayStatus"`} {
		if !strings.Contains(string(b), want) {
			t.Errorf("the OpenAPI document lacks %s", want)
		}
	}
}

// A VOD play offers the file's seek thumbnails as an I-frame stream once
// they are made (PLAN-TRICKPLAY.md §7.3): the master playlist lists it, its
// playlist lists the track by byte range, and the track, made on the first
// ask, answers ranges; all with the player's media token. A server whose
// ffmpeg has no libx264 offers none.
func TestVODIFrameStream(t *testing.T) {
	t.Parallel()
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not installed")
	}
	if b, _ := exec.Command(ffmpeg, "-hide_banner", "-encoders").Output(); !strings.Contains(string(b), " libx264 ") { //nolint:gosec // G204: the test's own ffmpeg
		t.Skip("no libx264")
	}
	log := slog.New(slog.DiscardHandler)
	mgr := playback.NewManager(ffmpeg, filepath.Join(t.TempDir(), "transcode"), playback.Capabilities{Encoder: "libx264", Encoders: []string{"libx264"}, HWAccel: "software", MaxHeight: 1080}, time.Minute, 8, log)
	t.Cleanup(mgr.StopAll)
	var g *trickplay.Generator
	srv := newTestServerWith(t, func(d *Deps) {
		d.Playback = mgr
		d.Keyframes = playback.NewKeyframes(filepath.Join(filepath.Dir(ffmpeg), "ffprobe"), t.TempDir(), log)
		// A scan writes its result into that folder after it answers: wait for
		// it before the folder goes.
		t.Cleanup(d.Keyframes.Close)
		g = trickplay.New(d.Queries, d.Conn, d.Bus, log, trickplay.Options{FFmpeg: ffmpeg, Dir: filepath.Join(d.DataDir, "trickplay"), Enabled: true})
		d.Trickplay = g
	})
	ctx := t.Context()
	path := filepath.Join(t.TempDir(), "Clip (2020).mkv")
	if b, err := exec.Command(ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=24:duration=20", //nolint:gosec // G204: the test's own ffmpeg
		"-c:v", "mpeg4", "-g", "1000", "-force_key_frames", "0,5,10,15", path).CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg: %v\n%s", err, b)
	}
	st, _ := os.Stat(path)
	probeJSON := `{"container":"matroska","durationMs":20000,"bitRate":300000,"video":[{"index":0,"codec":"mpeg4","width":64,"height":64,"frameRate":24}]}`
	f, err := srv.q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: srv.fileLibrary(t, path), Path: path, Size: st.Size(), Mtime: db.FormatTime(st.ModTime()), AddedAt: db.FormatTime(time.Now()),
		Parsed: "{}", Probe: &probeJSON, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}

	tv := deviceLogin(t, srv.URL, "admin", "password1", "atv-1", "Living room Apple TV")
	var mt MediaToken
	doJSON(t, nil, http.MethodPost, srv.URL+"/api/auth/media-token", "", &mt, "Authorization", "Bearer "+tv.Token)
	q := "?access_token=" + mt.Token
	get := func(u string, header ...string) (int, http.Header, string) {
		t.Helper()
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
		for i := 0; i+1 < len(header); i += 2 {
			req.Header.Set(header[i], header[i+1])
		}
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		b, _ := io.ReadAll(res.Body)
		return res.StatusCode, res.Header, string(b)
	}
	play := func() string {
		t.Helper()
		var d playback.Decision
		body := `{"profile":{"name":"tvos","containers":["mp4"],"videoCodecs":["mpeg4"],"audioCodecs":["aac"],"subtitleFormats":["webvtt"]},"playlist":"vod",` +
			`"client":{"deviceId":"atv-1","product":"WatchMyMedia for Apple TV","platform":"tvos"}}`
		if res := doJSON(t, nil, http.MethodPost, srv.URL+fmt.Sprintf("/api/files/%d/playback", f.ID), body, &d, "Authorization", "Bearer "+tv.Token); res.StatusCode != 200 || d.Playlist != "vod" {
			t.Fatalf("playback -> %d, playlist %q", res.StatusCode, d.Playlist)
		}
		code, _, master := get(srv.URL + d.URL + q)
		if code != 200 {
			t.Fatalf("master -> %d", code)
		}
		return srv.URL + strings.TrimSuffix(d.URL, "master.m3u8") + "\n" + master
	}

	base, master, _ := strings.Cut(play(), "\n")
	if strings.Contains(master, "I-FRAME") {
		t.Fatalf("master before any thumbnails:\n%s", master)
	}
	if code, _, _ := get(base + "iframes.m3u8" + q); code != http.StatusNotFound {
		t.Errorf("iframes.m3u8 before any thumbnails -> %d", code)
	}

	// Two tiles of 64 × 64 on one sheet, as the job leaves them.
	version := trickplay.Version(f.Size, f.Mtime, trickplay.ToneSDR)
	dir := trickplay.FileDir(g.Dir(), f.ID, version)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	sheet := image.NewGray(image.Rect(0, 0, 128, 64))
	for x := range 128 {
		for y := range 64 {
			sheet.SetGray(x, y, color.Gray{Y: uint8(60 + x)})
		}
	}
	var jb bytes.Buffer
	_ = jpeg.Encode(&jb, sheet, nil)
	if err := os.WriteFile(filepath.Join(dir, trickplay.SheetName(0)), jb.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "manifest.json"), []byte("{}"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := srv.q.UpsertTrickplay(ctx, db.UpsertTrickplayParams{MediaFileID: f.ID, Version: version, AlgoVersion: trickplay.AlgoVersion,
		FileSize: f.Size, FileMtime: f.Mtime, Status: "done", IntervalMs: 10_000, Width: 64, Height: 64, Columns: 2, Rows: 1,
		Tiles: 2, Sheets: 1, Bytes: int64(jb.Len())}); err != nil {
		t.Fatal(err)
	}

	base, master, _ = strings.Cut(play(), "\n")
	if !strings.Contains(master, `,CODECS="avc1.4D401E",RESOLUTION=64x64,VIDEO-RANGE=SDR,URI="iframes.m3u8`+q+`"`) || !strings.Contains(master, "#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=") {
		t.Fatalf("master with thumbnails:\n%s", master)
	}
	code, h, list := get(base + "iframes.m3u8" + q)
	if code != 200 || h.Get("Content-Type") != "application/vnd.apple.mpegurl" || !strings.Contains(list, "#EXT-X-I-FRAMES-ONLY") ||
		!strings.Contains(list, `#EXT-X-MAP:URI="iframes.mp4`+q+`",BYTERANGE="`) || strings.Count(list, "#EXTINF:10.000,") != 2 || strings.Count(list, "\niframes.mp4"+q+"\n") != 2 {
		t.Fatalf("iframes.m3u8 -> %d:\n%s", code, list)
	}
	// The second frame by its byte range: a fragment of its own.
	i := strings.LastIndex(list, "#EXT-X-BYTERANGE:")
	var size, off int
	if _, err := fmt.Sscanf(list[i:], "#EXT-X-BYTERANGE:%d@%d", &size, &off); err != nil {
		t.Fatal(err)
	}
	code, h, frag := get(base+"iframes.mp4"+q, "Range", fmt.Sprintf("bytes=%d-%d", off, off+size-1))
	if code != http.StatusPartialContent || h.Get("Content-Type") != "video/mp4" || len(frag) != size || frag[4:8] != "moof" {
		t.Errorf("a frame by range -> %d %s, %d bytes (want %d)", code, h.Get("Content-Type"), len(frag), size)
	}
	if code, _, _ := get(base + "iframes.mp4"); code != http.StatusUnauthorized {
		t.Errorf("the track without a token -> %d", code)
	}

	// No libx264, no I-frame stream.
	none := playback.NewManager(ffmpeg, t.TempDir(), playback.Capabilities{Encoder: "h264_videotoolbox", Encoders: []string{"h264_videotoolbox"}}, time.Minute, 1, log)
	if s := iframeStream(ctx, Deps{Trickplay: g, Playback: none}, f, 20_000); s != nil {
		t.Errorf("a server without libx264 offers %+v", s)
	}
}
