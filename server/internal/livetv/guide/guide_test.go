package guide

import (
	"bytes"
	"compress/gzip"
	"context"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"testing/synctest"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/guide/guidetest"
	"watchmymedia/server/internal/livetv/hdhomerun"
	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
	"watchmymedia/server/internal/settings"
)

// route sends each host's requests to its test server, in-memory ones
// included, as Transport does for real sockets.
type route map[string]*httptest.Server

func (r route) RoundTrip(req *http.Request) (*http.Response, error) {
	srv, ok := r[req.URL.Hostname()]
	if !ok {
		return nil, errors.New("no fake for " + req.URL.Hostname())
	}
	// An in-memory server has its URL only from its first Client().
	client := srv.Client()
	target, _ := url.Parse(srv.URL)
	clone := req.Clone(req.Context())
	u := *req.URL
	u.Scheme, u.Host = target.Scheme, target.Host
	clone.URL = &u
	return client.Transport.RoundTrip(clone)
}

// syncBuffer is a log that tests read while the service writes.
type syncBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuffer) Write(p []byte) (int, error) { s.mu.Lock(); defer s.mu.Unlock(); return s.b.Write(p) }
func (s *syncBuffer) String() string              { s.mu.Lock(); defer s.mu.Unlock(); return s.b.String() }

type env struct {
	t        *testing.T
	ctx      context.Context
	dbPath   string
	q        *db.Queries
	store    *settings.Store
	live     *livetv.Service
	tuner    *hdhrtest.Tuner
	sd       *guidetest.SiliconDust
	pictures *guidetest.Pictures
	log      *syncBuffer
	g        *Service
	start    time.Time
}

// newEnv is a server with the fake tuner added (7.1, 12.2, 22.1, 42.3 and
// 33.1, which the feed doesn't list), a fake SiliconDust that accepts only
// the tuner's latest DeviceAuth, and listings still off. serve makes the
// fakes' servers: httptest.NewServer, or httptest.NewTestServer inside a
// synctest bubble.
func newEnv(t *testing.T, serve func(http.Handler) *httptest.Server) *env {
	t.Helper()
	// Its own file rather than dbtest's, so secretsKept can read it.
	path := filepath.Join(t.TempDir(), "test.db")
	conn, err := db.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := db.Migrate(t.Context(), conn); err != nil {
		t.Fatal(err)
	}
	q := db.New(conn)
	e := &env{t: t, ctx: t.Context(), dbPath: path, q: q, store: settings.New(q), log: &syncBuffer{}, start: time.Now()}
	e.tuner = hdhrtest.New(nil)
	e.tuner.Channels = append(e.tuner.Channels,
		hdhrtest.Channel{GuideNumber: "22.1", GuideName: "Cedar Sports", URL: "http://tuner.test:5004/auto/v22.1"},
		hdhrtest.Channel{GuideNumber: "42.3", GuideName: "Willow Cinema", URL: "http://tuner.test:5004/auto/v42.3"},
		hdhrtest.Channel{GuideNumber: "33.1", GuideName: "Harbor Shop", URL: "http://tuner.test:5004/auto/v33.1"})
	e.sd = &guidetest.SiliconDust{Feed: guidetest.Feed(e.start, guidetest.StandardChannels)}
	e.sd.DeviceAuth = func(a string) bool { return a == e.tuner.DeviceAuth() }
	e.pictures = guidetest.NewPictures()
	tuner, sd, pics := serve(e.tuner), serve(e.sd), serve(e.pictures)
	t.Cleanup(func() { tuner.Close(); sd.Close(); pics.Close() })
	e.live = livetv.New(q, e.store, func(a string) (*hdhomerun.Client, error) { return hdhomerun.New(a, route{"tuner.test": tuner}) })
	if _, err := e.live.Add(e.ctx, "tuner.test", ""); err != nil {
		t.Fatal(err)
	}
	e.g = New(q, e.store, e.live, Options{
		Log:       slog.New(slog.NewTextHandler(e.log, &slog.HandlerOptions{Level: slog.LevelDebug})),
		Transport: route{"api.hdhomerun.com": sd, "img.hdhomerun.com": pics},
		ArtDir:    filepath.Join(t.TempDir(), "live-tv-art"),
	})
	return e
}

