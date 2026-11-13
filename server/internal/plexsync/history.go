package plexsync

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"math"
	"strconv"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/plex"
	"watchmymedia/server/internal/settings"
)

// retentionCutoff reuses the application's history horizon, based on source
// time. Zero means keep available history; the initial backfill is page-bounded.
func (s *Service) retentionCutoff(ctx context.Context, q *db.Queries) time.Time {
	v, err := q.GetSetting(ctx, settings.KeyActivityRetention)
	if err != nil {
		return time.Unix(0, 0).UTC()
	}
	days, err := strconv.Atoi(v)
	if err != nil || days <= 0 {
		return time.Unix(0, 0).UTC()
	}
	return s.now().AddDate(0, 0, -days).UTC()
}
func (s *Service) historyCutoff(ctx context.Context, q *db.Queries, user int64) (time.Time, error) {
	v, err := q.GetPlexHistoryCutoff(ctx, user)
	if err != nil {
		return time.Time{}, err
	}
	str, ok := v.(string)
	if !ok {
		return time.Time{}, fail(Unavailable, "history_policy_invalid")
	}
	t, err := time.Parse(time.RFC3339Nano, str)
	if err != nil {
		return time.Time{}, err
	}
	retention := s.retentionCutoff(ctx, q)
	if retention.After(t) {
		t = retention
	}
	return t, nil
}
func eventFingerprint(e plex.HistoryEvent) string {
	sum := sha256.Sum256([]byte(itemFingerprint(e.Item) + ":" + strconv.FormatInt(e.AccountID, 10) + ":" + stamp(e.ViewedAt)))
	return hex.EncodeToString(sum[:])
}
func (s *Service) importHistory(ctx context.Context, r db.PlexSyncRun, c db.PlexConnection, links []db.PlexUserLink, libs []db.ListPlexLibraryLinksRow) (string, error) {
	if c.HistoryCapability == "unavailable" {
		return "unavailable", nil
	}
	// No optional provider read should schedule retries when every mapped
	// person has disabled history recording.
	historyEnabled := false
	for _, l := range links {
		u, err := s.q.GetUser(ctx, l.UserID)
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return "partial", err
		}
		if err == nil && u.RecordHistory == 1 {
			historyEnabled = true
			break
		}
	}
	if !historyEnabled {
		return "disabled", nil
	}
	o, err := s.openOwner(ctx, c)
	if err != nil {
		return "partial", err
	}
	people, err := s.readPeople(ctx, o, true)
	if err != nil {
		return "partial", err
	}
	byID := map[int64]PlexPerson{}
	for _, p := range people {
		byID[p.PlexUserID] = p
	}
	m := &matcher{q: s.q, owner: o.pms, shows: map[string]*showIDs{}}
	complete, enabled := true, false
	// A bounded number of pages per run protects the server. Checkpoints advance
	// only with committed events; subsequent runs continue the same fixed window.
	remainingPages := s.historyPages
	// Events read again whose item Plex has changed since they were imported.
	changedEvents, changedTotal := 0, 0
	defer func() {
		if changedTotal > 0 {
			s.log.Warn("plex: history events whose item changed in Plex since they were imported; skipped", "connection", c.ID, "run", r.ID, "events", changedTotal)
		}
	}()
	for _, group := range historyWorkOrder(links, libs, c.HistoryCursorUser, c.HistoryCursorSection) {
		l := group.link
		user, err := s.q.GetUser(ctx, l.UserID)
		if errors.Is(err, sql.ErrNoRows) {
			continue
		}
		if err != nil {
			return "partial", err
		}
		if user.RecordHistory != 1 {
			continue
		}
		enabled = true
		if remainingPages == 0 {
			complete = false
			break
		}
		p, ok := byID[l.PlexUserID]
		if !ok || p.Status != StatusReady || p.Kind != l.Kind || p.HistoryAccountID <= 0 || l.PmsAccountID == nil || *l.PmsAccountID != p.HistoryAccountID {
			complete = false
			continue
		}
		client, err := s.personClient(ctx, o, p)
		if err != nil {
			if retryablePlexError(err) {
				return "partial", plexFailed(err)
			}
			complete = false
			continue
		}
		visible, err := client.Libraries(ctx)
		if err != nil {
			if retryablePlexError(err) {
				return "partial", plexFailed(err)
			}
			complete = false
			continue
		}
		sections := map[string]plex.LibrarySection{}
		for _, v := range visible {
			sections[v.ID] = v
		}
		for _, lib := range group.libraries {
			secID := strconv.FormatInt(lib.SectionID, 10)
			sec, ok := sections[secID]
			if !ok || sec.UUID != lib.SectionUuid || localType(sec.Type) != lib.LibraryType {
				complete = false
				continue
			}
			cp, err := s.historyCheckpoint(ctx, r, l, lib)
			if err != nil {
				return "partial", err
			}
			for {
				if remainingPages == 0 {
					complete = false
					break
				}
				remainingPages--
				since, _ := time.Parse(time.RFC3339Nano, cp.SinceTime)
				until, _ := time.Parse(time.RFC3339Nano, cp.UntilTime)
				query := plex.HistoryPageQuery{AccountID: p.HistoryAccountID, SectionID: secID, Since: since, Paging: plex.Paging{PageSize: 200}, Until: until, Offset: int(cp.Offset), Own: p.Kind != KindOwner}
				page, err := client.HistoryPage(ctx, query)
				if err != nil {
					if retryablePlexError(err) {
						return "partial", plexFailed(err)
					}
					if plex.ErrorKind(err) == "pagination_drift" {
						cp.Offset, cp.Total = 0, -1
						_ = s.saveHistoryCheckpoint(ctx, s.q, cp)
					}
					complete = false
					break
				}
				if cp.Total >= 0 && int64(page.Total) != cp.Total {
					cp.Offset, cp.Total = 0, -1
					_ = s.saveHistoryCheckpoint(ctx, s.q, cp)
					complete = false
					break
				}
				// Corroborate a nonowner's unfiltered own-token history against the
				// exact administrator-filtered page. Empty pages prove no identity.
				if p.Kind != KindOwner && len(page.Events) > 0 {
					query.Own = false
					admin, err := o.pms.HistoryPage(ctx, query)
					if retryablePlexError(err) {
						return "partial", plexFailed(err)
					}
					if err != nil || !sameHistoryPage(page, admin) {
						complete = false
						break
					}
				}
				targets := make([]found, len(page.Events))
				for i, e := range page.Events {
					f, err := m.match(ctx, lib.LibraryID, e.Item)
					if err != nil {
						return "partial", err
					}
					targets[i] = f
				}
				next := cp
				next.Offset = int64(page.NextOffset)
				next.Total = int64(page.Total)
				next.Complete = db.BoolInt(page.Complete)
				if page.Complete && cp.FullPass == 1 {
					next.LastFull = stamp(s.now())
				}
				if s.beforeHistoryCommit != nil {
					s.beforeHistoryCommit()
				}
				err = s.q.Transaction(ctx, func(q *db.Queries) error {
					if err := s.validScope(ctx, q, r, db.PlexRunScope{UserID: l.UserID, PlexUserID: l.PlexUserID, Kind: l.Kind, SectionID: lib.SectionID, SectionUuid: lib.SectionUuid, LibraryID: lib.LibraryID}); err != nil {
						return err
					}
					u, err := q.GetUser(ctx, l.UserID)
					if err != nil {
						return err
					}
					cutoff, err := s.historyCutoff(ctx, q, l.UserID)
					if err != nil {
						return err
					}
					imported, skipped := int64(0), int64(0)
					changedEvents = 0
					for i, e := range page.Events {
						if u.RecordHistory != 1 || !e.ViewedAt.After(cutoff) {
							skipped++
							continue
						}
						fp := eventFingerprint(e)
						old, err := q.GetPlexHistoryFingerprint(ctx, db.GetPlexHistoryFingerprintParams{UserID: l.UserID, MachineIdentifier: c.MachineIdentifier, SourceEpoch: c.SourceEpoch, SourceKey: e.HistoryKey})
						if err != nil && !errors.Is(err, sql.ErrNoRows) {
							return err
						}
						if err == nil && old != fp {
							// Plex refreshed the item since this event was imported
							// (another GUID, a new ID): keep the imported event.
							skipped++
							changedEvents++
							continue
						}
						f := targets[i]
						var itemID *int64
						if f.category == "matched" {
							current, err := targetFor(ctx, q, f.target.ItemType, f.target.ItemID)
							if err != nil && !errors.Is(err, sql.ErrNoRows) {
								return err
							}
							if err == nil && current == f.target {
								itemID = &f.target.ItemID
							}
						}
						n, err := q.InsertPlexHistory(ctx, db.InsertPlexHistoryParams{MachineIdentifier: c.MachineIdentifier, SourceEpoch: c.SourceEpoch, UserID: l.UserID, PlexUserID: l.PlexUserID, SourceKey: e.HistoryKey, Fingerprint: fp, ItemType: e.Item.Type, ItemID: itemID, Title: e.Item.Title, ShowTitle: f.example.Show, Season: f.example.Season, Episode: f.example.Episode, ViewedAt: stamp(e.ViewedAt), ImportedAt: stamp(s.now()), RunID: &r.ID})
						if err != nil {
							return err
						}
						imported += n
					}
					if err := q.AddPlexHistoryCount(ctx, db.AddPlexHistoryCountParams{HistoryImported: imported, HistorySkipped: skipped, ID: r.ID}); err != nil {
						return err
					}
					if err := s.saveHistoryCheckpoint(ctx, q, next); err != nil {
						return err
					}
					if err := q.SavePlexRunHistoryCoverage(ctx, db.SavePlexRunHistoryCoverageParams{RunID: r.ID, UserID: l.UserID, SectionID: lib.SectionID, FromTime: next.SinceTime, ThroughTime: next.UntilTime, Complete: next.Complete}); err != nil {
						return err
					}
					n, err := q.SetPlexHistoryCursor(ctx, db.SetPlexHistoryCursorParams{PlexUserID: l.PlexUserID, SectionID: lib.SectionID, ID: c.ID, Generation: r.Generation})
					if err != nil {
						return err
					}
					if n != 1 {
						return fail(Conflict, "stale_generation")
					}
					return nil
				})
				if err != nil {
					return "partial", err
				}
				s.publishHistory(l.UserID)
				changedTotal += changedEvents
				cp = next
				if page.Complete {
					break
				}
			}
		}
	}
	if !enabled {
		return "disabled", nil
	}
	if !complete {
		return "partial", nil
	}
	return "complete", nil
}
func sameHistoryPage(a, b plex.HistoryPage) bool {
	if a.Total != b.Total || a.NextOffset != b.NextOffset || len(a.Events) != len(b.Events) {
		return false
	}
	keys := map[string]string{}
	for _, e := range a.Events {
		keys[e.HistoryKey] = eventFingerprint(e)
	}
	for _, e := range b.Events {
		if keys[e.HistoryKey] != eventFingerprint(e) {
			return false
		}
	}
	return true
}
func (s *Service) historyCheckpoint(ctx context.Context, r db.PlexSyncRun, l db.PlexUserLink, lib db.ListPlexLibraryLinksRow) (db.PlexHistoryCheckpoint, error) {
	cp, err := s.q.GetPlexHistoryCheckpoint(ctx, db.GetPlexHistoryCheckpointParams{ConnectionID: *r.ConnectionID, UserID: l.UserID, SectionID: lib.SectionID})
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return cp, err
	}
	cutoff, err := s.historyCutoff(ctx, s.q, l.UserID)
	if err != nil {
		return cp, err
	}
	lastFull, _ := time.Parse(time.RFC3339Nano, cp.LastFull)
	if cp.Generation != r.Generation || cp.UntilTime == "" || (cp.Complete == 1 && (lastFull.IsZero() || s.now().Sub(lastFull) >= 7*24*time.Hour)) {
		cp = db.PlexHistoryCheckpoint{ConnectionID: *r.ConnectionID, UserID: l.UserID, SectionID: lib.SectionID, Generation: r.Generation, SinceTime: stamp(cutoff), UntilTime: stamp(s.now().Truncate(time.Second)), LastFull: cp.LastFull, Total: -1, FullPass: 1}
	} else if cp.Complete == 1 {
		since, _ := time.Parse(time.RFC3339Nano, cp.UntilTime)
		since = since.Add(-time.Minute)
		if cutoff.After(since) {
			since = cutoff
		}
		cp.SinceTime, cp.UntilTime = stamp(since), stamp(s.now().Truncate(time.Second))
		cp.Offset, cp.Total, cp.Complete, cp.FullPass = 0, -1, 0, 0
	}
	return cp, nil
}
func (s *Service) saveHistoryCheckpoint(ctx context.Context, q *db.Queries, v db.PlexHistoryCheckpoint) error {
	return q.SavePlexHistoryCheckpoint(ctx, db.SavePlexHistoryCheckpointParams(v))
}

