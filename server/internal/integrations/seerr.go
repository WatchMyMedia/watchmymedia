package integrations

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/integrations/arr"
	"watchmymedia/server/internal/integrations/seerr"
	"watchmymedia/server/internal/memo"
)

// Seerr as the request backend (PLAN-ARR.md §7.3). With Seerr connected,
// requests go to Seerr as each person's linked Seerr user, so Seerr's
// permissions, quotas, approvals and notifications apply; the server's own
// requests table is left as it was. Problem reports become Seerr issues.

// seerrFresh is how long Seerr's record of an item is kept. Asking for the
// item, cancelling a request, or a webhook drops it sooner.
const seerrFresh = time.Minute

// ErrRefused is Seerr (or the server on its behalf) declining a request,
// with the HTTP status the API answers with and a message for the person.
type ErrRefused struct {
	Status int
	Reason string
}

func (e *ErrRefused) Error() string { return e.Reason }

// IsRefused reports whether err is a refusal the person should read.
func IsRefused(err error) (int, string, bool) {
	if e, ok := errors.AsType[*ErrRefused](err); ok {
		return e.Status, e.Reason, true
	}
	return 0, "", false
}

// itemInfo is what a request shows of an item. Titles barely change, so
// they are kept for as long as the server runs.
type itemInfo struct {
	title  string
	year   int64
	poster string
}

func seerrKey(mediaType string, tmdbID int) string { return mediaType + ":" + strconv.Itoa(tmdbID) }

// seerrConn returns the Seerr connection, or nil.
func (s *Service) seerrConn() *conn {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, c := range s.sorted() {
		if c.seerr != nil {
			return c
		}
	}
	return nil
}

// Seerr reports whether Seerr carries requests out.
func (s *Service) Seerr() bool { return s.seerrConn() != nil }

// HasArr reports whether a Radarr or Sonarr is connected, for the admin's
// search, monitor and approve buttons.
func (s *Service) HasArr() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, c := range s.conns {
		if c.arr != nil {
			return true
		}
	}
	return false
}

// SeerrUser is a Seerr user as the Users page shows it.
type SeerrUser struct {
	ID         int64    `json:"id"`
	Name       string   `json:"name" doc:"Seerr's display name."`
	Names      []string `json:"names" nullable:"false" doc:"Every name Seerr has for the user (username, Plex and Jellyfin names, email), for suggesting links."`
	CanRequest bool     `json:"canRequest" doc:"Seerr lets the user request movies or shows."`
}

// SeerrUsers reads Seerr's users now.
func (s *Service) SeerrUsers(ctx context.Context) ([]SeerrUser, error) {
	c := s.seerrConn()
	if c == nil {
		return nil, invalid("Seerr isn't connected")
	}
	users, err := c.seerr.Users(ctx)
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	c.seerrUsers = users
	s.mu.Unlock()
	out := make([]SeerrUser, 0, len(users))
	for _, u := range users {
		su := SeerrUser{ID: int64(u.ID), Name: u.DisplayName, Names: []string{}, CanRequest: u.CanRequest("movie") || u.CanRequest("tv")}
		for _, n := range []string{u.Username, u.PlexUsername, u.JellyfinUsername, u.Email} {
			if n != "" && !slices.Contains(su.Names, n) {
				su.Names = append(su.Names, n)
			}
		}
		out = append(out, su)
	}
	return out, nil
}

// LinkSeerrUser sets the Seerr user a person requests as; 0 unlinks. A link
// is checked against Seerr's users first.
func (s *Service) LinkSeerrUser(ctx context.Context, userID, seerrUserID int64) error {
	if seerrUserID == 0 {
		return s.q.SetUserSeerrUser(ctx, db.SetUserSeerrUserParams{ID: userID})
	}
	users, err := s.SeerrUsers(ctx)
	if err != nil {
		return err
	}
	if !slices.ContainsFunc(users, func(u SeerrUser) bool { return u.ID == seerrUserID }) {
		return invalid("Seerr has no user %d", seerrUserID)
	}
	return s.q.SetUserSeerrUser(ctx, db.SetUserSeerrUserParams{SeerrUserID: &seerrUserID, ID: userID})
}

