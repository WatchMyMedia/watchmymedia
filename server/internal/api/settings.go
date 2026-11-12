package api

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/danielgtaylor/huma/v2"
	"github.com/go-chi/chi/v5"
	"golang.org/x/text/unicode/norm"

	"watchmymedia/server/internal/backup"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/hwdevice"
	"watchmymedia/server/internal/library"
	"watchmymedia/server/internal/lists"
	"watchmymedia/server/internal/netguard"
	"watchmymedia/server/internal/opensubtitles"
	"watchmymedia/server/internal/parser"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/settings"
	"watchmymedia/server/internal/subtitles"
	"watchmymedia/server/internal/tmdb"
	"watchmymedia/server/internal/trickplay"
	"watchmymedia/server/internal/turnstile"
	"watchmymedia/server/internal/upload"
)

// Settings is what the settings page shows and edits (admin only).
type Settings struct {
	TMDBConfigured bool   `json:"tmdbConfigured"`
	TMDBKeyHint    string `json:"tmdbKeyHint,omitempty" doc:"Last four characters of the stored key."`
	HWAccel        string `json:"hwaccel" enum:"auto,software,videotoolbox,nvenc,qsv,vaapi,amf"`
	// HWDevice is the requested adapter, nil for the backend default.
	HWDevice            *int                          `json:"hwDevice,omitempty" required:"false" doc:"Requested adapter: a CUDA index for NVENC, the render node number for QSV and VAAPI on Linux (N is /dev/dri/renderD128+N), a DirectX index for QSV/AMF on Windows; absent means backend default."`
	HardwareDiagnostics []playback.HardwareDiagnostic `json:"hardwareDiagnostics,omitempty" required:"false" doc:"Bounded results of transcoder probes, including failures. Admin-only; synthetic inputs, never library files."`
	HardwareAdapters    *hwdevice.Inventory           `json:"hardwareAdapters,omitempty" required:"false" doc:"Live Windows CUDA/DirectX adapter names and backend-specific ordinals. Admin-only; discovery does not prove encoding support. Absent on older servers or unsupported platforms."`
	// Capabilities is what ffmpeg can do with the current preference.
	Capabilities      *playback.Capabilities   `json:"capabilities,omitempty"`
	BackupKeep        int                      `json:"backupKeep" doc:"Nightly backups to keep; 0 disables them."`
	ScanIntervalMin   int                      `json:"scanIntervalMinutes" doc:"Minutes between scheduled scans of every library, which find what the filesystem watcher misses."`
	DataDir           string                   `json:"dataDir"`
	Backups           []backup.Info            `json:"backups" nullable:"false"`
	Activity          ActivitySettings         `json:"activity"`
	ServerName        string                   `json:"serverName" doc:"What TV and phone apps call this server."`
	ServerNameDefault string                   `json:"serverNameDefault" doc:"The name used when none is set: the host name."`
	Turnstile         TurnstileSettings        `json:"turnstile"`
	Cast              CastSettings             `json:"cast"`
	Subtitles         subtitles.SubtitleStatus `json:"subtitles"`
	Trickplay         *trickplay.Status        `json:"trickplay,omitempty" required:"false" doc:"Seek thumbnails: whether they are made, how far they have got and what they take on disk."`
	RemoteAdmin       *RemoteAdminSettings     `json:"remoteAdmin,omitempty" required:"false"`
	Upload            *upload.Snapshot         `json:"upload,omitempty" required:"false" doc:"Remote media ceiling and live delivery accounting. The configured, reserved and delivered rates have different meanings."`
	Storage           *PlaybackCacheLoad       `json:"storage,omitempty" required:"false" doc:"Owned temporary playback storage usage and configured limits. Library files and recordings are excluded."`
	TrailersYouTube   bool                     `json:"trailersYouTube" required:"false" doc:"Movie pages offer TMDB's trailers, which play from YouTube (on by default). Absent on older servers."`
	// MDBList's key for lists (PLAN-ROWS.md §3): write-only, like the TMDB key.
	ListsMDBListConfigured bool   `json:"listsMdblistConfigured" required:"false" doc:"An MDBList key is stored, so lists can come from MDBList (API level 22)."`
	ListsMDBListKeyHint    string `json:"listsMdblistKeyHint,omitempty" required:"false" doc:"Last four characters of the stored MDBList key."`
}

// SubtitlesUpdate changes online subtitles. Username and password go
// together: the account is checked with OpenSubtitles before it is saved,
// and an empty username signs out.
type SubtitlesUpdate struct {
	Username  *string  `json:"username,omitempty" maxLength:"100"`
	Password  string   `json:"password,omitempty" maxLength:"200"`
	Languages []string `json:"languages,omitempty" maxItems:"10" doc:"Languages to search, in order (ISO 639-1, pt-br style for regions)."`
	Downloads *string  `json:"downloads,omitempty" enum:"everyone,admins"`
}

// CastSettings is how casting is set up; it comes from config.yaml or the
// environment (cast.*), so the page only shows it.
type CastSettings struct {
	Enabled         bool     `json:"enabled" doc:"cast.enabled: the server issues cast tokens."`
	AppID           string   `json:"appId,omitempty" doc:"cast.app_id: the receiver the web UI launches; empty shows no Cast button in the browser."`
	URL             string   `json:"url,omitempty" doc:"cast.url: offered to TVs first."`
	ReceiverOrigins []string `json:"receiverOrigins" nullable:"false"`
	URLs            []string `json:"urls" nullable:"false" doc:"The addresses a cast from this browser would offer the TV, best first."`
}

