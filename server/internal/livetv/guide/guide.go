// Package guide keeps programme listings for live channels: it fetches
// SiliconDust's XMLTV feed, matches it to the lineup, stores it and answers
// window queries (PLAN-LIVE-TV-GUIDE.md §5). Listings are off until an
// admin turns them on, and a guide outage never stops a channel playing.
package guide

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/guide/art"
	"watchmymedia/server/internal/settings"
)

const (
	keyEnabled   = "livetv.guide.enabled"
	keyTMDB      = "livetv.guide.tmdb"
	keyEmail     = "livetv.guide.email"
	keyStatus    = "livetv.guide.status"
	keyRefreshes = "livetv.guide.refreshes"

	// StaleAfter is how old listings are when they are labelled out of date.
	StaleAfter = 36 * time.Hour
	// MaxWindow is the most a guide request covers.
	MaxWindow = 6 * time.Hour
	// RefreshesPerDay bounds an admin's Refresh now.
	RefreshesPerDay = 4
)

var (
	ErrOff     = errors.New("listings are turned off")
	ErrLimit   = errors.New("listings were refreshed 4 times in the last day; try again later")
	ErrMissing = errors.New("channel not found")
	ErrEmail   = errors.New("enter an email address")
)

// States a Status reports.
const (
	StateOff         = "off"
	StateWaiting     = "waiting"
	StateFetching    = "fetching"
	StateOK          = "ok"
	StateStale       = "stale"
	StateFirmware    = "firmware"
	StateRefused     = "refused"
	StateUnavailable = "unavailable"
	StateFailed      = "failed"
	StateNoTuner     = "no_tuner"
)

// Status is what admins see of the listings.
type Status struct {
	State         string     `json:"state" doc:"off, waiting, fetching, ok, stale, firmware (the tuner's firmware gives no DeviceAuth), refused (SiliconDust refused the tuner or account), unavailable (SiliconDust is down or busy), failed or no_tuner. Other values may follow."`
	Message       string     `json:"message,omitempty" required:"false" doc:"What the state means and what to do about it."`
	LastAttemptAt *time.Time `json:"lastAttemptAt,omitempty" required:"false"`
	LastSuccessAt *time.Time `json:"lastSuccessAt,omitempty" required:"false"`
	NextAt        *time.Time `json:"nextAt,omitempty" required:"false" doc:"When the next fetch is due."`
	Programmes    int        `json:"programmes"`
	ListedThrough *time.Time `json:"listedThrough,omitempty" required:"false" doc:"When the last listing ends."`
	Matched       int        `json:"matched" doc:"Channels the feed lists."`
	Flagged       int        `json:"flagged" doc:"Channels matched by number whose names disagree."`
	Missing       int        `json:"missing" doc:"Channels the feed doesn't list; they still play."`
	Overlaps      int        `json:"overlaps" doc:"Programmes in the last fetch moved to start when the one before ended."`
	Dropped       int        `json:"dropped" doc:"Programmes in the last fetch left out as invalid."`
	RefreshesLeft int        `json:"refreshesLeft" doc:"How many more times Refresh now works today."`
}

// ChannelMatch is how one lineup channel matched the feed.
type ChannelMatch struct {
	ID       string `json:"id"`
	Number   string `json:"number"`
	Name     string `json:"name"`
	FeedName string `json:"feedName,omitempty" required:"false" doc:"The feed's name for the channel."`
	LogoURL  string `json:"logoUrl,omitempty" required:"false"`
	Match    string `json:"match" doc:"pending (no fetch yet), number (matched by number; the tuner gave no name to check), name (the names agree too), differs (the names disagree) or missing (the feed doesn't list it). Other values may follow."`
	Listings bool   `json:"listings" doc:"The admin's switch for this channel's listings."`
}

// Settings is the listings section of Live TV's settings.
type Settings struct {
	Enabled  bool           `json:"enabled" doc:"Fetch listings from SiliconDust about once a day."`
	TMDB     bool           `json:"tmdb" doc:"Use TMDB pictures and descriptions matched to the same programme, when TMDB is configured."`
	Email    string         `json:"email" doc:"An HDHomeRun DVR account's email, sent with the tuners' device IDs when their firmware gives no DeviceAuth."`
	Status   Status         `json:"status"`
	Channels []ChannelMatch `json:"channels" nullable:"false"`
}

// State is what viewers see of the listings.
type State struct {
	Enabled       bool
	UpdatedAt     *time.Time
	Stale         bool
	ListedThrough *time.Time
}

