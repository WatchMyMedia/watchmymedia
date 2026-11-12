// Package settings persists values changed from the UI. A stored value wins
// over config.yaml and the environment, which only seed first-run defaults.
package settings

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"

	"watchmymedia/server/internal/db"
)

const (
	KeyTMDBAPIKey = "tmdb.api_key" //nolint:gosec // G101: a setting's name, not its value
	KeyHWAccel    = "transcode.hwaccel"
	KeyHWDevice   = "transcode.device"
	KeyBackupKeep = "backup.keep"
	// KeyScanInterval is the minutes between scheduled scans.
	KeyScanInterval = "scan.interval_minutes"
	// KeyMediaTokenKey signs media tokens, so they survive restarts.
	KeyMediaTokenKey = "auth.media_token_key" //nolint:gosec // G101: a setting's name, not its value
	// Activity: plays shorter than this many seconds are not kept; resumed
	// plays are grouped ("1"/"0"); days of history to keep (0 = forever);
	// whether trimming also drops the daily totals; whether everyone may
	// see what is playing now.
	KeyActivityMinPlay   = "activity.min_play_seconds"
	KeyActivityGroup     = "activity.group"
	KeyActivityRetention = "activity.retention_days"
	KeyActivityTrimStats = "activity.trim_stats"
	KeyActivityHousehold = "activity.household"
	// Cloudflare Turnstile on sign-in: on while both keys are set.
	KeyRemoteAdmin        = "network.remote_admin"
	KeyUpload             = "network.upload"
	KeyTurnstileSiteKey   = "auth.turnstile.site_key"
	KeyTurnstileSecretKey = "auth.turnstile.secret_key" //nolint:gosec // G101: a setting's name, not its value
	// Online subtitles: the OpenSubtitles account downloads count against,
	// the languages searched (comma separated), and who may download
	// ("everyone" or "admins").
	KeyOpenSubtitlesUsername = "subtitles.opensubtitles.username"
	KeyOpenSubtitlesPassword = "subtitles.opensubtitles.password" //nolint:gosec // G101: a setting's name, not its value
	KeySubtitleLanguages     = "subtitles.languages"
	KeySubtitleDownloads     = "subtitles.downloads"
	// KeyTrickplayEnabled turns seek thumbnails off ("0"); they are on
	// unless it is set.
	KeyTrickplayEnabled = "trickplay.enabled"
	// KeyTrailersYouTube turns movies' TMDB trailers, which play from
	// YouTube, off ("0"); they are on unless it is set.
	KeyTrailersYouTube = "trailers.youtube"
	// KeyListsMDBListKey is the admin's MDBList API key, for lists from
	// MDBList (PLAN-ROWS.md 3).
	KeyListsMDBListKey = "lists.mdblist.api_key" //nolint:gosec // G101: a setting's name, not its value
	// KeyLibrariesSeeded ("1") is set once the server has had a library,
	// after which the configured library folders are never added again.
	KeyLibrariesSeeded = "libraries.seeded"
)

type Store struct{ q *db.Queries }

func New(q *db.Queries) *Store { return &Store{q: q} }

// Get returns the stored value, or def when none is stored.
func (s *Store) Get(ctx context.Context, key, def string) (string, error) {
	v, err := s.q.GetSetting(ctx, key)
	if errors.Is(err, sql.ErrNoRows) {
		return def, nil
	}
	if err != nil {
		return def, err
	}
	return v, nil
}

// UploadPreference is one atomic stored override for the remote upload
// policy. A missing value uses config.yaml/environment defaults.
type UploadPreference struct {
	Bps     int64 `json:"bps"`
	Enforce bool  `json:"enforce"`
}

func (p UploadPreference) Validate() error {
	if p.Bps < 0 || p.Bps > 1_000_000_000_000 {
		return fmt.Errorf("remote upload bps must be between 0 and 1000000000000")
	}
	return nil
}

func (s *Store) Upload(ctx context.Context, def UploadPreference) (UploadPreference, error) {
	if err := def.Validate(); err != nil {
		return def, err
	}
	raw, err := s.Get(ctx, KeyUpload, "")
	if err != nil || raw == "" {
		return def, err
	}
	var p UploadPreference
	if err := json.Unmarshal([]byte(raw), &p); err != nil {
		return def, fmt.Errorf("stored upload setting: %w", err)
	}
	if err := p.Validate(); err != nil {
		return def, err
	}
	return p, nil
}

func (s *Store) SetUpload(ctx context.Context, p UploadPreference) error {
	if err := p.Validate(); err != nil {
		return err
	}
	b, err := json.Marshal(p)
	if err != nil {
		return err
	}
	return s.Set(ctx, KeyUpload, string(b))
}

func (s *Store) Set(ctx context.Context, key, value string) error {
	if value == "" {
		return s.q.DeleteSetting(ctx, key)
	}
	return s.q.SetSetting(ctx, db.SetSettingParams{Key: key, Value: value})
}

// IsSet reports whether a value is stored (as opposed to defaulted).
func (s *Store) IsSet(ctx context.Context, key string) bool {
	_, err := s.q.GetSetting(ctx, key)
	return err == nil
}
