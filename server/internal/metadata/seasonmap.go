package metadata

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"slices"
	"sort"
	"strings"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/library"
	"watchmymedia/server/internal/memo"
	"watchmymedia/server/internal/parser"
	"watchmymedia/server/internal/tmdb"
)

// Season line-ups (PLAN-SEASON-MAPPING.md): where a season starts when TMDB
// numbers it differently from the files in a show folder.

// SeasonRule says that files numbered from FromSeason and FromEpisode on
// are the TMDB show's ToSeason and ToEpisode on, in order. A number past
// the end of that season runs on into the show's next one.
type SeasonRule struct {
	FromSeason  int `json:"fromSeason" minimum:"1" doc:"The season as the files number it."`
	FromEpisode int `json:"fromEpisode" minimum:"1" doc:"The first episode number, as the files number it, that this covers."`
	TMDBID      int `json:"tmdbId" minimum:"1" doc:"The TMDB show the files belong to: the folder's show or another."`
	ToSeason    int `json:"toSeason" minimum:"1" doc:"The TMDB season the first file is in."`
	ToEpisode   int `json:"toEpisode" minimum:"1" doc:"The TMDB episode the first file is."`
}

// seasonCounts is how many episodes each of a show's seasons has, without
// the specials.
func seasonCounts(seasons []tmdb.SeasonSummary) map[int]int {
	counts := make(map[int]int, len(seasons))
	for _, s := range seasons {
		if s.SeasonNumber > 0 {
			counts[s.SeasonNumber] = s.EpisodeCount
		}
	}
	return counts
}

// place returns the TMDB season and episode a file's episode number lands
// on under the rule, or false when it comes before the rule's first
// episode or past the end of the show's last season.
func (r SeasonRule) place(counts map[int]int, episode int) (season, number int, ok bool) {
	if episode < r.FromEpisode {
		return 0, 0, false
	}
	season, number = r.ToSeason, r.ToEpisode+episode-r.FromEpisode
	for {
		n, has := counts[season]
		if !has {
			return 0, 0, false
		}
		if number <= n {
			return season, number, true
		}
		number -= n
		season++
	}
}

// placeAll places every episode of a file; a file whose episodes would land
// in two seasons has no place.
func (r SeasonRule) placeAll(counts map[int]int, episodes []int) (season int, numbers []int, ok bool) {
	for i, e := range episodes {
		s, n, ok := r.place(counts, e)
		if !ok || (i > 0 && s != season) {
			return 0, nil, false
		}
		season = s
		numbers = append(numbers, n)
	}
	return season, numbers, len(numbers) > 0
}

// numbered reports whether a file is named by a season and episodes a
// line-up can cover: not the specials, a year season or an air date.
func numbered(g parser.Guess) bool {
	if g.AirDate != "" || g.Season == nil || *g.Season < 1 || *g.Season >= 1900 || len(g.Episodes) == 0 {
		return false
	}
	return slices.Min(g.Episodes) >= 1
}

func guessOf(f db.MediaFile) parser.Guess {
	var g parser.Guess
	_ = json.Unmarshal([]byte(f.Parsed), &g)
	return g
}

// seasonRule returns the line-up a person stored for the file's folder
// that covers the file's season and first episode.
func (m *Matcher) seasonRule(ctx context.Context, lib db.Library, f db.MediaFile, g parser.Guess) (SeasonRule, bool) {
	if !numbered(g) {
		return SeasonRule{}, false
	}
	folder := Folder(lib, f)
	if folder == "" {
		return SeasonRule{}, false
	}
	row, err := m.q.GetSeasonMap(ctx, db.GetSeasonMapParams{LibraryID: lib.ID, Folder: folder, Season: int64(*g.Season), Episode: int64(slices.Min(g.Episodes))})
	if err != nil {
		if !errors.Is(err, sql.ErrNoRows) {
			m.log.Warn("read season line-up", "folder", folder, "err", err)
		}
		return SeasonRule{}, false
	}
	return ruleFromRow(row), true
}

func ruleFromRow(r db.SeasonMap) SeasonRule {
	return SeasonRule{FromSeason: int(r.FromSeason), FromEpisode: int(r.FromEpisode), TMDBID: int(r.TmdbID), ToSeason: int(r.ToSeason), ToEpisode: int(r.ToEpisode)}
}

