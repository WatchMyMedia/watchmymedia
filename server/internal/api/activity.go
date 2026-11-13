package api

import (
	"context"
	"encoding/csv"
	"errors"
	"io"
	"net/http"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/netguard"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/probe"
	"watchmymedia/server/internal/settings"
	"watchmymedia/server/internal/upload"
)

// ActivityConfig reads the Activity settings, with Tautulli's defaults.
func ActivityConfig(ctx context.Context, store *settings.Store) activity.Config {
	c := activity.DefaultConfig()
	if store == nil {
		return c
	}
	get := func(key, def string) string {
		v, _ := store.Get(ctx, key, def)
		return v
	}
	if n, err := strconv.Atoi(get(settings.KeyActivityMinPlay, "120")); err == nil && n >= 0 {
		c.MinPlay = time.Duration(n) * time.Second
	}
	c.Group = get(settings.KeyActivityGroup, "1") == "1"
	c.Household = get(settings.KeyActivityHousehold, "0") == "1"
	c.TrimStats = get(settings.KeyActivityTrimStats, "0") == "1"
	if n, err := strconv.Atoi(get(settings.KeyActivityRetention, "0")); err == nil && n >= 0 {
		c.RetentionDays = n
	}
	return c
}

// ActivitySettings is the Activity section of the settings page.
type ActivitySettings struct {
	MinPlaySeconds int  `json:"minPlaySeconds" minimum:"0" maximum:"3600" doc:"Plays shorter than this are not kept unless they reach the credits; 0 keeps every play."`
	GroupPlays     bool `json:"groupPlays" doc:"Join a play that picks up an unfinished one into the same history row."`
	RetentionDays  int  `json:"retentionDays" minimum:"0" maximum:"36500" doc:"Delete plays older than this many days; 0 keeps them."`
	TrimStats      bool `json:"trimStats" doc:"When trimming, drop the daily totals of those days too; off, statistics outlive history."`
	Household      bool `json:"household" doc:"Let everyone see what is playing now (not history or statistics)."`
}

func activitySettings(c activity.Config) ActivitySettings {
	return ActivitySettings{MinPlaySeconds: int(c.MinPlay / time.Second), GroupPlays: c.Group, RetentionDays: c.RetentionDays, TrimStats: c.TrimStats, Household: c.Household}
}

func saveActivitySettings(ctx context.Context, deps Deps, a ActivitySettings) error {
	flag := func(b bool) string {
		if b {
			return "1"
		}
		return "0"
	}
	for k, v := range map[string]string{
		settings.KeyActivityMinPlay:   strconv.Itoa(a.MinPlaySeconds),
		settings.KeyActivityGroup:     flag(a.GroupPlays),
		settings.KeyActivityRetention: strconv.Itoa(a.RetentionDays),
		settings.KeyActivityTrimStats: flag(a.TrimStats),
		settings.KeyActivityHousehold: flag(a.Household),
	} {
		if err := deps.Settings.Set(ctx, k, v); err != nil {
			return err
		}
	}
	if deps.Activity != nil {
		deps.Activity.SetConfig(ActivityConfig(ctx, deps.Settings))
	}
	return nil
}

// viewer is who asks for activity: admins see everyone's from any network,
// other users their own. Reading activity changes nothing, so unlike admin
// settings it does not need an admin network; stopping a play or deleting
// history still does (adminOp).
func viewer(ctx context.Context) (activity.Viewer, error) {
	u, err := currentUser(ctx)
	if err != nil {
		return activity.Viewer{}, err
	}
	return activity.Viewer{UserID: u.ID, Admin: u.IsAdmin}, nil
}

