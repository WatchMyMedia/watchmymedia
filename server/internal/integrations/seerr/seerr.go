// Package seerr is a client for Seerr's v1 API (Seerr 3 and later, the
// merged Overseerr and Jellyseerr). It covers what WatchMyMedia uses
// (PLAN-ARR.md §0, §7.3), not the whole API.
//
// Calls carry the API key, which acts as Seerr's first user. Calls made for
// a person add X-Api-User, so Seerr applies that user's permissions, quotas
// and auto-approval; a body userId would instead decide with the key's own
// permissions.
package seerr

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"watchmymedia/server/internal/integrations/apiclient"
)

// Timeout bounds every call, as for Radarr and Sonarr.
const Timeout = apiclient.Timeout

// Media statuses. Seerr renumbered them: Overseerr had DELETED=6.
const (
	StatusUnknown            = 1
	StatusPending            = 2
	StatusProcessing         = 3
	StatusPartiallyAvailable = 4
	StatusAvailable          = 5
	StatusBlocklisted        = 6
	StatusDeleted            = 7
)

// Request statuses.
const (
	RequestPending   = 1
	RequestApproved  = 2
	RequestDeclined  = 3
	RequestFailed    = 4
	RequestCompleted = 5
)

// Issue types.
const (
	IssueVideo     = 1
	IssueAudio     = 2
	IssueSubtitles = 3
	IssueOther     = 4
)

// Permissions are bits in a user's permissions; ADMIN has them all.
const (
	PermAdmin            = 2
	PermManageRequests   = 16
	PermRequest          = 32
	PermAutoApprove      = 128
	PermAutoApproveMovie = 256
	PermAutoApproveTV    = 512
	PermRequestMovie     = 262144
	PermRequestTV        = 524288
	PermManageIssues     = 1048576
	PermCreateIssues     = 4194304
)

// JobSonarrScan is the job that reads Sonarr's series again and marks
// seasons available from it.
const JobSonarrScan = "sonarr-scan"

// Client talks to one Seerr.
type Client struct {
	api apiclient.Client
}

// New returns a client for a Seerr at base, an address already checked by
// arr.BaseURL. hc may be nil, as for arr.New.
func New(base, apiKey string, hc *http.Client) *Client {
	if hc == nil {
		hc = apiclient.HTTPClient(Timeout)
	}
	return &Client{api: apiclient.Client{App: "Seerr", Base: strings.TrimRight(base, "/") + "/api/v1", Key: strings.TrimSpace(apiKey), HTTP: hc, MaxBody: 16 << 20}}
}

// Error is Seerr's own answer to a call that failed. Status 202 is Seerr
// declining a request with nothing left in it to ask for.
type Error = apiclient.Error

// StatusOf is the HTTP status of Seerr's answer, or 0 when err is not one.
func StatusOf(err error) int { return apiclient.StatusOf(err) }

// call is one call: asUser > 0 makes it as that Seerr user.
type call struct {
	method string
	path   string
	query  url.Values
	body   any
	asUser int
	// accept lists statuses other than 200, 201 and 204 that mean success.
	accept []int
}

func (c *Client) do(ctx context.Context, cl call, out any) error {
	ac := apiclient.Call{Method: cl.method, Path: cl.path, Query: encode(cl.query), Body: cl.body,
		// Anything else, 202 among them, is Seerr declining.
		OK: append([]int{http.StatusOK, http.StatusCreated, http.StatusNoContent}, cl.accept...)}
	if cl.asUser > 0 {
		ac.Header = http.Header{"X-Api-User": {strconv.Itoa(cl.asUser)}}
	}
	return c.api.Do(ctx, ac, out)
}

// encode is url.Values.Encode with spaces as %20: Seerr's request validator
// refuses a search query with + for spaces.
func encode(q url.Values) string {
	return strings.ReplaceAll(q.Encode(), "+", "%20")
}

// --- status and users ------------------------------------------------------

