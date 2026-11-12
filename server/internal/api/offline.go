package api

import (
	"cmp"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"path/filepath"
	"slices"
	"strings"
	"time"

	"github.com/danielgtaylor/huma/v2"
	"github.com/go-chi/chi/v5"

	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/offline"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/probe"
	"watchmymedia/server/internal/watchstate"
)

// Offline downloads (PLAN-DOWNLOADS.md): an Android phone or tablet
// downloads a movie or an episode to watch with no connection. What it
// downloads is the playback decision for the device at the chosen quality:
// the original file, fetched with Range, when the device plays it directly;
// otherwise HLS from a download session the server can make again from the
// download's record. Each download has a record per file, person and
// device; its media routes take short offline tokens (o1.) that open that
// record's routes only. Progress made offline comes back through
// syncOffline and goes into watch state by when it happened; a download is
// not a play, so nothing reaches History.

// Download record states, as OfflineDownload.state reports them. ready and
// restart are stored; replaced and gone are worked out from the file;
// revoked is stored, or worked out when the person may no longer download.
const (
	offlineReady    = "ready"
	offlineRestart  = "restart"
	offlineRevoked  = "revoked"
	offlineReplaced = "replaced"
	offlineGone     = "gone"
)

// offlineQualities are the steps a download may be made at, best first:
// the Ladder's, without 2160 (a phone's screen) and 360.
var offlineQualities = []string{"original", "1080", "720", "480"}

// offlineSlotWait is how long a download's request waits for a transcoder
// slot before answering 503 (PLAN-DOWNLOADS.md §3.2).
const offlineSlotWait = 30 * time.Second

type OfflineSettings struct {
	Access string `json:"access" enum:"none,admins,everyone" doc:"Who may download to watch offline in the apps: none, admins (the default) or everyone with an account. Withdrawing it removes a person's downloads when their device next syncs."`
}

type OfflineSettingsOutput struct{ Body OfflineSettings }

type UpdateOfflineSettingsInput struct{ Body OfflineSettings }

// OfflineItem is what a download is of, for the Downloads screen: a
// snapshot taken when it was made, so it still reads after the file goes.
type OfflineItem struct {
	Type          string `json:"type" doc:"movie, episode, or file for a file not matched yet."`
	MovieID       *int64 `json:"movieId,omitempty"`
	ShowID        *int64 `json:"showId,omitempty"`
	EpisodeID     *int64 `json:"episodeId,omitempty"`
	Title         string `json:"title"`
	ShowTitle     string `json:"showTitle,omitempty"`
	SeasonNumber  *int64 `json:"seasonNumber,omitempty"`
	EpisodeNumber *int64 `json:"episodeNumber,omitempty"`
	Year          *int64 `json:"year,omitempty"`
	PosterPath    string `json:"posterPath,omitempty" doc:"The movie's or the show's poster."`
	ArtPath       string `json:"artPath,omitempty" doc:"The movie's backdrop, or the episode's still."`
}

// OfflineUser names the person a download is for.
type OfflineUser struct {
	ID   int64  `json:"id"`
	Name string `json:"name"`
}

// OfflineDownload is a download record as the apps and Activity see it.
type OfflineDownload struct {
	ID     string `json:"id" doc:"The record's ID: the offline routes and tokens name it."`
	FileID int64  `json:"fileId"`
	// State is a plain string, not an enum, so an app reads a value it
	// does not know (as revoked) instead of rejecting the reply.
	State      string `json:"state" doc:"ready: plays and downloads. replaced: the server has a new version of the file (replacedBy); gone: the file was deleted; keep playing the copy, mark it, and sync no progress for gone. revoked: delete the copy (an admin revoked it, or the person may no longer download). restart: a remade download session's init segment differs, so delete what was downloaded and start over. Read a value you don't know as revoked."`
	ReplacedBy int64  `json:"replacedBy,omitempty" required:"false" doc:"replaced only: the item's file now, to download again."`
	RevokedBy  string `json:"revokedBy,omitempty" required:"false" doc:"revoked only: admin, access (the person may no longer download) or user."`
	Mode       string `json:"mode" doc:"direct, remux, audio or transcode, as the playback decision's mode."`
	Protocol   string `json:"protocol" doc:"file: fetch url with Range requests, the original file. hls: url is a VOD master playlist; fetch its playlists, init segment and segments in order."`
	URL        string `json:"url" doc:"Add an offline token (createOfflineToken) as access_token, or send the device's session as a bearer token."`
	SizeBytes  int64  `json:"sizeBytes" doc:"The file's size for file; an estimate for hls (sizeEstimated), or what the device reported once downloaded."`
	// SizeEstimated says the size is the server's guess.
	SizeEstimated bool        `json:"sizeEstimated"`
	Quality       string      `json:"quality" doc:"original, 1080, 720 or 480."`
	CreatedAt     time.Time   `json:"createdAt"`
	Item          OfflineItem `json:"item"`
	// Decision is what the offline player needs: tracks, segments,
	// credits, the watched point and the next episode. Only when the
	// download is made.
	Decision *playback.Decision `json:"decision,omitempty" required:"false" doc:"Made downloads only: the playback decision, with url pointing at the offline route."`
	// What the device last said, for lists.
	DeviceState string       `json:"deviceState,omitempty" required:"false" doc:"queued, downloading, downloaded or removed, as the device last reported."`
	DeviceName  string       `json:"deviceName,omitempty" required:"false"`
	User        *OfflineUser `json:"user,omitempty" required:"false"`
	SyncedAt    *time.Time   `json:"syncedAt,omitempty" required:"false" doc:"When the device last synced or fetched."`
	CompletedAt *time.Time   `json:"completedAt,omitempty" required:"false"`
	RevokedAt   *time.Time   `json:"revokedAt,omitempty" required:"false"`
	// RevokedByName is the admin who revoked it.
	RevokedByName string `json:"revokedByName,omitempty" required:"false"`
	BytesSent     int64  `json:"bytesSent" required:"false" doc:"Everything the server has sent for this download, retries included."`
}

