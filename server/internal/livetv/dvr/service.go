package dvr

import (
	"context"
	"crypto/rand"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"watchmymedia/server/internal/bgwork"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/guide"
	"watchmymedia/server/internal/livetv/guide/art"
	"watchmymedia/server/internal/settings"
)

type Service struct {
	shared sync.Map
	dependencies
	mu      sync.Mutex
	wake    chan struct{}
	pipes   map[sourceKey]*pipeline
	running map[string]*output
	ctx     context.Context
	cancel  context.CancelFunc
	wg      sync.WaitGroup
	closed  bool
	// replan asks the next tick to plan again after a tuner change, which
	// the listener can't do itself under livetv.Service.mu.
	replan atomic.Bool
	// retryAt backs off publishing a finished recording that couldn't be
	// renamed into place, so a lasting failure isn't retried every tick.
	retryAt map[string]retry
}

type retry struct {
	at    time.Time
	delay time.Duration
}

func New(q *db.Queries, store *settings.Store, live *livetv.Service, sessions *livetv.Sessions, opts Options) *Service {
	if opts.Log == nil {
		opts.Log = slog.New(slog.DiscardHandler)
	}
	if opts.FreeBytes == nil {
		opts.FreeBytes = bgwork.FreeBytes
	}
	if opts.Now == nil {
		opts.Now = time.Now
	}
	ctx, cancel := context.WithCancel(context.Background())
	s := &Service{q: q, store: store, live: live, sessions: sessions, opts: opts, wake: make(chan struct{}, 1), pipes: map[sourceKey]*pipeline{}, running: map[string]*output{}, retryAt: map[string]retry{}, ctx: ctx, cancel: cancel}
	live.Listen(func(source, channel, reason string) { s.sourcesChanged(source, channel, reason) })
	if sessions != nil {
		sessions.SetRecordingInput(s.sharing, s.LiveInput)
		sessions.SetSharedSourceInput(s.JoinLive)
	}
	return s
}
func (s *Service) signal() {
	select {
	case s.wake <- struct{}{}:
	default:
	}
	s.publish()
}
func (s *Service) Active() int { s.mu.Lock(); defer s.mu.Unlock(); return len(s.running) }
func (s *Service) Create(ctx context.Context, a Actor, in Create) (Recording, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.Allowed(ctx, a) {
		return Recording{}, ErrDenied
	}
	p, err := s.q.GetProgramme(ctx, in.ProgrammeID)
	if err != nil {
		return Recording{}, ErrMissing
	}
	if p.EndAt <= s.opts.Now().Unix() {
		return Recording{}, ErrInvalid
	}
	ch, err := s.q.GetLiveChannel(ctx, p.ChannelID)
	if err != nil || ch.Enabled == 0 || ch.Protected != 0 {
		return Recording{}, ErrUnavailable
	}
	config, err := s.Settings(ctx)
	if err != nil {
		return Recording{}, err
	}
	before, after := config.PadBefore, config.PadAfter
	if in.PadBefore != nil {
		before = *in.PadBefore
	}
	if in.PadAfter != nil {
		after = *in.PadAfter
	}
	if !validPadding(before, after) || in.Keep < 0 || in.Keep > 10 {
		return Recording{}, ErrInvalid
	}
	rows, err := s.q.ListDVRProgrammeMarks(ctx, []string{p.ID})
	if err != nil {
		return Recording{}, err
	}
	for _, row := range rows {
		r, err := readRecord(row)
		if err != nil {
			return Recording{}, err
		}
		if (in.Scope != "series" || r.Series) && (row.State == "scheduled" || row.State == "conflict" || row.State == "recording" || row.State == "done" || row.State == "partial" || in.Scope == "series" && r.Series && row.State == "skipped") {
			return s.view(r, a), nil
		}
	}
	if p.Kind == "movie" && (config.MovieLibraryID == 0 || config.MoviePath == "") {
		return Recording{}, fmt.Errorf("%w: choose a Movies library in Live TV recording settings before recording films", ErrUnavailable)
	}
	r := s.fromProgramme(p, ch, a, before, after)
	if in.Scope == "series" {
		if p.SeriesKey == "" {
			return Recording{}, ErrInvalid
		}
		newOnly := true
		if in.NewOnly != nil {
			newOnly = *in.NewOnly
		}
		ru := rule{ID: rand.Text(), Kind: "series", Title: p.Title, UserID: &a.UserID, UserName: a.Name, ChannelID: ch.ID, Number: ch.Number, SeriesKey: p.SeriesKey, NewOnly: newOnly, AnyChannel: in.AnyChannel, Keep: in.Keep, PadBefore: before, PadAfter: after, CreatedAt: s.opts.Now().UnixNano()}
		err = putRule(ctx, s.q, ru)
		if err != nil {
			return Recording{}, err
		}
		r.RuleID = ru.ID
		r.Series = true
		if err = s.expandRule(ctx, ru); err != nil {
			return Recording{}, err
		}
		have, e := s.q.ListDVRProgrammeMarks(ctx, []string{p.ID})
		if e != nil {
			return Recording{}, e
		}
		for _, row := range have {
			if row.RuleID != nil && *row.RuleID == ru.ID {
				r, err = readRecord(row)
				break
			}
		}
		// A selected repeat is a representative of the new rule, not an
		// exception to New only. Preserve expansion's other skip decisions too.
		if newOnly && p.IsNew == 0 {
			r.State = "skipped"
			r.Reason = "not_new"
		}
		if r.RuleID == ru.ID && r.ID != "" {
			if err = put(ctx, s.q, r, s.opts.Now().Unix()); err != nil {
				return Recording{}, err
			}
		}
	} else if in.Scope == "once" {
		err = put(ctx, s.q, r, s.opts.Now().Unix())
	} else {
		return Recording{}, ErrInvalid
	}
	if err != nil {
		return Recording{}, err
	}
	if err = s.plan(ctx); err != nil {
		return Recording{}, err
	}
	s.signal()
	r, err = s.get(ctx, r.ID)
	return s.view(r, a), err
}
func (s *Service) fromProgramme(p db.LiveTvProgramme, ch db.LiveTvChannel, a Actor, before, after int) record {
	now := s.opts.Now()
	r := record{ID: rand.Text(), UserID: &a.UserID, UserName: a.Name, SourceID: ch.SourceID, ChannelID: ch.ID, Number: ch.Number, ChannelName: ch.Name, ProgrammeID: p.ID, Title: p.Title, EpisodeTitle: p.EpisodeTitle, Start: unix(p.StartAt), End: unix(p.EndAt), State: "scheduled", PadBefore: before, PadAfter: after, Gaps: []Gap{}, PictureURL: art.URL(p.Picture), SeriesKey: p.SeriesKey, Kind: p.Kind, AirDate: p.AirDate, TMDBKind: p.TmdbKind, Priority: now.UnixNano(), CreatedAt: now.Unix()}
	if p.PictureSource == "tmdb" {
		r.PictureURL = p.Picture
	}
	if p.Season != nil {
		r.Season = new(int(*p.Season))
	}
	if p.Episode != nil {
		r.Episode = new(int(*p.Episode))
	}
	// Only an episode-level enrichment may provide a TV identity. A series
	// card alone can point at a different production with the same name.
	if p.TmdbID != nil && (p.TmdbKind == "movie" || p.Still != "" && p.Season != nil && p.Episode != nil) {
		r.TMDBID = int(*p.TmdbID)
	}
	return r
}
func (s *Service) Manual(ctx context.Context, a Actor, in Manual) (Recording, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.Allowed(ctx, a) {
		return Recording{}, ErrDenied
	}
	now := s.opts.Now()
	in.Name = strings.TrimSpace(in.Name)
	if in.Name == "" || len(in.Name) > 200 || !in.End.After(in.Start) || in.End.Sub(in.Start) > 12*time.Hour || !in.End.After(now) || in.Start.After(now.AddDate(1, 0, 0)) {
		return Recording{}, ErrInvalid
	}
	loc, err := time.LoadLocation(in.Zone)
	if err != nil {
		return Recording{}, ErrInvalid
	}
	ch, err := s.q.GetLiveChannel(ctx, in.ChannelID)
	if err != nil || ch.Enabled == 0 || ch.Protected != 0 {
		return Recording{}, ErrUnavailable
	}
	config, err := s.Settings(ctx)
	if err != nil {
		return Recording{}, err
	}
	// Exact retries are one intent. A deliberate different name, time or
	// channel still creates an independent manual recording.
	pending, err := s.q.ListDVRManualCandidates(ctx, db.ListDVRManualCandidatesParams{UserID: &a.UserID, ChannelID: in.ChannelID, StartAt: in.Start.Unix(), EndAt: in.End.Unix()})
	if err != nil {
		return Recording{}, err
	}
	for _, row := range pending {
		r, e := readRecord(row)
		if e != nil {
			return Recording{}, e
		}
		if r.Title != in.Name {
			continue
		}
		if in.Repeat == "once" && r.RuleID == "" {
			return s.view(r, a), nil
		}
		if in.Repeat != "once" && r.RuleID != "" {
			row, e := s.q.GetDVRRule(ctx, r.RuleID)
			if e != nil {
				return Recording{}, e
			}
			ru, e := readRule(row)
			if e != nil {
				return Recording{}, e
			}
			if ru.Kind == "manual" && ru.Repeat == in.Repeat && ru.Zone == in.Zone {
				return s.view(r, a), nil
			}
		}
	}
	p := db.LiveTvProgramme{Title: in.Name, StartAt: in.Start.Unix(), EndAt: in.End.Unix()}
	r := s.fromProgramme(p, ch, a, config.PadBefore, config.PadAfter)
	r.ProgrammeID = ""
	if in.Repeat != "once" {
		local := in.Start.In(loc)
		days := 0
		switch in.Repeat {
		case "daily":
			days = 127
		case "weekdays":
			days = 62
		case "weekly":
			days = 1 << int(local.Weekday())
		default:
			return Recording{}, ErrInvalid
		}
		ru := rule{ID: rand.Text(), Kind: "manual", Title: in.Name, UserID: &a.UserID, UserName: a.Name, ChannelID: ch.ID, Number: ch.Number, PadBefore: config.PadBefore, PadAfter: config.PadAfter, Repeat: in.Repeat, Days: days, Zone: in.Zone, Start: &in.Start, End: &in.End, CreatedAt: now.UnixNano(), LocalMinute: local.Hour()*60 + local.Minute(), DurationSeconds: int64(in.End.Sub(in.Start).Seconds()), FirstDate: local.Format("2006-01-02")}
		// Refuse before saving anything, so a refused request leaves no
		// rule behind for a retry to duplicate.
		if from, until := ru.window(now); len(ManualTimes(from, ru.LocalMinute, ru.Days, ru.Zone, until)) == 0 {
			return Recording{}, ErrInvalid
		}
		if err = putRule(ctx, s.q, ru); err != nil {
			return Recording{}, err
		}
		if err = s.expandRule(ctx, ru); err != nil {
			return Recording{}, err
		}
		rows, e := s.q.ListDVRByRule(ctx, &ru.ID)
		if e != nil || len(rows) == 0 {
			return Recording{}, ErrInvalid
		}
		r, err = readRecord(rows[0])
		if err != nil {
			return Recording{}, err
		}
	} else if err = put(ctx, s.q, r, now.Unix()); err != nil {
		return Recording{}, err
	}
	if err = s.plan(ctx); err != nil {
		return Recording{}, err
	}
	s.signal()
	r, err = s.get(ctx, r.ID)
	return s.view(r, a), err
}
func (s *Service) Update(ctx context.Context, id string, a Actor, in Update) (Recording, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	r, err := s.get(ctx, id)
	if err != nil {
		return Recording{}, err
	}
	if !owner(r.UserID, a) {
		return Recording{}, ErrDenied
	}
	if r.State != "scheduled" && r.State != "conflict" && r.State != "gone" {
		return Recording{}, ErrInvalid
	}
	if in.PadBefore != nil {
		r.PadBefore = *in.PadBefore
	}
	if in.PadAfter != nil {
		r.PadAfter = *in.PadAfter
	}
	if !validPadding(r.PadBefore, r.PadAfter) {
		return Recording{}, ErrInvalid
	}
	var other *record
	if in.Prefer && in.Over != "" {
		// The chosen clash gives way and every other recording keeps its
		// tuner: the two swap places in the plan. A blocker always ranks
		// ahead of the recording it blocks, so the swap moves this one up.
		o, e := s.get(ctx, in.Over)
		if e != nil {
			return Recording{}, ErrMissing
		}
		if r.State != "conflict" || o.ID == r.ID || o.State != "scheduled" || o.SourceID != r.SourceID {
			return Recording{}, ErrInvalid
		}
		if o.Priority < r.Priority {
			r.Priority, o.Priority = o.Priority, r.Priority
		} else {
			r.Priority = o.Priority - 1
		}
		other = &o
	} else if in.Prefer {
		r.Priority = -s.opts.Now().UnixNano()
	}
	err = s.q.Transaction(ctx, func(q *db.Queries) error {
		if other != nil {
			if e := put(ctx, q, *other, s.opts.Now().Unix()); e != nil {
				return e
			}
		}
		return put(ctx, q, r, s.opts.Now().Unix())
	})
	if err != nil {
		return Recording{}, err
	}
	if err = s.plan(ctx); err != nil {
		return Recording{}, err
	}
	s.signal()
	r, err = s.get(ctx, id)
	return s.view(r, a), err
}
func (s *Service) Cancel(ctx context.Context, id string, a Actor) error {
	s.mu.Lock()
	r, err := s.get(ctx, id)
	if err != nil {
		s.mu.Unlock()
		return err
	}
	if !owner(r.UserID, a) {
		s.mu.Unlock()
		return ErrDenied
	}
	if out := s.running[id]; out != nil {
		out.mu.Lock()
		snapshot := out.r
		finishing := out.finishing
		out.mu.Unlock()
		if finishing {
			// It is ending now; its own final write keeps what it has.
			s.mu.Unlock()
			return nil
		}
		snapshot.Reason = "stop_requested"
		if err = put(ctx, s.q, snapshot, s.opts.Now().Unix()); err != nil {
			s.mu.Unlock()
			return err
		}
		out.mu.Lock()
		out.r.Reason = "stop_requested"
		out.reason = "stopped"
		out.stop = true
		out.mu.Unlock()
		s.mu.Unlock()
		s.signal()
		return nil
	}
	if r.State == "recording" {
		// Startup recovery may not have attached a local writer yet. The
		// durable request still wins over a scheduler's previously read row.
		r.Reason = "stop_requested"
		err = put(ctx, s.q, r, s.opts.Now().Unix())
		s.mu.Unlock()
		if err == nil {
			s.signal()
		}
		return err
	}
	if r.State == "scheduled" || r.State == "conflict" || r.State == "gone" || r.State == "skipped" {
		r.State = "cancelled"
		r.Reason = ""
		err = put(ctx, s.q, r, s.opts.Now().Unix())
		if err == nil {
			// A series episode skipped because this one was set records now.
			err = s.expandSeries(ctx, r.SeriesKey, "")
		}
		if err == nil {
			err = s.plan(ctx)
		}
	}
	s.mu.Unlock()
	if err == nil {
		s.signal()
	}
	return err
}
func (s *Service) Rule(ctx context.Context, id string, a Actor) (RecordingRule, error) {
	row, err := s.q.GetDVRRule(ctx, id)
	if err != nil {
		return RecordingRule{}, ErrMissing
	}
	r, err := readRule(row)
	r.CanCancel = owner(r.UserID, a)
	return r.RecordingRule, err
}
func (s *Service) UpdateRule(ctx context.Context, id string, a Actor, in UpdateRule) (RecordingRule, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	row, err := s.q.GetDVRRule(ctx, id)
	if err != nil {
		return RecordingRule{}, ErrMissing
	}
	r, err := readRule(row)
	if err != nil {
		return RecordingRule{}, err
	}
	if !owner(r.UserID, a) {
		return RecordingRule{}, ErrDenied
	}
	if in.NewOnly != nil {
		r.NewOnly = *in.NewOnly
	}
	if in.AnyChannel != nil {
		r.AnyChannel = *in.AnyChannel
	}
	if in.Keep != nil {
		r.Keep = *in.Keep
	}
	if in.PadBefore != nil {
		r.PadBefore = *in.PadBefore
	}
	if in.PadAfter != nil {
		r.PadAfter = *in.PadAfter
	}
	if in.Name != nil {
		r.Title = strings.TrimSpace(*in.Name)
	}
	if r.Title == "" || !validPadding(r.PadBefore, r.PadAfter) || r.Keep < 0 || r.Keep > 10 {
		return RecordingRule{}, ErrInvalid
	}
	if err = putRule(ctx, s.q, r); err != nil {
		return RecordingRule{}, err
	}
	if err = s.expandRule(ctx, r); err != nil {
		return RecordingRule{}, err
	}
	if err = s.plan(ctx); err != nil {
		return RecordingRule{}, err
	}
	s.signal()
	r.CanCancel = true
	return r.RecordingRule, nil
}
func (s *Service) DeleteRule(ctx context.Context, id string, a Actor) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	row, err := s.q.GetDVRRule(ctx, id)
	if err != nil {
		return ErrMissing
	}
	r, err := readRule(row)
	if err != nil {
		return err
	}
	if !owner(r.UserID, a) {
		return ErrDenied
	}
	rows, err := s.q.ListDVRByRule(ctx, &id)
	if err != nil {
		return err
	}
	err = s.q.Transaction(ctx, func(q *db.Queries) error {
		for _, row := range rows {
			if row.State == "scheduled" || row.State == "conflict" || row.State == "gone" {
				r, e := readRecord(row)
				if e != nil {
					return e
				}
				r.State = "cancelled"
				if e = put(ctx, q, r, s.opts.Now().Unix()); e != nil {
					return e
				}
			}
		}
		return q.DeleteDVRRule(ctx, id)
	})
	if err == nil && r.Kind == "series" {
		// Another person's rule for the series takes up the episodes this
		// one held.
		err = s.expandSeries(ctx, r.SeriesKey, id)
	}
	if err == nil {
		err = s.plan(ctx)
	}
	if err == nil {
		s.signal()
	}
	return err
}
func (s *Service) plan(ctx context.Context) error {
	rows, err := s.q.ListDVRPending(ctx)
	if err != nil {
		return err
	}
	config, err := s.Settings(ctx)
	if err != nil {
		return err
	}
	sources, err := s.live.Sources(ctx)
	if err != nil {
		return err
	}
	capacity := map[string]int{}
	for _, src := range sources {
		if src.Enabled {
			capacity[src.ID] = max(0, int(src.Device.Tuners)-config.TunersReserved)
		}
	}
	intervals := []Interval{}
	records := []record{}
	for _, row := range rows {
		r, err := readRecord(row)
		if err != nil {
			return err
		}
		if r.State == "gone" {
			continue
		}
		records = append(records, r)
		intervals = append(intervals, Interval{ID: r.ID, Source: r.SourceID, Channel: r.ChannelID, Start: r.from(), End: r.until(), Priority: r.Priority, Running: r.State == "recording"})
	}
	plan := Plan(intervals, capacity)
	return s.q.Transaction(ctx, func(q *db.Queries) error {
		for _, r := range records {
			if r.State == "recording" {
				continue
			}
			state, reason := "scheduled", ""
			var holders []ConflictRef
			if !plan[r.ID] {
				state = "conflict"
				reason = "tuners_busy"
				if capacity[r.SourceID] == 0 {
					reason = "no_tuners"
				}
				holders = blockers(r, records, plan)
			}
			if r.State != state || r.Reason != reason || !sameRefs(r.ConflictsWith, holders) {
				r.State = state
				r.Reason = reason
				r.ConflictsWith = holders
				if err := put(ctx, q, r, s.opts.Now().Unix()); err != nil {
					return err
				}
			}
		}
		return nil
	})
}

