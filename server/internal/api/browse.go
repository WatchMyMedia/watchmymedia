package api

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"

	"github.com/danielgtaylor/huma/v2"

	"time"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/integrations"
	"watchmymedia/server/internal/metadata"
	"watchmymedia/server/internal/probe"
)

// Movie is a matched movie with at least one present file.
type Movie struct {
	ID            int64             `json:"id"`
	LibraryID     int64             `json:"libraryId"`
	TMDBID        int64             `json:"tmdbId"`
	IMDBID        string            `json:"imdbId,omitempty"`
	Title         string            `json:"title"`
	OriginalTitle string            `json:"originalTitle,omitempty"`
	Year          int64             `json:"year,omitempty"`
	ReleaseDate   string            `json:"releaseDate,omitempty"`
	Overview      string            `json:"overview"`
	Tagline       string            `json:"tagline,omitempty"`
	RuntimeMin    int64             `json:"runtimeMin,omitempty"`
	Genres        []string          `json:"genres" nullable:"false"`
	Rating        float64           `json:"rating,omitempty" doc:"TMDB vote average, 0-10."`
	ContentRating string            `json:"contentRating,omitempty" example:"PG-13" doc:"US certification from TMDB; empty when it has none."`
	PosterPath    string            `json:"posterPath,omitempty" doc:"Image path; fetch via /api/images/{size}{posterPath}. TMDB's, or a picture an admin chose."`
	PosterSource  string            `json:"posterSource,omitempty" required:"false" enum:"tmdb,upload,url" doc:"Set when an admin chose the poster: another of TMDB's, an upload or a link. Absent for the match's own poster."`
	BackdropPath  string            `json:"backdropPath,omitempty"`
	FileCount     int64             `json:"fileCount"`
	Credits       []metadata.Credit `json:"credits" nullable:"false"`
}

type MovieList struct {
	Items  []Movie `json:"items" nullable:"false"`
	Total  int64   `json:"total"`
	Limit  int64   `json:"limit"`
	Offset int64   `json:"offset"`
}

type MovieDetail struct {
	Movie
	// Files are the versions on disk, each with its full probe for track lists.
	Files     []MediaFileDetail `json:"files" nullable:"false"`
	Quality   string            `json:"quality,omitempty" enum:",4K,1080p,720p,SD"`
	HDR       bool              `json:"hdr"`
	TotalSize int64             `json:"totalSize"`
	AddedAt   *time.Time        `json:"addedAt,omitempty"`
	Watch     *WatchState       `json:"watch,omitempty" doc:"The signed-in user's progress."`
	// ManagedBy and Download come from Radarr (PLAN-ARR.md §6).
	ManagedBy string                 `json:"managedBy,omitempty" required:"false" doc:"The Radarr connection that has the movie."`
	Download  *integrations.Download `json:"download,omitempty" required:"false" doc:"A download of it in Radarr's queue, such as an upgrade."`
	// Trailers are the movie's trailer files in the library, then its
	// YouTube trailers from TMDB (PLAN-TRAILERS.md §5.3), APILevel 20.
	Trailers []MovieTrailer `json:"trailers,omitempty" required:"false" nullable:"false" doc:"The movie's trailers, best first: trailer files in the library, then YouTube trailers while they are on. Apps play the first one whose source they know. Absent when it has none, and on older servers."`
}

// Show is a matched series with at least one present episode file.
type Show struct {
	ID            int64             `json:"id"`
	LibraryID     int64             `json:"libraryId"`
	TMDBID        int64             `json:"tmdbId"`
	Title         string            `json:"title"`
	OriginalTitle string            `json:"originalTitle,omitempty"`
	Year          int64             `json:"year,omitempty"`
	FirstAirDate  string            `json:"firstAirDate,omitempty"`
	Overview      string            `json:"overview"`
	Status        string            `json:"status,omitempty" example:"Ended"`
	Genres        []string          `json:"genres" nullable:"false"`
	Rating        float64           `json:"rating,omitempty"`
	ContentRating string            `json:"contentRating,omitempty" example:"TV-14" doc:"US certification from TMDB; empty when it has none."`
	PosterPath    string            `json:"posterPath,omitempty"`
	PosterSource  string            `json:"posterSource,omitempty" required:"false" enum:"tmdb,upload,url" doc:"Set when an admin chose the poster; absent for the match's own."`
	BackdropPath  string            `json:"backdropPath,omitempty"`
	EpisodeCount  int64             `json:"episodeCount" doc:"Episodes with a present file."`
	LastAirDate   string            `json:"lastAirDate,omitempty"`
	NextAirDate   string            `json:"nextAirDate,omitempty"`
	Credits       []metadata.Credit `json:"credits" nullable:"false"`
}

