package arr

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"slices"
	"strconv"
	"time"
)

// Series is a Sonarr series. TMDBID is 0 before Sonarr v4.0.6.
type Series struct {
	ID               int            `json:"id"`
	Title            string         `json:"title"`
	Year             int            `json:"year"`
	TVDBID           int            `json:"tvdbId"`
	TMDBID           int            `json:"tmdbId"`
	IMDBID           string         `json:"imdbId"`
	Path             string         `json:"path"`
	Monitored        bool           `json:"monitored"`
	QualityProfileID int            `json:"qualityProfileId"`
	Seasons          []SeriesSeason `json:"seasons"`
	Statistics       *Statistics    `json:"statistics,omitempty"`
}

type SeriesSeason struct {
	SeasonNumber int         `json:"seasonNumber"`
	Monitored    bool        `json:"monitored"`
	Statistics   *Statistics `json:"statistics,omitempty"`
}

type Statistics struct {
	EpisodeFileCount int   `json:"episodeFileCount"`
	EpisodeCount     int   `json:"episodeCount"`
	SizeOnDisk       int64 `json:"sizeOnDisk"`
}

type Episode struct {
	ID            int        `json:"id"`
	SeriesID      int        `json:"seriesId"`
	SeasonNumber  int        `json:"seasonNumber"`
	EpisodeNumber int        `json:"episodeNumber"`
	Title         string     `json:"title"`
	AirDateUTC    *time.Time `json:"airDateUtc"`
	HasFile       bool       `json:"hasFile"`
	Monitored     bool       `json:"monitored"`
	EpisodeFileID int        `json:"episodeFileId"`
}

type EpisodeFile struct {
	ID           int    `json:"id"`
	SeriesID     int    `json:"seriesId"`
	SeasonNumber int    `json:"seasonNumber"`
	Path         string `json:"path"`
	RelativePath string `json:"relativePath"`
	Size         int64  `json:"size"`
}

func (c *Client) Series(ctx context.Context) ([]Series, error) {
	var out []Series
	return out, c.do(ctx, http.MethodGet, "/series", nil, nil, &out)
}

func (c *Client) SeriesByID(ctx context.Context, id int) (Series, error) {
	var s Series
	return s, c.do(ctx, http.MethodGet, "/series/"+strconv.Itoa(id), nil, nil, &s)
}

// AddSeries adds a series by TVDB ID with only the given seasons monitored,
// and searches for their missing episodes, as Seerr does.
func (c *Client) AddSeries(ctx context.Context, tvdbID int, rootFolder string, qualityProfileID int, seasons []int) (Series, error) {
	var found []map[string]any
	if err := c.do(ctx, http.MethodGet, "/series/lookup", url.Values{"term": {"tvdb:" + strconv.Itoa(tvdbID)}}, nil, &found); err != nil {
		return Series{}, err
	}
	if len(found) == 0 {
		return Series{}, &Error{App: "Sonarr", Status: http.StatusNotFound, Message: "Sonarr found no series with TVDB ID " + strconv.Itoa(tvdbID)}
	}
	s := found[0]
	s["qualityProfileId"] = qualityProfileID
	s["rootFolderPath"] = rootFolder
	s["monitored"] = true
	s["seasonFolder"] = true
	s["seasons"] = monitorSeasons(s["seasons"], seasons, false)
	s["addOptions"] = map[string]any{"ignoreEpisodesWithFiles": true, "searchForMissingEpisodes": true}
	delete(s, "id")
	var out Series
	return out, c.do(ctx, http.MethodPost, "/series", nil, s, &out)
}

// MonitorSeasons monitors (or, with monitored false, unmonitors) seasons of a
// series. Sonarr sets the seasons' episodes to match.
func (c *Client) MonitorSeasons(ctx context.Context, seriesID int, seasons []int, monitored bool) error {
	var s map[string]any
	if err := c.do(ctx, http.MethodGet, "/series/"+strconv.Itoa(seriesID), nil, nil, &s); err != nil {
		return err
	}
	if monitored {
		s["monitored"] = true
		s["seasons"] = monitorSeasons(s["seasons"], seasons, true)
	} else {
		s["seasons"] = unmonitorSeasons(s["seasons"], seasons)
	}
	return c.do(ctx, http.MethodPut, "/series/"+strconv.Itoa(seriesID), nil, s, nil)
}

