package integrations

import (
	"context"
	"encoding/json"
	"net/http"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/integrations/arr"
	"watchmymedia/server/internal/integrations/seerr"
	"watchmymedia/server/internal/integrations/seerrtest"
)

type seerrEnv struct {
	s             *Service
	q             *db.Queries
	app           *seerrtest.App
	admin, member Person
	movies, tv    db.Library
	ctx           context.Context
}

// setupSeerr connects a fake Seerr whose users are its owner (1), sam (2,
// may request, needs approval) and robin (3, auto-approved), and knows the
// movie 700001 and the show 800001 with two seasons.
func setupSeerr(t *testing.T) seerrEnv {
	t.Helper()
	s, q, _ := setup(t)
	ctx := context.Background()
	s.Configure(fakeTMDB(t), events.NewBus())
	moviesDir, tvDir := filepath.Join(t.TempDir(), "Movies"), filepath.Join(t.TempDir(), "TV")
	movies, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "movie", Name: "Movies", Path: moviesDir})
	tv, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: tvDir})
	admin, _ := q.CreateUser(ctx, db.CreateUserParams{Name: "admin", PasswordHash: "x", IsAdmin: 1})
	member, _ := q.CreateUser(ctx, db.CreateUserParams{Name: "sam", PasswordHash: "x"})

	app := seerrtest.New(t)
	app.Users = append(app.Users,
		seerr.User{ID: 2, DisplayName: "Sam", Username: "sam", Email: "sam@example.test", Permissions: seerr.PermRequest | seerr.PermCreateIssues},
		seerr.User{ID: 3, DisplayName: "robin", Permissions: seerr.PermRequest | seerr.PermAutoApprove})
	app.Items = []*seerrtest.Item{
		{MediaType: "movie", TMDBID: 700001, Title: "Quiet Field", Date: "2019-04-02"},
		{MediaType: "tv", TMDBID: 800001, Title: "Harbor Lights", Date: "2021-01-05", Seasons: []seerr.Season{
			{SeasonNumber: 1, Name: "Season 1", EpisodeCount: 2, AirDate: "2021-01-05"},
			{SeasonNumber: 2, Name: "Season 2", EpisodeCount: 2, AirDate: "2022-01-05"}}},
	}
	if _, err := s.Create(ctx, Input{Kind: "seerr", Name: new("Seerr"), URL: new(app.URL), APIKey: new(app.Key)}); err != nil {
		t.Fatal(err)
	}
	return seerrEnv{s: s, q: q, app: app, admin: Person{ID: admin.ID, Admin: true}, member: Person{ID: member.ID},
		movies: movies, tv: tv, ctx: ctx}
}

func (e seerrEnv) link(t *testing.T, p Person, seerrID int64) {
	t.Helper()
	if err := e.s.LinkSeerrUser(e.ctx, p.ID, seerrID); err != nil {
		t.Fatal(err)
	}
}

func TestCreateSeerrChecksTheVersionAndKey(t *testing.T) {
	s, _, _ := setup(t)
	ctx := context.Background()
	app := seerrtest.New(t)

	if _, err := s.Create(ctx, Input{Kind: "seerr", Name: new("Seerr"), URL: new(app.URL), APIKey: new("wrong")}); err == nil ||
		!strings.Contains(err.Error(), "refused") {
		t.Fatalf("a refused key: %v", err)
	}
	app.Version = "2.7.3" // Jellyseerr
	if _, err := s.Create(ctx, Input{Kind: "seerr", Name: new("Seerr"), URL: new(app.URL), APIKey: new(app.Key)}); err == nil ||
		!strings.Contains(err.Error(), "Seerr 3 or later") {
		t.Fatalf("Jellyseerr was accepted: %v", err)
	}
	app.Version = "3.5.1"
	c, err := s.Create(ctx, Input{Kind: "seerr", Name: new("Seerr"), URL: new(app.URL), APIKey: new(app.Key),
		PathMap: &PathMap{{From: "/x", To: "/y"}}})
	if err != nil {
		t.Fatal(err)
	}
	if c.Kind != "seerr" || !c.Status.OK || c.Status.AppName != "Seerr" || c.Status.Version != "3.5.1" || c.APIKeyHint != "5678" ||
		c.Status.Items != 1 || len(c.PathMap) != 0 {
		t.Errorf("connection = %+v", c)
	}
	if _, err := s.Create(ctx, Input{Kind: "seerr", Name: new("Seerr 2"), URL: new(app.URL), APIKey: new(app.Key)}); err == nil {
		t.Error("a second Seerr was saved")
	}
	if err := s.ConnectWebhook(ctx, c.ID, "http://192.168.1.20:27587"); err == nil {
		t.Error("a webhook was offered for Seerr")
	}
	if d, err := s.Details(ctx, c.ID); err != nil || len(d.RootFolders) != 0 {
		t.Errorf("details = %+v %v", d, err)
	}
	if s.HasArr() || !s.Seerr() {
		t.Errorf("HasArr %v, Seerr %v", s.HasArr(), s.Seerr())
	}
}

