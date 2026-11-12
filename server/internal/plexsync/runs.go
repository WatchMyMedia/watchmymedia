package plexsync

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"math"
	"strconv"
	"strings"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/plex"
	"watchmymedia/server/internal/watchstate"
)

const pollInterval = 15 * time.Minute
const runLease = 2 * time.Minute

func stamp(t time.Time) string { return t.UTC().Format("2006-01-02T15:04:05.000Z") }

type scopeCandidate struct {
	UserID, PlexUserID                                   int64
	Kind, SectionID, SectionUUID, RemoteKey, Fingerprint string
	Found                                                found
	LibraryID                                            int64
	SourceKnown                                          bool
}

func itemFingerprint(i plex.Item) string {
	// A provider rating key reused for another canonical item is a source-epoch
	// conflict. Text, watch counts and last-view times are deliberately excluded.
	b, _ := json.Marshal([]any{i.Type, i.ExternalIDs, i.GUID, i.GrandparentRatingKey, i.SeasonNumber, i.EpisodeNumber, i.EditionTitle, i.EpisodeOrdering})
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}

func candidateFingerprint(item plex.Item, f found) string {
	sum := sha256.Sum256([]byte(itemFingerprint(item) + ":" + f.sourceIdentity))
	return hex.EncodeToString(sum[:])
}

type PlexRunCounts struct {
	Marked          int64 `json:"marked"`
	AlreadyWatched  int64 `json:"alreadyWatched"`
	LocalKept       int64 `json:"localKept"`
	Unmatched       int64 `json:"unmatched"`
	Ambiguous       int64 `json:"ambiguous"`
	Unavailable     int64 `json:"unavailable"`
	HistoryImported int64 `json:"historyImported"`
	HistorySkipped  int64 `json:"historySkipped"`
}
type PlexRun struct {
	ID              int64                 `json:"id"`
	ConnectionID    int64                 `json:"connectionId"`
	Generation      int64                 `json:"generation"`
	Status          string                `json:"status" enum:"queued,running,complete,partial,failed,cancelled"`
	Phase           string                `json:"phase"`
	Reason          string                `json:"reason,omitempty"`
	StartedAt       string                `json:"startedAt"`
	FinishedAt      *string               `json:"finishedAt,omitempty"`
	Counts          PlexRunCounts         `json:"counts"`
	History         string                `json:"history" doc:"complete, unavailable, partial, not_read or disabled. History is independent of watched marks."`
	HistoryCoverage []PlexHistoryCoverage `json:"historyCoverage,omitempty" required:"false" doc:"Per mapped person/library, the source window read by this run. Complete means fully traversed, not complete lifetime history. Details expire with run/privacy retention."`
}

func runView(r db.PlexSyncRun) PlexRun {
	id := int64(0)
	if r.ConnectionID != nil {
		id = *r.ConnectionID
	}
	return PlexRun{ID: r.ID, ConnectionID: id, Generation: r.Generation, Status: r.Status, Phase: r.Phase, Reason: r.Reason, StartedAt: r.StartedAt, FinishedAt: r.FinishedAt, History: r.History, Counts: PlexRunCounts{r.Marked, r.AlreadyWatched, r.LocalKept, r.Unmatched, r.Ambiguous, r.Unavailable, r.HistoryImported, r.HistorySkipped}}
}

type PlexRunStart struct {
	PreviewID  string `json:"previewId" maxLength:"64"`
	EnableSync bool   `json:"enableSync" default:"false"`
}