// linkByRule links a numbered file to the episodes a rule places it on.
func (m *Matcher) linkByRule(ctx context.Context, lib db.Library, rule SeasonRule, g parser.Guess) ([]int64, error) {
	tv, err := m.getTV(ctx, rule.TMDBID)
	if errors.Is(err, tmdb.ErrNotFound) {
		return nil, noMatch("the show this season was lined up with is no longer on TMDB")
	}
	if err != nil {
		return nil, err
	}
	season, numbers, ok := rule.placeAll(seasonCounts(tv.Seasons), g.Episodes)
	if !ok {
		return nil, noMatch("season %d, episode %v is past the end of %q on TMDB, where this season was lined up to start at season %d, episode %d",
			*g.Season, g.Episodes, tv.Name, rule.ToSeason, rule.ToEpisode)
	}
	return m.linkEpisodes(ctx, lib, rule.TMDBID, season, numbers)
}

// seasonList returns a season's episodes from TMDB, reused for ten minutes.
func (m *Matcher) seasonList(ctx context.Context, tmdbID, season int) (*tmdb.Season, error) {
	return memo.Load(ctx, m.seasonLists, &m.flight, fmt.Sprintf("%d/%d", tmdbID, season), func(ctx context.Context) (*tmdb.Season, error) {
		return m.tmdb.GetSeason(ctx, tmdbID, season)
	})
}

// maxOrders bounds how many of a show's episode orders are read.
const maxOrders = 6

// episodeOrders returns a show's other episode orders, reused for ten
// minutes. Most shows have none.
func (m *Matcher) episodeOrders(ctx context.Context, tmdbID int) ([]*tmdb.EpisodeGroup, error) {
	return memo.Load(ctx, m.orders, &m.flight, tmdbID, func(ctx context.Context) ([]*tmdb.EpisodeGroup, error) {
		list, err := m.tmdb.GetEpisodeGroups(ctx, tmdbID)
		if errors.Is(err, tmdb.ErrNotFound) {
			return nil, nil
		}
		if err != nil {
			return nil, err
		}
		var out []*tmdb.EpisodeGroup
		for _, s := range list {
			if len(out) == maxOrders {
				break
			}
			g, err := m.tmdb.GetEpisodeGroup(ctx, s.ID)
			if errors.Is(err, tmdb.ErrNotFound) {
				continue
			}
			if err != nil {
				return nil, err
			}
			for i := range g.Groups {
				eps := g.Groups[i].Episodes
				sort.SliceStable(eps, func(a, b int) bool { return eps[a].Order < eps[b].Order })
			}
			out = append(out, g)
		}
		return out, nil
	})
}

// Where a suggestion came from.
const (
	SourceOrder     = "order"     // one of TMDB's other episode orders numbers the show as the files do
	SourceRunOn     = "runOn"     // the numbers run past the season's end into the next
	SourceNames     = "names"     // the episode names in the files are another season's
	SourceOwnShow   = "ownShow"   // TMDB lists the season as a show of its own
	SourceNeighbour = "neighbour" // follows a season of the folder a person lined up
)

// ShowRef names a TMDB show a season can be lined up with.
type ShowRef struct {
	TMDBID     int    `json:"tmdbId"`
	Title      string `json:"title"`
	Year       int    `json:"year,omitempty"`
	PosterPath string `json:"posterPath,omitempty"`
	Seasons    int    `json:"seasons" doc:"How many seasons the show has on TMDB, without the specials."`
}

func showRef(tv *tmdb.TVShow) ShowRef {
	return ShowRef{TMDBID: tv.ID, Title: tv.Name, Year: tmdb.Year(tv.FirstAirDate), PosterPath: tv.PosterPath, Seasons: len(seasonCounts(tv.Seasons))}
}

// SeasonSuggestion is a line-up the server found for a season.
type SeasonSuggestion struct {
	Rule        SeasonRule `json:"rule"`
	Show        ShowRef    `json:"show"`
	LastSeason  int        `json:"lastSeason" doc:"The TMDB season the last file lands in."`
	LastEpisode int        `json:"lastEpisode" doc:"The TMDB episode the last file lands on."`
	Found       int        `json:"found" doc:"Files that land on an episode."`
	Source      string     `json:"source" enum:"order,runOn,names,ownShow,neighbour"`
	Detail      string     `json:"detail" doc:"Why, in plain words."`
}

