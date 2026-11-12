// Package seerrtest is a fake Seerr for tests: the v1 routes the server
// calls, over state the test sets, with Seerr's rules for who may request
// what, recording every call. Titles and IDs in tests are fictional.
package seerrtest

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"slices"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"watchmymedia/server/internal/integrations/seerr"
)

type Call struct {
	Method string
	Path   string
	Query  string
	// User is the X-Api-User header; empty when the call was made as the
	// key's own user.
	User string
	Body map[string]any
}

// Item is a movie or show TMDB knows, and Seerr's record of it when MediaID
// is set.
type Item struct {
	MediaType string
	TMDBID    int
	Title     string
	Date      string
	Seasons   []seerr.Season

	MediaID      int
	Status       int
	SeasonStatus map[int]int
	Downloads    []seerr.DownloadStatus
}

type Request struct {
	ID        int
	UserID    int
	Status    int
	MediaType string
	TMDBID    int
	Seasons   []int
	CreatedAt time.Time
}

// App is a fake Seerr. Its fields may be changed between calls under Lock.
type App struct {
	*httptest.Server
	Version string
	Key     string

	sync.Mutex
	Users    []seerr.User
	Items    []*Item
	Requests []*Request
	Issues   []map[string]any
	// QuotaLeft limits how many more requests a user may make; users not in
	// it have no quota.
	QuotaLeft      map[int]int
	ApplicationURL string
	Calls          []Call
	nextID         int
}

// New starts a fake Seerr 3.5.1 whose user 1 is its admin.
func New(t *testing.T) *App {
	a := &App{Version: "3.5.1", Key: "seerr-key-5678==", nextID: 500, QuotaLeft: map[int]int{},
		Users: []seerr.User{{ID: 1, DisplayName: "Owner", Username: "owner", Permissions: seerr.PermAdmin}}}
	a.Server = httptest.NewServer(http.HandlerFunc(a.serve))
	t.Cleanup(a.Close)
	return a
}

// CallsTo lists the calls made to method and path, in order.
func (a *App) CallsTo(method, path string) []Call {
	a.Lock()
	defer a.Unlock()
	var out []Call
	for _, c := range a.Calls {
		if c.Method == method && c.Path == path {
			out = append(out, c)
		}
	}
	return out
}

// Item returns the item, or nil.
func (a *App) Item(mediaType string, tmdbID int) *Item {
	for _, it := range a.Items {
		if it.MediaType == mediaType && it.TMDBID == tmdbID {
			return it
		}
	}
	return nil
}

func (a *App) user(id int) *seerr.User {
	for i := range a.Users {
		if a.Users[i].ID == id {
			return &a.Users[i]
		}
	}
	return nil
}

