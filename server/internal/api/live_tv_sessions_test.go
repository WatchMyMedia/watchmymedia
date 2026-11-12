package api

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/hdhomerun"
	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
	"watchmymedia/server/internal/playback"
)

func TestLiveTVAutomaticHTTPOptInAndGenerationContract(t *testing.T) {
	t.Parallel()
	fake := hdhrtest.New(hdhrtest.Broadcast("ffmpeg"))
	tuner := httptest.NewServer(fake)
	t.Cleanup(tuner.Close)
	var sessions *livetv.Sessions
	var pm *playback.Manager
	var authSvc *auth.Service
	baseTime := time.Now()
	var offset atomic.Int64
	srv := newTestServerWith(t, func(d *Deps) {
		authSvc = d.Auth
		device := func(address string) (*hdhomerun.Client, error) {
			return hdhomerun.New(address, hdhrtest.Transport(tuner.URL))
		}
		d.LiveTV = livetv.New(d.Queries, d.Settings, device)
		pm = playback.NewManager("ffmpeg", t.TempDir(), playback.Capabilities{Encoder: "libx264"}, time.Minute, 2, slog.New(slog.DiscardHandler))
		d.Playback = pm
		sessions = livetv.NewSessions(d.LiveTV, pm, livetv.SessionOptions{FFmpeg: "ffmpeg", FFprobe: "ffprobe", Dir: t.TempDir(), ShareSources: true,
			Clock: func() time.Time { return baseTime.Add(time.Duration(offset.Load())) },
			Validate: func(ctx context.Context, id string) (livetv.Owner, error) {
				u, err := d.Auth.ValidateSignIn(ctx, id)
				return liveOwner(u), err
			}})
		sessions.SetSharedSourceInput(func(ctx context.Context, _, _ string) (io.ReadCloser, error) {
			client, err := device("tuner.test")
			if err != nil {
				return nil, err
			}
			return client.Open(ctx, "/auto/v7.1")
		})
		d.LiveSessions = sessions
		t.Cleanup(sessions.Close)
	})
	t.Cleanup(pm.StopAll)
	var source livetv.Source
	if r := doJSON(t, srv.client, "POST", srv.URL+"/api/settings/live-tv/sources", `{"address":"tuner.test"}`, &source); r.StatusCode >= 300 {
		t.Fatalf("source: %d %s", r.StatusCode, r.Body)
	}
	var caps LiveTVCapabilities
	if r := doJSON(t, srv.client, "GET", srv.URL+"/api/live-tv/capabilities", "", &caps); r.StatusCode != 200 || !caps.Automatic {
		t.Fatalf("Automatic capability: %d %+v", r.StatusCode, caps)
	}
	var channels struct {
		Items []livetv.Channel `json:"items"`
	}
	doJSON(t, srv.client, "GET", srv.URL+"/api/live-tv/channels", "", &channels)
	request := fmt.Sprintf(`{"channelId":%q,"requestId":"auto-http","quality":"auto","autoRecovery":true,"initialThroughputBps":50000000,"profile":{"segmentFormats":["mpegts"],"videoCodecs":["mpeg2video"],"audioCodecs":["ac3"],"interlaced":true,"maxHeight":1080,"maxBitrate":8000000}}`, channels.Items[0].ID)
	var started livetv.SessionView
	if r := doJSON(t, srv.client, "POST", srv.URL+"/api/live-tv/sessions", request, &started); r.StatusCode != 200 || !started.Automatic || started.Quality != "auto" {
		t.Fatalf("Automatic start: %d %+v %s", r.StatusCode, started, r.Body)
	}
	ctx, cancel := context.WithTimeout(t.Context(), 35*time.Second)
	defer cancel()
	owner := livetv.Owner{UserID: started.UserID, SignInID: "", Name: started.UserName, Admin: true}
	root, _ := url.Parse(srv.URL)
	cookies := srv.client.Jar.Cookies(root)
	u, err := authSvc.Authenticate(ctx, cookies[0].Value)
	if err != nil {
		t.Fatal(err)
	}
	owner.SignInID = u.SignInID
	ready, err := sessions.WaitReady(ctx, owner, started.ID)
	if err != nil || ready.State != "playing" || ready.OutputGeneration != 1 || ready.URL == started.URL {
		t.Fatalf("Automatic ready: %+v %v", ready, err)
	}
	if r := doJSON(t, srv.client, "GET", srv.URL+ready.URL, "", nil); r.StatusCode != 200 || !strings.Contains(string(r.Body), "#EXTM3U") {
		t.Fatalf("authorized first output: %d %s", r.StatusCode, r.Body)
	}
	var beat livetv.SessionView
	path := srv.URL + "/api/live-tv/sessions/" + started.ID
	if r := doJSON(t, srv.client, "PUT", path, `{"paused":false,"state":"playing","bufferedMs":30000,"throughputBps":50000000,"throughputAgeMs":0,"stalls":0}`, &beat); r.StatusCode != 200 || beat.ID != started.ID || beat.OutputGeneration != 1 {
		t.Fatalf("Automatic heartbeat: %d %+v %s", r.StatusCode, beat, r.Body)
	}
	offset.Store(int64(10 * time.Second))
	doJSON(t, srv.client, "PUT", path, `{"paused":false,"state":"playing","bufferedMs":30000,"throughputBps":50000000,"throughputAgeMs":0,"stalls":0}`, &beat)
	offset.Store(int64(30 * time.Second))
	doJSON(t, srv.client, "PUT", path, `{"paused":false,"state":"buffering","bufferedMs":1000,"throughputBps":500000,"throughputAgeMs":0,"stalls":1}`, &beat)
	offset.Store(int64(36 * time.Second))
	doJSON(t, srv.client, "PUT", path, `{"paused":false,"state":"buffering","bufferedMs":1000,"throughputBps":500000,"throughputAgeMs":0,"stalls":1}`, &beat)
	var directive *livetv.LiveDirective
	for directive == nil {
		if r := doJSON(t, srv.client, "GET", path, "", &beat); r.StatusCode != 200 {
			t.Fatalf("read switch: %d %s", r.StatusCode, r.Body)
		}
		directive = beat.Directive
		if directive == nil {
			select {
			case <-ctx.Done():
				t.Fatal("HTTP bandwidth change did not prepare a live output")
			case <-time.After(100 * time.Millisecond):
			}
		}
	}
	if directive.Generation != 2 || directive.To == "original" || beat.OutputGeneration != 1 {
		t.Fatalf("unacknowledged live switch: %+v %+v", directive, beat)
	}
	offset.Store(int64(37 * time.Second))
	ack := fmt.Sprintf(`{"paused":false,"state":"playing","bufferedMs":30000,"throughputBps":500000,"throughputAgeMs":0,"stalls":0,"switchGeneration":%d}`, directive.Generation)
	if r := doJSON(t, srv.client, "PUT", path, ack, &beat); r.StatusCode != 200 || beat.OutputGeneration != 2 || beat.ActualQuality == "original" || beat.ID != started.ID {
		t.Fatalf("acknowledged live switch: %d %+v %s", r.StatusCode, beat, r.Body)
	}
	if r := doJSON(t, srv.client, "GET", srv.URL+beat.URL, "", nil); r.StatusCode != 200 || !strings.Contains(string(r.Body), "#EXTM3U") {
		t.Fatalf("authorized switched output: %d %s", r.StatusCode, r.Body)
	}
	if r := doJSON(t, srv.client, "PUT", path, `{"paused":false,"switchGeneration":99}`, nil); r.StatusCode != 409 {
		t.Fatalf("stale generation: %d %s", r.StatusCode, r.Body)
	}
	if r := doJSON(t, srv.client, "DELETE", path, "", nil); r.StatusCode >= 300 {
		t.Fatalf("stop: %d %s", r.StatusCode, r.Body)
	}
	// The server has released its source; the remote HTTP handler observes
	// socket closure asynchronously, particularly on Windows.
	for {
		idle, changed := fake.Idle()
		if idle {
			break
		}
		select {
		case <-changed:
		case <-ctx.Done():
			t.Fatal("HTTP stop kept the fictional tuner active")
		}
	}
}