type RemoteAdminSettings struct {
	Enabled    bool `json:"enabled"`
	FromConfig bool `json:"fromConfig"`
}

// TurnstileSettings is the Cloudflare Turnstile bot check on sign-in.
type TurnstileSettings struct {
	Enabled       bool   `json:"enabled" doc:"Password sign-in and setup need the check now."`
	SiteKey       string `json:"siteKey,omitempty"`
	SecretKeyHint string `json:"secretKeyHint,omitempty" doc:"Last four characters of the secret key."`
	FromConfig    bool   `json:"fromConfig" doc:"The keys come from config.yaml or the environment (auth.turnstile) and cannot be changed here."`
	Disabled      bool   `json:"disabled" doc:"auth.turnstile.disabled (WATCHMYMEDIA_AUTH_TURNSTILE_DISABLED) turns it off whatever keys are set."`
}

// TurnstileUpdate turns Turnstile on with a pair of keys, or off.
type TurnstileUpdate struct {
	SiteKey   string `json:"siteKey" maxLength:"100" doc:"The widget's site key; empty turns Turnstile off and forgets both keys."`
	SecretKey string `json:"secretKey,omitempty" maxLength:"100" doc:"The widget's secret key, checked with Cloudflare before it is saved. Omit to keep the saved one."`
}

type SettingsOutput struct{ Body Settings }

type UploadUpdate struct {
	Bps                    *int64  `json:"bps,omitempty" minimum:"0" maximum:"1000000000000" doc:"Aggregate remote media upload ceiling in bits per second; 0 means unlimited. Omit to keep the current value."`
	Enforce                *bool   `json:"enforce,omitempty" doc:"Apply the ceiling to remote delivery and starts. Defaults to false until an operator enables it."`
	ExpectedPolicyRevision *uint64 `json:"expectedPolicyRevision,omitempty" required:"false" doc:"Reject a stale edit with 409; read the current revision from Settings.upload.policyRevision."`
}

type UpdateSettingsInput struct {
	Body struct {
		TMDBAPIKey *string `json:"tmdbApiKey,omitempty" doc:"New key; empty string clears it; omit to keep."`
		HWDevice   *int    `json:"hwDevice,omitempty" minimum:"-1" maximum:"2147483647" doc:"Adapter index for the explicit hardware backend; -1 resets to its default. Omit to keep, except a backend change clears the old index."`
		HWAccel    *string `json:"hwaccel,omitempty" enum:"auto,software,videotoolbox,nvenc,qsv,vaapi,amf"`
		BackupKeep *int    `json:"backupKeep,omitempty" minimum:"0" maximum:"365"`
		// ScanIntervalMin takes effect at once: the next scheduled scan is
		// that long from now.
		ScanIntervalMin *int `json:"scanIntervalMinutes,omitempty" minimum:"5" maximum:"1440"`
		// Activity replaces the whole Activity section when present.
		Activity    *ActivitySettings `json:"activity,omitempty"`
		ServerName  *string           `json:"serverName,omitempty" maxLength:"63" doc:"Empty string goes back to the host name."`
		RemoteAdmin *bool             `json:"remoteAdmin,omitempty"`
		Upload      *UploadUpdate     `json:"upload,omitempty" required:"false"`
		Turnstile   *TurnstileUpdate  `json:"turnstile,omitempty"`
		Subtitles   *SubtitlesUpdate  `json:"subtitles,omitempty"`
		// TrickplayEnabled turns seek thumbnails on, which starts making
		// them now, or off, which stops and keeps those already made.
		TrickplayEnabled *bool `json:"trickplayEnabled,omitempty" doc:"Make seek thumbnails for every file (on by default)."`
		// TrailersYouTube turns YouTube trailers on or off (PLAN-TRAILERS.md
		// §4.5). Trailer files in the library are not affected.
		TrailersYouTube *bool `json:"trailersYouTube,omitempty" doc:"List movies' TMDB trailers, which play from YouTube, and let the web app show YouTube's player (on by default)."`
		// ListsMDBListKey is checked against MDBList before it is stored.
		ListsMDBListKey *string `json:"listsMdblistKey,omitempty" required:"false" maxLength:"200" doc:"New MDBList API key, checked with MDBList before it is kept; empty string clears it; omit to keep (API level 22)."`
	}
}

type CheckTurnstileInput struct {
	Body struct {
		Token string `json:"token" minLength:"1" maxLength:"2048"`
	}
}

type TestTMDBInput struct {
	Body struct {
		APIKey string `json:"apiKey" minLength:"1"`
	}
}

type TestResult struct {
	OK      bool   `json:"ok"`
	Message string `json:"message"`
}

type TestOutput struct{ Body TestResult }

type PathInput struct {
	Body struct {
		Path string `json:"path" minLength:"1"`
	}
}

type PathCheck struct {
	Path       string   `json:"path"`
	Exists     bool     `json:"exists"`
	Readable   bool     `json:"readable"`
	VideoFiles int      `json:"videoFiles" doc:"Video files found down to three levels (Show/Season 1/episode)."`
	Sample     []string `json:"sample" nullable:"false" doc:"A few entry names, to confirm it is the right folder."`
	Truncated  bool     `json:"truncated,omitempty" required:"false" doc:"The count stopped after the first thousand folders; the folder holds more."`
}

type PathCheckOutput struct{ Body PathCheck }

