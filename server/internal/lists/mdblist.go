package lists

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/time/rate"

	"watchmymedia/server/internal/httpx"
	"watchmymedia/server/internal/integrations/apiclient"
	"watchmymedia/server/internal/tmdb"
)

// DefaultMDBListURL is MDBList's API (https://api.mdblist.com), the only
// host the client asks.
const DefaultMDBListURL = "https://api.mdblist.com"

// MDBList is a client for MDBList's API with the admin's own key, one for
// the server (PLAN-ROWS.md 3). The key goes in the apikey parameter, so
// errors are redacted, and redirects to another host are refused. Free
// accounts get about a thousand requests a day; lists are fetched once a
// day each.
type MDBList struct {
	mu      sync.RWMutex
	key     string
	base    string
	http    *http.Client
	limiter *rate.Limiter
}

// MDBListOptions tunes a client; the zero value is what the server runs with.
type MDBListOptions struct {
	// BaseURL is the API's address; empty means DefaultMDBListURL.
	BaseURL string
	// HTTP sends the requests; nil means a 20 s timeout and no redirects
	// to another host.
	HTTP *http.Client
	// Limiter paces requests; nil means 2 a second in bursts of 4.
	Limiter *rate.Limiter
}

// NewMDBList returns a client with key ("" until an admin enters one).
func NewMDBList(key string, opts MDBListOptions) *MDBList {
	if opts.BaseURL == "" {
		opts.BaseURL = DefaultMDBListURL
	}
	if opts.HTTP == nil {
		opts.HTTP = &http.Client{Timeout: 20 * time.Second, CheckRedirect: apiclient.SameHost}
	}
	if opts.Limiter == nil {
		opts.Limiter = rate.NewLimiter(2, 4)
	}
	return &MDBList{key: strings.TrimSpace(key), base: strings.TrimRight(opts.BaseURL, "/"), http: opts.HTTP, limiter: opts.Limiter}
}

// Configured reports whether a key is set.
func (c *MDBList) Configured() bool { return c != nil && c.Key() != "" }

// Key is the key in use.
func (c *MDBList) Key() string {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.key
}

// SetKey replaces the key (Settings › Lists).
func (c *MDBList) SetKey(key string) {
	c.mu.Lock()
	c.key = strings.TrimSpace(key)
	c.mu.Unlock()
}

// MDBList's answers that are not the list.
var (
	ErrMDBListKey      = &RefusedError{Reason: "MDBList refused the key"}
	ErrMDBListNotFound = &RefusedError{Reason: "MDBList has no such list; it may be private or deleted"}
	ErrMDBListNoKey    = &RefusedError{Reason: "An admin can add an MDBList key in Settings › Lists"}
)

// MDBListLimitError is MDBList's daily request limit used up.
type MDBListLimitError struct{ RetryAfter time.Duration }

func (e *MDBListLimitError) Error() string {
	return "MDBList's daily request limit is used up; the list is fetched again later"
}

// Verify checks key with MDBList's cheapest call, /user, without keeping it.
func (c *MDBList) Verify(ctx context.Context, key string) error {
	var out struct {
		UserID json.RawMessage `json:"user_id"`
	}
	return c.get(ctx, strings.TrimSpace(key), "/user", nil, &out)
}

