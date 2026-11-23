package api

import (
	"context"
	"fmt"
	"net/http"
	"sort"
	"strings"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/db"
)

// PersonInput names a person by TMDB's person ID.
type PersonInput struct {
	PersonID int64 `path:"personId" minimum:"1" doc:"TMDB person ID, from a credit's personId."`
}

// PersonTitle is a title a person is in: its card and the part they play.
type PersonTitle struct {
	BrowseItem
	Character string `json:"character,omitempty" required:"false" doc:"The part, as TMDB words it; two parts in one title are joined with \" / \". Absent when TMDB names none."`
}

// PersonDetail is a person and their titles in the library.
type PersonDetail struct {
	PersonID    int64         `json:"personId" doc:"TMDB person ID."`
	Name        string        `json:"name"`
	ProfilePath string        `json:"profilePath,omitempty" required:"false" doc:"Their picture; fetch via /api/images/{size}{profilePath}."`
	Movies      []PersonTitle `json:"movies" nullable:"false" doc:"Movies with them in the billed cast and a present file, newest first, one card per title however many libraries have it."`
	Shows       []PersonTitle `json:"shows" nullable:"false" doc:"Shows likewise."`
}

type PersonOutput struct{ Body PersonDetail }

func registerPeople(h huma.API, deps Deps) {
	huma.Register(h, huma.Operation{
		OperationID: "getPerson",
		Method:      http.MethodGet,
		Path:        Prefix + "/people/{personId}",
		Summary:     "A cast member's movies and shows in the library",
		Description: "The titles in every library with the person in their billed cast, matched by TMDB person ID, never by name, with the part played. Titles matched before the server kept person IDs count once their credits are fetched again in the background. 404 when no title in the library has the person. From API level 34.",
		Tags:        []string{"browse"},
	}, func(ctx context.Context, in *PersonInput) (*PersonOutput, error) {
		out, err := personDetail(ctx, deps, in.PersonID)
		if err != nil {
			return nil, err
		}
		return &PersonOutput{Body: out}, nil
	})
}

// castRow is one of a person's parts, from either kind of title.
type castRow struct {
	titleID     int64
	name        string
	character   string
	profilePath *string
	checkedAt   string
}

// parts gathers a person's parts per title, in billing order, and keeps
// the row that names them best: the newest stored, with a picture.
func parts(rows []castRow, best *castRow) (map[int64][]string, []int64) {
	byTitle := map[int64][]string{}
	var ids []int64
	for _, r := range rows {
		if _, ok := byTitle[r.titleID]; !ok {
			ids = append(ids, r.titleID)
			byTitle[r.titleID] = nil
		}
		if c := strings.TrimSpace(r.character); c != "" && !containsFold(byTitle[r.titleID], c) {
			byTitle[r.titleID] = append(byTitle[r.titleID], c)
		}
		if betterName(r, *best) {
			*best = r
		}
	}
	return byTitle, ids
}

func betterName(r, best castRow) bool {
	if best.name == "" {
		return true
	}
	if (r.profilePath != nil) != (best.profilePath != nil) {
		return r.profilePath != nil
	}
	return r.checkedAt > best.checkedAt
}

func personDetail(ctx context.Context, deps Deps, personID int64) (PersonDetail, error) {
	out := PersonDetail{PersonID: personID, Movies: []PersonTitle{}, Shows: []PersonTitle{}}
	movieRows, err := deps.Queries.ListPersonMovieCast(ctx, personID)
	if err != nil {
		return out, err
	}
	showRows, err := deps.Queries.ListPersonShowCast(ctx, personID)
	if err != nil {
		return out, err
	}
	var best castRow
	rows := make([]castRow, 0, len(movieRows))
	for _, r := range movieRows {
		rows = append(rows, castRow{titleID: r.MovieID, name: r.Name, character: r.Character, profilePath: r.ProfilePath, checkedAt: r.CheckedAt})
	}
	movieParts, movieIDs := parts(rows, &best)
	rows = rows[:0]
	for _, r := range showRows {
		rows = append(rows, castRow{titleID: r.ShowID, name: r.Name, character: r.Character, profilePath: r.ProfilePath, checkedAt: r.CheckedAt})
	}
	showParts, showIDs := parts(rows, &best)

	marks, err := loadWatchMarks(ctx, deps)
	if err != nil {
		return out, err
	}
	var movies, shows []BrowseItem
	if len(movieIDs) > 0 {
		cards, err := deps.Queries.ListMovieCardsByIDs(ctx, movieIDs)
		if err != nil {
			return out, err
		}
		for _, r := range cards {
			movies = append(movies, movieItem(db.ListMovieItemsRow(r)))
		}
	}
	if len(showIDs) > 0 {
		cards, err := deps.Queries.ListShowCardsByIDs(ctx, showIDs)
		if err != nil {
			return out, err
		}
		for _, r := range cards {
			shows = append(shows, showItem(db.ListShowItemsRow(r)))
		}
	}
	movies, shows = onePerTitle(movies), onePerTitle(shows)
	marks.apply(movies)
	marks.apply(shows)
	if len(movies) == 0 && len(shows) == 0 {
		return out, huma.Error404NotFound("no title in the library has this person")
	}
	out.Name = best.name
	out.ProfilePath = deref(best.profilePath)
	out.Movies = personTitles(movies, movieParts)
	out.Shows = personTitles(shows, showParts)
	return out, nil
}

// onePerTitle keeps one card per TMDB title, the first library's copy, as
// list rows do, newest first.
func onePerTitle(items []BrowseItem) []BrowseItem {
	sort.SliceStable(items, func(i, j int) bool {
		if items[i].TMDBID != items[j].TMDBID {
			return items[i].TMDBID < items[j].TMDBID
		}
		return items[i].LibraryID < items[j].LibraryID
	})
	out := items[:0]
	for i, it := range items {
		if i > 0 && it.TMDBID == items[i-1].TMDBID {
			continue
		}
		out = append(out, it)
	}
	sort.SliceStable(out, func(i, j int) bool {
		a, b := newest(out[i]), newest(out[j])
		if a != b {
			return a > b
		}
		return titleKey(out[i].Title) < titleKey(out[j].Title)
	})
	return out
}

// newest is what a person's titles sort by: a movie's release, a show's
// latest airing, else its year.
func newest(it BrowseItem) string {
	switch {
	case it.ReleaseDate != "":
		return it.ReleaseDate
	case it.LastAirDate != "":
		return it.LastAirDate
	case it.Year > 0:
		return fmt.Sprintf("%04d", it.Year)
	}
	return ""
}

func personTitles(items []BrowseItem, parts map[int64][]string) []PersonTitle {
	out := make([]PersonTitle, 0, len(items))
	for _, it := range items {
		out = append(out, PersonTitle{BrowseItem: it, Character: strings.Join(parts[it.ID], " / ")})
	}
	return out
}