type OfflineDownloadOutput struct {
	Status int
	Body   OfflineDownload
}

// OfflineTransfer is a download being fetched now, for Activity › Downloads.
type OfflineTransfer struct {
	DownloadID    string      `json:"downloadId"`
	User          OfflineUser `json:"user"`
	DeviceName    string      `json:"deviceName"`
	FileID        int64       `json:"fileId"`
	Item          OfflineItem `json:"item"`
	Quality       string      `json:"quality"`
	Mode          string      `json:"mode"`
	Encoder       string      `json:"encoder,omitempty" doc:"A transcode's encoder."`
	Speed         float64     `json:"speed,omitempty" doc:"A transcode's ffmpeg speed, as a multiple of real time."`
	Network       string      `json:"network" doc:"local, tailnet or remote."`
	SizeBytes     int64       `json:"sizeBytes"`
	SizeEstimated bool        `json:"sizeEstimated"`
	BytesSent     int64       `json:"bytesSent" doc:"Sent since this transfer started."`
	Bandwidth     int64       `json:"bandwidth" doc:"Bits per second over the last 30 seconds."`
	Progress      float64     `json:"progress" doc:"How far through the download the device has fetched, 0 to 1."`
	StartedAt     time.Time   `json:"startedAt"`
	LastSeenAt    time.Time   `json:"lastSeenAt"`
}

type OfflineDownloadList struct {
	Items     []OfflineDownload `json:"items" nullable:"false" doc:"Newest first."`
	Transfers []OfflineTransfer `json:"transfers" nullable:"false" doc:"Downloads fetched in the last minute, oldest first."`
}

type OfflineDownloadListOutput struct{ Body OfflineDownloadList }

type ListOfflineInput struct {
	All bool `query:"all" required:"false" doc:"Admins: everyone's downloads instead of your own."`
}

type OfflineIDInput struct {
	ID string `path:"id" pattern:"^[a-z2-7]{16}$"`
}

type DeleteOfflineInput struct {
	ID     string `path:"id" pattern:"^[a-z2-7]{16}$"`
	Revoke bool   `query:"revoke" required:"false" doc:"Admins: revoke even your own download, so its device deletes it at its next sync, instead of removing the record."`
}

type OfflineToken struct {
	Token     string    `json:"token" doc:"Add as ?access_token= to this download's url and every URL its playlists list. Works on this download's routes only."`
	ExpiresAt time.Time `json:"expiresAt" doc:"Ask for a new one before this."`
	Param     string    `json:"param" doc:"The query parameter name, access_token."`
}

type OfflineTokenOutput struct{ Body OfflineToken }

type OfflinePlanInput struct {
	ID   int64 `path:"id" doc:"Media file ID."`
	Body struct {
		Profile playback.Profile `json:"profile"`
	}
}

// OfflineQuality is how a file would download at one quality.
type OfflineQuality struct {
	Quality       string             `json:"quality" doc:"original, 1080, 720 or 480."`
	Mode          string             `json:"mode" doc:"direct, remux, audio or transcode."`
	Protocol      string             `json:"protocol" doc:"file or hls."`
	SizeBytes     int64              `json:"sizeBytes"`
	SizeEstimated bool               `json:"sizeEstimated" doc:"The size is about this much: anything but the original file."`
	Reasons       []string           `json:"reasons" nullable:"false" doc:"Why a cheaper mode was not possible."`
	Video         playback.VideoPlan `json:"video"`
	Audio         playback.AudioPlan `json:"audio"`
}

// OfflinePlan is what the download sheet lists.
type OfflinePlan struct {
	FileID      int64                    `json:"fileId"`
	DurationMs  int64                    `json:"durationMs"`
	Qualities   []OfflineQuality         `json:"qualities" nullable:"false" doc:"Best first; several may be the same download when the file is small."`
	AudioTracks []playback.AudioTrack    `json:"audioTracks" nullable:"false" doc:"For hls downloads, which carry one: pick it with audioIndex."`
	Subtitles   []playback.SubtitleTrack `json:"subtitles" nullable:"false" doc:"Text subtitles come with every download; an image subtitle only burned in (subtitleId and burnIn), for hls downloads."`
}

type OfflinePlanOutput struct{ Body OfflinePlan }

type CreateOfflineInput struct {
	ID   int64 `path:"id" doc:"Media file ID."`
	Body struct {
		Profile    playback.Profile `json:"profile"`
		Quality    string           `json:"quality,omitempty" enum:"original,1080,720,480" doc:"Default original."`
		AudioIndex *int             `json:"audioIndex,omitempty" doc:"hls downloads carry one audio track; omit for the one the player would pick: the person's audio languages, else the file's default."`
		SubtitleID string           `json:"subtitleId,omitempty" maxLength:"32" doc:"With burnIn: a subtitle to burn into an hls download."`
		BurnIn     bool             `json:"burnIn,omitempty"`
		Client     playback.Client  `json:"client" doc:"The device downloading. Its deviceId names the device in syncOffline."`
	}
}

