// Package tmdb is a small client for The Movie Database API v3 covering what
// the matcher needs: search, movie and show details, and season episodes.
package tmdb

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math/rand/v2"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/time/rate"

	"watchmymedia/server/internal/httpx"
)

const (
	DefaultBaseURL      = "https://api.themoviedb.org/3"
	DefaultImageBaseURL = "https://image.tmdb.org/t/p"
)

var (
	ErrNotFound     = errors.New("tmdb: not found")
	ErrUnauthorized = errors.New("tmdb: invalid API key")
	ErrNoAPIKey     = errors.New("tmdb: no API key configured")
)

type Client struct {
	mu      sync.RWMutex
	apiKey  string
	baseURL string
	http    *http.Client
	limiter *rate.Limiter
	backoff time.Duration
}

// Options tunes a client. The zero value is what the server runs with;
// tests set a limiter without a limit and a short backoff.
type Options struct {
	// Limiter paces every request of the client; nil means 20 a second in
	// bursts of 10, well under the ~50 TMDB tolerates.
	Limiter *rate.Limiter
	// Backoff is the first wait before trying a 5xx or a timeout again; it
	// doubles each time, with jitter. A 429 without Retry-After waits twice
	// it. Zero means one second.
	Backoff time.Duration
	// HTTP sends the requests; nil means a client with a 20 s timeout.
	HTTP *http.Client
}

// New returns a client. apiKey may be a v3 key or a v4 read access token
// (a JWT starting with "eyJ"); both are accepted by TMDB.
func New(apiKey, baseURL string) *Client { return NewWith(apiKey, baseURL, Options{}) }

// NewWith is New with options.
func NewWith(apiKey, baseURL string, opts Options) *Client {
	if baseURL == "" {
		baseURL = DefaultBaseURL
	}
	if opts.Limiter == nil {
		opts.Limiter = rate.NewLimiter(20, 10)
	}
	if opts.Backoff <= 0 {
		opts.Backoff = time.Second
	}
	if opts.HTTP == nil {
		opts.HTTP = &http.Client{Timeout: 20 * time.Second}
	}
	return &Client{
		apiKey:  strings.TrimSpace(apiKey),
		baseURL: strings.TrimRight(baseURL, "/"),
		http:    opts.HTTP,
		limiter: opts.Limiter,
		backoff: opts.Backoff,
	}
}

func (c *Client) Configured() bool { return c != nil && c.key() != "" }

func (c *Client) key() string {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.apiKey
}

// SetAPIKey replaces the key at runtime (the settings UI).
func (c *Client) SetAPIKey(key string) {
	c.mu.Lock()
	c.apiKey = strings.TrimSpace(key)
	c.mu.Unlock()
}

// Verify checks a key against TMDB without storing it. It shares the
// client's limiter, so checks count against the same budget as matching.
func (c *Client) Verify(ctx context.Context, key string) error {
	probe := &Client{apiKey: strings.TrimSpace(key), baseURL: c.baseURL, http: c.http, limiter: c.limiter, backoff: c.backoff}
	var out struct {
		Images map[string]any `json:"images"`
	}
	return probe.get(ctx, "/configuration", nil, &out)
}

type Genre struct {
	ID   int    `json:"id"`
	Name string `json:"name"`
}

type MovieResult struct {
	ID            int     `json:"id"`
	Title         string  `json:"title"`
	OriginalTitle string  `json:"original_title"`
	ReleaseDate   string  `json:"release_date"`
	Overview      string  `json:"overview"`
	PosterPath    string  `json:"poster_path"`
	BackdropPath  string  `json:"backdrop_path"`
	Popularity    float64 `json:"popularity"`
	VoteAverage   float64 `json:"vote_average"`
}

type TVResult struct {
	ID            int      `json:"id"`
	Name          string   `json:"name"`
	OriginalName  string   `json:"original_name"`
	FirstAirDate  string   `json:"first_air_date"`
	Overview      string   `json:"overview"`
	PosterPath    string   `json:"poster_path"`
	BackdropPath  string   `json:"backdrop_path"`
	Popularity    float64  `json:"popularity"`
	VoteAverage   float64  `json:"vote_average"`
	OriginCountry []string `json:"origin_country"`
}

type CastMember struct {
	ID          int    `json:"id"`
	Name        string `json:"name"`
	Character   string `json:"character"`
	ProfilePath string `json:"profile_path"`
	Order       int    `json:"order"`
}

