package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/probe"
)

func TestAudioPreferencePatch(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	url := srv.URL + "/api/me/preferences"
	var p Preferences
	doJSON(t, srv.client, http.MethodGet, url, "", &p)
	if p.Audio.Languages == nil || len(p.Audio.Languages) != 0 || p.Subtitles.ForeignAudio {
		t.Fatalf("defaults = audio %+v, subtitles %+v", p.Audio, p.Subtitles)
	}
	// Audio languages for someone who never chose a subtitle mode also
	// give them subtitles for audio they don't follow.
	if code := doJSON(t, srv.client, http.MethodPut, url, `{"audio":{"languages":["spa","en","es"]}}`, &p).StatusCode; code != 200 ||
		!slices.Equal(p.Audio.Languages, []string{"es", "en"}) || p.Subtitles.Mode != "forced" || !p.Subtitles.ForeignAudio {
		t.Fatalf("audio patch -> %d %+v %+v", code, p.Audio, p.Subtitles)
	}
	// An older app saves the whole subtitle object it knows, without
	// foreignAudio: the flag stays.
	doJSON(t, srv.client, http.MethodPut, url, `{"subtitles":{"languages":["en"],"mode":"forced","automaticDownload":false}}`, &p)
	if !p.Subtitles.ForeignAudio || !slices.Equal(p.Audio.Languages, []string{"es", "en"}) {
		t.Fatalf("older app's save = %+v %+v", p.Audio, p.Subtitles)
	}
	doJSON(t, srv.client, http.MethodPut, url, `{"subtitles":{"foreignAudio":false}}`, &p)
	if p.Subtitles.ForeignAudio {
		t.Fatal("foreignAudio could not be turned off")
	}
	// Once chosen, saving audio languages again leaves it off.
	doJSON(t, srv.client, http.MethodPut, url, `{"audio":{"languages":["ja"]}}`, &p)
	if p.Subtitles.ForeignAudio || !slices.Equal(p.Audio.Languages, []string{"ja"}) {
		t.Fatalf("second audio save = %+v %+v", p.Audio, p.Subtitles)
	}
	for _, bad := range []string{
		`{"autoplayNext":false,"audio":{"languages":["und"]}}`,
		`{"autoplayNext":false,"audio":{"languages":["not a tag"]}}`,
		`{"autoplayNext":false,"audio":{"languages":[" en"]}}`,
	} {
		if code := doJSON(t, srv.client, http.MethodPut, url, bad, nil).StatusCode; code != http.StatusUnprocessableEntity {
			t.Errorf("invalid patch %s -> %d", bad, code)
		}
	}
	doJSON(t, srv.client, http.MethodGet, url, "", &p)
	if !p.AutoplayNext || !slices.Equal(p.Audio.Languages, []string{"ja"}) {
		t.Fatalf("invalid patch partly changed preferences: %+v", p)
	}
	doJSON(t, srv.client, http.MethodPut, url, `{"audio":{"languages":[]}}`, &p)
	if p.Audio.Languages == nil || len(p.Audio.Languages) != 0 {
		t.Fatalf("clearing = %+v", p.Audio)
	}

	// Someone who already chose a subtitle mode keeps it as it was.
	other, _ := securityTestUser(t, srv, "alex")
	doJSON(t, other, http.MethodPut, url, `{"subtitles":{"mode":"forced"}}`, nil)
	doJSON(t, other, http.MethodPut, url, `{"audio":{"languages":["en"]}}`, &p)
	if p.Subtitles.ForeignAudio {
		t.Fatalf("a chosen mode gained foreignAudio: %+v", p.Subtitles)
	}
	// And a patch that sets the mode itself is that choice.
	third, _ := securityTestUser(t, srv, "jordan")
	doJSON(t, third, http.MethodPut, url, `{"audio":{"languages":["en"]},"subtitles":{"mode":"on"}}`, &p)
	if p.Subtitles.ForeignAudio || p.Subtitles.Mode != "on" {
		t.Fatalf("mode in the same patch = %+v", p.Subtitles)
	}
}

