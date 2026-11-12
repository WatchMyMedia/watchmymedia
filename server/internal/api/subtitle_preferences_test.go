package api

import (
	"fmt"
	"net/http"
	"slices"
	"testing"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/probe"
)

func TestSubtitlePreferencePatch(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	url := srv.URL + "/api/me/preferences"
	var p Preferences
	doJSON(t, srv.client, http.MethodGet, url, "", &p)
	if p.Subtitles.Mode != "forced" || p.Subtitles.AutomaticDownload || p.Subtitles.Languages == nil || len(p.Subtitles.Languages) != 0 {
		t.Fatalf("default subtitles = %+v", p.Subtitles)
	}
	patch := `{"subtitles":{"languages":["eng","pt-br","en"],"mode":"on","automaticDownload":true}}`
	if code := doJSON(t, srv.client, http.MethodPut, url, patch, &p).StatusCode; code != 200 || !slices.Equal(p.Subtitles.Languages, []string{"en", "pt-BR"}) || p.Subtitles.Mode != "on" || !p.Subtitles.AutomaticDownload {
		t.Fatalf("patch -> %d %+v", code, p)
	}
	// Partial updates preserve the language order and auto-download opt-in.
	doJSON(t, srv.client, http.MethodPut, url, `{"subtitles":{"mode":"off"}}`, &p)
	if p.Subtitles.Mode != "off" || !p.Subtitles.AutomaticDownload || !slices.Equal(p.Subtitles.Languages, []string{"en", "pt-BR"}) {
		t.Fatalf("partial update = %+v", p.Subtitles)
	}
	for _, bad := range []string{
		`{"autoplayNext":false,"subtitles":{"mode":"sometimes"}}`,
		`{"autoplayNext":false,"subtitles":{"languages":[""]}}`,
		`{"autoplayNext":false,"subtitles":{"languages":["und"]}}`,
		`{"autoplayNext":false,"subtitles":{"languages":[" en "]}}`,
		`{"autoplayNext":false,"subtitles":{"languages":["not a tag"]}}`,
	} {
		if code := doJSON(t, srv.client, http.MethodPut, url, bad, nil).StatusCode; code != http.StatusUnprocessableEntity {
			t.Errorf("invalid patch %s -> %d", bad, code)
		}
	}
	doJSON(t, srv.client, http.MethodGet, url, "", &p)
	if !p.AutoplayNext || p.Subtitles.Mode != "off" {
		t.Fatalf("invalid patch partly changed preferences: %+v", p)
	}
	doJSON(t, srv.client, http.MethodPut, url, `{"subtitles":{"languages":[]}}`, &p)
	if p.Subtitles.Languages == nil || len(p.Subtitles.Languages) != 0 {
		t.Fatalf("clearing languages = %+v", p.Subtitles)
	}
	other, _ := securityTestUser(t, srv, "alex")
	doJSON(t, other, http.MethodGet, url, "", &p)
	if p.Subtitles.Mode != "forced" || p.Subtitles.AutomaticDownload {
		t.Fatalf("another person inherited settings: %+v", p)
	}
}

func TestSubtitlePreferencesChooseBeforePlayback(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	file := subtitlePreferenceFile(t, srv, []probe.SubtitleStream{
		{Index: 2, Codec: "subrip", Language: "eng", Forced: true},
		{Index: 3, Codec: "subrip", Language: "eng"},
		{Index: 4, Codec: "subrip", Language: "spa"},
	})
	url := fmt.Sprintf("%s/api/files/%d/playback", srv.URL, file.ID)
	for _, tc := range []struct{ name, prefs, request, want string }{
		{"legacy omission keeps Off", "", "", ""},
		{"default forced follows audio", "", `,"subtitleMode":"auto"`, "s2"},
		{"explicit Off wins", "", `,"subtitleMode":"off"`, ""},
		{"preferred language", `{"mode":"on","languages":["es","en"]}`, `,"subtitleMode":"auto"`, "s4"},
		{"manual pick wins", `{"mode":"off"}`, `,"subtitleMode":"auto","subtitleId":"s3"`, "s3"},
		{"manual pick beats explicit default Off", "", `,"subtitleMode":"off","subtitleId":"s4"`, "s4"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if tc.prefs != "" {
				doJSON(t, srv.client, http.MethodPut, srv.URL+"/api/me/preferences", `{"subtitles":`+tc.prefs+`}`, nil)
			}
			var d playback.Decision
			if code := doJSON(t, srv.client, http.MethodPost, url, subtitleProfileBody(tc.request), &d).StatusCode; code != 200 || d.SubtitleID != tc.want {
				t.Fatalf("playback -> %d, selected %q, want %q", code, d.SubtitleID, tc.want)
			}
		})
	}
}

