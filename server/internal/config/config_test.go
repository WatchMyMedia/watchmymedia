package config

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestLoadDefaultsAndEnv(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("WATCHMYMEDIA_DATA_DIR", dir)
	t.Setenv("WATCHMYMEDIA_PORT", "9090")
	t.Setenv("WATCHMYMEDIA_MOVIES_PATH", "/media/Movies/")
	t.Setenv("WATCHMYMEDIA_HOST", "127.0.0.1")

	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Port != 9090 || cfg.Host != "127.0.0.1" {
		t.Errorf("listen = %q:%d, want 127.0.0.1:9090", cfg.Host, cfg.Port)
	}
	if n := len(cfg.Network.AdminPrefixes); n == 0 || len(cfg.Network.TrustedProxyPrefixes) != 0 {
		t.Errorf("network defaults = %+v", cfg.Network)
	}
	if got := Default().Host; got != "" {
		t.Errorf("default host = %q, want all interfaces", got)
	}
	if cfg.Libraries.Movies != filepath.FromSlash("/media/Movies") {
		t.Errorf("movies = %q, want cleaned path", cfg.Libraries.Movies)
	}
	if cfg.Transcode.Dir != filepath.Join(dir, "transcode") {
		t.Errorf("transcode dir = %q", cfg.Transcode.Dir)
	}
	if cfg.DBPath() != filepath.Join(dir, "watchmymedia.db") {
		t.Errorf("db path = %q", cfg.DBPath())
	}
	if got := Default().Port; got != 27587 {
		t.Errorf("default port = %d, want 27587", got)
	}
	if cfg.Scan.Interval != 15*time.Minute || cfg.Scan.Workers != 4 || cfg.FFmpeg.FFprobe != "ffprobe" {
		t.Errorf("scan/ffmpeg defaults = %+v %+v", cfg.Scan, cfg.FFmpeg)
	}
}

func TestAdaptiveHLSRequiresExplicitOperatorOptIn(t *testing.T) {
	if Default().Transcode.AdaptiveHLS {
		t.Fatal("adaptive HLS enabled by default")
	}
	t.Setenv("WATCHMYMEDIA_DATA_DIR", t.TempDir())
	t.Setenv("WATCHMYMEDIA_TRANSCODE_ADAPTIVE_HLS", "true")
	cfg, err := Load()
	if err != nil || !cfg.Transcode.AdaptiveHLS {
		t.Fatalf("experimental opt-in: %+v %v", cfg, err)
	}
	t.Setenv("WATCHMYMEDIA_TRANSCODE_ADAPTIVE_HLS", "invalid")
	if _, err := Load(); err == nil {
		t.Fatal("invalid opt-in accepted")
	}
}

func TestPlaybackResourceConfig(t *testing.T) {
	t.Setenv("WATCHMYMEDIA_DATA_DIR", t.TempDir())
	t.Setenv("WATCHMYMEDIA_TRANSCODE_MAX_CACHE_BYTES", "1048576")
	t.Setenv("WATCHMYMEDIA_TRANSCODE_MAX_SESSION_BYTES", "524288")
	t.Setenv("WATCHMYMEDIA_TRANSCODE_MIN_FREE_BYTES", "4096")
	t.Setenv("WATCHMYMEDIA_TRANSCODE_EVENT_AHEAD", "24s")
	t.Setenv("WATCHMYMEDIA_TRANSCODE_EVENT_KEEP_BEHIND", "12s")
	c, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if c.Transcode.MaxCacheBytes != 1048576 || c.Transcode.MaxSessionBytes != 524288 || c.Transcode.MinFreeBytes != 4096 || c.Transcode.EventAhead != 24*time.Second || c.Transcode.EventKeepBehind != 12*time.Second {
		t.Fatalf("resource settings: %+v", c.Transcode)
	}
	t.Setenv("WATCHMYMEDIA_TRANSCODE_MAX_SESSION_BYTES", "-1")
	if _, err := Load(); err == nil {
		t.Fatal("negative byte budget accepted")
	}
	t.Setenv("WATCHMYMEDIA_TRANSCODE_MAX_SESSION_BYTES", "524288")
	t.Setenv("WATCHMYMEDIA_TRANSCODE_EVENT_AHEAD", "1s")
	if _, err := Load(); err == nil {
		t.Fatal("event window below minimum accepted")
	}
}

func TestLiveSourceSharingRolloutDefaultsOff(t *testing.T) {
	if Default().LiveTV.ShareSources {
		t.Fatal("shared tuners must remain opt-in during L0")
	}
	t.Setenv("WATCHMYMEDIA_DATA_DIR", t.TempDir())
	t.Setenv("WATCHMYMEDIA_LIVE_TV_SHARE_SOURCES", "true")
	cfg, err := Load()
	if err != nil || !cfg.LiveTV.ShareSources {
		t.Fatalf("rollout override: enabled=%t err=%v", cfg.LiveTV.ShareSources, err)
	}
	t.Setenv("WATCHMYMEDIA_LIVE_TV_SHARE_SOURCES", "not-a-boolean")
	if _, err := Load(); err == nil {
		t.Fatal("invalid rollout override was accepted")
	}
}