// historyItem is the snapshot of what a file is, for the history.
func historyItem(ctx context.Context, deps Deps, f db.MediaFile) activity.Item {
	lib := f.LibraryID
	it := activity.Item{Type: "file", ItemID: f.ID, LibraryID: &lib, Title: strings.TrimSuffix(filepath.Base(f.Path), filepath.Ext(f.Path))}
	if f.ExtraKind != nil {
		return trailerItem(ctx, deps, f, it)
	}
	r, err := deps.Queries.GetFileItem(ctx, f.ID)
	if err != nil {
		deps.Log.WarnContext(ctx, "could not look up what a file is, for history", "file", f.ID, "err", err)
		return it
	}
	switch {
	case r.MovieID != nil:
		id := *r.MovieID
		return activity.Item{Type: "movie", ItemID: id, MovieID: &id, LibraryID: &lib, Title: deref(r.MovieTitle), Year: r.MovieYear,
			PosterPath: deref(r.MoviePosterPath), ArtPath: deref(r.MovieBackdropPath)}
	case r.EpisodeID != nil && r.SeasonNumber != nil && r.ShowID != nil:
		epID, showID, sn, en := *r.EpisodeID, *r.ShowID, *r.SeasonNumber, deref(r.EpisodeNumber)
		return activity.Item{Type: "episode", ItemID: epID, ShowID: &showID, EpisodeID: &epID, LibraryID: &lib, Title: deref(r.EpisodeTitle),
			ShowTitle: deref(r.ShowTitle), SeasonNumber: &sn, EpisodeNumber: &en, Year: r.ShowYear, PosterPath: deref(r.ShowPosterPath),
			ArtPath: deref(r.EpisodeStillPath)}
	}
	return it
}

// trailerItem is what Now playing shows for a trailer: "<movie> · Trailer"
// with the movie's pictures. It stays a file, so apps don't open the movie
// as the thing being watched.
func trailerItem(ctx context.Context, deps Deps, f db.MediaFile, it activity.Item) activity.Item {
	it.Title = "Trailer"
	if f.ExtraOf == nil {
		return it
	}
	m, err := deps.Queries.GetMovie(ctx, *f.ExtraOf)
	if err != nil {
		deps.Log.WarnContext(ctx, "could not look up a trailer's movie", "file", f.ID, "err", err)
		return it
	}
	it.Title = m.Title + " · Trailer"
	it.PosterPath, it.ArtPath = deref(m.PosterPath), deref(m.BackdropPath)
	return it
}

// streamInfo describes how a decision delivers a file, for activity.
func streamInfo(info *probe.MediaInfo, ext string, d playback.Decision, req playback.Request) activity.Stream {
	src := activity.MediaInfo{Container: strings.TrimPrefix(strings.ToLower(ext), "."), Bitrate: info.BitRate}
	if len(info.Video) > 0 {
		v := info.Video[0]
		src.VideoCodec, src.Width, src.Height, src.HDR = v.Codec, v.Width, v.Height, v.HDR
		if v.BitRate > 0 {
			src.Bitrate = v.BitRate
		}
	}
	for _, a := range info.Audio {
		if a.Index == d.Audio.Index {
			src.AudioCodec, src.AudioChannels, src.AudioLanguage = a.Codec, a.Channels, a.Language
		}
	}
	sub := d.BurnIn
	if sub == nil && req.SubtitleID != "" {
		for i := range d.Subtitles {
			if d.Subtitles[i].ID == req.SubtitleID {
				sub = &d.Subtitles[i]
			}
		}
	}
	if sub != nil {
		src.Subtitle = strings.TrimSpace(strings.ToUpper(sub.Language) + " " + sub.Codec)
	}
	out := src
	if d.Bitrate > 0 {
		out.Bitrate = d.Bitrate
	}
	// What the screen gets: a Dolby Vision file's base layer when that is
	// what is sent, and SDR once tone mapped.
	out.HDR = d.Video.HDR
	if d.Video.ToneMap {
		out.HDR = ""
	}
	out.VideoDecision, out.AudioDecision = "copy", "copy"
	if d.Protocol == "hls" {
		out.Container = "hls"
		if !d.Video.Copy {
			out.VideoDecision, out.VideoCodec = "transcode", d.Video.Codec
			if d.Video.Height > 0 {
				out.Width, out.Height = d.Video.Width, d.Video.Height
			}
		}
		if !d.Audio.Copy {
			out.AudioDecision, out.AudioCodec = "transcode", "aac"
			if d.Audio.Channels > 0 {
				out.AudioChannels = d.Audio.Channels
			}
		}
	}
	switch {
	case d.BurnIn != nil:
		out.SubtitleMode = "burn"
	case sub != nil:
		out.SubtitleMode = "text"
	}
	reasons := d.Reasons
	if reasons == nil {
		reasons = []string{}
	}
	s := activity.Stream{Mode: string(d.Mode), Reasons: reasons, Source: src, Stream: out, Quality: req.Quality, SessionID: d.SessionID}
	s.Adaptive = d.Adaptive
	s.AutoRecovery = req.AutoRecovery && req.Quality == playback.QualityAuto
	s.OriginalBps = info.BitRate
	if s.OriginalBps <= 0 {
		s.OriginalBps = src.Bitrate
	}
	if d.Auto != nil {
		s.Step, s.StepReason = d.Auto.Step, d.Auto.Reason
	}
	return s
}

