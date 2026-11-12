package lists

import (
	"context"
	"encoding/csv"
	"errors"
	"fmt"
	"io"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"unicode/utf8"

	"golang.org/x/text/unicode/norm"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/tmdb"
)

// Import formats, as MediaListSource.format says them.
const (
	FormatIMDb       = "imdb"
	FormatLetterboxd = "letterboxd"
	FormatLines      = "lines"
)

// Line is one title an import names: by IMDb ID, by TMDB ID, or by its
// title and year.
type Line struct {
	IMDbID string
	TMDBID int64
	// MediaType is movie or tv when the import says, else "".
	MediaType string
	Title     string
	Year      int
}

var (
	imdbID    = regexp.MustCompile(`\btt\d{5,10}\b`)
	tmdbLink  = regexp.MustCompile(`themoviedb\.org/(?:[a-z]{2}(?:-[A-Z]{2})?/)?(movie|tv)/(\d+)`)
	titleYear = regexp.MustCompile(`^(.+?)\s*\((\d{4})\)$`)
	numbered  = regexp.MustCompile(`^\d{1,4}[.)]\s+`)
)

// Parse reads an import: an IMDb list export (CSV with a Const column of
// tt IDs), a Letterboxd export (CSV with Name and Year, after the export's
// own header lines), or lines of tt IDs, TMDB links or "Title (Year)". It
// keeps at most MaxEntries titles.
func Parse(text string) (format string, lines []Line, err error) {
	if len(text) > MaxImportBytes {
		return "", nil, invalid("The import is larger than 1 MB")
	}
	if !utf8.ValidString(text) {
		return "", nil, invalid("The import isn't text (UTF-8)")
	}
	text = strings.TrimPrefix(text, "\uFEFF")
	text = strings.ReplaceAll(strings.ReplaceAll(text, "\r\n", "\n"), "\r", "\n")
	text = norm.NFC.String(text)
	raw := strings.Split(text, "\n")
	for i, l := range raw {
		if i > 20 {
			break
		}
		cols := csvFields(l)
		switch {
		case hasColumn(cols, "Const"):
			lines, err := parseCSV(strings.Join(raw[i:], "\n"), FormatIMDb)
			return FormatIMDb, lines, err
		case hasColumn(cols, "Name") && hasColumn(cols, "Year") && (hasColumn(cols, "Letterboxd URI") || hasColumn(cols, "URL")):
			lines, err := parseCSV(strings.Join(raw[i:], "\n"), FormatLetterboxd)
			return FormatLetterboxd, lines, err
		}
	}
	for _, l := range raw {
		if len(lines) == MaxEntries {
			break
		}
		if line, ok := parseLine(l); ok {
			lines = append(lines, line)
		}
	}
	if len(lines) == 0 {
		return FormatLines, nil, invalid("The import names no titles: paste tt IDs, TMDB links or Title (Year) lines, or an IMDb or Letterboxd export")
	}
	return FormatLines, lines, nil
}

func csvFields(line string) []string {
	r := csv.NewReader(strings.NewReader(line))
	r.LazyQuotes = true
	r.FieldsPerRecord = -1
	f, err := r.Read()
	if err != nil {
		return nil
	}
	return f
}

func hasColumn(cols []string, name string) bool { return column(cols, name) >= 0 }

func column(cols []string, name string) int {
	for i, c := range cols {
		if strings.EqualFold(strings.TrimSpace(c), name) {
			return i
		}
	}
	return -1
}