type OfflineSyncDownload struct {
	ID          string `json:"id" maxLength:"64"`
	DeviceState string `json:"deviceState" enum:"queued,downloading,downloaded,removed" doc:"removed: the copy is gone from the device (deleted, or never made); the record goes."`
	SizeBytes   int64  `json:"sizeBytes,omitempty" minimum:"0" doc:"What the download takes on the device, once downloaded."`
}

type OfflineSyncProgress struct {
	DownloadID string    `json:"downloadId,omitempty" maxLength:"64"`
	FileID     int64     `json:"fileId"`
	PositionMs *int64    `json:"positionMs,omitempty" minimum:"0" doc:"A position report; omit for a mark."`
	DurationMs int64     `json:"durationMs,omitempty" minimum:"0"`
	Watched    *bool     `json:"watched,omitempty" doc:"Mark watched (true) or unwatched (false); omit for a position."`
	At         time.Time `json:"at" doc:"When it happened, by the device's clock."`
}

type OfflineSyncInput struct {
	Body struct {
		Client    playback.Client       `json:"client"`
		SentAt    time.Time             `json:"sentAt" doc:"The device's clock as it sends this; the server corrects every at by the difference from its own."`
		Downloads []OfflineSyncDownload `json:"downloads,omitempty" maxItems:"5000" nullable:"false"`
		Progress  []OfflineSyncProgress `json:"progress,omitempty" maxItems:"5000" nullable:"false" doc:"Per file: the newest position, and any mark after it."`
	}
}

type OfflineSyncResult struct {
	DownloadID string      `json:"downloadId,omitempty"`
	FileID     int64       `json:"fileId"`
	Outcome    string      `json:"outcome" doc:"applied; newer (a later play or mark on the server stands); unavailable (the item or file is gone). Every outcome is final: drop the entry."`
	WatchState *WatchState `json:"watchState,omitempty" doc:"The item's state now; absent when it has none (never played, or unwatched)."`
}

type OfflineSync struct {
	ServerTime time.Time           `json:"serverTime"`
	Offline    bool                `json:"offline" doc:"This person may download now. When false every download is revoked."`
	Downloads  []OfflineDownload   `json:"downloads" nullable:"false" doc:"Every download this device has on the server, with its state. A download the device holds that is not listed is unknown here: delete it."`
	Progress   []OfflineSyncResult `json:"progress" nullable:"false" doc:"One per progress entry, in the order sent."`
}

type OfflineSyncOutput struct{ Body OfflineSync }

// mayDownload says whether u may download now (offline.access).
func mayDownload(ctx context.Context, deps Deps, u auth.User) bool {
	return offline.Allowed(offline.Access(ctx, deps.Settings), u.IsAdmin)
}

// recordState is where a download stands: its state, the item's new file
// when it was replaced, and its file while it is the one downloaded.
type recordState struct {
	state      string
	revokedBy  string
	replacedBy int64
	file       *db.MediaFile
}

// offlineState works out a record's state. may is whether its person may
// download now: without it the record reads as revoked, and is revoked
// for good at the next sync or token.
func offlineState(ctx context.Context, q *db.Queries, rec db.OfflineDownload, may bool) (recordState, error) {
	if rec.State == offlineRevoked {
		return recordState{state: offlineRevoked, revokedBy: rec.RevokedBy}, nil
	}
	if !may {
		return recordState{state: offlineRevoked, revokedBy: "access"}, nil
	}
	if rec.MediaFileID != nil {
		f, err := q.GetMediaFile(ctx, *rec.MediaFileID)
		switch {
		case err == nil && f.Status == "present" && f.Size == rec.FileSize && f.Mtime == rec.FileMtime:
			return recordState{state: rec.State, file: &f}, nil
		case err != nil && !errors.Is(err, sql.ErrNoRows):
			return recordState{}, err
		}
	}
	var ids []int64
	var err error
	switch rec.ItemType {
	case watchstate.Movie:
		ids, err = q.ListPresentMovieFileIDs(ctx, &rec.ItemID)
	case watchstate.Episode:
		ids, err = q.ListPresentEpisodeFileIDs(ctx, rec.ItemID)
	}
	if err != nil {
		return recordState{}, err
	}
	if len(ids) > 0 {
		return recordState{state: offlineReplaced, replacedBy: ids[0]}, nil
	}
	return recordState{state: offlineGone}, nil
}

func offlineURL(id, mode string) (url, protocol string) {
	if mode == string(playback.ModeDirect) {
		return fmt.Sprintf("%s/offline/%s/stream", Prefix, id), "file"
	}
	return fmt.Sprintf("%s/offline/%s/hls/%s", Prefix, id, playback.VODMaster), "hls"
}

func offlineItem(snapshot string) OfflineItem {
	var it activity.Item
	_ = json.Unmarshal([]byte(snapshot), &it)
	return OfflineItem{Type: it.Type, MovieID: it.MovieID, ShowID: it.ShowID, EpisodeID: it.EpisodeID, Title: it.Title, ShowTitle: it.ShowTitle,
		SeasonNumber: it.SeasonNumber, EpisodeNumber: it.EpisodeNumber, Year: it.Year, PosterPath: it.PosterPath, ArtPath: it.ArtPath}
}

