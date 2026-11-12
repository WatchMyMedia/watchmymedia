// Package arrtest is a fake Radarr or Sonarr for tests: the v3 routes the
// server calls, over state the test sets, recording every call. Titles and
// IDs in tests are fictional.
package arrtest

import (
	"cmp"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"slices"
	"strconv"
	"strings"
	"sync"
	"testing"
)

type Call struct {
	Method string
	Path   string
	Query  string
	Body   map[string]any
}

// App is a fake Radarr or Sonarr. Its fields may be changed between calls
// under Lock.
type App struct {
	*httptest.Server
	Kind    string
	Version string
	Key     string

	sync.Mutex
	Movies        []map[string]any
	Series        []map[string]any
	Episodes      []map[string]any
	EpisodeFiles  []map[string]any
	RootFolders   []map[string]any
	Profiles      []map[string]any
	Queue         []map[string]any
	History       []map[string]any
	Notifications []map[string]any
	// ExtraFiles are Radarr's subtitles, metadata and other files beside
	// the media, each with a movieId and a relativePath.
	ExtraFiles []map[string]any
	RecycleBin string
	// RefuseHook makes saving a webhook fail as the app does when its test
	// event cannot reach the address.
	RefuseHook string
	// Fail answers "METHOD /path" (the path after /api/v3) with that status,
	// after recording the call.
	Fail   map[string]int
	Calls  []Call
	nextID int
}

