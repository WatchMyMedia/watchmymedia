package api

import (
	"bytes"
	"context"
	"errors"
	"net/http"
	"regexp"
	"slices"
	"strings"
	"sync"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/artwork"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/tmdb"
)

// Posters an admin chooses in place of the match's (PLAN-POSTERS.md): one
// of TMDB's others, an upload, or a link the server copies once. The choice
// goes out as the item's posterPath, so no client changes to show it.

// Poster sources kept in poster_source; "" is the match's own poster.
const (
	posterSourceTMDB   = "tmdb"
	posterSourceUpload = "upload"
	posterSourceURL    = "url"
)

// maxTMDBPosters bounds each list sent to the dialog; popular titles have
// hundreds of posters.
const maxTMDBPosters = 60

// tmdbPosterRe is a TMDB poster path as the images route accepts it.
var tmdbPosterRe = regexp.MustCompile(`^/[A-Za-z0-9_-]{1,64}\.(jpg|jpeg|png|webp)$`)

type PosterChoice struct {
	Path     string `json:"path" doc:"Image path; fetch via /api/images/{size}{path}."`
	Width    int    `json:"width"`
	Height   int    `json:"height"`
	Language string `json:"language,omitempty" doc:"The language of the words on the poster; absent when it has none."`
}

type PosterOptions struct {
	PosterPath    string         `json:"posterPath,omitempty" doc:"The poster shown now."`
	PosterSource  string         `json:"posterSource,omitempty" enum:"tmdb,upload,url" doc:"Set when an admin chose the poster; absent for the match's own."`
	TMDBPath      string         `json:"tmdbPath,omitempty" doc:"The match's own poster, which Reset goes back to."`
	Posters       []PosterChoice `json:"posters" nullable:"false" doc:"TMDB's posters for the item, English and wordless ones first."`
	ShowPosters   []PosterChoice `json:"showPosters" nullable:"false" doc:"For a season: its show's posters on TMDB. Empty otherwise."`
	TMDBAvailable bool           `json:"tmdbAvailable" doc:"False without a TMDB key or when TMDB could not be reached; the lists are then empty, and uploads and links still work."`
}

type PosterResult struct {
	PosterPath   string `json:"posterPath,omitempty"`
	PosterSource string `json:"posterSource,omitempty" enum:"tmdb,upload,url"`
}

type PosterInput struct {
	ID int64 `path:"id"`
}

type PosterOptionsOutput struct{ Body PosterOptions }

type PosterOutput struct{ Body PosterResult }

type SetPosterInput struct {
	ID   int64 `path:"id"`
	Body struct {
		TMDBPath string `json:"tmdbPath,omitempty" required:"false" doc:"One of the paths the posters route listed."`
		URL      string `json:"url,omitempty" required:"false" doc:"A link to a JPEG, PNG or WebP picture on the internet. The server copies it once."`
	}
}

type UploadPosterInput struct {
	ID      int64  `path:"id"`
	RawBody []byte `contentType:"application/octet-stream" doc:"The picture's bytes: JPEG, PNG or WebP, 20 MB at most."`
}

// posterTarget is the movie, show or season a poster route acts on.
type posterTarget struct {
	kind      string
	id        int64
	libraryID int64
	current   string
	tmdbPath  string
	source    string
	// lists fetches TMDB's posters: the item's, and for a season its show's.
	lists func(ctx context.Context, c *tmdb.Client) (own, show []tmdb.Poster, err error)
	// set stores a choice; an empty source puts TMDB's poster back.
	set func(ctx context.Context, path, source string) (PosterResult, error)
	// historyUpdate sets history's copy of the item's poster (a poster
	// path, then the item's id), when history keeps one.
	historyUpdate string
}

// ownPoster is the match's own poster: the one shown while no choice is
// set, and the one kept beside the choice otherwise.
func ownPoster(shown, kept *string, source string) string {
	if source == "" {
		return deref(shown)
	}
	return deref(kept)
}

type posterService struct {
	deps Deps
	// mu keeps a sweep from deleting a picture saved but not yet set on its
	// item.
	mu sync.Mutex
}