// languageFile is a probed file, nothing on disk but its size, with the
// audio and subtitle streams given.
func languageFile(t *testing.T, srv *testEnv, audio []probe.AudioStream, subs []probe.SubtitleStream) db.MediaFile {
	t.Helper()
	path := filepath.Join(t.TempDir(), "Fictional Clip.mp4")
	if err := os.WriteFile(path, make([]byte, 300_000), 0o600); err != nil {
		t.Fatal(err)
	}
	info := probe.MediaInfo{Container: "mov", DurationMs: 60_000,
		Video: []probe.VideoStream{{Index: 0, Codec: "h264", Width: 1280, Height: 720}}, Audio: audio, Subtitles: subs}
	if info.Subtitles == nil {
		info.Subtitles = []probe.SubtitleStream{}
	}
	encoded, err := probe.Encode(&info)
	if err != nil {
		t.Fatal(err)
	}
	probeJSON := string(encoded)
	now := db.FormatTime(time.Now())
	f, err := srv.q.UpsertMediaFile(t.Context(), db.UpsertMediaFileParams{
		LibraryID: srv.fileLibrary(t, path), Path: path, Size: 300_000, Mtime: now, AddedAt: now,
		Parsed: "{}", Probe: &probeJSON, LastSeenAt: now, SeenInScan: 1,
	})
	if err != nil {
		t.Fatal(err)
	}
	return f
}

