package guide

import (
	"cmp"
	"context"
	"crypto/rand"
	"database/sql"
	"errors"
	"fmt"
	"slices"
	"strings"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/metadata"
)

// Favourites, search and reminders (PLAN-LIVE-TV-GUIDE.md §5.7, G6).
// Favourites and reminders name a channel by its number, which outlives a
// tuner removed and added again; a renumbered channel loses them, as it
// loses its listings.

const (
	// MaxReminders is how many reminders a person may have that haven't
	// ended.
	MaxReminders = 200
	// moveWindow is how far from a reminder's start a refresh looks for its
	// programme moved.
	moveWindow = 3 * time.Hour
	// searchCandidates bounds the rows one search reads for its longest
	// word before checking the others.
	searchCandidates = 2000
)

// Reminder states.
const (
	ReminderSet   = "set"
	ReminderMoved = "moved"
	ReminderGone  = "gone"
)

// Events to a person's own devices when their favourites or reminders
// change, so the others update and reschedule.
const (
	FavouritesChangedEvent = "live.favourites.changed"
	RemindersChangedEvent  = "live.reminders.changed"
)

// FavouritesChanged and RemindersChanged are those events' data. They carry
// nothing, but are types of their own: /api/events names each event it
// streams by its data's type, and sent an anonymous struct{} as an unnamed
// message that no client listened for.
type (
	FavouritesChanged struct{}
	RemindersChanged  struct{}
)

// ChangedData is the data for one of the events above.
func ChangedData(kind string) any {
	if kind == FavouritesChangedEvent {
		return FavouritesChanged{}
	}
	return RemindersChanged{}
}

var (
	ErrProgramme = errors.New("that programme is not in the listings")
	ErrStarted   = errors.New("that programme has already started")
	ErrReminders = fmt.Errorf("a person may have at most %d reminders", MaxReminders)
)

// searchText is what search compares: the title and episode title folded
// as library names are, so case and accents' forms don't matter.
func searchText(title, episodeTitle string) string {
	return strings.TrimSpace(metadata.Normalize(title) + " " + metadata.Normalize(episodeTitle))
}

// Favourites are the channel numbers a person starred.
func (s *Service) Favourites(ctx context.Context, userID int64) (map[string]bool, error) {
	numbers, err := s.q.ListLiveFavourites(ctx, userID)
	if err != nil {
		return nil, err
	}
	out := make(map[string]bool, len(numbers))
	for _, n := range numbers {
		out[n] = true
	}
	return out, nil
}

// SetFavourite stars or unstars a channel number for a person.
func (s *Service) SetFavourite(ctx context.Context, userID int64, number string, on bool) error {
	var err error
	if on {
		err = s.q.AddLiveFavourite(ctx, db.AddLiveFavouriteParams{UserID: userID, Number: number, CreatedAt: s.now().Unix()})
	} else {
		err = s.q.RemoveLiveFavourite(ctx, db.RemoveLiveFavouriteParams{UserID: userID, Number: number})
	}
	if err == nil {
		s.publish(FavouritesChangedEvent, userID)
	}
	return err
}

// Hit is a programme search found, on its channel.
type Hit struct {
	ChannelID string
	Programme livetv.Programme
}

// Search finds programmes still to end on the given channels whose title
// and episode title hold every word of query, soonest first. Only channels
// whose listings show are searched.
func (s *Service) Search(ctx context.Context, ids []string, query string, limit int) ([]Hit, error) {
	out := []Hit{}
	words := strings.Fields(metadata.Normalize(query))
	if len(words) == 0 || limit <= 0 {
		return out, nil
	}
	info, err := s.Channels(ctx)
	if err != nil {
		return nil, err
	}
	shown := map[string]bool{}
	for _, id := range ids {
		if _, ok := info[id]; ok {
			shown[id] = true
		}
	}
	if len(shown) == 0 {
		return out, nil
	}
	longest := slices.MaxFunc(words, func(a, b string) int { return cmp.Compare(len(a), len(b)) })
	rows, err := s.q.SearchProgrammes(ctx, db.SearchProgrammesParams{NowAt: s.now().Unix(), Word: longest, RowLimit: searchCandidates})
	if err != nil {
		return nil, err
	}
	for _, r := range rows {
		if !shown[r.ChannelID] || !containsAll(r.Search, words) {
			continue
		}
		out = append(out, Hit{ChannelID: r.ChannelID, Programme: programmeView(r)})
		if len(out) == limit {
			break
		}
	}
	return out, nil
}

func containsAll(text string, words []string) bool {
	for _, w := range words {
		if !strings.Contains(text, w) {
			return false
		}
	}
	return true
}

// Reminders are a person's reminders that haven't ended, soonest first.
func (s *Service) Reminders(ctx context.Context, userID int64) ([]db.LiveTvReminder, error) {
	return s.q.ListLiveReminders(ctx, db.ListLiveRemindersParams{UserID: userID, EndAt: s.now().Unix()})
}

