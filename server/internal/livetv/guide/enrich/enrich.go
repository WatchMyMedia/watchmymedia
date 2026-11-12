// Package enrich matches listings to TMDB and adds its pictures and
// descriptions where the feed's fall short (PLAN-LIVE-TV-GUIDE.md §4.2,
// G3). A wrong picture is worse than none, so a programme is matched only
// when every check agrees; when unsure, it keeps what the feed gave.
package enrich

import (
	"cmp"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"log/slog"
	"maps"
	"math"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf8"

	"golang.org/x/text/unicode/norm"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/metadata"
	"watchmymedia/server/internal/tmdb"
)

const (
	// MaxCalls bounds the TMDB requests one run makes. What it leaves
	// unmatched waits for the next run, about a day later.
	MaxCalls = 200
	// KeepFor is how long a match, or the lack of one, is kept per series.
	KeepFor = 30 * 24 * time.Hour
	// recheckAfter is how soon a season that lacked a listed episode, or a
	// recent episode's still, is asked for again: TMDB gains a daily
	// show's episodes around their air dates.
	recheckAfter = 24 * time.Hour
	// maxOverview is the longest TMDB overview used, the feed's own cap
	// (§5.2). A longer one is left out rather than cut mid-sentence.
	maxOverview = 4000

	SourceProvider = "provider"
	SourceTMDB     = "tmdb"

	KindTV    = "tv"
	KindMovie = "movie"

	// imageRoute serves TMDB images at the width the wide panel and the
	// TVs' hero ask for.
	imageRoute = "/api/images/w780"
)

// Options configure an Enricher.
type Options struct {
	Log *slog.Logger
	// On says whether the admin allows TMDB pictures and descriptions in
	// the listings (guide.Service.TMDB); nil is always.
	On func(context.Context) bool
	// Now is the clock; nil is time.Now.
	Now func() time.Time
}

// Enricher writes TMDB matches into the stored listings.
type Enricher struct {
	q    *db.Queries
	tmdb *tmdb.Client
	log  *slog.Logger
	on   func(context.Context) bool
	now  func() time.Time
	// mu runs one Enrich at a time.
	mu sync.Mutex
}

func New(q *db.Queries, client *tmdb.Client, opt Options) *Enricher {
	if opt.Log == nil {
		opt.Log = slog.Default()
	}
	if opt.On == nil {
		opt.On = func(context.Context) bool { return true }
	}
	if opt.Now == nil {
		opt.Now = time.Now
	}
	return &Enricher{q: q, tmdb: client, log: opt.Log, on: opt.On, now: opt.Now}
}