func (s *Service) StartRun(ctx context.Context, id int64, who Requester, in PlexRunStart) (PlexRun, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	p := s.previews[id]
	if p == nil || p.result.ID != in.PreviewID || p.consumed || p.result.Status != "complete" || p.result.ExpiresAt == nil || !p.result.ExpiresAt.After(s.now()) {
		return PlexRun{}, fail(Conflict, "preview_not_ready")
	}
	if who.UserID <= 0 || who.Session == "" || p.requester != who {
		return PlexRun{}, fail(Conflict, "preview_session_changed")
	}
	for _, u := range p.result.Users {
		if u.Status != "complete" || u.SkippedSections > 0 {
			return PlexRun{}, fail(Conflict, "preview_incomplete")
		}
	}
	c, err := s.connection(ctx, id)
	if err != nil {
		return PlexRun{}, err
	}
	if c.Generation != p.result.Generation || !s.authorized(ctx, c) || c.CredentialRef == nil {
		return PlexRun{}, fail(Conflict, "stale_generation")
	}
	var r db.PlexSyncRun
	err = s.q.Transaction(ctx, func(q *db.Queries) error {
		fresh, err := q.GetPlexConnection(ctx, id)
		if err != nil {
			return err
		}
		actor, err := q.GetUser(ctx, who.UserID)
		if err != nil {
			return err
		}
		if actor.IsAdmin != 1 {
			return fail(Conflict, "needs_authorization")
		}
		if fresh.Generation != c.Generation {
			return fail(Conflict, "stale_generation")
		}
		r, err = q.InsertPlexRun(ctx, db.InsertPlexRunParams{ConnectionID: &id, Generation: c.Generation, MachineIdentifier: c.MachineIdentifier, SourceEpoch: c.SourceEpoch, ActorID: &who.UserID, EnableSync: db.BoolInt(in.EnableSync), StartedAt: stamp(s.now())})
		if err != nil {
			return runInsertError(err)
		}
		for _, v := range p.scope {
			if err := insertScope(ctx, q, r.ID, v); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return PlexRun{}, err
	}
	p.consumed = true
	s.publishAdmins(ctx, id)
	s.signal()
	return runView(r), nil
}
func insertScope(ctx context.Context, q *db.Queries, run int64, v scopeCandidate) error {
	section, err := strconv.ParseInt(v.SectionID, 10, 64)
	if err != nil {
		return err
	}
	t := v.Found.target
	library := t.Identity.LibraryID
	if library == 0 {
		library = v.LibraryID
	}
	return q.InsertPlexScope(ctx, db.InsertPlexScopeParams{RunID: run, UserID: v.UserID, PlexUserID: v.PlexUserID, Kind: v.Kind, SectionID: section, SectionUuid: v.SectionUUID, RemoteKey: v.RemoteKey, Fingerprint: v.Fingerprint, ItemType: v.Found.example.ItemType, ItemID: t.ItemID, LibraryID: library, TmdbID: t.Identity.TMDBID, Season: t.Identity.Season, Episode: t.Identity.Episode, Category: v.Found.category, SourceKnown: db.BoolInt(v.SourceKnown)})
}
func (s *Service) signal() {
	select {
	case s.wake <- struct{}{}:
	default:
	}
}
func (s *Service) GetRun(ctx context.Context, connection, id int64) (PlexRun, error) {
	r, err := s.run(ctx, connection, id)
	if err != nil {
		return PlexRun{}, err
	}
	return s.coverage(ctx, r)
}
func (s *Service) run(ctx context.Context, connection, id int64) (db.PlexSyncRun, error) {
	r, err := s.q.GetPlexRun(ctx, id)
	if errors.Is(err, sql.ErrNoRows) || err == nil && (r.ConnectionID == nil || *r.ConnectionID != connection) {
		return r, fail(NotFound, "run_not_found")
	}
	return r, err
}

type PlexRunPage struct {
	Items  []PlexRun `json:"items" nullable:"false"`
	Next   *int64    `json:"next,omitempty" required:"false"`
	Active *PlexRun  `json:"active,omitempty" required:"false" doc:"The active import, even when its original run ID is older than this page after a retry."`
}

func (s *Service) Runs(ctx context.Context, id int64) ([]PlexRun, error) {
	page, err := s.RunPage(ctx, id, 30, 0)
	return page.Items, err
}
func (s *Service) RunPage(ctx context.Context, id int64, limit int, before int64) (PlexRunPage, error) {
	if _, err := s.connection(ctx, id); err != nil {
		return PlexRunPage{}, err
	}
	if limit <= 0 || limit > 100 {
		limit = 30
	}
	if before <= 0 {
		before = math.MaxInt64
	}
	rows, err := s.q.ListPlexRunsBefore(ctx, db.ListPlexRunsBeforeParams{ConnectionID: &id, BeforeID: before, PageSize: int64(limit + 1)})
	if err != nil {
		return PlexRunPage{}, err
	}
	page := PlexRunPage{Items: []PlexRun{}}
	active, err := s.q.ListActivePlexRuns(ctx, &id)
	if err != nil {
		return PlexRunPage{}, err
	}
	if len(active) > 0 {
		v, err := s.coverage(ctx, active[0])
		if err != nil {
			return PlexRunPage{}, err
		}
		page.Active = &v
	}
	if len(rows) > limit {
		page.Next = &rows[limit-1].ID
		rows = rows[:limit]
	}
	for _, r := range rows {
		v, err := s.coverage(ctx, r)
		if err != nil {
			return PlexRunPage{}, err
		}
		page.Items = append(page.Items, v)
	}
	return page, nil
}
func (s *Service) CancelRun(ctx context.Context, connection, id int64) (PlexRun, error) {
	if _, err := s.run(ctx, connection, id); err != nil {
		return PlexRun{}, err
	}
	_, err := s.q.CancelPlexRun(ctx, db.CancelPlexRunParams{FinishedAt: new(stamp(s.now())), ID: id})
	if err != nil {
		return PlexRun{}, err
	}
	s.publishAdmins(ctx, connection)
	return s.GetRun(ctx, connection, id)
}
func (s *Service) RetryRun(ctx context.Context, connection, id int64) (PlexRun, error) {
	r, err := s.run(ctx, connection, id)
	if err != nil {
		return PlexRun{}, err
	}
	started, _ := time.Parse(time.RFC3339Nano, r.StartedAt)
	if r.ScopeExpired == 1 || s.now().Sub(started) > 7*24*time.Hour {
		return PlexRun{}, fail(Conflict, "run_scope_expired")
	}
	c, err := s.connection(ctx, connection)
	if err != nil {
		return PlexRun{}, err
	}
	if r.Generation != c.Generation || !s.authorized(ctx, c) || c.CredentialRef == nil {
		return PlexRun{}, fail(Conflict, "stale_generation")
	}
	n, err := s.q.RetryPlexRun(ctx, db.RetryPlexRunParams{RetryAt: stamp(s.now()), ID: id})
	if err != nil {
		return PlexRun{}, runInsertError(err)
	}
	if n == 0 {
		return PlexRun{}, fail(Conflict, "run_not_retryable")
	}
	s.signal()
	s.publishAdmins(ctx, connection)
	return s.GetRun(ctx, connection, id)
}

type PlexSyncUpdate struct {
	Generation int64 `json:"generation"`
	Enabled    bool  `json:"enabled"`
}

func (s *Service) SetSync(ctx context.Context, id int64, in PlexSyncUpdate) (PlexConnection, error) {
	c, err := s.connection(ctx, id)
	if err != nil {
		return PlexConnection{}, err
	}
	who := Requester{}
	if c.AuthorizedBy != nil {
		who.UserID = *c.AuthorizedBy
	}
	return s.SetSyncFor(ctx, id, who, in)
}
func (s *Service) SetSyncFor(ctx context.Context, id int64, who Requester, in PlexSyncUpdate) (PlexConnection, error) {
	c, err := s.connection(ctx, id)
	if err != nil {
		return PlexConnection{}, err
	}
	if c.Generation != in.Generation || in.Enabled && (!s.authorized(ctx, c) || c.CredentialRef == nil) {
		return PlexConnection{}, fail(Conflict, "stale_generation")
	}
	if in.Enabled {
		approved, err := s.q.HasApprovedPlexRun(ctx, db.HasApprovedPlexRunParams{ConnectionID: &id, Generation: c.Generation})
		if err != nil {
			return PlexConnection{}, err
		}
		if !approved {
			return PlexConnection{}, fail(Conflict, "import_first")
		}
	}

	err = s.q.Transaction(ctx, func(q *db.Queries) error {
		actor, err := q.GetUser(ctx, who.UserID)
		if err != nil {
			return err
		}
		if actor.IsAdmin != 1 {
			return fail(Conflict, "needs_authorization")
		}
		if in.Enabled {
			n, err := q.SetPlexScheduleActor(ctx, db.SetPlexScheduleActorParams{ActorID: &who.UserID, ID: id, Generation: in.Generation})
			if err != nil {
				return err
			}
			if n == 0 {
				return fail(Conflict, "needs_authorization")
			}
		}
		n, err := q.SetPlexSync(ctx, db.SetPlexSyncParams{SyncEnabled: db.BoolInt(in.Enabled), NextSyncAt: stamp(s.now().Add(pollInterval)), ID: id, Generation: in.Generation})
		if err != nil {
			return err
		}
		if n == 0 {
			return fail(Conflict, "stale_generation")
		}
		if !in.Enabled {
			rows, err := q.ListActivePlexRuns(ctx, &id)
			if err != nil {
				return err
			}
			for _, r := range rows {
				if _, err := q.CancelPlexRun(ctx, db.CancelPlexRunParams{FinishedAt: new(stamp(s.now())), ID: r.ID}); err != nil {
					return err
				}
			}
		}
		return nil
	})
	if err != nil {
		return PlexConnection{}, err
	}
	c, err = s.connection(ctx, id)
	if err != nil {
		return PlexConnection{}, err
	}
	s.publishAdmins(ctx, id)
	return s.view(ctx, c)
}

// Run owns one bounded remote operation at a time. Durable leases allow a server
// restart to replay an expired attempt, with transaction fencing at every effect.
func (s *Service) Run(ctx context.Context) {
	defer func() {
		s.mu.Lock()
		s.closing = true
		for _, p := range s.previews {
			p.cancel()
		}
		s.mu.Unlock()
		s.work.Wait()
	}()
	timer := time.NewTicker(time.Second * 5)
	defer timer.Stop()
	for {
		if ctx.Err() != nil {
			return
		}
		if err := s.tick(ctx); err != nil && ctx.Err() == nil {
			s.log.Warn("plex: worker failed", "reason", reasonCode(err))
		}
		select {
		case <-ctx.Done():
			return
		case <-s.wake:
		case <-timer.C:
		}
	}
}
func (s *Service) tick(ctx context.Context) error {
	if err := s.cleanup(ctx); err != nil {
		return err
	}
	if err := s.schedule(ctx); err != nil {
		return err
	}
	r, err := s.q.NextPlexRun(ctx, stamp(s.now()))
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	r, err = s.q.ClaimPlexRun(ctx, db.ClaimPlexRunParams{ID: r.ID, Now: stamp(s.now()), LeaseUntil: stamp(s.now().Add(runLease))})
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	run, cancel := context.WithTimeout(ctx, previewTimeout)
	defer cancel()
	done := make(chan struct{})
	go func() {
		defer close(done)
		t := time.NewTicker(30 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-run.Done():
				return
			case <-t.C:
				n, e := s.q.RenewPlexRun(run, db.RenewPlexRunParams{LeaseUntil: stamp(s.now().Add(runLease)), ID: r.ID, Fence: r.Fence})
				if e != nil || n == 0 {
					cancel()
					return
				}
			}
		}
	}()
	history, partial, err := s.execute(run, r)
	if s.beforeFinalize != nil {
		s.beforeFinalize()
	}
	cancel()
	<-done
	if ctx.Err() != nil {
		return nil
	} // Leave the fenced checkpoint for restart.
	status, reason := "complete", ""
	if partial {
		status = "partial"
	}
	if err != nil {
		reason = reasonCode(err)
		status = "failed"
		if reason == "stale_generation" || reason == "cancelled" {
			status = "cancelled"
		}
		if reason == "source_identity_changed" && r.ConnectionID != nil {
			_ = s.q.PausePlexConnection(ctx, db.PausePlexConnectionParams{Problem: reason, ID: *r.ConnectionID, Generation: r.Generation})
		}
		if r.Attempt < 3 && retryablePlexError(err) {
			delay := time.Minute*time.Duration(1<<uint(r.Attempt)) + retryJitter()
			if failure, ok := errors.AsType[*Error](plexFailed(err)); ok && failure.RetryAfter > delay {
				delay = failure.RetryAfter
			}
			_, e := s.q.BackoffPlexRun(ctx, db.BackoffPlexRunParams{Reason: reason, RetryAt: stamp(s.now().Add(delay)), ID: r.ID, Fence: r.Fence})
			return e
		}
	}
	err = s.q.Transaction(ctx, func(q *db.Queries) error {
		n, err := q.FinishPlexRun(ctx, db.FinishPlexRunParams{Status: status, Reason: reason, History: history, FinishedAt: new(stamp(s.now())), ID: r.ID, Fence: r.Fence})
		if err != nil {
			return err
		}
		if n == 0 {
			return nil
		}
		if err := s.cleanupScope(ctx, q); err != nil {
			return err
		}
		if r.ConnectionID == nil {
			return nil
		}
		if (status == "complete" || status == "partial" && reason == "") && r.EnableSync == 1 {
			c, err := q.GetPlexConnection(ctx, *r.ConnectionID)
			if errors.Is(err, sql.ErrNoRows) {
				return nil
			}
			if err != nil {
				return err
			}
			if c.Generation != r.Generation || c.CredentialRef == nil || c.AuthorizedBy == nil {
				return nil
			}
			admin, err := q.GetUser(ctx, *c.AuthorizedBy)
			if err != nil {
				return err
			}
			if admin.IsAdmin != 1 {
				return nil
			}
			if r.ActorID == nil {
				return nil
			}
			n, err := q.SetPlexScheduleActor(ctx, db.SetPlexScheduleActorParams{ActorID: r.ActorID, ID: *r.ConnectionID, Generation: r.Generation})
			if err != nil {
				return err
			}
			if n == 0 {
				return nil
			}
			_, err = q.SetPlexSync(ctx, db.SetPlexSyncParams{SyncEnabled: 1, NextSyncAt: stamp(s.now().Add(pollInterval)), ID: *r.ConnectionID, Generation: r.Generation})
			return err
		}
		return q.SchedulePlexNext(ctx, db.SchedulePlexNextParams{NextSyncAt: stamp(s.now().Add(pollInterval)), ID: *r.ConnectionID, Generation: r.Generation})
	})
	if err == nil && r.ConnectionID != nil {
		s.publishAdmins(ctx, *r.ConnectionID)
	}
	return err
}

func (s *Service) validRun(ctx context.Context, q *db.Queries, r db.PlexSyncRun) error {
	ok, err := q.ValidPlexRun(ctx, db.ValidPlexRunParams{ID: r.ID, Fence: r.Fence, Now: stamp(s.now())})
	if err != nil {
		return err
	}
	if !ok {
		return fail(Conflict, "stale_generation")
	}
	return nil
}
func (s *Service) validScope(ctx context.Context, q *db.Queries, r db.PlexSyncRun, v db.PlexRunScope) error {
	if err := s.validRun(ctx, q, r); err != nil {
		return err
	}
	ok, err := q.HasPlexUserBinding(ctx, db.HasPlexUserBindingParams{ConnectionID: *r.ConnectionID, UserID: v.UserID, PlexUserID: v.PlexUserID, Kind: v.Kind})
	if err != nil {
		return err
	}
	if !ok {
		return fail(Conflict, "stale_generation")
	}
	if v.LibraryID > 0 {
		ok, err = q.HasPlexLibraryBinding(ctx, db.HasPlexLibraryBindingParams{ConnectionID: *r.ConnectionID, SectionID: v.SectionID, SectionUuid: v.SectionUuid, LibraryID: v.LibraryID})
		if err != nil {
			return err
		}
		if !ok {
			return fail(Conflict, "stale_generation")
		}
	}
	return nil
}
func candidateKey(user, section int64, key string) string {
	return strconv.FormatInt(user, 10) + ":" + strconv.FormatInt(section, 10) + ":" + key
}
func (s *Service) execute(ctx context.Context, r db.PlexSyncRun) (string, bool, error) {
	if err := s.validRun(ctx, s.q, r); err != nil {
		return "not_read", false, err
	}
	c, err := s.connection(ctx, *r.ConnectionID)
	if err != nil {
		return "not_read", false, err
	}
	users, err := s.q.ListPlexUserLinks(ctx, c.ID)
	if err != nil {
		return "not_read", false, err
	}
	libs, err := s.q.ListPlexLibraryLinks(ctx, c.ID)
	if err != nil {
		return "not_read", false, err
	}
	if r.WatchComplete == 1 {
		history, err := s.importHistory(ctx, r, c, users, libs)
		return history, history == "partial", err
	}
	fresh := map[string]scopeCandidate{}
	results, _, err := s.runPreview(ctx, c, users, libs, func(v scopeCandidate) error {
		if len(fresh) >= 50000 {
			return fail(Invalid, "scope_limit")
		}
		n, _ := strconv.ParseInt(v.SectionID, 10, 64)
		fresh[candidateKey(v.UserID, n, v.RemoteKey)] = v
		return nil
	})
	if err != nil {
		return "not_read", false, err
	}
	partial := false
	for _, u := range results {
		if u.Status != "complete" || u.SkippedSections > 0 {
			partial = true
		}
	}
	// An incomplete snapshot cannot establish the continued eligibility of an
	// approved watched fact, and contributes no watch writes in this attempt.
	if partial {
		return "not_read", true, fail(Unavailable, "incomplete_snapshot")
	}
	scope, err := s.q.ListPlexScope(ctx, r.ID)
	if err != nil {
		return "not_read", false, err
	}
	if r.Scheduled == 1 && len(scope) == 0 {
		err = s.q.Transaction(ctx, func(q *db.Queries) error {
			if err := s.validRun(ctx, q, r); err != nil {
				return err
			}
			for _, v := range fresh {
				if err := insertScope(ctx, q, r.ID, v); err != nil {
					return err
				}
			}
			return nil
		})
		if err != nil {
			return "not_read", false, err
		}
		scope, err = s.q.ListPlexScope(ctx, r.ID)
		if err != nil {
			return "not_read", false, err
		}
	}
	// A bounded transaction emits one private watched event per item kind,
	// rather than disconnecting every client stream on a household-sized import.
	exclusions, err := s.q.ListPlexExclusions(ctx, c.ID)
	if err != nil {
		return "not_read", false, err
	}
	excluded := map[string]bool{}
	for _, v := range exclusions {
		excluded[exclusionKey(v.UserID, v.ItemType, v.ItemID)] = true
	}
	pending := make([]db.PlexRunScope, 0, 100)
	flush := func() error {
		if len(pending) == 0 {
			return nil
		}
		err := s.applyBatch(ctx, r, pending, fresh, excluded)
		pending = pending[:0]
		return err
	}
	for _, v := range scope {
		if v.Outcome != "" {
			continue
		}
		if len(pending) > 0 && (pending[0].UserID != v.UserID || len(pending) == 100) {
			if err := flush(); err != nil {
				return "not_read", false, err
			}
		}
		pending = append(pending, v)
	}
	if err := flush(); err != nil {
		return "not_read", false, err
	}

	err = s.q.Transaction(ctx, func(q *db.Queries) error {
		if err := s.validRun(ctx, q, r); err != nil {
			return err
		}
		_, err := q.CompletePlexWatchScope(ctx, db.CompletePlexWatchScopeParams{ID: r.ID, Fence: r.Fence})
		return err
	})
	if err != nil {
		return "not_read", false, err
	}
	history, err := s.importHistory(ctx, r, c, users, libs)
	return history, history == "partial", err
}
func (s *Service) verifyFingerprint(ctx context.Context, q *db.Queries, r db.PlexSyncRun, v db.PlexRunScope) error {
	if v.SourceKnown != 1 {
		return nil
	}
	key := db.GetPlexSourceFingerprintParams{MachineIdentifier: r.MachineIdentifier, SourceEpoch: r.SourceEpoch, UserID: v.UserID, SectionID: v.SectionID, RemoteKey: v.RemoteKey}
	old, err := q.GetPlexSourceFingerprint(ctx, key)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	if err == nil && old != v.Fingerprint {
		return fail(Conflict, "source_identity_changed")
	}
	if err := q.InsertPlexSourceFingerprint(ctx, db.InsertPlexSourceFingerprintParams{MachineIdentifier: r.MachineIdentifier, SourceEpoch: r.SourceEpoch, UserID: v.UserID, SectionID: v.SectionID, RemoteKey: v.RemoteKey, Fingerprint: v.Fingerprint}); err != nil {
		return err
	}

	return nil
}
func (s *Service) recordOutcome(ctx context.Context, q *db.Queries, v db.PlexRunScope, outcome string) error {
	n, err := q.SetPlexScopeOutcome(ctx, db.SetPlexScopeOutcomeParams{Outcome: outcome, RunID: v.RunID, UserID: v.UserID, SectionID: v.SectionID, RemoteKey: v.RemoteKey})
	if err != nil {
		return err
	}
	if n == 1 {
		return q.AddPlexOutcomeCounts(ctx, db.AddPlexOutcomeCountsParams{Outcome: outcome, ID: v.RunID})
	}
	return nil
}
func (s *Service) schedule(ctx context.Context) error {
	rows, err := s.q.ListPlexConnections(ctx)
	if err != nil {
		return err
	}
	for _, c := range rows {
		if c.SyncEnabled != 1 || c.NextSyncAt > stamp(s.now()) {
			continue
		}
		if !s.authorized(ctx, c) || c.CredentialRef == nil {
			if err := s.q.PausePlexConnection(ctx, db.PausePlexConnectionParams{Problem: "needs_authorization", ID: c.ID, Generation: c.Generation}); err != nil {
				return err
			}
			continue
		}
		s.mu.Lock()
		if p := s.previews[c.ID]; p != nil && p.result.Status == "running" {
			s.mu.Unlock()
			continue
		}
		err = s.q.Transaction(ctx, func(q *db.Queries) error {
			active, err := q.ListActivePlexRuns(ctx, &c.ID)
			if err != nil {
				return err
			}
			for _, r := range active {
				if r.Status == "queued" || r.Status == "running" {
					return nil
				}
			}
			_, err = q.InsertPlexRun(ctx, db.InsertPlexRunParams{ConnectionID: &c.ID, Generation: c.Generation, MachineIdentifier: c.MachineIdentifier, SourceEpoch: c.SourceEpoch, ActorID: scheduleActor(c), Scheduled: 1, StartedAt: stamp(s.now())})
			return err
		})
		s.mu.Unlock()
		if err != nil {
			return err
		}
	}
	return nil
}

func runInsertError(err error) error {
	if strings.Contains(err.Error(), "UNIQUE constraint failed: plex_sync_runs.connection_id") {
		return fail(Conflict, "run_active")
	}
	return err
}

func retryJitter() time.Duration {
	var b [2]byte
	_, _ = rand.Read(b[:])
	return time.Duration(uint16(b[0])<<8|uint16(b[1])) * (15 * time.Second) / 65536
}
func scheduleActor(c db.PlexConnection) *int64 {
	if c.EnabledBy != nil {
		return c.EnabledBy
	}
	return c.AuthorizedBy
}

func exclusionKey(user int64, kind string, id int64) string {
	return strconv.FormatInt(user, 10) + ":" + kind + ":" + strconv.FormatInt(id, 10)
}
func (s *Service) applyBatch(ctx context.Context, r db.PlexSyncRun, scope []db.PlexRunScope, fresh map[string]scopeCandidate, excluded map[string]bool) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	outcomes := make([]string, len(scope))
	targetIndexes := map[int]int{}
	targets := []watchstate.Target{}
	for i, v := range scope {
		f, watched := fresh[candidateKey(v.UserID, v.SectionID, v.RemoteKey)]
		outcome := v.Category
		approved := watchstate.Target{ItemType: v.ItemType, ItemID: v.ItemID, Identity: watchstate.Identity{LibraryID: v.LibraryID, TMDBID: v.TmdbID, Season: v.Season, Episode: v.Episode}}
		switch {
		case !watched:
			outcome = "unavailable"
		case f.Fingerprint != v.Fingerprint:
			if v.SourceKnown == 1 {
				return fail(Conflict, "source_identity_changed")
			}
			outcome = "unavailable"
		case outcome == "matched" && (f.Found.category != "matched" || f.Found.target != approved):
			outcome = "unavailable"
		case outcome == "matched" && excluded[exclusionKey(v.UserID, v.ItemType, v.ItemID)]:
			outcome = "excluded"
		}
		outcomes[i] = outcome
		if outcome == "matched" {
			targetIndexes[i] = len(targets)
			targets = append(targets, approved)
		}
	}
	if s.beforeImportCommit != nil {
		s.beforeImportCommit()
	}
	_, err := s.watch.ImportChecked(ctx, scope[0].UserID, r.ID, targets, func(q *db.Queries) error {
		for _, v := range scope {
			if err := s.validScope(ctx, q, r, v); err != nil {
				return err
			}
			if _, watched := fresh[candidateKey(v.UserID, v.SectionID, v.RemoteKey)]; watched {
				if err := s.verifyFingerprint(ctx, q, r, v); err != nil {
					return err
				}
			}
		}
		return nil
	}, func(q *db.Queries, applied []watchstate.Outcome) error {
		for i, v := range scope {
			outcome := outcomes[i]
			if index, ok := targetIndexes[i]; ok {
				outcome = string(applied[index])
			}
			if err := s.recordOutcome(ctx, q, v, outcome); err != nil {
				return err
			}
		}
		return s.validRun(ctx, q, r)
	})
	return err
}