type Credits struct {
	Cast []CastMember `json:"cast"`
}

type Movie struct {
	ID            int          `json:"id"`
	IMDBID        string       `json:"imdb_id"`
	Title         string       `json:"title"`
	OriginalTitle string       `json:"original_title"`
	ReleaseDate   string       `json:"release_date"`
	Overview      string       `json:"overview"`
	Tagline       string       `json:"tagline"`
	Runtime       int          `json:"runtime"`
	Genres        []Genre      `json:"genres"`
	VoteAverage   float64      `json:"vote_average"`
	PosterPath    string       `json:"poster_path"`
	BackdropPath  string       `json:"backdrop_path"`
	Credits       Credits      `json:"credits"`
	ReleaseDates  ReleaseDates `json:"release_dates"`
	// Videos are its trailers, teasers and clips in English or no language
	// (VideoLanguages).
	Videos Videos `json:"videos"`
}

// VideoLanguages is what GetMovie and GetMovieVideos ask videos in: English,
// and videos with no language ("null"). TMDB answers in English otherwise
// too (there is no language setting), so this keeps both lists alike.
const VideoLanguages = "en,null"

// Videos are a movie's videos on outside sites. TMDB hosts no video: each
// is a key on Site, mostly YouTube.
type Videos struct {
	Results []Video `json:"results"`
}

// Video is one of a movie's videos.
type Video struct {
	// Key is the video's id on Site, a YouTube video id for "YouTube".
	Key  string `json:"key"`
	Name string `json:"name"`
	Site string `json:"site"`
	// Type is Trailer, Teaser, Clip, Featurette, Behind the Scenes,
	// Bloopers or Opening Credits.
	Type     string `json:"type"`
	Official bool   `json:"official"`
	// Size is the video's height: 360, 480, 720, 1080 or 2160.
	Size int `json:"size"`
	// Language is an ISO 639-1 code, or "" when the video has none.
	Language    string `json:"iso_639_1"`
	PublishedAt string `json:"published_at"`
}

// ReleaseDates are a movie's releases by country, each with its
// certification (R, PG-13) where the country rates films.
type ReleaseDates struct {
	Results []struct {
		Country  string `json:"iso_3166_1"`
		Releases []struct {
			Certification string `json:"certification"`
			// Type is 1 premiere, 2 limited theatrical, 3 theatrical,
			// 4 digital, 5 physical, 6 TV.
			Type int `json:"type"`
		} `json:"release_dates"`
	} `json:"results"`
}

// Certification is the country's rating for the movie: its theatrical
// release's, else the first release that has one; "" when it has none.
func (r ReleaseDates) Certification(country string) string {
	for _, c := range r.Results {
		if !strings.EqualFold(c.Country, country) {
			continue
		}
		first := ""
		for _, rel := range c.Releases {
			cert := strings.TrimSpace(rel.Certification)
			if cert != "" && rel.Type == 3 {
				return cert
			}
			if first == "" {
				first = cert
			}
		}
		return first
	}
	return ""
}

// ContentRatings are a show's ratings by country (TV-14, TV-MA).
type ContentRatings struct {
	Results []struct {
		Country string `json:"iso_3166_1"`
		Rating  string `json:"rating"`
	} `json:"results"`
}

// Certification is the country's rating for the show, or "".
func (r ContentRatings) Certification(country string) string {
	for _, c := range r.Results {
		if strings.EqualFold(c.Country, country) {
			return strings.TrimSpace(c.Rating)
		}
	}
	return ""
}

// EpisodeRef is TMDB's summary of a show's last or next episode.
type EpisodeRef struct {
	AirDate       string `json:"air_date"`
	SeasonNumber  int    `json:"season_number"`
	EpisodeNumber int    `json:"episode_number"`
	Name          string `json:"name"`
}

type SeasonSummary struct {
	ID           int    `json:"id"`
	SeasonNumber int    `json:"season_number"`
	Name         string `json:"name"`
	Overview     string `json:"overview"`
	AirDate      string `json:"air_date"`
	PosterPath   string `json:"poster_path"`
	EpisodeCount int    `json:"episode_count"`
}