func (e *env) on() {
	e.t.Helper()
	if err := e.g.Configure(e.ctx, true, true, ""); err != nil {
		e.t.Fatal(err)
	}
}

func (e *env) channel(number string) livetv.Channel {
	e.t.Helper()
	channels, err := e.live.Channels(e.ctx, true)
	if err != nil {
		e.t.Fatal(err)
	}
	for _, c := range channels {
		if c.Number == number {
			return c
		}
	}
	e.t.Fatalf("no channel %s", number)
	return livetv.Channel{}
}

func (e *env) status() Status {
	e.t.Helper()
	st, err := e.g.Status(e.ctx)
	if err != nil {
		e.t.Fatal(err)
	}
	return st
}

func (e *env) programmes(number string) []livetv.Programme {
	e.t.Helper()
	c := e.channel(number)
	w, err := e.g.Window(e.ctx, []string{c.ID}, e.start.Add(-4*time.Hour), e.start.Add(48*time.Hour))
	if err != nil {
		e.t.Fatal(err)
	}
	return w[c.ID]
}

// secretsKept fails if the fake DeviceAuth reached the log or a database
// file.
func (e *env) secretsKept() {
	e.t.Helper()
	if strings.Contains(e.log.String(), "fictional-never") {
		e.t.Errorf("DeviceAuth in the log:\n%s", e.log.String())
	}
	matches, _ := filepath.Glob(e.dbPath + "*")
	for _, f := range matches {
		b, _ := os.ReadFile(f)
		if bytes.Contains(b, []byte("fictional-never")) {
			e.t.Errorf("DeviceAuth in %s", filepath.Base(f))
		}
	}
}

func TestFetchStoresListings(t *testing.T) {
	t.Parallel()
	e := newEnv(t, httptest.NewServer)
	if st := e.status(); st.State != StateOff {
		t.Fatalf("before = %s", st.State)
	}
	e.g.fetch(e.ctx)
	if e.sd.Requests() != 0 {
		t.Fatal("fetched while off")
	}
	e.on()
	if st := e.status(); st.State != StateWaiting {
		t.Fatalf("on = %s", st.State)
	}
	e.g.fetch(e.ctx)
	st := e.status()
	if st.State != StateOK || st.Programmes == 0 || st.LastSuccessAt == nil || st.NextAt == nil || st.Matched != 4 || st.Missing != 1 {
		t.Fatalf("status = %+v", st)
	}
	if q := e.sd.Queries(); len(q) != 1 || !strings.Contains(q[0], "DeviceAuth=") || strings.Contains(q[0], "Email") {
		t.Fatalf("queries = %v", q)
	}
	news := e.programmes("7.1")
	if len(news) == 0 {
		t.Fatal("no listings for 7.1")
	}
	for i, p := range news {
		if p.Title == "" || !p.End.After(p.Start) || p.ID == "" || (i > 0 && p.Start.Before(news[i-1].End)) {
			t.Fatalf("programme %d = %+v", i, p)
		}
		if p.PictureURL != "" && (!strings.HasPrefix(p.PictureURL, "/api/live-tv/art/") || p.PictureSource != "provider") {
			t.Fatalf("picture = %q %q", p.PictureURL, p.PictureSource)
		}
	}
	if len(e.programmes("33.1")) != 0 {
		t.Fatal("33.1 isn't in the feed")
	}
	channels, _ := e.live.Channels(e.ctx, false)
	if err := e.g.Decorate(e.ctx, channels); err != nil {
		t.Fatal(err)
	}
	for _, c := range channels {
		listed := c.Number != "33.1"
		if (c.Now != nil) != listed || (c.Next != nil) != listed {
			t.Errorf("%s now/next = %v %v", c.Number, c.Now, c.Next)
		}
		if c.Now != nil && (c.Now.Start.After(time.Now()) || !c.Now.End.After(time.Now()) || !c.Next.Start.After(time.Now())) {
			t.Errorf("%s now = %v–%v, next %v", c.Number, c.Now.Start, c.Now.End, c.Next.Start)
		}
		if (c.LogoURL != "") != (listed && c.Number != "42.3") {
			t.Errorf("%s logo = %q", c.Number, c.LogoURL)
		}
	}
	s, err := e.g.Settings(e.ctx)
	if err != nil {
		t.Fatal(err)
	}
	for _, m := range s.Channels {
		want := map[string]string{"33.1": "missing"}[m.Number]
		if want == "" && (m.Match != "name" && m.Match != "number") || want != "" && m.Match != want {
			t.Errorf("%s match = %s (%s)", m.Number, m.Match, m.FeedName)
		}
	}
	// The same programmes again keep their ids.
	before := e.programmes("12.2")
	e.g.fetch(e.ctx)
	after := e.programmes("12.2")
	if len(before) != len(after) || before[0].ID != after[0].ID {
		t.Fatalf("ids changed: %d %d", len(before), len(after))
	}
	e.secretsKept()
}