// parseCSV reads an export from its header row on.
func parseCSV(text, format string) ([]Line, error) {
	r := csv.NewReader(strings.NewReader(text))
	r.LazyQuotes = true
	r.FieldsPerRecord = -1
	header, err := r.Read()
	if err != nil {
		return nil, invalid("The export's header can't be read")
	}
	at := func(row []string, name string) string {
		if i := column(header, name); i >= 0 && i < len(row) {
			return strings.TrimSpace(row[i])
		}
		return ""
	}
	var out []Line
	for len(out) < MaxEntries {
		row, err := r.Read()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			continue
		}
		year, _ := strconv.Atoi(at(row, "Year"))
		switch format {
		case FormatIMDb:
			id := imdbID.FindString(at(row, "Const"))
			if id == "" {
				continue
			}
			l := Line{IMDbID: id, Title: at(row, "Title"), Year: year}
			switch strings.ToLower(at(row, "Title Type")) {
			case "tvseries", "tv series", "tvminiseries", "tv mini series":
				l.MediaType = "tv"
			case "tvepisode", "tv episode", "podcastepisode", "podcastseries", "videogame", "video game":
				continue // not something a library holds as a title
			case "":
			default:
				l.MediaType = "movie"
			}
			out = append(out, l)
		case FormatLetterboxd:
			name := at(row, "Name")
			if name == "" {
				// A blank line or another section of the export.
				continue
			}
			out = append(out, Line{Title: name, Year: year, MediaType: "movie"})
		}
	}
	if len(out) == 0 {
		return nil, invalid("The export names no titles")
	}
	return out, nil
}

// parseLine reads one pasted line.
func parseLine(l string) (Line, bool) {
	l = strings.TrimSpace(l)
	if l == "" || strings.HasPrefix(l, "#") {
		return Line{}, false
	}
	l = numbered.ReplaceAllString(l, "")
	if m := tmdbLink.FindStringSubmatch(l); m != nil {
		id, _ := strconv.ParseInt(m[2], 10, 64)
		return Line{TMDBID: id, MediaType: m[1]}, id > 0
	}
	if id := imdbID.FindString(l); id != "" {
		return Line{IMDbID: id}, true
	}
	if m := titleYear.FindStringSubmatch(l); m != nil {
		year, _ := strconv.Atoi(m[2])
		return Line{Title: strings.TrimSpace(m[1]), Year: year}, true
	}
	return Line{Title: l}, utf8.RuneCountInString(l) <= 200
}

// Importer turns an import's lines into entries: IMDb IDs through the
// library's own movies first, then TMDB's /find; titles through TMDB's
// search by title and year. Titles nothing matches are kept, not found.
type Importer struct {
	q    *db.Queries
	tmdb *tmdb.Client
	find *resolver
	// Workers is how many lines are looked up at once; TMDB's limiter
	// paces them anyway.
	Workers int
}

func (*Importer) Kind() string { return KindImport }

// Name says where an import came from, for the row's line.
func (*Importer) Name(p Params) string {
	switch p.Format {
	case FormatIMDb:
		return "an IMDb export"
	case FormatLetterboxd:
		return "a Letterboxd export"
	}
	return "your list"
}

func (*Importer) Validate(r Request) error {
	if strings.TrimSpace(r.Params.Text) == "" {
		return invalid("Paste the list, or choose an IMDb or Letterboxd export")
	}
	if len(r.Params.Text) > MaxImportBytes {
		return invalid("The import is larger than 1 MB")
	}
	return nil
}

// Fetch parses the request's text and looks its titles up.
func (im *Importer) Fetch(ctx context.Context, r Request) (Result, error) {
	if err := im.Validate(r); err != nil {
		return Result{}, err
	}
	format, lines, err := Parse(r.Params.Text)
	if err != nil {
		return Result{}, err
	}
	entries, err := im.Resolve(ctx, lines, r.MediaType)
	if err != nil {
		return Result{}, err
	}
	return Result{Entries: finish(entries, r.MediaType, r.Limit), Format: format}, nil
}