// SeasonLineUp is one season of a group, as its files number it, that does
// not line up with the folder's show on TMDB.
type SeasonLineUp struct {
	Season      int               `json:"season"`
	FromEpisode int               `json:"fromEpisode" doc:"The first episode number, as the files number it, that does not line up."`
	LastEpisode int               `json:"lastEpisode"`
	FileIDs     []int64           `json:"fileIds" nullable:"false" doc:"The files a line-up for this season would cover."`
	OnTMDB      int               `json:"onTmdb" doc:"Episodes the folder's show has for this season on TMDB; 0 when it has no such season."`
	Suggestion  *SeasonSuggestion `json:"suggestion,omitempty"`
}

// seasonFile is a numbered file of a season being lined up.
type seasonFile struct {
	id       int64
	episodes []int
	title    string
}

// SuggestSeasons finds, among files of a show folder, the seasons whose
// numbers the folder's show does not have on TMDB, and a line-up for each
// when one can be found. It saves nothing.
func (m *Matcher) SuggestSeasons(ctx context.Context, lib db.Library, files []db.MediaFile, tmdbID int) ([]SeasonLineUp, error) {
	out := []SeasonLineUp{}
	if lib.Type != library.TypeTV {
		return out, nil
	}
	base, err := m.getTV(ctx, tmdbID)
	if err != nil {
		return nil, err
	}
	counts := seasonCounts(base.Seasons)
	bySeason := map[int][]seasonFile{}
	for _, f := range files {
		g := guessOf(f)
		if !numbered(g) {
			continue
		}
		// Only what the show does not have needs lining up.
		if n, ok := counts[*g.Season]; ok && slices.Max(g.Episodes) <= n {
			continue
		}
		bySeason[*g.Season] = append(bySeason[*g.Season], seasonFile{id: f.ID, episodes: g.Episodes, title: g.EpisodeTitle})
	}
	folder := sharedFolder(lib, files)
	for season, fs := range bySeason {
		sort.SliceStable(fs, func(i, j int) bool { return fs[i].episodes[0] < fs[j].episodes[0] })
		lu := SeasonLineUp{Season: season, FromEpisode: slices.Min(fs[0].episodes), OnTMDB: counts[season], FileIDs: []int64{}}
		for _, f := range fs {
			lu.FileIDs = append(lu.FileIDs, f.id)
			lu.LastEpisode = max(lu.LastEpisode, slices.Max(f.episodes))
		}
		s, err := m.suggestSeason(ctx, lib, folder, base, lu, fs)
		if err != nil {
			return nil, err
		}
		lu.Suggestion = s
		out = append(out, lu)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Season < out[j].Season })
	return out, nil
}

// suggestSeason tries each source in turn; the first whose line-up places
// every file wins.
func (m *Matcher) suggestSeason(ctx context.Context, lib db.Library, folder string, base *tmdb.TVShow, lu SeasonLineUp, fs []seasonFile) (*SeasonSuggestion, error) {
	sources := []func() (*SeasonSuggestion, error){
		func() (*SeasonSuggestion, error) { return m.fromOrder(ctx, base, lu, fs) },
		func() (*SeasonSuggestion, error) { return m.fromRunOn(base, lu, fs), nil },
		func() (*SeasonSuggestion, error) { return m.fromNames(ctx, base, lu, fs) },
		func() (*SeasonSuggestion, error) { return m.fromOwnShow(ctx, base, lu, fs) },
		func() (*SeasonSuggestion, error) { return m.fromNeighbour(ctx, lib, folder, lu, fs) },
	}
	for _, source := range sources {
		s, err := source()
		if err != nil || s != nil {
			return s, err
		}
	}
	return nil, nil
}

// suggestion builds a suggestion when the rule places every file, with the
// rule's target written as the season and episode the first file lands on.
func suggestion(tv *tmdb.TVShow, rule SeasonRule, fs []seasonFile, source, detail string) *SeasonSuggestion {
	counts := seasonCounts(tv.Seasons)
	first, firstEp, ok := rule.place(counts, rule.FromEpisode)
	if !ok {
		return nil
	}
	rule.ToSeason, rule.ToEpisode = first, firstEp
	s := &SeasonSuggestion{Rule: rule, Show: showRef(tv), Source: source, Detail: detail}
	for _, f := range fs {
		season, numbers, ok := rule.placeAll(counts, f.episodes)
		if !ok {
			return nil
		}
		s.Found++
		s.LastSeason, s.LastEpisode = season, slices.Max(numbers)
	}
	return s
}