// Info is a channel's listings, for decorating channel lists.
type Info struct {
	LogoURL  string
	Listings bool
}

// record is what the settings store keeps between fetches.
type record struct {
	LastAttempt int64  `json:"lastAttempt,omitempty"`
	LastSuccess int64  `json:"lastSuccess,omitempty"`
	Next        int64  `json:"next,omitempty"`
	Error       string `json:"error,omitempty"`
	Failures    int    `json:"failures,omitempty"`
	Overlaps    int    `json:"overlaps,omitempty"`
	Dropped     int    `json:"dropped,omitempty"`
}

// Options configure a Service.
type Options struct {
	Log *slog.Logger
	// Transport carries the feed and picture requests; nil is the standard
	// one. Tests point it at fakes.
	Transport http.RoundTripper
	// ArtDir holds the picture cache: <data_dir>/live-tv-art.
	ArtDir string
	// Now is the clock; nil is time.Now.
	Now func() time.Time
	// Enrich runs after each successful refresh: TMDB matching (G3).
	Enrich func(context.Context) error
	// Bus carries live.favourites.changed and live.reminders.changed to a
	// person's devices (G6); nil sends nothing.
	Bus *events.Bus
}

// Service is the listings.
type Service struct {
	afterSave func(context.Context)
	q         *db.Queries
	store     *settings.Store
	live      *livetv.Service
	log       *slog.Logger
	now       func() time.Time
	client    *http.Client
	Art       *art.Cache

	enrich func(context.Context) error
	bus    *events.Bus

	// mu guards the settings, the record and storing a fetch; a fetch's
	// download runs without it.
	mu       sync.Mutex
	fetching bool
	// epoch goes up when listings are turned off, so a fetch that was under
	// way stores nothing.
	epoch int
	// rematch asks Run to run Enrich: the TMDB switch changed.
	rematch bool
	wake    chan struct{}
}

func New(q *db.Queries, store *settings.Store, live *livetv.Service, opt Options) *Service {
	if opt.Log == nil {
		opt.Log = slog.Default()
	}
	if opt.Now == nil {
		opt.Now = time.Now
	}
	s := &Service{q: q, store: store, live: live, log: opt.Log, now: opt.Now, enrich: opt.Enrich, bus: opt.Bus, wake: make(chan struct{}, 1)}
	s.client = feedClient(opt.Transport)
	s.Art = art.New(q, opt.ArtDir, opt.Transport, opt.Log, opt.Now)
	return s
}

func (s *Service) flag(ctx context.Context, key string, def bool) bool {
	d := "0"
	if def {
		d = "1"
	}
	v, err := s.store.Get(ctx, key, d)
	return err == nil && v == "1"
}

// Enabled says whether listings are on.
func (s *Service) Enabled(ctx context.Context) bool { return s.flag(ctx, keyEnabled, false) }

// TMDB says whether matched TMDB pictures and descriptions may be used.
func (s *Service) TMDB(ctx context.Context) bool { return s.flag(ctx, keyTMDB, true) }

func (s *Service) record(ctx context.Context) record {
	var r record
	if v, err := s.store.Get(ctx, keyStatus, ""); err == nil && v != "" {
		_ = json.Unmarshal([]byte(v), &r)
	}
	return r
}

func (s *Service) saveRecord(ctx context.Context, r record) error {
	b, err := json.Marshal(r)
	if err != nil {
		return err
	}
	return s.store.Set(ctx, keyStatus, string(b))
}

func unixPtr(sec int64) *time.Time {
	if sec == 0 {
		return nil
	}
	t := time.Unix(sec, 0).UTC()
	return &t
}

// State is what the guide reply says about the listings.
func (s *Service) State(ctx context.Context) (State, error) {
	if !s.Enabled(ctx) {
		return State{}, nil
	}
	r := s.record(ctx)
	sum, err := s.q.GuideProgrammeSummary(ctx)
	if err != nil {
		return State{}, err
	}
	st := State{Enabled: true, UpdatedAt: unixPtr(r.LastSuccess), ListedThrough: unixPtr(sum.ListedThrough)}
	st.Stale = r.LastSuccess != 0 && s.now().Sub(time.Unix(r.LastSuccess, 0)) > StaleAfter
	return st, nil
}