// seerrUserOf returns the Seerr user p is linked to, and Seerr's record of
// it when the users have been read. ok is false when p is not linked, or
// is linked to a user Seerr no longer has.
func (s *Service) seerrUserOf(ctx context.Context, c *conn, p Person) (id int, u *seerr.User, ok bool) {
	row, err := s.q.GetUser(ctx, p.ID)
	if err != nil || row.SeerrUserID == nil || *row.SeerrUserID <= 0 {
		return 0, nil, false
	}
	id = int(*row.SeerrUserID)
	s.mu.RLock()
	users := c.seerrUsers
	s.mu.RUnlock()
	if users == nil {
		return id, nil, true
	}
	for i := range users {
		if users[i].ID == id {
			return id, &users[i], true
		}
	}
	return id, nil, false
}

// SeerrAccess is what a person may do with Seerr as the backend.
type SeerrAccess struct {
	Linked        bool
	Movies, Shows bool
	NeedsApproval bool
}

// SeerrAccess reads p's linked Seerr user's permissions. When Seerr's users
// haven't been read yet, a linked person may try and Seerr decides.
func (s *Service) SeerrAccess(ctx context.Context, p Person) SeerrAccess {
	c := s.seerrConn()
	if c == nil {
		return SeerrAccess{}
	}
	_, u, ok := s.seerrUserOf(ctx, c, p)
	if !ok {
		return SeerrAccess{}
	}
	a := SeerrAccess{Linked: true, Movies: true, Shows: true}
	if u != nil {
		a.Movies, a.Shows = u.CanRequest("movie"), u.CanRequest("tv")
		a.NeedsApproval = (a.Movies && !u.AutoApproved("movie")) || (a.Shows && !u.AutoApproved("tv"))
	}
	return a
}

// --- what Seerr says about items -------------------------------------------

// seerrMedia returns Seerr's lookup of an item, from the cache when fresh.
// A page waits at most stateWait for it, pages asking at once share one
// call, and a failure is kept for stateFresh so the items of a page don't
// each wait on a Seerr that doesn't answer.
func (s *Service) seerrMedia(ctx context.Context, c *conn, mediaType string, tmdbID int) (seerr.Media, error) {
	key := seerrKey(mediaType, tmdbID)
	if m, ok := c.seerrMedia.Get(key); ok {
		return m.v, m.err
	}
	if err := s.reachable(c); err != nil {
		return seerr.Media{}, err
	}
	return memo.Do(ctx, &s.flight, fmt.Sprintf("%d/media/%s", c.row.ID, key), func(ctx context.Context) (seerr.Media, error) {
		ctx, cancel := context.WithTimeout(ctx, stateWait)
		defer cancel()
		v, err := c.seerr.Lookup(ctx, mediaType, tmdbID)
		if err != nil {
			c.seerrMedia.SetFor(key, cached[seerr.Media]{at: time.Now(), err: err}, stateFresh)
			return v, err
		}
		c.seerrMedia.Set(key, cached[seerr.Media]{at: time.Now(), v: v})
		s.remember(v)
		return v, nil
	})
}

// dropSeerr forgets Seerr's record of an item, after it changed.
func (s *Service) dropSeerr(mediaType string, tmdbID int) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, c := range s.conns {
		if c.seerrMedia != nil {
			c.seerrMedia.Delete(seerrKey(mediaType, tmdbID))
		}
	}
}

func (s *Service) remember(m seerr.Media) {
	if m.ID == 0 || m.Label() == "" {
		return
	}
	info := itemInfo{title: m.Label(), poster: m.PosterPath}
	if y := yearOf(m.Date()); y != nil {
		info.year = *y
	}
	s.titles.Set(seerrKey(m.MediaType, m.ID), info)
}