// playStart describes a new play by the signed-in user of a file.
func playStart(ctx context.Context, deps Deps, r *http.Request, user auth.User, f db.MediaFile, client *playback.Client) activity.Start {
	s := activity.Start{UserID: user.ID, UserName: user.Name, RecordHistory: true, FileID: f.ID, Item: historyItem(ctx, deps, f)}
	if u, err := deps.Queries.GetUser(ctx, user.ID); err == nil {
		s.RecordHistory = u.RecordHistory == 1
	}
	// Trailers show in Now playing but stay out of history and statistics
	// (PLAN-TRAILERS.md §4.6).
	if f.ExtraKind != nil {
		s.RecordHistory = false
	}
	if f.DurationMs != nil {
		s.DurationMs = *f.DurationMs
	}
	// Behind a proxy that is not trusted the client is unknown: no address,
	// and the play counts as remote.
	if info, ok := netguard.From(ctx); ok && !info.Proxied {
		s.IP = info.IP
	}
	s.Device = clientDevice(r, client)
	return s
}

// clientDevice names the device playing, from what the client says of
// itself or else its user agent.
func clientDevice(r *http.Request, client *playback.Client) activity.Device {
	ua := ""
	if r != nil {
		ua = r.UserAgent()
	}
	if client != nil {
		return activity.DeviceFrom(client.DeviceID, client.Name, client.Product, client.Platform, client.Version, ua)
	}
	return activity.DeviceFrom("", "", "", "", "", ua)
}

// requestKey carries the HTTP request into huma handlers that need its
// user agent.
type requestKey struct{}

func requestFrom(ctx context.Context) *http.Request {
	r, _ := ctx.Value(requestKey{}).(*http.Request)
	return r
}

func withRequest(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), requestKey{}, r)))
	})
}

func terminatedError(err error) error {
	if te, ok := errors.AsType[*activity.TerminatedError](err); ok {
		return huma.Error403Forbidden(te.Error())
	}
	return err
}

type LiveActivity struct {
	Plays      []activity.LivePlay `json:"plays" nullable:"false"`
	Summary    activity.Summary    `json:"summary"`
	Transcoder TranscoderLoad      `json:"transcoder"`
	Upload     *upload.Snapshot    `json:"upload,omitempty" required:"false" doc:"Admin-only remote media ceiling, reservations and recent delivered rate; a configured ceiling is not a measured internet speed."`
}

type TranscoderLoad struct {
	Sessions int    `json:"sessions" doc:"ffmpeg sessions running, for plays and downloads: what max limits."`
	Max      int    `json:"max"`
	Encoder  string `json:"encoder"`
	// Downloads is optional: servers before API level 10 don't send it.
	Downloads int                `json:"downloads" required:"false" doc:"Of the sessions, how many are offline downloads' transcodes (they give way to plays)."`
	Cache     *PlaybackCacheLoad `json:"cache,omitempty" required:"false" doc:"Owned temporary playback output and volume headroom; shown to admins only. A missing freeBytes means measurement failed."`
}

