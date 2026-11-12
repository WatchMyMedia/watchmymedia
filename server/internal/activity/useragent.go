package activity

import (
	"crypto/sha256"
	"encoding/hex"
	"strings"
)

// DeviceFrom fills in a device from the client's own description and the
// request's user agent. Clients that send no device ID are told apart by a
// hash of their user agent.
func DeviceFrom(id, name, product, platform, version, userAgent string) Device {
	browser, os := parseUserAgent(userAgent)
	d := Device{Key: strings.TrimSpace(id), Name: strings.TrimSpace(name), Product: strings.TrimSpace(product),
		Platform: strings.TrimSpace(platform), Version: strings.TrimSpace(version)}
	if d.Key == "" {
		sum := sha256.Sum256([]byte(userAgent))
		d.Key = "ua:" + hex.EncodeToString(sum[:8])
	}
	if d.Platform == "" {
		d.Platform = os
	}
	if d.Product == "" {
		d.Product = browser
	}
	if d.Name == "" {
		switch {
		case browser != "" && os != "":
			d.Name = browser + " on " + os
		case browser != "":
			d.Name = browser
		case os != "":
			d.Name = os
		default:
			d.Name = "Unknown device"
		}
	}
	return d
}

// parseUserAgent names the browser and operating system well enough for a
// device list; it is not a full parser.
func parseUserAgent(ua string) (browser, os string) {
	switch {
	case strings.Contains(ua, "iPhone"):
		os = "iPhone"
	case strings.Contains(ua, "iPad"):
		os = "iPad"
	case strings.Contains(ua, "Android"):
		os = "Android"
	case strings.Contains(ua, "CrOS"):
		os = "ChromeOS"
	case strings.Contains(ua, "Mac OS X"), strings.Contains(ua, "Macintosh"):
		os = "macOS"
	case strings.Contains(ua, "Windows"):
		os = "Windows"
	case strings.Contains(ua, "Linux"):
		os = "Linux"
	}
	switch {
	case strings.Contains(ua, "Edg/"), strings.Contains(ua, "EdgiOS/"), strings.Contains(ua, "EdgA/"):
		browser = "Edge"
	case strings.Contains(ua, "OPR/"):
		browser = "Opera"
	case strings.Contains(ua, "Firefox/"), strings.Contains(ua, "FxiOS/"):
		browser = "Firefox"
	case strings.Contains(ua, "CriOS/"), strings.Contains(ua, "Chrome/"):
		browser = "Chrome"
	case strings.Contains(ua, "Safari/"):
		browser = "Safari"
	case strings.HasPrefix(ua, "curl/"):
		browser = "curl"
	}
	return browser, os
}