type TVShow struct {
	ID              int             `json:"id"`
	Name            string          `json:"name"`
	OriginalName    string          `json:"original_name"`
	FirstAirDate    string          `json:"first_air_date"`
	Overview        string          `json:"overview"`
	Status          string          `json:"status"`
	Genres          []Genre         `json:"genres"`
	VoteAverage     float64         `json:"vote_average"`
	PosterPath      string          `json:"poster_path"`
	BackdropPath    string          `json:"backdrop_path"`
	NumberOfSeasons int             `json:"number_of_seasons"`
	Seasons         []SeasonSummary `json:"seasons"`
	LastAirDate     string          `json:"last_air_date"`
	NextEpisode     *EpisodeRef     `json:"next_episode_to_air"`
	Credits         Credits         `json:"credits"`
	ContentRatings  ContentRatings  `json:"content_ratings"`
}

type Episode struct {
	ID            int     `json:"id"`
	EpisodeNumber int     `json:"episode_number"`
	SeasonNumber  int     `json:"season_number"`
	Name          string  `json:"name"`
	Overview      string  `json:"overview"`
	AirDate       string  `json:"air_date"`
	StillPath     string  `json:"still_path"`
	Runtime       int     `json:"runtime"`
	VoteAverage   float64 `json:"vote_average"`
}

type Season struct {
	ID           int       `json:"id"`
	SeasonNumber int       `json:"season_number"`
	Name         string    `json:"name"`
	Overview     string    `json:"overview"`
	AirDate      string    `json:"air_date"`
	PosterPath   string    `json:"poster_path"`
	Episodes     []Episode `json:"episodes"`
}

type searchResponse[T any] struct {
	Results      []T `json:"results"`
	TotalResults int `json:"total_results"`
}

// SearchMovies searches by title; year 0 means any year.
func (c *Client) SearchMovies(ctx context.Context, query string, year int) ([]MovieResult, error) {
	q := url.Values{"query": {query}, "include_adult": {"false"}}
	if year > 0 {
		q.Set("year", strconv.Itoa(year))
	}
	var res searchResponse[MovieResult]
	if err := c.get(ctx, "/search/movie", q, &res); err != nil {
		return nil, err
	}
	return res.Results, nil
}

// SearchTV searches shows by name; year 0 means any first-air year.
func (c *Client) SearchTV(ctx context.Context, query string, year int) ([]TVResult, error) {
	q := url.Values{"query": {query}, "include_adult": {"false"}}
	if year > 0 {
		q.Set("first_air_date_year", strconv.Itoa(year))
	}
	var res searchResponse[TVResult]
	if err := c.get(ctx, "/search/tv", q, &res); err != nil {
		return nil, err
	}
	return res.Results, nil
}

// GetMovie fetches a movie's details with its cast, releases and videos, in
// one request.
func (c *Client) GetMovie(ctx context.Context, id int) (*Movie, error) {
	var m Movie
	q := url.Values{"append_to_response": {"credits,release_dates,videos"}, "include_video_language": {VideoLanguages}}
	if err := c.get(ctx, "/movie/"+strconv.Itoa(id), q, &m); err != nil {
		return nil, err
	}
	return &m, nil
}

// GetMovieVideos fetches a movie's videos alone, for refreshing its
// trailers.
func (c *Client) GetMovieVideos(ctx context.Context, id int) (*Videos, error) {
	var v Videos
	if err := c.get(ctx, "/movie/"+strconv.Itoa(id)+"/videos", url.Values{"include_video_language": {VideoLanguages}}, &v); err != nil {
		return nil, err
	}
	return &v, nil
}

func (c *Client) GetTV(ctx context.Context, id int) (*TVShow, error) {
	var s TVShow
	if err := c.get(ctx, "/tv/"+strconv.Itoa(id), url.Values{"append_to_response": {"credits,content_ratings"}}, &s); err != nil {
		return nil, err
	}
	return &s, nil
}

// GetMovieReleaseDates fetches a movie's releases alone, for its
// certification.
func (c *Client) GetMovieReleaseDates(ctx context.Context, id int) (*ReleaseDates, error) {
	var r ReleaseDates
	if err := c.get(ctx, "/movie/"+strconv.Itoa(id)+"/release_dates", nil, &r); err != nil {
		return nil, err
	}
	return &r, nil
}