func TestSeerrRequestsGoAsTheLinkedUser(t *testing.T) {
	e := setupSeerr(t)
	if _, _, err := e.s.Ask(e.ctx, e.member, "movie", 700001, nil); err == nil {
		t.Fatal("an unlinked user's request was sent")
	} else if st, reason, ok := IsRefused(err); !ok || st != http.StatusForbidden || !strings.Contains(reason, "isn't linked") {
		t.Fatalf("unlinked: %v", err)
	}
	if a := e.s.SeerrAccess(e.ctx, e.member); a.Linked || a.Movies {
		t.Errorf("unlinked access = %+v", a)
	}
	if err := e.s.LinkSeerrUser(e.ctx, e.member.ID, 99); err == nil {
		t.Error("linked to a user Seerr doesn't have")
	}
	e.link(t, e.member, 2)
	if a := e.s.SeerrAccess(e.ctx, e.member); !a.Linked || !a.Movies || !a.Shows || !a.NeedsApproval {
		t.Errorf("sam's access = %+v", a)
	}

	r, created, err := e.s.Ask(e.ctx, e.member, "movie", 700001, nil)
	if err != nil || !created {
		t.Fatalf("ask: %v %v", created, err)
	}
	if r.Status != "pending" || r.Title != "Quiet Field" || r.Year != 2019 || !r.Mine || r.RequestedBy != "Sam" || r.MediaType != "movie" {
		t.Errorf("request = %+v", r)
	}
	calls := e.app.CallsTo(http.MethodPost, "/request")
	if len(calls) != 1 || calls[0].User != "2" {
		t.Fatalf("request calls = %+v", calls)
	}
	if b := calls[0].Body; b["mediaType"] != "movie" || b["mediaId"] != float64(700001) || b["userId"] != nil || b["seasons"] != nil {
		t.Errorf("body = %v", b)
	}

	// Seerr's refusals become messages.
	e.link(t, e.admin, 3)
	if _, _, err := e.s.Ask(e.ctx, e.admin, "movie", 700001, nil); err == nil {
		t.Error("a duplicate was accepted")
	} else if st, reason, _ := IsRefused(err); st != http.StatusConflict || !strings.Contains(reason, "requested in Seerr already") {
		t.Errorf("duplicate: %d %q", st, reason)
	}
	e.app.QuotaLeft[2] = 0
	if _, _, err := e.s.Ask(e.ctx, e.member, "tv", 800001, []int{1}); err == nil {
		t.Error("over quota was accepted")
	} else if st, reason, _ := IsRefused(err); st != http.StatusForbidden || !strings.Contains(reason, "request limit") {
		t.Errorf("quota: %d %q", st, reason)
	}
	delete(e.app.QuotaLeft, 2)
	e.app.Users[1].Permissions = seerr.PermRequestMovie
	if _, _, err := e.s.Ask(e.ctx, e.member, "tv", 800001, []int{1}); err == nil {
		t.Error("a request without permission was accepted")
	} else if st, reason, _ := IsRefused(err); st != http.StatusForbidden || !strings.Contains(reason, "permission to make series requests") {
		t.Errorf("permission: %d %q", st, reason)
	}

	// robin's requests are approved at once; asking again for the same
	// seasons has nothing left to ask for.
	r, _, err = e.s.Ask(e.ctx, e.admin, "tv", 800001, []int{2, 1, 2})
	if err != nil || r.Status != "approved" || len(r.Seasons) != 2 {
		t.Fatalf("robin's request: %+v %v", r, err)
	}
	if got := e.app.CallsTo(http.MethodPost, "/request"); got[len(got)-1].User != "3" {
		t.Errorf("robin's request went as %q", got[len(got)-1].User)
	}
	if _, _, err := e.s.Ask(e.ctx, e.admin, "tv", 800001, []int{1}); err == nil {
		t.Error("asking again was accepted")
	} else if st, reason, _ := IsRefused(err); st != http.StatusConflict || !strings.Contains(reason, "Nothing left") {
		t.Errorf("nothing left: %d %q", st, reason)
	}
	if _, _, err := e.s.Ask(e.ctx, e.admin, "tv", 800001, nil); err == nil {
		t.Error("a show request without seasons was sent")
	}
}