func TestAudioPreferencesChooseStartingTrack(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	file := languageFile(t, srv, []probe.AudioStream{
		{Index: 1, Codec: "aac", Channels: 2, Language: "eng", Default: true},
		{Index: 2, Codec: "aac", Channels: 2, Language: "spa"},
		{Index: 3, Codec: "aac", Channels: 2, Language: "spa", Title: "Commentary"},
	}, []probe.SubtitleStream{
		{Index: 4, Codec: "subrip", Language: "eng", Forced: true},
		{Index: 5, Codec: "subrip", Language: "spa", Forced: true},
	})
	url := fmt.Sprintf("%s/api/files/%d/playback", srv.URL, file.ID)
	play := func(t *testing.T, client *http.Client, body string) playback.Decision {
		t.Helper()
		var d playback.Decision
		if code := doJSON(t, client, http.MethodPost, url, body, &d).StatusCode; code != 200 {
			t.Fatalf("playback -> %d", code)
		}
		return d
	}
	// Starting a remux needs a transcoder and a real file, so plays here
	// use a native player's profile, which stays direct play; the browser's
	// remux is checked through version lists and decideTracks.
	nativeBody := func(extra string) string {
		return `{"profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"],"subtitleFormats":["webvtt"],"selectsAudioTrack":true}` + extra + `}`
	}

	if d := play(t, srv.client, subtitleProfileBody("")); d.Audio.Index != 1 || d.Mode != playback.ModeDirect {
		t.Fatalf("no languages: audio %d mode %s", d.Audio.Index, d.Mode)
	}
	doJSON(t, srv.client, http.MethodPut, srv.URL+"/api/me/preferences", `{"audio":{"languages":["es"]}}`, nil)

	d := play(t, srv.client, nativeBody(`,"subtitleMode":"auto"`))
	if d.Audio.Index != 2 || d.Mode != playback.ModeDirect || !slices.ContainsFunc(d.Reasons, func(r string) bool { return strings.Contains(r, "audio track 2 (spa)") }) {
		t.Fatalf("native player: audio %d mode %s reasons %v", d.Audio.Index, d.Mode, d.Reasons)
	}
	// Forced subtitles follow the audio the preference chose.
	if d.SubtitleID != "s5" {
		t.Fatalf("forced subtitle for Spanish audio = %q", d.SubtitleID)
	}
	if d := play(t, srv.client, subtitleProfileBody(`,"audioIndex":1`)); d.Audio.Index != 1 || d.Mode != playback.ModeDirect {
		t.Fatalf("a named track wins: audio %d mode %s", d.Audio.Index, d.Mode)
	}
	row, err := srv.q.GetMediaFile(t.Context(), file.ID)
	if err != nil {
		t.Fatal(err)
	}
	info, err := probe.Parse(*row.Probe)
	if err != nil {
		t.Fatal(err)
	}
	browser := playback.Request{Profile: playback.Profile{Containers: []string{"mp4"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}}}
	bd, chosen := decideTracks(t.Context(), Deps{}, row, info, browser, nil, playback.Capabilities{}, trackPreferences{audio: []string{"es"}})
	if bd.Audio.Index != 2 || bd.Mode != playback.ModeRemux || chosen.AudioIndex == nil || *chosen.AudioIndex != 2 {
		t.Fatalf("browser: audio %d mode %s, request names %v", bd.Audio.Index, bd.Mode, chosen.AudioIndex)
	}
	// Version lists say what starting would do.
	var plans PlanList
	doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/playback/plans",
		fmt.Sprintf(`{"profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"]},"fileIds":[%d]}`, file.ID), &plans)
	if len(plans.Items) != 1 || plans.Items[0].Mode != playback.ModeRemux || plans.Items[0].Audio.Index != 2 {
		t.Fatalf("plans = %+v", plans.Items)
	}
	// Someone else's languages are their own.
	other, _ := securityTestUser(t, srv, "alex")
	if d := play(t, other, subtitleProfileBody("")); d.Audio.Index != 1 || d.Mode != playback.ModeDirect {
		t.Fatalf("another person: audio %d mode %s", d.Audio.Index, d.Mode)
	}
	// The file's default track in the person's language changes nothing.
	doJSON(t, srv.client, http.MethodPut, srv.URL+"/api/me/preferences", `{"audio":{"languages":["en"]}}`, nil)
	if d := play(t, srv.client, subtitleProfileBody("")); d.Audio.Index != 1 || d.Mode != playback.ModeDirect || slices.ContainsFunc(d.Reasons, func(r string) bool { return strings.Contains(r, "audio track") }) {
		t.Fatalf("default already right: audio %d mode %s reasons %v", d.Audio.Index, d.Mode, d.Reasons)
	}
}

func TestForeignAudioTurnsSubtitlesOn(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	subs := []probe.SubtitleStream{
		{Index: 2, Codec: "subrip", Language: "eng", Forced: true},
		{Index: 3, Codec: "subrip", Language: "eng"},
	}
	korean := languageFile(t, srv, []probe.AudioStream{{Index: 1, Codec: "aac", Channels: 2, Language: "kor", Default: true}}, subs)
	english := languageFile(t, srv, []probe.AudioStream{{Index: 1, Codec: "aac", Channels: 2, Language: "eng", Default: true}}, subs)
	// Audio languages alone: foreignAudio comes on, and subtitles use the
	// audio languages when there are no subtitle languages.
	doJSON(t, srv.client, http.MethodPut, srv.URL+"/api/me/preferences", `{"audio":{"languages":["en"]}}`, nil)
	for _, tc := range []struct {
		name string
		file db.MediaFile
		want string
	}{
		{"foreign audio", korean, "s3"},
		{"followed audio", english, "s2"},
	} {
		var d playback.Decision
		url := fmt.Sprintf("%s/api/files/%d/playback", srv.URL, tc.file.ID)
		if code := doJSON(t, srv.client, http.MethodPost, url, subtitleProfileBody(`,"subtitleMode":"auto"`), &d).StatusCode; code != 200 || d.SubtitleID != tc.want {
			t.Errorf("%s -> %d, subtitle %q, want %q", tc.name, code, d.SubtitleID, tc.want)
		}
	}
}