func (s *posterService) target(ctx context.Context, kind string, id int64) (posterTarget, error) {
	q := s.deps.Queries
	result := func(path *string, source string) PosterResult {
		return PosterResult{PosterPath: deref(path), PosterSource: source}
	}
	arg := func(path string) *string {
		if path == "" {
			return nil
		}
		return &path
	}
	switch kind {
	case "movie":
		m, err := q.GetMovie(ctx, id)
		if err != nil {
			return posterTarget{}, notFound(err, "movie not found")
		}
		return posterTarget{
			kind: kind, id: id, libraryID: m.LibraryID, current: deref(m.PosterPath), tmdbPath: ownPoster(m.PosterPath, m.PosterTmdb, m.PosterSource), source: m.PosterSource,
			historyUpdate: `UPDATE history_items SET poster_path = ? WHERE movie_id = ?`,
			lists: func(ctx context.Context, c *tmdb.Client) ([]tmdb.Poster, []tmdb.Poster, error) {
				own, err := c.GetMoviePosters(ctx, int(m.TmdbID))
				return own, nil, err
			},
			set: func(ctx context.Context, path, source string) (PosterResult, error) {
				row, err := q.SetMoviePoster(ctx, db.SetMoviePosterParams{ID: id, Path: arg(path), Source: source})
				return result(row.PosterPath, row.PosterSource), err
			},
		}, nil
	case "show":
		sh, err := q.GetShow(ctx, id)
		if err != nil {
			return posterTarget{}, notFound(err, "show not found")
		}
		return posterTarget{
			kind: kind, id: id, libraryID: sh.LibraryID, current: deref(sh.PosterPath), tmdbPath: ownPoster(sh.PosterPath, sh.PosterTmdb, sh.PosterSource), source: sh.PosterSource,
			historyUpdate: `UPDATE history_items SET poster_path = ? WHERE show_id = ?`,
			lists: func(ctx context.Context, c *tmdb.Client) ([]tmdb.Poster, []tmdb.Poster, error) {
				own, err := c.GetTVPosters(ctx, int(sh.TmdbID))
				return own, nil, err
			},
			set: func(ctx context.Context, path, source string) (PosterResult, error) {
				row, err := q.SetShowPoster(ctx, db.SetShowPosterParams{ID: id, Path: arg(path), Source: source})
				return result(row.PosterPath, row.PosterSource), err
			},
		}, nil
	default:
		se, err := q.GetSeason(ctx, id)
		if err != nil {
			return posterTarget{}, notFound(err, "season not found")
		}
		sh, err := q.GetShow(ctx, se.ShowID)
		if err != nil {
			return posterTarget{}, notFound(err, "show not found")
		}
		return posterTarget{
			kind: kind, id: id, libraryID: sh.LibraryID, current: deref(se.PosterPath), tmdbPath: ownPoster(se.PosterPath, se.PosterTmdb, se.PosterSource), source: se.PosterSource,
			lists: func(ctx context.Context, c *tmdb.Client) ([]tmdb.Poster, []tmdb.Poster, error) {
				// A season TMDB has no pictures for is a 404 there; its
				// show's posters are still worth offering.
				own, err := c.GetSeasonPosters(ctx, int(sh.TmdbID), int(se.Number))
				show, showErr := c.GetTVPosters(ctx, int(sh.TmdbID))
				if showErr != nil {
					return own, nil, errors.Join(err, showErr)
				}
				return own, show, nil
			},
			set: func(ctx context.Context, path, source string) (PosterResult, error) {
				row, err := q.SetSeasonPoster(ctx, db.SetSeasonPosterParams{ID: id, Path: arg(path), Source: source})
				return result(row.PosterPath, row.PosterSource), err
			},
		}, nil
	}
}

// posterChoices orders TMDB's posters for the dialog: English and wordless
// ones first, each group as TMDB ranked it, and drops paths the images
// route would refuse.
func posterChoices(in []tmdb.Poster) []PosterChoice {
	out := make([]PosterChoice, 0, min(len(in), maxTMDBPosters))
	for _, first := range []bool{true, false} {
		for _, p := range in {
			if (p.Language == "en" || p.Language == "") != first || !tmdbPosterRe.MatchString(p.FilePath) {
				continue
			}
			if len(out) == maxTMDBPosters {
				return out
			}
			out = append(out, PosterChoice{Path: p.FilePath, Width: p.Width, Height: p.Height, Language: p.Language})
		}
	}
	return out
}

