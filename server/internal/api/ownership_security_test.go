package api

import (
	"encoding/csv"
	"fmt"
	"net/http"
	"net/http/cookiejar"
	"os"
	"path/filepath"
	"testing"
	"time"

	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/playback"
)

func securityTestUser(t *testing.T, srv *testEnv, name string) (*http.Client, Account) {
	t.Helper()
	base := srv.URL
	var u Account
	if code := doJSON(t, srv.client, http.MethodPost, base+"/api/users", `{"name":"`+name+`","password":"synthetic-password"}`, &u).StatusCode; code != 201 {
		t.Fatalf("create user: %d", code)
	}
	jar, _ := cookiejar.New(nil)
	c := &http.Client{Jar: jar}
	if code := doJSON(t, c, http.MethodPost, base+"/api/auth/login", `{"name":"`+name+`","password":"synthetic-password"}`, nil).StatusCode; code != 200 {
		t.Fatalf("login: %d", code)
	}
	return c, u
}

func securityTestFile(t *testing.T, srv *testEnv) db.MediaFile {
	t.Helper()
	ctx := t.Context()
	path := filepath.Join(t.TempDir(), "Synthetic Clip.mp4")
	if err := os.WriteFile(path, []byte("0123456789abcdef"), 0600); err != nil {
		t.Fatal(err)
	}
	pj := `{"container":"mov","durationMs":600000,"video":[{"index":0,"codec":"h264","width":1280,"height":720}],"audio":[{"index":1,"codec":"aac","channels":2,"default":true}],"subtitles":[]}`
	dur := int64(600000)
	now := db.FormatTime(time.Now())
	f, err := srv.q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: srv.fileLibrary(t, path), Path: path, Size: 16, Mtime: now, AddedAt: now, Parsed: "{}", Probe: &pj, DurationMs: &dur, LastSeenAt: now, SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	return f
}

func securityTestPlay(t *testing.T, c *http.Client, base string, f db.MediaFile, name string) playback.Decision {
	t.Helper()
	var p playback.Decision
	body := `{"profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"]},"quality":"original","client":{"deviceId":"synthetic-device","name":"` + name + `"}}`
	if code := doJSON(t, c, http.MethodPost, fmt.Sprintf("%s/api/files/%d/playback", base, f.ID), body, &p).StatusCode; code != 200 {
		t.Fatalf("play: %d", code)
	}
	return p
}

func securityTestStop(t *testing.T, c *http.Client, base string, f db.MediaFile, p playback.Decision) {
	t.Helper()
	body := `{"positionMs":5000,"playId":"` + p.PlayID + `","state":"stopped"}`
	if code := doJSON(t, c, http.MethodPut, fmt.Sprintf("%s/api/files/%d/progress", base, f.ID), body, nil).StatusCode; code != 200 {
		t.Fatalf("stop: %d", code)
	}
}

func TestCSVNeutralizesViewerFormulaCell(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, func(d *Deps) { d.Activity = activity.New(d.Conn, d.Bus, nil, activity.Options{}) })
	c, _ := securityTestUser(t, srv, "auditoriginal")
	f := securityTestFile(t, srv)
	p := securityTestPlay(t, c, srv.URL, f, "=1+1")
	securityTestStop(t, c, srv.URL, f, p)
	res, err := srv.client.Get(srv.URL + "/api/history.csv")
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	rows, err := csv.NewReader(res.Body).ReadAll()
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 2 || rows[1][3] != "'=1+1" {
		t.Fatalf("expected formula escaped as text, rows=%v", rows)
	}

}

func TestDeletedAccountDoesNotLeakHistory(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, func(d *Deps) { d.Activity = activity.New(d.Conn, d.Bus, nil, activity.Options{}) })
	original, u := securityTestUser(t, srv, "auditformer")
	f := securityTestFile(t, srv)
	p := securityTestPlay(t, original, srv.URL, f, "Former synthetic device")
	securityTestStop(t, original, srv.URL, f, p)
	var before activity.Cards
	if code := doJSON(t, original, http.MethodGet, srv.URL+"/api/stats/cards?days=0", "", &before).StatusCode; code != 200 || len(before.Recent) != 1 {
		t.Fatalf("populate cache: %d, recent=%d", code, len(before.Recent))
	}
	if code := doJSON(t, srv.client, http.MethodDelete, fmt.Sprintf("%s/api/users/%d", srv.URL, u.ID), "", nil).StatusCode; code != 204 {
		t.Fatalf("delete: %d", code)
	}
	replacement, v := securityTestUser(t, srv, "auditreplacement")
	if u.ID == v.ID {
		t.Fatalf("account ID reused: old=%d new=%d", u.ID, v.ID)
	}
	var after activity.Cards
	if code := doJSON(t, replacement, http.MethodGet, srv.URL+"/api/stats/cards?days=0", "", &after).StatusCode; code != 200 {
		t.Fatalf("read replacement stats: %d", code)
	}
	if len(after.Recent) != 0 {
		t.Fatalf("former account history leaked: %+v", after.Recent)
	}
	var series activity.Series
	if code := doJSON(t, replacement, http.MethodGet, srv.URL+"/api/stats/series?days=0", "", &series).StatusCode; code != 200 {
		t.Fatalf("series: %d", code)
	}
	for _, line := range series.Lines {
		for _, value := range line.Values {
			if value != 0 {
				t.Fatalf("former aggregate leaked: %+v", series)
			}
		}
	}

}

func TestDirectStreamRejectsAnotherViewersPlay(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, func(d *Deps) {
		d.Activity = activity.New(d.Conn, d.Bus, nil, activity.Options{Config: activity.Config{Household: true}})
	})
	victim, _ := securityTestUser(t, srv, "auditvictim")
	other, _ := securityTestUser(t, srv, "auditother")
	f := securityTestFile(t, srv)
	p := securityTestPlay(t, victim, srv.URL, f, "Synthetic viewer")
	var visible LiveActivity
	if code := doJSON(t, other, http.MethodGet, srv.URL+"/api/activity", "", &visible).StatusCode; code != 200 || len(visible.Plays) != 1 {
		t.Fatal("household play unavailable")
	}
	if visible.Plays[0].ID != p.PlayID {
		t.Fatal("unexpected play")
	}
	if code := doJSON(t, other, http.MethodGet, srv.URL+p.URL, "", nil).StatusCode; code != http.StatusForbidden {
		t.Fatalf("stream: %d", code)
	}
	var after LiveActivity
	doJSON(t, victim, http.MethodGet, srv.URL+"/api/activity", "", &after)
	if len(after.Plays) != 1 || after.Plays[0].BytesSent != 0 {
		t.Fatalf("unexpected byte count: %+v", after)
	}

}

// Real-file fixtures must belong to the directory configured as their library.
// fileLibrary makes a movie library of the folder that holds path.
func (srv *testEnv) fileLibrary(t *testing.T, path string) int64 {
	t.Helper()
	lib, err := srv.q.UpsertLibrary(t.Context(), db.UpsertLibraryParams{Type: "movie", Name: "Synthetic clips", Path: filepath.Dir(path)})
	if err != nil {
		t.Fatal(err)
	}
	return lib.ID
}