type BrowseInput struct {
	Path string `query:"path" required:"false" doc:"Folder to list; empty for the filesystem roots."`
}

type BrowseEntry struct {
	Name string `json:"name"`
	Path string `json:"path"`
}

type BrowseResult struct {
	Path    string        `json:"path"`
	Parent  string        `json:"parent,omitempty"`
	Entries []BrowseEntry `json:"entries" nullable:"false"`
}

type BrowseOutput struct{ Body BrowseResult }

type CreateLibraryInput struct {
	Body struct {
		Type string `json:"type" enum:"movie,tv"`
		Name string `json:"name" minLength:"1" maxLength:"64"`
		Path string `json:"path" minLength:"1"`
	}
}

type UpdateLibraryInput struct {
	ID   int64 `path:"id"`
	Body struct {
		Name string `json:"name" minLength:"1" maxLength:"64"`
		Path string `json:"path" minLength:"1"`
	}
}

type LibraryOutput struct {
	Status int
	Body   Library
}

type BackupInfoOutput struct {
	Body backup.Info
}

func registerSettings(h huma.API, r *chi.Mux, deps Deps) {
	var hardwareMu sync.Mutex
	var remoteAdminMu sync.Mutex
	var uploadSettingsMu sync.Mutex
	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "getSettings",
		Method:      http.MethodGet,
		Path:        Prefix + "/settings",
		Summary:     "Server settings (admin)",
		Tags:        []string{"settings"},
	}), func(ctx context.Context, _ *struct{}) (*SettingsOutput, error) {
		return &SettingsOutput{Body: currentSettings(ctx, deps)}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "updateSettings",
		Method:      http.MethodPut,
		Path:        Prefix + "/settings",
		Summary:     "Change server settings (admin)",
		Description: "Stored values take effect immediately. Remote admin and Turnstile configured in config.yaml or the environment cannot be changed here.",
		Tags:        []string{"settings"},
	}), func(ctx context.Context, in *UpdateSettingsInput) (*SettingsOutput, error) {
		if in.Body.Turnstile != nil || in.Body.Upload != nil {
			if err := requireConfirmed(ctx, deps, "updateSettings", nil); err != nil {
				return nil, err
			}
		}
		if in.Body.RemoteAdmin != nil {
			if deps.RemoteAdminConfig != nil {
				return nil, huma.Error409Conflict("remote admin is set by network.remote_admin in config.yaml or the environment; change it there")
			}
			if *in.Body.RemoteAdmin && !netguard.Trusted(ctx) {
				return nil, huma.Error403Forbidden("turn remote admin on from your home network or tailnet")
			}
		}
		hardwareChanged := in.Body.HWAccel != nil || in.Body.HWDevice != nil
		hardwareLocked := false
		if hardwareChanged {
			hardwareMu.Lock()
			hardwareLocked = true
		}
		defer func() {
			if hardwareLocked {
				hardwareMu.Unlock()
			}
		}()
		var hw string
		var device *int
		if hardwareChanged {
			if in.Body.HWAccel != nil && in.Body.HWDevice != nil {
				hw, device = *in.Body.HWAccel, in.Body.HWDevice
			} else {
				var err error
				hw, device, err = deps.Settings.HardwarePreference(ctx, deps.DefaultHWAccel, deps.DefaultHWDevice)
				if err != nil {
					return nil, err
				}
				if in.Body.HWAccel != nil && *in.Body.HWAccel != hw {
					device = nil
				}
				if in.Body.HWAccel != nil {
					hw = *in.Body.HWAccel
				}
				if in.Body.HWDevice != nil {
					device = in.Body.HWDevice
				}
			}
			if err := hwdevice.Validate(hw, device); err != nil {
				return nil, huma.Error422UnprocessableEntity(err.Error())
			}
		}
		if in.Body.TMDBAPIKey != nil {
			key := strings.TrimSpace(*in.Body.TMDBAPIKey)
			if key != "" {
				if err := deps.TMDB.Verify(ctx, key); errors.Is(err, tmdb.ErrUnauthorized) {
					return nil, huma.Error422UnprocessableEntity("TMDB rejected the key: " + err.Error())
				} else if err != nil {
					// Not the key's fault: nothing is saved, and trying again may work.
					return nil, huma.Error503ServiceUnavailable("couldn't reach TMDB to check the key; try again: " + err.Error())
				}
			}
			if err := deps.Settings.Set(ctx, settings.KeyTMDBAPIKey, key); err != nil {
				return nil, err
			}
			deps.TMDB.SetAPIKey(key)
			if key != "" && deps.Matcher != nil {
				deps.Matcher.RequestAll(true)
			}
		}
		if hardwareChanged {
			if err := deps.Settings.SetHardwarePreference(ctx, deps.Conn, hw, device); err != nil {
				return nil, err
			}
			if deps.Playback != nil {
				deps.Playback.SetHardwarePreferenceInBackground(hw, hwdevice.Index(device))
			}
		}
		if hardwareLocked {
			hardwareMu.Unlock()
			hardwareLocked = false
		}
		if in.Body.BackupKeep != nil {
			if err := deps.Settings.Set(ctx, settings.KeyBackupKeep, strconv.Itoa(*in.Body.BackupKeep)); err != nil {
				return nil, err
			}
		}
		if in.Body.ScanIntervalMin != nil {
			if err := deps.Settings.Set(ctx, settings.KeyScanInterval, strconv.Itoa(*in.Body.ScanIntervalMin)); err != nil {
				return nil, err
			}
			if deps.Scanner != nil {
				deps.Scanner.SetInterval(time.Duration(*in.Body.ScanIntervalMin) * time.Minute)
			}
		}
		if in.Body.Activity != nil {
			if err := saveActivitySettings(ctx, deps, *in.Body.Activity); err != nil {
				return nil, err
			}
		}
		if in.Body.Upload != nil {
			if deps.Upload == nil {
				return nil, huma.Error503ServiceUnavailable("upload policy is unavailable")
			}
			uploadSettingsMu.Lock()
			snapshot := deps.Upload.Snapshot()
			if in.Body.Upload.ExpectedPolicyRevision != nil && *in.Body.Upload.ExpectedPolicyRevision != snapshot.PolicyRevision {
				uploadSettingsMu.Unlock()
				return nil, huma.Error409Conflict("upload settings changed in another browser; reload before saving")
			}
			p := settings.UploadPreference{Bps: snapshot.ConfiguredBps, Enforce: snapshot.Enforcing}
			if in.Body.Upload.Bps != nil {
				p.Bps = *in.Body.Upload.Bps
			}
			if in.Body.Upload.Enforce != nil {
				p.Enforce = *in.Body.Upload.Enforce
			}
			err := p.Validate()
			if err == nil {
				err = deps.Settings.SetUpload(ctx, p)
			}
			if err == nil {
				deps.Upload.Update(p.Bps, p.Enforce)
			}
			uploadSettingsMu.Unlock()
			if err != nil {
				return nil, err
			}
		}
		if in.Body.ServerName != nil {
			if err := deps.Settings.Set(ctx, settings.KeyServerName, strings.TrimSpace(*in.Body.ServerName)); err != nil {
				return nil, err
			}
			if deps.Discovery != nil {
				deps.Discovery.Rename(deps.Settings.ServerName(ctx))
			}
		}
		if in.Body.Turnstile != nil {
			if err := saveTurnstile(ctx, deps, *in.Body.Turnstile); err != nil {
				return nil, err
			}
		}
		if in.Body.Subtitles != nil {
			if err := saveSubtitles(ctx, deps, *in.Body.Subtitles); err != nil {
				return nil, err
			}
		}
		if in.Body.TrickplayEnabled != nil {
			if err := saveTrickplayEnabled(ctx, deps, *in.Body.TrickplayEnabled); err != nil {
				return nil, err
			}
		}
		if in.Body.TrailersYouTube != nil {
			if err := saveTrailersYouTube(ctx, deps, *in.Body.TrailersYouTube); err != nil {
				return nil, err
			}
		}
		if in.Body.ListsMDBListKey != nil {
			if err := saveMDBListKey(ctx, deps, *in.Body.ListsMDBListKey); err != nil {
				return nil, err
			}
		}
		if in.Body.RemoteAdmin != nil {
			remoteAdminMu.Lock()
			defer remoteAdminMu.Unlock()
			enabled := *in.Body.RemoteAdmin
			v := "0"
			if enabled {
				v = "1"
			}
			if err := deps.Settings.Set(ctx, settings.KeyRemoteAdmin, v); err != nil {
				return nil, err
			}
			changed := enabled != deps.Net.RemoteAdmin()
			deps.Net.SetRemoteAdmin(enabled)
			if changed {
				logRemoteAdminSwitch(ctx, deps, enabled)
			}
		}
		return &SettingsOutput{Body: currentSettings(ctx, deps)}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "checkTurnstile",
		Method:      http.MethodPost,
		Path:        Prefix + "/settings/turnstile/check",
		Summary:     "Check a Turnstile token with the saved keys (admin)",
		Description: "For trying the widget from Settings: verifies a token as sign-in would, without signing in.",
		Tags:        []string{"settings"},
	}), func(ctx context.Context, in *CheckTurnstileInput) (*TestOutput, error) {
		if !deps.Turnstile.Required() {
			return &TestOutput{Body: TestResult{OK: false, Message: "Turnstile is off."}}, nil
		}
		if err := deps.Turnstile.Verify(ctx, in.Body.Token, clientIPFrom(ctx)); err != nil {
			deps.Log.WarnContext(ctx, "turnstile check from settings failed", "err", err)
			msg := "Cloudflare did not accept the token. If the widget showed an error, the site key may be wrong or not allow this host name."
			if errors.Is(err, turnstile.ErrUnavailable) {
				msg = "Could not check the token with Cloudflare: it is unreachable, or the secret key no longer works."
			}
			return &TestOutput{Body: TestResult{OK: false, Message: msg}}, nil
		}
		return &TestOutput{Body: TestResult{OK: true, Message: "Cloudflare accepted the check. Sign-in is protected."}}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "testTMDBKey",
		Method:      http.MethodPost,
		Path:        Prefix + "/settings/tmdb/test",
		Summary:     "Check a TMDB API key without saving it (admin)",
		Tags:        []string{"settings"},
	}), func(ctx context.Context, in *TestTMDBInput) (*TestOutput, error) {
		// TMDB refusing the key is the answer to the test, not a failed request.
		res := TestResult{OK: true, Message: "TMDB accepted the key."}
		if err := deps.TMDB.Verify(ctx, in.Body.APIKey); err != nil {
			res = TestResult{OK: false, Message: err.Error()}
		}
		return &TestOutput{Body: res}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "checkPath",
		Method:      http.MethodPost,
		Path:        Prefix + "/settings/check-path",
		Summary:     "Check a library folder on the server (admin)",
		Tags:        []string{"settings"},
	}), func(ctx context.Context, in *PathInput) (*PathCheckOutput, error) {
		return &PathCheckOutput{Body: checkPath(in.Body.Path)}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "browseFolders",
		Method:      http.MethodGet,
		Path:        Prefix + "/settings/browse",
		Summary:     "List folders on the server for picking a library path (admin)",
		Tags:        []string{"settings"},
	}), func(ctx context.Context, in *BrowseInput) (*BrowseOutput, error) {
		return &BrowseOutput{Body: browseFolders(in.Path)}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID:   "createLibrary",
		Method:        http.MethodPost,
		Path:          Prefix + "/libraries",
		Summary:       "Add a library (admin)",
		Tags:          []string{"libraries"},
		DefaultStatus: http.StatusCreated,
	}), func(ctx context.Context, in *CreateLibraryInput) (*LibraryOutput, error) {
		if err := requireConfirmed(ctx, deps, "createLibrary", nil); err != nil {
			return nil, err
		}
		path, err := libraryPath(in.Body.Path)
		if err != nil {
			return nil, huma.Error422UnprocessableEntity(err.Error())
		}
		if err := overlapping(ctx, deps, 0, path); err != nil {
			return nil, err
		}
		row, err := deps.Queries.CreateLibrary(ctx, db.CreateLibraryParams{Type: in.Body.Type, Name: strings.TrimSpace(in.Body.Name), Path: path})
		if err != nil {
			if db.IsUniqueViolation(err) {
				return nil, huma.Error409Conflict("a library already uses that folder")
			}
			return nil, err
		}
		setAdminAuditTarget(ctx, row.ID)
		// From now on the configured folders are never added at startup.
		if err := library.MarkSeeded(ctx, deps.Queries); err != nil {
			deps.Log.WarnContext(ctx, "could not record that the server has a library", "err", err)
		}
		if deps.Scanner != nil {
			deps.Scanner.Request(row.ID)
		}
		counts, _ := fileCounts(ctx, deps)
		return &LibraryOutput{Status: http.StatusCreated, Body: libraryFromDB(deps, row, counts[row.ID])}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "updateLibrary",
		Method:      http.MethodPut,
		Path:        Prefix + "/libraries/{id}",
		Summary:     "Rename a library or move its folder (admin)",
		Tags:        []string{"libraries"},
	}), func(ctx context.Context, in *UpdateLibraryInput) (*LibraryOutput, error) {
		old, err := getLibrary(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		if filepath.Clean(strings.TrimSpace(in.Body.Path)) != old.Path {
			if err := requireConfirmed(ctx, deps, "updateLibrary", in.ID); err != nil {
				return nil, err
			}
		}
		path, err := libraryPath(in.Body.Path)
		if err != nil {
			return nil, huma.Error422UnprocessableEntity(err.Error())
		}
		if err := overlapping(ctx, deps, in.ID, path); err != nil {
			return nil, err
		}
		row, err := deps.Queries.UpdateLibrary(ctx, db.UpdateLibraryParams{Name: strings.TrimSpace(in.Body.Name), Path: path, ID: in.ID})
		if err != nil {
			if db.IsUniqueViolation(err) {
				return nil, huma.Error409Conflict("a library already uses that folder")
			}
			return nil, err
		}
		if deps.Scanner != nil {
			deps.Scanner.Request(row.ID)
		}
		counts, _ := fileCounts(ctx, deps)
		return &LibraryOutput{Status: http.StatusOK, Body: libraryFromDB(deps, row, counts[row.ID])}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID:   "deleteLibrary",
		Method:        http.MethodDelete,
		Path:          Prefix + "/libraries/{id}",
		Summary:       "Remove a library and everything indexed from it (admin)",
		Description:   "Files on disk are untouched. Watch history for its items is removed.",
		Tags:          []string{"libraries"},
		DefaultStatus: http.StatusNoContent,
	}), func(ctx context.Context, in *LibraryIDInput) (*struct{}, error) {
		if _, err := getLibrary(ctx, deps, in.ID); err != nil {
			return nil, err
		}
		if err := deps.Queries.DeleteLibrary(ctx, in.ID); err != nil {
			return nil, err
		}
		forgetLibraryRows(ctx, deps, in.ID)
		return nil, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID:   "createBackup",
		Method:        http.MethodPost,
		Path:          Prefix + "/backups",
		Summary:       "Write a backup archive into the data directory now (admin)",
		Tags:          []string{"settings"},
		DefaultStatus: http.StatusCreated,
	}), func(ctx context.Context, _ *struct{}) (*BackupInfoOutput, error) {
		if err := requireConfirmed(ctx, deps, "createBackup", nil); err != nil {
			return nil, err
		}
		if deps.Conn == nil {
			return nil, huma.Error503ServiceUnavailable("backups not available")
		}
		keep, _ := strconv.Atoi(mustGet(ctx, deps, settings.KeyBackupKeep, "7"))
		path, err := backup.Save(ctx, deps.Conn, deps.DataDir, keep)
		if err != nil {
			return nil, err
		}
		st, err := os.Stat(path)
		if err != nil {
			return nil, err
		}
		return &BackupInfoOutput{Body: backup.Info{Name: filepath.Base(path), Size: st.Size(), ModTime: st.ModTime()}}, nil
	})

	// Preflight lets the typed web client confirm without fetching an archive
	// into memory or asking again while this session's confirmation is fresh.
	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "prepareBackupDownload", Method: http.MethodPost, Path: Prefix + "/backups/download",
		Summary: "Check password confirmation before navigating to the backup download", Tags: []string{"settings"}, DefaultStatus: http.StatusNoContent,
	}), func(ctx context.Context, _ *struct{}) (*struct{}, error) {
		return nil, requireConfirmed(ctx, deps, "prepareBackupDownload", nil)
	})

	// Downloads stay on chi so the archive can stream.
	r.Get(Prefix+"/backups/download", func(w http.ResponseWriter, req *http.Request) {
		if err := requireConfirmed(req.Context(), deps, "downloadBackup", nil); err != nil {
			writeAdminError(w, err)
			return
		}
		if deps.Conn == nil {
			writeAdminError(w, huma.Error503ServiceUnavailable("backups not available"))
			return
		}
		w.Header().Set("Content-Type", "application/gzip")
		w.Header().Set("Content-Disposition", `attachment; filename="watchmymedia-backup.tar.gz"`)
		if err := backup.Write(req.Context(), deps.Conn, deps.DataDir, w); err != nil {
			deps.Log.Error("backup download failed", "err", err)
		}
	})
}

