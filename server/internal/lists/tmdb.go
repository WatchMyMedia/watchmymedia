package lists

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"

	"watchmymedia/server/internal/tmdb"
)

// TMDB makes lists from TMDB with the server's key: trending, the charts,
// discover, public lists and collections. Pages hold 20 titles, so 50
// entries take three requests.
type TMDB struct {
	Client *tmdb.Client
	// Now is today, for discover's newest-first sort; nil means time.Now.
	Now func() time.Time
}

func (*TMDB) Kind() string       { return KindTMDB }
func (*TMDB) Name(Params) string { return "TMDB" }

// chartPaths are the charts by TMDB's names for them, per media type.
var chartPaths = map[string]map[string]string{
	"movie": {ChartPopular: "popular", ChartTopRated: "top_rated", ChartNowPlaying: "now_playing", ChartUpcoming: "upcoming"},
	"tv":    {ChartPopular: "popular", ChartTopRated: "top_rated", ChartOnTheAir: "on_the_air", ChartAiringToday: "airing_today"},
}

// discoverSorts are discover's orders.
var discoverSorts = map[string]bool{"": true, "popularity": true, "rating": true, "votes": true, "newest": true}

var languageCode = regexp.MustCompile(`^[a-z]{2}$`)

func (t *TMDB) Validate(r Request) error {
	p := r.Params
	switch p.Chart {
	case ChartTrendingDay, ChartTrendingWeek:
	case ChartPopular, ChartTopRated:
		if r.MediaType == "all" {
			return invalid("Choose movies or TV shows for TMDB's %s chart", strings.ReplaceAll(p.Chart, "-", " "))
		}
	case ChartNowPlaying, ChartUpcoming:
		if r.MediaType != "movie" {
			return invalid("TMDB's %s chart is of movies", strings.ReplaceAll(p.Chart, "-", " "))
		}
	case ChartOnTheAir, ChartAiringToday:
		if r.MediaType != "tv" {
			return invalid("TMDB's %s chart is of TV shows", strings.ReplaceAll(p.Chart, "-", " "))
		}
	case ChartDiscover:
		if r.MediaType == "all" {
			return invalid("Choose movies or TV shows to discover")
		}
		if p.YearFrom > 0 && p.YearTo > 0 && p.YearFrom > p.YearTo {
			return invalid("The first year comes after the last")
		}
		if !discoverSorts[p.SortBy] {
			return invalid("Sort by popularity, rating, votes or newest")
		}
		if p.Language != "" && !languageCode.MatchString(p.Language) {
			return invalid("The language is a two-letter code, such as ko or fr")
		}
		if len(p.Genres) > 10 {
			return invalid("Choose at most 10 genres")
		}
	case ChartList:
		if _, err := tmdbRef(p.Ref, "list"); err != nil {
			return err
		}
	case ChartCollection:
		if r.MediaType == "tv" {
			return invalid("TMDB's collections are of movies")
		}
		if _, err := tmdbRef(p.Ref, "collection"); err != nil {
			return err
		}
	case "":
		return invalid("Choose a TMDB chart, discover, a list or a collection")
	default:
		return invalid("TMDB has no chart %q", p.Chart)
	}
	return nil
}

// tmdbRef reads a TMDB list's or collection's ID from its ID or its link
// on themoviedb.org ("https://www.themoviedb.org/list/12345-best-of").
func tmdbRef(ref, kind string) (int, error) {
	ref = strings.TrimSpace(ref)
	if ref == "" {
		return 0, invalid("Enter the TMDB %s's ID or link", kind)
	}
	if n, err := strconv.Atoi(ref); err == nil && n > 0 {
		return n, nil
	}
	if !strings.Contains(ref, "://") {
		ref = "https://" + ref
	}
	u, err := url.Parse(ref)
	host := ""
	if err == nil {
		host = strings.TrimPrefix(strings.ToLower(u.Hostname()), "www.")
	}
	if host != "themoviedb.org" {
		return 0, invalid("That isn't a TMDB %s's ID or a link to one on themoviedb.org", kind)
	}
	parts := strings.Split(strings.Trim(u.Path, "/"), "/")
	// Links may carry a language first: /fr/list/12345.
	for i := 0; i+1 < len(parts); i++ {
		if parts[i] != kind {
			continue
		}
		digits := parts[i+1]
		if cut := strings.IndexFunc(digits, func(r rune) bool { return r < '0' || r > '9' }); cut >= 0 {
			digits = digits[:cut]
		}
		if n, err := strconv.Atoi(digits); err == nil && n > 0 {
			return n, nil
		}
	}
	return 0, invalid("That link isn't to a TMDB %s", kind)
}

