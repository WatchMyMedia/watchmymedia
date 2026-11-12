// Package arr is a client for Radarr's and Sonarr's v3 API, which share their
// shape: an X-Api-Key header, /api/v3 resources, commands, a queue and history.
// It covers what WatchMyMedia uses (PLAN-ARR.md §0), not the whole API.
package arr

import (
	"context"
	"errors"
	"fmt"
	"maps"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"watchmymedia/server/internal/integrations/apiclient"
)

type Kind string

const (
	Radarr Kind = "radarr"
	Sonarr Kind = "sonarr"
)

// Timeout bounds every call: what the apps say is never worth holding up a
// page for.
const Timeout = apiclient.Timeout

// Client talks to one Radarr or Sonarr.
type Client struct {
	Kind Kind
	api  apiclient.Client
}

// New checks the address and returns a client for it. hc may be nil; its
// redirect policy should be apiclient.SameHost's, as apiclient.HTTPClient's
// is.
func New(kind Kind, address, apiKey string, hc *http.Client) (*Client, error) {
	base, err := BaseURL(address)
	if err != nil {
		return nil, err
	}
	if kind != Radarr && kind != Sonarr {
		return nil, fmt.Errorf("unknown kind %q", kind)
	}
	if hc == nil {
		hc = apiclient.HTTPClient(Timeout)
	}
	app := "Radarr"
	if kind == Sonarr {
		app = "Sonarr"
	}
	// The whole movie or series list of a large library is a few MB.
	return &Client{Kind: kind, api: apiclient.Client{App: app, Base: base + "/api/v3", Key: strings.TrimSpace(apiKey), HTTP: hc, MaxBody: 64 << 20}}, nil
}

// Patient returns a client that waits up to d for each answer. A delete
// answers once the app has moved the files to its recycling bin, which
// across disks is a copy.
func (c *Client) Patient(d time.Duration) *Client {
	cp := *c
	hc := *c.api.HTTP
	hc.Timeout = d
	cp.api.HTTP = &hc
	return &cp
}

// BaseURL normalises an app's address: http or https, a host, and any URL
// base the app runs under ("http://nas:7878/radarr"), without a trailing
// slash, query or fragment.
func BaseURL(address string) (string, error) {
	u, err := url.Parse(strings.TrimSpace(address))
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return "", errors.New("the address must be http:// or https:// with a host")
	}
	if u.User != nil {
		return "", errors.New("put the API key in its own field, not in the address")
	}
	u.RawQuery, u.Fragment = "", ""
	return strings.TrimRight(u.String(), "/"), nil
}

// Error is the app's own answer to a call that failed.
type Error = apiclient.Error

// IsNotFound reports whether the app said the thing asked for is not there.
func IsNotFound(err error) bool { return apiclient.IsNotFound(err) }

func (c *Client) do(ctx context.Context, method, path string, q url.Values, body, out any) error {
	return c.api.Do(ctx, apiclient.Call{Method: method, Path: path, Query: q.Encode(), Body: body}, out)
}

// --- shared resources -----------------------------------------------------

type SystemStatus struct {
	AppName      string `json:"appName"`
	InstanceName string `json:"instanceName"`
	Version      string `json:"version"`
}

func (c *Client) Status(ctx context.Context) (SystemStatus, error) {
	var s SystemStatus
	err := c.do(ctx, http.MethodGet, "/system/status", nil, nil, &s)
	if err == nil && s.AppName != "" && !strings.EqualFold(s.AppName, string(c.Kind)) {
		return s, fmt.Errorf("that address is %s, not %s", s.AppName, c.Kind)
	}
	return s, err
}

type RootFolder struct {
	ID        int    `json:"id"`
	Path      string `json:"path"`
	FreeSpace int64  `json:"freeSpace"`
}

func (c *Client) RootFolders(ctx context.Context) ([]RootFolder, error) {
	var out []RootFolder
	return out, c.do(ctx, http.MethodGet, "/rootfolder", nil, nil, &out)
}

type QualityProfile struct {
	ID   int    `json:"id"`
	Name string `json:"name"`
}

func (c *Client) QualityProfiles(ctx context.Context) ([]QualityProfile, error) {
	var out []QualityProfile
	return out, c.do(ctx, http.MethodGet, "/qualityprofile", nil, nil, &out)
}