// ScanInterval is the time between scheduled scans: the one saved in
// Settings, else def from config.yaml or the environment.
func ScanInterval(ctx context.Context, store *settings.Store, def time.Duration) time.Duration {
	v, _ := store.Get(ctx, settings.KeyScanInterval, "")
	if n, err := strconv.Atoi(v); err == nil && n > 0 {
		return time.Duration(n) * time.Minute
	}
	return def
}

func mustGet(ctx context.Context, deps Deps, key, def string) string {
	if deps.Settings == nil {
		return def
	}
	v, _ := deps.Settings.Get(ctx, key, def)
	return v
}

func currentSettings(ctx context.Context, deps Deps) Settings {
	s := Settings{RemoteAdmin: &RemoteAdminSettings{Enabled: deps.Net != nil && deps.Net.RemoteAdmin(), FromConfig: deps.RemoteAdminConfig != nil}, DataDir: deps.DataDir, Backups: []backup.Info{}, HWAccel: "auto"}
	if deps.Upload != nil {
		snapshot := deps.Upload.Snapshot()
		s.Upload = &snapshot
	}
	s.Subtitles = subtitles.SubtitleStatus{Languages: subtitles.DefaultLanguages, Downloads: subtitles.DownloadsEveryone}
	if deps.OnlineSubtitles != nil {
		// Reading the account asks OpenSubtitles; don't let it hold up the page.
		sctx, cancel := context.WithTimeout(ctx, 4*time.Second)
		s.Subtitles = deps.OnlineSubtitles.Status(sctx)
		cancel()
	}
	if deps.TMDB != nil {
		s.TMDBConfigured = deps.TMDB.Configured()
	}
	if deps.Settings != nil {
		if key, _ := deps.Settings.Get(ctx, settings.KeyTMDBAPIKey, ""); len(key) >= 4 {
			s.TMDBKeyHint = "…" + key[len(key)-4:]
		}
		s.HWAccel, s.HWDevice, _ = deps.Settings.HardwarePreference(ctx, deps.DefaultHWAccel, deps.DefaultHWDevice)
		s.BackupKeep, _ = strconv.Atoi(mustGet(ctx, deps, settings.KeyBackupKeep, "7"))
		if deps.Scanner != nil {
			s.ScanIntervalMin = int(deps.Scanner.Interval() / time.Minute)
		}
		s.ServerName = mustGet(ctx, deps, settings.KeyServerName, "")
	}
	s.ServerNameDefault = settings.DefaultServerName()
	if ts := deps.Turnstile; ts != nil {
		site, secret := ts.Keys()
		s.Turnstile = TurnstileSettings{Enabled: ts.Required(), SiteKey: site, FromConfig: ts.Fixed, Disabled: ts.Disabled}
		if len(secret) >= 4 {
			s.Turnstile.SecretKeyHint = "…" + secret[len(secret)-4:]
		}
	}
	s.Activity = activitySettings(ActivityConfig(ctx, deps.Settings))
	s.TrailersYouTube = deps.Trailers.YouTube()
	if deps.Lists != nil {
		if key := deps.Lists.MDBList().Key(); key != "" {
			s.ListsMDBListConfigured = true
			if len(key) >= 4 {
				s.ListsMDBListKeyHint = "…" + key[len(key)-4:]
			}
		}
	}
	s.Cast = CastSettings{Enabled: deps.Cast.Enabled, AppID: deps.Cast.AppID, URL: deps.Cast.URL,
		ReceiverOrigins: append([]string{}, deps.Cast.ReceiverOrigins...), URLs: castURLs(requestFrom(ctx), deps)}
	if deps.Playback != nil {
		resources, scanErr := deps.Playback.Resources()
		s.Storage = &PlaybackCacheLoad{TemporaryBytes: resources.TemporaryBytes, TemporaryFiles: resources.TemporaryFiles,
			FreeBytes: resources.FreeBytes, MaxCacheBytes: resources.MaxCacheBytes, MaxSessionBytes: resources.MaxSessionBytes,
			MinFreeBytes: resources.MinFreeBytes, EventAheadMs: resources.EventAheadMs,
			EventKeepBehindMs: resources.EventKeepBehindMs, ScanComplete: scanErr == nil && resources.ScanComplete}
		caps := deps.Playback.Capabilities()
		s.Capabilities = &caps
		s.HardwareDiagnostics = caps.Diagnostics
		s.HardwareAdapters = deps.Playback.HardwareAdapters()
	}
	if deps.DataDir != "" {
		if list := backup.List(deps.DataDir); list != nil {
			s.Backups = list
		}
	}
	if deps.Trickplay != nil {
		if t, err := deps.Trickplay.Status(ctx); err != nil {
			deps.Log.WarnContext(ctx, "could not read seek thumbnail status", "err", err)
		} else {
			s.Trickplay = &t
		}
	}
	return s
}