func New(t *testing.T, kind string) *App {
	a := &App{Kind: kind, Version: "6.4.4.10685", Key: "fake-key-1234", nextID: 100}
	if kind == "sonarr" {
		a.Version = "4.0.20.3014"
	}
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

func (a *App) serve(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("X-Api-Key") != a.Key {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	var body map[string]any
	if b, _ := io.ReadAll(r.Body); len(b) > 0 {
		_ = json.Unmarshal(b, &body)
	}
	a.Lock()
	defer a.Unlock()
	p := strings.TrimPrefix(r.URL.Path, "/api/v3")
	a.Calls = append(a.Calls, Call{Method: r.Method, Path: p, Query: r.URL.RawQuery, Body: body})
	q := r.URL.Query()
	send := func(v any) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(v)
	}
	if status := a.Fail[r.Method+" "+p]; status != 0 {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(map[string]any{"message": "fake failure"})
		return
	}
	parts := strings.Split(strings.Trim(p, "/"), "/")
	id := func() int {
		n, _ := strconv.Atoi(parts[len(parts)-1])
		return n
	}
	switch {
	case p == "/system/status":
		name := "Radarr"
		if a.Kind == "sonarr" {
			name = "Sonarr"
		}
		send(map[string]any{"appName": name, "instanceName": name, "version": a.Version})
	case p == "/rootfolder":
		send(nonNil(a.RootFolders))
	case p == "/qualityprofile":
		send(nonNil(a.Profiles))
	case p == "/config/mediamanagement":
		send(map[string]any{"recycleBin": a.RecycleBin, "recycleBinCleanupDays": 7})
	case p == "/movie" && r.Method == http.MethodGet:
		send(nonNil(a.Movies))
	case p == "/movie" && r.Method == http.MethodPost:
		body["id"] = a.next()
		a.Movies = append(a.Movies, body)
		send(body)
	case p == "/movie/lookup/tmdb":
		tmdb, _ := strconv.Atoi(q.Get("tmdbId"))
		send(map[string]any{"title": "Looked Up " + q.Get("tmdbId"), "tmdbId": tmdb, "year": 2020, "images": []any{}})
	case p == "/movie/editor" && r.Method == http.MethodPut:
		for _, m := range a.Movies {
			if containsID(body["movieIds"], m["id"]) {
				m["monitored"] = body["monitored"]
			}
		}
		send(map[string]any{})
	case strings.HasPrefix(p, "/movie/") && r.Method == http.MethodGet:
		if m := find(a.Movies, id()); m != nil {
			send(m)
			return
		}
		w.WriteHeader(http.StatusNotFound)
	case strings.HasPrefix(p, "/movie/") && r.Method == http.MethodDelete:
		a.Movies = slices.DeleteFunc(a.Movies, func(m map[string]any) bool { return num(m["id"]) == id() })
	case strings.HasPrefix(p, "/moviefile/") && r.Method == http.MethodDelete:
		for _, m := range a.Movies {
			if f, ok := m["movieFile"].(map[string]any); ok && num(f["id"]) == id() {
				delete(m, "movieFile")
				m["hasFile"] = false
			}
		}
	case p == "/series" && r.Method == http.MethodGet:
		send(nonNil(a.Series))
	case p == "/series" && r.Method == http.MethodPost:
		body["id"] = a.next()
		a.Series = append(a.Series, body)
		send(body)
	case p == "/series/lookup":
		tvdb, _ := strconv.Atoi(strings.TrimPrefix(q.Get("term"), "tvdb:"))
		send([]map[string]any{{"title": "Looked Up", "tvdbId": tvdb, "seasons": []any{
			map[string]any{"seasonNumber": 1, "monitored": true}, map[string]any{"seasonNumber": 2, "monitored": true}}}})
	case strings.HasPrefix(p, "/series/") && r.Method == http.MethodGet:
		if s := find(a.Series, id()); s != nil {
			send(s)
			return
		}
		w.WriteHeader(http.StatusNotFound)
	case strings.HasPrefix(p, "/series/") && r.Method == http.MethodPut:
		for i, s := range a.Series {
			if num(s["id"]) == id() {
				a.Series[i] = body
			}
		}
		send(body)
	case strings.HasPrefix(p, "/series/") && r.Method == http.MethodDelete:
		a.Series = slices.DeleteFunc(a.Series, func(s map[string]any) bool { return num(s["id"]) == id() })
	case p == "/episode":
		sid, _ := strconv.Atoi(q.Get("seriesId"))
		var out []map[string]any
		for _, e := range a.Episodes {
			if num(e["seriesId"]) == sid {
				out = append(out, e)
			}
		}
		send(nonNil(out))
	case p == "/episode/monitor":
		for _, e := range a.Episodes {
			if containsID(body["episodeIds"], e["id"]) {
				e["monitored"] = body["monitored"]
			}
		}
		send(map[string]any{})
	case p == "/episodefile":
		sid, _ := strconv.Atoi(q.Get("seriesId"))
		var out []map[string]any
		for _, f := range a.EpisodeFiles {
			if num(f["seriesId"]) == sid {
				out = append(out, f)
			}
		}
		send(nonNil(out))
	case p == "/episodefile/bulk" && r.Method == http.MethodDelete:
		a.EpisodeFiles = slices.DeleteFunc(a.EpisodeFiles, func(f map[string]any) bool { return containsID(body["episodeFileIds"], f["id"]) })
	case p == "/queue":
		// Paged as the apps page it: pageSize records (10 by default) of
		// page (from 1), with the total.
		page, _ := strconv.Atoi(q.Get("page"))
		size, _ := strconv.Atoi(q.Get("pageSize"))
		page, size = max(page, 1), cmp.Or(max(size, 0), 10)
		from := min((page-1)*size, len(a.Queue))
		to := min(from+size, len(a.Queue))
		send(map[string]any{"page": page, "pageSize": size, "totalRecords": len(a.Queue), "records": nonNil(a.Queue[from:to])})
	case p == "/history":
		send(map[string]any{"records": nonNil(matching(a.History, "episodeId", q.Get("episodeId")))})
	case p == "/history/movie":
		send(nonNil(matching(a.History, "movieId", q.Get("movieId"))))
	case p == "/extrafile" && a.Kind == "sonarr":
		// Sonarr 4 has no extra file list.
		w.WriteHeader(http.StatusNotFound)
	case p == "/extrafile":
		key := "movieId"
		if q.Has("seriesId") {
			key = "seriesId"
		}
		send(nonNil(matching(a.ExtraFiles, key, q.Get(key))))
	case strings.HasPrefix(p, "/history/failed/"):
		send(map[string]any{})
	case p == "/command":
		send(map[string]any{"id": a.next(), "name": body["name"], "status": "queued"})
	case p == "/notification" && r.Method == http.MethodGet:
		send(nonNil(a.Notifications))
	case p == "/notification/schema":
		send([]map[string]any{{"implementation": "Webhook", "configContract": "WebhookSettings",
			"onGrab": false, "supportsOnGrab": true, "onDownload": false, "supportsOnDownload": true,
			"onHealthIssue": false, "supportsOnHealthIssue": true, "onRename": false, "supportsOnRename": true,
			"fields": []any{map[string]any{"name": "url"}, map[string]any{"name": "method", "value": 1},
				map[string]any{"name": "username"}, map[string]any{"name": "password"}}}})
	case strings.HasPrefix(p, "/notification") && (r.Method == http.MethodPost || r.Method == http.MethodPut):
		if a.RefuseHook != "" {
			w.WriteHeader(http.StatusBadRequest)
			send([]map[string]any{{"propertyName": "Url", "errorMessage": a.RefuseHook}})
			return
		}
		if r.Method == http.MethodPost {
			body["id"] = a.next()
			a.Notifications = append(a.Notifications, body)
		}
		send(body)
	default:
		w.WriteHeader(http.StatusNotFound)
	}
}

func (a *App) next() int { a.nextID++; return a.nextID }

func nonNil(v []map[string]any) []map[string]any {
	if v == nil {
		return []map[string]any{}
	}
	return v
}

func num(v any) int {
	switch n := v.(type) {
	case float64:
		return int(n)
	case int:
		return n
	}
	return 0
}

// matching is the records whose key is the number v, or every record when
// none of them carries the key.
func matching(list []map[string]any, key, v string) []map[string]any {
	n, _ := strconv.Atoi(v)
	var out []map[string]any
	keyed := false
	for _, m := range list {
		if _, ok := m[key]; ok {
			keyed = true
			if num(m[key]) == n {
				out = append(out, m)
			}
		}
	}
	if !keyed {
		return list
	}
	return out
}

func find(list []map[string]any, id int) map[string]any {
	for _, m := range list {
		if num(m["id"]) == id {
			return m
		}
	}
	return nil
}

func containsID(list, id any) bool {
	l, _ := list.([]any)
	for _, x := range l {
		if num(x) == num(id) {
			return true
		}
	}
	return false
}