// Resolve looks lines up, in order. It fails only when ctx ends.
func (im *Importer) Resolve(ctx context.Context, lines []Line, mediaType string) ([]Entry, error) {
	kept := make([]Line, 0, len(lines))
	for _, l := range lines {
		if l.MediaType == "" || keeps(mediaType, l.MediaType) {
			kept = append(kept, l)
		}
	}
	local, err := im.libraryTitles(ctx, kept, mediaType)
	if err != nil {
		return nil, err
	}
	out := make([]Entry, len(kept))
	work := make(chan int)
	var wg sync.WaitGroup
	for range max(im.Workers, 1) {
		wg.Go(func() {
			for i := range work {
				out[i] = im.resolveLine(ctx, kept[i], mediaType, local)
			}
		})
	}
	for i := range kept {
		select {
		case work <- i:
		case <-ctx.Done():
		}
		if ctx.Err() != nil {
			break
		}
	}
	close(work)
	wg.Wait()
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return out, nil
}

// libraryTitles are the titles lines name that the library has already,
// by IMDb ID ("tt…") and by TMDB ID ("movie/603"), so an import of them
// asks TMDB nothing and works without a TMDB key.
func (im *Importer) libraryTitles(ctx context.Context, lines []Line, mediaType string) (map[string]Entry, error) {
	out := map[string]Entry{}
	if im.q == nil {
		return out, nil
	}
	var imdb []*string
	var movies, shows []int64
	for _, l := range lines {
		switch {
		case l.IMDbID != "" && keeps(mediaType, "movie"):
			imdb = append(imdb, &l.IMDbID)
		case l.TMDBID > 0 && l.MediaType == "movie":
			movies = append(movies, l.TMDBID)
		case l.TMDBID > 0 && l.MediaType == "tv":
			shows = append(shows, l.TMDBID)
		}
	}
	keep := func(key string, e Entry) {
		if _, ok := out[key]; !ok {
			out[key] = e
		}
	}
	if len(imdb) > 0 {
		rows, err := im.q.ListMoviesByIMDb(ctx, imdb)
		if err != nil {
			return nil, err
		}
		for _, r := range rows {
			if r.ImdbID != nil {
				keep(*r.ImdbID, Entry{MediaType: "movie", TMDBID: r.TmdbID, IMDbID: *r.ImdbID, Title: r.Title, Year: deref(r.Year), PosterPath: deref(r.PosterPath)})
			}
		}
	}
	if len(movies) > 0 {
		rows, err := im.q.ListMovieItemsByTMDB(ctx, movies)
		if err != nil {
			return nil, err
		}
		for _, r := range rows {
			keep(fmt.Sprintf("movie/%d", r.TmdbID), Entry{MediaType: "movie", TMDBID: r.TmdbID, Title: r.Title, Year: deref(r.Year), PosterPath: deref(r.PosterPath)})
		}
	}
	if len(shows) > 0 {
		rows, err := im.q.ListShowItemsByTMDB(ctx, shows)
		if err != nil {
			return nil, err
		}
		for _, r := range rows {
			keep(fmt.Sprintf("tv/%d", r.TmdbID), Entry{MediaType: "tv", TMDBID: r.TmdbID, Title: r.Title, Year: deref(r.Year), PosterPath: deref(r.PosterPath)})
		}
	}
	return out, nil
}

// resolveLine finds one line's title: in the library first, then on TMDB.
// A lookup that fails leaves it not found rather than failing the import.
func (im *Importer) resolveLine(ctx context.Context, l Line, mediaType string, local map[string]Entry) Entry {
	e := Entry{MediaType: firstOf(l.MediaType, preferred(mediaType)), IMDbID: l.IMDbID, Title: l.Title, Year: int64(l.Year)}
	configured := im.tmdb != nil && im.tmdb.Configured()
	switch {
	case l.TMDBID > 0:
		e.TMDBID = l.TMDBID
		if have, ok := local[fmt.Sprintf("%s/%d", e.MediaType, l.TMDBID)]; ok {
			return have
		}
		if configured {
			it, err := im.tmdb.Summary(ctx, e.MediaType, int(l.TMDBID))
			switch {
			case err == nil:
				e.Title, e.Year, e.PosterPath = it.Title, int64(tmdb.Year(it.Date)), it.PosterPath
			case errors.Is(err, tmdb.ErrNotFound):
				e.TMDBID = 0
			}
		}
		if e.Title == "" {
			e.Title = fmt.Sprintf("TMDB %s %d", map[string]string{"movie": "movie", "tv": "show"}[e.MediaType], l.TMDBID)
		}
	case l.IMDbID != "":
		if have, ok := local[l.IMDbID]; ok {
			return have
		}
		if it, ok, _ := im.find.find(ctx, "imdb_id", l.IMDbID, firstOf(l.MediaType, mediaType)); ok {
			e.MediaType, e.TMDBID, e.Title, e.Year, e.PosterPath = it.MediaType, int64(it.ID), it.Title, int64(tmdb.Year(it.Date)), it.PosterPath
		}
		if e.Title == "" {
			e.Title = l.IMDbID
		}
	case l.Title != "" && configured:
		if it, ok := im.search(ctx, l, firstOf(l.MediaType, mediaType)); ok {
			e.MediaType, e.TMDBID, e.Title, e.Year, e.PosterPath = it.MediaType, int64(it.ID), it.Title, int64(tmdb.Year(it.Date)), it.PosterPath
		}
	}
	return e
}