// saveTurnstile turns Turnstile on with checked keys, or off. A secret
// Cloudflare does not know is refused, so a typo cannot lock everyone out.
func saveTurnstile(ctx context.Context, deps Deps, in TurnstileUpdate) error {
	ts := deps.Turnstile
	if ts == nil || deps.Settings == nil {
		return huma.Error503ServiceUnavailable("Turnstile is not available")
	}
	if ts.Fixed {
		return huma.Error409Conflict("the Turnstile keys are set in config.yaml or the environment (auth.turnstile); change them there")
	}
	site, secret := strings.TrimSpace(in.SiteKey), strings.TrimSpace(in.SecretKey)
	if site == "" {
		for _, k := range []string{settings.KeyTurnstileSiteKey, settings.KeyTurnstileSecretKey} {
			if err := deps.Settings.Set(ctx, k, ""); err != nil {
				return err
			}
		}
		ts.SetKeys("", "")
		return nil
	}
	if secret == "" {
		_, secret = ts.Keys()
		if secret == "" {
			return huma.Error422UnprocessableEntity("enter the secret key too")
		}
	}
	if !turnstile.ValidKey(site) || !turnstile.ValidKey(secret) {
		return huma.Error422UnprocessableEntity("that does not look like a Turnstile key; copy the site key and secret key from the widget in Cloudflare's dashboard")
	}
	if err := ts.CheckSecret(ctx, secret); err != nil {
		if errors.Is(err, turnstile.ErrBadSecret) {
			return huma.Error422UnprocessableEntity(err.Error())
		}
		deps.Log.WarnContext(ctx, "turnstile secret check failed", "err", err)
		return huma.Error503ServiceUnavailable("could not reach Cloudflare to check the secret key; try again in a minute")
	}
	if err := deps.Settings.Set(ctx, settings.KeyTurnstileSiteKey, site); err != nil {
		return err
	}
	if err := deps.Settings.Set(ctx, settings.KeyTurnstileSecretKey, secret); err != nil {
		return err
	}
	ts.SetKeys(site, secret)
	return nil
}

