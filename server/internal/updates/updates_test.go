package updates

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// manifest lists 0.4.0, a yanked 0.4.1, 0.5.0-rc.1 and a broken entry, with
// downloads on the test server's own host plus one on another host. Only
// 0.4.0 has a Windows installer, as when the installer is published later.
func manifest(host string) string {
	return `{"schema": 1, "releases": [
	  {"version": "0.5.0-rc.1", "date": "2026-11-10", "notes": "- rc notes",
	   "assets": {"macos": {"url": "` + host + `/mac/WatchMyMedia-0.5.0-rc.1.dmg", "size": 2}}},
	  {"version": "0.4.1", "yanked": true, "assets": {}},
	  {"version": "0.4.0", "date": "2026-11-03", "notes": "- stable notes",
	   "assets": {
	     "macos": {"url": "` + host + `/mac/WatchMyMedia-0.4.0.dmg", "size": 137313786},
	     "linux-amd64": {"url": "` + host + `/server/watchmymedia-v0.4.0-linux-amd64.tar.gz", "size": 1},
	     "linux-arm64": {"url": "https://elsewhere.example/watchmymedia.tar.gz", "size": 1},
	     "windows-amd64": {"url": "` + host + `/server/watchmymedia-v0.4.0-windows-amd64.zip", "size": 1},
	     "windows-app-amd64": {"url": "` + host + `/windows/WatchMyMedia-0.4.0-x64-setup.exe", "size": 60000000},
	     "windows-app-arm64": {"url": "https://elsewhere.example/setup.exe", "size": 1}}},
	  {"version": "not-a-version"},
	  {"version": "0.3.4"}
	]}`
}

func serve(t *testing.T, h http.HandlerFunc) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	return srv
}

func manifestServer(t *testing.T, seenUA *string) *httptest.Server {
	var srv *httptest.Server
	srv = serve(t, func(w http.ResponseWriter, r *http.Request) {
		if seenUA != nil {
			*seenUA = r.Header.Get("User-Agent")
		}
		_, _ = w.Write([]byte(manifest(srv.URL)))
	})
	return srv
}

func checker(srv *httptest.Server, version, install, platform string) *Checker {
	c := New(srv.URL+"/releases.json", version, install)
	c.Platform = platform
	c.now = func() time.Time { return time.Date(2026, 11, 5, 12, 0, 0, 0, time.UTC) }
	return c
}

func TestCheck(t *testing.T) {
	var ua string
	srv := manifestServer(t, &ua)
	tests := []struct {
		name, version, install, platform string
		latest                           string
		update, development              bool
		download                         string // path on the test server, or "" for none
	}{
		{name: "older stable mac app", version: "v0.3.4", install: InstallMacApp, latest: "0.4.0", update: true, download: "/mac/WatchMyMedia-0.4.0.dmg"},
		{name: "same version", version: "v0.4.0", install: InstallMacApp, latest: "0.4.0", download: "/mac/WatchMyMedia-0.4.0.dmg"},
		{name: "newer than the list", version: "v0.6.0", install: InstallMacApp, latest: "0.4.0", download: "/mac/WatchMyMedia-0.4.0.dmg"},
		{name: "pre-release sees pre-releases", version: "v0.4.0-rc.2", install: InstallMacApp, latest: "0.5.0-rc.1", update: true, download: "/mac/WatchMyMedia-0.5.0-rc.1.dmg"},
		{name: "rc before its release", version: "v0.4.0-rc.1", install: InstallBinary, platform: "linux-amd64", latest: "0.5.0-rc.1", update: true},
		{name: "binary gets its platform", version: "v0.3.4", install: InstallBinary, platform: "linux-amd64", latest: "0.4.0", update: true, download: "/server/watchmymedia-v0.4.0-linux-amd64.tar.gz"},
		{name: "download on another host is dropped", version: "v0.3.4", install: InstallBinary, platform: "linux-arm64", latest: "0.4.0", update: true},
		{name: "no file for this platform", version: "v0.3.4", install: InstallBinary, platform: "freebsd-amd64", latest: "0.4.0", update: true},
		{name: "docker pulls the image", version: "v0.3.4", install: InstallDocker, latest: "0.4.0", update: true},
		{name: "windows app gets its installer", version: "v0.3.4", install: InstallWindowsApp, platform: "windows-amd64", latest: "0.4.0", update: true, download: "/windows/WatchMyMedia-0.4.0-x64-setup.exe"},
		{name: "windows app skips releases without an installer", version: "v0.4.0-rc.2", install: InstallWindowsApp, platform: "windows-amd64", latest: "0.4.0", update: true, download: "/windows/WatchMyMedia-0.4.0-x64-setup.exe"},
		{name: "windows app ignores an installer on another host", version: "v0.3.4", install: InstallWindowsApp, platform: "windows-arm64"},
		{name: "portable windows server keeps its archive", version: "v0.3.4", install: InstallBinary, platform: "windows-amd64", latest: "0.4.0", update: true, download: "/server/watchmymedia-v0.4.0-windows-amd64.zip"},
		{name: "dev build", version: "dev", install: InstallBinary, platform: "linux-amd64", latest: "0.4.0", development: true, download: "/server/watchmymedia-v0.4.0-linux-amd64.tar.gz"},
		{name: "commit after a tag", version: "v0.3.4-5-gabc1234-dirty", install: InstallMacApp, latest: "0.4.0", development: true, download: "/mac/WatchMyMedia-0.4.0.dmg"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			s := checker(srv, tt.version, tt.install, tt.platform).Check(context.Background())
			if s.Error != "" {
				t.Fatalf("error %q", s.Error)
			}
			if s.Latest != tt.latest || s.UpdateAvailable != tt.update || s.Development != tt.development {
				t.Errorf("latest %q update %v development %v, want %q %v %v", s.Latest, s.UpdateAvailable, s.Development, tt.latest, tt.update, tt.development)
			}
			got := ""
			if s.Download != nil {
				got = strings.TrimPrefix(s.Download.URL, srv.URL)
			}
			if got != tt.download {
				t.Errorf("download %q, want %q", got, tt.download)
			}
			if s.Checked == nil || s.Current != tt.version {
				t.Errorf("checked %v current %q", s.Checked, s.Current)
			}
		})
	}
	if ua != "WatchMyMedia/dev" && !strings.HasPrefix(ua, "WatchMyMedia/v") {
		t.Errorf("User-Agent %q", ua)
	}
}