type Status struct {
	Version   string `json:"version"`
	CommitTag string `json:"commitTag"`
}

// Status asks Seerr for its version, then checks the key with a call that
// needs it (the status itself is public).
func (c *Client) Status(ctx context.Context) (Status, error) {
	var s Status
	if err := c.do(ctx, call{method: http.MethodGet, path: "/status"}, &s); err != nil {
		return s, err
	}
	if s.Version == "" {
		return s, errors.New("that address doesn't answer as Seerr")
	}
	var me User
	if err := c.do(ctx, call{method: http.MethodGet, path: "/auth/me"}, &me); err != nil {
		if st := StatusOf(err); st == http.StatusUnauthorized || st == http.StatusForbidden {
			return s, &Error{App: "Seerr", Status: st, Message: "the API key was refused"}
		}
		return s, err
	}
	return s, nil
}

// PublicSettings are the settings Seerr shows anyone.
type PublicSettings struct {
	ApplicationTitle string `json:"applicationTitle"`
	// ApplicationURL is the address people open Seerr at, when the admin set
	// one.
	ApplicationURL string `json:"applicationUrl"`
}

func (c *Client) PublicSettings(ctx context.Context) (PublicSettings, error) {
	var p PublicSettings
	return p, c.do(ctx, call{method: http.MethodGet, path: "/settings/public"}, &p)
}

// User is a Seerr user. DisplayName is Seerr's pick of the names below.
type User struct {
	ID               int    `json:"id"`
	DisplayName      string `json:"displayName"`
	Username         string `json:"username"`
	Email            string `json:"email"`
	PlexUsername     string `json:"plexUsername"`
	JellyfinUsername string `json:"jellyfinUsername"`
	Permissions      int    `json:"permissions"`
}

// Has reports whether the user has any of the permissions; ADMIN has all.
func (u User) Has(perms ...int) bool {
	if u.Permissions&PermAdmin != 0 {
		return true
	}
	for _, p := range perms {
		if u.Permissions&p != 0 {
			return true
		}
	}
	return false
}

// CanRequest is whether the user may request movies ("movie") or shows
// ("tv").
func (u User) CanRequest(mediaType string) bool {
	if mediaType == "movie" {
		return u.Has(PermRequest, PermRequestMovie)
	}
	return u.Has(PermRequest, PermRequestTV)
}

// AutoApproved is whether the user's requests of a type skip approval.
func (u User) AutoApproved(mediaType string) bool {
	if mediaType == "movie" {
		return u.Has(PermManageRequests, PermAutoApprove, PermAutoApproveMovie)
	}
	return u.Has(PermManageRequests, PermAutoApprove, PermAutoApproveTV)
}

type pageInfo struct {
	Pages   int `json:"pages"`
	Results int `json:"results"`
}

// Users lists every Seerr user.
func (c *Client) Users(ctx context.Context) ([]User, error) {
	var out []User
	for page := range 50 {
		var resp struct {
			PageInfo pageInfo `json:"pageInfo"`
			Results  []User   `json:"results"`
		}
		q := url.Values{"take": {"100"}, "skip": {strconv.Itoa(page * 100)}, "sort": {"displayname"}}
		if err := c.do(ctx, call{method: http.MethodGet, path: "/user", query: q}, &resp); err != nil {
			return nil, err
		}
		out = append(out, resp.Results...)
		if len(resp.Results) == 0 || len(out) >= resp.PageInfo.Results {
			break
		}
	}
	return out, nil
}

// --- media -----------------------------------------------------------------

// MediaInfo is Seerr's record of an item. Seerr has none for an item nobody
// asked for and no *arr sync saw.
type MediaInfo struct {
	ID             int              `json:"id"`
	TMDBID         int              `json:"tmdbId"`
	MediaType      string           `json:"mediaType"`
	Status         int              `json:"status"`
	Seasons        []SeasonStatus   `json:"seasons"`
	Requests       []MediaRequest   `json:"requests"`
	DownloadStatus []DownloadStatus `json:"downloadStatus"`
}