func plural(n int, one, many string) string {
	if n == 1 {
		return one
	}
	return many
}

// fromOrder looks for one of TMDB's other orders of the show whose part
// numbered like the files' season holds their episodes.
func (m *Matcher) fromOrder(ctx context.Context, base *tmdb.TVShow, lu SeasonLineUp, fs []seasonFile) (*SeasonSuggestion, error) {
	orders, err := m.episodeOrders(ctx, base.ID)
	if err != nil {
		return nil, err
	}
	for _, o := range orders {
		for _, part := range o.Groups {
			if part.Order != lu.Season || lu.LastEpisode > len(part.Episodes) {
				continue
			}
			first := part.Episodes[lu.FromEpisode-1]
			if first.SeasonNumber < 1 {
				continue
			}
			rule := SeasonRule{FromSeason: lu.Season, FromEpisode: lu.FromEpisode, TMDBID: base.ID, ToSeason: first.SeasonNumber, ToEpisode: first.EpisodeNumber}
			where := fmt.Sprintf("is season %d", first.SeasonNumber)
			if first.EpisodeNumber != lu.FromEpisode {
				where = fmt.Sprintf("starts at season %d, episode %d", first.SeasonNumber, first.EpisodeNumber)
			}
			s := suggestion(base, rule, fs, SourceOrder, fmt.Sprintf("TMDB also lists the show in %d parts (%q), as your files are numbered. Part %d %s.", len(o.Groups), o.Name, lu.Season, where))
			if s == nil || !orderAgrees(rule, seasonCounts(base.Seasons), part, fs) {
				continue
			}
			return s, nil
		}
	}
	return nil, nil
}

// orderAgrees checks that the rule, which counts on in order, puts every
// file where the order's part does.
func orderAgrees(rule SeasonRule, counts map[int]int, part tmdb.EpisodeGroupPart, fs []seasonFile) bool {
	for _, f := range fs {
		for _, e := range f.episodes {
			s, n, ok := rule.place(counts, e)
			at := part.Episodes[e-1]
			if !ok || s != at.SeasonNumber || n != at.EpisodeNumber {
				return false
			}
		}
	}
	return true
}

// fromRunOn covers numbers past the end of a season the show has: they
// continue into the next.
func (m *Matcher) fromRunOn(base *tmdb.TVShow, lu SeasonLineUp, fs []seasonFile) *SeasonSuggestion {
	if lu.OnTMDB == 0 || lu.FromEpisode <= lu.OnTMDB {
		return nil
	}
	rule := SeasonRule{FromSeason: lu.Season, FromEpisode: lu.FromEpisode, TMDBID: base.ID, ToSeason: lu.Season, ToEpisode: lu.FromEpisode}
	s := suggestion(base, rule, fs, SourceRunOn, "")
	if s == nil {
		return nil
	}
	s.Detail = fmt.Sprintf("Season %d has %d %s on TMDB, so these run on into season %d.", lu.Season, lu.OnTMDB, plural(lu.OnTMDB, "episode", "episodes"), s.Rule.ToSeason)
	return s
}

// maxNameSeasons bounds how many seasons' episode names are read to find
// where a season's names are.
const maxNameSeasons = 60

// namesAgree counts the files with an episode name whose name is the one
// TMDB has where the rule places them, and how many files have a name.
func (m *Matcher) namesAgree(ctx context.Context, tv *tmdb.TVShow, rule SeasonRule, fs []seasonFile) (agree, named int, err error) {
	counts := seasonCounts(tv.Seasons)
	for _, f := range fs {
		if f.title == "" {
			continue
		}
		named++
		season, number, ok := rule.place(counts, f.episodes[0])
		if !ok {
			continue
		}
		list, err := m.seasonList(ctx, tv.ID, season)
		if errors.Is(err, tmdb.ErrNotFound) {
			continue
		}
		if err != nil {
			return 0, 0, err
		}
		for _, e := range list.Episodes {
			if e.EpisodeNumber == number && sameName(e.Name, f.title) {
				agree++
			}
		}
	}
	return agree, named, nil
}

