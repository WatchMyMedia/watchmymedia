// Package config loads server configuration from defaults, an optional
// config.yaml in the data directory, and WATCHMYMEDIA_* environment variables,
// in that order of precedence (env wins).
package config

import (
	"errors"
	"fmt"
	"io/fs"
	"net/netip"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"

	"go.yaml.in/yaml/v3"

	"watchmymedia/server/internal/hwdevice"
	"watchmymedia/server/internal/netguard"
)

const (
	EnvPrefix      = "WATCHMYMEDIA_"
	configFileName = "config.yaml"
	dbFileName     = "watchmymedia.db"
)

type Config struct {
	// DataDir holds the database, artwork cache, config file and logs.
	DataDir   string          `yaml:"data_dir"`
	Host      string          `yaml:"host"` // listen address; empty means all interfaces
	Port      int             `yaml:"port"`
	Log       LogConfig       `yaml:"log"`
	Libraries LibrariesConfig `yaml:"libraries"`
	TMDB      TMDBConfig      `yaml:"tmdb"`
	Transcode TranscodeConfig `yaml:"transcode"`
	Scan      ScanConfig      `yaml:"scan"`
	FFmpeg    FFmpegConfig    `yaml:"ffmpeg"`
	Auth      AuthConfig      `yaml:"auth"`
	Network   NetworkConfig   `yaml:"network"`
	Cast      CastConfig      `yaml:"cast"`
	LiveTV    LiveTVConfig    `yaml:"live_tv"`
	// OpenSubtitles overrides the API key built into release binaries, for
	// development builds; leave it empty otherwise.
	OpenSubtitles OpenSubtitlesConfig `yaml:"opensubtitles"`
	Updates       UpdatesConfig       `yaml:"updates"`
	Lists         ListsConfig         `yaml:"lists"`
}

// ListsConfig is the lists rows can be made from (PLAN-ROWS.md 7.3).
// Enabled false turns off every list from an outside service (TMDB's
// charts and discover, MDBList), for a server that should ask nothing of
// them; lists of the library and imports still work.
type ListsConfig struct {
	Enabled bool `yaml:"enabled"`
}

// UpdatesConfig is Check for updates in Settings › General
// (PLAN-RELEASES.md §5), which reads the release list at URL when an admin
// asks. Install is how the server was installed (macos-app, windows-app,
// docker or binary), which decides the download it links to; the menu bar
// app, the Windows tray app and the Docker image set it, so it comes from
// WATCHMYMEDIA_INSTALL only.
type UpdatesConfig struct {
	// Enabled shows the button; turn it off for a server that should never
	// reach the internet.
	Enabled bool   `yaml:"enabled"`
	URL     string `yaml:"url"`
	Install string `yaml:"-"`
	// Host is the platform a Docker install's container runs on, when the
	// server can tell: "unraid" when Unraid's Docker page started it (it
	// sets HOST_OS=Unraid in every container), else empty. Settings then
	// word updates and GPU setup for that platform ([PLAN-UNRAID.md]).
	Host string `yaml:"-"`
}

// HostUnraid is UpdatesConfig.Host for a container Unraid started.
const HostUnraid = "unraid"

// DefaultUpdatesURL is the release list the release workflow publishes.
const DefaultUpdatesURL = "https://downloads.watchmy.media/releases.json"

// OpenSubtitlesConfig is the online subtitle provider's API key and address
// (the address exists for tests). The account downloads count against is
// set in Settings › Subtitles.
type OpenSubtitlesConfig struct {
	APIKey  string `yaml:"api_key"`
	BaseURL string `yaml:"base_url"`
}

// CastConfig is casting to Google Cast devices (PLAN-CAST.md). The receiver
// is a web page hosted for every WatchMyMedia server at one address; each
// cast tells it which server to talk to.
type CastConfig struct {
	// Enabled offers casting in the web UI and the apps.
	Enabled bool `yaml:"enabled"`
	// AppID is the receiver application senders launch, from the Google Cast
	// SDK Developer Console. Empty means no Cast button anywhere.
	AppID string `yaml:"app_id"`
	// URL is an address TVs can reach this server at, such as the HTTPS
	// domain of a reverse proxy. It is offered to receivers first.
	URL string `yaml:"url"`
	// ReceiverOrigins are the web origins receiver pages are served from;
	// they may call the API from the browser (CORS).
	ReceiverOrigins []string `yaml:"receiver_origins"`
}

