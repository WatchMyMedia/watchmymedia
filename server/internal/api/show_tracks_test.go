package api

import (
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"testing"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/probe"
)

// The sound and subtitles a person picks for a show start its episodes,
// before their own languages; a play that names its tracks, a movie and
// someone else are untouched; Forget brings the person's languages back.
func TestShowTracks(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx := t.Context()
	file := languageFile(t, srv, []probe.AudioStream{
		{Index: 1, Codec: "aac", Channels: 2, Language: "eng", Default: true},
		{Index: 2, Codec: "aac", Channels: 2, Language: "spa"},
	}, []probe.SubtitleStream{
		{Index: 3, Codec: "subrip", Language: "eng"},
		{Index: 4, Codec: "subrip", Language: "spa"},
	})
	movie := languageFile(t, srv, []probe.AudioStream{
		{Index: 1, Codec: "aac", Channels: 2, Language: "eng", Default: true},
		{Index: 2, Codec: "aac", Channels: 2, Language: "spa"},
	}, nil)
	show, err := srv.q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: file.LibraryID, TmdbID: 777, Title: "Harbor Lights", Genres: "[]", Credits: "[]"})
	if err != nil {
		t.Fatal(err)
	}
	season, _ := srv.q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: show.ID, Number: 1, Name: "Season 1", EpisodeCount: 1})
	ep, _ := srv.q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: season.ID, ShowID: show.ID, Number: 1, Title: "Pilot"})
	if err := srv.q.InsertEpisodeFile(ctx, db.InsertEpisodeFileParams{EpisodeID: ep.ID, MediaFileID: file.ID}); err != nil {
		t.Fatal(err)
	}

	native := `{"profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"],"subtitleFormats":["webvtt"],"selectsAudioTrack":true},"subtitleMode":"auto"%s}`
	play := func(t *testing.T, client *http.Client, f db.MediaFile, extra string) playback.Decision {
		t.Helper()
		var d playback.Decision
		if code := doJSON(t, client, http.MethodPost, fmt.Sprintf("%s/api/files/%d/playback", srv.URL, f.ID), fmt.Sprintf(native, extra), &d).StatusCode; code != http.StatusOK {
			t.Fatalf("playback -> %d", code)
		}
		return d
	}
	tracks := fmt.Sprintf("%s/api/shows/%d/tracks", srv.URL, show.ID)
	put := func(t *testing.T, body string) ShowTracks {
		t.Helper()
		var got ShowTracks
		if code := doJSON(t, srv.client, http.MethodPut, tracks, body, &got).StatusCode; code != http.StatusOK {
			t.Fatalf("setShowTracks %s -> %d", body, code)
		}
		return got
	}
	page := func(t *testing.T) *ShowTracks {
		t.Helper()
		var d ShowDetail
		doJSON(t, srv.client, http.MethodGet, fmt.Sprintf("%s/api/shows/%d", srv.URL, show.ID), "", &d)
		return d.Tracks
	}

	if d := play(t, srv.client, file, ""); d.Audio.Index != 1 || d.SubtitleID != "" {
		t.Fatalf("no choice: audio %d subtitle %q", d.Audio.Index, d.SubtitleID)
	}
	if got := page(t); got != nil {
		t.Fatalf("tracks before a choice = %+v", got)
	}

	if got := put(t, `{"audioLanguage":"spa"}`); got.AudioLanguage != "es" || got.SubtitleLanguage != "" || got.SubtitlesOff {
		t.Fatalf("audio choice = %+v", got)
	}
	if d := play(t, srv.client, file, ""); d.Audio.Index != 2 {
		t.Fatalf("show's audio: audio %d", d.Audio.Index)
	}
	if d := play(t, srv.client, file, `,"audioIndex":1`); d.Audio.Index != 1 {
		t.Fatalf("a named track wins: audio %d", d.Audio.Index)
	}
	if d := play(t, srv.client, movie, ""); d.Audio.Index != 1 {
		t.Fatalf("a movie: audio %d", d.Audio.Index)
	}
	other, _ := securityTestUser(t, srv, "alex")
	if d := play(t, other, file, ""); d.Audio.Index != 1 {
		t.Fatalf("someone else: audio %d", d.Audio.Index)
	}

	// Subtitles join the audio choice, which stays.
	if got := put(t, `{"subtitleLanguage":"es"}`); got.AudioLanguage != "es" || got.SubtitleLanguage != "es" || got.SubtitlesOff {
		t.Fatalf("subtitle choice = %+v", got)
	}
	if d := play(t, srv.client, file, ""); d.SubtitleID != "s4" {
		t.Fatalf("show's subtitles = %q", d.SubtitleID)
	}
	if d := play(t, srv.client, file, `,"subtitleId":"s3"`); d.SubtitleID != "s3" {
		t.Fatalf("a named subtitle wins = %q", d.SubtitleID)
	}
	if got := put(t, `{"subtitlesOff":true}`); got.SubtitleLanguage != "" || !got.SubtitlesOff || got.AudioLanguage != "es" {
		t.Fatalf("subtitles off = %+v", got)
	}
	doJSON(t, srv.client, http.MethodPut, srv.URL+"/api/me/preferences", `{"subtitles":{"mode":"on","languages":["en"]}}`, nil)
	if d := play(t, srv.client, file, ""); d.SubtitleID != "" {
		t.Fatalf("show's subtitles off, the account's on: %q", d.SubtitleID)
	}
	if got := page(t); got == nil || got.AudioLanguage != "es" || !got.SubtitlesOff {
		t.Fatalf("show page tracks = %+v", got)
	}

	// The plans for the episode follow the show's sound too.
	var plans PlanList
	doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/playback/plans",
		fmt.Sprintf(`{"profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"]},"fileIds":[%d]}`, file.ID), &plans)
	if len(plans.Items) != 1 || plans.Items[0].Audio.Index != 2 || plans.Items[0].Mode != playback.ModeRemux {
		t.Fatalf("plans = %+v", plans.Items)
	}

	for _, bad := range []string{`{"audioLanguage":"not a tag"}`, `{"subtitleLanguage":"und"}`} {
		if code := doJSON(t, srv.client, http.MethodPut, tracks, bad, nil).StatusCode; code != http.StatusUnprocessableEntity {
			t.Errorf("%s -> %d", bad, code)
		}
	}
	if code := doJSON(t, srv.client, http.MethodPut, fmt.Sprintf("%s/api/shows/%d/tracks", srv.URL, show.ID+100), `{"audioLanguage":"en"}`, nil).StatusCode; code != http.StatusNotFound {
		t.Errorf("unknown show -> %d", code)
	}

	// Forget.
	if code := doJSON(t, srv.client, http.MethodDelete, tracks, "", nil).StatusCode; code != http.StatusNoContent {
		t.Fatalf("forgetShowTracks -> %d", code)
	}
	if got := page(t); got != nil {
		t.Fatalf("tracks after Forget = %+v", got)
	}
	if d := play(t, srv.client, file, ""); d.Audio.Index != 1 || d.SubtitleID != "s3" {
		t.Fatalf("after Forget: audio %d subtitle %q", d.Audio.Index, d.SubtitleID)
	}
}

