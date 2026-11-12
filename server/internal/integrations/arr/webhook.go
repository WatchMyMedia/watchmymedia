package arr

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
)

// Event is what Radarr's and Sonarr's webhook sends (Settings → Connect →
// Webhook), the fields WatchMyMedia reads.
type Event struct {
	EventType    string `json:"eventType"`
	InstanceName string `json:"instanceName"`

	Series *struct {
		ID     int    `json:"id"`
		Title  string `json:"title"`
		Path   string `json:"path"`
		TVDBID int    `json:"tvdbId"`
		TMDBID int    `json:"tmdbId"`
	} `json:"series"`
	Episodes []struct {
		ID            int `json:"id"`
		SeasonNumber  int `json:"seasonNumber"`
		EpisodeNumber int `json:"episodeNumber"`
	} `json:"episodes"`
	EpisodeFile         *EventFile    `json:"episodeFile"`
	EpisodeFiles        []EventFile   `json:"episodeFiles"`
	RenamedEpisodeFiles []RenamedFile `json:"renamedEpisodeFiles"`

	Movie *struct {
		ID         int    `json:"id"`
		Title      string `json:"title"`
		Year       int    `json:"year"`
		FolderPath string `json:"folderPath"`
		TMDBID     int    `json:"tmdbId"`
	} `json:"movie"`
	MovieFile         *EventFile    `json:"movieFile"`
	RenamedMovieFiles []RenamedFile `json:"renamedMovieFiles"`

	// DeletedFiles is a bool on MovieDelete and SeriesDelete, and the files an
	// upgrade replaced on Download.
	DeletedFiles json.RawMessage `json:"deletedFiles"`
	IsUpgrade    bool            `json:"isUpgrade"`
	DeleteReason string          `json:"deleteReason"`
}

type EventFile struct {
	ID           int    `json:"id"`
	Path         string `json:"path"`
	RelativePath string `json:"relativePath"`
}

type RenamedFile struct {
	Path         string `json:"path"`
	PreviousPath string `json:"previousPath"`
}

// ItemPath is the event's movie or series folder, in the app's own paths.
func (e Event) ItemPath() string {
	switch {
	case e.Series != nil:
		return e.Series.Path
	case e.Movie != nil:
		return e.Movie.FolderPath
	}
	return ""
}

// FilesDeleted reports whether a MovieDelete or SeriesDelete took the files.
func (e Event) FilesDeleted() bool {
	var b bool
	return json.Unmarshal(e.DeletedFiles, &b) == nil && b
}

// Paths are the paths, in the app's own terms, a scan should read after the
// event: the files it imported, renamed or deleted, or the whole folder when
// the item went or its files were renamed. Nil when nothing changed on disk.
func (e Event) Paths() []string {
	var out []string
	add := func(p string) {
		if p != "" {
			out = append(out, p)
		}
	}
	switch e.EventType {
	case "Download":
		if e.EpisodeFile != nil {
			add(e.EpisodeFile.Path)
		}
		for _, f := range e.EpisodeFiles {
			add(f.Path)
		}
		if e.MovieFile != nil {
			add(e.MovieFile.Path)
		}
		var replaced []EventFile
		if json.Unmarshal(e.DeletedFiles, &replaced) == nil {
			for _, f := range replaced {
				add(f.Path)
			}
		}
		if len(out) == 0 {
			add(e.ItemPath())
		}
	case "Rename":
		add(e.ItemPath())
	case "EpisodeFileDelete", "MovieFileDelete":
		if e.EpisodeFile != nil {
			add(e.EpisodeFile.Path)
		}
		if e.MovieFile != nil {
			add(e.MovieFile.Path)
		}
	case "SeriesDelete", "MovieDelete":
		if e.FilesDeleted() {
			add(e.ItemPath())
		}
	}
	return out
}

// WebhookName is what the webhook WatchMyMedia creates is called in the app.
const WebhookName = "WatchMyMedia"

// skippedEvents are the webhook's events the server has no use for.
var skippedEvents = []string{"HealthIssue", "HealthRestored", "ApplicationUpdate", "ManualInteractionRequired"}

// ConnectWebhook creates the app's webhook to hookURL with basic auth, or
// updates the one it made before. The app sends its test event before it
// saves, so an address it cannot reach is refused with the app's message.
func (c *Client) ConnectWebhook(ctx context.Context, hookURL, username, password string) error {
	var existing []map[string]any
	if err := c.do(ctx, http.MethodGet, "/notification", nil, nil, &existing); err != nil {
		return err
	}
	var hook map[string]any
	for _, n := range existing {
		if n["implementation"] == "Webhook" && n["name"] == WebhookName {
			hook = n
			break
		}
	}
	if hook == nil {
		var schema []map[string]any
		if err := c.do(ctx, http.MethodGet, "/notification/schema", nil, nil, &schema); err != nil {
			return err
		}
		for _, s := range schema {
			if s["implementation"] == "Webhook" {
				hook = s
				break
			}
		}
		if hook == nil {
			return errors.New("this version has no webhook connection")
		}
		hook["name"] = WebhookName
		delete(hook, "id")
	}
	for k, v := range hook {
		supported, ok := hook["supports"+strings.ToUpper(k[:1])+k[1:]].(bool)
		if _, isBool := v.(bool); !isBool || !strings.HasPrefix(k, "on") || !ok {
			continue
		}
		skip := false
		for _, s := range skippedEvents {
			skip = skip || k == "on"+s
		}
		hook[k] = supported && !skip
	}
	fields, _ := hook["fields"].([]any)
	set := map[string]any{"url": hookURL, "method": 1, "username": username, "password": password}
	for _, f := range fields {
		m, _ := f.(map[string]any)
		name, _ := m["name"].(string)
		if v, ok := set[name]; ok {
			m["value"] = v
		}
	}
	if id, ok := hook["id"].(float64); ok {
		return c.do(ctx, http.MethodPut, "/notification/"+strconv.Itoa(int(id)), nil, hook, nil)
	}
	return c.do(ctx, http.MethodPost, "/notification", nil, hook, nil)
}