// Enrich matches the stored programmes to TMDB. It runs after every
// refresh, which replaces the programmes with the feed's, so it writes
// every match again each time from cached lookups. It does nothing without
// a TMDB key. With the admin's switch off it takes TMDB's pictures and
// descriptions back out, asking TMDB nothing. TMDB failing ends the run's
// lookups but not the run: only database and context errors are returned.
func (e *Enricher) Enrich(ctx context.Context) error {
	if !e.tmdb.Configured() {
		return nil
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if !e.on(ctx) {
		_, err := e.write(ctx, nil)
		return err
	}
	rows, err := e.q.ListEnrichProgrammes(ctx)
	if err != nil {
		return err
	}
	now := e.now()
	since := now.Add(-KeepFor).Unix()
	if err := e.q.ExpireGuideMatches(ctx, since); err != nil {
		return err
	}
	if err := e.q.ExpireGuideSeasons(ctx, since); err != nil {
		return err
	}
	cached, err := e.q.ListGuideMatches(ctx, since)
	if err != nil {
		return err
	}
	r := &run{e: e, now: now, calls: MaxCalls, matches: map[string]db.LiveTvGuideMatch{}, seasons: map[seasonKey][]episode{}}
	for _, m := range cached {
		r.matches[m.SeriesKey] = m
	}
	for _, s := range group(rows, now) {
		if err := r.series(ctx, s); err != nil {
			return err
		}
	}
	if r.failed != nil {
		e.log.Warn("live tv guide: TMDB lookups stopped for this run", "err", r.failed)
	}
	matched, err := e.write(ctx, r)
	if err != nil {
		return err
	}
	e.log.Info("live tv guide matched to TMDB", "programmes", len(rows), "matched", matched, "tmdb_calls", MaxCalls-r.calls)
	return nil
}

// series is one series key's programmes.
type series struct {
	key, kind string
	rows      []db.ListEnrichProgrammesRow
	// next is when its next programme starts, or MaxInt64 when all have
	// ended; only series with one still to come are worth TMDB's calls.
	next int64
}

func (s *series) upcoming() bool { return s.next != math.MaxInt64 }

// group sorts the programmes TMDB may be asked about by series, soonest
// first, so a run that runs out of calls has matched what is on next.
func group(rows []db.ListEnrichProgrammesRow, now time.Time) []*series {
	skip := skipped(rows)
	by := map[string]*series{}
	for _, p := range rows {
		if p.SeriesKey == "" || skip[p.SeriesKey] || strings.TrimSpace(p.Title) == "" {
			continue
		}
		s := by[p.SeriesKey]
		if s == nil {
			s = &series{key: p.SeriesKey, kind: p.Kind, next: math.MaxInt64}
			by[p.SeriesKey] = s
		}
		s.rows = append(s.rows, p)
		if p.EndAt > now.Unix() {
			s.next = min(s.next, p.StartAt)
		}
	}
	return slices.SortedFunc(maps.Values(by), func(a, b *series) int {
		return cmp.Or(cmp.Compare(a.next, b.next), strings.Compare(a.key, b.key))
	})
}

// skipped are the series TMDB is never asked about: any of whose
// programmes is in a category in excludedCategories or sportWords.
func skipped(rows []db.ListEnrichProgrammesRow) map[string]bool {
	out := map[string]bool{}
	for _, p := range rows {
		if excluded(p.Categories) {
			out[p.SeriesKey] = true
		}
	}
	return out
}

// Categories TMDB is never asked about (§4.2, §7.1 item 8): news, weather,
// shopping and sport, which TMDB can't match to an episode. Sport is any
// category with one of sportWords in it.
var (
	excludedCategories = map[string]bool{
		"news": true, "weather": true, "shop": true, "shopping": true, "consumer": true, "paid programming": true,
		"mixed martial arts": true,
	}
	sportWords = map[string]bool{
		"sport": true, "sports": true, "motorsports": true, "football": true, "soccer": true, "basketball": true,
		"baseball": true, "hockey": true, "golf": true, "tennis": true, "boxing": true, "wrestling": true,
		"racing": true, "olympics": true, "rugby": true, "cricket": true, "volleyball": true, "softball": true,
		"lacrosse": true, "bowling": true, "cycling": true, "skiing": true, "skating": true, "gymnastics": true,
		"rodeo": true, "swimming": true, "surfing": true, "snowboarding": true, "curling": true, "billiards": true,
		"darts": true, "polo": true, "mma": true,
	}
)

func excluded(categories string) bool {
	var names []string
	if json.Unmarshal([]byte(categories), &names) != nil {
		return true // unreadable, so unsure
	}
	for _, c := range names {
		c = strings.ToLower(strings.TrimSpace(c))
		if excludedCategories[c] {
			return true
		}
		for _, w := range strings.FieldsFunc(c, func(r rune) bool { return !unicode.IsLetter(r) }) {
			if sportWords[w] {
				return true
			}
		}
	}
	return false
}

// run is one Enrich's lookups.
type run struct {
	e   *Enricher
	now time.Time
	// calls is how many TMDB requests are left; failed is why lookups
	// stopped early.
	calls   int
	failed  error
	matches map[string]db.LiveTvGuideMatch
	seasons map[seasonKey][]episode
}

type seasonKey struct{ id, season int64 }

// episode is what a cached season keeps of each of its episodes.
type episode struct {
	Number   int    `json:"e"`
	Name     string `json:"name,omitempty"`
	AirDate  string `json:"air,omitempty"`
	Overview string `json:"overview,omitempty"`
	Still    string `json:"still,omitempty"`
}

// call takes one of the run's TMDB requests, or says there are none left.
func (r *run) call() bool {
	if r.calls <= 0 || r.failed != nil {
		return false
	}
	r.calls--
	return true
}

// fail ends the run's lookups after a TMDB error. A context error ends the
// run itself.
func (r *run) fail(ctx context.Context, err error) error {
	if ctx.Err() != nil {
		return ctx.Err()
	}
	r.failed = err
	return nil
}

// series looks up a series' match, unless it is cached, and loads the
// seasons its listed episodes are in.
func (r *run) series(ctx context.Context, s *series) error {
	m, ok := r.matches[s.key]
	if !ok {
		if !s.upcoming() || !r.call() {
			return nil
		}
		var err error
		m, err = r.lookup(ctx, s)
		if err != nil {
			return r.fail(ctx, err)
		}
		m.SeriesKey, m.CheckedAt = s.key, r.now.Unix()
		if err := r.e.q.PutGuideMatch(ctx, db.PutGuideMatchParams(m)); err != nil {
			return err
		}
		r.matches[s.key] = m
	}
	if m.TmdbKind != KindTV || m.TmdbID == nil {
		return nil
	}
	need := map[int64][]int{}
	for _, p := range s.rows {
		if p.Season != nil && p.Episode != nil {
			need[*p.Season] = append(need[*p.Season], int(*p.Episode))
		}
	}
	for _, season := range slices.Sorted(maps.Keys(need)) {
		if err := r.season(ctx, *m.TmdbID, season, need[season], s.upcoming()); err != nil {
			return err
		}
	}
	return nil
}

// lookup asks TMDB for the one show or movie the series is (§4.2). Its
// result has no TMDB kind when there isn't exactly one.
func (r *run) lookup(ctx context.Context, s *series) (db.LiveTvGuideMatch, error) {
	none := db.LiveTvGuideMatch{}
	title := metadata.Normalize(s.rows[0].Title)
	for _, p := range s.rows {
		if metadata.Normalize(p.Title) != title {
			return none, nil // the feed names the series two ways
		}
	}
	query := norm.NFC.String(strings.TrimSpace(s.rows[0].Title))
	switch s.kind {
	case "movie":
		year := yearOf(s.rows[0].AirDate)
		if year == 0 {
			return none, nil
		}
		results, err := r.e.tmdb.SearchMovies(ctx, query, year)
		if err != nil {
			return none, err
		}
		var found []tmdb.MovieResult
		for _, m := range results {
			named := metadata.Normalize(m.Title) == title || metadata.Normalize(m.OriginalTitle) == title
			if named && tmdb.Year(m.ReleaseDate) == year {
				found = append(found, m)
			}
		}
		if len(found) != 1 {
			return none, nil
		}
		id := int64(found[0].ID)
		return db.LiveTvGuideMatch{TmdbKind: KindMovie, TmdbID: &id, Backdrop: found[0].BackdropPath, Overview: found[0].Overview}, nil
	case "episode":
		// The earliest original air date: the show began no later.
		aired := 0
		for _, p := range s.rows {
			if y := yearOf(p.AirDate); y > 0 && (aired == 0 || y < aired) {
				aired = y
			}
		}
		results, err := r.e.tmdb.SearchTV(ctx, query, 0)
		if err != nil {
			return none, err
		}
		var found []tmdb.TVResult
		for _, t := range results {
			if metadata.Normalize(t.Name) != title && metadata.Normalize(t.OriginalName) != title {
				continue
			}
			if began := tmdb.Year(t.FirstAirDate); aired > 0 && (began == 0 || began > aired) {
				continue
			}
			found = append(found, t)
		}
		if len(found) != 1 {
			return none, nil
		}
		id := int64(found[0].ID)
		return db.LiveTvGuideMatch{TmdbKind: KindTV, TmdbID: &id, Backdrop: found[0].BackdropPath}, nil
	}
	return none, nil
}

// season loads a season's episodes from the cache, or from TMDB when the
// cache is older than KeepFor, or older than a day and missing an episode
// the listings need or a recent one's still. ask says the series has a
// programme to come, so TMDB may be asked.
func (r *run) season(ctx context.Context, id, season int64, need []int, ask bool) error {
	key := seasonKey{id, season}
	row, err := r.e.q.GetGuideSeason(ctx, db.GetGuideSeasonParams{TmdbID: id, Season: season})
	switch {
	case err == nil:
		var eps []episode
		if json.Unmarshal([]byte(row.Episodes), &eps) == nil {
			r.seasons[key] = eps
			if r.now.Sub(time.Unix(row.CheckedAt, 0)) < recheckAfter || complete(eps, need, r.now) {
				return nil
			}
		}
	case !errors.Is(err, sql.ErrNoRows):
		return err
	}
	if !ask || !r.call() {
		return nil
	}
	s, err := r.e.tmdb.GetSeason(ctx, int(id), int(season))
	eps := []episode{}
	switch {
	case errors.Is(err, tmdb.ErrNotFound):
	case err != nil:
		return r.fail(ctx, err)
	default:
		for _, e := range s.Episodes {
			eps = append(eps, episode{Number: e.EpisodeNumber, Name: e.Name, AirDate: e.AirDate, Overview: e.Overview, Still: e.StillPath})
		}
	}
	b, err := json.Marshal(eps)
	if err != nil {
		return err
	}
	if err := r.e.q.PutGuideSeason(ctx, db.PutGuideSeasonParams{TmdbID: id, Season: season, Episodes: string(b), CheckedAt: r.now.Unix()}); err != nil {
		return err
	}
	r.seasons[key] = eps
	return nil
}

// complete says whether a cached season has every episode the listings
// need, each with a still unless it aired over KeepFor ago.
func complete(eps []episode, need []int, now time.Time) bool {
	for _, n := range need {
		i := slices.IndexFunc(eps, func(e episode) bool { return e.Number == n })
		if i < 0 {
			return false
		}
		if eps[i].Still == "" {
			aired, ok := parseDate(eps[i].AirDate)
			if !ok || now.Sub(aired) < KeepFor {
				return false
			}
		}
	}
	return true
}

// episodeFor is the season's episode a programme is, if TMDB has it and
// its name or air date agrees (§4.2). A date agrees within a day, as the
// feed and TMDB may date a late-night episode a day apart, but only when
// no other episode is as close: a daily show has one every day, and an
// episode numbered one off would be a day off too.
func episodeFor(eps []episode, p db.ListEnrichProgrammesRow) (episode, bool) {
	i := slices.IndexFunc(eps, func(e episode) bool { return e.Number == int(*p.Episode) })
	if i < 0 {
		return episode{}, false
	}
	e := eps[i]
	if name := metadata.Normalize(p.EpisodeTitle); name != "" && metadata.Normalize(e.Name) == name {
		return e, true
	}
	listed, ok := parseDate(p.AirDate)
	if !ok {
		return episode{}, false
	}
	aired, ok := parseDate(e.AirDate)
	if !ok {
		return episode{}, false
	}
	gap := daysApart(aired, listed)
	if gap > 1 {
		return episode{}, false
	}
	for j, o := range eps {
		if d, ok := parseDate(o.AirDate); ok && j != i && daysApart(d, listed) <= gap {
			return episode{}, false
		}
	}
	return e, true
}

func daysApart(a, b time.Time) int {
	d := int(a.Sub(b) / (24 * time.Hour))
	if d < 0 {
		return -d
	}
	return d
}

func parseDate(s string) (time.Time, bool) {
	t, err := time.Parse(time.DateOnly, s)
	return t, err == nil
}

// yearOf reads the year of a YYYY-MM-DD date or a YYYY year, or 0.
func yearOf(s string) int {
	if len(s) != 4 && len(s) != len(time.DateOnly) {
		return 0
	}
	return tmdb.Year(s)
}

// write sets each programme's TMDB fields from the run's matches, or takes
// them out when r is nil, and says how many have a match. It reads the
// programmes again in its transaction, so a refresh during the lookups
// can't be undone. Only programmes that change are written.
func (e *Enricher) write(ctx context.Context, r *run) (int, error) {
	matched := 0
	err := e.q.Transaction(ctx, func(q *db.Queries) error {
		rows, err := q.ListEnrichProgrammes(ctx)
		if err != nil {
			return err
		}
		repeated := repeatedDescriptions(rows)
		skip := skipped(rows)
		for _, p := range rows {
			want := feed(p)
			if r != nil && !skip[p.SeriesKey] {
				r.apply(&want, p, repeated[p.ID])
			}
			if want.TmdbID != nil {
				matched++
			}
			if same(want, current(p)) {
				continue
			}
			if err := q.SetProgrammeEnrichment(ctx, want); err != nil {
				return err
			}
		}
		return nil
	})
	return matched, err
}

func current(p db.ListEnrichProgrammesRow) db.SetProgrammeEnrichmentParams {
	return db.SetProgrammeEnrichmentParams{
		Description: p.Description, DescriptionSource: p.DescriptionSource, FeedDescription: p.FeedDescription,
		Picture: p.Picture, PictureSource: p.PictureSource, Still: p.Still, TmdbKind: p.TmdbKind, TmdbID: p.TmdbID, ID: p.ID,
	}
}

func same(a, b db.SetProgrammeEnrichmentParams) bool {
	idA, idB := a.TmdbID, b.TmdbID
	a.TmdbID, b.TmdbID = nil, nil
	return a == b && (idA == nil) == (idB == nil) && (idA == nil || *idA == *idB)
}

// feed is the programme as the feed gave it, without anything from TMDB.
func feed(p db.ListEnrichProgrammesRow) db.SetProgrammeEnrichmentParams {
	f := db.SetProgrammeEnrichmentParams{
		Description: p.Description, DescriptionSource: p.DescriptionSource, Picture: p.Picture, PictureSource: p.PictureSource, ID: p.ID,
	}
	if p.DescriptionSource == SourceTMDB {
		f.Description, f.DescriptionSource = p.FeedDescription, ""
		if f.Description != "" {
			f.DescriptionSource = SourceProvider
		}
	}
	if p.PictureSource == SourceTMDB {
		f.Picture, f.PictureSource = "", ""
	}
	return f
}

// apply adds the programme's TMDB match to want, the programme as the feed
// gave it: the TMDB link, an episode still or movie backdrop, a picture
// where the feed has none, and an overview where the feed's description is
// empty or repeated across the series' episodes.
func (r *run) apply(want *db.SetProgrammeEnrichmentParams, p db.ListEnrichProgrammesRow, repeated bool) {
	m, ok := r.matches[p.SeriesKey]
	if !ok || m.TmdbID == nil {
		return
	}
	var still, overview string
	switch {
	case p.Kind == "movie" && m.TmdbKind == KindMovie:
		still, overview = m.Backdrop, m.Overview
		repeated = false
	case p.Kind == "episode" && m.TmdbKind == KindTV:
		if p.Season != nil && p.Episode != nil {
			if e, ok := episodeFor(r.seasons[seasonKey{*m.TmdbID, *p.Season}], p); ok {
				still, overview = e.Still, e.Overview
			}
		}
	default:
		return
	}
	want.TmdbKind, want.TmdbID = m.TmdbKind, m.TmdbID
	want.Still = imagePath(still)
	if want.Picture == "" {
		if pic := cmp.Or(want.Still, imagePath(m.Backdrop)); pic != "" {
			want.Picture, want.PictureSource = pic, SourceTMDB
		}
	}
	overview = strings.TrimSpace(overview)
	if overview != "" && utf8.RuneCountInString(overview) <= maxOverview && (want.Description == "" || repeated) {
		want.FeedDescription = want.Description
		want.Description, want.DescriptionSource = overview, SourceTMDB
	}
}

// tmdbFileRe is a TMDB image path the image route serves (images.fileRe).
var tmdbFileRe = regexp.MustCompile(`^/[A-Za-z0-9_-]{1,64}\.(jpg|jpeg|png|webp)$`)

// imagePath is where this server serves a TMDB image path, or "".
func imagePath(p string) string {
	if !tmdbFileRe.MatchString(p) {
		return ""
	}
	return imageRoute + p
}

// repeatedDescriptions finds the episodes whose feed description another
// episode of the series also has, which describes the series rather than
// them. Airings of one episode don't count.
func repeatedDescriptions(rows []db.ListEnrichProgrammesRow) map[string]bool {
	type text struct{ series, desc string }
	episodes := map[text]map[string]bool{}
	for _, p := range rows {
		if d := feed(p).Description; p.Kind == "episode" && d != "" {
			t := text{p.SeriesKey, d}
			if episodes[t] == nil {
				episodes[t] = map[string]bool{}
			}
			episodes[t][identity(p)] = true
		}
	}
	out := map[string]bool{}
	for _, p := range rows {
		if d := feed(p).Description; p.Kind == "episode" && d != "" && len(episodes[text{p.SeriesKey, d}]) > 1 {
			out[p.ID] = true
		}
	}
	return out
}

// identity tells a programme's episode from the series' others.
func identity(p db.ListEnrichProgrammesRow) string {
	switch {
	case p.Season != nil && p.Episode != nil:
		return "s" + strconv.FormatInt(*p.Season, 10) + "e" + strconv.FormatInt(*p.Episode, 10)
	case metadata.Normalize(p.EpisodeTitle) != "":
		return "t" + metadata.Normalize(p.EpisodeTitle)
	case p.AirDate != "":
		return "d" + p.AirDate
	}
	return "p" + p.ID
}