func (c *MDBList) get(ctx context.Context, key, path string, q url.Values, out any) error {
	if key == "" {
		return ErrMDBListNoKey
	}
	if q == nil {
		q = url.Values{}
	}
	q.Set("apikey", key)
	if err := c.limiter.Wait(ctx); err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.base+path+"?"+q.Encode(), nil)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")
	res, err := c.http.Do(req)
	if err != nil {
		// The URL carries the key: keep it out of the error.
		return fmt.Errorf("mdblist: %w", httpx.Redact(err))
	}
	defer func() { _ = res.Body.Close() }()
	body, err := httpx.ReadLimited(res.Body, 8<<20)
	if err != nil {
		return fmt.Errorf("mdblist: read %s: %w", path, err)
	}
	switch res.StatusCode {
	case http.StatusOK:
		if err := json.Unmarshal(body, out); err != nil {
			return fmt.Errorf("mdblist: decode %s: %w", path, err)
		}
		return nil
	case http.StatusUnauthorized, http.StatusForbidden:
		return ErrMDBListKey
	case http.StatusNotFound:
		return ErrMDBListNotFound
	case http.StatusTooManyRequests:
		wait, _ := httpx.RetryAfter(res.Header.Get("Retry-After"), time.Now(), 24*time.Hour)
		return &MDBListLimitError{RetryAfter: wait}
	}
	var e struct {
		Error  string `json:"error"`
		Detail string `json:"detail"`
	}
	_ = json.Unmarshal(body, &e)
	return fmt.Errorf("mdblist: %s returned %d: %s", path, res.StatusCode, strings.TrimSpace(e.Error+" "+e.Detail))
}

// MDBListRef names a list: its numeric ID, or its owner and slug.
type MDBListRef struct {
	ID   int
	User string
	Slug string
}

var mdbName = regexp.MustCompile(`^[A-Za-z0-9_.~-]{1,100}$`)

// ParseMDBListRef reads a list's ID, "user/name", or its mdblist.com link
// ("https://mdblist.com/lists/user/name").
func ParseMDBListRef(ref string) (MDBListRef, error) {
	ref = strings.TrimSpace(ref)
	if ref == "" {
		return MDBListRef{}, invalid("Enter the MDBList list's ID, user/name, or its link")
	}
	if n, err := strconv.Atoi(ref); err == nil && n > 0 {
		return MDBListRef{ID: n}, nil
	}
	path := ref
	if strings.Contains(ref, "mdblist.com") {
		raw := ref
		if !strings.Contains(raw, "://") {
			raw = "https://" + raw
		}
		u, err := url.Parse(raw)
		if err != nil || strings.TrimPrefix(strings.ToLower(u.Hostname()), "www.") != "mdblist.com" {
			return MDBListRef{}, invalid("That isn't a link to a list on mdblist.com")
		}
		p, ok := strings.CutPrefix(strings.Trim(u.Path, "/"), "lists/")
		if !ok {
			return MDBListRef{}, invalid("That link isn't to an MDBList list")
		}
		path = p
	}
	parts := strings.Split(strings.Trim(path, "/"), "/")
	if len(parts) == 2 && mdbName.MatchString(parts[0]) && mdbName.MatchString(parts[1]) {
		return MDBListRef{User: parts[0], Slug: parts[1]}, nil
	}
	return MDBListRef{}, invalid("That isn't an MDBList list's ID, user/name, or link")
}

func (r MDBListRef) path() string {
	if r.ID > 0 {
		return "/lists/" + strconv.Itoa(r.ID)
	}
	return "/lists/" + url.PathEscape(r.User) + "/" + url.PathEscape(r.Slug)
}

// MDBListInfo is what MDBList says about a list.
type MDBListInfo struct {
	ID        int    `json:"id"`
	Name      string `json:"name"`
	UserName  string `json:"user_name"`
	Slug      string `json:"slug"`
	MediaType string `json:"mediatype"`
	Items     int    `json:"items"`
}

// URL is the list's page on mdblist.com.
func (i MDBListInfo) URL() string {
	if i.UserName == "" || i.Slug == "" {
		return ""
	}
	return "https://mdblist.com/lists/" + url.PathEscape(i.UserName) + "/" + url.PathEscape(i.Slug)
}