// infoFor returns what a request shows of an item, looking it up in Seerr
// when it isn't known yet.
func (s *Service) infoFor(ctx context.Context, c *conn, mediaType string, tmdbID int) itemInfo {
	key := seerrKey(mediaType, tmdbID)
	info, ok := s.titles.Get(key)
	if !ok {
		if _, err := s.seerrMedia(ctx, c, mediaType, tmdbID); err == nil {
			info, _ = s.titles.Get(key)
		}
	}
	return info
}

// Found is a movie or show from Seerr's search or lookup, with Seerr's
// record of it.
type Found struct {
	MediaType  string
	TMDBID     int64
	Title      string
	Year       int64
	Overview   string
	PosterPath string
	// Status is Seerr's media status; 0 when Seerr has no record.
	Status int
	// Seasons and Requests come from a lookup only. Requests are newest
	// first, as the person sees them.
	Seasons  []FoundSeason
	Requests []RequestState
	Download *Download
}

type FoundSeason struct {
	Number       int64
	Name         string
	EpisodeCount int64
	AirDate      string
	// Taken means Seerr would not take a request for the season: it is
	// asked for, downloading, or the *arr app has it.
	Taken bool
}

// SeerrSearch searches Seerr for movies and shows.
func (s *Service) SeerrSearch(ctx context.Context, p Person, query string) ([]Found, error) {
	c := s.seerrConn()
	if c == nil {
		return nil, invalid("Seerr isn't connected")
	}
	results, err := c.seerr.Search(ctx, query)
	if err != nil {
		return nil, err
	}
	uid, _, _ := s.seerrUserOf(ctx, c, p)
	out := make([]Found, 0, len(results))
	for _, m := range results {
		s.remember(m)
		out = append(out, s.found(ctx, uid, m))
	}
	return out, nil
}

// SeerrLookup looks a movie or show up in Seerr, with its seasons and
// requests.
func (s *Service) SeerrLookup(ctx context.Context, p Person, mediaType string, tmdbID int) (Found, error) {
	c := s.seerrConn()
	if c == nil {
		return Found{}, invalid("Seerr isn't connected")
	}
	m, err := s.seerrMedia(ctx, c, mediaType, tmdbID)
	if err != nil {
		if seerr.StatusOf(err) == http.StatusNotFound {
			return Found{}, errNotFound
		}
		return Found{}, err
	}
	uid, _, _ := s.seerrUserOf(ctx, c, p)
	return s.found(ctx, uid, m), nil
}

// found is what Seerr says about an item, as the person linked to Seerr
// user uid sees it.
func (s *Service) found(ctx context.Context, uid int, m seerr.Media) Found {
	f := Found{MediaType: m.MediaType, TMDBID: int64(m.ID), Title: m.Label(), Overview: m.Overview, PosterPath: m.PosterPath}
	if y := yearOf(m.Date()); y != nil {
		f.Year = *y
	}
	taken := map[int]bool{}
	if mi := m.MediaInfo; mi != nil {
		f.Status = mi.Status
		for _, r := range mi.Requests {
			if r.Is4K {
				continue
			}
			st := s.seerrState(ctx, uid, m.MediaType, m.ID, r)
			f.Requests = append(f.Requests, st)
			if st.Status == "pending" || st.Status == "approved" {
				for _, n := range st.Seasons {
					taken[n] = true
				}
			}
		}
		slices.SortStableFunc(f.Requests, func(a, b RequestState) int { return b.CreatedAt.Compare(a.CreatedAt) })
		for _, se := range mi.Seasons {
			if se.Status != seerr.StatusUnknown && se.Status != seerr.StatusDeleted && se.Status != 0 {
				taken[se.SeasonNumber] = true
			}
		}
		if len(mi.DownloadStatus) > 0 {
			f.Download = downloadFromSeerr(mi.DownloadStatus[0])
		}
	}
	for _, se := range m.Seasons {
		f.Seasons = append(f.Seasons, FoundSeason{Number: int64(se.SeasonNumber), Name: se.Name, EpisodeCount: int64(se.EpisodeCount),
			AirDate: se.AirDate, Taken: taken[se.SeasonNumber]})
	}
	return f
}