// sameName compares an episode name in a file with TMDB's. File names drop
// punctuation and often end in words the parser could not tell from the
// name, so TMDB's name may be the start of the file's.
func sameName(tmdbName, fileName string) bool {
	a, b := normalize(tmdbName), normalize(fileName)
	if a == "" || b == "" {
		return false
	}
	return a == b || strings.ReplaceAll(a, " ", "") == strings.ReplaceAll(b, " ", "") || (len(a) >= 8 && strings.HasPrefix(b, a+" "))
}

// fromNames finds the run of the show's episodes whose names are the ones
// in the files, whatever its season is numbered.
func (m *Matcher) fromNames(ctx context.Context, base *tmdb.TVShow, lu SeasonLineUp, fs []seasonFile) (*SeasonSuggestion, error) {
	anchor := -1
	named := 0
	for i, f := range fs {
		if f.title != "" {
			named++
			if anchor < 0 {
				anchor = i
			}
		}
	}
	// One named file among several is too little to go on.
	if anchor < 0 || (named < 2 && len(fs) > 1) || len(base.Seasons) > maxNameSeasons {
		return nil, nil
	}
	back := fs[anchor].episodes[0] - lu.FromEpisode
	for _, season := range base.Seasons {
		if season.SeasonNumber < 1 {
			continue
		}
		list, err := m.seasonList(ctx, base.ID, season.SeasonNumber)
		if errors.Is(err, tmdb.ErrNotFound) {
			continue
		}
		if err != nil {
			return nil, err
		}
		for _, e := range list.Episodes {
			if !sameName(e.Name, fs[anchor].title) || e.EpisodeNumber-back < 1 {
				continue
			}
			rule := SeasonRule{FromSeason: lu.Season, FromEpisode: lu.FromEpisode, TMDBID: base.ID, ToSeason: season.SeasonNumber, ToEpisode: e.EpisodeNumber - back}
			agree, _, err := m.namesAgree(ctx, base, rule, fs)
			if err != nil {
				return nil, err
			}
			if agree != named {
				continue
			}
			detail := fmt.Sprintf("The %d episode names in your files are season %d's on TMDB.", named, season.SeasonNumber)
			if named == 1 {
				detail = fmt.Sprintf("The episode name in your file is in season %d on TMDB.", season.SeasonNumber)
			}
			if s := suggestion(base, rule, fs, SourceNames, detail); s != nil {
				return s, nil
			}
		}
	}
	return nil, nil
}

// maxOwnShows bounds how many search results are read as a season's own show.
const maxOwnShows = 5

// fromOwnShow looks for a later show named after the folder's, whose first
// season holds the files: TMDB lists some seasons as shows of their own.
func (m *Matcher) fromOwnShow(ctx context.Context, base *tmdb.TVShow, lu SeasonLineUp, fs []seasonFile) (*SeasonSuggestion, error) {
	results, err := m.search(ctx, library.TypeTV, base.Name, nil)
	if err != nil {
		return nil, err
	}
	prefix := normalize(base.Name) + " "
	var guess *SeasonSuggestion
	tried := 0
	for _, r := range results {
		if r.TMDBID == base.ID || !strings.HasPrefix(normalize(r.Title), prefix) {
			continue
		}
		if tried++; tried > maxOwnShows {
			break
		}
		tv, err := m.getTV(ctx, r.TMDBID)
		if errors.Is(err, tmdb.ErrNotFound) {
			continue
		}
		if err != nil {
			return nil, err
		}
		if tv.FirstAirDate == "" || tv.FirstAirDate <= base.FirstAirDate {
			continue
		}
		rule := SeasonRule{FromSeason: lu.Season, FromEpisode: lu.FromEpisode, TMDBID: tv.ID, ToSeason: 1, ToEpisode: lu.FromEpisode}
		s := suggestion(tv, rule, fs, SourceOwnShow, "")
		if s == nil {
			continue
		}
		agree, named, err := m.namesAgree(ctx, tv, rule, fs)
		if err != nil {
			return nil, err
		}
		switch {
		case named > 0 && agree == named:
			s.Detail = fmt.Sprintf("TMDB lists this season as a show of its own. %s in your files %s.",
				plural(named, "The episode name", fmt.Sprintf("All %d episode names", named)), plural(named, "agrees", "agree"))
			return s, nil
		case named == 0 && guess == nil:
			n := seasonCounts(tv.Seasons)[1]
			s.Detail = fmt.Sprintf("TMDB lists a later show with this name and %d %s in its first season. Check the names before matching.", n, plural(n, "episode", "episodes"))
			guess = s
		}
	}
	return guess, nil
}

