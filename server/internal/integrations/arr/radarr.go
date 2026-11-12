package arr

import (
	"context"
	"net/http"
	"net/url"
	"strconv"
)

// Movie is a Radarr movie.
type Movie struct {
	ID               int        `json:"id"`
	Title            string     `json:"title"`
	Year             int        `json:"year"`
	TMDBID           int        `json:"tmdbId"`
	IMDBID           string     `json:"imdbId"`
	Path             string     `json:"path"`
	Monitored        bool       `json:"monitored"`
	HasFile          bool       `json:"hasFile"`
	SizeOnDisk       int64      `json:"sizeOnDisk"`
	QualityProfileID int        `json:"qualityProfileId"`
	MovieFile        *MovieFile `json:"movieFile,omitempty"`
}

type MovieFile struct {
	ID           int    `json:"id"`
	Path         string `json:"path"`
	RelativePath string `json:"relativePath"`
	Size         int64  `json:"size"`
}

func (c *Client) Movies(ctx context.Context) ([]Movie, error) {
	var out []Movie
	return out, c.do(ctx, http.MethodGet, "/movie", nil, nil, &out)
}

func (c *Client) Movie(ctx context.Context, id int) (Movie, error) {
	var m Movie
	return m, c.do(ctx, http.MethodGet, "/movie/"+strconv.Itoa(id), nil, nil, &m)
}

// AddMovie adds a movie by TMDB ID, monitored, and searches for it.
func (c *Client) AddMovie(ctx context.Context, tmdbID int, rootFolder string, qualityProfileID int) (Movie, error) {
	var found map[string]any
	if err := c.do(ctx, http.MethodGet, "/movie/lookup/tmdb", url.Values{"tmdbId": {strconv.Itoa(tmdbID)}}, nil, &found); err != nil {
		return Movie{}, err
	}
	found["qualityProfileId"] = qualityProfileID
	found["rootFolderPath"] = rootFolder
	found["monitored"] = true
	if _, ok := found["minimumAvailability"]; !ok {
		found["minimumAvailability"] = "released"
	}
	found["addOptions"] = map[string]any{"searchForMovie": true}
	delete(found, "id")
	var m Movie
	return m, c.do(ctx, http.MethodPost, "/movie", nil, found, &m)
}

// SetMoviesMonitored monitors or unmonitors movies.
func (c *Client) SetMoviesMonitored(ctx context.Context, ids []int, monitored bool) error {
	return c.do(ctx, http.MethodPut, "/movie/editor", nil, map[string]any{"movieIds": ids, "monitored": monitored}, nil)
}

// SearchMovies starts a search for the movies.
func (c *Client) SearchMovies(ctx context.Context, ids []int) error {
	return c.Command(ctx, "MoviesSearch", map[string]any{"movieIds": ids})
}

// DeleteMovie removes a movie, its folder too with deleteFiles.
func (c *Client) DeleteMovie(ctx context.Context, id int, deleteFiles, exclude bool) error {
	q := url.Values{"deleteFiles": {strconv.FormatBool(deleteFiles)}, "addImportExclusion": {strconv.FormatBool(exclude)}}
	return c.do(ctx, http.MethodDelete, "/movie/"+strconv.Itoa(id), q, nil, nil)
}

func (c *Client) DeleteMovieFile(ctx context.Context, id int) error {
	return c.do(ctx, http.MethodDelete, "/moviefile/"+strconv.Itoa(id), nil, nil, nil)
}

// MovieHistory lists a movie's history, newest first.
func (c *Client) MovieHistory(ctx context.Context, movieID int) ([]HistoryRecord, error) {
	var out []HistoryRecord
	return out, c.do(ctx, http.MethodGet, "/history/movie", url.Values{"movieId": {strconv.Itoa(movieID)}}, nil, &out)
}