// Channels says, per channel id, whether its listings show and its logo.
// It is empty while listings are off.
func (s *Service) Channels(ctx context.Context) (map[string]Info, error) {
	out := map[string]Info{}
	if !s.Enabled(ctx) {
		return out, nil
	}
	rows, err := s.q.ListGuideChannels(ctx)
	if err != nil {
		return nil, err
	}
	listed, err := s.q.ListedChannels(ctx)
	if err != nil {
		return nil, err
	}
	has := map[string]bool{}
	for _, id := range listed {
		has[id] = true
	}
	for _, r := range rows {
		if r.Listings == 0 || r.ProviderID == "" {
			continue
		}
		out[r.ChannelID] = Info{LogoURL: art.URL(r.Logo), Listings: has[r.ChannelID]}
	}
	return out, nil
}

// Available says whether any channel has listings to show, which is what
// capabilities.guide reports.
func (s *Service) Available(ctx context.Context) bool {
	info, err := s.Channels(ctx)
	if err != nil {
		return false
	}
	for _, i := range info {
		if i.Listings {
			return true
		}
	}
	return false
}

// Window returns the programmes overlapping [from, to) on the channels
// whose listings show, by channel id, in start order.
func (s *Service) Window(ctx context.Context, ids []string, from, to time.Time) (map[string][]livetv.Programme, error) {
	out := map[string][]livetv.Programme{}
	info, err := s.Channels(ctx)
	if err != nil || len(info) == 0 {
		return out, err
	}
	shown := make([]string, 0, len(ids))
	for _, id := range ids {
		if _, ok := info[id]; ok {
			shown = append(shown, id)
		}
	}
	if len(shown) == 0 {
		return out, nil
	}
	rows, err := s.q.ListProgrammesWindow(ctx, db.ListProgrammesWindowParams{
		ChannelIds: shown, LowerAt: from.Unix() - 86400, ToAt: to.Unix(), FromAt: from.Unix(),
	})
	if err != nil {
		return nil, err
	}
	for _, r := range rows {
		out[r.ChannelID] = append(out[r.ChannelID], programmeView(r))
	}
	return out, nil
}

// Decorate fills each channel's logo and what is on now and next, while
// listings are on.
func (s *Service) Decorate(ctx context.Context, channels []livetv.Channel) error {
	info, err := s.Channels(ctx)
	if err != nil || len(info) == 0 {
		return err
	}
	ids := make([]string, 0, len(channels))
	for _, c := range channels {
		ids = append(ids, c.ID)
	}
	now := s.now()
	// Four hours is enough to find what's next on nearly every channel
	// without reading a day of listings for each.
	window, err := s.Window(ctx, ids, now, now.Add(4*time.Hour))
	if err != nil {
		return err
	}
	for i := range channels {
		c := &channels[i]
		c.LogoURL = info[c.ID].LogoURL
		for _, p := range window[c.ID] {
			switch {
			case c.Now == nil && !p.Start.After(now):
				c.Now = &p
			case c.Next == nil && p.Start.After(now):
				c.Next = &p
			}
		}
	}
	return nil
}

func programmeView(r db.LiveTvProgramme) livetv.Programme {
	p := livetv.Programme{
		ID: r.ID, SeriesKey: r.SeriesKey, Start: time.Unix(r.StartAt, 0).UTC(), End: time.Unix(r.EndAt, 0).UTC(), Title: r.Title,
		EpisodeTitle: r.EpisodeTitle, EpisodeLabel: r.EpisodeLabel, AirDate: r.AirDate, Kind: r.Kind,
		New: r.IsNew != 0, Live: r.IsLive != 0, Repeat: r.IsRepeat != 0, HD: r.Hd != 0, Rating: r.Rating,
		Description: r.Description, DescriptionSource: r.DescriptionSource,
		PictureURL: art.URL(r.Picture), PictureSource: r.PictureSource, PosterURL: art.URL(r.Poster), StillURL: r.Still,
	}
	// A picture G3 filled in from TMDB is already a path on /api/images.
	if r.PictureSource == "tmdb" {
		p.PictureURL = r.Picture
	}
	if r.Season != nil {
		n := int(*r.Season)
		p.Season = &n
	}
	if r.Episode != nil {
		n := int(*r.Episode)
		p.Episode = &n
	}
	_ = json.Unmarshal([]byte(r.Categories), &p.Categories)
	return p
}

// OnSaved registers work to run after a complete listings transaction. Call before Run.
func (s *Service) OnSaved(fn func(context.Context)) { s.afterSave = fn }
