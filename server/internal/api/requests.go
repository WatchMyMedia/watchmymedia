package api

import (
	"cmp"
	"context"
	"errors"
	"net/http"
	"slices"
	"sync"
	"time"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/integrations"
	"watchmymedia/server/internal/integrations/seerr"
	"watchmymedia/server/internal/tmdb"
)

// Requests and problem reports (PLAN-ARR.md §7, §9). Radarr and Sonarr carry
// requests out; problems go to the admins' Inbox. With Seerr connected,
// requests and problems go to Seerr as each person's linked Seerr user.

// RequestOptions says what the signed-in user may do, so every app decides
// from one place which buttons to show.
type RequestOptions struct {
	Movies        bool `json:"movies" doc:"Movies can be requested."`
	Shows         bool `json:"shows" doc:"Shows and seasons can be requested."`
	NeedsApproval bool `json:"needsApproval" doc:"This user's requests wait for an admin (in Seerr, for its approval)."`
	Problems      bool `json:"problems" doc:"This user can report a problem with a file."`
	Manage        bool `json:"manage" doc:"This user can search, monitor and approve (an admin, with Radarr or Sonarr connected)."`
	// Backend is a plain string, not an enum: generated clients refuse enum
	// values they don't know, so a backend added later would break them.
	Backend string `json:"backend" doc:"Who carries requests out: none, arr (Radarr and Sonarr) or seerr. movies and shows say whether to offer requests; this only changes the wording, so word a value you don't know as arr. With seerr, a user not linked to a Seerr user can't request."`
	Inbox   int64  `json:"inbox" doc:"Admins: requests waiting for approval plus open problems."`
	// Delete is optional: servers before API level 6 don't send it.
	Delete bool `json:"delete" required:"false" doc:"This user can delete episodes and movies: an admin, or anyone while Who can delete is everyone, with Radarr or Sonarr connected. Offer it only for items with a file; the preview says whether an app manages it."`
	// Offline is optional: servers before API level 10 don't send it.
	Offline bool `json:"offline" required:"false" doc:"This user may download movies and episodes to watch offline (Who can download: an admin by default, or everyone). From API level 10; offer Download only then."`
}

type RequestOptionsOutput struct{ Body RequestOptions }

// Requestable is a movie or show someone may ask for, with where it stands.
type Requestable struct {
	MediaType  string `json:"mediaType" enum:"movie,tv"`
	TMDBID     int64  `json:"tmdbId"`
	Title      string `json:"title"`
	Year       int64  `json:"year,omitempty"`
	Overview   string `json:"overview"`
	PosterPath string `json:"posterPath,omitempty"`
	// State is requestable, requested (asked for, not yet downloading),
	// downloading, or available (something of it is in the library).
	State   string                     `json:"state" enum:"requestable,requested,downloading,available"`
	MovieID int64                      `json:"movieId,omitempty" doc:"The library's movie, when it has one."`
	ShowID  int64                      `json:"showId,omitempty" doc:"The library's show, when it has one."`
	Request *integrations.RequestState `json:"request,omitempty" doc:"The newest open request."`
	// Seasons are a show's seasons, from the lookup only.
	Seasons []RequestableSeason `json:"seasons,omitempty"`
}

type RequestableSeason struct {
	Number       int64  `json:"number"`
	Name         string `json:"name"`
	EpisodeCount int64  `json:"episodeCount"`
	AirDate      string `json:"airDate,omitempty"`
	// State is available (every aired episode is in the library), partial,
	// requested, unaired (nothing has aired) or missing.
	State string `json:"state" enum:"available,partial,requested,unaired,missing"`
}

type RequestableList struct {
	Items []Requestable `json:"items" nullable:"false"`
}

type SearchRequestableInput struct {
	Q string `query:"q" minLength:"1" maxLength:"100"`
}

type GetRequestableInput struct {
	MediaType string `query:"mediaType" enum:"movie,tv"`
	TMDBID    int64  `query:"tmdbId" minimum:"1"`
}

type CreateRequestInput struct {
	Body struct {
		MediaType string  `json:"mediaType" enum:"movie,tv"`
		TMDBID    int64   `json:"tmdbId" minimum:"1"`
		Seasons   []int64 `json:"seasons,omitempty" maxItems:"100" doc:"Season numbers; required for a show."`
	}
}