func (a *App) serve(w http.ResponseWriter, r *http.Request) {
	send := func(status int, v any) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(v)
	}
	fail := func(status int, msg string) { send(status, map[string]any{"message": msg}) }
	p := strings.TrimPrefix(r.URL.Path, "/api/v1")
	if p == "/status" {
		send(http.StatusOK, map[string]any{"version": a.Version, "commitTag": "local"})
		return
	}
	// Seerr's sign-in check answers with {"error": …}, its routes with
	// {"message": …}.
	refuse := func() {
		send(http.StatusForbidden, map[string]any{"status": 403, "error": "You do not have permission to access this endpoint"})
	}
	if r.Header.Get("X-Api-Key") != a.Key {
		refuse()
		return
	}
	var body map[string]any
	if b, _ := io.ReadAll(r.Body); len(b) > 0 {
		_ = json.Unmarshal(b, &body)
	}
	a.Lock()
	defer a.Unlock()
	a.Calls = append(a.Calls, Call{Method: r.Method, Path: p, Query: r.URL.RawQuery, User: r.Header.Get("X-Api-User"), Body: body})
	asUser := 1
	if h := r.Header.Get("X-Api-User"); h != "" {
		asUser, _ = strconv.Atoi(h)
	}
	me := a.user(asUser)
	if me == nil {
		refuse()
		return
	}
	parts := strings.Split(strings.Trim(p, "/"), "/")
	last, _ := strconv.Atoi(parts[len(parts)-1])
	q := r.URL.Query()
	switch {
	case p == "/auth/me":
		send(http.StatusOK, me)
	case p == "/settings/public":
		send(http.StatusOK, map[string]any{"applicationTitle": "Seerr", "applicationUrl": a.ApplicationURL})
	case p == "/user":
		take, _ := strconv.Atoi(q.Get("take"))
		skip, _ := strconv.Atoi(q.Get("skip"))
		end := min(len(a.Users), skip+take)
		page := []seerr.User{}
		if skip < end {
			page = a.Users[skip:end]
		}
		send(http.StatusOK, map[string]any{"pageInfo": map[string]any{"pages": 1, "results": len(a.Users)}, "results": page})
	case p == "/search":
		out := []any{map[string]any{"id": 1, "mediaType": "person", "name": "Someone"}}
		for _, it := range a.Items {
			if strings.Contains(strings.ToLower(it.Title), strings.ToLower(q.Get("query"))) {
				out = append(out, a.media(it, false))
			}
		}
		send(http.StatusOK, map[string]any{"page": 1, "results": out})
	case (parts[0] == "movie" || parts[0] == "tv") && len(parts) == 2:
		it := a.Item(parts[0], last)
		if it == nil {
			fail(http.StatusNotFound, "Unable to retrieve "+parts[0]+".")
			return
		}
		send(http.StatusOK, a.media(it, true))
	case p == "/request" && r.Method == http.MethodPost:
		a.request(me, body, send, fail)
	case p == "/request" && r.Method == http.MethodGet:
		by, _ := strconv.Atoi(q.Get("requestedBy"))
		out := []any{}
		for _, rq := range slices.Backward(a.Requests) {

			if (by == 0 || rq.UserID == by) && (rq.UserID == me.ID || me.Has(seerr.PermManageRequests)) {
				out = append(out, a.requestJSON(rq, true))
			}
		}
		send(http.StatusOK, map[string]any{"pageInfo": map[string]any{"pages": 1, "results": len(out)}, "results": out})
	case p == "/request/count":
		n := 0
		for _, rq := range a.Requests {
			if rq.Status == seerr.RequestPending {
				n++
			}
		}
		send(http.StatusOK, map[string]any{"pending": n})
	case parts[0] == "request" && r.Method == http.MethodDelete:
		i := slices.IndexFunc(a.Requests, func(rq *Request) bool { return rq.ID == last })
		if i < 0 {
			fail(http.StatusNotFound, "Request not found.")
			return
		}
		if rq := a.Requests[i]; !me.Has(seerr.PermManageRequests) && (rq.UserID != me.ID || rq.Status != seerr.RequestPending) {
			fail(http.StatusUnauthorized, "You do not have permission to delete this request.")
			return
		}
		a.Requests = slices.Delete(a.Requests, i, i+1)
		w.WriteHeader(http.StatusNoContent)
	case p == "/issue" && r.Method == http.MethodPost:
		if !me.Has(seerr.PermManageIssues, seerr.PermCreateIssues) {
			refuse()
			return
		}
		if !slices.ContainsFunc(a.Items, func(it *Item) bool { return it.MediaID > 0 && float64(it.MediaID) == body["mediaId"] }) {
			fail(http.StatusInternalServerError, "Media does not exist.")
			return
		}
		body["id"] = a.next()
		body["createdBy"] = me.ID
		a.Issues = append(a.Issues, body)
		send(http.StatusOK, body)
	case parts[0] == "media" && r.Method == http.MethodDelete:
		for _, it := range a.Items {
			if it.MediaID == last {
				it.MediaID, it.Status, it.SeasonStatus, it.Downloads = 0, 0, nil, nil
				a.Requests = slices.DeleteFunc(a.Requests, func(rq *Request) bool { return rq.MediaType == it.MediaType && rq.TMDBID == it.TMDBID })
				w.WriteHeader(http.StatusNoContent)
				return
			}
		}
		fail(http.StatusNotFound, "Media not found.")
	case strings.HasPrefix(p, "/settings/jobs/") && strings.HasSuffix(p, "/run"):
		send(http.StatusOK, map[string]any{"id": parts[2], "running": true})
	default:
		fail(http.StatusNotFound, "not found")
	}
}