// MediaManagement is the part of the app's media management settings a
// delete depends on.
type MediaManagement struct {
	RecycleBin            string `json:"recycleBin"`
	RecycleBinCleanupDays int    `json:"recycleBinCleanupDays"`
}

func (c *Client) MediaManagement(ctx context.Context) (MediaManagement, error) {
	var m MediaManagement
	return m, c.do(ctx, http.MethodGet, "/config/mediamanagement", nil, nil, &m)
}

// ExtraFile is a file the app keeps beside a movie's or episode's file:
// subtitles, metadata and other files it imported.
type ExtraFile struct {
	RelativePath string `json:"relativePath"`
}

// ExtraFiles lists the extra files of a Radarr movie or a Sonarr series.
func (c *Client) ExtraFiles(ctx context.Context, id int) ([]ExtraFile, error) {
	key := "movieId"
	if c.Kind == Sonarr {
		key = "seriesId"
	}
	var out []ExtraFile
	return out, c.do(ctx, http.MethodGet, "/extrafile", url.Values{key: {strconv.Itoa(id)}}, nil, &out)
}

// Command starts a command (a search, a rescan) and returns at once.
func (c *Client) Command(ctx context.Context, name string, args map[string]any) error {
	body := map[string]any{"name": name}
	maps.Copy(body, args)
	return c.do(ctx, http.MethodPost, "/command", nil, body, nil)
}

// QueueItem is one download in the app's queue. Sonarr lists one per
// episode.
type QueueItem struct {
	ID                   int        `json:"id"`
	MovieID              int        `json:"movieId"`
	SeriesID             int        `json:"seriesId"`
	EpisodeID            int        `json:"episodeId"`
	SeasonNumber         int        `json:"seasonNumber"`
	Title                string     `json:"title"`
	Size                 float64    `json:"size"`
	SizeLeft             float64    `json:"sizeleft"`
	TimeLeft             string     `json:"timeleft"`
	EstimatedCompletion  *time.Time `json:"estimatedCompletionTime"`
	Status               string     `json:"status"`
	TrackedDownloadState string     `json:"trackedDownloadState"`
	ErrorMessage         string     `json:"errorMessage"`
}

// Queue returns the whole download queue.
func (c *Client) Queue(ctx context.Context) ([]QueueItem, error) {
	var out []QueueItem
	for page := 1; page <= 20; page++ {
		var resp struct {
			TotalRecords int         `json:"totalRecords"`
			Records      []QueueItem `json:"records"`
		}
		q := url.Values{"page": {strconv.Itoa(page)}, "pageSize": {"250"}}
		if err := c.do(ctx, http.MethodGet, "/queue", q, nil, &resp); err != nil {
			return nil, err
		}
		out = append(out, resp.Records...)
		if len(resp.Records) == 0 || len(out) >= resp.TotalRecords {
			break
		}
	}
	return out, nil
}

// HistoryRecord is one event in an item's history.
type HistoryRecord struct {
	ID         int               `json:"id"`
	EventType  string            `json:"eventType"`
	DownloadID string            `json:"downloadId"`
	SourceName string            `json:"sourceTitle"`
	Date       time.Time         `json:"date"`
	Data       map[string]string `json:"data"`
}

// MarkFailed marks a grab failed, so the app blocklists that release.
func (c *Client) MarkFailed(ctx context.Context, historyID int) error {
	return c.do(ctx, http.MethodPost, "/history/failed/"+strconv.Itoa(historyID), nil, nil, nil)
}

// FailedGrab finds the grab that brought in the file at path (the app's own
// path), from its import record's download ID. It returns 0 when the file
// was not downloaded by the app (imported by hand) or the history is gone.
func FailedGrab(history []HistoryRecord, path string) int {
	downloadID := ""
	for _, h := range history { // newest first
		if h.EventType == "downloadFolderImported" && h.DownloadID != "" &&
			(path == "" || h.Data["importedPath"] == "" || h.Data["importedPath"] == path) {
			downloadID = h.DownloadID
			break
		}
	}
	if downloadID == "" {
		return 0
	}
	for _, h := range history {
		if h.EventType == "grabbed" && h.DownloadID == downloadID {
			return h.ID
		}
	}
	return 0
}
