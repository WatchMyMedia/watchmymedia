package api

import (
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/probe"
	"watchmymedia/server/internal/probe/probetest"
)

// Level 8's profile end to end on a Dolby Vision 8.1 file (PLAN-HDR-PROFILE.md):
// the server takes hdrTypes, keeps Dolby Vision for dovi8 with the right
// playlist and record, sends the HDR10 base layer without it, and leaves an
// older app's profile as it was.
func TestHDRProfileLevel8(t *testing.T) {
	t.Parallel()
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not installed")
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	mgr := playback.NewManager(ffmpeg, filepath.Join(t.TempDir(), "transcode"), playback.Detect(t.Context(), ffmpeg, "software", log), time.Minute, 8, log)
	t.Cleanup(mgr.StopAll)
	ffprobe := filepath.Join(filepath.Dir(ffmpeg), "ffprobe")
	srv := newTestServerWith(t, func(d *Deps) {
		d.Playback = mgr
		d.Keyframes = playback.NewKeyframes(ffprobe, t.TempDir(), log)
	})
	ctx := t.Context()
	clip := probetest.DolbyVisionClip(t, ffmpeg, probetest.DolbyVision{Profile: 8, Level: 6, Compat: 1, MKV: true})
	path := filepath.Join(t.TempDir(), "Clip (2020).mkv")
	if err := os.Rename(clip, path); err != nil {
		t.Fatal(err)
	}
	info, err := (&probe.Prober{Path: ffprobe}).Probe(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := probe.Encode(info)
	stored := string(raw)
	st, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	eight := int64(8)
	f, err := srv.q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: srv.fileLibrary(t, path), Path: path, Size: st.Size(), Mtime: db.FormatTime(st.ModTime()),
		AddedAt: db.FormatTime(time.Now()), Parsed: "{}", Probe: &stored, Hdr: "dolbyvision", DvProfile: &eight, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	get := func(u string) string {
		t.Helper()
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
		res, err := srv.client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		b, _ := io.ReadAll(res.Body)
		if res.StatusCode != http.StatusOK {
			t.Fatalf("GET %s -> %d", u, res.StatusCode)
		}
		return string(b)
	}
	// play asks for a VOD remux (an Apple device: MP4 only) and returns the
	// decision, the master playlist and the init segment.
	play := func(profile string) (playback.Decision, string, string) {
		t.Helper()
		var d playback.Decision
		body := `{"profile":{"containers":["mp4"],"videoCodecs":["h264","hevc","hevc10"` + profile + `,"audioCodecs":["aac"]},"playlist":"vod"}`
		if res := doJSON(t, srv.client, http.MethodPost, fmt.Sprintf("%s/api/files/%d/playback", srv.URL, f.ID), body, &d); res.StatusCode != http.StatusOK {
			t.Fatalf("playback %s -> %d", profile, res.StatusCode)
		}
		if d.Mode != playback.ModeRemux || d.Playlist != "vod" {
			t.Fatalf("%s: mode %s playlist %s (reasons %q)", profile, d.Mode, d.Playlist, d.Reasons)
		}
		master := get(srv.URL + d.URL)
		return d, master, get(srv.URL + strings.TrimSuffix(d.URL, "master.m3u8") + "init.mp4")
	}

	// A Dolby Vision display with a profile 8 decoder.
	d, master, init := play(`,"dovi8"],"hdr":true,"hdrTypes":["hdr10","hlg","dovi"]`)
	if d.Video.HDR != "dolbyvision" || d.Video.DVProfile != 8 || !strings.Contains(master, `CODECS="hvc1.2.4.L90.B0,mp4a.40.2",SUPPLEMENTAL-CODECS="dvh1.08.06/db1p"`) ||
		!strings.Contains(master, "VIDEO-RANGE=PQ") || !strings.Contains(init, "hvc1") || !strings.Contains(init, "dvvC") {
		t.Errorf("dovi8: video %+v, init has dvvC %v, master:\n%s", d.Video, strings.Contains(init, "dvvC"), master)
	}
	// An HDR10 display: the base layer, without the record.
	d, master, init = play(`],"hdr":true,"hdrTypes":["hdr10"]`)
	if d.Video.HDR != "hdr10" || d.Video.DVProfile != 0 || strings.Contains(master, "SUPPLEMENTAL-CODECS") || !strings.Contains(master, `CODECS="hvc1.2.4.L90.B0,mp4a.40.2"`) ||
		!strings.Contains(master, "VIDEO-RANGE=PQ") || strings.Contains(init, "dvvC") || strings.Contains(init, "dvcC") {
		t.Errorf("hdr10: video %+v, master:\n%s", d.Video, master)
	}
	// An older app's profile, on an HDR screen: as before.
	d, master, init = play(`],"hdr":true`)
	if d.Video.HDR != "dolbyvision" || d.Video.DVProfile != 8 || strings.Contains(master, "SUPPLEMENTAL-CODECS") || !strings.Contains(master, `CODECS="hvc1.2.4.L90.B0,mp4a.40.2"`) ||
		strings.Contains(init, "dvvC") {
		t.Errorf("older profile: video %+v, master:\n%s", d.Video, master)
	}

	// The plans list takes the same profile. An SDR screen gets a tone
	// mapped transcode even with hevc10; an older profile with hdr false too.
	var plans PlanList
	for _, profile := range []string{
		`{"containers":["mkv"],"videoCodecs":["h264","hevc","hevc10"],"audioCodecs":["aac"],"hdr":false,"hdrTypes":[]}`,
		`{"containers":["mkv"],"videoCodecs":["h264","hevc","hevc10"],"audioCodecs":["aac"],"hdr":false}`,
	} {
		body := fmt.Sprintf(`{"profile":%s,"fileIds":[%d]}`, profile, f.ID)
		if res := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/playback/plans", body, &plans); res.StatusCode != http.StatusOK || len(plans.Items) != 1 {
			t.Fatalf("plans %s -> %d", profile, res.StatusCode)
		}
		p := plans.Items[0]
		if p.Mode != playback.ModeTranscode || !p.Video.ToneMap || p.Video.HDR != "hdr10" ||
			!slices.ContainsFunc(p.Reasons, func(r string) bool { return strings.HasPrefix(r, "the screen cannot show") }) {
			t.Errorf("%s: plan %+v", profile, p)
		}
	}
	// Fields the profile does not have are still refused.
	if res := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/playback/plans",
		fmt.Sprintf(`{"profile":{"videoCodecs":["h264"],"hdrKinds":["hdr10"]},"fileIds":[%d]}`, f.ID), nil); res.StatusCode != http.StatusUnprocessableEntity {
		t.Errorf("unknown profile field -> %d", res.StatusCode)
	}
	// The capabilities say whether profile 5 converts.
	var caps map[string]any
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/playback/capabilities", "", &caps)
	if _, ok := caps["doviToneMap"]; !ok {
		t.Errorf("capabilities = %v", caps)
	}
	var server ServerInfo
	doJSON(t, nil, http.MethodGet, srv.URL+"/api/server", "", &server)
	if server.APILevel < 8 {
		t.Errorf("api level %d", server.APILevel)
	}
}

