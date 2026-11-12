package guide

import (
	"context"
	"errors"
	"math/rand/v2"
	"net/url"
	"strings"
	"time"

	"watchmymedia/server/internal/httpx"
	"watchmymedia/server/internal/livetv/guide/xmltv"
)

// The schedule (§3, Freshness; §5.5): a fetch 20–28 hours after the last
// success; the first 1–10 minutes after listings are turned on or the
// server starts without current listings; failures back off 1, 2, 4, 8
// and then 12 hours.
const (
	refreshAfter    = 20 * time.Hour
	refreshSpread   = 8 * time.Hour
	firstAfter      = time.Minute
	firstSpread     = 9 * time.Minute
	maxBackoff      = 12 * time.Hour
	earlyAfter      = 6 * time.Hour
	keepAhead       = 15 * 24 * time.Hour
	maintainEvery   = time.Hour
	settingsBackoff = 24 * time.Hour
)

// spread is a random wait up to d, so servers don't all fetch at once.
func spread(d time.Duration) time.Duration { return rand.N(d + 1) } //nolint:gosec // G404: jitter, not a secret

// Run fetches listings on their schedule, and expires old programmes and
// pictures, until ctx ends. A fetch under way ends with ctx.
func (s *Service) Run(ctx context.Context) {
	timer := time.NewTimer(time.Hour)
	defer timer.Stop()
	last := s.now()
	s.maintain(ctx)
	for {
		timer.Reset(s.schedule(ctx))
		select {
		case <-ctx.Done():
			return
		case <-s.wake:
			s.rematchNow(ctx)
			continue
		case <-timer.C:
		}
		if s.due(ctx) {
			s.fetch(ctx)
		}
		if s.now().Sub(last) >= maintainEvery {
			s.maintain(ctx)
			last = s.now()
		}
	}
}

// schedule decides when the next fetch is due and returns how long to
// wait: until then, or an hour for the upkeep.
func (s *Service) schedule(ctx context.Context) time.Duration {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.Enabled(ctx) {
		return maintainEvery
	}
	now := s.now()
	r := s.record(ctx)
	if r.Next == 0 {
		next := now.Add(firstAfter + spread(firstSpread))
		if r.LastSuccess != 0 && now.Sub(time.Unix(r.LastSuccess, 0)) < refreshAfter {
			next = time.Unix(r.LastSuccess, 0).Add(refreshAfter + spread(refreshSpread))
		}
		r.Next = next.Unix()
		if err := s.saveRecord(ctx, r); err != nil {
			s.log.Warn("live TV listings schedule", "err", err)
		}
	}
	return min(max(0, time.Unix(r.Next, 0).Sub(now)), maintainEvery)
}

func (s *Service) due(ctx context.Context) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	r := s.record(ctx)
	return s.Enabled(ctx) && r.Next != 0 && !s.now().Before(time.Unix(r.Next, 0))
}

// LineupChanged fetches early when a tuner's lineup gained channels the
// listings haven't seen and the last fetch is over 6 hours old.
func (s *Service) LineupChanged(ctx context.Context) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.Enabled(ctx) {
		return
	}
	now := s.now()
	r := s.record(ctx)
	if r.LastAttempt != 0 && now.Sub(time.Unix(r.LastAttempt, 0)) < earlyAfter {
		return
	}
	channels, err := s.q.ListLiveChannels(ctx)
	if err != nil {
		return
	}
	rows, err := s.q.ListGuideChannels(ctx)
	if err != nil {
		return
	}
	known := map[string]bool{}
	for _, g := range rows {
		known[g.ChannelID] = true
	}
	for _, c := range channels {
		if !known[c.ID] {
			next := now.Add(firstAfter + spread(firstSpread)).Unix()
			if r.Next == 0 || r.Next > next {
				r.Next = next
				_ = s.saveRecord(ctx, r)
				s.poke()
			}
			return
		}
	}
}

