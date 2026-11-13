package plexsync

import (
	"context"
	"errors"
	"strconv"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/plex"
	"watchmymedia/server/internal/watchstate"
)

const (
	// previewTTL is how long a finished preview is kept, in memory only.
	previewTTL = 15 * time.Minute
	// previewTimeout bounds one preview's reads of Plex.
	previewTimeout = 15 * time.Minute
	// maxExamples is how many items each category names, per person.
	maxExamples = 5
	// maxShows bounds the shows whose IDs one preview looks up.
	maxShows = 10000
)

// sectionPaging bounds each library read: running out is an error, never a
// silently shorter preview.
var sectionPaging = plex.Paging{PageSize: 200, MaxPages: 250, MaxItems: 50000}

// PlexPreview is what importing the linked people's watched marks would do
// now, worked out with the rules an import will apply (watchstate.Evaluate),
// without writing anything. It is kept in memory for 15 minutes and goes
// stale as soon as the links change.
type PlexPreview struct {
	ID         string            `json:"id"`
	Status     string            `json:"status" enum:"running,complete,failed,stale" doc:"stale: the links changed since; start another."`
	Reason     string            `json:"reason,omitempty" doc:"Why it failed, as a reason code."`
	Generation int64             `json:"generation" doc:"The links' generation it was made from."`
	StartedAt  time.Time         `json:"startedAt"`
	FinishedAt *time.Time        `json:"finishedAt,omitempty"`
	ExpiresAt  *time.Time        `json:"expiresAt,omitempty"`
	Users      []PlexPreviewUser `json:"users,omitempty" doc:"One per linked person, once complete."`
	Unlinked   []PlexPerson      `json:"unlinked,omitempty" doc:"Everyone on the server who isn't linked, with why not (needs_pin, …). Nothing of theirs is read."`
	History    string            `json:"history" enum:"not_read" doc:"Play history is not read by previews; only current watched marks are."`
}

// PlexPreviewUser is one linked person's part of a preview.
type PlexPreviewUser struct {
	PlexUserID      int64         `json:"plexUserId"`
	Kind            string        `json:"kind" enum:"owner,account,managed"`
	Title           string        `json:"title"`
	UserID          int64         `json:"userId" doc:"The local account the marks would go to."`
	Status          string        `json:"status" enum:"complete,incomplete,failed" doc:"incomplete: some libraries couldn't be read, and are left out of the counts."`
	Reason          string        `json:"reason,omitempty"`
	SkippedSections int           `json:"skippedSections,omitempty" doc:"Linked libraries left out: not visible to this person, or changed in Plex since linking."`
	Counts          PlexCounts    `json:"counts"`
	Examples        []PlexExample `json:"examples" nullable:"false"`
}

// PlexCounts sort every item Plex has in the linked libraries for one person.
type PlexCounts struct {
	WillMark       int `json:"willMark" doc:"Watched in Plex, matched, and undecided here: an import would mark it watched."`
	AlreadyWatched int `json:"alreadyWatched" doc:"Watched in Plex and already watched here."`
	LocalKept      int `json:"localKept" doc:"Watched in Plex, but the person's own activity here decides it (a play in progress, a rewatch, an unwatch)."`
	Unmatched      int `json:"unmatched" doc:"Watched in Plex, with no local item of the same TMDB or IMDb ID."`
	Ambiguous      int `json:"ambiguous" doc:"Watched in Plex, but the IDs conflict, name several local items, or the episode order or edition can't be trusted."`
	Unavailable    int `json:"unavailable" doc:"Matched, but the local item changed or went while the preview ran."`
	NotWatched     int `json:"notWatched" doc:"Not watched in Plex: an import changes nothing for it."`
	UnknownState   int `json:"unknownState" doc:"Plex didn't say whether it is watched."`
}

// PlexExample names one item in a category. Titles are Plex's.
type PlexExample struct {
	Category string `json:"category" enum:"willMark,alreadyWatched,localKept,unmatched,ambiguous,unavailable"`
	ItemType string `json:"itemType" enum:"movie,episode"`
	ItemID   int64  `json:"itemId,omitempty" doc:"The local movie or episode, when matched."`
	Title    string `json:"title"`
	Show     string `json:"show,omitempty"`
	Season   int64  `json:"season,omitempty"`
	Episode  int64  `json:"episode,omitempty"`
	Reason   string `json:"reason,omitempty" doc:"Why it is unmatched or ambiguous, as a reason code."`
}