// clientIPFrom is the request's client address, or "" when unknown.
func clientIPFrom(ctx context.Context) string {
	if i, ok := netguard.From(ctx); ok && i.IP.IsValid() {
		return i.IP.String()
	}
	return ""
}

// libraryPath cleans and validates a folder for a library.
func libraryPath(p string) (string, error) {
	p = filepath.Clean(strings.TrimSpace(p))
	if !filepath.IsAbs(p) {
		return "", fmt.Errorf("path must be absolute")
	}
	st, err := os.Stat(p)
	if err != nil {
		return "", fmt.Errorf("folder not found: %s", p)
	}
	if !st.IsDir() {
		return "", fmt.Errorf("not a folder: %s", p)
	}
	return p, nil
}

// checkFolders caps the folders checkPath reads, so a large library answers
// about as fast as a small one.
const checkFolders = 1000

// checkPath looks at a folder before it becomes a library: whether it can
// be read, a few of its names, and its video files down to three levels, as
// in Show/Season 1/episode.
func checkPath(p string) PathCheck {
	out := PathCheck{Path: filepath.Clean(p), Sample: []string{}}
	st, err := os.Stat(out.Path)
	if err != nil || !st.IsDir() {
		return out
	}
	out.Exists = true
	entries, err := os.ReadDir(out.Path)
	if err != nil {
		return out
	}
	out.Readable = true
	for _, e := range entries {
		if len(out.Sample) < 5 && !strings.HasPrefix(e.Name(), ".") {
			out.Sample = append(out.Sample, e.Name())
		}
	}
	read := 1
	var count func(dir string, entries []os.DirEntry, depth int)
	count = func(dir string, entries []os.DirEntry, depth int) {
		for _, e := range entries {
			name := e.Name()
			if !e.IsDir() {
				if !strings.HasPrefix(name, ".") && parser.IsVideo(name) {
					out.VideoFiles++
				}
				continue
			}
			if depth == 3 || parser.IgnoreDir(name) {
				continue
			}
			if read >= checkFolders {
				out.Truncated = true
				continue
			}
			read++
			sub := filepath.Join(dir, name)
			if subEntries, err := os.ReadDir(sub); err == nil {
				count(sub, subEntries, depth+1)
			}
		}
	}
	count(out.Path, entries, 1)
	return out
}