type PlexHistoryItem struct {
	ID         int64  `json:"id"`
	UserID     int64  `json:"userId"`
	ItemType   string `json:"itemType"`
	ItemID     *int64 `json:"itemId,omitempty"`
	Title      string `json:"title"`
	Show       string `json:"show,omitempty"`
	Season     int64  `json:"season,omitempty"`
	Episode    int64  `json:"episode,omitempty"`
	ViewedAt   string `json:"viewedAt"`
	ImportedAt string `json:"importedAt"`
	Source     string `json:"source" enum:"plex"`
}
type PlexHistoryList struct {
	Items []PlexHistoryItem `json:"items" nullable:"false"`
	Next  *int64            `json:"next,omitempty"`
}

func (s *Service) History(ctx context.Context, user int64, limit int, before int64) (PlexHistoryList, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	if before <= 0 {
		before = math.MaxInt64
	}
	cutoff, err := s.historyCutoff(ctx, s.q, user)
	if err != nil {
		return PlexHistoryList{}, err
	}
	rows, err := s.q.ListPlexHistory(ctx, db.ListPlexHistoryParams{UserID: user, BeforeID: before, PageSize: int64(limit + 1), Cutoff: stamp(cutoff)})
	if err != nil {
		return PlexHistoryList{}, err
	}
	out := PlexHistoryList{Items: []PlexHistoryItem{}}
	if len(rows) > limit {
		out.Next = &rows[limit-1].ID
		rows = rows[:limit]
	}
	for _, r := range rows {
		out.Items = append(out.Items, PlexHistoryItem{ID: r.ID, UserID: r.UserID, ItemType: r.ItemType, ItemID: r.ItemID, Title: r.Title, Show: r.ShowTitle, Season: r.Season, Episode: r.Episode, ViewedAt: r.ViewedAt, ImportedAt: r.ImportedAt, Source: "plex"})
	}
	return out, nil
}
func (s *Service) DeleteHistory(ctx context.Context, user, id int64) error {
	err := s.q.Transaction(ctx, func(q *db.Queries) error {
		if _, err := q.GetPlexHistory(ctx, db.GetPlexHistoryParams{ID: id, UserID: user}); errors.Is(err, sql.ErrNoRows) {
			return fail(NotFound, "history_not_found")
		} else if err != nil {
			return err
		}
		if err := q.SuppressPlexHistory(ctx, db.SuppressPlexHistoryParams{ID: id, UserID: user}); err != nil {
			return err
		}
		return q.DeletePlexHistory(ctx, db.DeletePlexHistoryParams{ID: id, UserID: user})
	})
	if err == nil {
		s.publishHistoryChange(user, PlexHistoryChanged{DeletedIDs: []int64{id}})
	}
	return err
}
func (s *Service) ClearHistory(ctx context.Context, user int64) error {
	err := s.q.Transaction(ctx, func(q *db.Queries) error {
		if err := q.SetPlexHistoryCutoff(ctx, db.SetPlexHistoryCutoffParams{UserID: user, Cutoff: stamp(s.now())}); err != nil {
			return err
		}
		return q.ClearPlexHistory(ctx, user)
	})
	if err == nil {
		s.publishHistoryChange(user, PlexHistoryChanged{Cleared: true})
	}
	return err
}
func (s *Service) cleanup(ctx context.Context) error {
	now := s.now()
	s.mu.Lock()
	last := s.lastCleanup
	s.mu.Unlock()
	if !last.IsZero() && !now.Before(last) && now.Sub(last) < time.Hour {
		return nil
	}
	cutoff := stamp(s.retentionCutoff(ctx, s.q))
	if err := s.q.DeletePlexOldHistory(ctx, cutoff); err != nil {
		return err
	}
	if err := s.q.DeletePlexOldSuppressions(ctx, cutoff); err != nil {
		return err
	}
	if err := s.cleanupScope(ctx, s.q); err != nil {
		return err
	}
	s.mu.Lock()
	s.lastCleanup = now
	s.mu.Unlock()
	return nil
}