type PlaybackCacheLoad struct {
	TemporaryBytes    int64   `json:"temporaryBytes"`
	TemporaryFiles    int     `json:"temporaryFiles"`
	FreeBytes         *uint64 `json:"freeBytes,omitempty" required:"false"`
	MaxCacheBytes     int64   `json:"maxCacheBytes"`
	MaxSessionBytes   int64   `json:"maxSessionBytes"`
	MinFreeBytes      int64   `json:"minFreeBytes"`
	EventAheadMs      int64   `json:"eventAheadMs"`
	EventKeepBehindMs int64   `json:"eventKeepBehindMs"`
	ScanComplete      bool    `json:"scanComplete"`
}

type LiveActivityOutput struct{ Body LiveActivity }

type StopPlayInput struct {
	PlayID string `path:"playId" pattern:"^[0-9a-f]{16}$"`
	Body   struct {
		Message string `json:"message,omitempty" maxLength:"200" doc:"Shown to the viewer."`
	}
}

type HistoryInput struct {
	Q         string `query:"q" maxLength:"200" doc:"Titles (any part of three or more letters, or SxxEyy), user and device names, or the start of an IP address."`
	User      int64  `query:"user" doc:"Admins only; others always see their own."`
	Device    int64  `query:"device"`
	Type      string `query:"type" enum:",movie,episode,file"`
	Library   int64  `query:"library"`
	Mode      string `query:"mode" enum:",direct,remux,audio,transcode"`
	Network   string `query:"network" enum:",local,tailnet,remote"`
	From      string `query:"from" doc:"First day, YYYY-MM-DD in the server's time zone."`
	To        string `query:"to" doc:"Last day, inclusive."`
	Completed string `query:"completed" enum:",true,false"`
	Movie     int64  `query:"movie"`
	Show      int64  `query:"show"`
	Episode   int64  `query:"episode"`
	Grouped   bool   `query:"grouped" default:"true" doc:"One row per group of resumed plays."`
	Cursor    string `query:"cursor"`
	Limit     int    `query:"limit" minimum:"1" maximum:"500" default:"50"`
}

func (in *HistoryInput) filter(v activity.Viewer) activity.Filter {
	f := activity.Filter{Q: in.Q, UserID: in.User, DeviceID: in.Device, Type: in.Type, LibraryID: in.Library, Mode: in.Mode, Network: in.Network,
		From: in.From, To: in.To, MovieID: in.Movie, ShowID: in.Show, EpisodeID: in.Episode, Ungrouped: !in.Grouped, Cursor: in.Cursor, Limit: in.Limit}
	if in.Completed != "" {
		c := in.Completed == "true"
		f.Completed = &c
	}
	if !v.Admin {
		f.UserID = v.UserID
	}
	return f
}

type HistoryOutput struct{ Body activity.Page }

type GroupInput struct {
	GroupID int64 `path:"groupId"`
}

type GroupOutput struct {
	Body struct {
		Items []activity.HistoryRow `json:"items" nullable:"false"`
	}
}

type DeleteHistoryInput struct {
	Body struct {
		IDs    []int64 `json:"ids,omitempty" nullable:"false" doc:"Single plays."`
		Groups []int64 `json:"groups,omitempty" nullable:"false" doc:"Whole groups, by groupId."`
	}
}

type DeleteHistoryOutput struct {
	Body struct {
		Deleted int64 `json:"deleted"`
	}
}

type StatsInput struct {
	Days    int    `query:"days" minimum:"0" maximum:"36500" default:"30" doc:"0 is all time."`
	By      string `query:"by" enum:"plays,time" default:"plays"`
	User    int64  `query:"user" doc:"Admins only; others always see their own."`
	Library int64  `query:"library"`
}

type CardsOutput struct{ Body activity.Cards }