func TestOldFirmwareAndTheEmailForm(t *testing.T) {
	t.Parallel()
	e := newEnv(t, httptest.NewServer)
	e.tuner.OldFirmware = true
	e.on()
	e.g.fetch(e.ctx)
	if st := e.status(); st.State != StateFirmware || e.sd.Requests() != 0 || !strings.Contains(st.Message, "firmware") {
		t.Fatalf("old firmware = %+v, %d requests", st, e.sd.Requests())
	}
	if err := e.g.Configure(e.ctx, true, true, "not an address"); !errors.Is(err, ErrEmail) {
		t.Fatalf("bad email = %v", err)
	}
	e.sd.Lock()
	e.sd.Emails = map[string]string{"viewer@example.com": "12345678"}
	e.sd.Unlock()
	if err := e.g.Configure(e.ctx, true, true, "viewer@example.com"); err != nil {
		t.Fatal(err)
	}
	e.g.fetch(e.ctx)
	if st := e.status(); st.State != StateOK {
		t.Fatalf("email form = %+v", st)
	}
	if q := e.sd.Queries(); len(q) != 1 || !strings.Contains(q[0], "Email=viewer%40example.com") || !strings.Contains(q[0], "DeviceIDs=12345678") {
		t.Fatalf("queries = %v", q)
	}
	if strings.Contains(e.log.String(), "viewer@example.com") {
		t.Error("the email reached the log")
	}
}

func TestFailuresKeepListingsAndBackOff(t *testing.T) {
	t.Parallel()
	e := newEnv(t, httptest.NewServer)
	e.on()
	e.g.fetch(e.ctx)
	kept := len(e.programmes("7.1"))
	for _, c := range []struct {
		status    int
		retry     string
		state     string
		atLeast   time.Duration
		atMost    time.Duration
		raw       []byte
		failState string
	}{
		{status: 403, state: StateRefused, atLeast: 48 * time.Minute, atMost: 72 * time.Minute},
		{status: 429, retry: "10800", state: StateUnavailable, atLeast: 3 * time.Hour, atMost: 3*time.Hour + time.Minute},
		{status: 503, state: StateUnavailable, atLeast: 192 * time.Minute, atMost: 288 * time.Minute},
		{raw: []byte("\x1f\x8b\x08\x00"), state: StateFailed},
	} {
		e.sd.Lock()
		e.sd.Status, e.sd.RetryAfter, e.sd.Raw = c.status, c.retry, c.raw
		e.sd.Unlock()
		e.g.fetch(e.ctx)
		st := e.status()
		if st.State != c.state || len(e.programmes("7.1")) != kept {
			t.Fatalf("%d: %s, %d programmes", c.status, st.State, len(e.programmes("7.1")))
		}
		if c.atMost > 0 {
			if wait := time.Until(*st.NextAt); wait < c.atLeast-time.Minute || wait > c.atMost {
				t.Errorf("%d: next in %v", c.status, wait)
			}
		}
	}
	e.secretsKept()
}