// search finds a title by name and year on TMDB: movies first for a list
// of both, then shows. Of the results, the first whose year is the line's
// (or one off, as release years differ by country) wins, else the first
// when the line has no year.
func (im *Importer) search(ctx context.Context, l Line, mediaType string) (tmdb.ListItem, bool) {
	title := norm.NFC.String(l.Title)
	for _, kind := range mediaTypes(mediaType) {
		var found []tmdb.ListItem
		if kind == "movie" {
			res, err := im.tmdb.SearchMovies(ctx, title, l.Year)
			if err != nil {
				continue
			}
			if len(res) == 0 && l.Year > 0 {
				res, _ = im.tmdb.SearchMovies(ctx, title, 0)
			}
			for _, m := range res {
				found = append(found, tmdb.ListItem{MediaType: "movie", ID: m.ID, Title: m.Title, Date: m.ReleaseDate, PosterPath: m.PosterPath})
			}
		} else {
			res, err := im.tmdb.SearchTV(ctx, title, 0)
			if err != nil {
				continue
			}
			for _, s := range res {
				found = append(found, tmdb.ListItem{MediaType: "tv", ID: s.ID, Title: s.Name, Date: s.FirstAirDate, PosterPath: s.PosterPath})
			}
		}
		if it, ok := bestYear(found, l.Year); ok {
			return it, true
		}
	}
	return tmdb.ListItem{}, false
}

func bestYear(found []tmdb.ListItem, year int) (tmdb.ListItem, bool) {
	if len(found) == 0 {
		return tmdb.ListItem{}, false
	}
	if year == 0 {
		return found[0], true
	}
	for _, off := range []int{0, 1} {
		for _, it := range found {
			if y := tmdb.Year(it.Date); y == year-off || y == year+off {
				return it, true
			}
		}
	}
	return tmdb.ListItem{}, false
}

// Refresh looks found entries' TMDB details up again (title, year, poster),
// which TMDB's terms allow keeping six months. Entries TMDB no longer has,
// or that fail, keep what they had.
func (im *Importer) Refresh(ctx context.Context, entries []Entry) ([]Entry, error) {
	if im.tmdb == nil || !im.tmdb.Configured() {
		return nil, tmdb.ErrNoAPIKey
	}
	out := slices.Clone(entries)
	for i, e := range out {
		if !e.Found() {
			continue
		}
		it, err := im.tmdb.Summary(ctx, e.MediaType, int(e.TMDBID))
		if err != nil {
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			if errors.Is(err, tmdb.ErrUnauthorized) {
				return nil, err
			}
			continue
		}
		out[i].Title, out[i].Year, out[i].PosterPath = it.Title, int64(tmdb.Year(it.Date)), it.PosterPath
	}
	return out, nil
}

func preferred(mediaType string) string {
	if mediaType == "tv" {
		return "tv"
	}
	return "movie"
}

func deref[T any](p *T) T {
	var zero T
	if p == nil {
		return zero
	}
	return *p
}