// Info is /lists/{id} or /lists/{user}/{name}: the list's name and owner.
// MDBList answers with an array, of two lists when a list has a movie
// half and a show half.
func (c *MDBList) Info(ctx context.Context, ref MDBListRef) (MDBListInfo, error) {
	var out []MDBListInfo
	if err := c.get(ctx, c.Key(), ref.path(), nil, &out); err != nil {
		return MDBListInfo{}, err
	}
	if len(out) == 0 {
		return MDBListInfo{}, ErrMDBListNotFound
	}
	return out[0], nil
}

// MDBListItem is a title on a list.
type MDBListItem struct {
	Rank        flexInt `json:"rank"`
	Title       string  `json:"title"`
	ReleaseYear flexInt `json:"release_year"`
	MediaType   string  `json:"mediatype"` // movie or show
	IMDbID      string  `json:"imdb_id"`
	TVDBID      flexInt `json:"tvdb_id"`
	TMDBID      flexInt `json:"tmdb_id"`
	Poster      string  `json:"poster"`
	IDs         struct {
		TMDB flexInt `json:"tmdb"`
		IMDb string  `json:"imdb"`
		TVDB flexInt `json:"tvdb"`
	} `json:"ids"`
}

// Items is a list's titles in rank order, at most limit (MDBList gives up
// to 1,000 a call). Its reply holds movies and shows apart; they are put
// back together by rank.
func (c *MDBList) Items(ctx context.Context, ref MDBListRef, limit int) ([]MDBListItem, error) {
	q := url.Values{"limit": {strconv.Itoa(max(limit, 1))}, "append_to_response": {"poster"}}
	var raw json.RawMessage
	if err := c.get(ctx, c.Key(), ref.path()+"/items", q, &raw); err != nil {
		return nil, err
	}
	var items []MDBListItem
	if bytes.HasPrefix(bytes.TrimSpace(raw), []byte("[")) {
		if err := json.Unmarshal(raw, &items); err != nil {
			return nil, fmt.Errorf("mdblist: decode items: %w", err)
		}
	} else {
		var split struct {
			Movies []MDBListItem `json:"movies"`
			Shows  []MDBListItem `json:"shows"`
		}
		if err := json.Unmarshal(raw, &split); err != nil {
			return nil, fmt.Errorf("mdblist: decode items: %w", err)
		}
		for i := range split.Shows {
			if split.Shows[i].MediaType == "" {
				split.Shows[i].MediaType = "show"
			}
		}
		for i := range split.Movies {
			if split.Movies[i].MediaType == "" {
				split.Movies[i].MediaType = "movie"
			}
		}
		items = append(split.Movies, split.Shows...)
	}
	slices.SortStableFunc(items, func(a, b MDBListItem) int { return int(a.Rank) - int(b.Rank) })
	return items, nil
}

// flexInt reads a number MDBList may send as a number, a string of digits
// or null.
type flexInt int64

func (f *flexInt) UnmarshalJSON(b []byte) error {
	s := strings.Trim(string(b), `"`)
	if s == "" || s == "null" {
		*f = 0
		return nil
	}
	n, err := strconv.ParseInt(s, 10, 64)
	if err != nil {
		// Not a number (an ID such as "m917496"): no value.
		*f = 0
		return nil //nolint:nilerr // an unreadable ID is no ID, not a failed list
	}
	*f = flexInt(n)
	return nil
}

// MDBListSource makes lists from MDBList's lists, which follow IMDb's,
// Rotten Tomatoes' and Letterboxd's charts among others. Titles carry
// TMDB IDs; those that only carry IMDb or TheTVDB IDs are found on TMDB.
type MDBListSource struct {
	Client *MDBList
	find   *resolver
}

// newMDBListSource is a source on client that finds titles without a TMDB
// ID through t.
func newMDBListSource(client *MDBList, r *resolver) *MDBListSource {
	return &MDBListSource{Client: client, find: r}
}

func (*MDBListSource) Kind() string       { return KindMDBList }
func (*MDBListSource) Name(Params) string { return "MDBList" }

func (s *MDBListSource) Validate(r Request) error {
	_, err := ParseMDBListRef(r.Params.Ref)
	return err
}