func (t *TMDB) Fetch(ctx context.Context, r Request) (Result, error) {
	if err := t.Validate(r); err != nil {
		return Result{}, err
	}
	if !t.Client.Configured() {
		return Result{}, tmdb.ErrNoAPIKey
	}
	limit := min(max(r.Limit, 1), MaxEntries)
	p := r.Params
	var (
		got  []tmdb.ListItem
		link string
		err  error
	)
	pages := func(fetch func(page int) (tmdb.Page, error)) {
		for page := 1; len(got) < limit && page <= (MaxEntries+19)/20+1; page++ {
			var pg tmdb.Page
			if pg, err = fetch(page); err != nil {
				return
			}
			got = append(got, pg.Items...)
			if len(pg.Items) == 0 || page >= pg.TotalPages {
				return
			}
		}
	}
	switch p.Chart {
	case ChartTrendingDay, ChartTrendingWeek:
		window := strings.TrimPrefix(p.Chart, "trending-")
		pages(func(page int) (tmdb.Page, error) { return t.Client.Trending(ctx, r.MediaType, window, page) })
		link = "https://www.themoviedb.org/"
	case ChartDiscover:
		q := t.discover(p, r.MediaType)
		pages(func(page int) (tmdb.Page, error) { return t.Client.Discover(ctx, r.MediaType, q, page) })
	case ChartList:
		id, _ := tmdbRef(p.Ref, "list")
		pages(func(page int) (tmdb.Page, error) { return t.Client.List(ctx, id, page) })
		link = fmt.Sprintf("https://www.themoviedb.org/list/%d", id)
	case ChartCollection:
		id, _ := tmdbRef(p.Ref, "collection")
		got, err = t.Client.Collection(ctx, id)
		link = fmt.Sprintf("https://www.themoviedb.org/collection/%d", id)
	default:
		path := chartPaths[r.MediaType][p.Chart]
		pages(func(page int) (tmdb.Page, error) { return t.Client.Chart(ctx, r.MediaType, path, page) })
		link = "https://www.themoviedb.org/" + r.MediaType
		if path != "popular" {
			link += "/" + strings.ReplaceAll(path, "_", "-")
		}
	}
	if errors.Is(err, tmdb.ErrNotFound) && (p.Chart == ChartList || p.Chart == ChartCollection) {
		return Result{}, &RefusedError{Reason: "TMDB has no such " + p.Chart + "; it may be private or deleted"}
	}
	if err != nil {
		return Result{}, err
	}
	entries := make([]Entry, 0, len(got))
	for _, it := range got {
		entries = append(entries, Entry{MediaType: it.MediaType, TMDBID: int64(it.ID), Title: it.Title, Year: int64(tmdb.Year(it.Date)), PosterPath: it.PosterPath})
	}
	return Result{Entries: finish(entries, r.MediaType, limit), URL: link}, nil
}

// discover is discover's query for p: every genre must match; the years
// count releases (or first airings); rating and votes are TMDB's own. A
// rating sort without a minimum of votes asks for 200, or a film three
// people loved would lead.
func (t *TMDB) discover(p Params, mediaType string) url.Values {
	q := url.Values{}
	date := "primary_release_date"
	if mediaType == "tv" {
		date = "first_air_date"
	}
	if len(p.Genres) > 0 {
		ids := make([]string, len(p.Genres))
		for i, g := range p.Genres {
			ids[i] = strconv.FormatInt(g, 10)
		}
		q.Set("with_genres", strings.Join(ids, ","))
	}
	if p.YearFrom > 0 {
		q.Set(date+".gte", fmt.Sprintf("%04d-01-01", p.YearFrom))
	}
	if p.YearTo > 0 {
		q.Set(date+".lte", fmt.Sprintf("%04d-12-31", p.YearTo))
	}
	if p.MinRating > 0 {
		q.Set("vote_average.gte", strconv.FormatFloat(p.MinRating, 'f', -1, 64))
	}
	votes := p.MinVotes
	if votes == 0 && p.SortBy == "rating" {
		votes = 200
	}
	if votes > 0 {
		q.Set("vote_count.gte", strconv.Itoa(votes))
	}
	if p.Language != "" {
		q.Set("with_original_language", p.Language)
	}
	switch p.SortBy {
	case "rating":
		q.Set("sort_by", "vote_average.desc")
	case "votes":
		q.Set("sort_by", "vote_count.desc")
	case "newest":
		q.Set("sort_by", date+".desc")
		// Newest first means released by today, not announced for 2031.
		if p.YearTo == 0 {
			now := time.Now
			if t.Now != nil {
				now = t.Now
			}
			q.Set(date+".lte", now().UTC().Format(time.DateOnly))
		}
	default:
		q.Set("sort_by", "popularity.desc")
	}
	return q
}