// offlineView is a record as the API shows it.
func offlineView(rec db.OfflineDownload, st recordState) OfflineDownload {
	url, protocol := offlineURL(rec.PublicID, rec.Mode)
	o := OfflineDownload{
		ID: rec.PublicID, State: st.state, ReplacedBy: st.replacedBy, Mode: rec.Mode, Protocol: protocol, URL: url,
		SizeBytes: rec.SizeBytes, SizeEstimated: rec.Mode != string(playback.ModeDirect) && rec.CompletedAt == nil,
		Quality: rec.Quality, CreatedAt: db.ParseTime(rec.CreatedAt), Item: offlineItem(rec.Item),
		DeviceState: rec.DeviceState, DeviceName: rec.DeviceName, BytesSent: rec.BytesSent,
		CompletedAt: db.ParseTimePtr(rec.CompletedAt), RevokedAt: db.ParseTimePtr(rec.RevokedAt),
	}
	if st.state == offlineRevoked {
		o.RevokedBy = st.revokedBy
	}
	if rec.MediaFileID != nil {
		o.FileID = *rec.MediaFileID
	} else if fid, ok := requestFileID(rec); ok {
		o.FileID = fid
	}
	if t := db.ParseTime(rec.SyncedAt); !t.IsZero() {
		o.SyncedAt = &t
	}
	return o
}

// offlineRequest is what a record keeps of how its download was asked for.
type offlineRequest struct {
	playback.Request
	// FileID is the file downloaded, which the record's foreign key
	// forgets when the file's row goes.
	FileID int64 `json:"fileId"`
}

func requestFileID(rec db.OfflineDownload) (int64, bool) {
	var r offlineRequest
	if json.Unmarshal([]byte(rec.Request), &r) != nil || r.FileID == 0 {
		return 0, false
	}
	return r.FileID, true
}

// offlineDecide runs the playback decision for a download: the stored
// request against the file, with its sidecar and downloaded subtitles.
func offlineDecide(ctx context.Context, deps Deps, row db.MediaFile, info *probe.MediaInfo, req playback.Request) (playback.Decision, []playback.Sidecar) {
	sidecars := playback.FindSidecars(row.Path)
	var caps playback.Capabilities
	if deps.Playback != nil {
		caps = deps.Playback.Capabilities()
	}
	d := playback.Decide(info, filepath.Ext(row.Path), req, sidecars, caps)
	for _, r := range deps.OnlineSubtitles.List(ctx, row.ID) {
		d.Subtitles = append(d.Subtitles, downloadedTrack(r))
	}
	return d, sidecars
}

// offlineDecidePreferred is offlineDecide with the person's audio languages.
// They choose the one track an hls download carries when the request names
// none; a file download keeps every track, and the device chooses at play
// time, so naming a track must not turn it into an hls download. The
// request it returns is the one to keep with the record.
func offlineDecidePreferred(ctx context.Context, deps Deps, row db.MediaFile, info *probe.MediaInfo, req playback.Request, audioLanguages []string) (playback.Decision, []playback.Sidecar, playback.Request) {
	d, sidecars := offlineDecide(ctx, deps, row, info, req)
	if d.Protocol != "hls" {
		return d, sidecars, req
	}
	preferred, why := withPreferredAudio(req, info, audioLanguages)
	if why == "" {
		return d, sidecars, req
	}
	d, sidecars = offlineDecide(ctx, deps, row, info, preferred)
	d.Reasons = append(d.Reasons, why)
	return d, sidecars, preferred
}

// offlineSize is how big a download will be: the file for direct, else the
// decision's bit rate over the file's length.
func offlineSize(row db.MediaFile, durationMs int64, d playback.Decision) (int64, bool) {
	if d.Protocol == "file" || d.Bitrate <= 0 || durationMs <= 0 {
		return row.Size, d.Protocol != "file"
	}
	return d.Bitrate * durationMs / 8000, true
}

// stepProfile caps a profile to a download quality; original leaves it.
func stepProfile(quality string, p playback.Profile) playback.Profile {
	if quality == "" || quality == "original" {
		return p
	}
	return playback.StepByID(quality).Cap(p)
}

// downloadable refuses trailers: they play from the movie's page, not
// offline (PLAN-TRAILERS.md §5.2).
func downloadable(f db.MediaFile) error {
	if f.ExtraKind != nil {
		return huma.Error409Conflict("trailers aren't downloaded")
	}
	return nil
}