// A plan with tracks lists every audio track and subtitle a play could
// use, those beside the file included, without URLs; one without, none.
func TestPlansListTracks(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	file := languageFile(t, srv, []probe.AudioStream{
		{Index: 1, Codec: "aac", Channels: 2, Language: "eng", Default: true},
		{Index: 2, Codec: "aac", Channels: 6, Language: "spa"},
	}, []probe.SubtitleStream{{Index: 3, Codec: "subrip", Language: "eng", Forced: true}})
	sidecar := filepath.Join(filepath.Dir(file.Path), "Fictional Clip.fr.srt")
	if err := os.WriteFile(sidecar, []byte("1\n00:00:01,000 --> 00:00:02,000\nBonjour\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	plan := func(t *testing.T, tracks bool) PlaybackPlan {
		t.Helper()
		var plans PlanList
		body := fmt.Sprintf(`{"profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"]},"fileIds":[%d],"tracks":%v}`, file.ID, tracks)
		if code := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/playback/plans", body, &plans).StatusCode; code != http.StatusOK || len(plans.Items) != 1 {
			t.Fatalf("plans -> %d %+v", code, plans.Items)
		}
		return plans.Items[0]
	}
	if p := plan(t, false); p.AudioTracks != nil || p.Subtitles != nil {
		t.Errorf("without tracks: %+v %+v", p.AudioTracks, p.Subtitles)
	}
	p := plan(t, true)
	if len(p.AudioTracks) != 2 || p.AudioTracks[1].Index != 2 || p.AudioTracks[1].Channels != 6 {
		t.Errorf("audio tracks = %+v", p.AudioTracks)
	}
	var ids []string
	for _, s := range p.Subtitles {
		ids = append(ids, s.ID)
		if s.URL != "" {
			t.Errorf("subtitle %s has a URL %q", s.ID, s.URL)
		}
	}
	if !slices.Equal(ids, []string{"s3", "x0"}) || !p.Subtitles[0].Forced || p.Subtitles[1].Language != "fr" || p.Subtitles[1].Source != "sidecar" {
		t.Errorf("subtitles = %+v", p.Subtitles)
	}
}

func TestHideSpoilersPreference(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	url := srv.URL + "/api/me/preferences"
	var p Preferences
	doJSON(t, srv.client, http.MethodGet, url, "", &p)
	if p.HideSpoilers {
		t.Fatal("spoilers hidden by default")
	}
	if code := doJSON(t, srv.client, http.MethodPut, url, `{"hideSpoilers":true}`, &p).StatusCode; code != http.StatusOK || !p.HideSpoilers || !p.AutoplayNext {
		t.Fatalf("hideSpoilers -> %d %+v", code, p)
	}
	other, _ := securityTestUser(t, srv, "alex")
	var theirs Preferences
	doJSON(t, other, http.MethodGet, url, "", &theirs)
	if theirs.HideSpoilers {
		t.Error("another person's preference changed")
	}
	doJSON(t, srv.client, http.MethodPut, url, `{"autoplayNext":false}`, &p)
	if !p.HideSpoilers {
		t.Error("another patch turned it off")
	}
}