// GetTVContentRatings fetches a show's content ratings alone.
func (c *Client) GetTVContentRatings(ctx context.Context, id int) (*ContentRatings, error) {
	var r ContentRatings
	if err := c.get(ctx, "/tv/"+strconv.Itoa(id)+"/content_ratings", nil, &r); err != nil {
		return nil, err
	}
	return &r, nil
}

// TVDBID returns a show's TheTVDB ID, which Sonarr adds series by; 0 when
// TMDB has none.
func (c *Client) TVDBID(ctx context.Context, tvID int) (int, error) {
	var r struct {
		TVDBID int `json:"tvdb_id"`
	}
	if err := c.get(ctx, "/tv/"+strconv.Itoa(tvID)+"/external_ids", nil, &r); err != nil {
		return 0, err
	}
	return r.TVDBID, nil
}

// FindResults are the TMDB items that carry an outside ID.
type FindResults struct {
	MovieResults []MovieResult `json:"movie_results"`
	TVResults    []TVResult    `json:"tv_results"`
}

// Find looks up TMDB items by an outside ID; source is "imdb_id" or "tvdb_id".
func (c *Client) Find(ctx context.Context, id, source string) (*FindResults, error) {
	var r FindResults
	if err := c.get(ctx, "/find/"+url.PathEscape(id), url.Values{"external_source": {source}}, &r); err != nil {
		return nil, err
	}
	return &r, nil
}

func (c *Client) GetSeason(ctx context.Context, tvID, season int) (*Season, error) {
	var s Season
	if err := c.get(ctx, fmt.Sprintf("/tv/%d/season/%d", tvID, season), nil, &s); err != nil {
		return nil, err
	}
	return &s, nil
}

// Year extracts the year from a TMDB date ("2008-01-20"), or 0.
func Year(date string) int {
	if len(date) < 4 {
		return 0
	}
	y, _ := strconv.Atoi(date[:4])
	return y
}

const (
	// rateRetries is how often a 429 is tried again, after the wait its
	// Retry-After asks for (at most maxRetryAfter).
	rateRetries   = 3
	maxRetryAfter = 30 * time.Second
	// errorRetries is how often a 5xx is tried again, and timeoutRetries a
	// request that timed out, after a backoff: TMDB's own hiccups pass in
	// seconds. Every request here is a GET, so trying again is safe.
	errorRetries   = 2
	timeoutRetries = 1
)