// overlapping refuses a library folder inside another library's, or holding
// one: its files would be indexed twice. id is the library being moved, or
// 0 for a new one. Accents are composed before comparing, as folder names on
// real servers are often decomposed.
func overlapping(ctx context.Context, deps Deps, id int64, path string) error {
	libs, err := deps.Queries.ListLibraries(ctx)
	if err != nil {
		return err
	}
	p := norm.NFC.String(filepath.Clean(path))
	for _, l := range libs {
		other := norm.NFC.String(filepath.Clean(l.Path))
		if l.ID == id {
			continue
		}
		if rel, ok := below(other, p); ok && rel == "." {
			return huma.Error409Conflict("a library already uses that folder")
		} else if ok {
			return huma.Error409Conflict(fmt.Sprintf("that folder is inside the %q library (%s)", l.Name, l.Path))
		}
		if _, ok := below(p, other); ok {
			return huma.Error409Conflict(fmt.Sprintf("that folder holds the %q library (%s)", l.Name, l.Path))
		}
	}
	return nil
}

// below reports whether p is dir or below it, and the path from one to the
// other ("." for the same folder). filepath.Rel compares as the platform
// does, so case doesn't matter on Windows.
func below(dir, p string) (string, bool) {
	rel, err := filepath.Rel(dir, p)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return "", false
	}
	return rel, true
}

