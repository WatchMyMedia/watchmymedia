package api

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/playback"
)

// directProfile plays the synthetic MP4 as it is.
const directProfile = `{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"],"subtitleFormats":["webvtt"]}`

// offlineEnv is a test server with a movie whose file is real bytes on disk
// (a fake MP4 the profile plays directly), an admin's phone and a member's.
type offlineEnv struct {
	*testEnv
	file   db.MediaFile
	movie  int64
	path   string
	admin  SignIn // admin's phone
	member SignIn // sam's phone
}

func newOfflineEnv(t *testing.T, adjust func(*Deps)) *offlineEnv {
	t.Helper()
	srv := newTestServerWith(t, adjust)
	ctx := t.Context()
	dir := t.TempDir()
	path := filepath.Join(dir, "Arrival (2016).mp4")
	data := make([]byte, 64<<10)
	_, _ = rand.Read(data)
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	st, _ := os.Stat(path)
	probeJSON := `{"container":"mov,mp4,m4a,3gp,3g2,mj2","durationMs":600000,"bitRate":2000000,"video":[{"index":0,"codec":"h264","width":640,"height":360,"frameRate":24}],"audio":[{"index":1,"codec":"aac","channels":2,"default":true}],"subtitles":[]}`
	lib := srv.fileLibrary(t, path)
	f, err := srv.q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: lib, Path: path, Size: st.Size(), Mtime: db.FormatTime(st.ModTime()),
		AddedAt: db.FormatTime(time.Now()), Parsed: "{}", Probe: &probeJSON, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	m, err := srv.q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: lib, TmdbID: 329865, Title: "Arrival", Genres: "[]"})
	if err != nil {
		t.Fatal(err)
	}
	if err := srv.q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &m.ID, ID: f.ID}); err != nil {
		t.Fatal(err)
	}
	if _, err := srv.q.CreateUser(ctx, db.CreateUserParams{Name: "sam", PasswordHash: adminHash()}); err != nil {
		t.Fatal(err)
	}
	return &offlineEnv{testEnv: srv, file: f, movie: m.ID, path: path,
		admin:  deviceLogin(t, srv.URL, "admin", "password1", "admin-phone", "Robin's Pixel"),
		member: deviceLogin(t, srv.URL, "sam", "password1", "sam-phone", "Sam's phone")}
}

// as makes a request with a device's bearer token.
func (e *offlineEnv) as(t *testing.T, who SignIn, method, path, body string, out any) reply {
	t.Helper()
	return doJSON(t, nil, method, e.URL+path, body, out, "Authorization", "Bearer "+who.Token)
}

func (e *offlineEnv) setAccess(t *testing.T, access string) {
	t.Helper()
	if code := doJSON(t, e.client, http.MethodPut, e.URL+"/api/settings/offline", `{"access":"`+access+`"}`, nil).StatusCode; code != http.StatusOK {
		t.Fatalf("set access %s -> %d", access, code)
	}
}

// download makes a download of the file for a device and returns it.
func (e *offlineEnv) download(t *testing.T, who SignIn, device, body string) (OfflineDownload, int) {
	t.Helper()
	var d OfflineDownload
	if body == "" {
		body = `{"profile":` + directProfile + `,"client":{"deviceId":"` + device + `","name":"phone"}}`
	}
	code := e.as(t, who, http.MethodPost, fmt.Sprintf("/api/files/%d/offline", e.file.ID), body, &d).StatusCode
	return d, code
}

func (e *offlineEnv) token(t *testing.T, who SignIn, id string) (OfflineToken, int) {
	t.Helper()
	var tok OfflineToken
	code := e.as(t, who, http.MethodPost, "/api/offline/downloads/"+id+"/token", "", &tok).StatusCode
	return tok, code
}

func (e *offlineEnv) sync(t *testing.T, who SignIn, device, extra string) (OfflineSync, int) {
	t.Helper()
	var out OfflineSync
	body := `{"client":{"deviceId":"` + device + `"},"sentAt":"` + time.Now().UTC().Format(time.RFC3339Nano) + `"` + extra + `}`
	code := e.as(t, who, http.MethodPost, "/api/offline/sync", body, &out).StatusCode
	return out, code
}

// get fetches a media URL with no header, as a downloader does.
func get(t *testing.T, url string, header ...string) (int, http.Header, []byte) {
	t.Helper()
	r := doJSON(t, nil, http.MethodGet, url, "", nil, header...)
	return r.StatusCode, r.Header, r.Body
}