func (c *Client) get(ctx context.Context, path string, q url.Values, out any) error {
	apiKey := c.key()
	if apiKey == "" {
		return ErrNoAPIKey
	}
	if q == nil {
		q = url.Values{}
	}
	bearer := strings.HasPrefix(apiKey, "eyJ")
	if !bearer {
		q.Set("api_key", apiKey)
	}
	u := c.baseURL + path + "?" + q.Encode()

	for attempt := 0; ; attempt++ {
		if err := c.limiter.Wait(ctx); err != nil {
			return err
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
		if err != nil {
			return err
		}
		req.Header.Set("Accept", "application/json")
		if bearer {
			req.Header.Set("Authorization", "Bearer "+apiKey)
		}
		res, err := c.http.Do(req)
		if err != nil {
			// The URL carries the key: keep it out of the error, which is
			// logged and stored as a file's match error.
			err = fmt.Errorf("tmdb: %w", httpx.Redact(err))
			var ne net.Error
			if attempt < timeoutRetries && ctx.Err() == nil && errors.As(err, &ne) && ne.Timeout() {
				if err := sleep(ctx, c.backoffFor(attempt)); err != nil {
					return err
				}
				continue
			}
			return err
		}
		body, err := httpx.ReadLimited(res.Body, 4<<20)
		_ = res.Body.Close()
		if err != nil {
			return fmt.Errorf("tmdb: read %s: %w", path, err)
		}
		switch {
		case res.StatusCode == http.StatusOK:
			if err := json.Unmarshal(body, out); err != nil {
				return fmt.Errorf("tmdb: decode %s: %w", path, err)
			}
			return nil
		case res.StatusCode == http.StatusNotFound:
			return ErrNotFound
		case res.StatusCode == http.StatusUnauthorized:
			return ErrUnauthorized
		case res.StatusCode == http.StatusTooManyRequests && attempt < rateRetries:
			wait, ok := httpx.RetryAfter(res.Header.Get("Retry-After"), time.Now(), maxRetryAfter)
			if !ok {
				wait = 2 * c.backoff
			}
			if err := sleep(ctx, wait); err != nil {
				return err
			}
		case res.StatusCode >= 500 && attempt < errorRetries:
			if err := sleep(ctx, c.backoffFor(attempt)); err != nil {
				return err
			}
		default:
			var e struct {
				StatusMessage string `json:"status_message"`
			}
			_ = json.Unmarshal(body, &e)
			return fmt.Errorf("tmdb: %s returned %d: %s", path, res.StatusCode, e.StatusMessage)
		}
	}
}

// backoffFor is the wait before retry n+1: the backoff doubled n times,
// between half and one and a half of it so retries of many requests spread.
func (c *Client) backoffFor(n int) time.Duration {
	d := c.backoff << n
	return d/2 + rand.N(d) //nolint:gosec // jitter, not a secret
}

func sleep(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-t.C:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

// EpisodeGroupSummary is one of a show's other episode orders, as its list
// gives it: "Parts", a DVD order, TheTVDB's seasons.
type EpisodeGroupSummary struct {
	ID           string `json:"id"`
	Name         string `json:"name"`
	EpisodeCount int    `json:"episode_count"`
	GroupCount   int    `json:"group_count"`
}

// EpisodeGroup is one such order: its parts, each a run of the show's
// episodes under their real season and episode numbers.
type EpisodeGroup struct {
	ID     string             `json:"id"`
	Name   string             `json:"name"`
	Groups []EpisodeGroupPart `json:"groups"`
}

// EpisodeGroupPart is one part of an order ("Part 6", "Season 6"). Order is
// its number in the order; 0 is usually the specials.
type EpisodeGroupPart struct {
	Name     string                `json:"name"`
	Order    int                   `json:"order"`
	Episodes []EpisodeGroupEpisode `json:"episodes"`
}

// EpisodeGroupEpisode is an episode's place in a part (Order, from 0) and
// its real numbers.
type EpisodeGroupEpisode struct {
	Name          string `json:"name"`
	AirDate       string `json:"air_date"`
	SeasonNumber  int    `json:"season_number"`
	EpisodeNumber int    `json:"episode_number"`
	Order         int    `json:"order"`
}

// GetEpisodeGroups lists a show's other episode orders; most shows have none.
func (c *Client) GetEpisodeGroups(ctx context.Context, tvID int) ([]EpisodeGroupSummary, error) {
	var out struct {
		Results []EpisodeGroupSummary `json:"results"`
	}
	if err := c.get(ctx, fmt.Sprintf("/tv/%d/episode_groups", tvID), nil, &out); err != nil {
		return nil, err
	}
	return out.Results, nil
}

// GetEpisodeGroup returns one episode order with its parts and episodes.
func (c *Client) GetEpisodeGroup(ctx context.Context, id string) (*EpisodeGroup, error) {
	var g EpisodeGroup
	if err := c.get(ctx, "/tv/episode_group/"+url.PathEscape(id), nil, &g); err != nil {
		return nil, err
	}
	return &g, nil
}

// Poster is one of the posters TMDB has for a movie, show or season.
type Poster struct {
	FilePath string `json:"file_path"`
	Width    int    `json:"width"`
	Height   int    `json:"height"`
	// Language is the language of the words on the poster; empty when it
	// has none.
	Language    string  `json:"iso_639_1"`
	VoteAverage float64 `json:"vote_average"`
}

// GetMoviePosters lists every poster TMDB has for a movie, in every
// language.
func (c *Client) GetMoviePosters(ctx context.Context, id int) ([]Poster, error) {
	return c.posters(ctx, "/movie/"+strconv.Itoa(id)+"/images")
}

// GetTVPosters lists every poster TMDB has for a show.
func (c *Client) GetTVPosters(ctx context.Context, id int) ([]Poster, error) {
	return c.posters(ctx, "/tv/"+strconv.Itoa(id)+"/images")
}

// GetSeasonPosters lists every poster TMDB has for a season of a show.
func (c *Client) GetSeasonPosters(ctx context.Context, tvID, season int) ([]Poster, error) {
	return c.posters(ctx, fmt.Sprintf("/tv/%d/season/%d/images", tvID, season))
}

func (c *Client) posters(ctx context.Context, path string) ([]Poster, error) {
	var out struct {
		Posters []Poster `json:"posters"`
	}
	if err := c.get(ctx, path, nil, &out); err != nil {
		return nil, err
	}
	return out.Posters, nil
}