type ShowList struct {
	Items  []Show `json:"items" nullable:"false"`
	Total  int64  `json:"total"`
	Limit  int64  `json:"limit"`
	Offset int64  `json:"offset"`
}

type Season struct {
	ID             int64  `json:"id"`
	ShowID         int64  `json:"showId"`
	Number         int64  `json:"number"`
	Name           string `json:"name"`
	Overview       string `json:"overview"`
	AirDate        string `json:"airDate,omitempty"`
	PosterPath     string `json:"posterPath,omitempty"`
	PosterSource   string `json:"posterSource,omitempty" required:"false" enum:"tmdb,upload,url" doc:"Set when an admin chose the poster; absent for the match's own."`
	EpisodeCount   int64  `json:"episodeCount" doc:"Episodes TMDB lists for the season."`
	AvailableCount int64  `json:"availableCount" doc:"Episodes with a present file."`
	WatchedCount   int64  `json:"watchedCount" doc:"Episodes the signed-in user has watched."`
	// Monitored and Request come from Sonarr and requests (PLAN-ARR.md §6).
	Monitored *bool                      `json:"monitored,omitempty" required:"false" doc:"Sonarr monitors the season; absent when no Sonarr has the show."`
	Request   *integrations.RequestState `json:"request,omitempty" required:"false" doc:"The newest request for the season."`
}

type ShowDetail struct {
	Show
	Seasons       []Season   `json:"seasons" nullable:"false"`
	TotalEpisodes int64      `json:"totalEpisodes" doc:"Episodes TMDB lists across all seasons."`
	Quality       string     `json:"quality,omitempty" enum:",4K,1080p,720p,SD"`
	HDR           bool       `json:"hdr"`
	TotalSize     int64      `json:"totalSize"`
	FileCount     int64      `json:"fileCount"`
	AddedAt       *time.Time `json:"addedAt,omitempty"`
	// NextEpisode is the first unwatched episode on disk, for "Play next".
	NextEpisode *NextEpisode `json:"nextEpisode,omitempty"`
	ManagedBy   string       `json:"managedBy,omitempty" required:"false" doc:"The Sonarr connection that has the show."`
}

type NextEpisode struct {
	EpisodeID     int64  `json:"episodeId"`
	SeasonNumber  int64  `json:"seasonNumber"`
	EpisodeNumber int64  `json:"episodeNumber"`
	Title         string `json:"title"`
	FileID        int64  `json:"fileId"`
}

type Episode struct {
	ID         int64       `json:"id"`
	SeasonID   int64       `json:"seasonId"`
	Number     int64       `json:"number"`
	Title      string      `json:"title"`
	Overview   string      `json:"overview"`
	AirDate    string      `json:"airDate,omitempty"`
	StillPath  string      `json:"stillPath,omitempty"`
	RuntimeMin int64       `json:"runtimeMin,omitempty"`
	Rating     float64     `json:"rating,omitempty"`
	Files      []MediaFile `json:"files" nullable:"false"`
	HasFile    bool        `json:"hasFile"`
	Aired      bool        `json:"aired" doc:"Air date is today or earlier."`
	AddedAt    *time.Time  `json:"addedAt,omitempty" doc:"When its newest file was added: its date on disk when first found."`
	IsNew      bool        `json:"isNew"`
	Watch      *WatchState `json:"watch,omitempty"`
	// Monitored, Download and Request say why a missing episode is missing
	// (PLAN-ARR.md §6).
	Monitored *bool                      `json:"monitored,omitempty" required:"false" doc:"Sonarr monitors the episode; absent when no Sonarr has the show."`
	Download  *integrations.Download     `json:"download,omitempty" required:"false" doc:"Its download in Sonarr's queue."`
	Request   *integrations.RequestState `json:"request,omitempty" required:"false" doc:"The newest request for its season."`
}