type SeasonStatus struct {
	SeasonNumber int `json:"seasonNumber"`
	Status       int `json:"status"`
}

// DownloadStatus is one download in the *arr app's queue, as Seerr tracks
// it.
type DownloadStatus struct {
	Status                  string     `json:"status"`
	Size                    float64    `json:"size"`
	SizeLeft                float64    `json:"sizeLeft"`
	EstimatedCompletionTime *time.Time `json:"estimatedCompletionTime"`
	Episode                 *struct {
		SeasonNumber  int `json:"seasonNumber"`
		EpisodeNumber int `json:"episodeNumber"`
	} `json:"episode"`
}

// MediaRequest is a request in Seerr.
type MediaRequest struct {
	ID          int            `json:"id"`
	Status      int            `json:"status"`
	Type        string         `json:"type"`
	Is4K        bool           `json:"is4k"`
	CreatedAt   time.Time      `json:"createdAt"`
	RequestedBy *User          `json:"requestedBy"`
	Seasons     []SeasonStatus `json:"seasons"`
	Media       *MediaInfo     `json:"media"`
}

// SeasonNumbers lists the request's seasons.
func (r MediaRequest) SeasonNumbers() []int {
	out := make([]int, 0, len(r.Seasons))
	for _, s := range r.Seasons {
		out = append(out, s.SeasonNumber)
	}
	return out
}

// Season is a show's season as TMDB lists it.
type Season struct {
	SeasonNumber int    `json:"seasonNumber"`
	Name         string `json:"name"`
	EpisodeCount int    `json:"episodeCount"`
	AirDate      string `json:"airDate"`
}

// Media is a movie or show from a lookup or a search: TMDB's details and
// Seerr's record of it.
type Media struct {
	ID           int        `json:"id"`
	MediaType    string     `json:"mediaType"`
	Title        string     `json:"title"`
	Name         string     `json:"name"`
	ReleaseDate  string     `json:"releaseDate"`
	FirstAirDate string     `json:"firstAirDate"`
	Overview     string     `json:"overview"`
	PosterPath   string     `json:"posterPath"`
	Popularity   float64    `json:"popularity"`
	Seasons      []Season   `json:"seasons"`
	MediaInfo    *MediaInfo `json:"mediaInfo"`
}

// Label is the movie's title or the show's name.
func (m Media) Label() string {
	if m.Title != "" {
		return m.Title
	}
	return m.Name
}

// Date is the release or first air date.
func (m Media) Date() string {
	if m.ReleaseDate != "" {
		return m.ReleaseDate
	}
	return m.FirstAirDate
}

// Movie looks a movie up by TMDB ID.
func (c *Client) Movie(ctx context.Context, tmdbID int) (Media, error) {
	var m Media
	err := c.do(ctx, call{method: http.MethodGet, path: "/movie/" + strconv.Itoa(tmdbID)}, &m)
	m.MediaType = "movie"
	return m, err
}

// TV looks a show up by TMDB ID.
func (c *Client) TV(ctx context.Context, tmdbID int) (Media, error) {
	var m Media
	err := c.do(ctx, call{method: http.MethodGet, path: "/tv/" + strconv.Itoa(tmdbID)}, &m)
	m.MediaType = "tv"
	return m, err
}

// Lookup is Movie or TV by media type.
func (c *Client) Lookup(ctx context.Context, mediaType string, tmdbID int) (Media, error) {
	if mediaType == "movie" {
		return c.Movie(ctx, tmdbID)
	}
	return c.TV(ctx, tmdbID)
}

// Search returns the first page of Seerr's search for movies and shows;
// people are left out.
func (c *Client) Search(ctx context.Context, query string) ([]Media, error) {
	var resp struct {
		Results []Media `json:"results"`
	}
	q := url.Values{"query": {query}, "page": {"1"}}
	if err := c.do(ctx, call{method: http.MethodGet, path: "/search", query: q}, &resp); err != nil {
		return nil, err
	}
	out := make([]Media, 0, len(resp.Results))
	for _, m := range resp.Results {
		if m.MediaType == "movie" || m.MediaType == "tv" {
			out = append(out, m)
		}
	}
	return out, nil
}

