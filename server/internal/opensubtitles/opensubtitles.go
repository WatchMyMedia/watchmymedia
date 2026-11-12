// Package opensubtitles is a small client for the OpenSubtitles.com REST API
// v1: sign-in, subtitle search and download, and the file hash it matches
// releases by. One API key belongs to the application; downloads count
// against the signed-in account's quota (5 a day per IP without one).
package opensubtitles

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/time/rate"

	"watchmymedia/server/internal/httpx"
)

const DefaultBaseURL = "https://api.opensubtitles.com/api/v1"

// Attribution is the credit the terms ask for where subtitles are searched
// or picked, on a free API key.
const Attribution = "Subtitles provided by OpenSubtitles.com"

var (
	ErrNoAPIKey = errors.New("opensubtitles: no API key in this build")
	// ErrQuota means the account (or this IP, signed out) has no downloads
	// left until the reset.
	ErrQuota = errors.New("opensubtitles: download quota used up")
	// ErrSignIn means the saved username and password were refused; the
	// client stops trying until they change.
	ErrSignIn = errors.New("opensubtitles: sign-in failed")
)

// QuotaError carries when downloads resume.
type QuotaError struct {
	Message string
	ResetAt time.Time
}

func (e *QuotaError) Error() string { return "opensubtitles: " + e.Message }
func (e *QuotaError) Unwrap() error { return ErrQuota }

type Client struct {
	apiKey    string
	userAgent string
	baseURL   string
	http      *http.Client
	limiter   *rate.Limiter

	mu       sync.Mutex
	username string
	password string
	token    string
	tokenAt  time.Time
	apiBase  string // the base_url sign-in gave (VIP accounts get their own host)
	signIn   error  // the last sign-in failure, kept until the credentials change
	account  *ProviderAccount
}

// New returns a client. userAgent is "WatchMyMedia v<version>"; the API
// refuses requests without a proper one.
func New(apiKey, userAgent, baseURL string) *Client {
	return NewWith(apiKey, userAgent, baseURL, Options{})
}

// Options tunes a client. The zero value is what the server runs with;
// tests set a limiter without a limit.
type Options struct {
	// Limiter paces every request; nil means 4 a second, under the 5 a
	// second per IP the API allows.
	Limiter *rate.Limiter
}

// NewWith is New with options.
func NewWith(apiKey, userAgent, baseURL string, opts Options) *Client {
	if baseURL == "" {
		baseURL = DefaultBaseURL
	}
	if opts.Limiter == nil {
		opts.Limiter = rate.NewLimiter(4, 4)
	}
	return &Client{
		apiKey:    strings.TrimSpace(apiKey),
		userAgent: userAgent,
		baseURL:   strings.TrimRight(baseURL, "/"),
		http:      &http.Client{Timeout: 20 * time.Second},
		limiter:   opts.Limiter,
	}
}

// Configured reports whether this build has an API key.
func (c *Client) Configured() bool { return c != nil && c.apiKey != "" }

// SetCredentials replaces the account (empty username signs out).
func (c *Client) SetCredentials(username, password string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if username == c.username && password == c.password {
		return
	}
	c.username, c.password = strings.TrimSpace(username), password
	c.token, c.apiBase, c.signIn, c.account = "", "", nil, nil
}

// Username is the saved account's name, or "".
func (c *Client) Username() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.username
}

// ProviderAccount is the signed-in OpenSubtitles account's level and downloads.
type ProviderAccount struct {
	Level             string    `json:"level"`
	VIP               bool      `json:"vip"`
	AllowedDownloads  int       `json:"allowedDownloads"`
	RemainingDownload int       `json:"remainingDownloads"`
	ResetAt           time.Time `json:"resetAt,omitzero"`
}

// Query is what a search knows about the file. IDs win over Query.
type Query struct {
	TMDBID       int64
	IMDBID       string
	ParentTMDBID int64 // the show, for an episode
	Season       int64
	Episode      int64
	Query        string
	Year         int64
	MovieHash    string
	Languages    []string
}

// Result is one subtitle found. FileID is what Download takes.
type Result struct {
	FileID            int64
	Language          string
	Release           string
	HearingImpaired   bool
	ForeignPartsOnly  bool
	HashMatch         bool
	Trusted           bool
	AITranslated      bool
	MachineTranslated bool
	Downloads         int
	FPS               float64
	Uploaded          time.Time
}