func registerOffline(h huma.API, r *chi.Mux, deps Deps) {
	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "getOfflineSettings",
		Method:      http.MethodGet,
		Path:        Prefix + "/settings/offline",
		Summary:     "Who may download to watch offline (admin)",
		Tags:        []string{"offline"},
	}), func(ctx context.Context, _ *struct{}) (*OfflineSettingsOutput, error) {
		return &OfflineSettingsOutput{Body: OfflineSettings{Access: offline.Access(ctx, deps.Settings)}}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "updateOfflineSettings",
		Method:      http.MethodPut,
		Path:        Prefix + "/settings/offline",
		Summary:     "Change who may download to watch offline (admin)",
		Description: "Someone who may no longer download keeps their downloads until their device next syncs, which deletes them.",
		Tags:        []string{"offline"},
	}), func(ctx context.Context, in *UpdateOfflineSettingsInput) (*OfflineSettingsOutput, error) {
		if err := offline.SetAccess(ctx, deps.Settings, in.Body.Access); err != nil {
			if errors.Is(err, offline.ErrBadAccess) {
				return nil, huma.Error422UnprocessableEntity(err.Error())
			}
			return nil, err
		}
		return &OfflineSettingsOutput{Body: OfflineSettings{Access: offline.Access(ctx, deps.Settings)}}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "planOfflineDownload",
		Method:      http.MethodPost,
		Path:        Prefix + "/files/{id}/offline/plan",
		Summary:     "How a file would download at each quality",
		Description: "Runs the playback decision for the device's profile at each download quality, as createOfflineDownload would, with each one's size (the file's, or about this much). Starts nothing. 403 when this person may not download.",
		Tags:        []string{"offline"},
	}, func(ctx context.Context, in *OfflinePlanInput) (*OfflinePlanOutput, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		if !mayDownload(ctx, deps, u) {
			return nil, huma.Error403Forbidden("downloads are off for you on this server")
		}
		row, _, info, err := playableFile(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		if err := downloadable(row); err != nil {
			return nil, err
		}
		prefs, err := loadPreferences(ctx, deps, u.ID)
		if err != nil {
			return nil, err
		}
		out := OfflinePlan{FileID: row.ID, DurationMs: info.DurationMs, Qualities: []OfflineQuality{}}
		for _, q := range offlineQualities {
			d, _, _ := offlineDecidePreferred(ctx, deps, row, info, playback.Request{Profile: stepProfile(q, in.Body.Profile), Playlist: "vod"}, prefs.Audio.Languages)
			size, about := offlineSize(row, info.DurationMs, d)
			out.Qualities = append(out.Qualities, OfflineQuality{Quality: q, Mode: string(d.Mode), Protocol: d.Protocol, SizeBytes: size, SizeEstimated: about,
				Reasons: d.Reasons, Video: d.Video, Audio: d.Audio})
			if q == "original" {
				out.AudioTracks, out.Subtitles = d.AudioTracks, d.Subtitles
			}
		}
		return &OfflinePlanOutput{Body: out}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID:   "createOfflineDownload",
		Method:        http.MethodPost,
		Path:          Prefix + "/files/{id}/offline",
		Summary:       "Download a file to watch offline",
		Description:   "Records the download for this person and device and answers with the URL to fetch and the decision the offline player needs. A device downloading a file again replaces its earlier download of it. protocol file: fetch url with Range. protocol hls: fetch the master playlist, its media playlists, the init segment and every segment in order; the master answers 503 with Retry-After while the file's keyframes are read, and segments 503 with Retry-After while the transcoder is busy. Every fetch carries an offline token (createOfflineToken). 403 when this person may not download.",
		Tags:          []string{"offline"},
		DefaultStatus: http.StatusCreated,
	}, func(ctx context.Context, in *CreateOfflineInput) (*OfflineDownloadOutput, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		if !mayDownload(ctx, deps, u) {
			return nil, huma.Error403Forbidden("downloads are off for you on this server")
		}
		if strings.TrimSpace(in.Body.Client.DeviceID) == "" {
			return nil, huma.Error422UnprocessableEntity("client.deviceId is required")
		}
		row, _, info, err := playableFile(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		if err := downloadable(row); err != nil {
			return nil, err
		}
		quality := cmp.Or(in.Body.Quality, "original")
		req := playback.Request{Profile: stepProfile(quality, in.Body.Profile), AudioIndex: in.Body.AudioIndex, SubtitleID: in.Body.SubtitleID,
			BurnIn: in.Body.BurnIn, Playlist: "vod", Quality: quality, Client: &in.Body.Client}
		prefs, err := loadPreferences(ctx, deps, u.ID)
		if err != nil {
			return nil, err
		}
		d, _, req := offlineDecidePreferred(ctx, deps, row, info, req, prefs.Audio.Languages)
		if d.Protocol == "hls" && deps.Playback == nil {
			return nil, huma.Error503ServiceUnavailable("transcoder not available")
		}
		size, _ := offlineSize(row, info.DurationMs, d)
		stored, err := json.Marshal(offlineRequest{Request: req, FileID: row.ID})
		if err != nil {
			return nil, err
		}
		itemType, ids, err := itemsForFile(ctx, deps, row)
		if err != nil {
			return nil, err
		}
		var itemID int64
		if len(ids) > 0 {
			itemID = ids[0]
		} else {
			itemType = ""
		}
		snapshot, err := json.Marshal(historyItem(ctx, deps, row))
		if err != nil {
			return nil, err
		}
		dev := clientDevice(requestFrom(ctx), &in.Body.Client)
		fileID := row.ID
		var rec db.OfflineDownload
		err = deps.Queries.Transaction(ctx, func(q *db.Queries) error {
			if err := q.DeleteDeviceFileDownloads(ctx, db.DeleteDeviceFileDownloadsParams{UserID: u.ID, DeviceKey: dev.Key, MediaFileID: &fileID}); err != nil {
				return err
			}
			var err error
			rec, err = q.CreateOfflineDownload(ctx, db.CreateOfflineDownloadParams{
				PublicID: offline.NewID(), UserID: u.ID, DeviceKey: dev.Key, DeviceName: dev.Name, MediaFileID: &fileID,
				ItemType: itemType, ItemID: itemID, Item: string(snapshot), FileSize: row.Size, FileMtime: row.Mtime,
				Mode: string(d.Mode), Quality: quality, Request: string(stored), SizeBytes: size,
			})
			return err
		})
		if err != nil {
			return nil, err
		}
		// Point the decision at the offline routes.
		d.URL, d.Protocol = offlineURL(rec.PublicID, rec.Mode)
		d.PlayID, d.SessionID, d.StartMs = "", "", 0
		if d.Protocol == "hls" {
			d.Playlist, d.MimeType = "vod", "application/vnd.apple.mpegurl"
			// Start reading a copied file's keyframes now: the master
			// playlist waits for them.
			if d.Video.Copy && deps.Keyframes != nil {
				_, _ = deps.Keyframes.Get(ctx, row.Path, row.Size, row.Mtime)
			}
		}
		for i := range d.Subtitles {
			if t := &d.Subtitles[i]; t.Format != "image" {
				t.URL = fmt.Sprintf("%s/offline/%s/subtitles/%s.%s", Prefix, rec.PublicID, t.ID, subtitleExt(t.Format))
			}
		}
		prepareSubtitles(deps, row, d)
		addMarkers(ctx, deps, row, info, &d)
		if w := watchForFile(ctx, deps, u, row); w != nil && !w.Completed {
			d.ResumeMs = w.PositionMs
		}
		view := offlineView(rec, recordState{state: rec.State})
		view.Decision = &d
		deps.Log.Info("offline download made", "download", rec.PublicID, "file", row.ID, "user", u.ID, "device", dev.Name, "mode", d.Mode, "quality", quality)
		return &OfflineDownloadOutput{Status: http.StatusCreated, Body: view}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "listOfflineDownloads",
		Method:      http.MethodGet,
		Path:        Prefix + "/offline/downloads",
		Summary:     "Downloads and what is being downloaded now",
		Description: "Your downloads on every device, newest first; admins may ask for everyone's with all=true (Activity › Downloads), from any network, as with other activity.",
		Tags:        []string{"offline"},
	}, func(ctx context.Context, in *ListOfflineInput) (*OfflineDownloadListOutput, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		out := OfflineDownloadList{Items: []OfflineDownload{}, Transfers: []OfflineTransfer{}}
		scope := u.ID
		if in.All {
			if !u.IsAdmin {
				return nil, huma.Error403Forbidden("admin only")
			}
			scope = 0
			rows, err := deps.Queries.ListAllOfflineDownloads(ctx)
			if err != nil {
				return nil, err
			}
			access := offline.Access(ctx, deps.Settings)
			for _, r := range rows {
				st, err := offlineState(ctx, deps.Queries, r.OfflineDownload, offline.Allowed(access, r.UserIsAdmin == 1))
				if err != nil {
					return nil, err
				}
				v := offlineView(r.OfflineDownload, st)
				v.User, v.RevokedByName = &OfflineUser{ID: r.OfflineDownload.UserID, Name: r.UserName}, r.RevokedByName
				out.Items = append(out.Items, v)
			}
		} else {
			rows, err := deps.Queries.ListUserOfflineDownloads(ctx, u.ID)
			if err != nil {
				return nil, err
			}
			may := mayDownload(ctx, deps, u)
			for _, r := range rows {
				st, err := offlineState(ctx, deps.Queries, r, may)
				if err != nil {
					return nil, err
				}
				v := offlineView(r, st)
				v.User = &OfflineUser{ID: u.ID, Name: u.Name}
				out.Items = append(out.Items, v)
			}
		}
		for _, t := range deps.Offline.List(scope) {
			out.Transfers = append(out.Transfers, transferView(deps, t))
		}
		return &OfflineDownloadListOutput{Body: out}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID:   "deleteOfflineDownload",
		Method:        http.MethodDelete,
		Path:          Prefix + "/offline/downloads/{id}",
		Summary:       "Remove or revoke a download",
		Description:   "The owner's app removes its record once the copy is gone from the device. An admin revokes anyone else's (or, with revoke=true, their own): the record stays, revoked, and the device deletes the copy when it next syncs.",
		Tags:          []string{"offline"},
		DefaultStatus: http.StatusNoContent,
	}, func(ctx context.Context, in *DeleteOfflineInput) (*struct{}, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		rec, err := deps.Queries.GetOfflineDownload(ctx, in.ID)
		if err != nil || (rec.UserID != u.ID && !u.IsAdmin) {
			return nil, notFound(orErr(err, sql.ErrNoRows), "download not found")
		}
		if rec.UserID == u.ID && !in.Revoke {
			err = deps.Queries.DeleteOfflineDownload(ctx, rec.ID)
		} else {
			if _, err := requireAdmin(ctx); err != nil {
				return nil, err
			}
			admin := u.ID
			err = deps.Queries.RevokeOfflineDownload(ctx, db.RevokeOfflineDownloadParams{RevokedBy: "admin", RevokedByUser: &admin, ID: rec.ID})
			deps.Log.Info("offline download revoked", "download", rec.PublicID, "by", u.ID)
		}
		if err != nil {
			return nil, err
		}
		endTransfer(deps, rec.PublicID)
		return nil, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "createOfflineToken",
		Method:      http.MethodPost,
		Path:        Prefix + "/offline/downloads/{id}/token",
		Summary:     "Get a token for one download's media",
		Description: "With the device's session. The token opens this download's routes only, for a few hours; ask again while the download stands. 403 when it was revoked or this person may no longer download, 410 when its file was replaced or deleted.",
		Tags:        []string{"offline"},
	}, func(ctx context.Context, in *OfflineIDInput) (*OfflineTokenOutput, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		session, ok := auth.SessionTokenFrom(ctx)
		if !ok {
			return nil, huma.Error401Unauthorized("sign in with the device's session to get an offline token")
		}
		rec, err := deps.Queries.GetOfflineDownload(ctx, in.ID)
		if err != nil || rec.UserID != u.ID {
			return nil, notFound(orErr(err, sql.ErrNoRows), "download not found")
		}
		may := mayDownload(ctx, deps, u)
		if !may {
			revokeForAccess(ctx, deps, u.ID)
		}
		st, err := offlineState(ctx, deps.Queries, rec, may)
		if err != nil {
			return nil, err
		}
		if err := offlineStateError(st); err != nil {
			return nil, err
		}
		tok, exp, err := deps.Auth.IssueOfflineToken(ctx, session, rec.PublicID)
		if err != nil {
			return nil, huma.Error401Unauthorized("sign in required")
		}
		return &OfflineTokenOutput{Body: OfflineToken{Token: tok, ExpiresAt: exp.UTC(), Param: auth.QueryParam}}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "syncOffline",
		Method:      http.MethodPost,
		Path:        Prefix + "/offline/sync",
		Summary:     "Sync a device's downloads and offline progress",
		Description: "The device says what it holds and sends the progress and marks it made offline, each with when it happened (corrected by sentAt against the server's clock). The newest local change wins: an entry older than a play or mark made since on the server is dropped (newer). Offline viewing changes watch state only, never History. The reply lists every download of this device with its state. 401 when the device was signed out, which means delete every download.",
		Tags:        []string{"offline"},
	}, func(ctx context.Context, in *OfflineSyncInput) (*OfflineSyncOutput, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		if strings.TrimSpace(in.Body.Client.DeviceID) == "" {
			return nil, huma.Error422UnprocessableEntity("client.deviceId is required")
		}
		for i, p := range in.Body.Progress {
			if (p.PositionMs == nil) == (p.Watched == nil) {
				return nil, huma.Error422UnprocessableEntity(fmt.Sprintf("progress[%d] needs positionMs or watched", i))
			}
		}
		now := time.Now().UTC()
		dev := clientDevice(requestFrom(ctx), &in.Body.Client)
		out := OfflineSync{ServerTime: now, Downloads: []OfflineDownload{}, Progress: make([]OfflineSyncResult, len(in.Body.Progress))}
		// Progress first: entries for a download removed in this same sync
		// still count.
		if err := syncProgress(ctx, deps, u, in.Body.Progress, in.Body.SentAt, now, out.Progress); err != nil {
			return nil, err
		}
		out.Offline = mayDownload(ctx, deps, u)
		if !out.Offline {
			revokeForAccess(ctx, deps, u.ID)
		}
		for _, d := range in.Body.Downloads {
			rec, err := deps.Queries.GetOfflineDownload(ctx, d.ID)
			if err != nil || rec.UserID != u.ID || rec.DeviceKey != dev.Key {
				continue
			}
			if d.DeviceState == "removed" {
				if err := deps.Queries.DeleteOfflineDownload(ctx, rec.ID); err != nil {
					return nil, err
				}
				endTransfer(deps, rec.PublicID)
				continue
			}
			if err := deps.Queries.SetOfflineDeviceState(ctx, db.SetOfflineDeviceStateParams{DeviceState: d.DeviceState, SizeBytes: d.SizeBytes, ID: rec.ID}); err != nil {
				return nil, err
			}
			// A device back at queued after a restart has started over.
			if d.DeviceState == "queued" && rec.State == offlineRestart {
				if err := deps.Queries.RestartOfflineDownload(ctx, rec.ID); err != nil {
					return nil, err
				}
			}
		}
		if err := deps.Queries.TouchDeviceOfflineDownloads(ctx, db.TouchDeviceOfflineDownloadsParams{UserID: u.ID, DeviceKey: dev.Key}); err != nil {
			return nil, err
		}
		recs, err := deps.Queries.ListDeviceOfflineDownloads(ctx, db.ListDeviceOfflineDownloadsParams{UserID: u.ID, DeviceKey: dev.Key})
		if err != nil {
			return nil, err
		}
		for _, rec := range recs {
			st, err := offlineState(ctx, deps.Queries, rec, out.Offline)
			if err != nil {
				return nil, err
			}
			out.Downloads = append(out.Downloads, offlineView(rec, st))
		}
		return &OfflineSyncOutput{Body: out}, nil
	})

	// The media, on chi: Range requests and segments do not fit huma's
	// typed responses.
	r.Get(Prefix+"/offline/{id}/stream", serveOfflineStream(deps))
	r.Head(Prefix+"/offline/{id}/stream", serveOfflineStream(deps))
	r.Get(Prefix+"/offline/{id}/hls/{name}", serveOfflineHLS(deps))
	r.Get(Prefix+"/offline/{id}/subtitles/{track}", serveOfflineSubtitle(deps))
}