// PLAN-DOWNLOADS.md §4.3: who may download, for admins and members under
// none, admins (the default) and everyone, on every route.
func TestOfflineAccess(t *testing.T) {
	t.Parallel()
	e := newOfflineEnv(t, nil)
	var s OfflineSettings
	if doJSON(t, e.client, http.MethodGet, e.URL+"/api/settings/offline", "", &s); s.Access != "admins" {
		t.Errorf("default access = %q, want admins", s.Access)
	}
	if code := e.as(t, e.member, http.MethodPut, "/api/settings/offline", `{"access":"everyone"}`, nil).StatusCode; code != http.StatusForbidden {
		t.Errorf("member changing access -> %d", code)
	}
	if code := doJSON(t, e.client, http.MethodPut, e.URL+"/api/settings/offline", `{"access":"sometimes"}`, nil).StatusCode; code != http.StatusUnprocessableEntity {
		t.Errorf("bad access -> %d", code)
	}
	plan := fmt.Sprintf("/api/files/%d/offline/plan", e.file.ID)
	for _, c := range []struct {
		access        string
		admin, member bool
	}{{"none", false, false}, {"admins", true, false}, {"everyone", true, true}} {
		e.setAccess(t, c.access)
		for _, who := range []struct {
			name   string
			signIn SignIn
			device string
			may    bool
		}{{"admin", e.admin, "admin-phone", c.admin}, {"member", e.member, "sam-phone", c.member}} {
			want := http.StatusForbidden
			if who.may {
				want = http.StatusOK
			}
			var opts RequestOptions
			e.as(t, who.signIn, http.MethodGet, "/api/requests/options", "", &opts)
			if opts.Offline != who.may {
				t.Errorf("%s, %s: RequestOptions.offline = %v", c.access, who.name, opts.Offline)
			}
			if code := e.as(t, who.signIn, http.MethodPost, plan, `{"profile":`+directProfile+`}`, nil).StatusCode; code != want {
				t.Errorf("%s, %s: plan -> %d, want %d", c.access, who.name, code, want)
			}
			d, code := e.download(t, who.signIn, who.device, "")
			if who.may && code != http.StatusCreated || !who.may && code != http.StatusForbidden {
				t.Errorf("%s, %s: create -> %d", c.access, who.name, code)
			}
			if !who.may {
				continue
			}
			if _, code := e.token(t, who.signIn, d.ID); code != http.StatusOK {
				t.Errorf("%s, %s: token -> %d", c.access, who.name, code)
			}
			if code, _, _ := get(t, e.URL+d.URL, "Authorization", "Bearer "+who.signIn.Token); code != http.StatusOK {
				t.Errorf("%s, %s: stream -> %d", c.access, who.name, code)
			}
			if out, code := e.sync(t, who.signIn, who.device, ""); code != http.StatusOK || !out.Offline || len(out.Downloads) != 1 || out.Downloads[0].State != "ready" {
				t.Errorf("%s, %s: sync -> %d %+v", c.access, who.name, code, out)
			}
		}
	}
	// Withdrawn: the member's download reads as revoked, its media and
	// token are refused, and the sync revokes it for good.
	e.setAccess(t, "admins")
	var list OfflineDownloadList
	doJSON(t, e.client, http.MethodGet, e.URL+"/api/offline/downloads?all=true", "", &list)
	var sams OfflineDownload
	for _, d := range list.Items {
		if d.User != nil && d.User.Name == "sam" {
			sams = d
		}
	}
	if sams.State != "revoked" || sams.RevokedBy != "access" {
		t.Fatalf("sam's download after withdrawal = %+v", sams)
	}
	if code, _, _ := get(t, e.URL+sams.URL, "Authorization", "Bearer "+e.member.Token); code != http.StatusForbidden {
		t.Errorf("stream after withdrawal -> %d", code)
	}
	if _, code := e.token(t, e.member, sams.ID); code != http.StatusForbidden {
		t.Errorf("token after withdrawal -> %d", code)
	}
	out, _ := e.sync(t, e.member, "sam-phone", "")
	if out.Offline || len(out.Downloads) != 1 || out.Downloads[0].State != "revoked" || out.Downloads[0].RevokedBy != "access" {
		t.Errorf("sync after withdrawal = %+v", out)
	}
	rec, _ := e.q.GetOfflineDownload(t.Context(), sams.ID)
	if rec.State != "revoked" || rec.RevokedBy != "access" {
		t.Errorf("stored = %s by %s, want revoked for good", rec.State, rec.RevokedBy)
	}
	// Opened again, it stays revoked: the device deletes it at this sync.
	e.setAccess(t, "everyone")
	if out, _ := e.sync(t, e.member, "sam-phone", ""); !out.Offline || out.Downloads[0].State != "revoked" {
		t.Errorf("sync after reopening = %+v", out)
	}
}

