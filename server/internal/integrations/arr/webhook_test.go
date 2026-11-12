package arr

import (
	"encoding/json"
	"slices"
	"testing"
)

// Payloads shaped as Sonarr 4 and Radarr 6 send them, with fictional titles.
func TestEventPaths(t *testing.T) {
	cases := []struct {
		name, body string
		want       []string
	}{
		{"sonarr import", `{"eventType":"Download","series":{"id":7,"title":"Harbor Lights","path":"/tv/Harbor Lights","tvdbId":900001,"tmdbId":800001},
			"episodes":[{"id":71,"seasonNumber":1,"episodeNumber":2}],
			"episodeFile":{"id":5,"relativePath":"Season 01/Harbor Lights - S01E02.mkv","path":"/tv/Harbor Lights/Season 01/Harbor Lights - S01E02.mkv"},
			"isUpgrade":false}`,
			[]string{"/tv/Harbor Lights/Season 01/Harbor Lights - S01E02.mkv"}},
		{"sonarr upgrade", `{"eventType":"Download","series":{"id":7,"path":"/tv/Harbor Lights"},
			"episodeFile":{"path":"/tv/Harbor Lights/Season 01/e2.2160p.mkv"},"isUpgrade":true,
			"deletedFiles":[{"path":"/tv/Harbor Lights/Season 01/e2.720p.mkv"}]}`,
			[]string{"/tv/Harbor Lights/Season 01/e2.2160p.mkv", "/tv/Harbor Lights/Season 01/e2.720p.mkv"}},
		{"radarr import", `{"eventType":"Download","movie":{"id":3,"title":"Quiet Field","year":2019,"folderPath":"/movies/Quiet Field (2019)","tmdbId":700001},
			"movieFile":{"id":9,"relativePath":"Quiet Field (2019).mkv","path":"/movies/Quiet Field (2019)/Quiet Field (2019).mkv"}}`,
			[]string{"/movies/Quiet Field (2019)/Quiet Field (2019).mkv"}},
		{"rename", `{"eventType":"Rename","series":{"id":7,"path":"/tv/Harbor Lights"},"renamedEpisodeFiles":[{"path":"/tv/Harbor Lights/a.mkv","previousPath":"/tv/Harbor Lights/b.mkv"}]}`,
			[]string{"/tv/Harbor Lights"}},
		{"series deleted with files", `{"eventType":"SeriesDelete","series":{"id":7,"path":"/tv/Harbor Lights"},"deletedFiles":true}`,
			[]string{"/tv/Harbor Lights"}},
		{"series removed, files kept", `{"eventType":"SeriesDelete","series":{"id":7,"path":"/tv/Harbor Lights"},"deletedFiles":false}`, nil},
		{"movie file deleted", `{"eventType":"MovieFileDelete","movie":{"id":3,"folderPath":"/movies/Quiet Field (2019)"},"movieFile":{"path":"/movies/Quiet Field (2019)/q.mkv"},"deleteReason":"manual"}`,
			[]string{"/movies/Quiet Field (2019)/q.mkv"}},
		{"grab", `{"eventType":"Grab","series":{"id":7,"path":"/tv/Harbor Lights"}}`, nil},
		{"test", `{"eventType":"Test","instanceName":"Sonarr"}`, nil},
	}
	for _, c := range cases {
		var e Event
		if err := json.Unmarshal([]byte(c.body), &e); err != nil {
			t.Fatalf("%s: %v", c.name, err)
		}
		if got := e.Paths(); !slices.Equal(got, c.want) {
			t.Errorf("%s: Paths() = %q, want %q", c.name, got, c.want)
		}
	}
}

func TestFailedGrab(t *testing.T) {
	h := []HistoryRecord{
		{ID: 30, EventType: "downloadFolderImported", DownloadID: "abc", Data: map[string]string{"importedPath": "/movies/Q/q.mkv"}},
		{ID: 20, EventType: "grabbed", DownloadID: "abc"},
		{ID: 10, EventType: "grabbed", DownloadID: "old"},
	}
	if got := FailedGrab(h, "/movies/Q/q.mkv"); got != 20 {
		t.Errorf("FailedGrab = %d, want 20", got)
	}
	if got := FailedGrab(h[1:], "/movies/Q/q.mkv"); got != 0 {
		t.Errorf("FailedGrab without an import = %d, want 0", got)
	}
}
