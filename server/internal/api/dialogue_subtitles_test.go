package api

import (
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/probe"
)

// The sound settings and the subtitle size are preferences like the
// others: partial updates, checked whole, kept per person.
func TestSoundAndSubtitleSizePreferences(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	url := srv.URL + "/api/me/preferences"
	var p Preferences
	doJSON(t, srv.client, http.MethodGet, url, "", &p)
	// A size nobody chose is left out, so a TV that kept its own size before
	// can tell it apart from a person who chose Normal.
	if p.Audio.DialogueBoost != "off" || p.Audio.ReduceLoudSounds != "off" || p.Subtitles.Size != "" {
		t.Fatalf("defaults = audio %+v, subtitles %+v", p.Audio, p.Subtitles)
	}
	var raw struct {
		Subtitles map[string]any `json:"subtitles"`
	}
	doJSON(t, srv.client, http.MethodGet, url, "", &raw)
	if _, ok := raw.Subtitles["size"]; ok {
		t.Fatalf("an unchosen size is sent: %v", raw.Subtitles)
	}
	if code := doJSON(t, srv.client, http.MethodPut, url, `{"audio":{"dialogueBoost":"high"}}`, &p).StatusCode; code != 200 ||
		p.Audio.DialogueBoost != "high" || p.Audio.ReduceLoudSounds != "off" {
		t.Fatalf("boost -> %d %+v", code, p.Audio)
	}
	doJSON(t, srv.client, http.MethodPut, url, `{"audio":{"reduceLoudSounds":"medium","languages":["en"]},"subtitles":{"size":"largest"}}`, &p)
	if p.Audio.DialogueBoost != "high" || p.Audio.ReduceLoudSounds != "medium" || p.Subtitles.Size != "largest" || len(p.Audio.Languages) != 1 {
		t.Fatalf("second patch = %+v %+v", p.Audio, p.Subtitles)
	}
	// An older app saves the whole subtitle object it knows: the size stays.
	doJSON(t, srv.client, http.MethodPut, url, `{"subtitles":{"languages":["en"],"mode":"on","automaticDownload":false}}`, &p)
	if p.Subtitles.Size != "largest" || p.Audio.DialogueBoost != "high" {
		t.Fatalf("older app's save = %+v %+v", p.Audio, p.Subtitles)
	}
	for _, bad := range []string{
		`{"autoplayNext":false,"audio":{"dialogueBoost":"loud"}}`,
		`{"autoplayNext":false,"audio":{"reduceLoudSounds":"High"}}`,
		`{"autoplayNext":false,"subtitles":{"size":"huge"}}`,
	} {
		if code := doJSON(t, srv.client, http.MethodPut, url, bad, nil).StatusCode; code != http.StatusUnprocessableEntity {
			t.Errorf("invalid patch %s -> %d", bad, code)
		}
	}
	doJSON(t, srv.client, http.MethodGet, url, "", &p)
	if !p.AutoplayNext || p.Audio.DialogueBoost != "high" || p.Subtitles.Size != "largest" {
		t.Fatalf("invalid patch partly changed preferences: %+v", p)
	}
	doJSON(t, srv.client, http.MethodPut, url, `{"audio":{"dialogueBoost":"off"}}`, &p)
	if p.Audio.DialogueBoost != "off" || p.Audio.ReduceLoudSounds != "medium" {
		t.Fatalf("off = %+v", p.Audio)
	}
	other, _ := securityTestUser(t, srv, "sam")
	var theirs Preferences // a fresh value: decoding leaves fields the reply omits as they were
	doJSON(t, other, http.MethodGet, url, "", &theirs)
	if theirs.Audio.ReduceLoudSounds != "off" || theirs.Subtitles.Size != "" {
		t.Fatalf("another person's = %+v %+v", theirs.Audio, theirs.Subtitles)
	}
	doJSON(t, other, http.MethodPut, url, `{"subtitles":{"size":"normal"}}`, &theirs)
	if theirs.Subtitles.Size != "normal" {
		t.Fatalf("a chosen Normal = %+v", theirs.Subtitles)
	}
}