func TestSeerrStateComesFromItsRecord(t *testing.T) {
	e := setupSeerr(t)
	e.link(t, e.member, 2)
	if _, _, err := e.s.Ask(e.ctx, e.member, "tv", 800001, []int{1}); err != nil {
		t.Fatal(err)
	}
	f, err := e.s.SeerrLookup(e.ctx, e.member, "tv", 800001)
	if err != nil {
		t.Fatal(err)
	}
	if f.Title != "Harbor Lights" || f.Year != 2021 || f.Status != seerr.StatusPending || len(f.Seasons) != 2 {
		t.Fatalf("found = %+v", f)
	}
	if !f.Seasons[0].Taken || f.Seasons[1].Taken {
		t.Errorf("seasons = %+v", f.Seasons)
	}
	if len(f.Requests) != 1 || f.Requests[0].Status != "pending" || !f.Requests[0].Mine || f.Requests[0].Seasons[0] != 1 {
		t.Errorf("requests = %+v", f.Requests)
	}
	// The admin isn't the one who asked.
	if st := e.s.ForMedia(e.ctx, e.admin, "tv", 800001); len(st) != 1 || st[0].Mine || st[0].RequestedBy != "Sam" {
		t.Errorf("for the admin = %+v", st)
	}
	// Seerr's record is kept for a minute; a webhook about the show drops it.
	before := len(e.app.CallsTo(http.MethodGet, "/tv/800001"))
	e.s.ForMedia(e.ctx, e.member, "tv", 800001)
	if n := len(e.app.CallsTo(http.MethodGet, "/tv/800001")); n != before {
		t.Errorf("looked up again within the minute (%d calls, was %d)", n, before)
	}
	var grab arr.Event
	if err := json.Unmarshal([]byte(`{"eventType":"Grab","series":{"id":7,"tmdbId":800001}}`), &grab); err != nil {
		t.Fatal(err)
	}
	e.s.handle(e.ctx, &conn{arr: &arr.Client{Kind: arr.Sonarr}, limit: newLimiter()}, grab)
	e.s.ForMedia(e.ctx, e.member, "tv", 800001)
	if n := len(e.app.CallsTo(http.MethodGet, "/tv/800001")); n != before+1 {
		t.Errorf("a webhook didn't drop the record (%d calls, was %d)", n, before)
	}

	// Search sends spaces as %20 and leaves people out.
	found, err := e.s.SeerrSearch(e.ctx, e.member, "harbor lights")
	if err != nil || len(found) != 1 || found[0].TMDBID != 800001 || found[0].Status != seerr.StatusPending {
		t.Fatalf("search = %+v %v", found, err)
	}
	if q := e.app.CallsTo(http.MethodGet, "/search")[0].Query; !strings.Contains(q, "harbor%20lights") || strings.Contains(q, "+") {
		t.Errorf("search query = %q", q)
	}
}