type SeasonDetail struct {
	Season
	Show     Show      `json:"show"`
	Episodes []Episode `json:"episodes" nullable:"false"`
}

type PageInput struct {
	ID     int64 `path:"id" doc:"Library ID."`
	Limit  int64 `query:"limit" default:"100" minimum:"1" maximum:"500"`
	Offset int64 `query:"offset" default:"0" minimum:"0"`
}

type ListMoviesOutput struct{ Body MovieList }
type GetMovieOutput struct{ Body MovieDetail }
type ListShowsOutput struct{ Body ShowList }
type GetShowOutput struct{ Body ShowDetail }
type GetSeasonOutput struct{ Body SeasonDetail }

type MovieIDInput struct {
	ID int64 `path:"id" doc:"Movie ID."`
}

type ShowIDInput struct {
	ID int64 `path:"id" doc:"Show ID."`
}

type SeasonInput struct {
	ID     int64 `path:"id" doc:"Show ID."`
	Number int64 `path:"number" minimum:"0" doc:"Season number; 0 is specials."`
}

func registerBrowse(h huma.API, deps Deps) {
	huma.Register(h, huma.Operation{
		OperationID: "listMovies",
		Method:      http.MethodGet,
		Path:        Prefix + "/libraries/{id}/movies",
		Summary:     "List matched movies in a library",
		Tags:        []string{"browse"},
	}, func(ctx context.Context, in *PageInput) (*ListMoviesOutput, error) {
		if _, err := getLibrary(ctx, deps, in.ID); err != nil {
			return nil, err
		}
		total, err := deps.Queries.CountMovies(ctx, in.ID)
		if err != nil {
			return nil, err
		}
		rows, err := deps.Queries.ListMovies(ctx, db.ListMoviesParams{LibraryID: in.ID, Limit: in.Limit, Offset: in.Offset})
		if err != nil {
			return nil, err
		}
		out := &ListMoviesOutput{}
		out.Body.Total, out.Body.Limit, out.Body.Offset = total, in.Limit, in.Offset
		out.Body.Items = make([]Movie, 0, len(rows))
		for _, r := range rows {
			m := movieFromDB(r.Movie)
			m.FileCount = r.FileCount
			out.Body.Items = append(out.Body.Items, m)
		}
		return out, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "getMovie",
		Method:      http.MethodGet,
		Path:        Prefix + "/movies/{id}",
		Summary:     "Get a movie with its files",
		Tags:        []string{"browse"},
	}, func(ctx context.Context, in *MovieIDInput) (*GetMovieOutput, error) {
		row, err := deps.Queries.GetMovie(ctx, in.ID)
		if err != nil {
			return nil, notFound(err, "movie not found")
		}
		lib, err := deps.Queries.GetLibrary(ctx, row.LibraryID)
		if err != nil {
			return nil, err
		}
		files, err := deps.Queries.ListMovieFiles(ctx, &row.ID)
		if err != nil {
			return nil, err
		}
		out := &GetMovieOutput{Body: MovieDetail{Movie: movieFromDB(row), Files: make([]MediaFileDetail, 0, len(files))}}
		var maxHeight int64
		for _, f := range files {
			d := MediaFileDetail{MediaFile: mediaFileFromDB(f, lib.Path)}
			if f.Probe != nil {
				if info, err := probe.Parse(*f.Probe); err == nil {
					d.Probe = info
				}
			}
			out.Body.Files = append(out.Body.Files, d)
			if f.Status != "present" {
				continue
			}
			out.Body.TotalSize += f.Size
			maxHeight = max(maxHeight, deref(f.Height))
			if f.Hdr != "" {
				out.Body.HDR = true
			}
			if t := db.ParseTime(f.AddedAt); out.Body.AddedAt == nil || t.After(*out.Body.AddedAt) {
				out.Body.AddedAt = &t
			}
		}
		out.Body.FileCount = int64(len(files))
		out.Body.Quality = qualityLabel(maxHeight)
		if out.Body.Trailers, err = fileTrailers(ctx, deps, row.ID); err != nil {
			return nil, err
		}
		out.Body.Trailers = append(out.Body.Trailers, movieTrailers(deps, row.Trailers)...)
		if user, ok := auth.UserFrom(ctx); ok {
			w, err := deps.Queries.GetWatchState(ctx, db.GetWatchStateParams{UserID: user.ID, ItemType: "movie", ItemID: row.ID})
			switch {
			case err == nil:
				ws := watchFromDB(w)
				out.Body.Watch = &ws
			case !errors.Is(err, sql.ErrNoRows):
				return nil, err
			}
		}
		if s := deps.Integrations; s != nil {
			paths := make([]string, 0, len(files))
			for _, f := range files {
				paths = append(paths, f.Path)
			}
			if st := s.Movie(ctx, integrations.Item{LibraryPath: lib.Path, TMDBID: int(row.TmdbID), Files: paths}); st != nil {
				out.Body.ManagedBy, out.Body.Download = st.Connection, st.Download
			}
		}
		var recordingFiles []*MediaFile
		for i := range out.Body.Files {
			recordingFiles = append(recordingFiles, &out.Body.Files[i].MediaFile)
		}
		if err := decorateRecordingFiles(ctx, deps, recordingFiles); err != nil {
			return nil, err
		}
		return out, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "listShows",
		Method:      http.MethodGet,
		Path:        Prefix + "/libraries/{id}/shows",
		Summary:     "List matched shows in a library",
		Tags:        []string{"browse"},
	}, func(ctx context.Context, in *PageInput) (*ListShowsOutput, error) {
		if _, err := getLibrary(ctx, deps, in.ID); err != nil {
			return nil, err
		}
		total, err := deps.Queries.CountShows(ctx, in.ID)
		if err != nil {
			return nil, err
		}
		rows, err := deps.Queries.ListShows(ctx, db.ListShowsParams{LibraryID: in.ID, Limit: in.Limit, Offset: in.Offset})
		if err != nil {
			return nil, err
		}
		out := &ListShowsOutput{}
		out.Body.Total, out.Body.Limit, out.Body.Offset = total, in.Limit, in.Offset
		out.Body.Items = make([]Show, 0, len(rows))
		for _, r := range rows {
			s := showFromDB(r.Show)
			s.EpisodeCount = r.EpisodeCount
			out.Body.Items = append(out.Body.Items, s)
		}
		return out, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "getShow",
		Method:      http.MethodGet,
		Path:        Prefix + "/shows/{id}",
		Summary:     "Get a show with its seasons",
		Tags:        []string{"browse"},
	}, func(ctx context.Context, in *ShowIDInput) (*GetShowOutput, error) {
		row, err := deps.Queries.GetShow(ctx, in.ID)
		if err != nil {
			return nil, notFound(err, "show not found")
		}
		seasons, err := deps.Queries.ListShowSeasons(ctx, row.ID)
		if err != nil {
			return nil, err
		}
		out := &GetShowOutput{Body: ShowDetail{Show: showFromDB(row), Seasons: make([]Season, 0, len(seasons))}}
		for _, s := range seasons {
			out.Body.Seasons = append(out.Body.Seasons, seasonFromDB(s.Season, s.AvailableCount))
			out.Body.EpisodeCount += s.AvailableCount
			out.Body.TotalEpisodes += s.Season.EpisodeCount
		}
		stats, err := deps.Queries.GetShowFileStats(ctx, row.ID)
		if err != nil {
			return nil, err
		}
		out.Body.Quality, out.Body.HDR = qualityLabel(toInt64(stats.MaxHeight)), toInt64(stats.HasHdr) == 1
		out.Body.TotalSize = toInt64(stats.TotalSize)
		if t := db.ParseTime(toString(stats.AddedAt)); !t.IsZero() {
			out.Body.AddedAt = &t
		}
		fileRows, err := deps.Queries.ListShowFiles(ctx, row.ID)
		if err != nil {
			return nil, err
		}
		out.Body.FileCount = fileRows
		if user, ok := auth.UserFrom(ctx); ok {
			counts, err := deps.Queries.ListSeasonWatchedCounts(ctx, db.ListSeasonWatchedCountsParams{UserID: user.ID, ShowID: row.ID})
			if err != nil {
				return nil, err
			}
			watchedBySeason := map[int64]int64{}
			for _, c := range counts {
				watchedBySeason[c.SeasonID] = c.Watched
			}
			for i := range out.Body.Seasons {
				out.Body.Seasons[i].WatchedCount = watchedBySeason[out.Body.Seasons[i].ID]
			}
			n, err := deps.Queries.NextUnwatchedEpisode(ctx, db.NextUnwatchedEpisodeParams{UserID: user.ID, ShowID: row.ID})
			switch {
			case err == nil:
				out.Body.NextEpisode = &NextEpisode{EpisodeID: n.EpisodeID, SeasonNumber: n.SeasonNumber, EpisodeNumber: n.EpisodeNumber, Title: n.Title, FileID: toInt64(n.FileID)}
			case !errors.Is(err, sql.ErrNoRows):
				return nil, err
			}
		}
		if s := deps.Integrations; s != nil {
			reqs := seasonRequests(ctx, s, row.TmdbID)
			var monitored map[int]bool
			if it, ok := libraryShowItem(ctx, deps, row.ID); ok {
				out.Body.ManagedBy, monitored = s.SeriesSeasons(it)
			}
			for i := range out.Body.Seasons {
				n := int(out.Body.Seasons[i].Number)
				if m, ok := monitored[n]; ok {
					out.Body.Seasons[i].Monitored = &m
				}
				out.Body.Seasons[i].Request = reqs[n]
			}
		}
		return out, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "getSeason",
		Method:      http.MethodGet,
		Path:        Prefix + "/shows/{id}/seasons/{number}",
		Summary:     "Get a season with its episodes and files",
		Tags:        []string{"browse"},
	}, func(ctx context.Context, in *SeasonInput) (*GetSeasonOutput, error) {
		show, err := deps.Queries.GetShow(ctx, in.ID)
		if err != nil {
			return nil, notFound(err, "show not found")
		}
		lib, err := deps.Queries.GetLibrary(ctx, show.LibraryID)
		if err != nil {
			return nil, err
		}
		season, err := deps.Queries.GetSeasonByNumber(ctx, db.GetSeasonByNumberParams{ShowID: show.ID, Number: in.Number})
		if err != nil {
			return nil, notFound(err, "season not found")
		}
		episodes, err := deps.Queries.ListSeasonEpisodes(ctx, season.ID)
		if err != nil {
			return nil, err
		}
		fileRows, err := deps.Queries.ListSeasonFiles(ctx, season.ID)
		if err != nil {
			return nil, err
		}
		filesByEpisode := map[int64][]MediaFile{}
		for _, r := range fileRows {
			filesByEpisode[r.EpisodeID] = append(filesByEpisode[r.EpisodeID], mediaFileFromDB(r.MediaFile, lib.Path))
		}
		var recordingFiles []*MediaFile
		for id, files := range filesByEpisode {
			for i := range files {
				recordingFiles = append(recordingFiles, &files[i])
			}
			filesByEpisode[id] = files
		}
		if err := decorateRecordingFiles(ctx, deps, recordingFiles); err != nil {
			return nil, err
		}

		out := &GetSeasonOutput{Body: SeasonDetail{Show: showFromDB(show), Episodes: make([]Episode, 0, len(episodes))}}
		available := int64(0)
		today := time.Now().UTC().Format("2006-01-02")
		watch := map[int64]WatchState{}
		if user, ok := auth.UserFrom(ctx); ok {
			states, err := deps.Queries.ListShowWatchStates(ctx, db.ListShowWatchStatesParams{UserID: user.ID, ShowID: show.ID})
			if err != nil {
				return nil, err
			}
			for _, w := range states {
				watch[w.ItemID] = watchFromDB(w)
			}
		}
		for _, e := range episodes {
			// Only present files: one that went missing (deleted in Sonarr or
			// on disk) leaves the episode missing, as the season's count has it.
			files := []MediaFile{}
			for _, f := range filesByEpisode[e.ID] {
				if f.Status == "present" {
					files = append(files, f)
				}
			}
			if len(files) > 0 {
				available++
			}
			ep := Episode{
				ID: e.ID, SeasonID: e.SeasonID, Number: e.Number, Title: e.Title, Overview: e.Overview,
				AirDate: deref(e.AirDate), StillPath: deref(e.StillPath), RuntimeMin: deref(e.RuntimeMin), Rating: deref(e.Rating),
				Files: files, HasFile: len(files) > 0,
			}
			ep.Aired = ep.AirDate != "" && ep.AirDate <= today
			for _, f := range files {
				if f.Status != "present" {
					continue
				}
				t := f.AddedAt
				if ep.AddedAt == nil || t.After(*ep.AddedAt) {
					ep.AddedAt = &t
				}
			}
			w, seen := watch[e.ID]
			ep.IsNew = ep.AddedAt != nil && time.Since(*ep.AddedAt) < newWindow && (!seen || !w.Completed)
			if seen {
				ep.Watch = &w
				if w.Completed {
					out.Body.WatchedCount++
				}
			}
			out.Body.Episodes = append(out.Body.Episodes, ep)
		}
		out.Body.Season = seasonFromDB(season, available)
		if s := deps.Integrations; s != nil {
			req := seasonRequests(ctx, s, show.TmdbID)[int(season.Number)]
			out.Body.Request = req
			paths := make([]string, 0, 3)
			for _, r := range fileRows {
				if r.MediaFile.Status == "present" && len(paths) < 3 {
					paths = append(paths, r.MediaFile.Path)
				}
			}
			if len(paths) == 0 {
				paths, _ = deps.Queries.ListShowFilePaths(ctx, show.ID)
			}
			st := s.Series(ctx, integrations.Item{LibraryPath: lib.Path, TMDBID: int(show.TmdbID), Files: paths})
			if st != nil {
				if m, ok := st.Seasons[int(season.Number)]; ok {
					out.Body.Monitored = &m
				}
			}
			for i := range out.Body.Episodes {
				ep := &out.Body.Episodes[i]
				if st != nil {
					if es, ok := st.Episodes[[2]int{int(season.Number), int(ep.Number)}]; ok {
						m := es.Monitored
						ep.Monitored, ep.Download = &m, es.Download
					}
				}
				if !ep.HasFile {
					ep.Request = req
				}
			}
		}
		return out, nil
	})
}