// PLAN-DOWNLOADS.md §4.2: an offline token opens its own record's media
// and nothing else; media tokens never open offline routes; a revoked
// record or a signed-out device is refused.
func TestOfflineTokenScope(t *testing.T) {
	t.Parallel()
	e := newOfflineEnv(t, nil)
	a, _ := e.download(t, e.admin, "admin-phone", "")
	tok, code := e.token(t, e.admin, a.ID)
	if code != http.StatusOK || !strings.HasPrefix(tok.Token, "o1.") || tok.Param != "access_token" || time.Until(tok.ExpiresAt) > auth.OfflineTokenTTL {
		t.Fatalf("token -> %d %+v", code, tok)
	}
	// Another of the admin's downloads, on another device.
	tablet := deviceLogin(t, e.URL, "admin", "password1", "admin-tablet", "Robin's tablet")
	b, _ := e.download(t, tablet, "admin-tablet", "")
	var media MediaToken
	e.as(t, e.admin, http.MethodPost, "/api/auth/media-token", "", &media)
	q := "?access_token=" + tok.Token
	for _, c := range []struct {
		url  string
		want int
	}{
		{e.URL + a.URL + q, 200},
		{e.URL + b.URL + q, 401},
		{e.URL + "/api/offline/downloads" + q, 401},
		{e.URL + "/api/libraries" + q, 401},
		{fmt.Sprintf("%s/api/files/%d/stream%s", e.URL, e.file.ID, q), 401},
		{e.URL + a.URL + "?access_token=" + media.Token, 401},
	} {
		if code, _, _ := get(t, c.url); code != c.want {
			t.Errorf("GET %s -> %d, want %d", strings.TrimPrefix(c.url, e.URL), code, c.want)
		}
	}
	// A member cannot get a token for someone else's download.
	e.setAccess(t, "everyone")
	if _, code := e.token(t, e.member, a.ID); code != http.StatusNotFound {
		t.Errorf("member's token for the admin's download -> %d", code)
	}
	// Revoked: the token stops working at once.
	if code := doJSON(t, e.client, http.MethodDelete, e.URL+"/api/offline/downloads/"+a.ID+"?revoke=true", "", nil).StatusCode; code != http.StatusNoContent {
		t.Fatalf("revoke -> %d", code)
	}
	if code, _, _ := get(t, e.URL+a.URL+q); code != http.StatusForbidden {
		t.Errorf("revoked -> %d", code)
	}
	if _, code := e.token(t, e.admin, a.ID); code != http.StatusForbidden {
		t.Errorf("token for a revoked download -> %d", code)
	}
	// Signing the tablet out ends its tokens and its syncs.
	tabletTok, _ := e.token(t, tablet, b.ID)
	if code := e.as(t, tablet, http.MethodPost, "/api/auth/logout", "", nil).StatusCode; code != http.StatusNoContent {
		t.Fatalf("logout -> %d", code)
	}
	if code, _, _ := get(t, e.URL+b.URL+"?access_token="+tabletTok.Token); code != http.StatusUnauthorized {
		t.Errorf("signed out -> %d", code)
	}
	if _, code := e.sync(t, tablet, "admin-tablet", ""); code != http.StatusUnauthorized {
		t.Errorf("signed-out sync -> %d", code)
	}
}

// A direct download is the original file with Range, counted as a
// transfer and on its record, and never a play. A file changed on disk, or
// replaced by a new version, is refused (410) and marked.
func TestOfflineDirectDownload(t *testing.T) {
	t.Parallel()
	var tracker *activity.Tracker
	e := newOfflineEnv(t, func(d *Deps) {
		tracker = activity.New(d.Conn, d.Bus, nil, activity.Options{})
		d.Activity = tracker
	})
	var plan OfflinePlan
	if code := e.as(t, e.admin, http.MethodPost, fmt.Sprintf("/api/files/%d/offline/plan", e.file.ID), `{"profile":`+directProfile+`}`, &plan).StatusCode; code != 200 {
		t.Fatalf("plan -> %d", code)
	}
	if len(plan.Qualities) != 4 || plan.Qualities[0].Quality != "original" || plan.Qualities[0].Mode != "direct" || plan.Qualities[0].SizeBytes != e.file.Size || plan.Qualities[0].SizeEstimated {
		t.Fatalf("plan = %+v", plan.Qualities)
	}
	d, code := e.download(t, e.admin, "admin-phone", "")
	if code != http.StatusCreated || d.Mode != "direct" || d.Protocol != "file" || d.URL != "/api/offline/"+d.ID+"/stream" || d.SizeBytes != e.file.Size ||
		d.State != "ready" || d.Quality != "original" || d.Item.Title != "Arrival" || d.Decision == nil || d.Decision.URL != d.URL {
		t.Fatalf("create -> %d %+v", code, d)
	}
	tok, _ := e.token(t, e.admin, d.ID)
	url := e.URL + d.URL + "?access_token=" + tok.Token
	data, _ := os.ReadFile(e.path)
	code, h, body := get(t, url, "Range", "bytes=1000-1999")
	if code != http.StatusPartialContent || string(body) != string(data[1000:2000]) || h.Get("Accept-Ranges") != "bytes" {
		t.Fatalf("range -> %d, %d bytes", code, len(body))
	}
	code, _, body = get(t, url, "Range", "bytes=2000-")
	if code != http.StatusPartialContent || len(body) != len(data)-2000 {
		t.Fatalf("rest -> %d, %d bytes", code, len(body))
	}
	var list OfflineDownloadList
	e.as(t, e.admin, http.MethodGet, "/api/offline/downloads", "", &list)
	if len(list.Transfers) != 1 || list.Transfers[0].Progress != 1 || list.Transfers[0].BytesSent != int64(len(data)-1000) ||
		list.Transfers[0].Network != "local" || list.Transfers[0].DeviceName != "phone" {
		t.Errorf("transfers = %+v", list.Transfers)
	}
	if len(list.Items) != 1 || list.Items[0].BytesSent != int64(len(data)-1000) || list.Items[0].DeviceState != "downloading" {
		t.Errorf("items = %+v", list.Items)
	}
	// A download is not a play.
	if n := tracker.Playing(); n != 0 {
		t.Errorf("plays now = %d", n)
	}
	var plays int
	_ = e.conn.QueryRowContext(t.Context(), "SELECT COUNT(*) FROM plays").Scan(&plays)
	if plays != 0 {
		t.Errorf("history has %d plays", plays)
	}
	// Changed on disk before any scan: refused.
	later := time.Now().Add(time.Hour)
	_ = os.Chtimes(e.path, later, later)
	if code, _, _ := get(t, url); code != http.StatusGone {
		t.Errorf("changed file -> %d", code)
	}
	// The scan sees the new version in place: replaced by the same file.
	st, _ := os.Stat(e.path)
	if _, err := e.conn.ExecContext(t.Context(), "UPDATE media_files SET mtime = ? WHERE id = ?", db.FormatTime(st.ModTime()), e.file.ID); err != nil {
		t.Fatal(err)
	}
	out, _ := e.sync(t, e.admin, "admin-phone", `,"downloads":[{"id":"`+d.ID+`","deviceState":"downloaded","sizeBytes":65536}]`)
	if len(out.Downloads) != 1 || out.Downloads[0].State != "replaced" || out.Downloads[0].ReplacedBy != e.file.ID || out.Downloads[0].DeviceState != "downloaded" {
		t.Errorf("after an in-place upgrade = %+v", out.Downloads)
	}
	if _, code := e.token(t, e.admin, d.ID); code != http.StatusGone {
		t.Errorf("token for a replaced download -> %d", code)
	}
	// The file goes and nothing replaces it: gone.
	if _, err := e.conn.ExecContext(t.Context(), "UPDATE media_files SET status = 'missing' WHERE id = ?", e.file.ID); err != nil {
		t.Fatal(err)
	}
	if out, _ := e.sync(t, e.admin, "admin-phone", ""); out.Downloads[0].State != "gone" {
		t.Errorf("after the file went = %+v", out.Downloads)
	}
	// The device removes it: the record goes.
	if out, _ := e.sync(t, e.admin, "admin-phone", `,"downloads":[{"id":"`+d.ID+`","deviceState":"removed"}]`); len(out.Downloads) != 0 {
		t.Errorf("after removal = %+v", out.Downloads)
	}
}