func TestSeerrRequestIsAvailableOnceTheLibraryHasIt(t *testing.T) {
	e := setupSeerr(t)
	e.link(t, e.member, 2)
	if _, _, err := e.s.Ask(e.ctx, e.member, "movie", 700001, nil); err != nil {
		t.Fatal(err)
	}
	e.app.Items[0].Downloads = []seerr.DownloadStatus{{Status: "downloading", Size: 1000, SizeLeft: 250}}
	e.app.Requests[0].Status = seerr.RequestApproved
	mine, err := e.s.Mine(e.ctx, e.member)
	if err != nil || len(mine) != 1 {
		t.Fatalf("mine = %+v %v", mine, err)
	}
	if r := mine[0]; r.Status != "approved" || r.Title != "Quiet Field" || r.Download == nil || r.Download.Progress != 0.75 {
		t.Errorf("approved = %+v %+v", r, r.Download)
	}
	if q := e.app.CallsTo(http.MethodGet, "/request")[0]; q.User != "2" || !strings.Contains(q.Query, "requestedBy=2") {
		t.Errorf("list call = %+v", q)
	}

	m, _ := e.q.UpsertMovie(e.ctx, db.UpsertMovieParams{LibraryID: e.movies.ID, TmdbID: 700001, Title: "Quiet Field", Genres: "[]", Credits: "[]"})
	f, _ := e.q.UpsertMediaFile(e.ctx, db.UpsertMediaFileParams{LibraryID: e.movies.ID, Path: filepath.Join(e.movies.Path, "Quiet Field (2019)", "q.mkv"),
		Size: 1, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()), Parsed: "{}", LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	if err := e.q.SetMediaFileMatch(e.ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &m.ID, ID: f.ID}); err != nil {
		t.Fatal(err)
	}
	mine, _ = e.s.Mine(e.ctx, e.member)
	if len(mine) != 1 || mine[0].Status != "available" || mine[0].MovieID != m.ID {
		t.Errorf("mine = %+v", mine)
	}
	// Asking again for what the library has goes nowhere near Seerr.
	before := len(e.app.CallsTo(http.MethodPost, "/request"))
	if _, _, err := e.s.Ask(e.ctx, e.member, "movie", 700001, nil); err == nil || !strings.Contains(err.Error(), "in your library already") {
		t.Errorf("asked for what the library has: %v", err)
	}
	if n := len(e.app.CallsTo(http.MethodPost, "/request")); n != before {
		t.Errorf("Seerr was asked (%d calls, was %d)", n, before)
	}
	// Someone not linked has no requests in Seerr.
	if mine, err := e.s.Mine(e.ctx, e.admin); err != nil || len(mine) != 0 {
		t.Errorf("admin's = %+v %v", mine, err)
	}
}