func TestCheckNotesAndDate(t *testing.T) {
	s := checker(manifestServer(t, nil), "v0.3.4", InstallMacApp, "").Check(context.Background())
	if s.Date != "2026-11-03" || s.Notes != "- stable notes" {
		t.Errorf("date %q notes %q", s.Date, s.Notes)
	}
	if s.Download.Size != 137313786 {
		t.Errorf("size %d", s.Download.Size)
	}
}

// A failed check keeps the last answer that worked and says why; the next
// one that works clears the error.
func TestCheckFailureKeepsLastAnswer(t *testing.T) {
	var srv *httptest.Server
	status := http.StatusOK
	body := ""
	srv = serve(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(status)
		if body != "" {
			_, _ = w.Write([]byte(body))
			return
		}
		_, _ = w.Write([]byte(manifest(srv.URL)))
	})
	c := checker(srv, "v0.3.4", InstallMacApp, "")
	if s := c.Status(); s.Checked != nil || s.Latest != "" || s.Install != InstallMacApp || s.Source != strings.TrimPrefix(srv.URL, "http://") {
		t.Fatalf("before any check: %+v", s)
	}
	if s := c.Check(context.Background()); s.Latest != "0.4.0" {
		t.Fatalf("first check: %+v", s)
	}

	for _, tc := range []struct {
		name, body string
		status     int
		want       string
	}{
		{"server error", "", http.StatusBadGateway, "HTTP 502"},
		{"not JSON", "<html>", http.StatusOK, "could not be read"},
		{"too large", `{"releases": [], "pad": "` + strings.Repeat("x", maxManifest) + `"}`, http.StatusOK, "could not be read"},
	} {
		status, body = tc.status, tc.body
		s := c.Check(context.Background())
		if !strings.Contains(s.Error, tc.want) || s.Latest != "0.4.0" || !s.UpdateAvailable {
			t.Errorf("%s: %+v", tc.name, s)
		}
	}

	status, body = http.StatusOK, ""
	if s := c.Check(context.Background()); s.Error != "" {
		t.Errorf("error not cleared: %q", s.Error)
	}
}

func TestCheckUnreachable(t *testing.T) {
	srv := serve(t, func(w http.ResponseWriter, r *http.Request) {})
	c := checker(srv, "v0.3.4", InstallBinary, "linux-amd64")
	srv.Close()
	s := c.Check(context.Background())
	if !strings.Contains(s.Error, "could not reach the release list") || s.Checked != nil {
		t.Errorf("%+v", s)
	}
}

func TestOrder(t *testing.T) {
	// The same numbers as apple/scripts/version.sh and versionCodeOf.
	for v, want := range map[string]int{
		"v1.2.3": 10200399, "1.2.3-rc.1": 10200361, "v1.2.3-beta.2": 10200332, "v1.2.3-alpha.1": 10200301,
		"v0.4.0": 400099, "v0.4.0-rc1": 400061, "v1.2.3-rc": 10200360,
	} {
		if got, ok := order(v); !ok || got != want {
			t.Errorf("order(%q) = %d, %v; want %d", v, got, ok, want)
		}
	}
	for _, v := range []string{"dev", "3dd2970", "v0.3.4-5-gabc1234", "v1.2", "v210.0.0", "v1.100.0", "v1.2.3-rc.30", "v1.2.3-preview.1"} {
		if _, ok := order(v); ok {
			t.Errorf("order(%q) accepted", v)
		}
	}
}

func TestNewInstallKinds(t *testing.T) {
	for in, want := range map[string]string{"macos-app": InstallMacApp, "windows-app": InstallWindowsApp, "docker": InstallDocker, "": InstallBinary, "snap": InstallBinary} {
		if got := New("https://x/releases.json", "v1.0.0", in).Install; got != want {
			t.Errorf("New(%q).Install = %q, want %q", in, got, want)
		}
	}
}