// An hls download carries one audio track, the person's language; a file
// download keeps them all and must stay one.
func TestOfflineDownloadsFollowAudioLanguages(t *testing.T) {
	t.Parallel()
	row := db.MediaFile{ID: 1, Path: "/fictional/clip.mp4"}
	info := &probe.MediaInfo{Container: "mov", DurationMs: 60_000,
		Video: []probe.VideoStream{{Index: 0, Codec: "h264", Width: 1280, Height: 720}},
		Audio: []probe.AudioStream{
			{Index: 1, Codec: "aac", Channels: 2, Language: "eng", Default: true},
			{Index: 2, Codec: "aac", Channels: 2, Language: "spa"},
		},
	}
	direct := playback.Profile{Containers: []string{"mp4"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}}
	remux := playback.Profile{Containers: []string{"mkv"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}}
	d, _, req := offlineDecidePreferred(t.Context(), Deps{}, row, info, playback.Request{Profile: direct, Playlist: "vod"}, []string{"es"})
	if d.Protocol != "file" || req.AudioIndex != nil {
		t.Fatalf("file download = %s, audioIndex %v", d.Protocol, req.AudioIndex)
	}
	d, _, req = offlineDecidePreferred(t.Context(), Deps{}, row, info, playback.Request{Profile: remux, Playlist: "vod"}, []string{"es"})
	if d.Protocol != "hls" || d.Audio.Index != 2 || req.AudioIndex == nil || *req.AudioIndex != 2 {
		t.Fatalf("hls download = %s audio %d, request %+v", d.Protocol, d.Audio.Index, req.AudioIndex)
	}
	// The request kept with the record names the track, so the download
	// doesn't change if the person's languages do.
	stored, _ := json.Marshal(offlineRequest{Request: req, FileID: row.ID})
	if !strings.Contains(string(stored), `"audioIndex":2`) {
		t.Fatalf("stored request = %s", stored)
	}
}

// With foreignAudio, the opt-in automatic download is for audio the person
// doesn't follow: an English film's play can't ask for one, a Korean one's
// gets it.
func TestAutomaticSubtitleOnlyForForeignAudio(t *testing.T) {
	t.Parallel()
	p := &automaticProvider{}
	srv, _ := automaticSubtitleEnv(t, p)
	english := languageFile(t, srv, []probe.AudioStream{{Index: 1, Codec: "aac", Channels: 2, Language: "eng", Default: true}}, nil)
	korean := languageFile(t, srv, []probe.AudioStream{{Index: 1, Codec: "aac", Channels: 2, Language: "kor", Default: true}}, nil)
	if code := doJSON(t, srv.client, http.MethodPut, srv.URL+"/api/me/preferences", `{"audio":{"languages":["en"]},"subtitles":{"languages":["en"],"automaticDownload":true}}`, nil).StatusCode; code != 200 {
		t.Fatalf("preferences -> %d", code)
	}
	automatic := func(file db.MediaFile) (AutomaticSubtitleResult, int) {
		t.Helper()
		d := startAutomaticSubtitlePlay(t, srv, file, `,"subtitleMode":"auto"`)
		var got AutomaticSubtitleResult
		url := fmt.Sprintf("%s/api/files/%d/subtitles/automatic", srv.URL, file.ID)
		code := doJSON(t, srv.client, http.MethodPost, url, fmt.Sprintf(`{"playId":%q}`, d.PlayID), &got).StatusCode
		return got, code
	}
	if got, code := automatic(english); code != http.StatusConflict || got.Track != nil || p.searches.Load() != 0 {
		t.Fatalf("English audio -> %d %+v after %d searches", code, got, p.searches.Load())
	}
	if got, code := automatic(korean); code != 200 || got.Track == nil || p.downloads.Load() != 1 {
		t.Fatalf("Korean audio -> %d %+v after %d downloads", code, got, p.downloads.Load())
	}
}