// fromNeighbour follows the nearest earlier season of the folder that a
// person lined up from its first episode: season 22 as season 19 makes
// season 23 season 20.
func (m *Matcher) fromNeighbour(ctx context.Context, lib db.Library, folder string, lu SeasonLineUp, fs []seasonFile) (*SeasonSuggestion, error) {
	if folder == "" {
		return nil, nil
	}
	rows, err := m.q.ListFolderSeasonMaps(ctx, db.ListFolderSeasonMapsParams{LibraryID: lib.ID, Folder: folder})
	if err != nil {
		return nil, err
	}
	var near *db.SeasonMap
	for i, r := range rows {
		if r.FromEpisode == 1 && r.ToEpisode == 1 && int(r.FromSeason) < lu.Season {
			near = &rows[i]
		}
	}
	if near == nil {
		return nil, nil
	}
	tv, err := m.getTV(ctx, int(near.TmdbID))
	if errors.Is(err, tmdb.ErrNotFound) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	to := lu.Season + int(near.ToSeason-near.FromSeason)
	if to < 1 {
		return nil, nil
	}
	rule := SeasonRule{FromSeason: lu.Season, FromEpisode: lu.FromEpisode, TMDBID: tv.ID, ToSeason: to, ToEpisode: lu.FromEpisode}
	// Only the season itself: a neighbour is no reason to run on.
	if lu.LastEpisode > seasonCounts(tv.Seasons)[to] {
		return nil, nil
	}
	return suggestion(tv, rule, fs, SourceNeighbour, fmt.Sprintf("Follows season %d, which was lined up as season %d.", near.FromSeason, near.ToSeason)), nil
}

// SeasonChoice is a season of the show a line-up can start in.
type SeasonChoice struct {
	Number       int    `json:"number"`
	Name         string `json:"name"`
	EpisodeCount int    `json:"episodeCount"`
	AirDate      string `json:"airDate,omitempty"`
}

// EpisodeChoice is an episode a line-up can start at, or a file lands on.
type EpisodeChoice struct {
	Season  int    `json:"season"`
	Number  int    `json:"number"`
	Title   string `json:"title"`
	AirDate string `json:"airDate,omitempty"`
}

// PreviewFile is a file and where a line-up would put it.
type PreviewFile struct {
	FileID   int64           `json:"fileId"`
	Season   *int            `json:"season,omitempty"`
	Episodes []int           `json:"episodes,omitempty"`
	To       []EpisodeChoice `json:"to" nullable:"false" doc:"The TMDB episodes the file would be linked to; empty when it has no place."`
	Reason   string          `json:"reason,omitempty" doc:"Why the file has no place."`
}

// SeasonPreview shows what a line-up would do, without saving anything.
type SeasonPreview struct {
	Show     ShowRef         `json:"show"`
	Seasons  []SeasonChoice  `json:"seasons" nullable:"false" doc:"The show's seasons on TMDB, without the specials."`
	Episodes []EpisodeChoice `json:"episodes" nullable:"false" doc:"The episodes of the rule's season, to choose the first from."`
	Files    []PreviewFile   `json:"files" nullable:"false"`
	Found    int             `json:"found" doc:"Files that land on an episode."`
}