// Revoking and removing (PLAN-DOWNLOADS.md §4.1, §8): an admin revokes a
// member's download, which stays listed until its device syncs and removes
// it; the owner's app removes its own record; a device downloading a file
// again replaces its download.
func TestOfflineRevokeAndRemove(t *testing.T) {
	t.Parallel()
	e := newOfflineEnv(t, nil)
	e.setAccess(t, "everyone")
	sam, _ := e.download(t, e.member, "sam-phone", "")
	if again, _ := e.download(t, e.member, "sam-phone", ""); again.ID == sam.ID {
		t.Error("downloading again kept the old record")
	} else if _, err := e.q.GetOfflineDownload(t.Context(), sam.ID); err == nil {
		t.Error("the replaced record is still there")
	} else {
		sam = again
	}
	// Members can neither revoke nor see others' downloads.
	mine, _ := e.download(t, e.admin, "admin-phone", "")
	if code := e.as(t, e.member, http.MethodDelete, "/api/offline/downloads/"+mine.ID, "", nil).StatusCode; code != http.StatusNotFound {
		t.Errorf("member deleting the admin's -> %d", code)
	}
	if code := e.as(t, e.member, http.MethodGet, "/api/offline/downloads?all=true", "", nil).StatusCode; code != http.StatusForbidden {
		t.Errorf("member listing everyone's -> %d", code)
	}
	// The admin revokes Sam's from Activity.
	if code := doJSON(t, e.client, http.MethodDelete, e.URL+"/api/offline/downloads/"+sam.ID, "", nil).StatusCode; code != http.StatusNoContent {
		t.Fatalf("revoke -> %d", code)
	}
	var list OfflineDownloadList
	doJSON(t, e.client, http.MethodGet, e.URL+"/api/offline/downloads?all=true", "", &list)
	if len(list.Items) != 2 || list.Items[1].ID != sam.ID || list.Items[1].State != "revoked" || list.Items[1].RevokedBy != "admin" ||
		list.Items[1].RevokedByName != "admin" || list.Items[1].RevokedAt == nil || list.Items[1].User.Name != "sam" {
		t.Errorf("everyone's = %+v", list.Items)
	}
	out, _ := e.sync(t, e.member, "sam-phone", "")
	if len(out.Downloads) != 1 || out.Downloads[0].State != "revoked" {
		t.Errorf("sam's sync = %+v", out.Downloads)
	}
	// Another device's sync knows nothing of it.
	if out, _ := e.sync(t, e.member, "sam-tablet", `,"downloads":[{"id":"`+sam.ID+`","deviceState":"removed"}]`); len(out.Downloads) != 0 {
		t.Errorf("another device's sync = %+v", out.Downloads)
	}
	if _, err := e.q.GetOfflineDownload(t.Context(), sam.ID); err != nil {
		t.Error("another device removed sam's phone's record")
	}
	// Sam's phone deletes the copy and says so: the record goes.
	e.sync(t, e.member, "sam-phone", `,"downloads":[{"id":"`+sam.ID+`","deviceState":"removed"}]`)
	if _, err := e.q.GetOfflineDownload(t.Context(), sam.ID); err == nil {
		t.Error("the revoked record outlived its removal")
	}
	// The owner removes their own record.
	if code := e.as(t, e.admin, http.MethodDelete, "/api/offline/downloads/"+mine.ID, "", nil).StatusCode; code != http.StatusNoContent {
		t.Fatalf("owner's delete -> %d", code)
	}
	if _, err := e.q.GetOfflineDownload(t.Context(), mine.ID); err == nil {
		t.Error("the owner's delete kept the record")
	}
	// A deleted person's downloads go with them.
	d, _ := e.download(t, e.member, "sam-phone", "")
	var sam2 db.User
	sam2, _ = e.q.GetUserByName(t.Context(), "sam")
	if code := doJSON(t, e.client, http.MethodDelete, fmt.Sprintf("%s/api/users/%d", e.URL, sam2.ID), "", nil).StatusCode; code != http.StatusNoContent {
		t.Fatalf("delete user -> %d", code)
	}
	if _, err := e.q.GetOfflineDownload(t.Context(), d.ID); err == nil {
		t.Error("a deleted person's download is still recorded")
	}
}