func TestDefaultImageSubtitleChangesDeliveryBeforeSession(t *testing.T) {
	t.Parallel()
	row := db.MediaFile{ID: 1, Path: "/fictional/clip.mp4"}
	info := &probe.MediaInfo{Container: "mov", DurationMs: 60000,
		Video:     []probe.VideoStream{{Index: 0, Codec: "h264", Width: 1280, Height: 720}},
		Audio:     []probe.AudioStream{{Index: 1, Codec: "aac", Channels: 2, Language: "eng", Default: true}},
		Subtitles: []probe.SubtitleStream{{Index: 2, Codec: "hdmv_pgs_subtitle", Image: true, Forced: true, Language: "eng"}},
	}
	req := playback.Request{Profile: playback.Profile{Containers: []string{"mp4"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}}, SubtitleMode: "auto"}
	d, chosen := decideTracks(t.Context(), Deps{}, row, info, req, nil, playback.Capabilities{}, trackPreferences{subtitles: playback.SubtitlePreferences{Mode: "forced"}})
	if d.SubtitleID != "s2" || d.BurnIn == nil || d.BurnIn.ID != "s2" || d.Mode != playback.ModeTranscode || chosen.SubtitleID != "s2" {
		t.Fatalf("image default did not affect admission: %+v, request %+v", d, chosen)
	}
}

func TestPreferencePatchRollsBackAllFieldsWhenAWriteFails(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	url := srv.URL + "/api/me/preferences"
	if code := doJSON(t, srv.client, http.MethodPut, url, `{"subtitles":{"languages":["en"],"mode":"forced","automaticDownload":false}}`, nil).StatusCode; code != 200 {
		t.Fatalf("initial preferences -> %d", code)
	}
	// Fail the last subtitle write after the same transaction has written
	// autoplay, Skip, reminder timing, auto-download and languages.
	_, err := srv.conn.ExecContext(t.Context(), `CREATE TRIGGER reject_subtitle_mode
		BEFORE INSERT ON user_preferences
		WHEN NEW.key = 'subtitles.mode' AND NEW.value = 'on'
		BEGIN SELECT RAISE(ABORT, 'synthetic preference failure'); END`)
	if err != nil {
		t.Fatal(err)
	}
	patch := `{"autoplayNext":false,"skip":{"intro":"auto"},"liveTVReminderMinutes":15,"subtitles":{"languages":["es"],"mode":"on","automaticDownload":true}}`
	if code := doJSON(t, srv.client, http.MethodPut, url, patch, nil).StatusCode; code != http.StatusInternalServerError {
		t.Fatalf("failed patch -> %d", code)
	}
	var p Preferences
	doJSON(t, srv.client, http.MethodGet, url, "", &p)
	if !p.AutoplayNext || p.Skip["intro"] != SkipAsk || p.LiveTVReminderMinutes != defaultReminderMinutes ||
		p.Subtitles.Mode != "forced" || p.Subtitles.AutomaticDownload || !slices.Equal(p.Subtitles.Languages, []string{"en"}) {
		t.Fatalf("partial preferences escaped the rollback: %+v", p)
	}
}