// A tool next to the server binary wins over a bare name; names with a
// directory and names not found there are left for PATH.
func TestFFmpegBesideExecutable(t *testing.T) {
	exe, err := os.Executable()
	if err != nil {
		t.Skip(err)
	}
	beside := filepath.Join(filepath.Dir(exe), "ffmpeg")
	if err := os.WriteFile(beside, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Skipf("cannot write next to the test binary: %v", err)
	}
	t.Cleanup(func() { _ = os.Remove(beside) })
	t.Setenv("WATCHMYMEDIA_DATA_DIR", t.TempDir())
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.FFmpeg.FFmpeg != beside {
		t.Errorf("ffmpeg = %q, want %q", cfg.FFmpeg.FFmpeg, beside)
	}
	if cfg.FFmpeg.FFprobe != "ffprobe" {
		t.Errorf("ffprobe = %q, want bare name (none beside the binary)", cfg.FFmpeg.FFprobe)
	}
	t.Setenv("WATCHMYMEDIA_FFMPEG_PATH", "/opt/bin/ffmpeg")
	if cfg, err = Load(); err != nil || cfg.FFmpeg.FFmpeg != "/opt/bin/ffmpeg" {
		t.Errorf("explicit path: %q %v", cfg.FFmpeg.FFmpeg, err)
	}
}

func TestLoadScanOverrides(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("WATCHMYMEDIA_DATA_DIR", dir)
	t.Setenv("WATCHMYMEDIA_SCAN_INTERVAL", "30m")
	t.Setenv("WATCHMYMEDIA_SCAN_WORKERS", "2")
	yaml := "scan:\n  stable_for: 3s\nffmpeg:\n  ffprobe: /opt/bin/ffprobe\n"
	if err := os.WriteFile(filepath.Join(dir, "config.yaml"), []byte(yaml), 0o644); err != nil {
		t.Fatal(err)
	}
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Scan.Interval != 30*time.Minute || cfg.Scan.Workers != 2 || cfg.Scan.StableFor != 3*time.Second {
		t.Errorf("scan = %+v", cfg.Scan)
	}
	if cfg.FFmpeg.FFprobe != "/opt/bin/ffprobe" {
		t.Errorf("ffprobe = %q", cfg.FFmpeg.FFprobe)
	}
}

func TestLoadConfigFileThenEnvOverrides(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("WATCHMYMEDIA_DATA_DIR", dir)
	t.Setenv("WATCHMYMEDIA_TV_PATH", "/from/env")
	yaml := "port: 7000\nlibraries:\n  movies: /from/file/movies\n  tv: /from/file/tv\nlog:\n  format: json\n"
	if err := os.WriteFile(filepath.Join(dir, "config.yaml"), []byte(yaml), 0o644); err != nil {
		t.Fatal(err)
	}

	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Port != 7000 {
		t.Errorf("port = %d, want 7000 from file", cfg.Port)
	}
	if cfg.Libraries.Movies != filepath.FromSlash("/from/file/movies") {
		t.Errorf("movies = %q", cfg.Libraries.Movies)
	}
	if cfg.Libraries.TV != filepath.FromSlash("/from/env") {
		t.Errorf("tv = %q, env should win over file", cfg.Libraries.TV)
	}
	if cfg.Log.Format != "json" {
		t.Errorf("log format = %q", cfg.Log.Format)
	}
}

func TestLoadRejectsBadPort(t *testing.T) {
	t.Setenv("WATCHMYMEDIA_DATA_DIR", t.TempDir())
	t.Setenv("WATCHMYMEDIA_PORT", "70000")
	if _, err := Load(); err == nil {
		t.Fatal("expected error for out-of-range port")
	}
}