// --- requests and issues ---------------------------------------------------

// Request asks for a movie, or some seasons of a show, as a Seerr user. A
// 202 (nothing left to ask for), 403 (quota or permission) or 409 (asked for
// already) comes back as an Error with Seerr's message.
func (c *Client) Request(ctx context.Context, asUser int, mediaType string, tmdbID int, seasons []int) (MediaRequest, error) {
	body := map[string]any{"mediaType": mediaType, "mediaId": tmdbID, "is4k": false}
	if mediaType == "tv" {
		body["seasons"] = seasons
	}
	var r MediaRequest
	// A 202 carries {"message": …}, not a request; read it as a refusal.
	var raw json.RawMessage
	err := c.do(ctx, call{method: http.MethodPost, path: "/request", body: body, asUser: asUser, accept: []int{http.StatusAccepted}}, &raw)
	if err != nil {
		return r, err
	}
	if err := json.Unmarshal(raw, &r); err != nil || r.ID == 0 {
		return r, &Error{App: "Seerr", Status: http.StatusAccepted, Message: apiclient.Message(http.StatusAccepted, raw)}
	}
	return r, nil
}

// UserRequests lists a Seerr user's requests, newest first.
func (c *Client) UserRequests(ctx context.Context, asUser, limit int) ([]MediaRequest, error) {
	var resp struct {
		Results []MediaRequest `json:"results"`
	}
	q := url.Values{"take": {strconv.Itoa(limit)}, "skip": {"0"}, "filter": {"all"}, "sort": {"added"}, "requestedBy": {strconv.Itoa(asUser)}}
	return resp.Results, c.do(ctx, call{method: http.MethodGet, path: "/request", query: q, asUser: asUser}, &resp)
}

// DeleteRequest withdraws a request as a Seerr user; Seerr lets people
// withdraw their own while they wait.
func (c *Client) DeleteRequest(ctx context.Context, asUser, requestID int) error {
	return c.do(ctx, call{method: http.MethodDelete, path: "/request/" + strconv.Itoa(requestID), asUser: asUser}, nil)
}

// RequestCounts are how many requests Seerr has in each state.
type RequestCounts struct {
	Pending int64 `json:"pending"`
}

func (c *Client) RequestCounts(ctx context.Context) (RequestCounts, error) {
	var n RequestCounts
	return n, c.do(ctx, call{method: http.MethodGet, path: "/request/count"}, &n)
}

// Issue is a reported problem in Seerr.
type Issue struct {
	ID int `json:"id"`
}

// CreateIssue reports a problem with Seerr's media record mediaID as a
// Seerr user; season and episode are 0 for a movie.
func (c *Client) CreateIssue(ctx context.Context, asUser, mediaID, issueType int, message string, season, episode int) (Issue, error) {
	body := map[string]any{"issueType": issueType, "message": message, "mediaId": mediaID,
		"problemSeason": season, "problemEpisode": episode}
	var is Issue
	return is, c.do(ctx, call{method: http.MethodPost, path: "/issue", body: body, asUser: asUser}, &is)
}

// DeleteMedia clears Seerr's record of an item, so it can be requested
// again.
func (c *Client) DeleteMedia(ctx context.Context, mediaID int) error {
	return c.do(ctx, call{method: http.MethodDelete, path: "/media/" + strconv.Itoa(mediaID)}, nil)
}

// RunJob starts one of Seerr's jobs now.
func (c *Client) RunJob(ctx context.Context, jobID string) error {
	return c.do(ctx, call{method: http.MethodPost, path: "/settings/jobs/" + url.PathEscape(jobID) + "/run"}, nil)
}