// seerrState is a Seerr request as a person linked to Seerr user uid sees
// it. Like the server's own requests, it is available once the library has
// what it asked for.
func (s *Service) seerrState(ctx context.Context, uid int, mediaType string, tmdbID int, r seerr.MediaRequest) RequestState {
	st := RequestState{ID: int64(r.ID), Status: seerrRequestStatus(r.Status), Seasons: nonNilInts(r.SeasonNumbers()), CreatedAt: r.CreatedAt}
	if r.RequestedBy != nil {
		st.RequestedBy = r.RequestedBy.DisplayName
		st.Mine = uid > 0 && r.RequestedBy.ID == uid
	}
	if st.Status == "approved" || st.Status == "pending" {
		if done, err := s.inLibrary(ctx, mediaType, int64(tmdbID), st.Seasons); err == nil && done {
			st.Status = "available"
		}
	}
	return st
}

func seerrRequestStatus(status int) string {
	switch status {
	case seerr.RequestApproved:
		return "approved"
	case seerr.RequestDeclined:
		return "declined"
	case seerr.RequestFailed:
		return "failed"
	case seerr.RequestCompleted:
		return "available"
	}
	return "pending"
}

// downloadFromSeerr reads one of the downloads Seerr tracks in the *arr
// queues; Seerr passes on the queue's status but not the import state.
func downloadFromSeerr(d seerr.DownloadStatus) *Download {
	q := arr.QueueItem{Size: d.Size, SizeLeft: d.SizeLeft, EstimatedCompletion: d.EstimatedCompletionTime, Status: d.Status}
	if d.Status == "completed" {
		q.TrackedDownloadState = "importPending"
	}
	return downloadFrom(q)
}

// seerrForMedia is ForMedia with Seerr as the backend: Seerr's requests for
// the item, newest first.
func (s *Service) seerrForMedia(ctx context.Context, c *conn, p Person, mediaType string, tmdbID int64) []RequestState {
	m, err := s.seerrMedia(ctx, c, mediaType, int(tmdbID))
	if err != nil {
		s.log.Debug("seerr lookup", "tmdb", tmdbID, "err", err)
		return nil
	}
	uid, _, _ := s.seerrUserOf(ctx, c, p)
	return s.found(ctx, uid, m).Requests
}

// --- asking ----------------------------------------------------------------

// askSeerr sends a request to Seerr as p's linked Seerr user.
func (s *Service) askSeerr(ctx context.Context, c *conn, p Person, mediaType string, tmdbID int, seasons []int) (Request, bool, error) {
	uid, _, ok := s.seerrUserOf(ctx, c, p)
	if !ok {
		return Request{}, false, &ErrRefused{Status: http.StatusForbidden,
			Reason: "Your account isn't linked to a Seerr user, so you can't request yet. An admin can link it under Settings → Users."}
	}
	if mediaType == "movie" {
		seasons = nil
	} else if len(seasons) == 0 {
		return Request{}, false, invalid("pick at least one season")
	}
	slices.Sort(seasons)
	seasons = slices.Compact(seasons)
	if done, err := s.inLibrary(ctx, mediaType, int64(tmdbID), seasons); err != nil {
		return Request{}, false, err
	} else if done {
		return Request{}, false, invalid("it's in your library already")
	}
	r, err := c.seerr.Request(ctx, uid, mediaType, tmdbID, seasons)
	s.dropSeerr(mediaType, tmdbID)
	if err != nil {
		return Request{}, false, seerrRefusal(err)
	}
	out := s.seerrRequest(ctx, c, uid, mediaType, tmdbID, r)
	s.announce(out.ID, p.ID, out.Status)
	return out, true, nil
}