// Search finds subtitles, best first: made for this release (hash match),
// then from trusted uploaders, then human translations, then most
// downloaded. Machine translations are left out; results split over
// several files (CD1, CD2) are skipped.
func (c *Client) Search(ctx context.Context, q Query) ([]Result, error) {
	if !c.Configured() {
		return nil, ErrNoAPIKey
	}
	v := url.Values{}
	set := func(k, val string) {
		if val != "" {
			v.Set(k, strings.ToLower(val))
		}
	}
	setInt := func(k string, n int64) {
		if n > 0 {
			v.Set(k, strconv.FormatInt(n, 10))
		}
	}
	langs := append([]string(nil), q.Languages...)
	sort.Strings(langs)
	set("languages", strings.Join(langs, ","))
	set("moviehash", q.MovieHash)
	switch {
	case q.ParentTMDBID > 0:
		setInt("parent_tmdb_id", q.ParentTMDBID)
		setInt("season_number", q.Season)
		setInt("episode_number", q.Episode)
		set("type", "episode")
	case q.TMDBID > 0:
		setInt("tmdb_id", q.TMDBID)
		set("type", "movie")
	case q.IMDBID != "":
		n, _ := strconv.ParseInt(strings.TrimLeft(strings.TrimPrefix(strings.ToLower(q.IMDBID), "tt"), "0"), 10, 64)
		setInt("imdb_id", n)
	default:
		set("query", q.Query)
		setInt("year", q.Year)
		setInt("season_number", q.Season)
		setInt("episode_number", q.Episode)
	}
	var out struct {
		Data []struct {
			Attributes struct {
				Language          string  `json:"language"`
				DownloadCount     int     `json:"download_count"`
				HearingImpaired   bool    `json:"hearing_impaired"`
				FPS               float64 `json:"fps"`
				FromTrusted       bool    `json:"from_trusted"`
				ForeignPartsOnly  bool    `json:"foreign_parts_only"`
				AITranslated      bool    `json:"ai_translated"`
				MachineTranslated bool    `json:"machine_translated"`
				Release           string  `json:"release"`
				UploadDate        string  `json:"upload_date"`
				MoviehashMatch    bool    `json:"moviehash_match"`
				Files             []struct {
					FileID   int64  `json:"file_id"`
					FileName string `json:"file_name"`
				} `json:"files"`
			} `json:"attributes"`
		} `json:"data"`
	}
	// Encode sorts the keys, and the list keeps its commas: the API redirects
	// queries in any other form.
	if err := c.do(ctx, http.MethodGet, "/subtitles?"+strings.ReplaceAll(v.Encode(), "%2C", ","), nil, false, &out); err != nil {
		return nil, err
	}
	results := make([]Result, 0, len(out.Data))
	for _, d := range out.Data {
		a := d.Attributes
		if len(a.Files) != 1 || a.MachineTranslated {
			continue
		}
		up, _ := time.Parse(time.RFC3339, a.UploadDate)
		release := a.Release
		if release == "" {
			release = a.Files[0].FileName
		}
		results = append(results, Result{
			FileID: a.Files[0].FileID, Language: a.Language, Release: release, HearingImpaired: a.HearingImpaired,
			ForeignPartsOnly: a.ForeignPartsOnly, HashMatch: a.MoviehashMatch, Trusted: a.FromTrusted,
			AITranslated: a.AITranslated, Downloads: a.DownloadCount, FPS: a.FPS, Uploaded: up,
		})
	}
	sort.SliceStable(results, func(i, j int) bool {
		a, b := results[i], results[j]
		switch {
		case a.HashMatch != b.HashMatch:
			return a.HashMatch
		case a.Trusted != b.Trusted:
			return a.Trusted
		case a.AITranslated != b.AITranslated:
			return !a.AITranslated
		}
		return a.Downloads > b.Downloads
	})
	return results, nil
}

// Downloaded is a fetched subtitle file (SRT, UTF-8) and what is left of
// the quota.
type Downloaded struct {
	Data      []byte
	FileName  string
	Remaining int
	ResetAt   time.Time
}