// request is POST /request with Seerr's rules: permission, quota, an open
// request for a movie, and seasons already asked for or available.
func (a *App) request(me *seerr.User, body map[string]any, send func(int, any), fail func(int, string)) {
	mediaType, _ := body["mediaType"].(string)
	tmdbID := int(num(body["mediaId"]))
	it := a.Item(mediaType, tmdbID)
	if it == nil {
		fail(http.StatusNotFound, "Media not found.")
		return
	}
	if !me.CanRequest(mediaType) {
		fail(http.StatusForbidden, "You do not have permission to make "+map[string]string{"movie": "movie", "tv": "series"}[mediaType]+" requests.")
		return
	}
	if left, ok := a.QuotaLeft[me.ID]; ok && left <= 0 {
		fail(http.StatusForbidden, map[string]string{"movie": "Movie", "tv": "Series"}[mediaType]+" Quota exceeded.")
		return
	}
	if it.Status == seerr.StatusBlocklisted {
		fail(http.StatusForbidden, "This media is blocklisted.")
		return
	}
	var seasons []int
	if mediaType == "movie" {
		for _, rq := range a.Requests {
			if rq.MediaType == "movie" && rq.TMDBID == tmdbID && rq.Status != seerr.RequestDeclined {
				fail(http.StatusConflict, "Request for this media already exists.")
				return
			}
		}
	} else {
		taken := map[int]bool{}
		for _, rq := range a.Requests {
			if rq.MediaType == "tv" && rq.TMDBID == tmdbID && rq.Status != seerr.RequestDeclined && rq.Status != seerr.RequestCompleted {
				for _, n := range rq.Seasons {
					taken[n] = true
				}
			}
		}
		for n, st := range it.SeasonStatus {
			if st != seerr.StatusUnknown && st != seerr.StatusDeleted {
				taken[n] = true
			}
		}
		list, _ := body["seasons"].([]any)
		for _, v := range list {
			if n := int(num(v)); !taken[n] {
				seasons = append(seasons, n)
			}
		}
		if len(seasons) == 0 {
			send(http.StatusAccepted, map[string]any{"message": "No seasons available to request"})
			return
		}
	}
	if _, ok := a.QuotaLeft[me.ID]; ok {
		a.QuotaLeft[me.ID]--
	}
	rq := &Request{ID: a.next(), UserID: me.ID, Status: seerr.RequestPending, MediaType: mediaType, TMDBID: tmdbID, Seasons: seasons, CreatedAt: time.Now().UTC()}
	if me.AutoApproved(mediaType) {
		rq.Status = seerr.RequestApproved
	}
	a.Requests = append(a.Requests, rq)
	if it.MediaID == 0 {
		it.MediaID, it.Status = a.next(), seerr.StatusUnknown
	}
	st := seerr.StatusPending
	if rq.Status == seerr.RequestApproved {
		st = seerr.StatusProcessing
	}
	if it.Status == seerr.StatusUnknown || it.Status == seerr.StatusDeleted || it.Status == 0 {
		it.Status = st
	}
	if mediaType == "tv" {
		if it.SeasonStatus == nil {
			it.SeasonStatus = map[int]int{}
		}
		for _, n := range seasons {
			it.SeasonStatus[n] = st
		}
	}
	send(http.StatusCreated, a.requestJSON(rq, false))
}

// media is a lookup's or search result's JSON; full adds the record's
// requests, as Seerr's lookups do and its search does not.
func (a *App) media(it *Item, full bool) map[string]any {
	m := map[string]any{"id": it.TMDBID, "mediaType": it.MediaType, "overview": "About " + it.Title, "posterPath": "/p" + strconv.Itoa(it.TMDBID) + ".jpg", "popularity": float64(it.TMDBID % 97)}
	if it.MediaType == "movie" {
		m["title"], m["releaseDate"] = it.Title, it.Date
	} else {
		m["name"], m["firstAirDate"] = it.Title, it.Date
		if full {
			m["seasons"] = it.Seasons
		}
	}
	if it.MediaID > 0 {
		info := map[string]any{"id": it.MediaID, "tmdbId": it.TMDBID, "mediaType": it.MediaType, "status": it.Status, "downloadStatus": nonNil(it.Downloads)}
		seasons := []any{}
		for n, st := range it.SeasonStatus {
			seasons = append(seasons, map[string]any{"seasonNumber": n, "status": st})
		}
		info["seasons"] = seasons
		if full {
			reqs := []any{}
			for _, rq := range a.Requests {
				if rq.MediaType == it.MediaType && rq.TMDBID == it.TMDBID {
					reqs = append(reqs, a.requestJSON(rq, false))
				}
			}
			info["requests"] = reqs
		}
		m["mediaInfo"] = info
	}
	return m
}

func (a *App) requestJSON(rq *Request, withMedia bool) map[string]any {
	seasons := []any{}
	for _, n := range rq.Seasons {
		seasons = append(seasons, map[string]any{"seasonNumber": n, "status": rq.Status})
	}
	out := map[string]any{"id": rq.ID, "status": rq.Status, "type": rq.MediaType, "is4k": false, "createdAt": rq.CreatedAt,
		"requestedBy": a.user(rq.UserID), "seasons": seasons}
	if it := a.Item(rq.MediaType, rq.TMDBID); withMedia && it != nil {
		out["media"] = map[string]any{"id": it.MediaID, "tmdbId": it.TMDBID, "mediaType": it.MediaType, "status": it.Status, "downloadStatus": nonNil(it.Downloads)}
	}
	return out
}

func (a *App) next() int { a.nextID++; return a.nextID }

func nonNil(v []seerr.DownloadStatus) []seerr.DownloadStatus {
	if v == nil {
		return []seerr.DownloadStatus{}
	}
	return v
}

func num(v any) float64 {
	n, _ := v.(float64)
	return n
}