// LiveTVConfig controls the staged same-channel tuner sharing rollout.
type LiveTVConfig struct {
	ShareSources bool `yaml:"share_sources"`
}

// DefaultCastAppID is the production receiver's Cast App ID, registered in
// the Google Cast SDK Developer Console under Eldwick Software (PLAN-CAST.md
// R0); DefaultCastReceiverOrigin is where that receiver is hosted.
const (
	DefaultCastAppID          = "0A4D0B6A"
	DefaultCastReceiverOrigin = "https://cast.watchmy.media"
)

type NetworkConfig struct {
	// RemoteUploadBps is an optional aggregate media ceiling for non-LAN
	// clients. Zero is unlimited; enforcement starts off for observation.
	RemoteUploadBps int64 `yaml:"remote_upload_bps"`
	EnforceUpload   bool  `yaml:"enforce_upload"`
	// TrustedProxies are reverse proxies (Caddy, Tailscale Serve) whose
	// X-Forwarded-For header is believed. Addresses or CIDR ranges.
	TrustedProxies []string `yaml:"trusted_proxies"`
	// AdminNetworks allow first-run setup and password-free admin, including HTTP. Addresses,
	// CIDR ranges, "local" (loopback, private LAN, Tailscale) or "any";
	// entries starting with "!" are excluded even when another entry covers
	// them.
	AdminNetworks []string `yaml:"admin_networks"`
	// RemoteAdmin overrides the stored switch; nil uses the setting (default on).
	RemoteAdmin *bool `yaml:"remote_admin"`
	// Discovery announces the server on the local network (mDNS/DNS-SD) so
	// TV and phone apps find it without an address. Needs host networking
	// in Docker; off when the server listens only on loopback.
	Discovery bool `yaml:"discovery"`

	TrustedProxyPrefixes []netip.Prefix `yaml:"-"`
	AdminPrefixes        []netip.Prefix `yaml:"-"`
	AdminExcluded        []netip.Prefix `yaml:"-"` // "!" entries in AdminNetworks
}

type AuthConfig struct {
	// SessionTTL is the sliding lifetime of a sign-in.
	SessionTTL time.Duration `yaml:"session_ttl"`
	// SecureCookies marks the session cookie Secure; enable behind HTTPS.
	SecureCookies bool `yaml:"secure_cookies"`
	// DeviceSessionTTL is the sliding lifetime of a native app's sign-in
	// (TVs and phones, which hold a bearer token rather than a cookie).
	DeviceSessionTTL time.Duration `yaml:"device_session_ttl"`
	// Turnstile puts Cloudflare's bot check in front of password sign-in.
	Turnstile TurnstileConfig `yaml:"turnstile"`
}

// TurnstileConfig sets Cloudflare Turnstile from config instead of Settings.
// Keys set here override the ones saved in Settings, which then cannot be
// changed there.
type TurnstileConfig struct {
	SiteKey   string `yaml:"site_key"`
	SecretKey string `yaml:"secret_key"`
	// Disabled turns Turnstile off whatever Settings says: the way back in
	// when a key problem stops everyone signing in.
	Disabled bool `yaml:"disabled"`
}

type ScanConfig struct {
	// Interval between scheduled full scans of every library, which find what
	// the filesystem watcher misses (network shares written to by other
	// machines, Docker Desktop mounts, folders beyond the system's limit on
	// watches). An interval saved in Settings wins.
	Interval time.Duration `yaml:"interval"`
	// StableFor is how long a file's size and mtime must be unchanged before
	// it is probed, so files still being copied are left alone.
	StableFor time.Duration `yaml:"stable_for"`
	// Workers is the number of concurrent ffprobe processes.
	Workers int `yaml:"workers"`
	// DetectSegments finds intros and credits by matching audio across each
	// season in the background (low priority, about a second of CPU per
	// episode). Chapter marks are used either way.
	DetectSegments bool `yaml:"detect_segments"`
}

type FFmpegConfig struct {
	FFmpeg  string `yaml:"ffmpeg"`
	FFprobe string `yaml:"ffprobe"`
}

type LogConfig struct {
	Level  string `yaml:"level"`  // debug | info | warn | error
	Format string `yaml:"format"` // text | json
}

type LibrariesConfig struct {
	Movies string `yaml:"movies"`
	TV     string `yaml:"tv"`
}

