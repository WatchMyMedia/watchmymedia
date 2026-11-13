package api

import (
	"bytes"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/ffexec"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/probe"
)

// adaptiveRequest asks for Automatic with an adaptive VOD family.
const adaptiveRequest = `{"adaptive":true,"quality":"auto","autoRecovery":true,"playlist":"vod","profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"],"maxHeight":720,"maxBitrate":4000000}}`

// adaptiveSample renders a fictional MPEG-4 Part 2 file, which always needs
// a transcode and so can be offered a family, and returns its path and
// encoded probe. It skips without ffmpeg and ffprobe.
func adaptiveSample(t *testing.T, seconds int) (string, string) {
	t.Helper()
	for _, tool := range []string{"ffmpeg", "ffprobe"} {
		if _, err := exec.LookPath(tool); err != nil {
			t.Skip(tool + " not installed")
		}
	}
	path := filepath.Join(t.TempDir(), "Fictional adaptive sample.mkv")
	if err := ffexec.Command(t.Context(), "ffmpeg", ffexec.FFmpegArgs("-f", "lavfi", "-i", fmt.Sprintf("testsrc2=size=320x180:rate=24:duration=%d", seconds), "-f", "lavfi", "-i", fmt.Sprintf("sine=frequency=440:duration=%d", seconds), "-c:v", "mpeg4", "-threads", "1", "-c:a", "aac", path)...).Run(); err != nil {
		t.Fatal(err)
	}
	info, err := (&probe.Prober{Path: "ffprobe"}).Probe(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	raw, err := probe.Encode(info)
	if err != nil {
		t.Fatal(err)
	}
	return path, string(raw)
}

// logCapture keeps what a test's playback manager logged, for the failure
// report. The manager logs from goroutines that can outlive the test, which
// the testing package's own log does not allow.
type logCapture struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (c *logCapture) Write(p []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.buf.Write(p)
}

func (c *logCapture) String() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.buf.String()
}

// adaptiveManager makes a software playback manager of the given limit that
// admits a family however fast this machine encodes: the server measures the
// encoder before it offers one, and on a busy machine falls back to a single
// rendition, as it should. These tests check what a family does once it is
// offered, so the manager is told each rung prepared in a millisecond
// (playback.Manager.LadderPreparationTook);
// TestAdaptiveVODFamilyNeedsFastPreparation checks the rule itself.
//
// A failed test reports the manager's log, debug lines included: a refused
// family is answered with a single rendition and no error, and the reason
// ("VOD ladder refused") is only there.
func adaptiveManager(t *testing.T, limit int) *playback.Manager {
	t.Helper()
	logged := &logCapture{}
	pm := playback.NewManager("ffmpeg", t.TempDir(), playback.Capabilities{Encoder: "libx264"}, time.Minute, limit,
		slog.New(slog.NewTextHandler(logged, &slog.HandlerOptions{Level: slog.LevelDebug})))
	// Registered before StopAll's cleanup, so it runs after it and has the
	// log of the stops too.
	t.Cleanup(func() {
		if t.Failed() {
			t.Logf("playback manager log:\n%s", logged)
		}
	})
	t.Cleanup(pm.StopAll)
	pm.LadderPreparationTook(time.Millisecond)
	return pm
}

// adaptiveServer starts a server with adaptive HLS on and a software playback
// manager of the given limit, with the sample file in its library.
func adaptiveServer(t *testing.T, path, encoded string, limit int, configure func(*Deps, *playback.Manager)) (*testEnv, *playback.Manager, string) {
	t.Helper()
	pm := adaptiveManager(t, limit)
	srv := newTestServerWith(t, func(d *Deps) {
		d.Playback = pm
		d.AdaptiveHLS = true
		if configure != nil {
			configure(d, pm)
		}
	})
	return srv, pm, adaptiveLibraryFile(t, srv, path, encoded)
}