// fetch fetches and stores the listings once, and records how it went.
func (s *Service) fetch(ctx context.Context) {
	now := s.now()
	s.mu.Lock()
	if !s.Enabled(ctx) {
		s.mu.Unlock()
		return
	}
	s.fetching = true
	epoch := s.epoch
	r := s.record(ctx)
	r.LastAttempt = now.Unix()
	_ = s.saveRecord(ctx, r)
	s.mu.Unlock()

	feed, err := s.download(ctx, now)

	s.mu.Lock()
	s.fetching = false
	if ctx.Err() != nil || s.epoch != epoch || !s.Enabled(ctx) {
		// Shutting down, or listings were turned off meanwhile.
		s.mu.Unlock()
		return
	}
	var wrote stored
	if err == nil {
		if wrote, err = s.save(ctx, feed, now); err != nil {
			err = failed(StateFailed, err)
		}
	}
	r = s.record(ctx)
	if err != nil {
		var fe *fetchError
		if !errors.As(err, &fe) {
			fe = failed(StateFailed, err)
		}
		r.Failures++
		r.Error = fe.state
		r.Next = now.Add(backoff(fe, r.Failures)).Unix()
		_ = s.saveRecord(ctx, r)
		s.mu.Unlock()
		s.log.Warn("live TV listings not fetched", "state", fe.state, "err", httpx.Redact(fe.err), "next", time.Unix(r.Next, 0).UTC())
		return
	}
	r.LastSuccess, r.Error, r.Failures = now.Unix(), "", 0
	r.Overlaps, r.Dropped = feed.Overlaps, feed.Dropped
	r.Next = now.Add(refreshAfter + spread(refreshSpread)).Unix()
	_ = s.saveRecord(ctx, r)
	s.mu.Unlock()
	s.log.Info("live TV listings fetched", "programmes", wrote.programmes, "matched", wrote.matched, "flagged", wrote.flagged,
		"missing", wrote.missing, "overlaps", feed.Overlaps, "dropped", feed.Dropped, "took", s.now().Sub(now).Round(time.Millisecond))
	s.publish(RemindersChangedEvent, wrote.reminded...)
	s.runEnrich(ctx)
	s.maintain(ctx)
}

// rematchNow runs Enrich if the TMDB switch changed.
func (s *Service) rematchNow(ctx context.Context) {
	s.mu.Lock()
	run := s.rematch && s.Enabled(ctx)
	s.rematch = false
	s.mu.Unlock()
	if run {
		s.runEnrich(ctx)
	}
}

func (s *Service) runEnrich(ctx context.Context) {
	if s.enrich == nil {
		return
	}
	if err := s.enrich(ctx); err != nil && ctx.Err() == nil {
		s.log.Warn("live TV listings: TMDB matching", "err", err)
	}
}

// backoff is how long to wait after the failures-th failure in a row:
// doubling from an hour to 12, with jitter, or longer if SiliconDust said
// so. A problem only a setting can fix waits a day, or until it changes.
func backoff(e *fetchError, failures int) time.Duration {
	if e.state == StateFirmware || e.state == StateNoTuner {
		return settingsBackoff
	}
	d := min(time.Hour<<min(max(failures-1, 0), 4), maxBackoff)
	d = d*4/5 + spread(d*2/5)
	return max(d, e.wait)
}

// download reads DeviceAuth from the tuners and fetches the feed: by
// DeviceAuth, or with the Email form when the firmware gives none.
func (s *Service) download(ctx context.Context, now time.Time) (xmltv.Feed, error) {
	auth, ids, err := s.live.GuideAuth(ctx)
	if err != nil {
		return xmltv.Feed{}, failed(StateFailed, err)
	}
	if len(ids) == 0 {
		return xmltv.Feed{}, failed(StateNoTuner, errors.New("no tuner is enabled"))
	}
	query := url.Values{}
	email, _ := s.store.Get(ctx, keyEmail, "")
	switch {
	case !auth.Empty():
		query.Set("DeviceAuth", auth.Reveal())
	case email != "":
		query.Set("Email", email)
		query.Set("DeviceIDs", strings.Join(ids, ","))
	default:
		return xmltv.Feed{}, failed(StateFirmware, errors.New("the tuner's firmware gives no DeviceAuth"))
	}
	channels, err := s.q.ListLiveChannels(ctx)
	if err != nil {
		return xmltv.Feed{}, failed(StateFailed, err)
	}
	numbers := map[string]bool{}
	for _, c := range channels {
		numbers[c.Number] = true
	}
	return s.fetchFeed(ctx, query, xmltv.Options{
		Keep: func(c xmltv.Channel) bool { return numbers[c.LCN] },
		From: now.Add(-keepEnded), To: now.Add(keepAhead),
	})
}

// maintain drops programmes that ended over 6 hours ago and pictures no
// listing has used for a week.
func (s *Service) maintain(ctx context.Context) {
	if err := s.backfillSearch(ctx); err != nil && ctx.Err() == nil {
		s.log.Warn("live TV listings: search", "err", err)
	}
	if n, err := s.q.ExpireProgrammes(ctx, s.now().Add(-keepEnded).Unix()); err != nil {
		s.log.Warn("live TV listings: expire", "err", err)
	} else if n > 0 {
		s.log.Debug("live TV listings expired", "programmes", n)
	}
	if err := s.Art.Sweep(ctx); err != nil && ctx.Err() == nil {
		s.log.Warn("live TV listings: pictures", "err", err)
	}
}

// Fetch fetches now, outside the schedule and Refresh now's limit, and
// returns when the listings are stored. It is for tools and tests.
func (s *Service) Fetch(ctx context.Context) { s.fetch(ctx) }
