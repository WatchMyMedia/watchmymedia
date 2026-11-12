// Package art fetches, caches and serves the pictures the listings feed
// names (PLAN-LIVE-TV-GUIDE.md §4.3). A client names a picture by a key the
// server made from a feed URL, never by a URL, so this is no image proxy.
package art

import (
	"crypto/sha256"
	"encoding/hex"
	"net/url"
	"regexp"
	"strings"
)

// Hosts are the only hosts pictures come from: the one SiliconDust's feed
// names (G0, §7.1).
var Hosts = map[string]bool{"img.hdhomerun.com": true}

// Path is where pictures are served; a key follows it.
const Path = "/api/live-tv/art/"

var keyRe = regexp.MustCompile(`^[0-9a-f]{24}$`)

// Key names a feed URL's picture.
func Key(rawURL string) string {
	sum := sha256.Sum256([]byte(rawURL))
	return hex.EncodeToString(sum[:12])
}

// ValidKey says whether s could be a Key.
func ValidKey(s string) bool { return keyRe.MatchString(s) }

// Allowed says whether the feed's URL may be fetched: HTTPS on a host in
// Hosts, without credentials or an odd port.
func Allowed(rawURL string) bool {
	u, err := url.Parse(rawURL)
	if err != nil || u.Scheme != "https" || u.User != nil || u.Port() != "" || u.Fragment != "" || len(rawURL) > 512 {
		return false
	}
	return Hosts[strings.ToLower(u.Hostname())]
}

// URL is the path a client fetches key's picture from, or "" for no key.
func URL(key string) string {
	if key == "" {
		return ""
	}
	return Path + key
}
