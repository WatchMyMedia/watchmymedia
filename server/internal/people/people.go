// Package people keeps the people behind a title: its billed cast with
// TMDB's person IDs, in the credits JSON the pages draw and in the
// movie_cast and show_cast rows that find a person's titles, and a few of
// its crew. A person is told apart by TMDB's person ID, never by name, since
// two people can share one.
//
// The matcher stores them with a title (StoreMovie, StoreShow, in its
// transaction), and the related refresher stores them whenever it fetches a
// title's details again, which fills in the titles matched before person
// IDs were kept (cast_checked_at NULL).
package people

import (
	"context"
	"encoding/json"
	"slices"
	"strings"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/tmdb"
)

// CastKept is how many of the billed cast a title keeps: the cast row shows
// 12.
const CastKept = 12

// Credit is a top-billed cast member kept with a movie or show.
type Credit struct {
	PersonID    int64  `json:"personId,omitempty" required:"false" doc:"TMDB's person ID: getPerson lists the person's titles in the library. Absent for a credit stored before person IDs were kept, until the server fetches the title's credits again, and on servers below API level 34; such a card opens nothing."`
	Name        string `json:"name"`
	Character   string `json:"character,omitempty"`
	ProfilePath string `json:"profilePath,omitempty"`
}

// CrewMember is one job of one person behind the camera.
type CrewMember struct {
	PersonID int64  `json:"personId" doc:"TMDB's person ID."`
	Name     string `json:"name"`
	Job      string `json:"job" example:"Director" doc:"As TMDB words it: Director, Screenplay, Writer, Story, Original Music Composer, Producer, or Creator for a show."`
}

// crewJobs are the jobs a movie keeps, in the order they are shown, with
// how many people of each.
var crewJobs = []struct {
	jobs []string
	most int
}{
	{[]string{"Director"}, 3},
	{[]string{"Screenplay", "Writer", "Story"}, 4},
	{[]string{"Original Music Composer"}, 2},
	{[]string{"Producer"}, 3},
}

// Cast is the billed cast kept from TMDB's credits: the first CastKept, in
// billing order.
func Cast(c tmdb.Credits) []Credit {
	out := make([]Credit, 0, CastKept)
	for _, m := range c.Cast {
		if len(out) == CastKept {
			break
		}
		out = append(out, Credit{PersonID: int64(m.ID), Name: m.Name, Character: m.Character, ProfilePath: m.ProfilePath})
	}
	return out
}

// CreditsJSON is Cast as the credits column stores it.
func CreditsJSON(c tmdb.Credits) string {
	b, _ := json.Marshal(Cast(c))
	return string(b)
}

// MovieCrew is the crew a movie keeps: its directors, writers, composers and
// producers, each person once per job.
func MovieCrew(c tmdb.Credits) []CrewMember {
	out := []CrewMember{}
	for _, group := range crewJobs {
		seen := map[int]bool{}
		n := 0
		for _, m := range c.Crew {
			if n == group.most {
				break
			}
			if m.ID <= 0 || seen[m.ID] || !slices.Contains(group.jobs, m.Job) {
				continue
			}
			seen[m.ID] = true
			out = append(out, CrewMember{PersonID: int64(m.ID), Name: m.Name, Job: m.Job})
			n++
		}
	}
	return out
}

// ShowCrew is a show's creators.
func ShowCrew(tv *tmdb.TVShow) []CrewMember {
	out := []CrewMember{}
	for _, p := range tv.CreatedBy {
		if p.ID > 0 && strings.TrimSpace(p.Name) != "" {
			out = append(out, CrewMember{PersonID: int64(p.ID), Name: p.Name, Job: "Creator"})
		}
	}
	return out
}

// StoreMovie replaces a movie's cast and crew with those in its TMDB
// details and marks its cast checked at now. mv nil (TMDB no longer has the
// movie) marks it checked and leaves what it has, so it isn't asked for
// again. A movie deleted meanwhile is left alone. Call it inside a
// transaction.
func StoreMovie(ctx context.Context, q *db.Queries, id int64, mv *tmdb.Movie, now time.Time) error {
	stamp := new(db.FormatTime(now))
	if mv == nil {
		return q.MarkMovieCastChecked(ctx, db.MarkMovieCastCheckedParams{CastCheckedAt: stamp, ID: id})
	}
	cast := Cast(mv.Credits)
	credits, _ := json.Marshal(cast)
	crew, _ := json.Marshal(MovieCrew(mv.Credits))
	n, err := q.SetMovieCredits(ctx, db.SetMovieCreditsParams{Credits: string(credits), Crew: string(crew), CastCheckedAt: stamp, ID: id})
	if err != nil || n == 0 {
		return err
	}
	if err := q.DeleteMovieCast(ctx, id); err != nil {
		return err
	}
	for i, c := range cast {
		if c.PersonID <= 0 {
			continue
		}
		if err := q.InsertMovieCast(ctx, db.InsertMovieCastParams{MovieID: id, Billing: int64(i), PersonID: c.PersonID, Name: c.Name,
			Character: c.Character, ProfilePath: nilIfEmpty(c.ProfilePath)}); err != nil {
			return err
		}
	}
	return nil
}

// StoreShow is StoreMovie for a show.
func StoreShow(ctx context.Context, q *db.Queries, id int64, tv *tmdb.TVShow, now time.Time) error {
	stamp := new(db.FormatTime(now))
	if tv == nil {
		return q.MarkShowCastChecked(ctx, db.MarkShowCastCheckedParams{CastCheckedAt: stamp, ID: id})
	}
	cast := Cast(tv.Credits)
	credits, _ := json.Marshal(cast)
	crew, _ := json.Marshal(ShowCrew(tv))
	n, err := q.SetShowCredits(ctx, db.SetShowCreditsParams{Credits: string(credits), Crew: string(crew), CastCheckedAt: stamp, ID: id})
	if err != nil || n == 0 {
		return err
	}
	if err := q.DeleteShowCast(ctx, id); err != nil {
		return err
	}
	for i, c := range cast {
		if c.PersonID <= 0 {
			continue
		}
		if err := q.InsertShowCast(ctx, db.InsertShowCastParams{ShowID: id, Billing: int64(i), PersonID: c.PersonID, Name: c.Name,
			Character: c.Character, ProfilePath: nilIfEmpty(c.ProfilePath)}); err != nil {
			return err
		}
	}
	return nil
}

// Crew reads a crew column; anything unreadable is no crew.
func Crew(raw string) []CrewMember {
	var out []CrewMember
	if err := json.Unmarshal([]byte(raw), &out); err != nil || out == nil {
		return []CrewMember{}
	}
	return out
}

func nilIfEmpty(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}