type SeriesInput struct {
	Days   int    `query:"days" minimum:"0" maximum:"36500" default:"30" doc:"0 is all time."`
	By     string `query:"by" enum:"plays,time" default:"plays"`
	Split  string `query:"split" enum:"type,mode,user,network" default:"type"`
	Bucket string `query:"bucket" enum:"day,month" default:"day"`
	User   int64  `query:"user" doc:"Admins only; others always see their own."`
}

type SeriesOutput struct{ Body activity.Series }

type HeatmapInput struct {
	Days int    `query:"days" minimum:"0" maximum:"36500" default:"90" doc:"0 is all time."`
	By   string `query:"by" enum:"plays,time" default:"plays"`
	User int64  `query:"user" doc:"Admins only; others always see their own."`
}

type HeatmapOutput struct{ Body activity.Heatmap }

type ActivityUsersOutput struct {
	Body struct {
		Items []activity.UserSummary `json:"items" nullable:"false"`
	}
}

type ActivityUserOutput struct{ Body activity.UserDetail }

func noActivity() error { return huma.Error503ServiceUnavailable("activity is not available") }

// scopeUser is the user a statistics query may cover: anyone for admins,
// otherwise only the viewer.
func scopeUser(v activity.Viewer, asked int64) int64 {
	if v.Admin {
		return asked
	}
	return v.UserID
}