// Activity's stream side shows what the screen gets: a Dolby Vision file's
// base layer when that is sent, SDR once tone mapped.
func TestStreamInfoShowsWhatTheScreenGets(t *testing.T) {
	t.Parallel()
	info := &probe.MediaInfo{Video: []probe.VideoStream{{Codec: "hevc", Width: 3840, Height: 2160, BitDepth: 10, HDR: "dolbyvision", DVProfile: 8, DVCompat: 1, DVLevel: 6, Detailed: true}},
		Audio: []probe.AudioStream{{Index: 1, Codec: "aac", Channels: 2}}}
	for _, c := range []struct {
		profile playback.Profile
		hdr     string
	}{
		{playback.Profile{Containers: []string{"mkv"}, VideoCodecs: []string{"hevc10"}, AudioCodecs: []string{"aac"}, HDR: true, HDRTypes: []string{"hdr10"}}, "hdr10"},
		{playback.Profile{Containers: []string{"mkv"}, VideoCodecs: []string{"hevc10", "dovi8"}, AudioCodecs: []string{"aac"}, HDR: true, HDRTypes: []string{"hdr10", "dovi"}}, "dolbyvision"},
		{playback.Profile{Containers: []string{"mkv"}, VideoCodecs: []string{"h264", "hevc10"}, AudioCodecs: []string{"aac"}, HDRTypes: []string{}}, ""},
	} {
		req := playback.Request{Profile: c.profile}
		d := playback.Decide(info, ".mkv", req, nil, playback.Capabilities{})
		s := streamInfo(info, ".mkv", d, req)
		if s.Source.HDR != "dolbyvision" || s.Stream.HDR != c.hdr {
			t.Errorf("%v: source %q stream %q, want %q (mode %s)", c.profile.HDRTypes, s.Source.HDR, s.Stream.HDR, c.hdr, d.Mode)
		}
	}
}