// What starting a play would do follows the person's sound settings: a
// file that plays directly has its sound converted while either is on.
func TestSoundSettingsConvertCopiedSound(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	file := languageFile(t, srv, []probe.AudioStream{{Index: 1, Codec: "aac", Channels: 2, ChannelLayout: "stereo", Language: "eng", Default: true}}, nil)
	plan := func(client *http.Client) PlaybackPlan {
		t.Helper()
		var plans PlanList
		doJSON(t, client, http.MethodPost, srv.URL+"/api/playback/plans",
			fmt.Sprintf(`{"profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"]},"fileIds":[%d]}`, file.ID), &plans)
		if len(plans.Items) != 1 {
			t.Fatalf("plans = %+v", plans.Items)
		}
		return plans.Items[0]
	}
	if p := plan(srv.client); p.Mode != playback.ModeDirect || p.Audio.Effects.On() {
		t.Fatalf("both off: %+v", p)
	}
	doJSON(t, srv.client, http.MethodPut, srv.URL+"/api/me/preferences", `{"audio":{"dialogueBoost":"medium","reduceLoudSounds":"high"}}`, nil)
	p := plan(srv.client)
	if p.Mode != playback.ModeAudio || !p.Video.Copy || p.Audio.Copy || p.Audio.Effects != (playback.AudioEffects{Dialogue: "medium", Loudness: "high"}) {
		t.Fatalf("with both on: %+v", p)
	}
	for _, want := range []string{"dialogue boosted (medium)", "loud sounds reduced (high)"} {
		if !strings.Contains(strings.Join(p.Reasons, "|"), want) {
			t.Errorf("reasons %q lack %q", p.Reasons, want)
		}
	}
	// Another person's plays are their own.
	other, _ := securityTestUser(t, srv, "alex")
	if p := plan(other); p.Mode != playback.ModeDirect {
		t.Fatalf("another person: %+v", p)
	}
	// A download keeps the film's own sound.
	row, err := srv.q.GetMediaFile(t.Context(), file.ID)
	if err != nil {
		t.Fatal(err)
	}
	info, _ := probe.Parse(*row.Probe)
	d, _ := offlineDecide(t.Context(), Deps{}, row, info, playback.Request{Profile: playback.Profile{Containers: []string{"mp4"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}},
		Effects: playback.AudioEffects{Dialogue: "high"}})
	if d.Mode != playback.ModeDirect || d.Audio.Effects.On() {
		t.Fatalf("download: %s %+v", d.Mode, d.Audio)
	}
}