func TestLoadNetworkEnv(t *testing.T) {
	t.Setenv("WATCHMYMEDIA_DATA_DIR", t.TempDir())
	t.Setenv("WATCHMYMEDIA_TRUSTED_PROXIES", "172.31.250.0/24, 127.0.0.1")
	t.Setenv("WATCHMYMEDIA_ADMIN_NETWORKS", "192.168.1.0/24")
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.Network.TrustedProxyPrefixes) != 2 || len(cfg.Network.AdminPrefixes) != 1 {
		t.Errorf("network = %+v", cfg.Network)
	}
	t.Setenv("WATCHMYMEDIA_ADMIN_NETWORKS", "local,!192.168.65.0/24")
	if cfg, err := Load(); err != nil || len(cfg.Network.AdminExcluded) != 1 {
		t.Errorf("exclusion: %v %+v", err, cfg.Network)
	}
	t.Setenv("WATCHMYMEDIA_ADMIN_NETWORKS", "!192.168.65.0/24")
	if _, err := Load(); err == nil {
		t.Error("admin networks with only exclusions accepted")
	}
	t.Setenv("WATCHMYMEDIA_ADMIN_NETWORKS", "")
	if _, err := Load(); err == nil {
		t.Error("empty admin networks accepted")
	}
	t.Setenv("WATCHMYMEDIA_ADMIN_NETWORKS", "local")
	t.Setenv("WATCHMYMEDIA_TRUSTED_PROXIES", "proxy.local")
	if _, err := Load(); err == nil {
		t.Error("hostname accepted as a trusted proxy")
	}
}

func TestLoadTurnstile(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("WATCHMYMEDIA_DATA_DIR", dir)
	yaml := "auth:\n  turnstile:\n    site_key: 1x00000000000000000000AA\n    secret_key: from-file\n"
	if err := os.WriteFile(filepath.Join(dir, "config.yaml"), []byte(yaml), 0o644); err != nil {
		t.Fatal(err)
	}
	t.Setenv("WATCHMYMEDIA_AUTH_TURNSTILE_SECRET_KEY", " 1x0000000000000000000000000000000AA ")
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if ts := cfg.Auth.Turnstile; ts.SiteKey != "1x00000000000000000000AA" || ts.SecretKey != "1x0000000000000000000000000000000AA" || ts.Disabled {
		t.Errorf("turnstile = %+v", ts)
	}
	t.Setenv("WATCHMYMEDIA_AUTH_TURNSTILE_DISABLED", "true")
	if cfg, err := Load(); err != nil || !cfg.Auth.Turnstile.Disabled {
		t.Errorf("disabled: %v %+v", err, cfg.Auth.Turnstile)
	}
	t.Setenv("WATCHMYMEDIA_AUTH_TURNSTILE_SECRET_KEY", "")
	if _, err := Load(); err == nil {
		t.Error("site key without a secret accepted")
	}
}

func TestLoadUpdates(t *testing.T) {
	t.Setenv("WATCHMYMEDIA_DATA_DIR", t.TempDir())
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if u := cfg.Updates; !u.Enabled || u.URL != DefaultUpdatesURL || u.Install != "" {
		t.Errorf("defaults = %+v", u)
	}
	t.Setenv("WATCHMYMEDIA_UPDATES_URL", " http://127.0.0.1:8080/releases.json ")
	t.Setenv("WATCHMYMEDIA_INSTALL", "docker")
	if cfg, err = Load(); err != nil {
		t.Fatal(err)
	}
	if u := cfg.Updates; u.URL != "http://127.0.0.1:8080/releases.json" || u.Install != "docker" || u.Host != "" {
		t.Errorf("from env = %+v", u)
	}
	// Unraid's Docker page sets HOST_OS on every container it starts.
	t.Setenv("HOST_OS", "Unraid")
	if cfg, err = Load(); err != nil {
		t.Fatal(err)
	}
	if cfg.Updates.Host != HostUnraid {
		t.Errorf("host from HOST_OS=Unraid: %q", cfg.Updates.Host)
	}
	t.Setenv("HOST_OS", "Synology")
	if cfg, err = Load(); err != nil {
		t.Fatal(err)
	}
	if cfg.Updates.Host != "" {
		t.Errorf("unknown HOST_OS kept: %q", cfg.Updates.Host)
	}
	t.Setenv("HOST_OS", "")
	t.Setenv("WATCHMYMEDIA_UPDATES_URL", "downloads.watchmy.media/releases.json")
	if _, err := Load(); err == nil {
		t.Error("an address without a scheme was accepted")
	}
	// Off, the address is not used, so it is not checked either.
	t.Setenv("WATCHMYMEDIA_UPDATES_ENABLED", "false")
	if cfg, err = Load(); err != nil || cfg.Updates.Enabled {
		t.Errorf("disabled: %+v, %v", cfg.Updates, err)
	}
}

