// Package updates finds out whether a newer release is out. It reads the
// release manifest the release workflow publishes next to the downloads
// (PLAN-RELEASES.md §3) and picks the download that fits how this server
// was installed. It only asks when an admin presses Check for updates in
// Settings › General: nothing runs in the background.
package updates

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"

	"watchmymedia/server/internal/httpx"
)

// How the server was installed, from WATCHMYMEDIA_INSTALL: the menu bar app,
// the Windows tray app and the Docker image set it, and anything else is a
// binary someone runs.
const (
	InstallMacApp     = "macos-app"
	InstallWindowsApp = "windows-app"
	InstallDocker     = "docker"
	InstallBinary     = "binary"
)

// maxManifest bounds the manifest read: it lists ten releases or so.
const maxManifest = 1 << 20

// Manifest is releases.json. Fields the server does not use (the site's
// "latest" pointers, checksums) are left out.
type Manifest struct {
	Releases []Release `json:"releases"`
}

type Release struct {
	Version string           `json:"version"`
	Date    string           `json:"date"`
	Notes   string           `json:"notes"`
	Yanked  bool             `json:"yanked"`
	Assets  map[string]Asset `json:"assets"`
}

// Asset is one platform's download. Assets are keyed "macos" for the menu
// bar app's DMG, "windows-app-amd64" for the Windows app's installer and
// GOOS-GOARCH ("linux-amd64") for server archives.
type Asset struct {
	URL  string `json:"url"`
	Size int64  `json:"size"`
}

// Status is what the server knows about updates: always its own version,
// and the latest release once a check has worked.
type Status struct {
	Current string `json:"current" doc:"This server's version, as built."`
	// Development is set for builds that are not a release (a commit
	// between tags, a dirty tree, "dev"): they are never told to update.
	Development bool   `json:"development" doc:"Not a release build, so it is never offered an update."`
	Install     string `json:"install" enum:"macos-app,windows-app,docker,binary" doc:"How the server was installed, which decides the download."`
	Source      string `json:"source,omitempty" doc:"The host the release list comes from (updates.url)."`
	// Checked is the last check that worked; the fields below come from it.
	Checked         *time.Time `json:"checked,omitempty" doc:"When the last check that worked ran; absent before the first."`
	Latest          string     `json:"latest,omitempty" doc:"Newest release for this server: stable releases, and pre-releases too when this is one."`
	UpdateAvailable bool       `json:"updateAvailable"`
	Date            string     `json:"date,omitempty" doc:"Release date of latest (YYYY-MM-DD)."`
	Notes           string     `json:"notes,omitempty" doc:"What changed in latest, as Markdown list items."`
	Download        *Download  `json:"download,omitempty" doc:"The file for this install; absent for Docker, which pulls the image."`
	// Error is why the most recent check failed, when it did.
	Error string `json:"error,omitempty" doc:"Why the most recent check failed; the other fields are from the last one that worked."`
}

type Download struct {
	URL  string `json:"url"`
	Size int64  `json:"size,omitempty"`
}

// Checker fetches the manifest when asked and remembers the answer.
type Checker struct {
	URL     string
	Version string
	Install string
	Client  *http.Client
	// Platform is the asset key for a binary install (GOOS-GOARCH).
	Platform string
	now      func() time.Time

	mu   sync.Mutex
	last Status
}

// New returns a checker for this build. An install kind it does not know
// counts as a binary.
func New(manifestURL, version, install string) *Checker {
	switch install {
	case InstallMacApp, InstallWindowsApp, InstallDocker:
	default:
		install = InstallBinary
	}
	c := &Checker{
		URL:      manifestURL,
		Version:  version,
		Install:  install,
		Client:   &http.Client{Timeout: 10 * time.Second},
		Platform: runtime.GOOS + "-" + runtime.GOARCH,
		now:      time.Now,
	}
	c.last = c.base()
	return c
}

// base is the status before any check: what this build knows of itself.
func (c *Checker) base() Status {
	s := Status{Current: c.Version, Development: !isRelease(c.Version), Install: c.Install}
	if u, err := url.Parse(c.URL); err == nil {
		s.Source = u.Host
	}
	return s
}

// Status is what the last check found, without asking again.
func (c *Checker) Status() Status {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.last
}

// Check fetches the manifest and compares it with this build. When the
// fetch fails, the status keeps the last answer that worked and says why.
func (c *Checker) Check(ctx context.Context) Status {
	m, err := c.fetch(ctx)
	c.mu.Lock()
	defer c.mu.Unlock()
	if err != nil {
		c.last.Error = err.Error()
		return c.last
	}
	c.last = c.compare(m)
	return c.last
}