type TMDBConfig struct {
	APIKey string `yaml:"api_key"`
	// BaseURL and ImageBaseURL exist for tests and proxies; leave empty for TMDB.
	BaseURL      string `yaml:"base_url"`
	ImageBaseURL string `yaml:"image_base_url"`
}

type TranscodeConfig struct {
	AdaptiveHLS bool `yaml:"adaptive_hls"` // experimental opt-in, disabled until qualified
	// Dir is where HLS session segments are written. Defaults to
	// <data_dir>/transcode. The server works in a watchmymedia-sessions
	// folder inside it and clears only that folder at startup, so Dir may be
	// a shared folder such as /tmp or /dev/shm.
	Dir string `yaml:"dir"`
	// HWAccel picks the H.264 encoder: auto, software, videotoolbox, nvenc, qsv, vaapi, amf.
	HWAccel string `yaml:"hwaccel"`
	// HWDevice selects a backend-specific adapter; nil or -1 uses its default.
	HWDevice *int `yaml:"device"`
	// IdleTimeout stops a session nobody has fetched from for this long.
	IdleTimeout time.Duration `yaml:"idle_timeout"`
	MaxSessions int           `yaml:"max_sessions"`
	// MaxDownloadSessions is how many of those sessions offline downloads
	// may use at once (PLAN-DOWNLOADS.md §3.2); they run at low priority
	// and give way to plays.
	MaxDownloadSessions int `yaml:"max_download_sessions"`
	// Playback cache budgets apply to owned file and Live TV session output, not
	// library files or durable recordings. Zero disables a particular limit.
	MaxCacheBytes   int64         `yaml:"max_cache_bytes"`
	MaxSessionBytes int64         `yaml:"max_session_bytes"`
	MinFreeBytes    int64         `yaml:"min_free_bytes"`
	EventAhead      time.Duration `yaml:"event_ahead"`
	EventKeepBehind time.Duration `yaml:"event_keep_behind"`
}

func Default() Config {
	return Config{
		DataDir: "./data",
		Port:    27587,
		Log:     LogConfig{Level: "info", Format: "text"},
		Scan:    ScanConfig{Interval: 15 * time.Minute, StableFor: 10 * time.Second, Workers: 4, DetectSegments: true},
		Transcode: TranscodeConfig{HWAccel: "auto", IdleTimeout: 60 * time.Second, MaxSessions: 8, MaxDownloadSessions: 1,
			MaxCacheBytes: 4 << 30, MaxSessionBytes: 512 << 20, MinFreeBytes: 1 << 30,
			EventAhead: 32 * time.Second, EventKeepBehind: 32 * time.Second},
		Auth:    AuthConfig{SessionTTL: 30 * 24 * time.Hour, DeviceSessionTTL: 365 * 24 * time.Hour},
		Network: NetworkConfig{AdminNetworks: []string{netguard.Local}, Discovery: true},
		Cast:    CastConfig{Enabled: true, AppID: DefaultCastAppID, ReceiverOrigins: []string{DefaultCastReceiverOrigin}},
		FFmpeg:  FFmpegConfig{FFmpeg: "ffmpeg", FFprobe: "ffprobe"},
		Updates: UpdatesConfig{Enabled: true, URL: DefaultUpdatesURL},
		Lists:   ListsConfig{Enabled: true},
	}
}

// Load builds the effective configuration.
func Load() (Config, error) {
	cfg := Default()

	// The data dir must be known before we can find config.yaml.
	if v := os.Getenv(EnvPrefix + "DATA_DIR"); v != "" {
		cfg.DataDir = v
	}

	path := os.Getenv(EnvPrefix + "CONFIG")
	if path == "" {
		path = filepath.Join(cfg.DataDir, configFileName)
	}
	// The path is the operator's own WATCHMYMEDIA_CONFIG or data folder.
	if b, err := os.ReadFile(path); err == nil { //nolint:gosec // G703: not request input
		if err := yaml.Unmarshal(b, &cfg); err != nil {
			return cfg, fmt.Errorf("parse %s: %w", path, err)
		}
	} else if !errors.Is(err, fs.ErrNotExist) {
		return cfg, fmt.Errorf("read %s: %w", path, err)
	}

	if err := applyEnv(&cfg); err != nil {
		return cfg, err
	}
	return cfg, cfg.finalize()
}