// Lists from outside services are on unless lists.enabled or
// WATCHMYMEDIA_LISTS turns them off.
func TestLoadLists(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("WATCHMYMEDIA_DATA_DIR", dir)
	if cfg, err := Load(); err != nil || !cfg.Lists.Enabled {
		t.Fatalf("default = %+v, %v", cfg.Lists, err)
	}
	if err := os.WriteFile(filepath.Join(dir, "config.yaml"), []byte("lists:\n  enabled: false\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if cfg, err := Load(); err != nil || cfg.Lists.Enabled {
		t.Errorf("from config.yaml = %+v, %v", cfg.Lists, err)
	}
	t.Setenv("WATCHMYMEDIA_LISTS", "true")
	if cfg, err := Load(); err != nil || !cfg.Lists.Enabled {
		t.Errorf("from env = %+v, %v", cfg.Lists, err)
	}
}

func TestLoadCast(t *testing.T) {
	t.Setenv("WATCHMYMEDIA_DATA_DIR", t.TempDir())
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if c := cfg.Cast; !c.Enabled || c.AppID != DefaultCastAppID || len(c.ReceiverOrigins) != 1 || c.ReceiverOrigins[0] != DefaultCastReceiverOrigin {
		t.Errorf("defaults = %+v", c)
	}
	t.Setenv("WATCHMYMEDIA_CAST_APP_ID", " ABCD1234 ")
	t.Setenv("WATCHMYMEDIA_CAST_URL", "https://media.example.com/")
	t.Setenv("WATCHMYMEDIA_CAST_RECEIVER_ORIGINS", "https://cast.watchmy.media, http://192.168.1.20:5173/")
	t.Setenv("WATCHMYMEDIA_CAST_ENABLED", "false")
	cfg, err = Load()
	if err != nil {
		t.Fatal(err)
	}
	if c := cfg.Cast; c.Enabled || c.AppID != "ABCD1234" || c.URL != "https://media.example.com" || len(c.ReceiverOrigins) != 2 || c.ReceiverOrigins[1] != "http://192.168.1.20:5173" {
		t.Errorf("from env = %+v", c)
	}
	for _, bad := range []string{"media.example.com", "https://media.example.com/watch", "ftp://x"} {
		t.Setenv("WATCHMYMEDIA_CAST_URL", bad)
		if _, err := Load(); err == nil {
			t.Errorf("cast url %q accepted", bad)
		}
	}
	t.Setenv("WATCHMYMEDIA_CAST_URL", "")
	t.Setenv("WATCHMYMEDIA_CAST_RECEIVER_ORIGINS", "https://cast.watchmy.media/receiver")
	if _, err := Load(); err == nil {
		t.Error("receiver origin with a path accepted")
	}
}

func TestHardwareDeviceConfiguration(t *testing.T) {
	t.Setenv("WATCHMYMEDIA_DATA_DIR", t.TempDir())
	t.Setenv("WATCHMYMEDIA_TRANSCODE_HWACCEL", "nvenc")
	t.Setenv("WATCHMYMEDIA_TRANSCODE_DEVICE", "0")
	c, err := Load()
	if err != nil || c.Transcode.HWDevice == nil || *c.Transcode.HWDevice != 0 {
		t.Fatalf("device=%v err=%v", c, err)
	}
	t.Setenv("WATCHMYMEDIA_TRANSCODE_HWACCEL", "auto")
	if _, err = Load(); err == nil {
		t.Fatal("auto accepted a backend-specific adapter")
	}
	t.Setenv("WATCHMYMEDIA_TRANSCODE_DEVICE", "not-an-index")
	if _, err = Load(); err == nil {
		t.Fatal("invalid device was ignored")
	}
}

func TestRemoteAdminConfig(t *testing.T) {
	t.Setenv("WATCHMYMEDIA_DATA_DIR", t.TempDir())
	if Default().Network.RemoteAdmin != nil {
		t.Fatal("default must defer to stored setting")
	}
	for _, value := range []string{"true", "false", "invalid"} {
		t.Run(value, func(t *testing.T) {
			t.Setenv("WATCHMYMEDIA_REMOTE_ADMIN", value)
			cfg, err := Load()
			if value == "invalid" {
				if err == nil {
					t.Fatal("invalid bool accepted")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if cfg.Network.RemoteAdmin == nil || *cfg.Network.RemoteAdmin != (value == "true") {
				t.Fatal("lost explicit override")
			}
		})
	}
}

func TestRemoteUploadConfig(t *testing.T) {
	t.Setenv("WATCHMYMEDIA_DATA_DIR", t.TempDir())
	if c := Default(); c.Network.RemoteUploadBps != 0 || c.Network.EnforceUpload {
		t.Fatalf("upload must start unlimited and observe-only: %+v", c.Network)
	}
	t.Setenv("WATCHMYMEDIA_REMOTE_UPLOAD_BPS", "40000000")
	t.Setenv("WATCHMYMEDIA_ENFORCE_UPLOAD", "true")
	c, err := Load()
	if err != nil || c.Network.RemoteUploadBps != 40_000_000 || !c.Network.EnforceUpload {
		t.Fatalf("upload configuration = %+v, %v", c.Network, err)
	}
	t.Setenv("WATCHMYMEDIA_REMOTE_UPLOAD_BPS", "-1")
	if _, err := Load(); err == nil {
		t.Fatal("negative upload ceiling accepted")
	}
}