// Download spends one download on fileID and fetches the file at once (the
// link it gives is temporary).
func (c *Client) Download(ctx context.Context, fileID int64) (Downloaded, error) {
	if !c.Configured() {
		return Downloaded{}, ErrNoAPIKey
	}
	var out struct {
		Link         string `json:"link"`
		FileName     string `json:"file_name"`
		Remaining    int    `json:"remaining"`
		Message      string `json:"message"`
		ResetTimeUTC string `json:"reset_time_utc"`
	}
	body, _ := json.Marshal(map[string]any{"file_id": fileID})
	err := c.do(ctx, http.MethodPost, "/download", body, true, &out)
	reset, _ := time.Parse(time.RFC3339, out.ResetTimeUTC)
	var status *statusError
	if errors.As(err, &status) && status.code == http.StatusNotAcceptable {
		return Downloaded{}, &QuotaError{Message: status.message, ResetAt: status.resetAt}
	}
	if err != nil {
		return Downloaded{}, err
	}
	if out.Link == "" {
		return Downloaded{}, &QuotaError{Message: out.Message, ResetAt: reset}
	}
	data, err := c.fetch(ctx, out.Link)
	if err != nil {
		return Downloaded{}, err
	}
	c.mu.Lock()
	if c.account != nil {
		c.account.RemainingDownload, c.account.ResetAt = out.Remaining, reset
	}
	c.mu.Unlock()
	return Downloaded{Data: data, FileName: out.FileName, Remaining: out.Remaining, ResetAt: reset}, nil
}

// Status signs in if needed and returns the account, or nil when no
// account is saved.
func (c *Client) Status(ctx context.Context) (*ProviderAccount, error) {
	if !c.Configured() {
		return nil, ErrNoAPIKey
	}
	if c.Username() == "" {
		return nil, nil
	}
	var out struct {
		Data struct {
			AllowedDownloads   int    `json:"allowed_downloads"`
			RemainingDownloads int    `json:"remaining_downloads"`
			Level              string `json:"level"`
			VIP                bool   `json:"vip"`
			ResetTimeUTC       string `json:"reset_time_utc"`
		} `json:"data"`
	}
	if err := c.do(ctx, http.MethodGet, "/infos/user", nil, true, &out); err != nil {
		return nil, err
	}
	reset, _ := time.Parse(time.RFC3339, out.Data.ResetTimeUTC)
	a := &ProviderAccount{Level: out.Data.Level, VIP: out.Data.VIP, AllowedDownloads: out.Data.AllowedDownloads, RemainingDownload: out.Data.RemainingDownloads, ResetAt: reset}
	c.mu.Lock()
	c.account = a
	c.mu.Unlock()
	return a, nil
}

// Cached is the account as last seen (by sign-in, Status or a download),
// without asking the API; nil before then.
func (c *Client) Cached() *ProviderAccount {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.account == nil {
		return nil
	}
	a := *c.account
	return &a
}

// Verify signs in with a username and password without keeping them.
func (c *Client) Verify(ctx context.Context, username, password string) error {
	if !c.Configured() {
		return ErrNoAPIKey
	}
	probe := New(c.apiKey, c.userAgent, c.baseURL)
	probe.http, probe.limiter = c.http, c.limiter
	probe.SetCredentials(username, password)
	_, _, err := probe.login(ctx)
	return err
}

type statusError struct {
	code    int
	message string
	resetAt time.Time
}

func (e *statusError) Error() string {
	return fmt.Sprintf("opensubtitles: HTTP %d: %s", e.code, e.message)
}

// do sends one request. auth adds the account's token, signing in first;
// a token the API calls invalid is renewed once.
func (c *Client) do(ctx context.Context, method, path string, body []byte, auth bool, out any) error {
	for attempt := 0; ; attempt++ {
		token, base := "", c.baseURL
		if auth || c.Username() != "" {
			var err error
			token, base, err = c.login(ctx)
			if err != nil && auth {
				return err
			}
		}
		err := c.send(ctx, method, base+path, body, token, out)
		var status *statusError
		if attempt == 0 && token != "" && errors.As(err, &status) && status.code == http.StatusUnauthorized {
			c.mu.Lock()
			c.token = ""
			c.mu.Unlock()
			continue
		}
		return err
	}
}