// orErr is err, or other when err is nil (a record of someone else's
// reads as not found).
func orErr(err, other error) error {
	if err != nil {
		return err
	}
	return other
}

// revokeForAccess revokes everything a person downloaded once they may no
// longer download.
func revokeForAccess(ctx context.Context, deps Deps, userID int64) {
	if n, err := deps.Queries.RevokeUserOfflineDownloads(ctx, userID); err != nil {
		deps.Log.Warn("could not revoke downloads", "user", userID, "err", err)
	} else if n > 0 {
		deps.Log.Info("offline downloads revoked: access withdrawn", "user", userID, "downloads", n)
	}
}

// endTransfer forgets a download's transfer and stops its session.
func endTransfer(deps Deps, id string) {
	deps.Offline.Forget(id)
	if deps.Playback != nil {
		deps.Playback.Stop(downloadSessionID(id))
	}
}

func downloadSessionID(record string) string { return "o-" + record }

// offlineStateError is the reply to a fetch or token for a download that
// is no longer ready.
func offlineStateError(st recordState) error {
	switch st.state {
	case offlineRevoked:
		return huma.Error403Forbidden("this download was revoked")
	case offlineReplaced, offlineGone:
		return huma.Error410Gone("the file of this download was " + st.state)
	}
	return nil
}