func TestLiveTVRouterOwnershipAndMediaTokens(t *testing.T) {
	t.Parallel()
	fake := hdhrtest.New(hdhrtest.Broadcast("ffmpeg"))
	tuner := httptest.NewServer(fake)
	t.Cleanup(tuner.Close)
	var manager *livetv.Sessions
	var authSvc *auth.Service
	var pm *playback.Manager
	srv := newTestServerWith(t, func(d *Deps) {
		d.LiveTV = livetv.New(d.Queries, d.Settings, func(a string) (*hdhomerun.Client, error) { return hdhomerun.New(a, hdhrtest.Transport(tuner.URL)) })
		pm = playback.NewManager("ffmpeg", t.TempDir(), playback.Capabilities{Encoder: "libx264"}, time.Minute, 1, slog.New(slog.DiscardHandler))
		d.Playback = pm
		authSvc = d.Auth
		d.Cast.Enabled = true
		manager = livetv.NewSessions(d.LiveTV, pm, livetv.SessionOptions{FFmpeg: "ffmpeg", Dir: t.TempDir(), Validate: func(ctx context.Context, id string) (livetv.Owner, error) {
			u, err := d.Auth.ValidateSignIn(ctx, id)
			return liveOwner(u), err
		}})
		d.LiveSessions = manager
		t.Cleanup(manager.Close)
	})
	root, _ := url.Parse(srv.URL)
	cookies := srv.client.Jar.Cookies(root)
	user, err := authSvc.Authenticate(t.Context(), cookies[0].Value)
	if err != nil {
		t.Fatal(err)
	}
	owner := liveOwner(user)
	var source livetv.Source
	if r := doJSON(t, srv.client, "POST", srv.URL+"/api/settings/live-tv/sources", `{"address":"tuner.test"}`, &source); r.StatusCode >= 300 {
		t.Fatalf("source: %s", r.Body)
	}
	var channels struct {
		Items []livetv.Channel `json:"items"`
	}
	doJSON(t, srv.client, "GET", srv.URL+"/api/live-tv/channels", "", &channels)
	invalid := fmt.Sprintf(`{"channelId":%q,"requestId":"bad-profile","profile":{"segmentFormats":["unknown"]}}`, channels.Items[0].ID)
	if got := doJSON(t, srv.client, "POST", srv.URL+"/api/live-tv/sessions", invalid, nil); got.StatusCode != 422 {
		t.Fatalf("invalid live profile: %d %s", got.StatusCode, got.Body)
	}
	auto := fmt.Sprintf(`{"channelId":%q,"requestId":"auto-off","quality":"auto","autoRecovery":true,"profile":{"segmentFormats":["mpegts"],"videoCodecs":["mpeg2video"],"audioCodecs":["ac3"],"interlaced":true}}`, channels.Items[0].ID)
	if got := doJSON(t, srv.client, "POST", srv.URL+"/api/live-tv/sessions", auto, nil); got.StatusCode != 422 {
		t.Fatalf("Automatic without shared source: %d %s", got.StatusCode, got.Body)
	}
	var session livetv.SessionView
	r := doJSON(t, srv.client, "POST", srv.URL+"/api/live-tv/sessions", fmt.Sprintf(`{"channelId":%q,"requestId":"browser-request","quality":"480"}`, channels.Items[0].ID), &session)
	if r.StatusCode >= 300 {
		t.Fatalf("start: %s", r.Body)
	}
	ctx, cancel := context.WithTimeout(t.Context(), 30*time.Second)
	defer cancel()
	if v, err := manager.WaitReady(ctx, owner, session.ID); err != nil || v.State != "playing" {
		t.Fatalf("ready: %+v %v", v, err)
	}
	var token MediaToken
	doJSON(t, srv.client, "POST", srv.URL+"/api/auth/media-token", "", &token)
	mediaURL := srv.URL + session.URL + "?access_token=" + url.QueryEscape(token.Token)
	r = doJSON(t, nil, "GET", mediaURL, "", nil)
	if r.StatusCode != 200 || !strings.Contains(string(r.Body), "access_token="+token.Token) {
		t.Fatalf("token playlist: %d %s", r.StatusCode, r.Body)
	}
	var segment string
	for line := range strings.SplitSeq(string(r.Body), "\n") {
		if strings.HasPrefix(line, "segment-") {
			segment = line
			break
		}
	}
	segmentURL := srv.URL + strings.TrimSuffix(session.URL, "live.m3u8") + segment
	if code := doJSON(t, nil, "GET", segmentURL, "", nil).StatusCode; code != 200 {
		t.Fatalf("child segment: %d", code)
	}
	for _, tc := range []struct{ method, path, body string }{
		{"POST", "/api/live-tv/sessions", `{}`},
		{"GET", "/api/live-tv/sessions/" + session.ID, ""},
		{"PUT", "/api/live-tv/sessions/" + session.ID, `{"paused":false}`},
		{"DELETE", "/api/live-tv/sessions/" + session.ID, ""},
		{"GET", "/api/live-tv/activity", ""},
		{"DELETE", "/api/live-tv/activity/" + session.ID, ""},
		{"GET", "/api/settings/live-tv", ""},
	} {
		if code := doJSON(t, nil, tc.method, srv.URL+tc.path+"?access_token="+url.QueryEscape(token.Token), tc.body, nil).StatusCode; code != 401 {
			t.Fatalf("media token reached %s %s: %d", tc.method, tc.path, code)
		}
	}
	grant, err := authSvc.CreateSession(t.Context(), user.ID, auth.Client{Kind: auth.KindBrowser, Name: "Other sign-in"})
	if err != nil {
		t.Fatal(err)
	}
	if code := doJSON(t, nil, "GET", srv.URL+session.URL, "", nil, "Authorization", "Bearer "+grant.Token).StatusCode; code != 404 {
		t.Fatalf("other sign-in fetched media: %d", code)
	}
	otherMedia, _, err := authSvc.IssueMediaToken(t.Context(), grant.Token)
	if err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{session.URL, strings.TrimSuffix(session.URL, "live.m3u8") + strings.Split(segment, "?")[0]} {
		if code := doJSON(t, nil, "GET", srv.URL+path+"?access_token="+url.QueryEscape(otherMedia), "", nil).StatusCode; code != 404 {
			t.Fatalf("other sign-in's media token fetched %s: %d", path, code)
		}
	}
	castToken, _, err := authSvc.IssueCastToken(t.Context(), cookies[0].Value)
	if err != nil {
		t.Fatal(err)
	}
	// A cast token plays its sender's channel (PLAN-LIVE-TV.md §4.1): the
	// playlist carries it on to the segments, and the session is the
	// sender's own, so a retry of the sender's request finds the same one.
	castBearer := []string{"Authorization", "Bearer " + castToken}
	r = doJSON(t, nil, "GET", srv.URL+session.URL+"?access_token="+url.QueryEscape(castToken), "", nil)
	if r.StatusCode != 200 || !strings.Contains(string(r.Body), "access_token="+url.QueryEscape(castToken)) {
		t.Fatalf("Cast playlist: %d %s", r.StatusCode, r.Body)
	}
	if code := doJSON(t, nil, "GET", srv.URL+session.URL, "", nil, castBearer...).StatusCode; code != 200 {
		t.Fatalf("Cast bearer playlist: %d", code)
	}
	var cast livetv.SessionView
	if r := doJSON(t, nil, "POST", srv.URL+"/api/live-tv/sessions", fmt.Sprintf(`{"channelId":%q,"requestId":"browser-request","quality":"480"}`, channels.Items[0].ID), &cast, castBearer...); r.StatusCode >= 300 || cast.ID != session.ID {
		t.Fatalf("Cast start: %d %s", r.StatusCode, r.Body)
	}
	for _, tc := range []struct{ method, path, body string }{
		{"GET", "/api/live-tv/sessions/" + session.ID, ""},
		{"PUT", "/api/live-tv/sessions/" + session.ID, `{"paused":false,"interacted":true}`},
		{"GET", "/api/live-tv/channels", ""},
	} {
		if r := doJSON(t, nil, tc.method, srv.URL+tc.path, tc.body, nil, castBearer...); r.StatusCode != 200 {
			t.Fatalf("Cast %s %s: %d %s", tc.method, tc.path, r.StatusCode, r.Body)
		}
	}
	var caps LiveTVCapabilities
	if doJSON(t, nil, "GET", srv.URL+"/api/live-tv/capabilities", "", &caps, castBearer...); !caps.Cast || !caps.Playback {
		t.Fatalf("Cast capabilities: %+v", caps)
	}
	for _, tc := range []struct{ method, path, body string }{
		{"GET", "/api/live-tv/activity", ""},
		{"DELETE", "/api/live-tv/activity/" + session.ID, ""},
		{"GET", "/api/settings/live-tv", ""},
		{"POST", "/api/settings/live-tv/sources/" + source.ID + "/refresh", ""},
		{"GET", "/api/settings/live-tv/guide", ""},
	} {
		if code := doJSON(t, nil, tc.method, srv.URL+tc.path, tc.body, nil, castBearer...).StatusCode; code != 401 {
			t.Fatalf("Cast reached %s %s: %d", tc.method, tc.path, code)
		}
	}
	var local LocalStatus
	doJSON(t, nil, "GET", srv.URL+"/api/playback/local", "", &local)
	if len(local.Plays) != 1 || local.Plays[0].ID != session.ID {
		t.Fatal("Mac status omitted live viewer")
	}
	if err := authSvc.Revoke(t.Context(), owner.SignInID); err != nil {
		t.Fatal(err)
	}
	if code := doJSON(t, nil, "GET", segmentURL, "", nil).StatusCode; code != 401 {
		t.Fatalf("revoked child token: %d", code)
	}
	if code := doJSON(t, nil, "GET", srv.URL+session.URL, "", nil, castBearer...).StatusCode; code != 401 {
		t.Fatalf("revoked sender's cast token: %d", code)
	}
	// Revocation is enforced without any request by the old viewer.
	runCtx, stop := context.WithCancel(t.Context())
	done := make(chan struct{})
	go func() { manager.Run(runCtx); close(done) }()
	deadline := time.NewTimer(5 * time.Second)
	defer deadline.Stop()
	for {
		_, changed := fake.Idle()
		active, _, _ := fake.Counts()
		if active == 0 {
			break
		}
		select {
		case <-changed:
		case <-deadline.C:
			t.Fatal("revoked stream kept tuner")
		}
	}
	stop()
	<-done
	if pm.Running() != 0 {
		t.Fatal("revocation kept encoder reservation")
	}
	// The new sign-in remains valid but cannot revive the old live session.
	if _, err := authSvc.Authenticate(t.Context(), grant.Token); err != nil {
		t.Fatal(err)
	}
}