func (c *Client) send(ctx context.Context, method, u string, body []byte, token string, out any) error {
	if err := c.limiter.Wait(ctx); err != nil {
		return err
	}
	var r io.Reader
	if body != nil {
		r = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, u, r)
	if err != nil {
		return err
	}
	req.Header.Set("Api-Key", c.apiKey)
	req.Header.Set("User-Agent", c.userAgent)
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	res, err := c.http.Do(req)
	if err != nil {
		return httpx.Redact(err)
	}
	defer func() { _ = res.Body.Close() }()
	b, err := httpx.ReadLimited(res.Body, 4<<20)
	if err != nil {
		return err
	}
	if res.StatusCode >= 300 {
		var e struct {
			Message      string `json:"message"`
			Errors       []string
			ResetTimeUTC string `json:"reset_time_utc"`
		}
		_ = json.Unmarshal(b, &e)
		msg := e.Message
		if msg == "" && len(e.Errors) > 0 {
			msg = strings.Join(e.Errors, "; ")
		}
		if msg == "" {
			msg = strings.TrimSpace(string(b))
			if len(msg) > 200 {
				msg = msg[:200]
			}
		}
		reset, _ := time.Parse(time.RFC3339, e.ResetTimeUTC)
		return &statusError{code: res.StatusCode, message: msg, resetAt: reset}
	}
	if out == nil {
		return nil
	}
	return json.Unmarshal(b, out)
}

// login returns a token and the API base to use with it, signing in when
// there is none or it is over 23 hours old (tokens last 24).
func (c *Client) login(ctx context.Context) (string, string, error) {
	c.mu.Lock()
	user, pass := c.username, c.password
	if user == "" {
		c.mu.Unlock()
		return "", c.baseURL, nil
	}
	if c.signIn != nil {
		err := c.signIn
		c.mu.Unlock()
		return "", c.baseURL, err
	}
	if c.token != "" && time.Since(c.tokenAt) < 23*time.Hour {
		token, base := c.token, c.apiBase
		c.mu.Unlock()
		return token, base, nil
	}
	c.mu.Unlock()

	body, _ := json.Marshal(map[string]string{"username": user, "password": pass})
	var out struct {
		Token   string `json:"token"`
		BaseURL string `json:"base_url"`
		User    struct {
			AllowedDownloads int    `json:"allowed_downloads"`
			Level            string `json:"level"`
			VIP              bool   `json:"vip"`
		} `json:"user"`
	}
	err := c.send(ctx, http.MethodPost, c.baseURL+"/login", body, "", &out)
	var status *statusError
	if errors.As(err, &status) && (status.code == http.StatusUnauthorized || status.code == http.StatusBadRequest) {
		err = fmt.Errorf("%w: %s", ErrSignIn, status.message)
		c.mu.Lock()
		if c.username == user && c.password == pass {
			c.signIn = err
		}
		c.mu.Unlock()
		return "", c.baseURL, err
	}
	if err != nil {
		return "", c.baseURL, err
	}
	if out.Token == "" {
		return "", c.baseURL, fmt.Errorf("%w: no token", ErrSignIn)
	}
	base := c.baseURL
	if out.BaseURL != "" && strings.HasPrefix(c.baseURL, "https://api.opensubtitles.com") {
		base = "https://" + strings.TrimSuffix(strings.TrimPrefix(out.BaseURL, "https://"), "/") + "/api/v1"
	}
	c.mu.Lock()
	if c.username == user && c.password == pass {
		c.token, c.tokenAt, c.apiBase = out.Token, time.Now(), base
		if c.account == nil {
			c.account = &ProviderAccount{Level: out.User.Level, VIP: out.User.VIP, AllowedDownloads: out.User.AllowedDownloads, RemainingDownload: -1}
		}
	}
	c.mu.Unlock()
	return out.Token, base, nil
}

// fetch reads a download link: subtitle files are small, so 8 MB is plenty.
func (c *Client) fetch(ctx context.Context, link string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", c.userAgent)
	res, err := c.http.Do(req)
	if err != nil {
		// The link is a signed one-time URL: keep it out of the log.
		return nil, httpx.Redact(err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("opensubtitles: download link: HTTP %d", res.StatusCode)
	}
	return httpx.ReadLimited(res.Body, 8<<20)
}