// syncProgress applies offline progress, oldest first, and writes each
// entry's outcome to out at its index.
func syncProgress(ctx context.Context, deps Deps, u auth.User, entries []OfflineSyncProgress, sentAt, now time.Time, out []OfflineSyncResult) error {
	// The device's clock may be off: move each time by how far its clock
	// was from the server's when it sent them, and never past now.
	var skew time.Duration
	if !sentAt.IsZero() {
		skew = now.Sub(sentAt)
	}
	at := make([]time.Time, len(entries))
	order := make([]int, len(entries))
	for i, p := range entries {
		at[i] = p.At.Add(skew)
		if at[i].After(now) {
			at[i] = now
		}
		order[i] = i
	}
	slices.SortStableFunc(order, func(a, b int) int { return at[a].Compare(at[b]) })
	recs := map[string]*db.OfflineDownload{}
	for _, i := range order {
		p := entries[i]
		res := OfflineSyncResult{DownloadID: p.DownloadID, FileID: p.FileID, Outcome: string(watchstate.Unavailable)}
		outcome, ws, err := applyOffline(ctx, deps, u, p, at[i], recs)
		if err != nil {
			return err
		}
		if outcome != "" {
			res.Outcome, res.WatchState = string(outcome), ws
		}
		out[i] = res
	}
	return nil
}

