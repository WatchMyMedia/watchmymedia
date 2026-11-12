package api

import (
	"cmp"
	"context"
	"path/filepath"
	"slices"
	"strings"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/parser"
	"watchmymedia/server/internal/settings"
	"watchmymedia/server/internal/trailers"
)

// Trailer sources (MovieTrailer.Source).
const (
	TrailerYouTube = "youtube"
	TrailerFile    = "file"
)

// MovieTrailer is one of a movie's trailers; the server lists them best
// first (PLAN-TRAILERS.md §5.3).
type MovieTrailer struct {
	// Source is a plain string, not an enum, so a source added later can't
	// break decoding in an older app; apps skip sources they don't know.
	Source     string `json:"source" doc:"\"youtube\": Key is a YouTube video id, played by YouTube's own player or app. \"file\": play FileID like any other file. Apps skip sources they don't know."`
	Name       string `json:"name" doc:"TMDB's name for it (\"Official Trailer\"), or the file's."`
	FileID     int64  `json:"fileId,omitempty" required:"false" doc:"The trailer file, for source \"file\"."`
	DurationMs int64  `json:"durationMs,omitempty" required:"false" doc:"The trailer file's length; absent for YouTube trailers."`
	Key        string `json:"key,omitempty" required:"false" doc:"The YouTube video id, for source \"youtube\"."`
}

// movieTrailers are a movie's YouTube trailers as getMovie lists them,
// after its trailer files (fileTrailers), while YouTube trailers are on.
func movieTrailers(deps Deps, stored string) []MovieTrailer {
	if !deps.Trailers.YouTube() {
		return nil
	}
	var out []MovieTrailer
	for _, t := range trailers.Decode(stored) {
		out = append(out, MovieTrailer{Source: TrailerYouTube, Name: t.Name, Key: t.Key})
	}
	return out
}

// fileTrailers are a movie's trailer files, as trailers: those next to the
// movie first, then those in its Trailers folder, each by name.
func fileTrailers(ctx context.Context, deps Deps, movieID int64) ([]MovieTrailer, error) {
	rows, err := deps.Queries.ListMovieTrailerFiles(ctx, &movieID)
	if err != nil {
		return nil, err
	}
	inFolder := func(p string) bool { return parser.TrailersDir(filepath.Base(filepath.Dir(p))) }
	slices.SortStableFunc(rows, func(a, b db.ListMovieTrailerFilesRow) int {
		switch fa, fb := inFolder(a.Path), inFolder(b.Path); {
		case fa == fb:
			return 0
		case fb:
			return -1
		}
		return 1
	})
	var out []MovieTrailer
	for _, r := range rows {
		name := "Trailer"
		if inFolder(r.Path) {
			name = cmp.Or(strings.TrimSpace(strings.TrimSuffix(filepath.Base(r.Path), filepath.Ext(r.Path))), name)
		}
		out = append(out, MovieTrailer{Source: TrailerFile, Name: name, FileID: r.ID, DurationMs: deref(r.DurationMs)})
	}
	return out, nil
}

// TrailersYouTube is the Trailers from YouTube setting: on unless turned off.
func TrailersYouTube(ctx context.Context, store *settings.Store) bool {
	if store == nil {
		return true
	}
	v, _ := store.Get(ctx, settings.KeyTrailersYouTube, "1")
	return v != "0"
}

// saveTrailersYouTube turns YouTube trailers on or off: in getMovie's
// replies, the web app's Content-Security-Policy and the refresh job.
func saveTrailersYouTube(ctx context.Context, deps Deps, on bool) error {
	if deps.Settings == nil {
		return huma.Error503ServiceUnavailable("settings are not available")
	}
	v := "0"
	if on {
		v = "1"
	}
	if err := deps.Settings.Set(ctx, settings.KeyTrailersYouTube, v); err != nil {
		return err
	}
	deps.Trailers.SetYouTube(on)
	return nil
}