// PreviewSeason places each file under a rule and names the episodes they
// land on. A rule whose season the show does not have still lists the
// show's seasons, so a person can choose one.
func (m *Matcher) PreviewSeason(ctx context.Context, lib db.Library, files []db.MediaFile, rule SeasonRule) (SeasonPreview, error) {
	out := SeasonPreview{Seasons: []SeasonChoice{}, Episodes: []EpisodeChoice{}, Files: []PreviewFile{}}
	if lib.Type != library.TypeTV {
		return out, fmt.Errorf("unsupported library type %q", lib.Type)
	}
	tv, err := m.getTV(ctx, rule.TMDBID)
	if err != nil {
		return out, err
	}
	out.Show = showRef(tv)
	counts := seasonCounts(tv.Seasons)
	for _, s := range tv.Seasons {
		if s.SeasonNumber > 0 {
			out.Seasons = append(out.Seasons, SeasonChoice{Number: s.SeasonNumber, Name: s.Name, EpisodeCount: s.EpisodeCount, AirDate: s.AirDate})
		}
	}
	sort.Slice(out.Seasons, func(i, j int) bool { return out.Seasons[i].Number < out.Seasons[j].Number })
	episode := func(season, number int) (EpisodeChoice, error) {
		c := EpisodeChoice{Season: season, Number: number}
		list, err := m.seasonList(ctx, tv.ID, season)
		if errors.Is(err, tmdb.ErrNotFound) {
			return c, nil
		}
		if err != nil {
			return c, err
		}
		for _, e := range list.Episodes {
			if e.EpisodeNumber == number {
				c.Title, c.AirDate = e.Name, e.AirDate
			}
		}
		return c, nil
	}
	if _, ok := counts[rule.ToSeason]; ok {
		list, err := m.seasonList(ctx, tv.ID, rule.ToSeason)
		if err != nil && !errors.Is(err, tmdb.ErrNotFound) {
			return out, err
		}
		if list != nil {
			for _, e := range list.Episodes {
				out.Episodes = append(out.Episodes, EpisodeChoice{Season: rule.ToSeason, Number: e.EpisodeNumber, Title: e.Name, AirDate: e.AirDate})
			}
		}
	}
	for _, f := range files {
		g := guessOf(f)
		pf := PreviewFile{FileID: f.ID, Season: g.Season, Episodes: g.Episodes, To: []EpisodeChoice{}}
		switch {
		case !numbered(g):
			pf.Reason = "The name has no season and episode number to line up."
		case *g.Season != rule.FromSeason || slices.Min(g.Episodes) < rule.FromEpisode:
			pf.Reason = "Not part of what is being lined up."
		default:
			season, numbers, ok := rule.placeAll(counts, g.Episodes)
			if !ok {
				pf.Reason = fmt.Sprintf("Past the end of %s on TMDB.", tv.Name)
				break
			}
			for _, n := range numbers {
				c, err := episode(season, n)
				if err != nil {
					return out, err
				}
				pf.To = append(pf.To, c)
			}
			out.Found++
		}
		out.Files = append(out.Files, pf)
	}
	return out, nil
}

// SeasonApply is a rule and the files to match by it.
type SeasonApply struct {
	Rule  SeasonRule
	Files []db.MediaFile
}

// ApplySeasons matches files by line-ups a person chose and locks what
// matched. A file a rule cannot place stays unmatched with the reason. With
// remember, each rule is stored for the folder its files share, so files
// added there later follow it.
func (m *Matcher) ApplySeasons(ctx context.Context, lib db.Library, lineUps []SeasonApply, remember bool) (GroupResult, error) {
	var res GroupResult
	if lib.Type != library.TypeTV {
		return res, fmt.Errorf("unsupported library type %q", lib.Type)
	}
	last := 0
	for _, lu := range lineUps {
		if _, err := m.getTV(ctx, lu.Rule.TMDBID); err != nil {
			return res, err
		}
		for _, f := range lu.Files {
			g := guessOf(f)
			if !numbered(g) || *g.Season != lu.Rule.FromSeason || slices.Min(g.Episodes) < lu.Rule.FromEpisode {
				res.NeedEpisodes++
				continue
			}
			ids, err := m.linkByRule(ctx, lib, lu.Rule, g)
			var nm *noMatchError
			switch {
			case err == nil:
				if err := m.record(ctx, f.ID, StatusMatched, nil, ids, nil, nil, true); err != nil {
					return res, err
				}
				res.Matched++
			case errors.As(err, &nm):
				if err := m.record(ctx, f.ID, StatusUnmatched, nil, nil, nil, new(err.Error()), false); err != nil {
					return res, err
				}
				res.NeedEpisodes++
			default:
				return res, err
			}
		}
		last = lu.Rule.TMDBID
		if folder := sharedFolder(lib, lu.Files); remember && folder != "" {
			r := lu.Rule
			if err := m.q.UpsertSeasonMap(ctx, db.UpsertSeasonMapParams{LibraryID: lib.ID, Folder: folder, FromSeason: int64(r.FromSeason), FromEpisode: int64(r.FromEpisode),
				TmdbID: int64(r.TMDBID), ToSeason: int64(r.ToSeason), ToEpisode: int64(r.ToEpisode)}); err != nil {
				return res, err
			}
			res.Folder = folder
		}
	}
	if show, err := m.q.GetShowByTMDB(ctx, db.GetShowByTMDBParams{LibraryID: lib.ID, TmdbID: int64(last)}); err == nil {
		res.ShowID = &show.ID
	}
	return res, nil
}