func applyEnv(cfg *Config) error {
	str := func(key string, dst *string) {
		if v, ok := os.LookupEnv(EnvPrefix + key); ok {
			*dst = v
		}
	}
	str("DATA_DIR", &cfg.DataDir)
	str("HOST", &cfg.Host)
	str("LOG_LEVEL", &cfg.Log.Level)
	str("LOG_FORMAT", &cfg.Log.Format)
	str("MOVIES_PATH", &cfg.Libraries.Movies)
	str("TV_PATH", &cfg.Libraries.TV)
	str("TMDB_API_KEY", &cfg.TMDB.APIKey)
	str("TMDB_BASE_URL", &cfg.TMDB.BaseURL)
	str("TMDB_IMAGE_BASE_URL", &cfg.TMDB.ImageBaseURL)
	str("OPENSUBTITLES_API_KEY", &cfg.OpenSubtitles.APIKey)
	str("OPENSUBTITLES_BASE_URL", &cfg.OpenSubtitles.BaseURL)
	str("TRANSCODE_DIR", &cfg.Transcode.Dir)
	str("TRANSCODE_HWACCEL", &cfg.Transcode.HWAccel)
	str("FFMPEG_PATH", &cfg.FFmpeg.FFmpeg)
	str("FFPROBE_PATH", &cfg.FFmpeg.FFprobe)
	str("AUTH_TURNSTILE_SITE_KEY", &cfg.Auth.Turnstile.SiteKey)
	str("AUTH_TURNSTILE_SECRET_KEY", &cfg.Auth.Turnstile.SecretKey)
	if v, ok := os.LookupEnv(EnvPrefix + "TRUSTED_PROXIES"); ok {
		cfg.Network.TrustedProxies = netguard.SplitList(v)
	}
	if v, ok := os.LookupEnv(EnvPrefix + "ADMIN_NETWORKS"); ok {
		cfg.Network.AdminNetworks = netguard.SplitList(v)
	}
	str("UPDATES_URL", &cfg.Updates.URL)
	str("INSTALL", &cfg.Updates.Install)
	// Not a WATCHMYMEDIA_ variable: Unraid's own, set on every container it runs.
	if strings.EqualFold(strings.TrimSpace(os.Getenv("HOST_OS")), "Unraid") {
		cfg.Updates.Host = HostUnraid
	}
	str("CAST_APP_ID", &cfg.Cast.AppID)
	str("CAST_URL", &cfg.Cast.URL)
	if v, ok := os.LookupEnv(EnvPrefix + "CAST_RECEIVER_ORIGINS"); ok {
		cfg.Cast.ReceiverOrigins = netguard.SplitList(v)
	}

	integer := func(key string, dst *int) error {
		v, ok := os.LookupEnv(EnvPrefix + key)
		if !ok {
			return nil
		}
		n, err := strconv.Atoi(v)
		if err != nil {
			return fmt.Errorf("%s%s: %w", EnvPrefix, key, err)
		}
		*dst = n
		return nil
	}
	bytesValue := func(key string, dst *int64) error {
		v, ok := os.LookupEnv(EnvPrefix + key)
		if !ok {
			return nil
		}
		n, err := strconv.ParseInt(v, 10, 64)
		if err != nil {
			return fmt.Errorf("%s%s: %w", EnvPrefix, key, err)
		}
		*dst = n
		return nil
	}
	boolean := func(key string, dst *bool) error {
		v, ok := os.LookupEnv(EnvPrefix + key)
		if !ok {
			return nil
		}
		b, err := strconv.ParseBool(v)
		if err != nil {
			return fmt.Errorf("%s%s: %w", EnvPrefix, key, err)
		}
		*dst = b
		return nil
	}
	duration := func(key string, dst *time.Duration) error {
		v, ok := os.LookupEnv(EnvPrefix + key)
		if !ok {
			return nil
		}
		d, err := time.ParseDuration(v)
		if err != nil {
			return fmt.Errorf("%s%s: %w", EnvPrefix, key, err)
		}
		*dst = d
		return nil
	}
	optionalInteger := func(key string, dst **int) error {
		if _, ok := os.LookupEnv(EnvPrefix + key); !ok {
			return nil
		}
		var n int
		if err := integer(key, &n); err != nil {
			return err
		}
		*dst = &n
		return nil
	}
	optionalBoolean := func(key string, dst **bool) error {
		if _, ok := os.LookupEnv(EnvPrefix + key); !ok {
			return nil
		}
		var b bool
		if err := boolean(key, &b); err != nil {
			return err
		}
		*dst = &b
		return nil
	}
	return errors.Join(
		integer("PORT", &cfg.Port),
		integer("SCAN_WORKERS", &cfg.Scan.Workers),
		integer("TRANSCODE_MAX_SESSIONS", &cfg.Transcode.MaxSessions),
		optionalInteger("TRANSCODE_DEVICE", &cfg.Transcode.HWDevice),
		integer("TRANSCODE_MAX_DOWNLOAD_SESSIONS", &cfg.Transcode.MaxDownloadSessions),
		bytesValue("TRANSCODE_MAX_CACHE_BYTES", &cfg.Transcode.MaxCacheBytes),
		bytesValue("TRANSCODE_MAX_SESSION_BYTES", &cfg.Transcode.MaxSessionBytes),
		bytesValue("TRANSCODE_MIN_FREE_BYTES", &cfg.Transcode.MinFreeBytes),
		bytesValue("REMOTE_UPLOAD_BPS", &cfg.Network.RemoteUploadBps),
		boolean("ENFORCE_UPLOAD", &cfg.Network.EnforceUpload),
		duration("TRANSCODE_EVENT_AHEAD", &cfg.Transcode.EventAhead),
		duration("TRANSCODE_EVENT_KEEP_BEHIND", &cfg.Transcode.EventKeepBehind),
		duration("TRANSCODE_IDLE_TIMEOUT", &cfg.Transcode.IdleTimeout),
		duration("AUTH_SESSION_TTL", &cfg.Auth.SessionTTL),
		duration("AUTH_DEVICE_SESSION_TTL", &cfg.Auth.DeviceSessionTTL),
		boolean("AUTH_SECURE_COOKIES", &cfg.Auth.SecureCookies),
		boolean("AUTH_TURNSTILE_DISABLED", &cfg.Auth.Turnstile.Disabled),
		boolean("DISCOVERY", &cfg.Network.Discovery),
		optionalBoolean("REMOTE_ADMIN", &cfg.Network.RemoteAdmin),
		boolean("CAST_ENABLED", &cfg.Cast.Enabled),
		boolean("LIVE_TV_SHARE_SOURCES", &cfg.LiveTV.ShareSources),
		boolean("TRANSCODE_ADAPTIVE_HLS", &cfg.Transcode.AdaptiveHLS),
		boolean("UPDATES_ENABLED", &cfg.Updates.Enabled),
		boolean("LISTS", &cfg.Lists.Enabled),
		duration("SCAN_INTERVAL", &cfg.Scan.Interval),
		duration("SCAN_STABLE_FOR", &cfg.Scan.StableFor),
		boolean("SCAN_DETECT_SEGMENTS", &cfg.Scan.DetectSegments),
	)
}