// syncOffline's table (PLAN-DOWNLOADS.md §6) through the API: offline
// progress applies by when it happened, corrected for the device's clock;
// a play since on the server stays; a position past the watched point
// marks the item watched; a gone file is dropped; History is untouched.
func TestOfflineSyncProgress(t *testing.T) {
	t.Parallel()
	e := newOfflineEnv(t, nil)
	ctx := t.Context()
	d, _ := e.download(t, e.admin, "admin-phone", "")
	entry := func(fields string, at time.Time) string {
		return fmt.Sprintf(`{"downloadId":%q,"fileId":%d,%s,"at":%q}`, d.ID, e.file.ID, fields, at.UTC().Format(time.RFC3339Nano))
	}
	sync := func(sentAt time.Time, entries ...string) OfflineSync {
		t.Helper()
		var out OfflineSync
		body := fmt.Sprintf(`{"client":{"deviceId":"admin-phone"},"sentAt":%q,"progress":[%s]}`, sentAt.UTC().Format(time.RFC3339Nano), strings.Join(entries, ","))
		if code := e.as(t, e.admin, http.MethodPost, "/api/offline/sync", body, &out).StatusCode; code != 200 {
			t.Fatalf("sync -> %d", code)
		}
		return out
	}
	state := func() db.WatchState {
		w, err := e.q.GetWatchState(ctx, db.GetWatchStateParams{UserID: 1, ItemType: "movie", ItemID: e.movie})
		if err != nil {
			t.Fatal(err)
		}
		return w
	}
	// The phone's clock runs two hours fast: it says it watched "an hour
	// from now", which was ten minutes before it synced... an hour and fifty
	// minutes ago by its clock, so ten minutes ago here.
	now := time.Now()
	skewed := now.Add(2 * time.Hour)
	out := sync(skewed, entry(`"positionMs":120000,"durationMs":600000`, skewed.Add(-10*time.Minute)))
	if len(out.Progress) != 1 || out.Progress[0].Outcome != "applied" || out.Progress[0].WatchState == nil || out.Progress[0].WatchState.PositionMs != 120000 {
		t.Fatalf("progress = %+v", out.Progress)
	}
	if got := db.ParseTime(state().UpdatedAt); got.Sub(now.Add(-10*time.Minute)).Abs() > 5*time.Second {
		t.Errorf("saved at %s, want about ten minutes ago", got)
	}
	// Played on the TV since: an older offline report is dropped.
	if code := doJSON(t, e.client, http.MethodPut, fmt.Sprintf("%s/api/files/%d/progress", e.URL, e.file.ID), `{"positionMs":300000,"durationMs":600000}`, nil).StatusCode; code != 200 {
		t.Fatalf("progress -> %d", code)
	}
	out = sync(time.Now(), entry(`"positionMs":200000`, time.Now().Add(-5*time.Minute)))
	if out.Progress[0].Outcome != "newer" || state().PositionMs != 300000 {
		t.Errorf("older offline report = %+v, state %d", out.Progress[0], state().PositionMs)
	}
	// Two entries out of order, on an item with nothing on the server:
	// applied oldest first, so the mark wins. A time ahead of the server
	// even after correction counts as now.
	for _, table := range []string{"watch_state", "watch_guards"} {
		if _, err := e.conn.ExecContext(ctx, "DELETE FROM "+table); err != nil { //nolint:gosec // G202: a table name from this test
			t.Fatal(err)
		}
	}
	out = sync(time.Now(), entry(`"watched":true`, time.Now().Add(time.Hour)), entry(`"positionMs":550000,"durationMs":600000`, time.Now().Add(-time.Minute)))
	if out.Progress[0].Outcome != "applied" || out.Progress[1].Outcome != "applied" || !out.Progress[0].WatchState.Completed {
		t.Errorf("mark and report = %+v", out.Progress)
	}
	if w := state(); w.Completed != 1 || db.ParseTime(w.UpdatedAt).After(time.Now()) {
		t.Errorf("state = %+v", w)
	}
	// Past the watched point (92% without credits): watched.
	if _, err := e.conn.ExecContext(ctx, "DELETE FROM watch_guards"); err != nil {
		t.Fatal(err)
	}
	if _, err := e.conn.ExecContext(ctx, "DELETE FROM watch_state"); err != nil {
		t.Fatal(err)
	}
	out = sync(time.Now(), entry(`"positionMs":580000,"durationMs":600000`, time.Now().Add(-time.Minute)))
	if w := out.Progress[0].WatchState; out.Progress[0].Outcome != "applied" || w == nil || !w.Completed || w.PositionMs != 0 {
		t.Errorf("past the credits = %+v", out.Progress[0])
	}
	// Malformed entries are refused whole.
	body := fmt.Sprintf(`{"client":{"deviceId":"admin-phone"},"sentAt":%q,"progress":[{"fileId":%d,"at":%q}]}`, time.Now().UTC().Format(time.RFC3339), e.file.ID, time.Now().UTC().Format(time.RFC3339))
	if code := e.as(t, e.admin, http.MethodPost, "/api/offline/sync", body, nil).StatusCode; code != http.StatusUnprocessableEntity {
		t.Errorf("entry with neither position nor mark -> %d", code)
	}
	// The file went from the server: dropped.
	if _, err := e.conn.ExecContext(ctx, "UPDATE media_files SET status = 'missing' WHERE id = ?", e.file.ID); err != nil {
		t.Fatal(err)
	}
	before := state()
	if out := sync(time.Now(), entry(`"positionMs":1000`, time.Now())); out.Progress[0].Outcome != "unavailable" || state().UpdatedAt != before.UpdatedAt {
		t.Errorf("gone file = %+v", out.Progress[0])
	}
	var plays int
	_ = e.conn.QueryRowContext(ctx, "SELECT COUNT(*) FROM plays").Scan(&plays)
	if plays != 0 {
		t.Errorf("history has %d plays", plays)
	}
}