func browseFolders(p string) BrowseResult {
	p = strings.TrimSpace(p)
	if p == "" {
		return BrowseResult{Path: "", Entries: folderRoots()}
	}
	if filepath.VolumeName(p) == p {
		p += string(filepath.Separator) // "D:" alone means D's current folder
	}
	p = filepath.Clean(p)
	out := BrowseResult{Path: p, Entries: []BrowseEntry{}}
	if parent := filepath.Dir(p); parent != p {
		out.Parent = parent
	}
	entries, err := os.ReadDir(p)
	if err != nil {
		return out
	}
	for _, e := range entries {
		if e.IsDir() && !strings.HasPrefix(e.Name(), ".") && !parser.IgnoreDir(e.Name()) && !hiddenFolder(e) {
			out.Entries = append(out.Entries, BrowseEntry{Name: e.Name(), Path: filepath.Join(p, e.Name())})
		}
	}
	sort.Slice(out.Entries, func(i, j int) bool {
		return strings.ToLower(out.Entries[i].Name) < strings.ToLower(out.Entries[j].Name)
	})
	return out
}

func saveSubtitles(ctx context.Context, deps Deps, u SubtitlesUpdate) error {
	if deps.OnlineSubtitles == nil {
		return huma.Error503ServiceUnavailable("online subtitles are not available on this server")
	}
	if u.Username != nil {
		err := deps.OnlineSubtitles.SetAccount(ctx, *u.Username, u.Password)
		switch {
		case errors.Is(err, opensubtitles.ErrSignIn):
			return huma.Error422UnprocessableEntity("OpenSubtitles refused that username and password.")
		case errors.Is(err, opensubtitles.ErrNoAPIKey):
			return huma.Error503ServiceUnavailable("this build has no OpenSubtitles API key")
		case err != nil:
			return huma.Error502BadGateway("could not reach OpenSubtitles: " + err.Error())
		}
	}
	if u.Languages != nil {
		langs := subtitles.ParseLanguages(strings.Join(u.Languages, ","))
		if err := deps.Settings.Set(ctx, settings.KeySubtitleLanguages, strings.Join(langs, ",")); err != nil {
			return err
		}
	}
	if u.Downloads != nil {
		if err := deps.Settings.Set(ctx, settings.KeySubtitleDownloads, *u.Downloads); err != nil {
			return err
		}
	}
	return nil
}

// saveMDBListKey checks a new MDBList key with MDBList before keeping it,
// so lists don't start failing on a typo; an empty key forgets it.
func saveMDBListKey(ctx context.Context, deps Deps, key string) error {
	if deps.Lists == nil || deps.Settings == nil {
		return huma.Error503ServiceUnavailable("lists are not available")
	}
	key = strings.TrimSpace(key)
	if key != "" {
		if err := deps.Lists.MDBList().Verify(ctx, key); errors.Is(err, lists.ErrMDBListKey) {
			return huma.Error422UnprocessableEntity("MDBList refused the key")
		} else if err != nil {
			// Not the key's fault: nothing is saved, and trying again may work.
			deps.Log.WarnContext(ctx, "could not check an MDBList key", "err", err)
			return huma.Error503ServiceUnavailable("couldn't reach MDBList to check the key; try again")
		}
	}
	if err := deps.Settings.Set(ctx, settings.KeyListsMDBListKey, key); err != nil {
		return err
	}
	deps.Lists.MDBList().SetKey(key)
	return nil
}

// forgetLibraryRows clears a deleted library's screen: everyone's layout
// of it and the household's default go, and lists made on it first show
// on Home (PLAN-ROWS.md 7.1).
func forgetLibraryRows(ctx context.Context, deps Deps, libraryID int64) {
	ref := screenRef{Name: libraryScreenName(libraryID), LibraryID: libraryID}
	if err := deps.Queries.DeleteUserPreferencesByKey(ctx, ref.prefKey()); err != nil {
		deps.Log.WarnContext(ctx, "could not forget a deleted library's layouts", "library", libraryID, "err", err)
	}
	if deps.Settings != nil {
		if err := deps.Settings.Set(ctx, ref.defaultKey(), ""); err != nil {
			deps.Log.WarnContext(ctx, "could not forget a deleted library's default layout", "library", libraryID, "err", err)
		}
	}
	if err := deps.Queries.MoveMediaListsToHome(ctx, ref.Name); err != nil {
		deps.Log.WarnContext(ctx, "could not move a deleted library's lists to Home", "library", libraryID, "err", err)
	}
}