func genres(raw string) []string {
	var out []string
	if err := json.Unmarshal([]byte(raw), &out); err != nil || out == nil {
		return []string{}
	}
	return out
}

func movieFromDB(r db.Movie) Movie {
	return Movie{
		ID: r.ID, LibraryID: r.LibraryID, TMDBID: r.TmdbID, IMDBID: deref(r.ImdbID), Title: r.Title,
		OriginalTitle: deref(r.OriginalTitle), Year: deref(r.Year), ReleaseDate: deref(r.ReleaseDate), Overview: r.Overview,
		Tagline: deref(r.Tagline), RuntimeMin: deref(r.RuntimeMin), Genres: genres(r.Genres), Rating: deref(r.Rating),
		PosterPath: deref(r.PosterPath), BackdropPath: deref(r.BackdropPath), Credits: credits(r.Credits),
		ContentRating: deref(r.ContentRating), PosterSource: r.PosterSource,
	}
}

func showFromDB(r db.Show) Show {
	return Show{
		ID: r.ID, LibraryID: r.LibraryID, TMDBID: r.TmdbID, Title: r.Title, OriginalTitle: deref(r.OriginalTitle),
		Year: deref(r.Year), FirstAirDate: deref(r.FirstAirDate), Overview: r.Overview, Status: deref(r.Status),
		Genres: genres(r.Genres), Rating: deref(r.Rating), PosterPath: deref(r.PosterPath), BackdropPath: deref(r.BackdropPath),
		LastAirDate: deref(r.LastAirDate), NextAirDate: deref(r.NextAirDate), Credits: credits(r.Credits),
		ContentRating: deref(r.ContentRating), PosterSource: r.PosterSource,
	}
}

// seasonFromDB is a season with available of its episodes on disk.
func seasonFromDB(s db.Season, available int64) Season {
	return Season{
		ID: s.ID, ShowID: s.ShowID, Number: s.Number, Name: s.Name, Overview: s.Overview, AirDate: deref(s.AirDate),
		PosterPath: deref(s.PosterPath), PosterSource: s.PosterSource, EpisodeCount: s.EpisodeCount, AvailableCount: available,
	}
}