func TestFeedSizeLimits(t *testing.T) {
	// Not parallel: it lowers the package's limits.
	e := newEnv(t, httptest.NewServer)
	e.on()
	defer func(c, x int64) { maxCompressed, maxXML = c, x }(maxCompressed, maxXML)
	var bomb bytes.Buffer
	gz := gzip.NewWriter(&bomb)
	_, _ = gz.Write(bytes.Repeat([]byte(" "), 4<<20))
	_ = gz.Close()
	maxXML = 1 << 20
	e.sd.Raw = bomb.Bytes()
	e.g.fetch(e.ctx)
	if st := e.status(); st.State != StateFailed || st.Programmes != 0 {
		t.Fatalf("gzip bomb = %+v", st)
	}
	maxXML, maxCompressed = 256<<20, 1<<10
	e.sd.Raw = nil
	e.g.fetch(e.ctx)
	if st := e.status(); st.State != StateFailed || st.Programmes != 0 {
		t.Fatalf("too large compressed = %+v", st)
	}
}

func TestTurningListingsOffDeletesThem(t *testing.T) {
	t.Parallel()
	e := newEnv(t, httptest.NewServer)
	e.on()
	e.g.fetch(e.ctx)
	c := e.channel("12.2")
	if err := e.g.SetChannelListings(e.ctx, c.ID, false); err != nil {
		t.Fatal(err)
	}
	if len(e.programmes("12.2")) != 0 {
		t.Fatal("hidden channel still listed")
	}
	if err := e.g.SetChannelListings(e.ctx, "nope", false); !errors.Is(err, ErrMissing) {
		t.Fatalf("unknown channel = %v", err)
	}
	if err := e.g.Configure(e.ctx, false, true, ""); err != nil {
		t.Fatal(err)
	}
	sum, _ := e.q.GuideProgrammeSummary(e.ctx)
	art, _ := e.q.GuideArtBytes(e.ctx)
	if sum.Programmes != 0 || art != 0 || e.g.Available(e.ctx) {
		t.Fatalf("after off: %d programmes, %d bytes", sum.Programmes, art)
	}
	if err := e.g.RefreshNow(e.ctx); !errors.Is(err, ErrOff) {
		t.Fatalf("refresh while off = %v", err)
	}
	e.on()
	s, _ := e.g.Settings(e.ctx)
	for _, m := range s.Channels {
		if m.Number == "12.2" && m.Listings {
			t.Error("the channel's switch was lost")
		}
	}
}

func TestRemovingTheTunerRemovesItsListings(t *testing.T) {
	t.Parallel()
	e := newEnv(t, httptest.NewServer)
	e.on()
	e.g.fetch(e.ctx)
	sources, _ := e.live.Sources(e.ctx)
	if err := e.live.Remove(e.ctx, sources[0].ID); err != nil {
		t.Fatal(err)
	}
	sum, _ := e.q.GuideProgrammeSummary(e.ctx)
	rows, _ := e.q.ListGuideChannels(e.ctx)
	if sum.Programmes != 0 || len(rows) != 0 {
		t.Fatalf("left: %d programmes, %d channels", sum.Programmes, len(rows))
	}
}