func registerActivity(h huma.API, deps Deps) {
	huma.Register(h, huma.Operation{
		OperationID: "getActivity",
		Method:      http.MethodGet,
		Path:        Prefix + "/activity",
		Summary:     "What is playing now",
		Description: "Admins see every play; other users see their own, or everyone's (without addresses) when the household setting is on.",
		Tags:        []string{"activity"},
	}, func(ctx context.Context, _ *struct{}) (*LiveActivityOutput, error) {
		v, err := viewer(ctx)
		if err != nil {
			return nil, err
		}
		out := &LiveActivityOutput{Body: LiveActivity{Plays: []activity.LivePlay{}}}
		if deps.Activity != nil {
			out.Body.Plays, out.Body.Summary = deps.Activity.Live(v)
		}
		if v.Admin && deps.Upload != nil {
			s := deps.Upload.Snapshot()
			out.Body.Upload = &s
		}
		if deps.Playback != nil {
			// Running, as the limit counts them: sessions kept for a paused
			// player or a download between requests take no slot.
			out.Body.Transcoder = TranscoderLoad{Sessions: deps.Playback.Running(), Max: deps.Playback.Limit(), Encoder: deps.Playback.Capabilities().Encoder,
				Downloads: deps.Playback.DownloadsRunning()}
			if v.Admin {
				resources, scanErr := deps.Playback.LastResources()
				out.Body.Transcoder.Cache = &PlaybackCacheLoad{TemporaryBytes: resources.TemporaryBytes,
					TemporaryFiles: resources.TemporaryFiles, FreeBytes: resources.FreeBytes,
					MaxCacheBytes: resources.MaxCacheBytes, MaxSessionBytes: resources.MaxSessionBytes,
					MinFreeBytes: resources.MinFreeBytes, ScanComplete: scanErr == nil && resources.ScanComplete}
				out.Body.Transcoder.Cache.EventAheadMs = resources.EventAheadMs
				out.Body.Transcoder.Cache.EventKeepBehindMs = resources.EventKeepBehindMs
			}
		}
		return out, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID:   "stopPlay",
		Method:        http.MethodPost,
		Path:          Prefix + "/activity/{playId}/stop",
		Summary:       "Stop someone's play (admin)",
		Description:   "Stops the play's transcoder and refuses it further bytes; the player exits and shows the message.",
		Tags:          []string{"activity"},
		DefaultStatus: http.StatusNoContent,
	}), func(ctx context.Context, in *StopPlayInput) (*struct{}, error) {
		if deps.Activity == nil {
			return nil, noActivity()
		}
		err := deps.Activity.Terminate(ctx, in.PlayID, strings.TrimSpace(in.Body.Message))
		if errors.Is(err, activity.ErrUnknownPlay) {
			return nil, huma.Error404NotFound("that play is not running")
		}
		return nil, err
	})

	huma.Register(h, huma.Operation{
		OperationID: "listHistory",
		Method:      http.MethodGet,
		Path:        Prefix + "/history",
		Summary:     "Search and page through play history",
		Description: "Newest first, one row per group of resumed plays unless grouped=false. Pass nextCursor back as cursor for the next page. The first page carries the total, counted up to 10,000.",
		Tags:        []string{"activity"},
	}, func(ctx context.Context, in *HistoryInput) (*HistoryOutput, error) {
		v, err := viewer(ctx)
		if err != nil {
			return nil, err
		}
		if deps.Activity == nil {
			return &HistoryOutput{Body: activity.Page{Items: []activity.HistoryRow{}}}, nil
		}
		page, err := deps.Activity.History(ctx, in.filter(v))
		if errors.Is(err, activity.ErrBadCursor) {
			return nil, huma.Error422UnprocessableEntity(err.Error())
		}
		return &HistoryOutput{Body: page}, err
	})

	huma.Register(h, huma.Operation{
		OperationID: "getHistoryGroup",
		Method:      http.MethodGet,
		Path:        Prefix + "/history/{groupId}",
		Summary:     "Every play of a history row, with stream detail",
		Tags:        []string{"activity"},
	}, func(ctx context.Context, in *GroupInput) (*GroupOutput, error) {
		v, err := viewer(ctx)
		if err != nil {
			return nil, err
		}
		out := &GroupOutput{}
		out.Body.Items = []activity.HistoryRow{}
		if deps.Activity == nil {
			return out, nil
		}
		items, err := deps.Activity.Group(ctx, in.GroupID, scopeUser(v, 0))
		if err != nil {
			return nil, err
		}
		if len(items) == 0 {
			return nil, huma.Error404NotFound("no such history row")
		}
		out.Body.Items = items
		return out, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "deleteHistory",
		Method:      http.MethodDelete,
		Path:        Prefix + "/history",
		Summary:     "Delete plays from history (admin)",
		Description: "Daily totals of the days touched are recomputed.",
		Tags:        []string{"activity"},
	}), func(ctx context.Context, in *DeleteHistoryInput) (*DeleteHistoryOutput, error) {
		if deps.Activity == nil {
			return nil, noActivity()
		}
		n, err := deps.Activity.Delete(ctx, in.Body.IDs, in.Body.Groups)
		if err != nil {
			return nil, err
		}
		out := &DeleteHistoryOutput{}
		out.Body.Deleted = n
		return out, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "exportHistory",
		Method:      http.MethodGet,
		Path:        Prefix + "/history.csv",
		Summary:     "Export history as CSV",
		Description: "The same filters as the history list, every matching row up to 100,000.",
		Tags:        []string{"activity"},
		Responses:   map[string]*huma.Response{"200": {Description: "CSV", Content: map[string]*huma.MediaType{"text/csv": {}}}},
	}, func(ctx context.Context, in *HistoryInput) (*huma.StreamResponse, error) {
		v, err := viewer(ctx)
		if err != nil {
			return nil, err
		}
		f := in.filter(v)
		return &huma.StreamResponse{Body: func(hctx huma.Context) {
			hctx.SetHeader("Content-Type", "text/csv; charset=utf-8")
			hctx.SetHeader("Content-Disposition", `attachment; filename="watchmymedia-history-`+time.Now().Format("2006-01-02")+`.csv"`)
			w := csv.NewWriter(hctx.BodyWriter())
			_ = w.Write([]string{"started", "stopped", "user", "device", "platform", "ip", "network", "type", "title", "show", "season", "episode", "year",
				"mode", "played_seconds", "paused_seconds", "position_seconds", "duration_seconds", "percent", "completed", "end_reason", "plays"})
			if deps.Activity != nil {
				err := deps.Activity.Each(ctx, f, 100_000, func(r activity.HistoryRow) error {
					dev, platform := "", ""
					if r.Device != nil {
						dev, platform = r.Device.Name, r.Device.Platform
					}
					num := func(p *int64) string {
						if p == nil {
							return ""
						}
						return strconv.FormatInt(*p, 10)
					}
					return w.Write(csvCells([]string{
						r.GroupStartedAt.Format(time.RFC3339), r.StoppedAt.Format(time.RFC3339), r.User.Name, dev, platform, r.IP, r.Network,
						r.Item.Type, r.Item.Title, r.Item.ShowTitle, num(r.Item.SeasonNumber), num(r.Item.EpisodeNumber), num(r.Item.Year),
						r.Mode, strconv.FormatInt(r.PlayedMs/1000, 10), strconv.FormatInt(r.PausedMs/1000, 10), strconv.FormatInt(r.PositionMs/1000, 10),
						strconv.FormatInt(r.DurationMs/1000, 10), strconv.Itoa(r.Percent), strconv.FormatBool(r.Completed), r.EndReason, strconv.FormatInt(r.GroupCount, 10),
					}))
				})
				if err != nil {
					deps.Log.Error("history export failed", "err", err)
				}
			}
			w.Flush()
		}}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "getStatsCards",
		Method:      http.MethodGet,
		Path:        Prefix + "/stats/cards",
		Summary:     "Top shows, movies, users and devices, peak streams and recent plays",
		Tags:        []string{"activity"},
	}, func(ctx context.Context, in *StatsInput) (*CardsOutput, error) {
		v, err := viewer(ctx)
		if err != nil {
			return nil, err
		}
		if deps.Activity == nil {
			return nil, noActivity()
		}
		c, err := deps.Activity.Cards(ctx, activity.StatsQuery{Days: in.Days, ByTime: in.By == "time", UserID: scopeUser(v, in.User), LibraryID: in.Library})
		return &CardsOutput{Body: c}, err
	})

	huma.Register(h, huma.Operation{
		OperationID: "getStatsSeries",
		Method:      http.MethodGet,
		Path:        Prefix + "/stats/series",
		Summary:     "Plays or time per day or month, split by type, mode, user or network",
		Tags:        []string{"activity"},
	}, func(ctx context.Context, in *SeriesInput) (*SeriesOutput, error) {
		v, err := viewer(ctx)
		if err != nil {
			return nil, err
		}
		if deps.Activity == nil {
			return nil, noActivity()
		}
		split := in.Split
		if split == "user" && !v.Admin {
			split = "type"
		}
		s, err := deps.Activity.Series(ctx, activity.SeriesQuery{Days: in.Days, ByTime: in.By == "time", Split: split, Bucket: in.Bucket, UserID: scopeUser(v, in.User)})
		return &SeriesOutput{Body: s}, err
	})

	huma.Register(h, huma.Operation{
		OperationID: "getStatsHeatmap",
		Method:      http.MethodGet,
		Path:        Prefix + "/stats/heatmap",
		Summary:     "Plays or time by weekday and hour",
		Tags:        []string{"activity"},
	}, func(ctx context.Context, in *HeatmapInput) (*HeatmapOutput, error) {
		v, err := viewer(ctx)
		if err != nil {
			return nil, err
		}
		if deps.Activity == nil {
			return nil, noActivity()
		}
		hm, err := deps.Activity.Heatmap(ctx, in.Days, in.By == "time", scopeUser(v, in.User))
		return &HeatmapOutput{Body: hm}, err
	})

	huma.Register(h, huma.Operation{
		OperationID: "listActivityUsers",
		Method:      http.MethodGet,
		Path:        Prefix + "/activity/users",
		Summary:     "Each user's plays, watch time, devices and last play (admin)",
		Tags:        []string{"activity"},
	}, func(ctx context.Context, _ *struct{}) (*ActivityUsersOutput, error) {
		v, err := viewer(ctx)
		if err != nil {
			return nil, err
		}
		if !v.Admin {
			return nil, huma.Error403Forbidden("admin only")
		}
		if deps.Activity == nil {
			return nil, noActivity()
		}
		items, err := deps.Activity.Users(ctx)
		if err != nil {
			return nil, err
		}
		out := &ActivityUsersOutput{}
		out.Body.Items = items
		return out, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "getActivityUser",
		Method:      http.MethodGet,
		Path:        Prefix + "/activity/users/{id}",
		Summary:     "A user's watch time, devices and favourites",
		Description: "Admins can read anyone's; other users their own.",
		Tags:        []string{"activity"},
	}, func(ctx context.Context, in *UserIDInput) (*ActivityUserOutput, error) {
		v, err := viewer(ctx)
		if err != nil {
			return nil, err
		}
		if !v.Admin && in.ID != v.UserID {
			return nil, huma.Error403Forbidden("admin only")
		}
		if deps.Activity == nil {
			return nil, noActivity()
		}
		d, err := deps.Activity.User(ctx, in.ID)
		if err != nil {
			return nil, notFound(err, "user not found")
		}
		return &ActivityUserOutput{Body: d}, nil
	})
}