// applyOffline applies one offline entry: a position or a mark on the
// file's items. A file deleted from the server, or an item gone, drops it.
func applyOffline(ctx context.Context, deps Deps, u auth.User, p OfflineSyncProgress, at time.Time, recs map[string]*db.OfflineDownload) (watchstate.Outcome, *WatchState, error) {
	f, err := deps.Queries.GetMediaFile(ctx, p.FileID)
	if errors.Is(err, sql.ErrNoRows) {
		return watchstate.Unavailable, nil, nil
	}
	if err != nil {
		return "", nil, err
	}
	// A download whose file is gone syncs no progress (PLAN-DOWNLOADS.md §5).
	if p.DownloadID != "" {
		rec, ok := recs[p.DownloadID]
		if !ok {
			if r, err := deps.Queries.GetOfflineDownload(ctx, p.DownloadID); err == nil && r.UserID == u.ID {
				rec = &r
			}
			recs[p.DownloadID] = rec
		}
		if rec != nil {
			st, err := offlineState(ctx, deps.Queries, *rec, true)
			if err != nil {
				return "", nil, err
			}
			if st.state == offlineGone {
				return watchstate.Unavailable, nil, nil
			}
		}
	}
	itemType, ids, err := itemsForFile(ctx, deps, f)
	if err != nil {
		return "", nil, err
	}
	if len(ids) == 0 {
		return watchstate.Unavailable, nil, nil
	}
	var outcomes []watchstate.Outcome
	if p.Watched != nil {
		outcomes, err = deps.Watch.MarkAt(ctx, u.ID, itemType, ids, *p.Watched, at)
	} else {
		duration := p.DurationMs
		if duration == 0 && f.DurationMs != nil {
			duration = *f.DurationMs
		}
		position, completed := *p.PositionMs, false
		if duration > 0 && position >= watchedPoint(ctx, deps, f.ID, duration, 0, 0) {
			position, completed = 0, true
		}
		outcomes, err = deps.Watch.ReportAt(ctx, watchstate.Progress{UserID: u.ID, ItemType: itemType, ItemIDs: ids, FileID: f.ID,
			PositionMs: position, DurationMs: duration, Completed: completed}, at)
	}
	if err != nil {
		return "", nil, err
	}
	// One outcome for the entry: applied if it changed any of the items.
	outcome := outcomes[0]
	if slices.Contains(outcomes, watchstate.Applied) {
		outcome = watchstate.Applied
	}
	var ws *WatchState
	if w, err := deps.Queries.GetWatchState(ctx, db.GetWatchStateParams{UserID: u.ID, ItemType: itemType, ItemID: ids[0]}); err == nil {
		v := watchFromDB(w)
		ws = &v
	}
	return outcome, ws, nil
}