func TestSeerrCancelWithdrawsAWaitingRequest(t *testing.T) {
	e := setupSeerr(t)
	e.link(t, e.member, 2)
	r, _, err := e.s.Ask(e.ctx, e.member, "movie", 700001, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := e.s.Cancel(e.ctx, e.member, r.ID); err != nil {
		t.Fatal(err)
	}
	if len(e.app.Requests) != 0 {
		t.Errorf("Seerr still has %d requests", len(e.app.Requests))
	}
	if err := e.s.Cancel(e.ctx, e.member, r.ID); !IsRequestNotFound(err) {
		t.Errorf("cancel twice: %v", err)
	}
	r, _, _ = e.s.Ask(e.ctx, e.member, "movie", 700001, nil)
	e.app.Requests[0].Status = seerr.RequestApproved
	if err := e.s.Cancel(e.ctx, e.member, r.ID); err == nil {
		t.Error("an approved request was cancelled")
	}
}

func TestSeerrProblemsBecomeIssues(t *testing.T) {
	e := setupSeerr(t)
	show, _ := e.q.UpsertShow(e.ctx, db.UpsertShowParams{LibraryID: e.tv.ID, TmdbID: 800001, Title: "Harbor Lights", Genres: "[]", Credits: "[]"})
	season, _ := e.q.UpsertSeason(e.ctx, db.UpsertSeasonParams{ShowID: show.ID, Number: 2})
	ep, _ := e.q.UpsertEpisode(e.ctx, db.UpsertEpisodeParams{SeasonID: season.ID, ShowID: show.ID, Number: 3, Title: "Tide"})

	// Not linked: the report goes to the Inbox.
	p, err := e.s.Report(e.ctx, e.member, "episode", ep.ID, 0, "audio", "out of sync")
	if err != nil || p.ID == 0 || p.SeerrIssueID != 0 {
		t.Fatalf("unlinked report = %+v %v", p, err)
	}
	e.link(t, e.member, 2)
	// Seerr has no record of the show yet: still the Inbox.
	if p, err := e.s.Report(e.ctx, e.member, "episode", ep.ID, 0, "audio", "out of sync"); err != nil || p.ID == 0 || p.SeerrIssueID != 0 {
		t.Fatalf("no record = %+v %v", p, err)
	}
	e.app.Items[1].MediaID, e.app.Items[1].Status = 41, seerr.StatusAvailable
	e.s.dropSeerr("tv", 800001)
	p, err = e.s.Report(e.ctx, e.member, "episode", ep.ID, 0, "audio", "out of sync")
	if err != nil || p.ID != 0 || p.SeerrIssueID == 0 || p.ReportedBy != "sam" || !strings.Contains(p.Title, "S02E03") {
		t.Fatalf("issue = %+v %v", p, err)
	}
	calls := e.app.CallsTo(http.MethodPost, "/issue")
	if len(calls) != 1 || calls[0].User != "2" {
		t.Fatalf("issue calls = %+v", calls)
	}
	b := calls[0].Body
	if b["mediaId"] != float64(41) || b["issueType"] != float64(seerr.IssueAudio) || b["problemSeason"] != float64(2) || b["problemEpisode"] != float64(3) ||
		!strings.HasPrefix(b["message"].(string), "out of sync") {
		t.Errorf("issue body = %v", b)
	}
	if open, _ := e.s.Problems(e.ctx); len(open) != 2 {
		t.Errorf("Inbox has %d problems, want the 2 Seerr couldn't take", len(open))
	}
}

func TestSeerrForgetClearsTheRecord(t *testing.T) {
	e := setupSeerr(t)
	// Nothing to clear.
	if err := e.s.SeerrForget(e.ctx, "movie", 700001, nil); err != nil {
		t.Fatal(err)
	}
	if n := len(e.app.CallsTo(http.MethodDelete, "/media/0")); n != 0 {
		t.Errorf("cleared a record Seerr doesn't have")
	}
	e.app.Items[0].MediaID, e.app.Items[0].Status = 40, seerr.StatusAvailable
	e.app.Items[1].MediaID, e.app.Items[1].Status = 41, seerr.StatusAvailable
	if err := e.s.SeerrForget(e.ctx, "movie", 700001, nil); err != nil {
		t.Fatal(err)
	}
	if len(e.app.CallsTo(http.MethodDelete, "/media/40")) != 1 || len(e.app.CallsTo(http.MethodPost, "/settings/jobs/sonarr-scan/run")) != 0 {
		t.Errorf("movie: calls = %+v", e.app.Calls)
	}
	if err := e.s.SeerrForget(e.ctx, "tv", 800001, []int{2}); err != nil {
		t.Fatal(err)
	}
	if len(e.app.CallsTo(http.MethodDelete, "/media/41")) != 1 || len(e.app.CallsTo(http.MethodPost, "/settings/jobs/sonarr-scan/run")) != 1 {
		t.Errorf("season: calls = %+v", e.app.Calls)
	}
	if e.app.Items[1].MediaID != 0 {
		t.Error("the show's record is still there")
	}

	// Without Seerr it does nothing.
	s, _, _ := setup(t)
	if err := s.SeerrForget(e.ctx, "movie", 700001, nil); err != nil {
		t.Error(err)
	}
}

func TestSeerrRequestChangesRefreshCachedMedia(t *testing.T) {
	e := setupSeerr(t)
	e.link(t, e.member, 2)

	// Warm the item before requesting it: creation must replace that empty
	// state immediately, without waiting for the one-minute cache lifetime.
	before, err := e.s.SeerrLookup(e.ctx, e.member, "movie", 700001)
	if err != nil || len(before.Requests) != 0 {
		t.Fatalf("before request: %+v, %v", before, err)
	}
	r, _, err := e.s.Ask(e.ctx, e.member, "movie", 700001, nil)
	if err != nil {
		t.Fatal(err)
	}
	after, err := e.s.SeerrLookup(e.ctx, e.member, "movie", 700001)
	if err != nil || len(after.Requests) != 1 || after.Requests[0].ID != r.ID {
		t.Fatalf("after request: %+v, %v", after, err)
	}

	// This lookup now caches the pending request. After a successful cancel,
	// pages must read Seerr's updated item rather than keep that old request.
	if err := e.s.Cancel(e.ctx, e.member, r.ID); err != nil {
		t.Fatal(err)
	}
	after, err = e.s.SeerrLookup(e.ctx, e.member, "movie", 700001)
	if err != nil || len(after.Requests) != 0 {
		t.Fatalf("after cancellation: %+v, %v", after, err)
	}
}

func TestSeerrInboxLinksToItsRequests(t *testing.T) {
	e := setupSeerr(t)
	e.link(t, e.member, 2)
	if _, _, err := e.s.Ask(e.ctx, e.member, "movie", 700001, nil); err != nil {
		t.Fatal(err)
	}
	in := e.s.SeerrInbox(e.ctx)
	if in == nil || in.RequestsURL != e.app.URL+"/requests?filter=pending" || in.Pending == nil || *in.Pending != 1 {
		t.Fatalf("inbox = %+v", in)
	}
	e.app.ApplicationURL = "https://requests.example.test/"
	conns := e.s.List()
	e.s.Refresh(e.ctx, conns[0].ID)
	if in := e.s.SeerrInbox(e.ctx); in.RequestsURL != "https://requests.example.test/requests?filter=pending" {
		t.Errorf("with an application URL: %+v", in)
	}
}