// PlexHistoryCoverage describes traversed source windows, not a promise that
// Plex retained every event since the start of the person's viewing history.
type PlexHistoryCoverage struct {
	UserID    int64  `json:"userId"`
	SectionID int64  `json:"sectionId"`
	From      string `json:"from"`
	Through   string `json:"through"`
	Complete  bool   `json:"complete" doc:"The fixed window was fully read. False means bounded backfill continues in a later run."`
}

func (s *Service) coverage(ctx context.Context, r db.PlexSyncRun) (PlexRun, error) {
	v := runView(r)
	rows, err := s.q.ListPlexHistoryCoverage(ctx, r.ID)
	if err != nil {
		return v, err
	}
	for _, row := range rows {
		v.HistoryCoverage = append(v.HistoryCoverage, PlexHistoryCoverage{UserID: row.UserID, SectionID: row.SectionID, From: row.FromTime, Through: row.ThroughTime, Complete: row.Complete == 1})
	}
	return v, nil
}

func (s *Service) cleanupScope(ctx context.Context, q *db.Queries) error {
	cutoff := s.now().Add(-7 * 24 * time.Hour)
	retention := s.retentionCutoff(ctx, q)
	if retention.After(cutoff) {
		cutoff = retention
	}
	at := new(stamp(cutoff))
	if err := q.MarkPlexExpiredScope(ctx, at); err != nil {
		return err
	}
	if err := q.DeletePlexPrivateScope(ctx, at); err != nil {
		return err
	}
	return q.DeletePlexPrivateCoverage(ctx, at)
}