func TestOfflineRoute(t *testing.T) {
	t.Parallel()
	for p, want := range map[string]string{
		"/api/offline/abc/stream":                 "abc",
		"/api/offline/abc/hls/master.m3u8":        "abc",
		"/api/offline/abc/subtitles/x0.vtt":       "abc",
		"/api/offline/downloads/abc/token":        "",
		"/api/offline/downloads":                  "",
		"/api/offline/sync":                       "",
		"/api/offline/abc/hls/x/../../files":      "",
		"/api/offline/abc/streams":                "",
		"/api/files/1/stream":                     "",
		"/api/offline/abc/subtitles/x/../y.vtt":   "",
		"/api/offline//stream":                    "",
		"/api/offline/abc/hls/v00001.m4s":         "abc",
		"/api/offline/abc/hls/sub-s2-00001.vtt":   "abc",
		"/api/playback/o-abc/master.m3u8":         "",
		"/api/offline/abc/stream/../../libraries": "",
	} {
		got, ok := offlineRoute(p)
		if got != want || ok != (want != "") {
			t.Errorf("offlineRoute(%q) = %q, %v", p, got, ok)
		}
		if mediaPath(p) && strings.HasPrefix(p, "/api/offline/") {
			t.Errorf("mediaPath(%q): media tokens would open it", p)
		}
	}
	for h, want := range map[string]int64{"": 0, "bytes=100-": 100, "bytes=5-9": 5, "bytes=-500": 0, "items=4-": 0} {
		if got := rangeStart(h); got != want {
			t.Errorf("rangeStart(%q) = %d", h, got)
		}
	}
}

