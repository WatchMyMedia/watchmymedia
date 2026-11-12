package tmdb

import (
	"context"
	"fmt"
	"net/url"
	"strconv"
)

// ListItem is a movie or show in one of TMDB's lists: trending, a chart,
// discover's results, a public list or a collection (PLAN-ROWS.md 7.2).
type ListItem struct {
	// MediaType is "movie" or "tv".
	MediaType  string
	ID         int
	Title      string
	Date       string // release date, or a show's first air date
	PosterPath string
	Popularity float64
}

// Page is one page of a list, 20 items at most.
type Page struct {
	Items      []ListItem
	Page       int
	TotalPages int
}

// listResult is an item as TMDB's list replies give it: movies have a title
// and a release date, shows a name and a first air date, and mixed lists
// say which with media_type.
type listResult struct {
	ID           int     `json:"id"`
	MediaType    string  `json:"media_type"`
	Title        string  `json:"title"`
	Name         string  `json:"name"`
	ReleaseDate  string  `json:"release_date"`
	FirstAirDate string  `json:"first_air_date"`
	PosterPath   string  `json:"poster_path"`
	Popularity   float64 `json:"popularity"`
}

type pageReply struct {
	Page       int          `json:"page"`
	TotalPages int          `json:"total_pages"`
	Results    []listResult `json:"results"`
	// Items is where /list/{id} puts its results.
	Items []listResult `json:"items"`
}

// items keeps the movies and shows of results, as mediaType when the
// reply doesn't say: mixed lists also hold people.
func items(results []listResult, mediaType string) []ListItem {
	out := make([]ListItem, 0, len(results))
	for _, r := range results {
		kind := r.MediaType
		if kind == "" {
			kind = mediaType
		}
		it := ListItem{MediaType: kind, ID: r.ID, PosterPath: r.PosterPath, Popularity: r.Popularity}
		switch kind {
		case "movie":
			it.Title, it.Date = r.Title, r.ReleaseDate
		case "tv":
			it.Title, it.Date = r.Name, r.FirstAirDate
		default:
			continue
		}
		if r.ID <= 0 {
			continue
		}
		out = append(out, it)
	}
	return out
}

func (c *Client) page(ctx context.Context, path string, q url.Values, mediaType string, page int) (Page, error) {
	if q == nil {
		q = url.Values{}
	}
	if page > 1 {
		q.Set("page", strconv.Itoa(page))
	}
	var r pageReply
	if err := c.get(ctx, path, q, &r); err != nil {
		return Page{}, err
	}
	results := r.Results
	if results == nil {
		results = r.Items
	}
	return Page{Items: items(results, mediaType), Page: max(r.Page, page, 1), TotalPages: r.TotalPages}, nil
}

func validMediaType(mediaType string, all bool) error {
	if mediaType == "movie" || mediaType == "tv" || (all && mediaType == "all") {
		return nil
	}
	return fmt.Errorf("tmdb: unknown media type %q", mediaType)
}

// Trending is /trending/{movie,tv,all}/{day,week}: what is watched most
// today or this week.
func (c *Client) Trending(ctx context.Context, mediaType, window string, page int) (Page, error) {
	if err := validMediaType(mediaType, true); err != nil {
		return Page{}, err
	}
	if window != "day" && window != "week" {
		return Page{}, fmt.Errorf("tmdb: unknown trending window %q", window)
	}
	return c.page(ctx, "/trending/"+mediaType+"/"+window, nil, mediaType, page)
}

// Charts are TMDB's charts for movies and for shows, by the path TMDB
// gives them.
var Charts = map[string][]string{
	"movie": {"popular", "top_rated", "now_playing", "upcoming"},
	"tv":    {"popular", "top_rated", "on_the_air", "airing_today"},
}

// Chart is one of Charts: /movie/popular, /tv/airing_today.
func (c *Client) Chart(ctx context.Context, mediaType, chart string, page int) (Page, error) {
	if err := validMediaType(mediaType, false); err != nil {
		return Page{}, err
	}
	known := false
	for _, ch := range Charts[mediaType] {
		known = known || ch == chart
	}
	if !known {
		return Page{}, fmt.Errorf("tmdb: unknown %s chart %q", mediaType, chart)
	}
	return c.page(ctx, "/"+mediaType+"/"+chart, nil, mediaType, page)
}

// Discover is /discover/{movie,tv} with the given filters. It never asks
// for adult titles, whatever q says.
func (c *Client) Discover(ctx context.Context, mediaType string, q url.Values, page int) (Page, error) {
	if err := validMediaType(mediaType, false); err != nil {
		return Page{}, err
	}
	params := url.Values{}
	for k, v := range q {
		params[k] = append([]string(nil), v...)
	}
	params.Set("include_adult", "false")
	return c.page(ctx, "/discover/"+mediaType, params, mediaType, page)
}

// List is a public TMDB list, /list/{id}, which can hold movies and shows.
func (c *Client) List(ctx context.Context, id, page int) (Page, error) {
	return c.page(ctx, "/list/"+strconv.Itoa(id), nil, "", page)
}

// Collection is a collection's films, /collection/{id}, in TMDB's order.
func (c *Client) Collection(ctx context.Context, id int) ([]ListItem, error) {
	var r struct {
		Parts []listResult `json:"parts"`
	}
	if err := c.get(ctx, "/collection/"+strconv.Itoa(id), nil, &r); err != nil {
		return nil, err
	}
	return items(r.Parts, "movie"), nil
}

// Genres is /genre/{movie,tv}/list: the genres discover filters by.
func (c *Client) Genres(ctx context.Context, mediaType string) ([]Genre, error) {
	if err := validMediaType(mediaType, false); err != nil {
		return nil, err
	}
	var r struct {
		Genres []Genre `json:"genres"`
	}
	if err := c.get(ctx, "/genre/"+mediaType+"/list", nil, &r); err != nil {
		return nil, err
	}
	return r.Genres, nil
}

// Summary is a movie's or show's title, date and poster, from its details
// without anything appended: what a list entry shows.
func (c *Client) Summary(ctx context.Context, mediaType string, id int) (ListItem, error) {
	if err := validMediaType(mediaType, false); err != nil {
		return ListItem{}, err
	}
	var r listResult
	if err := c.get(ctx, "/"+mediaType+"/"+strconv.Itoa(id), nil, &r); err != nil {
		return ListItem{}, err
	}
	got := items([]listResult{r}, mediaType)
	if len(got) == 0 {
		return ListItem{}, ErrNotFound
	}
	return got[0], nil
}
