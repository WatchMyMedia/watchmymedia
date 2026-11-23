package people

import (
	"testing"

	"watchmymedia/server/internal/tmdb"
)

func TestCastKeepsTheBilledTwelve(t *testing.T) {
	t.Parallel()
	var c tmdb.Credits
	for i := 1; i <= 15; i++ {
		c.Cast = append(c.Cast, tmdb.CastMember{ID: 100 + i, Name: "Someone", Character: "Part"})
	}
	got := Cast(c)
	if len(got) != CastKept || got[0].PersonID != 101 || got[11].PersonID != 112 {
		t.Errorf("cast = %+v", got)
	}
}

func TestMovieCrew(t *testing.T) {
	t.Parallel()
	c := tmdb.Credits{Crew: []tmdb.CrewMember{
		{ID: 1, Name: "Producer One", Job: "Producer"},
		{ID: 2, Name: "Writer Two", Job: "Screenplay"},
		{ID: 3, Name: "Director Three", Job: "Director"},
		{ID: 3, Name: "Director Three", Job: "Director"},
		{ID: 4, Name: "Grip Four", Job: "Key Grip"},
		{ID: 5, Name: "Composer Five", Job: "Original Music Composer"},
		{ID: 0, Name: "No ID", Job: "Director"},
	}}
	got := MovieCrew(c)
	want := []string{"Director Three Director", "Writer Two Screenplay", "Composer Five Original Music Composer", "Producer One Producer"}
	if len(got) != len(want) {
		t.Fatalf("crew = %+v", got)
	}
	for i, m := range got {
		if m.Name+" "+m.Job != want[i] {
			t.Errorf("crew %d = %q, want %q", i, m.Name+" "+m.Job, want[i])
		}
	}
	if show := ShowCrew(&tmdb.TVShow{CreatedBy: []tmdb.Person{{ID: 9, Name: "Maker"}, {ID: 0, Name: "Nobody"}}}); len(show) != 1 || show[0].Job != "Creator" {
		t.Errorf("show crew = %+v", show)
	}
	if got := Crew("not json"); got == nil || len(got) != 0 {
		t.Errorf("unreadable crew = %+v", got)
	}
}