func (c *Config) finalize() error {
	if c.Network.RemoteUploadBps < 0 || c.Network.RemoteUploadBps > 1_000_000_000_000 {
		return fmt.Errorf("network.remote_upload_bps must be between 0 and 1000000000000")
	}
	if c.Port < 1 || c.Port > 65535 {
		return fmt.Errorf("port %d out of range", c.Port)
	}
	var err error
	if c.DataDir, err = filepath.Abs(c.DataDir); err != nil {
		return err
	}
	if c.Transcode.Dir == "" {
		c.Transcode.Dir = filepath.Join(c.DataDir, "transcode")
	}
	// Library paths are cleaned but not required to exist: an external drive
	// may simply be unmounted right now.
	if c.Libraries.Movies != "" {
		c.Libraries.Movies = filepath.Clean(c.Libraries.Movies)
	}
	if c.Libraries.TV != "" {
		c.Libraries.TV = filepath.Clean(c.Libraries.TV)
	}
	switch c.Log.Format {
	case "text", "json":
	default:
		return fmt.Errorf("log.format %q must be text or json", c.Log.Format)
	}
	if c.Scan.Workers < 1 {
		return fmt.Errorf("scan.workers must be at least 1")
	}
	if c.Scan.Interval < time.Minute {
		return fmt.Errorf("scan.interval must be at least 1m")
	}
	if c.Scan.StableFor < 0 {
		return fmt.Errorf("scan.stable_for must not be negative")
	}
	if c.Network.TrustedProxyPrefixes, err = netguard.Parse(c.Network.TrustedProxies); err != nil {
		return fmt.Errorf("network.trusted_proxies: %w", err)
	}
	if c.Network.AdminPrefixes, c.Network.AdminExcluded, err = netguard.ParseRules(c.Network.AdminNetworks); err != nil {
		return fmt.Errorf("network.admin_networks: %w", err)
	}
	if len(c.Network.AdminPrefixes) == 0 {
		return fmt.Errorf("network.admin_networks is empty, which would lock every admin out; use %q to allow any address", netguard.Any)
	}
	ts := &c.Auth.Turnstile
	ts.SiteKey, ts.SecretKey = strings.TrimSpace(ts.SiteKey), strings.TrimSpace(ts.SecretKey)
	if (ts.SiteKey == "") != (ts.SecretKey == "") {
		return fmt.Errorf("auth.turnstile needs both site_key and secret_key, or neither")
	}
	switch c.Transcode.HWAccel {
	case "auto", "software", "videotoolbox", "nvenc", "qsv", "vaapi", "amf":
	default:
		return fmt.Errorf("transcode.hwaccel %q must be auto, software, videotoolbox, nvenc, qsv, vaapi or amf", c.Transcode.HWAccel)
	}
	if err := hwdevice.Validate(c.Transcode.HWAccel, c.Transcode.HWDevice); err != nil {
		return fmt.Errorf("transcode.device: %w", err)
	}
	if c.Transcode.MaxCacheBytes < 0 || c.Transcode.MaxSessionBytes < 0 || c.Transcode.MinFreeBytes < 0 {
		return errors.New("transcode cache and free-space byte limits must not be negative")
	}
	if c.Transcode.EventAhead < 8*time.Second || c.Transcode.EventAhead > 4*time.Minute || c.Transcode.EventKeepBehind < 0 || c.Transcode.EventKeepBehind > 4*time.Minute {
		return errors.New("transcode.event_ahead must be 8s–4m and event_keep_behind must be 0–4m")
	}

	if err := c.Cast.finalize(); err != nil {
		return err
	}
	if c.Updates.Enabled {
		c.Updates.URL = strings.TrimSpace(c.Updates.URL)
		if u, err := url.Parse(c.Updates.URL); err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			return fmt.Errorf("updates.url %q must be an address such as %s", c.Updates.URL, DefaultUpdatesURL)
		}
	}
	c.FFmpeg.FFmpeg = ResolveTool(c.FFmpeg.FFmpeg)
	c.FFmpeg.FFprobe = ResolveTool(c.FFmpeg.FFprobe)
	return nil
}