// timingFile is a file with an embedded text track, an image track, and a
// subtitle file beside it.
func timingFile(t *testing.T, srv *testEnv) db.MediaFile {
	t.Helper()
	file := languageFile(t, srv, []probe.AudioStream{{Index: 1, Codec: "aac", Channels: 2, Language: "eng", Default: true}},
		[]probe.SubtitleStream{{Index: 2, Codec: "subrip", Language: "eng"}, {Index: 3, Codec: "hdmv_pgs_subtitle", Language: "eng", Image: true}})
	sidecar := strings.TrimSuffix(file.Path, ".mp4") + ".en.srt"
	if err := os.WriteFile(sidecar, []byte("1\n00:00:01,000 --> 00:00:02,000\nThe Quiet Station\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	return file
}

func TestSubtitleTimingRoute(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	file := timingFile(t, srv)
	timing := func(c *http.Client, method, track, body string) (SubtitleTiming, int) {
		t.Helper()
		var out SubtitleTiming
		code := doJSON(t, c, method, fmt.Sprintf("%s/api/files/%d/subtitles/%s/timing", srv.URL, file.ID, track), body, &out).StatusCode
		return out, code
	}
	decision := func(c *http.Client) map[string]playback.SubtitleTrack {
		t.Helper()
		var d playback.Decision
		if code := doJSON(t, c, http.MethodPost, fmt.Sprintf("%s/api/files/%d/playback", srv.URL, file.ID), subtitleProfileBody(""), &d).StatusCode; code != 200 {
			t.Fatalf("playback -> %d", code)
		}
		tracks := map[string]playback.SubtitleTrack{}
		for _, s := range d.Subtitles {
			tracks[s.ID] = s
		}
		return tracks
	}
	sam, _ := securityTestUser(t, srv, "sam")
	alex, _ := securityTestUser(t, srv, "alex")

	// Bounds and tracks.
	for _, c := range []struct {
		track, body string
		want        int
	}{
		{"s2", `{"timingMs":60001}`, http.StatusUnprocessableEntity},
		{"s2", `{"timingMs":-60001}`, http.StatusUnprocessableEntity},
		{"s9", `{"timingMs":400}`, http.StatusNotFound},
		{"x1", `{"timingMs":400}`, http.StatusNotFound},
		{"d1", `{"timingMs":400}`, http.StatusNotFound},
		{"s02", `{"timingMs":400}`, http.StatusNotFound},
		{"q2", `{"timingMs":400}`, http.StatusNotFound},
	} {
		if _, code := timing(sam, http.MethodPut, c.track, c.body); code != c.want {
			t.Errorf("%s %s -> %d, want %d", c.track, c.body, code, c.want)
		}
	}
	if got, code := timing(sam, http.MethodPut, "s2", `{"timingMs":60000}`); code != 200 || got != (SubtitleTiming{TimingMs: 60000}) {
		t.Errorf("the limit -> %d %+v", code, got)
	}
	// A person's own timing, which nobody else gets.
	if got, code := timing(sam, http.MethodPut, "s2", `{"timingMs":-1200}`); code != 200 || got != (SubtitleTiming{TimingMs: -1200}) {
		t.Fatalf("own -> %d %+v", code, got)
	}
	if s := decision(sam)["s2"]; s.TimingMs != -1200 || s.TimingShared || s.TimingApplied {
		t.Errorf("Sam's decision: %+v", s)
	}
	if s := decision(alex)["s2"]; s.TimingMs != 0 || s.TimingShared {
		t.Errorf("Alex's decision: %+v", s)
	}
	// Only admins save for everyone.
	if _, code := timing(sam, http.MethodPut, "s2", `{"timingMs":800,"everyone":true}`); code != http.StatusForbidden {
		t.Errorf("a member saving for everyone -> %d", code)
	}
	// The admin's own timing goes when they save for everyone; everyone
	// without one of their own gets it, and Sam's own still wins.
	timing(srv.client, http.MethodPut, "s2", `{"timingMs":300}`)
	if got, code := timing(srv.client, http.MethodPut, "s2", `{"timingMs":800,"everyone":true}`); code != 200 || got != (SubtitleTiming{TimingMs: 800, TimingShared: true}) {
		t.Fatalf("for everyone -> %d %+v", code, got)
	}
	if s := decision(srv.client)["s2"]; s.TimingMs != 800 || !s.TimingShared {
		t.Errorf("the admin's decision: %+v", s)
	}
	if s := decision(alex)["s2"]; s.TimingMs != 800 || !s.TimingShared {
		t.Errorf("Alex's decision: %+v", s)
	}
	if s := decision(sam)["s2"]; s.TimingMs != -1200 || s.TimingShared {
		t.Errorf("Sam's own should win: %+v", s)
	}
	// Alex chooses in step over the shared timing: an own 0.
	if got, code := timing(alex, http.MethodPut, "s2", `{"timingMs":0}`); code != 200 || got != (SubtitleTiming{}) {
		t.Fatalf("own 0 over shared -> %d %+v", code, got)
	}
	if s := decision(alex)["s2"]; s.TimingMs != 0 || s.TimingShared {
		t.Errorf("Alex in step: %+v", s)
	}
	// Reset removes Sam's own and goes back to the shared one.
	if got, code := timing(sam, http.MethodDelete, "s2", ""); code != 200 || got != (SubtitleTiming{TimingMs: 800, TimingShared: true}) {
		t.Fatalf("reset -> %d %+v", code, got)
	}
	// Saving 0 for everyone removes the shared timing; Alex's own 0 stays.
	if got, _ := timing(srv.client, http.MethodPut, "s2", `{"timingMs":0,"everyone":true}`); got != (SubtitleTiming{}) {
		t.Errorf("shared removed -> %+v", got)
	}
	if s := decision(sam)["s2"]; s.TimingMs != 0 || s.TimingShared {
		t.Errorf("Sam with nothing saved: %+v", s)
	}
	if got, _ := timing(sam, http.MethodDelete, "s2", ""); got != (SubtitleTiming{}) {
		t.Errorf("reset with nothing saved -> %+v", got)
	}

	// A subtitle file beside the video is keyed by its name: another file
	// beside it renumbers the tracks, and the timing follows its file.
	timing(sam, http.MethodPut, "x0", `{"timingMs":2500}`)
	if s := decision(sam)["x0"]; s.TimingMs != 2500 {
		t.Errorf("sidecar: %+v", s)
	}
	if err := os.WriteFile(strings.TrimSuffix(file.Path, ".mp4")+".de.srt", []byte("1\n00:00:01,000 --> 00:00:02,000\nDer Hafen\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	tracks := decision(sam)
	if tracks["x0"].TimingMs != 0 || tracks["x1"].TimingMs != 2500 {
		t.Errorf("after another file beside it: x0 %+v, x1 %+v", tracks["x0"], tracks["x1"])
	}

	// A file replaced by one of another size has its streams elsewhere: its
	// timings are no longer read.
	timing(sam, http.MethodPut, "s3", `{"timingMs":700}`)
	if _, err := srv.conn.ExecContext(t.Context(), "UPDATE media_files SET size = size + 1 WHERE id = ?", file.ID); err != nil {
		t.Fatal(err)
	}
	if tracks := decision(sam); tracks["s3"].TimingMs != 0 || tracks["x1"].TimingMs != 0 {
		t.Errorf("a replaced file kept its timings: %+v %+v", tracks["s3"], tracks["x1"])
	}
	if got, _ := timing(sam, http.MethodPut, "s3", `{"timingMs":900}`); got.TimingMs != 900 {
		t.Errorf("saving again -> %+v", got)
	}
}

// A burned-in track's timing is applied in the conversion: an image track
// by the session's offset, a text track by a copy with its cues moved.
func TestBurnInTiming(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	video := filepath.Join(dir, "The Long Harbour.mkv")
	sidecar := filepath.Join(dir, "The Long Harbour.en.srt")
	if err := os.WriteFile(sidecar, []byte("1\n00:00:01,000 --> 00:00:02,000\nThe Long Harbour\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	sidecars := playback.FindSidecars(video)
	row := db.MediaFile{ID: 7, Path: video}
	image := playback.SubtitleTrack{ID: "s3", Format: "image", BurnInOnly: true, TimingMs: 1500}
	d := playback.Decision{Subtitles: []playback.SubtitleTrack{image}}
	d.BurnIn = &d.Subtitles[0]
	spec := playback.Spec{BurnIn: 3, BurnInImage: true}
	withBurnInTiming(t.Context(), Deps{}, row, sidecars, playback.Capabilities{Overlay: true}, &d, &spec)
	if spec.BurnInTimingMs != 1500 || !d.Subtitles[0].TimingApplied || !d.BurnIn.TimingApplied {
		t.Errorf("image: spec %+v, track %+v", spec, d.Subtitles[0])
	}
	// Without the overlay filter nothing is burned in, so nothing applied.
	d = playback.Decision{Subtitles: []playback.SubtitleTrack{image}}
	d.BurnIn = &d.Subtitles[0]
	spec = playback.Spec{BurnIn: 3, BurnInImage: true}
	withBurnInTiming(t.Context(), Deps{}, row, sidecars, playback.Capabilities{}, &d, &spec)
	if spec.BurnInTimingMs != 0 || d.BurnIn.TimingApplied {
		t.Errorf("no overlay: spec %+v, track %+v", spec, d.BurnIn)
	}

	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not installed")
	}
	subs := playback.NewSubtitles("ffmpeg", filepath.Join(t.TempDir(), "subtitles"))
	defer subs.Close()
	text := playback.SubtitleTrack{ID: "x0", Codec: "subrip", Format: "webvtt", External: true, TimingMs: -400}
	d = playback.Decision{Subtitles: []playback.SubtitleTrack{text}}
	d.BurnIn = &d.Subtitles[0]
	spec = playback.Spec{BurnIn: -1, BurnInFile: sidecar}
	withBurnInTiming(t.Context(), Deps{Subtitles: subs}, row, sidecars, playback.Capabilities{LibASS: true}, &d, &spec)
	shifted, err := os.ReadFile(spec.BurnInFile)
	if err != nil || spec.BurnInFile == sidecar || !d.BurnIn.TimingApplied {
		t.Fatalf("text: %s %v %+v", spec.BurnInFile, err, d.BurnIn)
	}
	if !strings.Contains(string(shifted), "00:00:00.600 --> 00:00:01.600") {
		t.Errorf("shifted copy: %q", shifted)
	}
}