// seerrRefusal turns Seerr's answers to a request into messages for the
// person who asked. Anything else comes back as it is, for a 502.
func seerrRefusal(err error) error {
	var e *seerr.Error
	if !errors.As(err, &e) {
		return err
	}
	switch e.Status {
	case http.StatusAccepted:
		return &ErrRefused{Status: http.StatusConflict, Reason: "Nothing left to request: Seerr has every season you picked requested or available already."}
	case http.StatusConflict:
		return &ErrRefused{Status: http.StatusConflict, Reason: "This is requested in Seerr already."}
	case http.StatusForbidden:
		msg := strings.ToLower(e.Message)
		switch {
		case strings.Contains(msg, "quota"):
			return &ErrRefused{Status: http.StatusForbidden, Reason: "You've reached your request limit in Seerr. You can ask again once it resets."}
		case strings.Contains(msg, "blocklist"), strings.Contains(msg, "blacklist"):
			return &ErrRefused{Status: http.StatusForbidden, Reason: "This is on Seerr's blocklist, so it can't be requested."}
		}
		return &ErrRefused{Status: http.StatusForbidden, Reason: "Seerr doesn't let you request this: " + e.Message}
	}
	return err
}

// seerrRequest is a Seerr request in a list, as the person linked to uid
// sees it.
func (s *Service) seerrRequest(ctx context.Context, c *conn, uid int, mediaType string, tmdbID int, r seerr.MediaRequest) Request {
	info := s.infoFor(ctx, c, mediaType, tmdbID)
	out := Request{RequestState: s.seerrState(ctx, uid, mediaType, tmdbID, r), MediaType: mediaType, TMDBID: int64(tmdbID),
		Title: info.title, Year: info.year, PosterPath: info.poster}
	if mediaType == "movie" {
		if m, err := s.q.GetLibraryMovieByTMDB(ctx, int64(tmdbID)); err == nil {
			out.MovieID = m.ID
		}
	} else if sh, err := s.q.GetLibraryShowByTMDB(ctx, int64(tmdbID)); err == nil {
		out.ShowID = sh.ID
	}
	if out.Status == "approved" && r.Media != nil {
		for _, d := range r.Media.DownloadStatus {
			if mediaType == "movie" || d.Episode == nil || slices.Contains(out.Seasons, d.Episode.SeasonNumber) {
				out.Download = downloadFromSeerr(d)
				break
			}
		}
	}
	return out
}

// mineSeerr lists p's requests in Seerr, newest first.
func (s *Service) mineSeerr(ctx context.Context, c *conn, p Person) ([]Request, error) {
	uid, _, ok := s.seerrUserOf(ctx, c, p)
	if !ok {
		return []Request{}, nil
	}
	reqs, err := c.seerr.UserRequests(ctx, uid, 100)
	if err != nil {
		return nil, err
	}
	// Seerr's list has no titles; look the unknown ones up a few at a time.
	var wg sync.WaitGroup
	sem := make(chan struct{}, 4)
	for _, r := range reqs {
		if r.Media == nil {
			continue
		}
		wg.Go(func() {
			sem <- struct{}{}
			defer func() { <-sem }()
			s.infoFor(ctx, c, r.Media.MediaType, r.Media.TMDBID)
		})
	}
	wg.Wait()
	out := make([]Request, 0, len(reqs))
	for _, r := range reqs {
		if r.Media == nil || r.Is4K {
			continue
		}
		out = append(out, s.seerrRequest(ctx, c, uid, r.Media.MediaType, r.Media.TMDBID, r))
	}
	return out, nil
}

// cancelSeerr withdraws p's request in Seerr while it waits.
func (s *Service) cancelSeerr(ctx context.Context, c *conn, p Person, id int64) error {
	uid, _, ok := s.seerrUserOf(ctx, c, p)
	if !ok {
		return errNotFound
	}
	err := c.seerr.DeleteRequest(ctx, uid, int(id))
	switch seerr.StatusOf(err) {
	case 0:
		if err == nil {
			// Cancellation returns no media identity. Drop this connection's
			// cached items before clients refetch on the change event, so a
			// removed request cannot keep an item marked as requested.
			c.seerrMedia.Clear()
			s.announce(id, p.ID, "cancelled")
		}
		return err
	case http.StatusNotFound:
		return errNotFound
	case http.StatusUnauthorized, http.StatusForbidden:
		return invalid("only a request that is waiting can be cancelled")
	}
	return err
}

