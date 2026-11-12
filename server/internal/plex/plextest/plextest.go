// Package plextest is a fictional plex.tv and Plex server for tests of the
// Plex connection (internal/plexsync and its routes).
package plextest

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sort"
	"strconv"
	"strings"
	"sync"

	"watchmymedia/server/internal/plex"
)

// Fake stands in for plex.tv and one Plex server, all fictional. Each
// token belongs to one person, and the server answers each with that
// person's own watched marks only, so a read through the wrong credential
// shows up as the wrong person's marks.
//
// People: the owner 701; 702, invited and shared this server; 704, invited
// but with no share here, titled like 702; 705, a Home member with a
// plex.tv sign-in; 706, a managed Home profile (also on the shared list as
// a Home user); 707, a PIN-protected managed profile.
type Fake struct {
	t       Reporter
	Cloud   *httptest.Server
	PMS     *httptest.Server
	Machine string

	mu sync.Mutex
	// kid is the key Plex registered at sign-in; renewals must sign with it.
	kid       string
	SignedIn  bool
	Revoked   bool
	OwnerID   int64
	HomeXML   string
	UsersXML  string
	SharesXML string
	Inventory string
	// rejectResource makes the server refuse the resource token with 401,
	// as the P0 server did, so the owner's device token is used.
	RejectResource bool
	// identity is the machine identifier the server reports.
	Identity string
	Sections string
	// visible limits a person's libraries, by token; all when absent.
	Visible map[string][]string
	// items is each server token's watched marks, by section.
	Items   map[string]map[string][]string
	Shows   map[string]string
	History int
	// HistoryEvents uses a server credential, then a section ID. The owner
	// entry contains all people's history for account-filtered admin reads;
	// each person's token exposes only that person's rows.
	HistoryEvents map[string]map[string][]plex.HistoryEvent
	// pins records every profile PIN sent; reads records "token path".
	pins  []string
	reads []string
}

const (
	OwnerResourceToken = "synthetic-server-resource"
	OwnerDeviceToken   = "synthetic-server-owner-device"
)

// Reporter is the lifecycle/error boundary shared by Go tests and the
// isolated browser fixture. A command can run the exact same fake without
// manufacturing a testing.T or bypassing the production Plex adapter.
type Reporter interface {
	Helper()
	Cleanup(func())
	Error(...any)
	Errorf(string, ...any)
}

func New(t Reporter) *Fake {
	t.Helper()
	f := &Fake{t: t, Machine: "fictional-machine", Identity: "fictional-machine", OwnerID: 701, RejectResource: true, History: http.StatusOK,
		HomeXML: `<MediaContainer size="5">
  <User id="701" title="Fictional Owner" username="fictional-owner" email="owner@example.invalid" admin="1"/>
  <User id="705" title="Fictional Member" username="fictional-member" email="member@example.invalid"/>
  <User id="706" title="Fictional Kid" username="" email="" restricted="1"/>
  <User id="707" title="Fictional Locked" username="" email="" protected="1" restricted="1"/>
  <User id="708" title="Guest" guest="1"/>
</MediaContainer>`,
		UsersXML: `<MediaContainer size="3">
  <User id="702" title="Fictional Viewer" username="fictional-viewer" email="viewer@example.invalid" home="0"/>
  <User id="704" title="Fictional Viewer" username="fictional-viewer-2" email="viewer2@example.invalid" home="0"/>
  <User id="706" title="Fictional Kid" home="1"/>
</MediaContainer>`,
		SharesXML:     `<MediaContainer><SharedServer userID="702" accessToken="synthetic-server-702"/></MediaContainer>`,
		Inventory:     `<MediaContainer size="5"><Account id="0" key="/accounts/0"/><Account id="1" key="/accounts/1"/><Account id="702" key="/accounts/702"/><Account id="705" key="/accounts/705"/><Account id="706" key="/accounts/706"/></MediaContainer>`,
		Sections:      `{"MediaContainer":{"size":3,"Directory":[{"key":"1","uuid":"fictional-movies","title":"Fictional Movies","type":"movie"},{"key":"2","uuid":"fictional-shows","title":"Fictional Shows","type":"show"},{"key":"3","uuid":"fictional-music","title":"Fictional Music","type":"artist"}]}}`,
		Items:         map[string]map[string][]string{},
		Shows:         map[string]string{},
		HistoryEvents: map[string]map[string][]plex.HistoryEvent{},
	}
	f.Cloud = httptest.NewServer(http.HandlerFunc(f.serveCloud))
	f.PMS = httptest.NewServer(http.HandlerFunc(f.servePMS))
	t.Cleanup(f.Cloud.Close)
	t.Cleanup(f.PMS.Close)
	return f
}