func (c *CastConfig) finalize() error {
	c.AppID = strings.TrimSpace(c.AppID)
	if c.URL = strings.TrimRight(strings.TrimSpace(c.URL), "/"); c.URL != "" {
		if u, err := url.Parse(c.URL); err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.Path != "" || u.RawQuery != "" {
			return fmt.Errorf("cast.url %q must be an address such as https://media.example.com", c.URL)
		}
	}
	for i, o := range c.ReceiverOrigins {
		o = strings.TrimRight(strings.TrimSpace(o), "/")
		if u, err := url.Parse(o); err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.Path != "" || u.RawQuery != "" {
			return fmt.Errorf("cast.receiver_origins: %q must be an origin such as https://cast.watchmy.media", o)
		}
		c.ReceiverOrigins[i] = o
	}
	return nil
}

// ResolveTool turns a bare tool name into the path of that tool next
// to the server binary when one is there, so a release tarball or app bundle
// that ships ffmpeg works with no configuration. Anything else, including a
// name that is not found there, is returned as is for exec to find on PATH.
func ResolveTool(name string) string {
	if name == "" || strings.ContainsRune(name, os.PathSeparator) || strings.ContainsRune(name, '/') {
		return name
	}
	exe, err := os.Executable()
	if err != nil {
		return name
	}
	for _, candidate := range []string{name, name + ".exe"} {
		p := filepath.Join(filepath.Dir(exe), candidate)
		if st, err := os.Stat(p); err == nil && !st.IsDir() && (runtime.GOOS == "windows" || st.Mode()&0o111 != 0) {
			return p
		}
	}
	return name
}

// DBPath is the SQLite database file.
func (c Config) DBPath() string { return filepath.Join(c.DataDir, dbFileName) }