// SeerrInbox is the Inbox's note that requests are approved in Seerr.
type SeerrInbox struct {
	RequestsURL string `json:"requestsUrl" doc:"Seerr's requests page, where admins approve and decline."`
	Pending     *int64 `json:"pending,omitempty" doc:"Requests waiting for approval in Seerr; absent when Seerr couldn't be reached."`
}

// SeerrInbox returns the Inbox's Seerr note, or nil without Seerr.
func (s *Service) SeerrInbox(ctx context.Context) *SeerrInbox {
	c := s.seerrConn()
	if c == nil {
		return nil
	}
	s.mu.RLock()
	base := c.seerrURL
	s.mu.RUnlock()
	if base == "" {
		base = c.row.Url
	}
	out := &SeerrInbox{RequestsURL: strings.TrimRight(base, "/") + "/requests?filter=pending"}
	ctx, cancel := context.WithTimeout(ctx, stateWait)
	defer cancel()
	if n, err := c.seerr.RequestCounts(ctx); err == nil {
		out.Pending = &n.Pending
	}
	return out
}

// --- problems --------------------------------------------------------------

// reportSeerr files a problem as a Seerr issue, as p's linked Seerr user.
// It returns false when Seerr can't take it (p isn't linked, Seerr has no
// record of the item, or refused), and the report goes to the Inbox.
func (s *Service) reportSeerr(ctx context.Context, c *conn, p Person, itemType string, itemID int64, kind, note, details string) (Problem, bool) {
	uid, _, ok := s.seerrUserOf(ctx, c, p)
	if !ok {
		return Problem{}, false
	}
	mediaType, tmdbID, season, episode := "movie", int64(0), int64(0), int64(0)
	if itemType == "movie" {
		m, err := s.q.GetMovie(ctx, itemID)
		if err != nil {
			return Problem{}, false
		}
		tmdbID = m.TmdbID
	} else {
		e, err := s.q.GetEpisode(ctx, itemID)
		if err != nil {
			return Problem{}, false
		}
		sh, err1 := s.q.GetShow(ctx, e.ShowID)
		se, err2 := s.q.GetSeason(ctx, e.SeasonID)
		if err1 != nil || err2 != nil {
			return Problem{}, false
		}
		mediaType, tmdbID, season, episode = "tv", sh.TmdbID, se.Number, e.Number
	}
	m, err := s.seerrMedia(ctx, c, mediaType, int(tmdbID))
	if err != nil || m.MediaInfo == nil || m.MediaInfo.ID == 0 {
		return Problem{}, false
	}
	issue, err := c.seerr.CreateIssue(ctx, uid, m.MediaInfo.ID, issueType(kind), issueMessage(kind, note, details), int(season), int(episode))
	if err != nil {
		s.log.Warn("seerr issue", "item", itemType, "id", itemID, "err", err)
		return Problem{}, false
	}
	t, _ := s.titleOf(ctx, itemType, itemID)
	who := ""
	if u, err := s.q.GetUser(ctx, p.ID); err == nil {
		who = u.Name
	}
	return Problem{SeerrIssueID: int64(issue.ID), ReportedBy: who, ItemType: itemType, ItemID: itemID, Kind: kind, Note: strings.TrimSpace(note),
		Details: details, Title: t.title, ShowID: t.showID, Season: t.season, CreatedAt: time.Now().UTC()}, true
}

func issueType(kind string) int {
	switch kind {
	case "video":
		return seerr.IssueVideo
	case "audio":
		return seerr.IssueAudio
	case "subtitles":
		return seerr.IssueSubtitles
	}
	return seerr.IssueOther
}