// blockers are the recordings ranked ahead of r that hold a tuner on its
// source while it would run, on another channel (one channel shares a
// tuner). Those are the ones that can give way to it.
func blockers(r record, records []record, plan map[string]bool) []ConflictRef {
	var out []ConflictRef
	for _, w := range records {
		ahead := w.State == "recording" || w.Priority < r.Priority || w.Priority == r.Priority && w.ID < r.ID
		if w.ID == r.ID || !plan[w.ID] || !ahead || w.SourceID != r.SourceID || w.ChannelID == r.ChannelID || !w.from().Before(r.until()) || !w.until().After(r.from()) {
			continue
		}
		out = append(out, ConflictRef{ID: w.ID, Title: w.Title, Number: w.Number, ChannelName: w.ChannelName, Start: w.Start, End: w.End, UserName: w.UserName, Running: w.State == "recording"})
	}
	return out
}
func sameRefs(a, b []ConflictRef) bool {
	return slices.EqualFunc(a, b, func(x, y ConflictRef) bool {
		return x.ID == y.ID && x.Title == y.Title && x.Running == y.Running && x.Start.Equal(y.Start) && x.End.Equal(y.End)
	})
}
func (s *Service) Reconcile(ctx context.Context) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	rows, err := s.q.ListDVRPending(ctx)
	if err != nil {
		return err
	}
	channels, err := s.q.ListLiveChannels(ctx)
	if err != nil {
		return err
	}
	byNumber := map[string][]string{}
	byID := map[string]db.LiveTvChannel{}
	for _, ch := range channels {
		byNumber[ch.Number] = append(byNumber[ch.Number], ch.ID)
		byID[ch.ID] = ch
	}
	for _, row := range rows {
		if row.State == "recording" {
			continue
		}
		r, e := readRecord(row)
		if e != nil {
			return e
		}
		if row.ProgrammeID == "" {
			// A manual recording follows its channel by number when the
			// tuner was added again and its channels have new ids.
			if _, ok := byID[r.ChannelID]; !ok {
				if ch, ok := channelByNumber(channels, r.Number); ok {
					r.SourceID, r.ChannelID, r.ChannelName = ch.SourceID, ch.ID, ch.Name
					if e = put(ctx, s.q, r, s.opts.Now().Unix()); e != nil {
						return e
					}
				}
			}
			continue
		}
		ids := byNumber[r.Number]
		if len(ids) == 0 {
			continue
		}
		identity := guide.FollowIdentity{ProgrammeID: r.ProgrammeID, StartAt: r.Start.Unix(), SeriesKey: r.SeriesKey, Title: r.Title, EpisodeTitle: r.EpisodeTitle}
		if r.Season != nil {
			identity.Season = new(int64(*r.Season))
		}
		if r.Episode != nil {
			identity.Episode = new(int64(*r.Episode))
		}
		p, e := guide.Follow(ctx, s.q, identity, ids)
		if errors.Is(e, sql.ErrNoRows) {
			r.State = "gone"
			r.Reason = "listing_gone"
			if r.Series && r.RuleID != "" {
				// A series simply drops an episode the listings no longer
				// have (§4.3); it comes back if the listings bring it back.
				r.State = "skipped"
			}
		} else if e != nil {
			return e
		} else {
			r.ProgrammeID = p.ID
			r.Start = unix(p.StartAt)
			r.End = unix(p.EndAt)
			// The programme may now be on another channel id with the same
			// number (the tuner added again): record from where it is.
			if ch, ok := byID[p.ChannelID]; ok {
				r.SourceID, r.ChannelID, r.ChannelName = ch.SourceID, ch.ID, ch.Name
			}
			if r.State == "gone" {
				r.State = "scheduled"
				r.Reason = ""
			}
		}
		if e = put(ctx, s.q, r, s.opts.Now().Unix()); e != nil {
			return e
		}
	}
	rules, err := s.q.ListDVRRules(ctx)
	if err != nil {
		return err
	}
	for _, row := range rules {
		r, e := readRule(row)
		if e != nil {
			return e
		}
		if e = s.expandRule(ctx, r); e != nil {
			return e
		}
	}
	if err = s.plan(ctx); err == nil {
		s.signal()
	}
	return err
}