// Remind sets a reminder for a programme in the listings that hasn't
// started. Setting one again returns it, marked set.
func (s *Service) Remind(ctx context.Context, userID int64, programmeID string) (db.LiveTvReminder, error) {
	var out db.LiveTvReminder
	err := s.q.Transaction(ctx, func(q *db.Queries) error {
		p, err := q.GetProgramme(ctx, programmeID)
		if errors.Is(err, sql.ErrNoRows) {
			return ErrProgramme
		}
		if err != nil {
			return err
		}
		now := s.now().Unix()
		if p.StartAt <= now {
			return ErrStarted
		}
		ch, err := q.GetLiveChannel(ctx, p.ChannelID)
		if errors.Is(err, sql.ErrNoRows) {
			return ErrProgramme
		}
		if err != nil {
			return err
		}
		have, err := q.ListLiveReminders(ctx, db.ListLiveRemindersParams{UserID: userID, EndAt: now})
		if err != nil {
			return err
		}
		if len(have) >= MaxReminders && !slices.ContainsFunc(have, func(r db.LiveTvReminder) bool { return r.ProgrammeID == p.ID }) {
			return ErrReminders
		}
		out, err = q.UpsertLiveReminder(ctx, db.UpsertLiveReminderParams{
			ID: rand.Text(), UserID: userID, Number: ch.Number, ProgrammeID: p.ID, StartAt: p.StartAt, EndAt: p.EndAt,
			Title: p.Title, EpisodeTitle: p.EpisodeTitle, Season: p.Season, Episode: p.Episode, SeriesKey: p.SeriesKey, CreatedAt: now,
		})
		return err
	})
	if err == nil {
		s.publish(RemindersChangedEvent, userID)
	}
	return out, err
}

// Unremind removes one of a person's reminders. Removing one that is gone
// already is not an error.
func (s *Service) Unremind(ctx context.Context, userID int64, id string) error {
	n, err := s.q.DeleteLiveReminder(ctx, db.DeleteLiveReminderParams{ID: id, UserID: userID})
	if err == nil && n > 0 {
		s.publish(RemindersChangedEvent, userID)
	}
	return err
}

func (s *Service) publish(kind string, userIDs ...int64) {
	if s.bus == nil {
		return
	}
	for _, id := range userIDs {
		s.bus.Publish(events.Event{Type: kind, Data: ChangedData(kind), To: &events.Audience{UserID: id, Private: true}})
	}
}

// reconcile follows every reminder that hasn't ended to its programme
// after a refresh (§5.7). The same listing, still the same programme,
// keeps it. Otherwise the same
// series and episode, or the same title and episode title, on that channel
// within three hours moves it; failing that it is gone. A number no longer
// in the lineup leaves its reminders alone. It returns the people whose
// reminders changed.
func reconcile(ctx context.Context, q *db.Queries, channels []db.LiveTvChannel, now time.Time) ([]int64, error) {
	byNumber := map[string][]string{}
	for _, ch := range channels {
		byNumber[ch.Number] = append(byNumber[ch.Number], ch.ID)
	}
	reminders, err := q.ListUpcomingReminders(ctx, now.Unix())
	if err != nil {
		return nil, err
	}
	changed := map[int64]bool{}
	for _, r := range reminders {
		ids := byNumber[r.Number]
		if len(ids) == 0 {
			continue
		}
		p, err := Follow(ctx, q, FollowIdentity{ProgrammeID: r.ProgrammeID, StartAt: r.StartAt, SeriesKey: r.SeriesKey, Season: r.Season, Episode: r.Episode, Title: r.Title, EpisodeTitle: r.EpisodeTitle}, ids)
		if errors.Is(err, sql.ErrNoRows) {
			if r.State != ReminderGone {
				if err := q.SetLiveReminderState(ctx, db.SetLiveReminderStateParams{State: ReminderGone, ID: r.ID}); err != nil {
					return nil, err
				}
				changed[r.UserID] = true
			}
			continue
		}
		if err != nil {
			return nil, err
		}
		state := r.State
		if p.ID != r.ProgrammeID {
			state = ReminderMoved
		} else if state == ReminderGone {
			state = ReminderSet
		}
		if state != r.State || p.EndAt != r.EndAt || p.StartAt != r.StartAt || p.ID != r.ProgrammeID {
			if err := move(ctx, q, r, p, state); err != nil {
				return nil, err
			}
			changed[r.UserID] = true
		}

	}
	if _, err := q.ExpireLiveReminders(ctx, now.Add(-keepEnded).Unix()); err != nil {
		return nil, err
	}
	users := make([]int64, 0, len(changed))
	for id := range changed {
		users = append(users, id)
	}
	slices.Sort(users)
	return users, nil
}

// move points a reminder at a programme. If the person already has a
// reminder for that programme, this one goes.
func move(ctx context.Context, q *db.Queries, r db.LiveTvReminder, p db.LiveTvProgramme, state string) error {
	n, err := q.MoveLiveReminder(ctx, db.MoveLiveReminderParams{ProgrammeID: p.ID, StartAt: p.StartAt, EndAt: p.EndAt, State: state, ID: r.ID})
	if err != nil || n > 0 {
		return err
	}
	return q.DeleteLiveReminderByID(ctx, r.ID)
}

func distance(a, b int64) int64 {
	if a > b {
		return a - b
	}
	return b - a
}

// backfillSearch folds the titles of programmes listed before search
// existed, so an upgrade needn't wait a day for the next refresh.
func (s *Service) backfillSearch(ctx context.Context) error {
	rows, err := s.q.ListProgrammesUnsearchable(ctx)
	if err != nil || len(rows) == 0 {
		return err
	}
	return s.q.Transaction(ctx, func(q *db.Queries) error {
		for _, r := range rows {
			if err := q.SetProgrammeSearch(ctx, db.SetProgrammeSearchParams{Search: searchText(r.Title, r.EpisodeTitle), ID: r.ID}); err != nil {
				return err
			}
		}
		return nil
	})
}