// SetSeriesMonitored monitors or unmonitors a whole series and its seasons.
func (c *Client) SetSeriesMonitored(ctx context.Context, seriesID int, monitored bool) error {
	var s map[string]any
	if err := c.do(ctx, http.MethodGet, "/series/"+strconv.Itoa(seriesID), nil, nil, &s); err != nil {
		return err
	}
	s["monitored"] = monitored
	if list, ok := s["seasons"].([]any); ok {
		for _, x := range list {
			if m, ok := x.(map[string]any); ok {
				m["monitored"] = monitored
			}
		}
	}
	return c.do(ctx, http.MethodPut, "/series/"+strconv.Itoa(seriesID), nil, s, nil)
}

// monitorSeasons marks the listed seasons monitored; with keep, other seasons
// keep their state, else they are unmonitored.
func monitorSeasons(raw any, want []int, keep bool) []any {
	list, _ := raw.([]any)
	for _, x := range list {
		m, ok := x.(map[string]any)
		if !ok {
			continue
		}
		n, _ := m["seasonNumber"].(float64)
		if slices.Contains(want, int(n)) {
			m["monitored"] = true
		} else if !keep {
			m["monitored"] = false
		}
	}
	return list
}

func unmonitorSeasons(raw any, seasons []int) []any {
	list, _ := raw.([]any)
	for _, x := range list {
		if m, ok := x.(map[string]any); ok {
			if n, _ := m["seasonNumber"].(float64); slices.Contains(seasons, int(n)) {
				m["monitored"] = false
			}
		}
	}
	return list
}

func (c *Client) Episodes(ctx context.Context, seriesID int) ([]Episode, error) {
	var out []Episode
	return out, c.do(ctx, http.MethodGet, "/episode", url.Values{"seriesId": {strconv.Itoa(seriesID)}}, nil, &out)
}

func (c *Client) SetEpisodesMonitored(ctx context.Context, ids []int, monitored bool) error {
	if len(ids) == 0 {
		return nil
	}
	return c.do(ctx, http.MethodPut, "/episode/monitor", nil, map[string]any{"episodeIds": ids, "monitored": monitored}, nil)
}

func (c *Client) SearchEpisodes(ctx context.Context, ids []int) error {
	if len(ids) == 0 {
		return errors.New("no episodes to search for")
	}
	return c.Command(ctx, "EpisodeSearch", map[string]any{"episodeIds": ids})
}

func (c *Client) SearchSeason(ctx context.Context, seriesID, season int) error {
	return c.Command(ctx, "SeasonSearch", map[string]any{"seriesId": seriesID, "seasonNumber": season})
}

func (c *Client) EpisodeFiles(ctx context.Context, seriesID int) ([]EpisodeFile, error) {
	var out []EpisodeFile
	return out, c.do(ctx, http.MethodGet, "/episodefile", url.Values{"seriesId": {strconv.Itoa(seriesID)}}, nil, &out)
}

func (c *Client) DeleteEpisodeFiles(ctx context.Context, ids []int) error {
	if len(ids) == 0 {
		return nil
	}
	return c.do(ctx, http.MethodDelete, "/episodefile/bulk", nil, map[string]any{"episodeFileIds": ids}, nil)
}

// DeleteSeries removes a series, its folder too with deleteFiles.
func (c *Client) DeleteSeries(ctx context.Context, id int, deleteFiles, exclude bool) error {
	q := url.Values{"deleteFiles": {strconv.FormatBool(deleteFiles)}, "addImportListExclusion": {strconv.FormatBool(exclude)}}
	return c.do(ctx, http.MethodDelete, "/series/"+strconv.Itoa(id), q, nil, nil)
}

// EpisodeHistory lists an episode's latest history, newest first.
func (c *Client) EpisodeHistory(ctx context.Context, episodeID int) ([]HistoryRecord, error) {
	var resp struct {
		Records []HistoryRecord `json:"records"`
	}
	q := url.Values{"episodeId": {strconv.Itoa(episodeID)}, "pageSize": {"50"}, "sortKey": {"date"}, "sortDirection": {"descending"}}
	return resp.Records, c.do(ctx, http.MethodGet, "/history", q, nil, &resp)
}