// expandSeries expands every series rule for the key except one, after a
// change that may unblock its episodes. It runs under s.mu.
func (s *Service) expandSeries(ctx context.Context, key, except string) error {
	if key == "" {
		return nil
	}
	rows, err := s.q.ListDVRRules(ctx)
	if err != nil {
		return err
	}
	for _, row := range rows {
		if row.Kind != "series" || row.SeriesKey != key || row.ID == except {
			continue
		}
		ru, e := readRule(row)
		if e != nil {
			return e
		}
		if e = s.expandRule(ctx, ru); e != nil {
			return e
		}
	}
	return nil
}
func channelByNumber(channels []db.LiveTvChannel, number string) (db.LiveTvChannel, bool) {
	for _, ch := range channels {
		if ch.Number == number && ch.Enabled != 0 && ch.Protected == 0 {
			return ch, true
		}
	}
	return db.LiveTvChannel{}, false
}
func (s *Service) Hint(lib db.Library, path string) (int, int) {
	row, err := s.q.GetDVRRecordingByPath(context.Background(), &path)
	if err != nil {
		return 0, 0
	}
	r, err := readRecord(row)
	if err != nil || r.LibraryID != lib.ID {
		return 0, 0
	}
	return r.TMDBID, 0
}

// Compile-time check: shared live input is a cancellable stream, never a path
// exposed to clients or another request to the tuner.
var _ func(context.Context, string) (io.ReadCloser, error) = (*Service)(nil).LiveInput
