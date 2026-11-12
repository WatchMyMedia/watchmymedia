package scanner

import (
	"context"
	"path/filepath"
	"slices"
	"strings"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/library"
	"watchmymedia/server/internal/parser"
)

// linkChunk is how many of a folder's files are looked up at once while
// telling whether it holds a single movie; a folder of many movies shows it
// in the first few.
const linkChunk = 64

// LinkTrailers links each trailer in a movie library to the movie it
// belongs to (PLAN-TRAILERS.md §5.2) and returns how many links changed.
// A trailer belongs to the movie whose file is in its folder, or in the
// folder above Trailers:
//   - "<name>-trailer.ext" to the movie of the file named <name>;
//   - trailer.ext, movie-trailer.ext and Trailers/ only when that folder
//     holds a single movie, every one of its files matched to it.
//
// A trailer that links to nothing stays out of the library. Scans and
// matching call it after they change something, and so does matching a file
// by hand, since a trailer follows its movie's files.
func LinkTrailers(ctx context.Context, q *db.Queries, lib db.Library) (int, error) {
	if lib.Type != library.TypeMovie {
		return 0, nil
	}
	trailers, err := q.ListLibraryTrailers(ctx, lib.ID)
	if err != nil || len(trailers) == 0 {
		return 0, err
	}
	l := linker{q: q, root: filepath.Clean(lib.Path), found: map[string]*int64{}}
	changed := 0
	for _, t := range trailers {
		var movie *int64
		if rel, ok := below(l.root, t.Path); ok {
			if x, ok := parser.ParseExtra(filepath.ToSlash(rel)); ok {
				if movie, err = l.movieFor(ctx, x); err != nil {
					return changed, err
				}
			}
		}
		if sameID(movie, t.ExtraOf) {
			continue
		}
		if err := q.SetMediaFileExtraOf(ctx, db.SetMediaFileExtraOfParams{ExtraOf: movie, ID: t.ID}); err != nil {
			return changed, err
		}
		changed++
	}
	return changed, nil
}

// linker finds trailers' movies, once per folder or file name.
type linker struct {
	q     *db.Queries
	root  string
	found map[string]*int64
}

func (l *linker) movieFor(ctx context.Context, x parser.Extra) (*int64, error) {
	dir := l.root
	if x.Dir != "" {
		dir = filepath.Join(l.root, filepath.FromSlash(x.Dir))
	}
	key := dir + "\x00" + x.Of
	if m, ok := l.found[key]; ok {
		return m, nil
	}
	var m *int64
	var err error
	if x.Of != "" {
		m, err = l.named(ctx, dir, x.Of)
	} else {
		m, err = l.only(ctx, dir)
	}
	if err == nil {
		l.found[key] = m
	}
	return m, err
}

// named is the movie of the file in dir named name, with any extension.
// Its paths are a range of the path index: name and a dot up to name and
// the byte after the dot ('/').
func (l *linker) named(ctx context.Context, dir, name string) (*int64, error) {
	base := filepath.Join(dir, name)
	rows, err := l.q.ListPathsUnder(ctx, db.ListPathsUnderParams{Lo: base + ".", Hi: base + "/"})
	if err != nil {
		return nil, err
	}
	var ids []int64
	for _, r := range rows {
		if filepath.Dir(r.Path) == dir && parser.IsVideo(r.Path) && strings.TrimSuffix(filepath.Base(r.Path), filepath.Ext(r.Path)) == name {
			ids = append(ids, r.ID)
		}
	}
	return l.single(ctx, ids, false)
}

// only is the movie of every library file directly in dir, when they all
// belong to one.
func (l *linker) only(ctx context.Context, dir string) (*int64, error) {
	sep := string(filepath.Separator)
	lo := strings.TrimSuffix(dir, sep) + sep
	rows, err := l.q.ListPathsUnder(ctx, db.ListPathsUnderParams{Lo: lo, Hi: lo[:len(lo)-1] + string(filepath.Separator+1)})
	if err != nil {
		return nil, err
	}
	var ids []int64
	for _, r := range rows {
		if filepath.Dir(r.Path) != dir {
			continue // in a subfolder
		}
		if rel, ok := below(l.root, r.Path); ok {
			if _, extra := parser.ParseExtra(filepath.ToSlash(rel)); extra {
				continue
			}
		}
		ids = append(ids, r.ID)
	}
	return l.single(ctx, ids, true)
}

// single is the one movie the present library files among ids belong to,
// or nil when they belong to none or several. With strict, every one of
// them must be matched to it: a folder with a file not matched yet may
// hold another movie.
func (l *linker) single(ctx context.Context, ids []int64, strict bool) (*int64, error) {
	var movie *int64
	for chunk := range slices.Chunk(ids, linkChunk) {
		rows, err := l.q.ListLinkTargets(ctx, chunk)
		if err != nil {
			return nil, err
		}
		for _, r := range rows {
			switch {
			case r.MovieID == nil && strict:
				return nil, nil
			case r.MovieID == nil:
			case movie == nil:
				movie = r.MovieID
			case *movie != *r.MovieID:
				return nil, nil
			}
		}
	}
	return movie, nil
}

func sameID(a, b *int64) bool {
	return a == nil && b == nil || a != nil && b != nil && *a == *b
}