type preview struct {
	cancel context.CancelFunc
	result PlexPreview
	// done closes when the preview's goroutine ends.
	done      chan struct{}
	scope     []scopeCandidate
	requester Requester
	consumed  bool
}

// StartPreview starts a preview of the connection's links in the
// background; GetPreview reads it. One runs per connection at a time.
func (s *Service) StartPreview(ctx context.Context, id int64) (PlexPreview, error) {
	return s.StartPreviewFor(ctx, id, Requester{})
}

// StartPreviewFor binds an actionable preview to the requesting admin and session.
func (s *Service) StartPreviewFor(ctx context.Context, id int64, who Requester) (PlexPreview, error) {
	c, err := s.connection(ctx, id)
	if err != nil {
		return PlexPreview{}, err
	}
	if c.CredentialRef == nil {
		return PlexPreview{}, fail(Conflict, "needs_reconnect")
	}
	if !s.authorized(ctx, c) {
		return PlexPreview{}, fail(Conflict, "needs_authorization")
	}
	users, err := s.q.ListPlexUserLinks(ctx, id)
	if err != nil {
		return PlexPreview{}, err
	}
	libs, err := s.q.ListPlexLibraryLinks(ctx, id)
	if err != nil {
		return PlexPreview{}, err
	}
	if len(users) == 0 || len(libs) == 0 {
		return PlexPreview{}, fail(Conflict, "nothing_linked")
	}
	pid, err := randomHex(16)
	if err != nil {
		return PlexPreview{}, err
	}
	s.mu.Lock()
	if s.closing || s.base.Err() != nil {
		s.mu.Unlock()
		return PlexPreview{}, fail(Conflict, "server_stopping")
	}
	active, err := s.q.ListActivePlexRuns(ctx, &id)
	if err != nil {
		s.mu.Unlock()
		return PlexPreview{}, err
	}
	if len(active) > 0 {
		s.mu.Unlock()
		return PlexPreview{}, fail(Conflict, "run_active")
	}
	if p := s.previews[id]; p != nil && p.result.Status == "running" {
		s.mu.Unlock()
		return PlexPreview{}, fail(Conflict, "preview_running")
	}
	run, cancel := context.WithTimeout(s.base, previewTimeout)
	p := &preview{cancel: cancel, done: make(chan struct{}), requester: who,
		result: PlexPreview{ID: pid, Status: "running", Generation: c.Generation, StartedAt: s.now(), History: "not_read"}}
	s.previews[id] = p
	s.work.Add(1)
	started := p.result
	s.mu.Unlock()
	go func() {
		defer s.work.Done()
		defer close(p.done)
		defer cancel()
		var scope []scopeCandidate
		users, unlinked, err := s.runPreview(run, c, users, libs, true, func(v scopeCandidate) error {
			if len(scope) >= 50000 {
				return fail(Invalid, "scope_limit")
			}
			scope = append(scope, v)
			return nil
		})
		s.mu.Lock()
		defer s.mu.Unlock()
		if s.previews[id] != p {
			return // dropped meanwhile
		}
		done := s.now()
		expires := done.Add(previewTTL)
		p.result.FinishedAt, p.result.ExpiresAt = &done, &expires
		if err != nil {
			p.result.Status, p.result.Reason = "failed", reasonCode(err)
			s.log.Info("plex: preview failed", "connection", id, "reason", p.result.Reason)
			return
		}
		p.result.Status, p.result.Users, p.result.Unlinked = "complete", users, unlinked
		p.scope = scope
	}()
	return started, nil
}