// A request for a busy transcoder waits for a slot, then answers 503 with
// Retry-After; other errors come back at once.
func TestOfflineWaitsForASlot(t *testing.T) {
	t.Parallel()
	calls := 0
	err := untilSlot(t.Context(), 50*time.Millisecond, func() error { calls++; return playback.ErrTooManySessions })
	if !errors.Is(err, playback.ErrTooManySessions) || calls < 2 {
		t.Errorf("busy: %v after %d calls", err, calls)
	}
	calls = 0
	if err := untilSlot(t.Context(), time.Second, func() error {
		calls++
		if calls < 2 {
			return playback.ErrTooManySessions
		}
		return nil
	}); err != nil || calls != 2 {
		t.Errorf("freed: %v after %d calls", err, calls)
	}
	for err, want := range map[error]string{playback.ErrTooManySessions: "30", playback.ErrKeyframesPending: "10", playback.ErrSubtitleBusy: "2"} {
		rec := httptest.NewRecorder()
		offlineHLSError(rec, httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/", nil), Deps{Log: slog.New(slog.DiscardHandler)}, "x", err)
		if rec.Code != http.StatusServiceUnavailable || rec.Header().Get("Retry-After") != want {
			t.Errorf("%v -> %d, Retry-After %q", err, rec.Code, rec.Header().Get("Retry-After"))
		}
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if err := untilSlot(ctx, time.Minute, func() error { return playback.ErrTooManySessions }); !errors.Is(err, context.Canceled) {
		t.Errorf("cancelled: %v", err)
	}
}

// HLS downloads against an HEVC file with E-AC-3 audio (PLAN-DOWNLOADS.md
// §13): a remux and a 720p transcode, each fetched in order with an offline
// token; the session made again after it went, with the same init segment;
// a differing init answering 409 until the device starts over.
func TestOfflineHLSDownloads(t *testing.T) {
	t.Parallel()
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not installed")
	}
	log := slog.New(slog.DiscardHandler)
	mgr := playback.NewManager(ffmpeg, filepath.Join(t.TempDir(), "transcode"), playback.Capabilities{Encoder: "libx264", Encoders: []string{"libx264"}, HWAccel: "software", MaxHeight: 1080}, time.Minute, 4, log)
	t.Cleanup(mgr.StopAll)
	// This test is about what the routes serve, not how the transcode is
	// scheduled. At a download's low priority, macOS gives ffmpeg almost no
	// CPU while every core is busy, and the suite's other transcodes keep
	// them busy: no init segment came in two minutes on the CI runners.
	mgr.DownloadsAtNormalPriority()
	e := newOfflineEnv(t, func(d *Deps) {
		d.Playback = mgr
		d.Keyframes = playback.NewKeyframes(filepath.Join(filepath.Dir(ffmpeg), "ffprobe"), t.TempDir(), log)
		d.Subtitles = playback.NewSubtitles(ffmpeg, t.TempDir())
	})
	ctx := t.Context()
	dir := t.TempDir()
	path := filepath.Join(dir, "Clip (2020).mkv")
	if b, err := exec.Command(ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=24:duration=13",
		"-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=13", "-map", "0:v", "-map", "1:a",
		"-c:v", "libx265", "-preset", "ultrafast", "-x265-params", "keyint=72:min-keyint=72:log-level=error", "-tag:v", "hvc1",
		"-c:a", "eac3", "-b:a", "96k", path).CombinedOutput(); err != nil {
		t.Skipf("no HEVC clip (libx265?): %v\n%s", err, b)
	}
	_ = os.WriteFile(filepath.Join(dir, "Clip (2020).en.srt"), []byte("1\n00:00:01,000 --> 00:00:02,500\nOpening line\n"), 0o600)
	st, _ := os.Stat(path)
	probeJSON := `{"container":"matroska","durationMs":13000,"bitRate":400000,"video":[{"index":0,"codec":"hevc","width":320,"height":180,"frameRate":24}],"audio":[{"index":1,"codec":"eac3","channels":1,"default":true}],"subtitles":[]}`
	f, err := e.q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: e.fileLibrary(t, path), Path: path, Size: st.Size(), Mtime: db.FormatTime(st.ModTime()),
		AddedAt: db.FormatTime(time.Now()), Parsed: "{}", Probe: &probeJSON, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	// Each on a device of its own: a device downloading a file again
	// replaces its download.
	create := func(profile, quality, device string) OfflineDownload {
		t.Helper()
		var d OfflineDownload
		body := `{"profile":` + profile + `,"quality":"` + quality + `","client":{"deviceId":"` + device + `","name":"Robin's Pixel"}}`
		if code := e.as(t, e.admin, http.MethodPost, fmt.Sprintf("/api/files/%d/offline", f.ID), body, &d).StatusCode; code != http.StatusCreated {
			t.Fatalf("create %s -> %d", quality, code)
		}
		return d
	}
	// fetch gets a name of the download's session with its token, asking
	// again after a 503 as a downloader does (a loaded machine's ffmpeg
	// can take longer than a request waits).
	fetch := func(d OfflineDownload, tok, name string) (int, []byte) {
		t.Helper()
		for range 4 {
			code, h, b := get(t, fmt.Sprintf("%s/api/offline/%s/hls/%s?access_token=%s", e.URL, d.ID, name, tok))
			if code != http.StatusServiceUnavailable || h.Get("Retry-After") == "" {
				return code, b
			}
		}
		t.Fatalf("%s: still 503", name)
		return 0, nil
	}
	// all fetches the playlists, the init segment and every segment in order.
	all := func(d OfflineDownload, tok string) int {
		t.Helper()
		code, master := fetch(d, tok, "master.m3u8")
		if code != 200 || !strings.Contains(string(master), "video.m3u8?access_token="+tok) {
			t.Fatalf("master -> %d\n%s", code, master)
		}
		code, media := fetch(d, tok, "video.m3u8")
		if code != 200 || !strings.Contains(string(media), `#EXT-X-MAP:URI="init.mp4?access_token=`) {
			t.Fatalf("video.m3u8 -> %d\n%s", code, media)
		}
		n := strings.Count(string(media), "#EXTINF")
		if code, init := fetch(d, tok, "init.mp4"); code != 200 || len(init) == 0 {
			t.Fatalf("init -> %d", code)
		}
		for k := range n {
			if code, b := fetch(d, tok, fmt.Sprintf("v%05d.m4s", k)); code != 200 || len(b) == 0 {
				t.Fatalf("segment %d -> %d", k, code)
			}
		}
		return n
	}

	remux := create(`{"containers":["mp4"],"videoCodecs":["hevc"],"audioCodecs":["eac3"],"subtitleFormats":["webvtt"]}`, "original", "admin-phone")
	tx := create(`{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"],"maxWidth":1920,"maxHeight":1080,"subtitleFormats":["webvtt"]}`, "720", "admin-tablet")
	if remux.Mode != "remux" || remux.Protocol != "hls" || !strings.HasSuffix(remux.URL, "/hls/master.m3u8") || !remux.SizeEstimated {
		t.Fatalf("remux = %+v", remux)
	}
	if tx.Mode != "transcode" || tx.Quality != "720" || tx.Decision.Video.Codec != "h264" || tx.Decision.Audio.Codec != "aac" || tx.Decision.Playlist != "vod" {
		t.Fatalf("transcode = %+v / %+v", tx, tx.Decision)
	}
	var sub *playback.SubtitleTrack
	for i := range tx.Decision.Subtitles {
		if tx.Decision.Subtitles[i].ID == "x0" {
			sub = &tx.Decision.Subtitles[i]
		}
	}
	if sub == nil || sub.URL != "/api/offline/"+tx.ID+"/subtitles/x0.vtt" {
		t.Fatalf("subtitles = %+v", tx.Decision.Subtitles)
	}
	for _, d := range []OfflineDownload{remux, tx} {
		tok, _ := e.token(t, e.admin, d.ID)
		segs := all(d, tok.Token)
		rec, _ := e.q.GetOfflineDownload(ctx, d.ID)
		if rec.InitHash == "" || rec.State != "ready" || rec.BytesSent == 0 || rec.DeviceState != "downloading" {
			t.Errorf("%s record = %+v", d.Mode, rec)
		}
		var list OfflineDownloadList
		e.as(t, e.admin, http.MethodGet, "/api/offline/downloads", "", &list)
		for _, tr := range list.Transfers {
			if tr.DownloadID == d.ID && (tr.Progress != 1 || tr.Mode != d.Mode || (d.Mode == "transcode" && tr.Encoder != "libx264")) {
				t.Errorf("%s transfer = %+v", d.Mode, tr)
			}
		}
		// The session goes (VODKeep, a restart): made again from the record,
		// with the same init segment, so the download carries on.
		mgr.Stop("o-" + d.ID)
		if code, _ := fetch(d, tok.Token, fmt.Sprintf("v%05d.m4s", segs-1)); code != 200 {
			t.Errorf("%s: last segment from a remade session -> %d", d.Mode, code)
		}
		if again, _ := e.q.GetOfflineDownload(ctx, d.ID); again.InitHash != rec.InitHash || again.State != "ready" {
			t.Errorf("%s: remade session's init %s, state %s", d.Mode, again.InitHash, again.State)
		}
	}
	tok, _ := e.token(t, e.admin, tx.ID)
	if code, _, b := get(t, e.URL+sub.URL+"?access_token="+tok.Token); code != 200 || !strings.HasPrefix(string(b), "WEBVTT") {
		t.Errorf("sidecar subtitle -> %d %q", code, b)
	}
	// A remade session whose init differs (as a hardware encoder's may):
	// segments answer 409 and the record says restart, until the device
	// fetches the init segment again, starting over.
	if _, err := e.conn.ExecContext(ctx, "UPDATE offline_downloads SET init_hash = 'earlier' WHERE public_id = ?", tx.ID); err != nil {
		t.Fatal(err)
	}
	mgr.Stop("o-" + tx.ID)
	if code, _ := fetch(tx, tok.Token, "v00001.m4s"); code != http.StatusConflict {
		t.Errorf("segment after a differing init -> %d", code)
	}
	if code, _ := fetch(tx, tok.Token, "init.mp4"); code != http.StatusOK {
		t.Errorf("init once told to restart -> %d", code)
	}
	if code, _ := fetch(tx, tok.Token, "v00000.m4s"); code != http.StatusOK {
		t.Errorf("segment after starting over -> %d", code)
	}
	if rec, _ := e.q.GetOfflineDownload(ctx, tx.ID); rec.State != "ready" || rec.InitHash == "earlier" {
		t.Errorf("after starting over: %s %s", rec.State, rec.InitHash)
	}
	// A download's session is not a play's: the playback routes refuse it.
	var media MediaToken
	e.as(t, e.admin, http.MethodPost, "/api/auth/media-token", "", &media)
	if code, _, _ := get(t, e.URL+"/api/playback/o-"+tx.ID+"/video.m3u8?access_token="+media.Token); code != http.StatusNotFound {
		t.Errorf("download session through the playback route -> %d", code)
	}
}