func (s *posterService) options(ctx context.Context, kind string, id int64) (*PosterOptionsOutput, error) {
	if _, err := requireAdmin(ctx); err != nil {
		return nil, err
	}
	t, err := s.target(ctx, kind, id)
	if err != nil {
		return nil, err
	}
	out := PosterOptions{PosterPath: t.current, PosterSource: t.source, TMDBPath: t.tmdbPath, Posters: []PosterChoice{}, ShowPosters: []PosterChoice{}}
	if s.deps.TMDB.Configured() {
		own, show, err := t.lists(ctx, s.deps.TMDB)
		if err != nil {
			s.deps.Log.WarnContext(ctx, "could not list TMDB's posters", "kind", kind, "id", id, "err", err)
		}
		out.TMDBAvailable = err == nil || len(own) > 0 || len(show) > 0
		out.Posters, out.ShowPosters = posterChoices(own), posterChoices(show)
	}
	return &PosterOptionsOutput{Body: out}, nil
}

// apply stores a choice, tells clients, and clears pictures nothing shows
// any more. save, when set, stores the picture first and returns its name.
func (s *posterService) apply(ctx context.Context, kind string, id int64, source string, path func(posterTarget) (string, error)) (*PosterOutput, error) {
	u, err := requireAdmin(ctx)
	if err != nil {
		return nil, err
	}
	t, err := s.target(ctx, kind, id)
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	chosen := ""
	if path != nil {
		if chosen, err = path(t); err != nil {
			return nil, err
		}
	}
	res, err := t.set(ctx, chosen, source)
	if err != nil {
		return nil, err
	}
	if t.historyUpdate != "" && s.deps.Conn != nil {
		// History keeps its own copy of the poster, written when something
		// is played.
		if _, err := s.deps.Conn.ExecContext(ctx, t.historyUpdate, res.PosterPath, id); err != nil {
			s.deps.Log.WarnContext(ctx, "could not update history's copy of a poster", "kind", kind, "id", id, "err", err)
		}
	}
	if err := SweepArtwork(ctx, s.deps.Queries, s.deps.Artwork, s.deps.ImagesDir); err != nil {
		s.deps.Log.WarnContext(ctx, "could not clear unused posters", "err", err)
	}
	s.deps.Log.InfoContext(ctx, "poster changed", "user", u.Name, "kind", kind, "id", id, "source", source)
	if lib, err := s.deps.Queries.GetLibrary(ctx, t.libraryID); err == nil {
		publishChanged(s.deps, lib)
	}
	return &PosterOutput{Body: res}, nil
}

// SweepArtwork deletes the stored pictures no movie, show or season shows,
// and their cached sizes under imagesDir.
func SweepArtwork(ctx context.Context, q *db.Queries, store *artwork.Store, imagesDir string) error {
	if store == nil {
		return nil
	}
	rows, err := q.ListCustomPosters(ctx)
	if err != nil {
		return err
	}
	keep := make([]string, 0, len(rows))
	for _, r := range rows {
		keep = append(keep, deref(r))
	}
	_, err = store.Sweep(keep, imagesDir)
	return err
}

// pictureError words a refused picture for the dialog.
func pictureError(err error) error {
	var fetch *artwork.FetchError
	switch {
	case errors.Is(err, artwork.ErrNotPicture):
		return huma.Error422UnprocessableEntity("That file isn't a picture we can use (JPEG, PNG or WebP).")
	case errors.Is(err, artwork.ErrTooLarge):
		return huma.Error422UnprocessableEntity("That picture is too large (20 MB at most).")
	case errors.As(err, &fetch) && fetch.Blocked:
		return huma.Error422UnprocessableEntity("That link points to this network, which isn't allowed. Upload the picture instead.")
	case errors.As(err, &fetch):
		return huma.Error502BadGateway("We couldn't get a picture from that link.", err)
	}
	return err
}

func (s *posterService) store() (*artwork.Store, error) {
	if s.deps.Artwork == nil {
		return nil, huma.Error503ServiceUnavailable("this server keeps no pictures of its own")
	}
	return s.deps.Artwork, nil
}