type CreatedRequest struct {
	Request integrations.Request `json:"request"`
	Created bool                 `json:"created" doc:"False when it was asked for already: by this user (the seasons were added) or by someone else."`
}

type CreateRequestOutput struct {
	Status int
	Body   CreatedRequest
}

type RequestList struct {
	Items []integrations.Request `json:"items" nullable:"false"`
}

type Inbox struct {
	Requests []integrations.Request   `json:"requests" nullable:"false" doc:"Waiting for approval, failed, then approved and still on their way."`
	Problems []integrations.Problem   `json:"problems" nullable:"false"`
	Seerr    *integrations.SeerrInbox `json:"seerr,omitempty" required:"false" doc:"Seerr carries requests out: approving and declining happen there. requests holds those made before Seerr was connected."`
}

type RequestIDInput struct {
	ID int64 `path:"id"`
}

type DecideRequestInput struct {
	ID   int64 `path:"id"`
	Body struct {
		Approve bool   `json:"approve"`
		Note    string `json:"note,omitempty" maxLength:"500" doc:"Shown to the person who asked when declined."`
	}
}

type RequestOutput struct{ Body integrations.Request }

type RequestSettings struct {
	Approval string `json:"approval" enum:"admins,everyone" doc:"admins: everyone else's requests wait for an admin; everyone: all go straight through."`
}

type RequestSettingsOutput struct{ Body RequestSettings }

type UpdateRequestSettingsInput struct{ Body RequestSettings }

type ReportProblemInput struct {
	Body struct {
		ItemType string `json:"itemType" enum:"movie,episode"`
		ItemID   int64  `json:"itemId" minimum:"1"`
		FileID   int64  `json:"fileId,omitempty" doc:"The file that was playing, if any."`
		Kind     string `json:"kind" enum:"video,audio,subtitles,other"`
		Note     string `json:"note,omitempty" maxLength:"1000"`
	}
}

type ProblemOutput struct {
	Status int
	Body   integrations.Problem
}

type ShowEpisodesInput struct {
	ID   int64 `path:"id" doc:"Show ID."`
	Body struct {
		Season   int64   `json:"season" minimum:"0"`
		Episodes []int64 `json:"episodes,omitempty" maxItems:"500" doc:"Episode numbers; empty for the whole season."`
	}
}

type MonitorEpisodesInput struct {
	ID   int64 `path:"id" doc:"Show ID."`
	Body struct {
		Season    int64   `json:"season" minimum:"0"`
		Episodes  []int64 `json:"episodes,omitempty" maxItems:"500" doc:"Episode numbers; empty for the whole season."`
		Monitored bool    `json:"monitored"`
	}
}

func person(ctx context.Context, u auth.User) integrations.Person {
	return integrations.Person{ID: u.ID, Name: u.Name, Admin: effectiveAdmin(ctx, u)}
}

// requester is who asks, and the integrations service, for the request
// routes; admin limits the route to admins on an admin network.
func requester(ctx context.Context, deps Deps, admin bool) (integrations.Person, *integrations.Service, error) {
	check := currentUser
	if admin {
		check = requireAdmin
	}
	u, err := check(ctx)
	if err != nil {
		return integrations.Person{}, nil, err
	}
	if deps.Integrations == nil {
		return integrations.Person{}, nil, huma.Error503ServiceUnavailable("integrations are off")
	}
	return person(ctx, u), deps.Integrations, nil
}

func ints(v []int64) []int {
	out := make([]int, len(v))
	for i, n := range v {
		out[i] = int(n)
	}
	return out
}