func TestSchedule(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		e := newEnv(t, func(h http.Handler) *httptest.Server { return httptest.NewTestServer(t, h) })
		ctx, cancel := context.WithCancel(t.Context())
		done := make(chan struct{})
		go func() { e.g.Run(ctx); close(done) }()
		defer func() { cancel(); <-done }()
		synctest.Wait()
		time.Sleep(time.Hour)
		synctest.Wait()
		if e.sd.Requests() != 0 {
			t.Fatal("fetched while off")
		}
		e.on()
		time.Sleep(time.Minute - time.Second)
		synctest.Wait()
		if e.sd.Requests() != 0 {
			t.Fatal("fetched within a minute of turning on")
		}
		time.Sleep(9*time.Minute + 2*time.Second)
		synctest.Wait()
		st := e.status()
		if e.sd.Requests() != 1 || st.State != StateOK || st.LastSuccessAt == nil {
			t.Fatalf("10 minutes after on: %d requests, %+v", e.sd.Requests(), st)
		}
		// The refresh is 20–28 hours after the fetch, which came 1–10
		// minutes after on, so count from the fetch: counting from here
		// failed about 1 run in 100, when the fetch was early and the spread
		// short.
		first := *st.LastSuccessAt
		time.Sleep(time.Until(first.Add(20*time.Hour - time.Minute)))
		synctest.Wait()
		if e.sd.Requests() != 1 {
			t.Fatal("refreshed within 20 hours")
		}
		time.Sleep(time.Until(first.Add(28*time.Hour + time.Minute)))
		synctest.Wait()
		if e.sd.Requests() != 2 {
			t.Fatalf("%v after the first: %d requests", time.Since(first), e.sd.Requests())
		}
		for i := range RefreshesPerDay {
			if err := e.g.RefreshNow(e.ctx); err != nil {
				t.Fatalf("refresh %d: %v", i, err)
			}
			synctest.Wait()
			if e.sd.Requests() != 3+i {
				t.Fatalf("refresh %d: %d requests", i, e.sd.Requests())
			}
		}
		if err := e.g.RefreshNow(e.ctx); !errors.Is(err, ErrLimit) {
			t.Fatalf("fifth refresh = %v", err)
		}
		if st := e.status(); st.RefreshesLeft != 0 {
			t.Fatalf("left = %d", st.RefreshesLeft)
		}
		// A failure backs off an hour, then two.
		e.sd.Lock()
		e.sd.Status = 500
		e.sd.Unlock()
		r := e.g.record(e.ctx)
		r.Next = time.Now().Unix()
		_ = e.g.saveRecord(e.ctx, r)
		e.g.poke()
		synctest.Wait()
		n := e.sd.Requests()
		time.Sleep(47 * time.Minute)
		synctest.Wait()
		if e.sd.Requests() != n {
			t.Fatal("retried within 47 minutes")
		}
		time.Sleep(26 * time.Minute)
		synctest.Wait()
		if e.sd.Requests() != n+1 {
			t.Fatalf("no retry after an hour: %d", e.sd.Requests()-n)
		}
		// With SiliconDust down for a day and a half, the listings are out
		// of date, and the status still says why.
		time.Sleep(36 * time.Hour)
		synctest.Wait()
		st = e.status()
		if st.State != StateUnavailable || st.LastSuccessAt == nil {
			t.Fatalf("down for a day and a half = %+v", st)
		}
		state, _ := e.g.State(e.ctx)
		if !state.Stale {
			t.Fatal("not stale after 36 hours")
		}
	})
}