func MovieJSON(key, title string, tmdb, imdb string, viewCount int) string {
	guids := []string{}
	if tmdb != "" {
		guids = append(guids, `{"id":"tmdb://`+tmdb+`"}`)
	}
	if imdb != "" {
		guids = append(guids, `{"id":"imdb://`+imdb+`"}`)
	}
	return fmt.Sprintf(`{"ratingKey":%q,"type":"movie","title":%q,"Guid":[%s],"viewCount":%d}`, key, title, strings.Join(guids, ","), viewCount)
}

func EpisodeJSON(key, title, show string, season, episode, viewCount int) string {
	return fmt.Sprintf(`{"ratingKey":%q,"type":"episode","title":%q,"grandparentRatingKey":%q,"parentIndex":%d,"index":%d,"viewCount":%d}`, key, title, show, season, episode, viewCount)
}

func (f *Fake) SetItems(token, section string, items ...string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.Items[token] == nil {
		f.Items[token] = map[string][]string{}
	}
	f.Items[token][section] = items
}

func (f *Fake) Set(fn func(*Fake)) {
	f.mu.Lock()
	defer f.mu.Unlock()
	fn(f)
}

func (f *Fake) cloudAccount(token string) (int64, bool) {
	switch {
	case token == "synthetic-cloud-pin" || token == "synthetic-cloud-owner":
		return f.OwnerID, true
	case strings.HasPrefix(token, "synthetic-cloud-profile-"):
		id, err := strconv.ParseInt(strings.TrimPrefix(token, "synthetic-cloud-profile-"), 10, 64)
		return id, err == nil
	}
	return 0, false
}