// adaptiveLibraryFile adds the sample to srv's library and returns its playback URL.
func adaptiveLibraryFile(t *testing.T, srv *testEnv, path, encoded string) string {
	t.Helper()
	st, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	now := db.FormatTime(time.Now())
	file, err := srv.q.UpsertMediaFile(t.Context(), db.UpsertMediaFileParams{LibraryID: srv.fileLibrary(t, path), Path: path, Size: st.Size(), Mtime: db.FormatTime(st.ModTime()), AddedAt: now, Parsed: "{}", Probe: &encoded, LastSeenAt: now, SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	return fmt.Sprintf("%s/api/files/%d/playback", srv.URL, file.ID)
}

func TestAdaptiveVODUsesOrdinaryAuthorizedPlaybackAndFallsBackAtCapacity(t *testing.T) {
	path, encoded := adaptiveSample(t, 12)
	srv, pm, url := adaptiveServer(t, path, encoded, 2, nil)
	request := adaptiveRequest
	// The request cannot enable experimental encoding on a default server.
	offPM := playback.NewManager("ffmpeg", t.TempDir(), playback.Capabilities{Encoder: "libx264"}, time.Minute, 2, slog.New(slog.DiscardHandler))
	t.Cleanup(offPM.StopAll)
	off := newTestServerWith(t, func(d *Deps) { d.Playback = offPM })
	offURL := adaptiveLibraryFile(t, off, path, encoded)
	var ordinary playback.Decision
	if res := doJSON(t, off.client, "POST", offURL, request, &ordinary); res.StatusCode != 200 || ordinary.Adaptive != nil || offPM.Claimed() > 1 {
		t.Fatalf("default-off negotiation: %d %+v", res.StatusCode, ordinary)
	}
	doJSON(t, off.client, "DELETE", off.URL+"/api/playback/"+ordinary.SessionID, "", nil)

	var family playback.Decision
	if res := doJSON(t, srv.client, "POST", url, request, &family); res.StatusCode != 200 || family.Adaptive == nil || pm.Claimed() != 2 {
		t.Fatalf("family negotiation: %d %+v claims %d %s", res.StatusCode, family, pm.Claimed(), res.Body)
	}
	if res := doJSON(t, srv.client, "GET", srv.URL+family.URL, "", nil); res.StatusCode != 200 || strings.Count(string(res.Body), "#EXT-X-STREAM-INF:") != 2 {
		t.Fatalf("master: %d %s", res.StatusCode, res.Body)
	}
	// An unrelated viewer cannot obtain a partial family at a full budget.
	var refused playback.Decision
	if res := doJSON(t, srv.client, "POST", url, request, &refused); res.StatusCode != 503 || pm.Claimed() != 2 {
		t.Fatalf("capacity: %d claims %d", res.StatusCode, pm.Claimed())
	}
	if res := doJSON(t, srv.client, "GET", srv.URL+family.URL, "", nil); res.StatusCode != 200 {
		t.Fatal("refused family killed original viewer")
	}
	doJSON(t, srv.client, "DELETE", srv.URL+"/api/playback/"+family.SessionID, "", nil)
	var single playback.Decision
	request = strings.Replace(request, `"quality":"auto"`, `"quality":"720"`, 1)
	if res := doJSON(t, srv.client, "POST", url, request, &single); res.StatusCode != 200 || single.Adaptive != nil || pm.Claimed() > 1 {
		t.Fatalf("fixed selection became adaptive: %d %+v", res.StatusCode, single)
	}
}

// The server offers a family only when each rung's first segment was made
// fast enough for every configured slot to encode at once with a quarter to
// spare; otherwise the viewer gets the ordinary single rendition. The times
// here are given to the manager, not measured, so the rule decides the same
// way on any machine: two seconds for a six-second segment is enough headroom
// for two slots and not for four.
func TestAdaptiveVODFamilyNeedsFastPreparation(t *testing.T) {
	path, encoded := adaptiveSample(t, 12)
	const took = 2 * time.Second
	for _, tc := range []struct {
		name   string
		limit  int
		family bool
	}{
		{"two slots", 2, true},
		{"four slots", 4, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			srv, pm, url := adaptiveServer(t, path, encoded, tc.limit, func(_ *Deps, pm *playback.Manager) { pm.LadderPreparationTook(took) })
			var d playback.Decision
			res := doJSON(t, srv.client, "POST", url, adaptiveRequest, &d)
			if res.StatusCode != 200 || d.Mode != playback.ModeTranscode || d.Playlist != "vod" {
				t.Fatalf("negotiation: %d %+v %s", res.StatusCode, d, res.Body)
			}
			if tc.family {
				if d.Adaptive == nil || pm.Claimed() != 2 {
					t.Fatalf("%s for each rung on %d slots: no family, claims %d: %+v", took, tc.limit, pm.Claimed(), d)
				}
			} else if d.Adaptive != nil || pm.Claimed() > 1 || len(pm.Sessions()) != 1 {
				// The refused rungs are gone, with the slots they reserved: one
				// session, which holds a slot only while its encoder runs.
				t.Fatalf("%s for each rung on %d slots: adaptive %v, claims %d, sessions %d; want one ordinary session", took, tc.limit, d.Adaptive, pm.Claimed(), len(pm.Sessions()))
			}
			// Either way the viewer can play what was offered.
			streams := 1
			if tc.family {
				streams = 2
			}
			if res := doJSON(t, srv.client, "GET", srv.URL+d.URL, "", nil); res.StatusCode != 200 || strings.Count(string(res.Body), "#EXT-X-STREAM-INF:") != streams {
				t.Fatalf("master: %d %s", res.StatusCode, res.Body)
			}
			if res := doJSON(t, srv.client, "GET", srv.URL+"/api/playback/"+d.SessionID+"/v00000.m4s", "", nil); res.StatusCode != 200 || len(res.Body) == 0 {
				t.Fatalf("first segment: %d %s", res.StatusCode, res.Body)
			}
		})
	}
}

// A player ends a play with a stopped report, not a DELETE. The family's
// rungs stay for reuse like any VOD session, but their reserved slots go:
// before, two paused rungs held half of a four-slot server for VODKeep.
func TestAdaptiveVODStoppedPlayReleasesReservedRungs(t *testing.T) {
	path, encoded := adaptiveSample(t, 12)
	srv, pm, url := adaptiveServer(t, path, encoded, 2, func(d *Deps, pm *playback.Manager) {
		d.Activity = activity.New(d.Conn, d.Bus, nil, activity.Options{Sessions: pm})
	})
	var family playback.Decision
	if res := doJSON(t, srv.client, "POST", url, adaptiveRequest, &family); res.StatusCode != 200 || family.Adaptive == nil || family.PlayID == "" {
		t.Fatalf("family negotiation: %d %+v %s", res.StatusCode, family, res.Body)
	}
	// Wait for both encoders to finish or pause ahead: only the reservation
	// is left holding capacity then. They are ffmpeg processes, with no event
	// this package can wait on, so this polls with a deadline.
	deadline := time.Now().Add(30 * time.Second)
	for pm.Running() > 0 && time.Now().Before(deadline) {
		time.Sleep(50 * time.Millisecond)
	}
	if running, claimed := pm.Running(), pm.Claimed(); running != 0 || claimed != 2 {
		t.Fatalf("idle family: running %d, claimed %d; want 0 and 2", running, claimed)
	}
	progress := fmt.Sprintf(`{"positionMs":4000,"durationMs":12000,"playId":%q,"state":"stopped"}`, family.PlayID)
	if res := doJSON(t, srv.client, "PUT", strings.TrimSuffix(url, "/playback")+"/progress", progress, nil); res.StatusCode != 200 {
		t.Fatalf("stopped report: %d %s", res.StatusCode, res.Body)
	}
	if claimed := pm.Claimed(); claimed != 0 {
		t.Fatalf("stopped play kept %d reserved claims", claimed)
	}
	// The rungs are still served, as a stopped ordinary VOD session is.
	if res := doJSON(t, srv.client, "GET", srv.URL+"/api/playback/"+family.Adaptive.Renditions[0].SessionID+"/v00000.m4s", "", nil); res.StatusCode != 200 || len(res.Body) == 0 {
		t.Fatalf("released rung: %d", res.StatusCode)
	}
}

func TestAdaptiveVODConcurrentColdSeekAndIndependentStop(t *testing.T) {
	path, encoded := adaptiveSample(t, 72)
	srv, pm, url := adaptiveServer(t, path, encoded, 4, func(d *Deps, _ *playback.Manager) { d.Cast = CastOptions{Enabled: true} })
	request := adaptiveRequest

	var first, peer playback.Decision
	for _, target := range []*playback.Decision{&first, &peer} {
		if res := doJSON(t, srv.client, "POST", url, request, target); res.StatusCode != 200 || target.Adaptive == nil {
			t.Fatalf("family: %d %+v", res.StatusCode, target)
		}
	}
	if first.PlayID == peer.PlayID {
		t.Fatal("viewers share a logical play")
	}
	// Revoking one credential must reject every family URL, including cached
	// media, while the independent viewing credential remains usable.
	jar, _ := cookiejar.New(nil)
	sender := &http.Client{Jar: jar}
	if res := doJSON(t, sender, "POST", srv.URL+"/api/auth/login", `{"name":"admin","password":"password1"}`, nil); res.StatusCode != 200 {
		t.Fatalf("sender login: %d", res.StatusCode)
	}
	var castToken, mediaToken struct{ Token string }
	doJSON(t, sender, "POST", srv.URL+"/api/auth/cast-token", "", &castToken)
	doJSON(t, sender, "POST", srv.URL+"/api/auth/media-token", "", &mediaToken)
	if castToken.Token == "" || mediaToken.Token == "" {
		t.Fatal("missing playback credentials")
	}
	var protected []string
	for _, family := range []playback.Decision{first, peer} {
		protected = append(protected, family.URL)
		for _, rung := range family.Adaptive.Renditions {
			protected = append(protected, "/api/playback/"+rung.SessionID+"/v00000.m4s")
		}
	}
	for _, token := range []string{castToken.Token, mediaToken.Token} {
		for _, path := range protected {
			if res := doJSON(t, http.DefaultClient, "GET", srv.URL+path+"?access_token="+token, "", nil); res.StatusCode != 200 {
				t.Fatalf("authorized family media: %d", res.StatusCode)
			}
		}
	}
	doJSON(t, sender, "POST", srv.URL+"/api/auth/logout", "", nil)
	for _, token := range []string{castToken.Token, mediaToken.Token} {
		for _, path := range protected {
			if res := doJSON(t, http.DefaultClient, "GET", srv.URL+path+"?access_token="+token, "", nil); res.StatusCode != 401 {
				t.Fatalf("revoked family media: %d", res.StatusCode)
			}
		}
	}
	var requests sync.WaitGroup
	failures := make(chan error, 4)
	for _, family := range []playback.Decision{first, peer} {
		for _, rung := range family.Adaptive.Renditions {
			requests.Go(func() {
				res, err := srv.client.Get(srv.URL + "/api/playback/" + rung.SessionID + "/v00009.m4s")
				if err != nil {
					failures <- err
					return
				}
				defer res.Body.Close()
				body, err := io.ReadAll(res.Body)
				if err != nil {
					failures <- fmt.Errorf("cold seek read: %w", err)
				} else if res.StatusCode != 200 || len(body) == 0 {
					failures <- fmt.Errorf("cold seek: status %d, bytes %d", res.StatusCode, len(body))
				}
			})
		}
	}
	requests.Wait()
	close(failures)
	for err := range failures {
		t.Error(err)
	}
	if t.Failed() {
		return
	}
	doJSON(t, srv.client, "DELETE", srv.URL+"/api/playback/"+first.SessionID, "", nil)
	for _, rung := range peer.Adaptive.Renditions {
		if res := doJSON(t, srv.client, "GET", srv.URL+"/api/playback/"+rung.SessionID+"/v00002.m4s", "", nil); res.StatusCode != 200 || len(res.Body) == 0 {
			t.Fatalf("peer backward seek after stop: %d %s", res.StatusCode, res.Body)
		}
	}
	doJSON(t, srv.client, "DELETE", srv.URL+"/api/playback/"+peer.SessionID, "", nil)
	// A stopped session whose producer the other family shared keeps its
	// claim until that ffmpeg has exited, which the stop does not wait for
	// (it took longer than the DELETE on a Windows runner). A leak is a
	// claim that never goes.
	for deadline := time.Now().Add(10 * time.Second); pm.Claimed() != 0; time.Sleep(20 * time.Millisecond) {
		if time.Now().After(deadline) {
			t.Fatalf("leaked claims: %d", pm.Claimed())
		}
	}
}