func TestLineupChangedFetchesEarly(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		e := newEnv(t, func(h http.Handler) *httptest.Server { return httptest.NewTestServer(t, h) })
		e.live.OnLineup(e.g.LineupChanged)
		e.on()
		e.g.fetch(e.ctx)
		ctx, cancel := context.WithCancel(t.Context())
		done := make(chan struct{})
		go func() { e.g.Run(ctx); close(done) }()
		defer func() { cancel(); <-done }()
		e.tuner.Channels = append(e.tuner.Channels, hdhrtest.Channel{GuideNumber: "5.1", GuideName: "New Channel", URL: "http://tuner.test:5004/auto/v5.1"})
		sources, _ := e.live.Sources(e.ctx)
		if _, err := e.live.Refresh(e.ctx, sources[0].ID); err != nil {
			t.Fatal(err)
		}
		time.Sleep(11 * time.Minute)
		synctest.Wait()
		if e.sd.Requests() != 1 {
			t.Fatal("fetched again within 6 hours of the last")
		}
		time.Sleep(6 * time.Hour)
		synctest.Wait()
		if _, err := e.live.Refresh(e.ctx, sources[0].ID); err != nil {
			t.Fatal(err)
		}
		time.Sleep(11 * time.Minute)
		synctest.Wait()
		if e.sd.Requests() != 2 {
			t.Fatalf("a new channel 6 hours on: %d requests", e.sd.Requests())
		}
	})
}

func TestMatchName(t *testing.T) {
	for _, c := range []struct {
		guide   string
		names   []string
		by      string
		flagged bool
	}{
		{"WXXX-DT", []string{"WXXXDT2", "7.2 WXXXDT2", "7.2 WXXX-DT", "7.2", "WXXX-DT", "NET"}, "name", false},
		{"WXXX-HD", []string{"WXXXDT", "7.1 WXXXDT"}, "name", false},
		{"Meadow News", []string{"MDWN", "7.1 Meadow News"}, "name", false},
		{"Unknown", []string{"WYYY"}, "number", false},
		{"Orchard Arts", []string{"WZZZ", "12.2 WZZZ-LD"}, "number", true},
		{"KAAA", []string{"7.2 ", ""}, "number", true},
	} {
		by, flagged := matchName(c.guide, c.names)
		if by != c.by || flagged != c.flagged {
			t.Errorf("%q vs %v = %s %v", c.guide, c.names, by, flagged)
		}
	}
}

func TestWindowQueryUsesItsIndex(t *testing.T) {
	t.Parallel()
	conn, _ := dbtest.Open(t)
	rows, err := conn.QueryContext(t.Context(), `EXPLAIN QUERY PLAN SELECT * FROM live_tv_programmes INDEXED BY live_tv_programme_window
WHERE channel_id IN (?, ?, ?) AND start_at >= ? AND start_at < ? AND end_at > ? ORDER BY channel_id, start_at`, "a", "b", "c", 0, 10, 5)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = rows.Close() }()
	var plan []string
	for rows.Next() {
		var id, parent, unused int
		var detail string
		if err := rows.Scan(&id, &parent, &unused, &detail); err != nil {
			t.Fatal(err)
		}
		plan = append(plan, detail)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	joined := strings.Join(plan, "; ")
	if !strings.Contains(joined, "live_tv_programme_window (channel_id=? AND start_at>? AND start_at<?)") || strings.Contains(joined, "TEMP B-TREE") {
		t.Fatalf("plan = %s", joined)
	}
}

func TestTMDBSwitchRunsEnrich(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		e := newEnv(t, func(h http.Handler) *httptest.Server { return httptest.NewTestServer(t, h) })
		var runs int
		e.g.enrich = func(context.Context) error { runs++; return nil }
		e.on()
		e.g.Fetch(e.ctx)
		if runs != 1 {
			t.Fatalf("after a fetch: %d runs", runs)
		}
		ctx, cancel := context.WithCancel(t.Context())
		done := make(chan struct{})
		go func() { e.g.Run(ctx); close(done) }()
		defer func() { cancel(); <-done }()
		synctest.Wait()
		for i, tmdb := range []bool{false, false, true} {
			if err := e.g.Configure(e.ctx, true, tmdb, ""); err != nil {
				t.Fatal(err)
			}
			synctest.Wait()
			if want := []int{2, 2, 3}[i]; runs != want {
				t.Fatalf("switch %d to %v: %d runs, want %d", i, tmdb, runs, want)
			}
		}
	})
}