func issueMessage(kind, note, details string) string {
	msg := strings.TrimSpace(note)
	if msg == "" {
		msg = map[string]string{"video": "Problem with the picture", "audio": "Problem with the sound", "subtitles": "Problem with the subtitles"}[kind]
		if msg == "" {
			msg = "Something is wrong with this file"
		}
	}
	if details != "" {
		msg += "\n\nFile: " + details
	}
	return msg + "\nReported in WatchMyMedia."
}

// --- deletes ---------------------------------------------------------------

// SeerrForget clears Seerr's record of a movie or show after it was deleted,
// so Seerr stops calling it available and it can be requested again (§8.1).
// seasons are the seasons deleted when only some were. Seerr can't take one
// season back from available, so then it clears the show's record and
// reruns Seerr's Sonarr sync, which rebuilds it from what Sonarr still has;
// Seerr's request history for the show is lost. Without Seerr it does
// nothing.
func (s *Service) SeerrForget(ctx context.Context, mediaType string, tmdbID int, seasons []int) error {
	c := s.seerrConn()
	if c == nil {
		return nil
	}
	s.dropSeerr(mediaType, tmdbID)
	m, err := c.seerr.Lookup(ctx, mediaType, tmdbID)
	if seerr.StatusOf(err) == http.StatusNotFound {
		return nil
	}
	if err != nil {
		return fmt.Errorf("seerr: %w", err)
	}
	if m.MediaInfo == nil || m.MediaInfo.ID == 0 {
		return nil
	}
	if err := c.seerr.DeleteMedia(ctx, m.MediaInfo.ID); err != nil && seerr.StatusOf(err) != http.StatusNotFound {
		return fmt.Errorf("seerr: %w", err)
	}
	s.log.Info("seerr record cleared", "type", mediaType, "tmdb", tmdbID, "seasons", seasons)
	if mediaType == "tv" && len(seasons) > 0 {
		if err := c.seerr.RunJob(ctx, seerr.JobSonarrScan); err != nil {
			return fmt.Errorf("seerr: %w", err)
		}
	}
	return nil
}

// --- the connection --------------------------------------------------------

// refreshSeerr checks Seerr and reads its users and public address.
func (s *Service) refreshSeerr(ctx context.Context, c *conn) {
	st, err := c.seerr.Status(ctx)
	var users []seerr.User
	var pub seerr.PublicSettings
	if err == nil {
		users, err = c.seerr.Users(ctx)
	}
	if err == nil {
		pub, _ = c.seerr.PublicSettings(ctx)
	}
	now := time.Now()
	s.mu.Lock()
	defer s.mu.Unlock()
	c.status.CheckedAt = &now
	c.status.OK = err == nil
	c.down = err
	if err != nil {
		c.status.Error = err.Error()
		s.log.Warn("integration unreachable", "integration", c.row.Name, "err", err)
		return
	}
	c.status.Error = ""
	c.status.AppName, c.status.Version = "Seerr", st.Version
	c.status.Items = len(users)
	c.seerrUsers, c.seerrURL = users, pub.ApplicationURL
}

// checkSeerr asks the Seerr at address for its version with key.
func (s *Service) checkSeerr(ctx context.Context, address, key string) (arr.SystemStatus, error) {
	base, err := arr.BaseURL(address)
	if err != nil {
		return arr.SystemStatus{}, invalid("%s", err.Error())
	}
	st, err := seerr.New(base, key, s.hc).Status(ctx)
	out := arr.SystemStatus{AppName: "Seerr", Version: st.Version}
	if err != nil {
		return out, err
	}
	return out, seerrSupported(st.Version)
}

// seerrSupported refuses Overseerr and Jellyseerr, which number media
// statuses differently. Development builds report a branch name, not a
// version, and are let through.
func seerrSupported(version string) error {
	major, _, _ := versionParts(version)
	if major > 0 && major < 3 {
		return invalid("Seerr %s is too old, or this is Overseerr or Jellyseerr: WatchMyMedia needs Seerr 3 or later", version)
	}
	return nil
}
