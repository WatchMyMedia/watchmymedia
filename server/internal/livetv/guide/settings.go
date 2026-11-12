package guide

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/mail"
	"strings"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/livetv/guide/art"
)

var messages = map[string]string{
	StateFirmware:    "The tuner's firmware is too old for listings. Update it in the HDHomeRun app, or enter the email of your HDHomeRun DVR account.",
	StateRefused:     "SiliconDust refused the listings request. Check that the tuner is registered to your HDHomeRun account.",
	StateUnavailable: "SiliconDust's listings service is unavailable. The server will try again.",
	StateFailed:      "The listings couldn't be read. The server will try again.",
	StateNoTuner:     "Add a tuner to get listings.",
	StateStale:       "Listings haven't been updated for over 36 hours and may be out of date.",
}

// Status reports the listings for admins.
func (s *Service) Status(ctx context.Context) (Status, error) {
	s.mu.Lock()
	fetching := s.fetching
	s.mu.Unlock()
	r := s.record(ctx)
	sum, err := s.q.GuideProgrammeSummary(ctx)
	if err != nil {
		return Status{}, err
	}
	st := Status{
		LastAttemptAt: unixPtr(r.LastAttempt), LastSuccessAt: unixPtr(r.LastSuccess), NextAt: unixPtr(r.Next),
		Programmes: int(sum.Programmes), ListedThrough: unixPtr(sum.ListedThrough), Overlaps: r.Overlaps, Dropped: r.Dropped,
		RefreshesLeft: RefreshesPerDay - len(s.refreshes(ctx)),
	}
	// Each fetch gives every lineup channel a row, matched or not.
	rows, err := s.q.ListGuideChannels(ctx)
	if err != nil {
		return Status{}, err
	}
	for _, g := range rows {
		switch {
		case g.ProviderID == "" && r.LastSuccess != 0:
			st.Missing++
		case g.ProviderID != "":
			st.Matched++
			if g.Flagged != 0 {
				st.Flagged++
			}
		}
	}
	switch {
	case !s.Enabled(ctx):
		st.State, st.NextAt = StateOff, nil
	case fetching:
		st.State = StateFetching
	case r.Error != "" && r.LastAttempt >= r.LastSuccess:
		st.State = r.Error
	case r.LastSuccess == 0:
		st.State = StateWaiting
	case s.now().Sub(time.Unix(r.LastSuccess, 0)) > StaleAfter:
		st.State = StateStale
	default:
		st.State = StateOK
	}
	st.Message = messages[st.State]
	return st, nil
}

// Settings is the listings section of Live TV's settings.
func (s *Service) Settings(ctx context.Context) (Settings, error) {
	st, err := s.Status(ctx)
	if err != nil {
		return Settings{}, err
	}
	email, err := s.store.Get(ctx, keyEmail, "")
	if err != nil {
		return Settings{}, err
	}
	out := Settings{Enabled: s.Enabled(ctx), TMDB: s.TMDB(ctx), Email: email, Status: st, Channels: []ChannelMatch{}}
	channels, err := s.live.Channels(ctx, true)
	if err != nil {
		return Settings{}, err
	}
	rows, err := s.q.ListGuideChannels(ctx)
	if err != nil {
		return Settings{}, err
	}
	byID := map[string]db.LiveTvGuideChannel{}
	for _, r := range rows {
		byID[r.ChannelID] = r
	}
	fetched := s.record(ctx).LastSuccess != 0
	for _, c := range channels {
		r, ok := byID[c.ID]
		m := ChannelMatch{ID: c.ID, Number: c.Number, Name: c.Name, Listings: !ok || r.Listings != 0, FeedName: r.FeedName, LogoURL: art.URL(r.Logo)}
		switch {
		case r.ProviderID == "" && !fetched:
			m.Match = "pending"
		case r.ProviderID == "":
			m.Match = "missing"
		case r.Flagged != 0:
			m.Match = "differs"
		default:
			m.Match = r.MatchedBy
		}
		out.Channels = append(out.Channels, m)
	}
	return out, nil
}

// Configure turns listings on or off, and sets the TMDB switch and the
// email for the Email form. Turning listings off deletes the listings and
// the picture cache; the per-channel switches stay.
func (s *Service) Configure(ctx context.Context, enabled, tmdb bool, email string) error {
	email = strings.TrimSpace(email)
	if email != "" {
		if a, err := mail.ParseAddress(email); err != nil || a.Address != email || len(email) > 254 {
			return ErrEmail
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	was, wasTMDB := s.Enabled(ctx), s.TMDB(ctx)
	for k, v := range map[string]string{keyEnabled: bit(enabled), keyTMDB: bit(tmdb), keyEmail: email} {
		if err := s.store.Set(ctx, k, v); err != nil {
			return err
		}
	}
	switch {
	case enabled && !was:
		r := s.record(ctx)
		r.Next, r.Error, r.Failures = 0, "", 0
		if err := s.saveRecord(ctx, r); err != nil {
			return err
		}
	case !enabled && was:
		s.epoch++
		if err := s.q.DeleteAllProgrammes(ctx); err != nil {
			return err
		}
		if err := s.Art.Clear(ctx); err != nil {
			return err
		}
		if err := s.saveRecord(ctx, record{}); err != nil {
			return err
		}
	}
	// Turning TMDB off takes its stills and descriptions away now, and
	// turning it on needn't wait for tomorrow's fetch.
	if enabled && tmdb != wasTMDB && s.enrich != nil {
		s.rematch = true
	}
	s.poke()
	return nil
}

func bit(b bool) string {
	if b {
		return "1"
	}
	return "0"
}

// SetChannelListings is the admin's switch for one channel's listings.
func (s *Service) SetChannelListings(ctx context.Context, id string, on bool) error {
	if _, err := s.q.GetLiveChannel(ctx, id); errors.Is(err, sql.ErrNoRows) {
		return ErrMissing
	} else if err != nil {
		return err
	}
	var v int64
	if on {
		v = 1
	}
	return s.q.SetGuideChannelListings(ctx, db.SetGuideChannelListingsParams{ChannelID: id, Listings: v})
}

// refreshes are the times of Refresh now in the last day.
func (s *Service) refreshes(ctx context.Context) []int64 {
	var all []int64
	if v, err := s.store.Get(ctx, keyRefreshes, ""); err == nil && v != "" {
		_ = json.Unmarshal([]byte(v), &all)
	}
	since := s.now().Add(-24 * time.Hour).Unix()
	out := all[:0]
	for _, t := range all {
		if t > since {
			out = append(out, t)
		}
	}
	return out
}

// RefreshNow fetches the listings now, at most RefreshesPerDay times a day.
// The fetch runs in the background; Status says how it went.
func (s *Service) RefreshNow(ctx context.Context) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.Enabled(ctx) {
		return ErrOff
	}
	done := s.refreshes(ctx)
	if len(done) >= RefreshesPerDay {
		return ErrLimit
	}
	b, _ := json.Marshal(append(done, s.now().Unix()))
	if err := s.store.Set(ctx, keyRefreshes, string(b)); err != nil {
		return err
	}
	r := s.record(ctx)
	r.Next = s.now().Unix()
	if err := s.saveRecord(ctx, r); err != nil {
		return err
	}
	s.poke()
	return nil
}

// poke wakes the scheduler to look at the record again.
func (s *Service) poke() {
	select {
	case s.wake <- struct{}{}:
	default:
	}
}