// GetPreview reads a preview. One made under older links is stale and
// shows nothing more; one past its time is gone.
func (s *Service) GetPreview(ctx context.Context, id int64, previewID string) (PlexPreview, error) {
	c, err := s.connection(ctx, id)
	if err != nil {
		return PlexPreview{}, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	p := s.previews[id]
	if p == nil || p.result.ID != previewID {
		return PlexPreview{}, fail(NotFound, "preview_not_found")
	}
	if p.result.ExpiresAt != nil && !p.result.ExpiresAt.After(s.now()) {
		delete(s.previews, id)
		return PlexPreview{}, fail(NotFound, "preview_not_found")
	}
	if p.result.Generation != c.Generation {
		p.cancel()
		p.result = PlexPreview{ID: p.result.ID, Status: "stale", Generation: p.result.Generation, StartedAt: p.result.StartedAt,
			FinishedAt: p.result.FinishedAt, ExpiresAt: p.result.ExpiresAt, History: "not_read"}
	}
	return p.result, nil
}

// dropPreview stops and forgets the connection's preview.
func (s *Service) dropPreview(id int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if p := s.previews[id]; p != nil {
		p.cancel()
		delete(s.previews, id)
	}
}

func reasonCode(err error) string {
	if e, ok := errors.AsType[*Error](err); ok {
		return e.Code
	}
	if k := plex.ErrorKind(err); k != "unknown" {
		return k
	}
	return "internal_error"
}

// runPreview reads each linked person's watched marks through their own
// credential, matches them by exact IDs and evaluates them against what
// the local account already decided. One Plex request at a time. Without
// counts it skips that last step: an import decides each item again when it
// writes, and needs only the statuses.
func (s *Service) runPreview(ctx context.Context, c db.PlexConnection, links []db.PlexUserLink, libs []db.ListPlexLibraryLinksRow, counts bool, collect func(scopeCandidate) error) ([]PlexPreviewUser, []PlexPerson, error) {
	o, err := s.openOwner(ctx, c)
	if err != nil {
		return nil, nil, err
	}
	people, err := s.people(ctx, o)
	if err != nil {
		return nil, nil, err
	}
	byID := make(map[int64]PlexPerson, len(people))
	for _, p := range people {
		byID[p.PlexUserID] = p
	}
	linked := make(map[int64]bool, len(links))
	for _, l := range links {
		linked[l.PlexUserID] = true
	}
	unlinked := []PlexPerson{}
	for _, p := range people {
		if !linked[p.PlexUserID] {
			unlinked = append(unlinked, p)
		}
	}
	// A linked library applies while its section is still the one linked:
	// same ID, same UUID, a type that matches the local library's.
	sections := make(map[int64]plex.LibrarySection, len(o.sections))
	for _, sec := range o.sections {
		if n, err := strconv.ParseInt(sec.ID, 10, 64); err == nil {
			sections[n] = sec
		}
	}
	type target struct {
		section   plex.LibrarySection
		libraryID int64
	}
	var targets []target
	changed := 0
	for _, l := range libs {
		sec, ok := sections[l.SectionID]
		if !ok || sec.UUID != l.SectionUuid || localType(sec.Type) != l.LibraryType {
			changed++
			continue
		}
		targets = append(targets, target{sec, l.LibraryID})
	}
	m := &matcher{q: s.q, owner: o.pms, shows: map[string]*showIDs{}}
	out := make([]PlexPreviewUser, 0, len(links))
	for _, l := range links {
		if err := ctx.Err(); err != nil {
			return nil, nil, err
		}
		u := PlexPreviewUser{PlexUserID: l.PlexUserID, Kind: l.Kind, UserID: l.UserID, Status: "complete", SkippedSections: changed, Examples: []PlexExample{}}
		p, ok := byID[l.PlexUserID]
		switch {
		case !ok:
			u.Status, u.Reason = "failed", "not_on_server"
		case p.Status != StatusReady:
			u.Status, u.Reason = "failed", p.Status
		case p.Kind != l.Kind:
			u.Status, u.Reason = "failed", "conflicting_kind"
		}
		u.Title = p.Title
		if u.Status == "failed" {
			out = append(out, u)
			continue
		}
		client, err := s.personClient(ctx, o, p)
		if err != nil {
			if retryablePlexError(err) {
				return nil, nil, plexFailed(err)
			}
			u.Status, u.Reason = "failed", reasonCode(err)
			out = append(out, u)
			continue
		}
		visible := map[string]bool{}
		if l.Kind == KindOwner {
			for _, sec := range o.sections {
				visible[sec.ID] = true
			}
		} else if secs, err := client.Libraries(ctx); err != nil {
			if retryablePlexError(err) {
				return nil, nil, plexFailed(err)
			}
			u.Status, u.Reason = "failed", reasonCode(err)
			out = append(out, u)
			continue
		} else {
			for _, sec := range secs {
				visible[sec.ID] = true
			}
		}
		var found []found
		for _, t := range targets {
			if !visible[t.section.ID] {
				u.SkippedSections++
				continue
			}
			snap, err := client.Snapshot(ctx, t.section, sectionPaging)
			if err != nil {
				if ctx.Err() != nil {
					return nil, nil, ctx.Err()
				}
				if retryablePlexError(err) {
					return nil, nil, plexFailed(err)
				}
				u.Status, u.Reason = "incomplete", reasonCode(err)
				u.SkippedSections++
				continue
			}
			for _, item := range snap.Items {
				watched, known := item.Watched()
				switch {
				case !known:
					u.Counts.UnknownState++
					continue
				case !watched:
					u.Counts.NotWatched++
					continue
				}
				f, err := m.match(ctx, t.libraryID, item)
				if err != nil {
					return nil, nil, err
				}
				if collect != nil {
					if err := collect(scopeCandidate{LibraryID: t.libraryID, UserID: l.UserID, PlexUserID: l.PlexUserID, Kind: l.Kind, SectionID: t.section.ID, SectionUUID: t.section.UUID, RemoteKey: item.RatingKey, Fingerprint: candidateFingerprint(item, f), SourceKnown: f.category == "matched" || (f.category == "unmatched" && f.example.Reason == "not_in_library"), Found: f}); err != nil {
						return nil, nil, err
					}
				}
				found = append(found, f)
			}
		}
		if counts {
			if err := s.evaluate(ctx, c.ID, l.UserID, found, &u); err != nil {
				return nil, nil, err
			}
		}
		out = append(out, u)
	}
	return out, unlinked, nil
}

// evaluate sorts one person's matched items with watchstate's import rules
// and counts the rest.
func (s *Service) evaluate(ctx context.Context, connectionID, userID int64, items []found, u *PlexPreviewUser) error {
	var targets []watchstate.Target
	var matched []found
	for _, f := range items {
		switch f.category {
		case "unmatched":
			u.Counts.Unmatched++
			u.example(f)
		case "ambiguous":
			u.Counts.Ambiguous++
			u.example(f)
		default:
			excluded, err := s.q.HasPlexExclusion(ctx, db.HasPlexExclusionParams{ConnectionID: connectionID, UserID: userID, ItemType: f.target.ItemType, ItemID: f.target.ItemID})
			if err != nil {
				return err
			}
			if excluded {
				u.Counts.LocalKept++
				f.category = "localKept"
				f.example.Reason = "excluded"
				u.example(f)
				continue
			}
			targets = append(targets, f.target)
			matched = append(matched, f)
		}
	}
	if len(targets) == 0 {
		return nil
	}
	outcomes, err := s.watch.Evaluate(ctx, userID, targets)
	if err != nil {
		return err
	}
	for i, o := range outcomes {
		f := matched[i]
		switch o {
		case watchstate.Applied:
			u.Counts.WillMark++
			f.category = "willMark"
		case watchstate.AlreadyWatched:
			u.Counts.AlreadyWatched++
			f.category = "alreadyWatched"
		case watchstate.LocalKept:
			u.Counts.LocalKept++
			f.category = "localKept"
		default:
			u.Counts.Unavailable++
			f.category = "unavailable"
		}
		u.example(f)
	}
	return nil
}

func (u *PlexPreviewUser) example(f found) {
	n := 0
	for _, e := range u.Examples {
		if e.Category == f.category {
			n++
		}
	}
	if n >= maxExamples {
		return
	}
	e := f.example
	e.Category = f.category
	if f.category != "unmatched" && f.category != "ambiguous" {
		e.ItemID = f.target.ItemID
	}
	u.Examples = append(u.Examples, e)
}