// countingWriter counts the bytes a play or a download is sent, in
// batches, and stops writing once add says to (an admin stopped the play).
type countingWriter struct {
	http.ResponseWriter
	add     func(n int64) bool
	pending int64
	stopped bool
}

var errPlayStopped = errors.New("play stopped")

func (c *countingWriter) Write(b []byte) (int, error) {
	if c.stopped {
		return 0, errPlayStopped
	}
	n, err := c.ResponseWriter.Write(b)
	c.count(int64(n))
	return n, err
}

// Unwrap lets http.ResponseController reach the connection.
func (c *countingWriter) Unwrap() http.ResponseWriter { return c.ResponseWriter }

// readFromChunk is how much ReadFrom hands the connection at a time: big
// enough for sendfile to matter, small enough that a stopped play stops soon.
const readFromChunk = 4 << 20

// ReadFrom passes the copy to the connection, which sends a file with
// sendfile, as http.ServeContent expects of a ResponseWriter. It does so a
// chunk at a time, counting each, so a play an admin stops ends within a
// chunk. The source is limited with one io.LimitedReader at a time: the
// connection only looks through one to find the file.
func (c *countingWriter) ReadFrom(src io.Reader) (int64, error) {
	rf, ok := c.ResponseWriter.(io.ReaderFrom)
	if !ok {
		return io.Copy(writerOnly{c}, src)
	}
	inner, remaining := src, int64(-1)
	if lr, ok := src.(*io.LimitedReader); ok {
		inner, remaining = lr.R, max(lr.N, 0)
		defer func() { lr.N = remaining }()
	}
	var total int64
	for remaining != 0 {
		if c.stopped {
			return total, errPlayStopped
		}
		size := int64(readFromChunk)
		if remaining > 0 {
			size = min(size, remaining)
		}
		n, err := rf.ReadFrom(&io.LimitedReader{R: inner, N: size})
		total += n
		if remaining > 0 {
			remaining -= n
		}
		c.count(n)
		if err != nil || n < size {
			return total, err
		}
	}
	return total, nil
}

// writerOnly hides ReadFrom, so io.Copy writes through Write.
type writerOnly struct{ io.Writer }

func (c *countingWriter) count(n int64) {
	c.pending += n
	if c.pending >= 256<<10 {
		c.flush()
	}
}

func (c *countingWriter) flush() {
	if c.pending > 0 {
		if !c.add(c.pending) {
			c.stopped = true
		}
		c.pending = 0
	}
}

// countBytes wraps w to count bytes for a play, when there is one; the
// returned func sends the last batch.
func countBytes(deps Deps, w http.ResponseWriter, play string) (http.ResponseWriter, func()) {
	if deps.Activity == nil || play == "" {
		return w, func() {}
	}
	c := &countingWriter{ResponseWriter: w, add: func(n int64) bool { return deps.Activity.AddBytes(play, n) }}
	return c, c.flush
}