func (f *Fake) serveCloud(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	token := r.Header.Get("X-Plex-Token")
	if r.URL.Query().Get("X-Plex-Token") != "" {
		f.t.Error("a token was sent in a URL")
	}
	switch path := r.Method + " " + r.URL.Path; {
	case path == "POST /api/v2/pins":
		var in struct {
			JWK plex.PublicJWK `json:"jwk"`
		}
		_ = json.NewDecoder(r.Body).Decode(&in)
		f.kid = in.JWK.KeyID
		write(w, `{"id":420,"code":"fictional-link-code","expiresIn":600,"clientIdentifier":%q}`, r.Header.Get("X-Plex-Client-Identifier"))
	case path == "GET /api/v2/pins/420":
		if f.SignedIn {
			write(w, "%s", `{"id":420,"authToken":"synthetic-cloud-pin"}`)
		} else {
			write(w, "%s", `{"id":420,"authToken":null}`)
		}
	case path == "GET /api/v2/auth/nonce":
		write(w, "%s", `{"nonce":"fictional-nonce"}`)
	case path == "POST /api/v2/auth/token":
		var in struct {
			JWT string `json:"jwt"`
		}
		_ = json.NewDecoder(r.Body).Decode(&in)
		if f.Revoked || jwtKeyID(in.JWT) != f.kid {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		write(w, "%s", `{"auth_token":"synthetic-cloud-owner"}`)
	case path == "GET /api/v2/user":
		id, ok := f.cloudAccount(token)
		if !ok {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		write(w, `{"id":%d,"uuid":"fictional-%d","title":"fictional-%d","homeAdmin":%t}`, id, id, id, id == f.OwnerID)
	case path == "GET /api/v2/resources":
		id, ok := f.cloudAccount(token)
		if !ok {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		if id == f.OwnerID {
			write(w, `[{"clientIdentifier":%q,"name":"Fictional Server","provides":"server","owned":true,"productVersion":"1.43.5","accessToken":%q,
"connections":[{"uri":%q,"local":true},{"uri":"https://relay.example.invalid:8443","relay":true}]},
{"clientIdentifier":"fictional-friend-server","name":"Friend's","provides":"server","owned":false,"accessToken":"synthetic-friend"}]`, f.Machine, OwnerResourceToken, f.PMS.URL)
			return
		}
		write(w, `[{"clientIdentifier":%q,"provides":"server","owned":false,"accessToken":"synthetic-server-%d","connections":[]}]`, f.Machine, id)
	case path == "GET /api/v2/devices":
		if token != "synthetic-cloud-owner" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		write(w, `[{"clientIdentifier":%q,"product":"Plex Media Server","token":%q}]`, f.Machine, OwnerDeviceToken)
	case path == "GET /api/users/":
		write(w, "%s", f.UsersXML)
	case path == "GET /api/home/users":
		write(w, "%s", f.HomeXML)
	case path == "GET /api/servers/"+f.Machine+"/shared_servers":
		write(w, "%s", f.SharesXML)
	case strings.HasPrefix(path, "POST /api/home/users/") && strings.HasSuffix(path, "/switch"):
		if pin := r.URL.Query().Get("pin"); pin != "" {
			f.pins = append(f.pins, pin)
		}
		id := strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/api/home/users/"), "/switch")
		if token != "synthetic-cloud-owner" || id == "707" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		write(w, `<user id=%q authenticationToken="synthetic-cloud-profile-%s"/>`, id, id)
	default:
		f.t.Errorf("unexpected plex.tv request %s", path)
		w.WriteHeader(http.StatusNotFound)
	}
}

func jwtKeyID(jwt string) string {
	header, _, _ := strings.Cut(jwt, ".")
	b, err := base64.RawURLEncoding.DecodeString(header)
	if err != nil {
		return ""
	}
	var h struct {
		KeyID string `json:"kid"`
	}
	_ = json.Unmarshal(b, &h)
	return h.KeyID
}

func (f *Fake) servePMS(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	token := r.Header.Get("X-Plex-Token")
	w.Header().Set("Content-Type", "application/json")
	if r.URL.Path == "/identity" {
		if token != "" {
			f.t.Error("the identity check sent a credential before the server was verified")
		}
		write(w, `{"MediaContainer":{"size":0,"claimed":true,"machineIdentifier":%q,"version":"1.43.5.11079-fictional"}}`, f.Identity)
		return
	}
	valid := token == OwnerDeviceToken || (token == OwnerResourceToken && !f.RejectResource) ||
		token == "synthetic-server-702" || token == "synthetic-server-705" || token == "synthetic-server-706"
	if !valid {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	f.reads = append(f.reads, token+" "+r.URL.Path)
	switch path := r.URL.Path; {
	case path == "/library/sections":
		if keys, ok := f.Visible[token]; ok {
			var dirs []string
			for _, k := range keys {
				uuid, typ := map[string][2]string{"1": {"fictional-movies", "movie"}, "2": {"fictional-shows", "show"}}[k][0], map[string]string{"1": "movie", "2": "show"}[k]
				dirs = append(dirs, fmt.Sprintf(`{"key":%q,"uuid":%q,"title":"Visible %s","type":%q}`, k, uuid, k, typ))
			}
			write(w, `{"MediaContainer":{"size":%d,"Directory":[%s]}}`, len(dirs), strings.Join(dirs, ","))
			return
		}
		write(w, "%s", f.Sections)
	case strings.HasPrefix(path, "/library/sections/") && strings.HasSuffix(path, "/all"):
		section := strings.TrimSuffix(strings.TrimPrefix(path, "/library/sections/"), "/all")
		items := f.Items[token][section]
		if token == OwnerDeviceToken || token == OwnerResourceToken {
			items = f.Items["owner"][section]
		}
		offset, _ := strconv.Atoi(r.URL.Query().Get("X-Plex-Container-Start"))
		pageSize, _ := strconv.Atoi(r.URL.Query().Get("X-Plex-Container-Size"))
		if pageSize <= 0 {
			pageSize = 200
		}
		if offset < 0 || offset > len(items) {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		total := len(items)
		items = items[offset:min(offset+pageSize, total)]
		write(w, `{"MediaContainer":{"size":%d,"totalSize":%d,"offset":%d,"librarySectionID":%q,"Metadata":[%s]}}`, len(items), total, offset, section, strings.Join(items, ","))
	case strings.HasPrefix(path, "/library/metadata/"):
		show, ok := f.Shows[strings.TrimPrefix(path, "/library/metadata/")]
		if !ok {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		write(w, `{"MediaContainer":{"size":1,"librarySectionID":"2","Metadata":[%s]}}`, show)
	case path == "/accounts":
		write(w, "%s", f.Inventory)
	case path == "/status/sessions/history/all":
		if f.History != http.StatusOK {
			w.WriteHeader(f.History)
			return
		}
		f.serveHistory(w, r, token)
	default:
		f.t.Errorf("unexpected server request %s", path)
		w.WriteHeader(http.StatusNotFound)
	}
}

func (f *Fake) serveHistory(w http.ResponseWriter, r *http.Request, token string) {
	if token == OwnerDeviceToken || token == OwnerResourceToken {
		token = "owner"
	}
	query := r.URL.Query()
	section := query.Get("librarySectionID")
	account, _ := strconv.ParseInt(query.Get("accountID"), 10, 64)
	since, _ := strconv.ParseInt(query.Get("viewedAt>"), 10, 64)
	until, _ := strconv.ParseInt(query.Get("viewedAt<"), 10, 64)
	var rows []plex.HistoryEvent
	for key, events := range f.HistoryEvents[token] {
		if section != "" && key != section {
			continue
		}
		for _, event := range events {
			if account > 0 && event.AccountID != account || since > 0 && event.ViewedAt.Unix() <= since || until > 0 && event.ViewedAt.Unix() >= until {
				continue
			}
			rows = append(rows, event)
		}
	}
	sort.Slice(rows, func(i, j int) bool {
		if rows[i].ViewedAt.Equal(rows[j].ViewedAt) {
			return rows[i].HistoryKey < rows[j].HistoryKey
		}
		return rows[i].ViewedAt.Before(rows[j].ViewedAt)
	})
	offset, _ := strconv.Atoi(query.Get("X-Plex-Container-Start"))
	count, _ := strconv.Atoi(query.Get("X-Plex-Container-Size"))
	if count <= 0 {
		count = 200
	}
	if offset < 0 || offset > len(rows) {
		w.WriteHeader(http.StatusBadRequest)
		return
	}
	total := len(rows)
	rows = rows[offset:min(offset+count, total)]
	metadata := make([]map[string]any, 0, len(rows))
	for _, event := range rows {
		item := event.Item
		guids := []map[string]string{}
		for prefix, value := range map[string]string{"tmdb": item.ExternalIDs.TMDB, "imdb": item.ExternalIDs.IMDb, "tvdb": item.ExternalIDs.TVDB} {
			if value != "" {
				guids = append(guids, map[string]string{"id": prefix + "://" + value})
			}
		}
		row := map[string]any{
			"historyKey": event.HistoryKey, "accountID": event.AccountID, "viewedAt": event.ViewedAt.Unix(),
			"type": item.Type, "title": item.Title, "guid": item.GUID, "Guid": guids,
			"parentIndex": item.SeasonNumber, "index": item.EpisodeNumber, "episodeOrdering": item.EpisodeOrdering,
		}
		for key, value := range map[string]string{"ratingKey": item.RatingKey, "librarySectionID": item.LibrarySectionID, "parentRatingKey": item.ParentRatingKey, "grandparentRatingKey": item.GrandparentRatingKey} {
			if value != "" {
				row[key] = value
			}
		}
		metadata = append(metadata, row)
	}
	container := map[string]any{"size": len(metadata), "totalSize": total, "offset": offset, "Metadata": metadata}
	if section != "" {
		container["librarySectionID"] = section
	}
	_ = json.NewEncoder(w).Encode(map[string]any{"MediaContainer": container})
}

// ReadsBy lists the server paths read with a token.
func (f *Fake) ReadsBy(token string) []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	var out []string
	for _, r := range f.reads {
		if t, path, _ := strings.Cut(r, " "); t == token {
			out = append(out, path)
		}
	}
	return out
}

// Pins lists every profile PIN Plex was sent.
func (f *Fake) Pins() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.pins...)
}

// write answers a request. The fake's replies are JSON and XML for the
// adapter, never a page for a browser.
func write(w http.ResponseWriter, format string, args ...any) {
	_, _ = fmt.Fprintf(w, format, args...) //nolint:gosec // G705: see above
}