func (s *posterService) set(ctx context.Context, kind string, in *SetPosterInput) (*PosterOutput, error) {
	tmdbPath, link := strings.TrimSpace(in.Body.TMDBPath), strings.TrimSpace(in.Body.URL)
	switch {
	case (tmdbPath == "") == (link == ""):
		return nil, huma.Error422UnprocessableEntity("give tmdbPath or url, one of them")
	case link != "":
		return s.apply(ctx, kind, in.ID, posterSourceURL, func(posterTarget) (string, error) {
			store, err := s.store()
			if err != nil {
				return "", err
			}
			name, err := store.Fetch(ctx, link)
			if err != nil {
				return "", pictureError(err)
			}
			return "/" + name, nil
		})
	}
	if t, err := s.target(ctx, kind, in.ID); err == nil && tmdbPath == t.tmdbPath {
		// The match's own poster is not a choice: it follows TMDB again.
		return s.apply(ctx, kind, in.ID, "", nil)
	}
	return s.apply(ctx, kind, in.ID, posterSourceTMDB, func(t posterTarget) (string, error) {
		if !tmdbPosterRe.MatchString(tmdbPath) || artwork.IsName(strings.TrimPrefix(tmdbPath, "/")) {
			return "", huma.Error422UnprocessableEntity("not one of TMDB's posters for this")
		}
		if !s.deps.TMDB.Configured() {
			return "", huma.Error503ServiceUnavailable("TMDB API key not configured")
		}
		own, show, err := t.lists(ctx, s.deps.TMDB)
		listed := func(p tmdb.Poster) bool { return p.FilePath == tmdbPath }
		if !slices.ContainsFunc(own, listed) && !slices.ContainsFunc(show, listed) {
			if err != nil {
				return "", huma.Error502BadGateway(err.Error())
			}
			return "", huma.Error422UnprocessableEntity("not one of TMDB's posters for this")
		}
		return tmdbPath, nil
	})
}

func (s *posterService) upload(ctx context.Context, kind string, in *UploadPosterInput) (*PosterOutput, error) {
	return s.apply(ctx, kind, in.ID, posterSourceUpload, func(posterTarget) (string, error) {
		store, err := s.store()
		if err != nil {
			return "", err
		}
		name, err := store.Save(bytes.NewReader(in.RawBody))
		if err != nil {
			return "", pictureError(err)
		}
		return "/" + name, nil
	})
}

func (s *posterService) reset(ctx context.Context, kind string, in *PosterInput) (*PosterOutput, error) {
	return s.apply(ctx, kind, in.ID, "", nil)
}

func registerPosters(h huma.API, deps Deps) {
	s := &posterService{deps: deps}
	for _, k := range []struct{ kind, name, path, a string }{
		{"movie", "Movie", "/movies/{id}", "a movie's"},
		{"show", "Show", "/shows/{id}", "a show's"},
		{"season", "Season", "/seasons/{id}", "a season's"},
	} {
		huma.Register(h, huma.Operation{
			OperationID: "get" + k.name + "Posters",
			Method:      http.MethodGet,
			Path:        Prefix + k.path + "/posters",
			Summary:     "The posters an admin can choose for " + strings.TrimSuffix(k.a, "'s"),
			Description: "The poster shown now, the match's own, and TMDB's other posters. Admins only.",
			Tags:        []string{"posters"},
		}, func(ctx context.Context, in *PosterInput) (*PosterOptionsOutput, error) {
			return s.options(ctx, k.kind, in.ID)
		})
		huma.Register(h, huma.Operation{
			OperationID: "set" + k.name + "Poster",
			Method:      http.MethodPut,
			Path:        Prefix + k.path + "/poster",
			Summary:     "Choose " + k.a + " poster: one of TMDB's, or a picture at a link",
			Description: "A link is fetched once and the picture kept on the server; links into the server's own network are refused. The choice stays through refreshes from TMDB. Admins only.",
			Tags:        []string{"posters"},
		}, func(ctx context.Context, in *SetPosterInput) (*PosterOutput, error) {
			return s.set(ctx, k.kind, in)
		})
		huma.Register(h, huma.Operation{
			OperationID:  "upload" + k.name + "Poster",
			Method:       http.MethodPost,
			Path:         Prefix + k.path + "/poster/upload",
			Summary:      "Upload a picture as " + k.a + " poster",
			Description:  "The request body is the picture itself. It is kept as a JPEG of at most 2000 by 3000. Admins only.",
			Tags:         []string{"posters"},
			MaxBodyBytes: artwork.MaxBytes,
		}, func(ctx context.Context, in *UploadPosterInput) (*PosterOutput, error) {
			return s.upload(ctx, k.kind, in)
		})
		huma.Register(h, huma.Operation{
			OperationID: "reset" + k.name + "Poster",
			Method:      http.MethodDelete,
			Path:        Prefix + k.path + "/poster",
			Summary:     "Go back to the match's own poster for " + strings.TrimSuffix(k.a, "'s"),
			Tags:        []string{"posters"},
		}, func(ctx context.Context, in *PosterInput) (*PosterOutput, error) {
			return s.reset(ctx, k.kind, in)
		})
	}
}