var errUnreachable = errors.New("could not reach the release list")

func (c *Checker) fetch(ctx context.Context) (Manifest, error) {
	var m Manifest
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.URL, nil)
	if err != nil {
		return m, err
	}
	req.Header.Set("User-Agent", "WatchMyMedia/"+c.Version)
	req.Header.Set("Accept", "application/json")
	res, err := c.Client.Do(req)
	if err != nil {
		return m, fmt.Errorf("%w at %s", errUnreachable, req.URL.Host)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode != http.StatusOK {
		return m, fmt.Errorf("%w at %s (HTTP %d)", errUnreachable, req.URL.Host, res.StatusCode)
	}
	body, err := httpx.ReadLimited(res.Body, maxManifest)
	if err != nil && !errors.Is(err, httpx.ErrTooLarge) {
		return m, fmt.Errorf("%w at %s", errUnreachable, req.URL.Host)
	}
	if err != nil || json.Unmarshal(body, &m) != nil {
		return m, fmt.Errorf("the release list at %s could not be read", req.URL.Host)
	}
	return m, nil
}

// compare picks the newest release this build may take: stable releases,
// plus pre-releases when this build is one, never a yanked release.
func (c *Checker) compare(m Manifest) Status {
	now := c.now().UTC()
	s := c.base()
	s.Checked = &now
	current, _ := order(c.Version)
	wantPre := isPrerelease(c.Version)
	var best *Release
	bestOrder := 0
	for i := range m.Releases {
		r := &m.Releases[i]
		n, ok := order(r.Version)
		if !ok || r.Yanked || (isPrerelease(r.Version) && !wantPre) {
			continue
		}
		// The Windows installer is published apart from the other files
		// (PUBLISH_WINDOWS_APP), so a release can come without one: offer the
		// tray app only releases it can install, never the portable server.
		if c.Install == InstallWindowsApp {
			if a, ok := r.Assets[c.assetKey()]; !ok || !c.sameSite(a.URL) {
				continue
			}
		}
		if n > bestOrder {
			best, bestOrder = r, n
		}
	}
	if best == nil {
		return s
	}
	s.Latest, s.Date, s.Notes = trimV(best.Version), best.Date, best.Notes
	s.UpdateAvailable = !s.Development && bestOrder > current
	if a, ok := best.Assets[c.assetKey()]; ok && c.sameSite(a.URL) {
		s.Download = &Download{URL: a.URL, Size: a.Size}
	}
	return s
}

func (c *Checker) assetKey() string {
	switch c.Install {
	case InstallMacApp:
		return "macos"
	case InstallWindowsApp:
		_, arch, _ := strings.Cut(c.Platform, "-")
		return "windows-app-" + arch // the installer, as the release list names it
	case InstallDocker:
		return "" // the image, not a file
	}
	return c.Platform
}

// sameSite accepts a download only from the manifest's own scheme and host,
// so a tampered or mistaken entry cannot send an admin somewhere else.
func (c *Checker) sameSite(raw string) bool {
	base, err := url.Parse(c.URL)
	if err != nil {
		return false
	}
	u, err := url.Parse(raw)
	return err == nil && u.Scheme == base.Scheme && u.Host == base.Host
}

// releasePattern is the tag format apple/scripts/version.sh and the Android
// build accept; the "v" is optional because the manifest leaves it out.
var releasePattern = regexp.MustCompile(`^v?(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.?(\d+)?)?$`)

func isRelease(v string) bool { _, ok := order(v); return ok }

func isPrerelease(v string) bool {
	m := releasePattern.FindStringSubmatch(v)
	return m != nil && m[4] != ""
}

// order is the build number the apps use for a version (version.sh,
// versionCodeOf): pre-releases sort before their release, alpha before beta
// before rc. ok is false for anything that is not a release.
func order(v string) (int, bool) {
	m := releasePattern.FindStringSubmatch(v)
	if m == nil {
		return 0, false
	}
	n := func(s string) int { i, _ := strconv.Atoi(s); return i }
	major, minor, patch, pre := n(m[1]), n(m[2]), n(m[3]), n(m[5])
	if major > 209 || minor > 99 || patch > 999 || pre > 29 {
		return 0, false
	}
	last := 99
	switch m[4] {
	case "alpha":
		last = pre
	case "beta":
		last = 30 + pre
	case "rc":
		last = 60 + pre
	}
	return major*10_000_000 + minor*100_000 + patch*100 + last, true
}

func trimV(v string) string {
	if len(v) > 0 && v[0] == 'v' {
		return v[1:]
	}
	return v
}