func registerRequests(h huma.API, deps Deps) {
	huma.Register(h, huma.Operation{
		OperationID: "getRequestOptions",
		Method:      http.MethodGet,
		Path:        Prefix + "/requests/options",
		Summary:     "What the signed-in user may request, report and manage",
		Tags:        []string{"requests"},
	}, func(ctx context.Context, _ *struct{}) (*RequestOptionsOutput, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		p := person(ctx, u)
		out := RequestOptions{Problems: true, Backend: "none", Offline: mayDownload(ctx, deps, u)}
		if s := deps.Integrations; s != nil {
			if s.Seerr() {
				out.Backend = "seerr"
				a := s.SeerrAccess(ctx, p)
				out.Movies, out.Shows, out.NeedsApproval = a.Movies, a.Shows, a.NeedsApproval
			} else if out.Movies, out.Shows = s.Requests(); out.Movies || out.Shows {
				out.Backend = "arr"
				out.NeedsApproval = !p.Admin && s.Approval(ctx) == "admins"
			}
			out.Manage = p.Admin && s.HasArr()
			out.Delete = s.HasArr() && (p.Admin || s.DeleteAccess(ctx) == "everyone")
			if p.Admin {
				out.Inbox = s.PendingCount(ctx) + s.OpenProblems(ctx)
			}
		}
		if deps.Recordings != nil && deps.Recordings.HasFiles(ctx) {
			access := "admins"
			if deps.Settings != nil {
				access, _ = deps.Settings.Get(ctx, integrations.KeyDeleteAccess, "admins")
			}
			out.Delete = out.Delete || p.Admin || access == "everyone"
		}
		return &RequestOptionsOutput{Body: out}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "searchRequestable",
		Method:      http.MethodGet,
		Path:        Prefix + "/requests/search",
		Summary:     "Search TMDB, or Seerr when it is connected, for movies and shows to request",
		Description: "Each result says whether it is in the library, asked for or downloading. Call it beside the library search, which never waits on it.",
		Tags:        []string{"requests"},
	}, func(ctx context.Context, in *SearchRequestableInput) (*struct{ Body RequestableList }, error) {
		p, s, err := requester(ctx, deps, false)
		if err != nil {
			return nil, err
		}
		if s.Seerr() {
			return seerrSearch(ctx, deps, s, p, in.Q)
		}
		if deps.TMDB == nil || !deps.TMDB.Configured() {
			return nil, huma.Error503ServiceUnavailable("this server has no TMDB key")
		}
		var (
			wg     sync.WaitGroup
			movies []tmdb.MovieResult
			shows  []tmdb.TVResult
			merr   error
			serr   error
		)
		wg.Go(func() { movies, merr = deps.TMDB.SearchMovies(ctx, in.Q, 0) })
		wg.Go(func() { shows, serr = deps.TMDB.SearchTV(ctx, in.Q, 0) })
		wg.Wait()
		if merr != nil && serr != nil {
			return nil, huma.Error502BadGateway("could not reach TMDB: " + merr.Error())
		}
		type hit struct {
			r   Requestable
			pop float64
		}
		var hits []hit
		for _, m := range movies {
			hits = append(hits, hit{Requestable{MediaType: "movie", TMDBID: int64(m.ID), Title: m.Title, Year: int64(tmdb.Year(m.ReleaseDate)),
				Overview: m.Overview, PosterPath: m.PosterPath}, m.Popularity})
		}
		for _, t := range shows {
			hits = append(hits, hit{Requestable{MediaType: "tv", TMDBID: int64(t.ID), Title: t.Name, Year: int64(tmdb.Year(t.FirstAirDate)),
				Overview: t.Overview, PosterPath: t.PosterPath}, t.Popularity})
		}
		slices.SortStableFunc(hits, func(a, b hit) int { return cmp.Compare(b.pop, a.pop) })
		out := RequestableList{Items: []Requestable{}}
		for _, h := range hits[:min(len(hits), 20)] {
			out.Items = append(out.Items, requestableState(ctx, deps, s, p, h.r))
		}
		return &struct{ Body RequestableList }{Body: out}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "getRequestable",
		Method:      http.MethodGet,
		Path:        Prefix + "/requests/lookup",
		Summary:     "Get a movie or show to request, with its seasons",
		Tags:        []string{"requests"},
	}, func(ctx context.Context, in *GetRequestableInput) (*struct{ Body Requestable }, error) {
		p, s, err := requester(ctx, deps, false)
		if err != nil {
			return nil, err
		}
		if s.Seerr() {
			return seerrLookup(ctx, deps, s, p, in.MediaType, in.TMDBID)
		}
		if deps.TMDB == nil || !deps.TMDB.Configured() {
			return nil, huma.Error503ServiceUnavailable("this server has no TMDB key")
		}
		var r Requestable
		var tv *tmdb.TVShow
		if in.MediaType == "movie" {
			m, err := deps.TMDB.GetMovie(ctx, int(in.TMDBID))
			if err != nil {
				return nil, tmdbError(err)
			}
			r = Requestable{MediaType: "movie", TMDBID: in.TMDBID, Title: m.Title, Year: int64(tmdb.Year(m.ReleaseDate)), Overview: m.Overview, PosterPath: m.PosterPath}
		} else {
			if tv, err = deps.TMDB.GetTV(ctx, int(in.TMDBID)); err != nil {
				return nil, tmdbError(err)
			}
			r = Requestable{MediaType: "tv", TMDBID: in.TMDBID, Title: tv.Name, Year: int64(tmdb.Year(tv.FirstAirDate)), Overview: tv.Overview, PosterPath: tv.PosterPath}
		}
		r = requestableState(ctx, deps, s, p, r)
		if tv != nil {
			r.Seasons = seasonStates(ctx, deps, s, p, tv)
		}
		return &struct{ Body Requestable }{Body: r}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID:   "createRequest",
		Method:        http.MethodPost,
		Path:          Prefix + "/requests",
		Summary:       "Ask for a movie or some seasons of a show",
		Description:   "An admin's request, or anyone's when approval is set to everyone, goes to Radarr or Sonarr at once; others wait in the admins' Inbox. Asking for what is already asked for returns that request. With Seerr connected the request goes to Seerr as the user's linked Seerr user; Seerr's refusals (no link, no permission, quota, asked for already, nothing left to ask for) come back as 403 or 409 with a message to show.",
		Tags:          []string{"requests"},
		DefaultStatus: http.StatusCreated,
	}, func(ctx context.Context, in *CreateRequestInput) (*CreateRequestOutput, error) {
		p, s, err := requester(ctx, deps, false)
		if err != nil {
			return nil, err
		}
		r, created, err := s.Ask(ctx, p, in.Body.MediaType, int(in.Body.TMDBID), ints(in.Body.Seasons))
		if err != nil {
			return nil, integrationError(err)
		}
		status := http.StatusCreated
		if !created {
			status = http.StatusOK
		}
		return &CreateRequestOutput{Status: status, Body: CreatedRequest{Request: r, Created: created}}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "listMyRequests",
		Method:      http.MethodGet,
		Path:        Prefix + "/requests/mine",
		Summary:     "List the signed-in user's requests",
		Description: "With Seerr connected, the user's requests in Seerr.",
		Tags:        []string{"requests"},
	}, func(ctx context.Context, _ *struct{}) (*struct{ Body RequestList }, error) {
		p, s, err := requester(ctx, deps, false)
		if err != nil {
			return nil, err
		}
		items, err := s.Mine(ctx, p)
		if err != nil {
			return nil, err
		}
		return &struct{ Body RequestList }{Body: RequestList{Items: items}}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID:   "cancelRequest",
		Method:        http.MethodDelete,
		Path:          Prefix + "/requests/{id}",
		Summary:       "Cancel a request that is waiting",
		Description:   "With Seerr connected, withdraws the request in Seerr.",
		Tags:          []string{"requests"},
		DefaultStatus: http.StatusNoContent,
	}, func(ctx context.Context, in *RequestIDInput) (*struct{}, error) {
		p, s, err := requester(ctx, deps, false)
		if err != nil {
			return nil, err
		}
		if err := s.Cancel(ctx, p, in.ID); err != nil {
			if integrations.IsRequestNotFound(err) {
				return nil, huma.Error404NotFound("request not found")
			}
			return nil, integrationError(err)
		}
		return nil, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "listInbox",
		Method:      http.MethodGet,
		Path:        Prefix + "/requests/inbox",
		Summary:     "Requests and problems for admins",
		Tags:        []string{"requests"},
	}, func(ctx context.Context, _ *struct{}) (*struct{ Body Inbox }, error) {
		p, s, err := requester(ctx, deps, true)
		if err != nil {
			return nil, err
		}
		reqs, err := s.Open(ctx, p)
		if err != nil {
			return nil, err
		}
		probs, err := s.Problems(ctx)
		if err != nil {
			return nil, err
		}
		return &struct{ Body Inbox }{Body: Inbox{Requests: reqs, Problems: probs, Seerr: s.SeerrInbox(ctx)}}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "decideRequest",
		Method:      http.MethodPost,
		Path:        Prefix + "/requests/{id}/decision",
		Summary:     "Approve or decline a request",
		Description: "Approving a pending or failed request sends it to Radarr or Sonarr; declining keeps the note for the person who asked.",
		Tags:        []string{"requests"},
	}, func(ctx context.Context, in *DecideRequestInput) (*RequestOutput, error) {
		p, s, err := requester(ctx, deps, true)
		if err != nil {
			return nil, err
		}
		r, err := s.Decide(ctx, p, in.ID, in.Body.Approve, in.Body.Note)
		if err != nil {
			if integrations.IsRequestNotFound(err) {
				return nil, huma.Error404NotFound("request not found")
			}
			return nil, integrationError(err)
		}
		return &RequestOutput{Body: r}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "getRequestSettings",
		Method:      http.MethodGet,
		Path:        Prefix + "/requests/settings",
		Summary:     "Who may request without approval",
		Tags:        []string{"requests"},
	}, func(ctx context.Context, _ *struct{}) (*RequestSettingsOutput, error) {
		_, s, err := requester(ctx, deps, true)
		if err != nil {
			return nil, err
		}
		return &RequestSettingsOutput{Body: RequestSettings{Approval: s.Approval(ctx)}}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "updateRequestSettings",
		Method:      http.MethodPatch,
		Path:        Prefix + "/requests/settings",
		Summary:     "Change who may request without approval",
		Tags:        []string{"requests"},
	}, func(ctx context.Context, in *UpdateRequestSettingsInput) (*RequestSettingsOutput, error) {
		_, s, err := requester(ctx, deps, true)
		if err != nil {
			return nil, err
		}
		if err := s.SetApproval(ctx, in.Body.Approval); err != nil {
			return nil, integrationError(err)
		}
		return &RequestSettingsOutput{Body: RequestSettings{Approval: s.Approval(ctx)}}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID:   "reportProblem",
		Method:        http.MethodPost,
		Path:          Prefix + "/problems",
		Summary:       "Report a problem with a movie or episode",
		Description:   "The server adds the file's name, codecs and resolution; the report waits in the admins' Inbox. With Seerr connected, a linked user's report becomes a Seerr issue (seerrIssueId) when Seerr has a record of the item; otherwise it goes to the Inbox.",
		Tags:          []string{"requests"},
		DefaultStatus: http.StatusCreated,
	}, func(ctx context.Context, in *ReportProblemInput) (*ProblemOutput, error) {
		p, s, err := requester(ctx, deps, false)
		if err != nil {
			return nil, err
		}
		b := in.Body
		problem, err := s.Report(ctx, p, b.ItemType, b.ItemID, b.FileID, b.Kind, b.Note)
		if err != nil {
			return nil, integrationError(err)
		}
		return &ProblemOutput{Status: http.StatusCreated, Body: problem}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID:   "resolveProblem",
		Method:        http.MethodPost,
		Path:          Prefix + "/problems/{id}/resolve",
		Summary:       "Mark a problem resolved",
		Tags:          []string{"requests"},
		DefaultStatus: http.StatusNoContent,
	}, func(ctx context.Context, in *RequestIDInput) (*struct{}, error) {
		p, s, err := requester(ctx, deps, true)
		if err != nil {
			return nil, err
		}
		if err := s.Resolve(ctx, p, in.ID); err != nil {
			if integrations.IsRequestNotFound(err) {
				return nil, huma.Error404NotFound("problem not found")
			}
			return nil, err
		}
		return nil, nil
	})

	huma.Register(h, huma.Operation{
		OperationID:   "searchMissingEpisodes",
		Method:        http.MethodPost,
		Path:          Prefix + "/shows/{id}/search",
		Summary:       "Have Sonarr look for a season's or some episodes' files now",
		Description:   "Monitors the episodes first, since Sonarr only grabs monitored ones.",
		Tags:          []string{"requests"},
		DefaultStatus: http.StatusAccepted,
	}, func(ctx context.Context, in *ShowEpisodesInput) (*struct{}, error) {
		it, s, err := sonarrItem(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		if err := s.SearchMissing(ctx, it, int(in.Body.Season), ints(in.Body.Episodes)); err != nil {
			return nil, integrationError(err)
		}
		return nil, nil
	})

	huma.Register(h, huma.Operation{
		OperationID:   "monitorEpisodes",
		Method:        http.MethodPost,
		Path:          Prefix + "/shows/{id}/monitor",
		Summary:       "Monitor or unmonitor a season or some episodes in Sonarr",
		Tags:          []string{"requests"},
		DefaultStatus: http.StatusNoContent,
	}, func(ctx context.Context, in *MonitorEpisodesInput) (*struct{}, error) {
		it, s, err := sonarrItem(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		if err := s.SetMonitored(ctx, it, int(in.Body.Season), ints(in.Body.Episodes), in.Body.Monitored); err != nil {
			return nil, integrationError(err)
		}
		return nil, nil
	})
}

// sonarrItem is a library show as the integrations find it, for admins.
func sonarrItem(ctx context.Context, deps Deps, showID int64) (integrations.Item, *integrations.Service, error) {
	s, err := integrationsAdmin(ctx, deps)
	if err != nil {
		return integrations.Item{}, nil, err
	}
	it, ok := libraryShowItem(ctx, deps, showID)
	if !ok {
		return integrations.Item{}, nil, huma.Error404NotFound("show not found")
	}
	return it, s, nil
}

func libraryShowItem(ctx context.Context, deps Deps, showID int64) (integrations.Item, bool) {
	show, err := deps.Queries.GetShow(ctx, showID)
	if err != nil {
		return integrations.Item{}, false
	}
	lib, err := deps.Queries.GetLibrary(ctx, show.LibraryID)
	if err != nil {
		return integrations.Item{}, false
	}
	files, _ := deps.Queries.ListShowFilePaths(ctx, show.ID)
	return integrations.Item{LibraryPath: lib.Path, TMDBID: int(show.TmdbID), Files: files}, true
}

// libraryState marks r available, with its library ID, when the library
// has the movie or show with a present file.
func libraryState(ctx context.Context, deps Deps, r *Requestable) {
	if r.MediaType == "movie" {
		if m, err := deps.Queries.GetLibraryMovieByTMDB(ctx, r.TMDBID); err == nil {
			r.MovieID, r.State = m.ID, "available"
		}
	} else if sh, err := deps.Queries.GetLibraryShowByTMDB(ctx, r.TMDBID); err == nil {
		r.ShowID, r.State = sh.ID, "available"
	}
}

// seasonState says where a season stands: the library has it all, it is
// asked for (taken), the library has some, it has not aired, or it is
// missing.
func seasonState(have db.ListSeasonAvailabilityByTMDBRow, taken bool, airDate, today string) string {
	switch {
	case have.AvailableCount > 0 && have.AvailableCount >= have.AiredCount:
		return "available"
	case taken:
		return "requested"
	case have.AvailableCount > 0:
		return "partial"
	case airDate == "" || airDate > today:
		return "unaired"
	}
	return "missing"
}

// requestableState fills in where a search result or lookup stands: in the
// library, asked for, or downloading.
func requestableState(ctx context.Context, deps Deps, s *integrations.Service, p integrations.Person, r Requestable) Requestable {
	r.State = "requestable"
	libraryState(ctx, deps, &r)
	for _, st := range s.ForMedia(ctx, p, r.MediaType, r.TMDBID) {
		if st.Status == "pending" || st.Status == "approved" || st.Status == "failed" {
			r.Request = &st
			if r.State == "requestable" {
				r.State = "requested"
				if st.Status == "approved" {
					r.State = "downloading"
				}
			}
			break
		}
	}
	return r
}

// seasonStates says, for each of a show's seasons, whether the library has
// it, someone asked for it, or it is missing.
func seasonStates(ctx context.Context, deps Deps, s *integrations.Service, p integrations.Person, tv *tmdb.TVShow) []RequestableSeason {
	today := time.Now().UTC().Format("2006-01-02")
	have := seasonAvailability(ctx, deps, int64(tv.ID), today)
	asked := map[int64]bool{}
	for _, st := range s.ForMedia(ctx, p, "tv", int64(tv.ID)) {
		if st.Status == "pending" || st.Status == "approved" {
			for _, n := range st.Seasons {
				asked[int64(n)] = true
			}
		}
	}
	out := []RequestableSeason{}
	for _, se := range tv.Seasons {
		n := int64(se.SeasonNumber)
		out = append(out, RequestableSeason{Number: n, Name: se.Name, EpisodeCount: int64(se.EpisodeCount), AirDate: se.AirDate,
			State: seasonState(have[n], asked[n], se.AirDate, today)})
	}
	return out
}

// seerrSearch is searchRequestable with Seerr as the backend: Seerr's
// results, in its order, with the library's state and Seerr's.
func seerrSearch(ctx context.Context, deps Deps, s *integrations.Service, p integrations.Person, q string) (*struct{ Body RequestableList }, error) {
	found, err := s.SeerrSearch(ctx, p, q)
	if err != nil {
		return nil, integrationError(err)
	}
	out := RequestableList{Items: []Requestable{}}
	for _, f := range found[:min(len(found), 20)] {
		out.Items = append(out.Items, seerrRequestable(ctx, deps, f))
	}
	return &struct{ Body RequestableList }{Body: out}, nil
}

// seerrLookup is getRequestable with Seerr as the backend.
func seerrLookup(ctx context.Context, deps Deps, s *integrations.Service, p integrations.Person, mediaType string, tmdbID int64) (*struct{ Body Requestable }, error) {
	f, err := s.SeerrLookup(ctx, p, mediaType, int(tmdbID))
	if integrations.IsNotFound(err) {
		return nil, huma.Error404NotFound("Seerr has no such item")
	}
	if err != nil {
		return nil, integrationError(err)
	}
	r := seerrRequestable(ctx, deps, f)
	if mediaType == "tv" {
		r.Seasons = seerrSeasonStates(ctx, deps, f)
	}
	return &struct{ Body Requestable }{Body: r}, nil
}

// seerrRequestable says where an item from Seerr stands. In the library
// comes from the library, as without Seerr; otherwise Seerr's record says
// whether it is asked for (pending) or on its way (approved, or the *arr
// app has it and the library will once it is imported).
func seerrRequestable(ctx context.Context, deps Deps, f integrations.Found) Requestable {
	r := Requestable{MediaType: f.MediaType, TMDBID: f.TMDBID, Title: f.Title, Year: f.Year, Overview: f.Overview, PosterPath: f.PosterPath, State: "requestable"}
	libraryState(ctx, deps, &r)
	if r.State == "requestable" {
		switch f.Status {
		case seerr.StatusPending:
			r.State = "requested"
		case seerr.StatusProcessing, seerr.StatusPartiallyAvailable, seerr.StatusAvailable:
			r.State = "downloading"
		}
	}
	for _, st := range f.Requests {
		if st.Status == "pending" || st.Status == "approved" || st.Status == "failed" {
			r.Request = &st
			if r.State == "requestable" {
				r.State = map[string]string{"pending": "requested", "approved": "downloading", "failed": "requestable"}[st.Status]
			}
			break
		}
	}
	return r
}

// seerrSeasonStates says, for each season of a show from Seerr, what the
// person can do: nothing when the library has it all or Seerr won't take a
// request for it (requested), else ask for it (partial, missing), or wait
// (unaired).
func seerrSeasonStates(ctx context.Context, deps Deps, f integrations.Found) []RequestableSeason {
	today := time.Now().UTC().Format("2006-01-02")
	have := seasonAvailability(ctx, deps, f.TMDBID, today)
	out := []RequestableSeason{}
	for _, se := range f.Seasons {
		out = append(out, RequestableSeason{Number: se.Number, Name: se.Name, EpisodeCount: se.EpisodeCount, AirDate: se.AirDate,
			State: seasonState(have[se.Number], se.Taken, se.AirDate, today)})
	}
	return out
}

// seasonAvailability is how many of each season's episodes the library has
// and how many have aired, from the show's best copy.
func seasonAvailability(ctx context.Context, deps Deps, tmdbID int64, today string) map[int64]db.ListSeasonAvailabilityByTMDBRow {
	rows, _ := deps.Queries.ListSeasonAvailabilityByTMDB(ctx, db.ListSeasonAvailabilityByTMDBParams{Today: &today, TmdbID: tmdbID})
	have := map[int64]db.ListSeasonAvailabilityByTMDBRow{}
	for _, r := range rows {
		if b, ok := have[r.Number]; !ok || r.AvailableCount > b.AvailableCount {
			have[r.Number] = r
		}
	}
	return have
}

func tmdbError(err error) error {
	if errors.Is(err, tmdb.ErrNotFound) {
		return huma.Error404NotFound("TMDB has no such item")
	}
	return huma.Error502BadGateway("could not reach TMDB: " + err.Error())
}

// seasonRequests maps a show's season numbers to their newest request that
// is not cancelled or done, as the signed-in user sees it.
func seasonRequests(ctx context.Context, s *integrations.Service, tmdbID int64) map[int]*integrations.RequestState {
	out := map[int]*integrations.RequestState{}
	u, ok := auth.UserFrom(ctx)
	if !ok {
		return out
	}
	for _, st := range s.ForMedia(ctx, person(ctx, u), "tv", tmdbID) {
		if st.Status == "available" {
			continue
		}
		for _, n := range st.Seasons {
			if out[n] == nil {
				out[n] = &st
			}
		}
	}
	return out
}