func (s *MDBListSource) Fetch(ctx context.Context, r Request) (Result, error) {
	ref, err := ParseMDBListRef(r.Params.Ref)
	if err != nil {
		return Result{}, err
	}
	if !s.Client.Configured() {
		return Result{}, ErrMDBListNoKey
	}
	limit := min(max(r.Limit, 1), MaxEntries)
	link := ""
	if ref.User != "" {
		link = MDBListInfo{UserName: ref.User, Slug: ref.Slug}.URL()
	} else {
		info, err := s.Client.Info(ctx, ref)
		if err != nil {
			return Result{}, err
		}
		link = info.URL()
	}
	// Ask for more than the limit when the list is of both kinds and only
	// one is kept.
	ask := limit
	if r.MediaType != "all" {
		ask = min(2*limit, 1000)
	}
	items, err := s.Client.Items(ctx, ref, ask)
	if err != nil {
		return Result{}, err
	}
	entries := make([]Entry, 0, len(items))
	for _, it := range items {
		kind := "movie"
		if it.MediaType == "show" || it.MediaType == "tv" {
			kind = "tv"
		}
		if !keeps(r.MediaType, kind) {
			continue
		}
		e := Entry{MediaType: kind, Title: it.Title, Year: int64(it.ReleaseYear), IMDbID: firstOf(it.IDs.IMDb, it.IMDbID), PosterPath: tmdbPoster(it.Poster)}
		e.TMDBID = int64(it.IDs.TMDB)
		if e.TMDBID == 0 {
			e.TMDBID = int64(it.TMDBID)
		}
		if e.TMDBID == 0 {
			found, ok, err := s.resolve(ctx, it, kind)
			if err != nil && ctx.Err() != nil {
				return Result{}, err
			}
			if ok {
				e.TMDBID, e.MediaType = int64(found.ID), found.MediaType
				if e.PosterPath == "" {
					e.PosterPath = found.PosterPath
				}
			}
		}
		entries = append(entries, e)
		if len(entries) >= limit {
			break
		}
	}
	return Result{Entries: finish(entries, r.MediaType, limit), URL: link}, nil
}

// resolve finds a title with no TMDB ID by its IMDb, then TheTVDB, ID.
func (s *MDBListSource) resolve(ctx context.Context, it MDBListItem, kind string) (tmdb.ListItem, bool, error) {
	if s.find == nil {
		return tmdb.ListItem{}, false, nil
	}
	if imdb := firstOf(it.IDs.IMDb, it.IMDbID); imdb != "" {
		if got, ok, err := s.find.find(ctx, "imdb_id", imdb, kind); ok || err != nil {
			return got, ok, err
		}
	}
	tvdb := int64(it.IDs.TVDB)
	if tvdb == 0 {
		tvdb = int64(it.TVDBID)
	}
	if tvdb > 0 {
		return s.find.find(ctx, "tvdb_id", strconv.FormatInt(tvdb, 10), kind)
	}
	return tmdb.ListItem{}, false, nil
}

func firstOf(v ...string) string {
	for _, s := range v {
		if s = strings.TrimSpace(s); s != "" {
			return s
		}
	}
	return ""
}

var tmdbImage = regexp.MustCompile(`^https://image\.tmdb\.org/t/p/[a-z0-9]+(/[A-Za-z0-9_-]+\.(?:jpg|png|webp))$`)

// tmdbPoster is the TMDB poster path in an MDBList poster address, or ""
// when the poster is from elsewhere: apps load posters through the
// server's TMDB image cache.
func tmdbPoster(link string) string {
	if m := tmdbImage.FindStringSubmatch(link); m != nil {
		return m[1]
	}
	return ""
}

// IsMDBListLimit reports whether err is MDBList's daily limit.
func IsMDBListLimit(err error) bool {
	_, ok := errors.AsType[*MDBListLimitError](err)
	return ok
}
