package metadata

import "testing"

func TestTitleSimilarity(t *testing.T) {
	cases := []struct {
		a, b string
		min  float64
		max  float64
	}{
		{"The Matrix", "The Matrix", 1, 1},
		{"The Matrix", "Matrix", 1, 1},
		{"Blade Runner 2049", "Blade Runner 2049", 1, 1},
		{"Blade Runner 2049", "Blade Runner", 0.6, 0.9},
		{"Heat", "Heat", 1, 1},
		{"Heat", "Hate", 0.4, 0.8},
		{"Mr Robot", "Mr. Robot", 1, 1},
		{"Léon The Professional", "Leon: The Professional", 1, 1},
		{"Breaking Bad", "Better Call Saul", 0, 0.4},
		// Names on disk leave out apostrophes and spaces TMDB keeps.
		{"Coopers Bar", "Cooper's Bar", 1, 1},
		{"The Queens Gambit", "The Queen’s Gambit", 1, 1},
		{"Mother Father Son", "MotherFatherSon", 1, 1},
		{"McMillions", "McMillion$", 1, 1},
		// The same words reordered or repeated are close, never the same.
		{"Red Rose", "Rose Red", 0.85, 0.9},
		{"The Way Down", "The Way Way Down", 0.7, 0.9},
	}
	for _, c := range cases {
		got := titleSimilarity(c.a, c.b)
		if got < c.min || got > c.max {
			t.Errorf("similarity(%q, %q) = %.2f, want [%.2f, %.2f]", c.a, c.b, got, c.min, c.max)
		}
	}
}

func TestRankAndAccept(t *testing.T) {
	in := []scoreInput{
		{TMDBID: 1, Title: "Heat", Year: 1995, Popularity: 40},
		{TMDBID: 2, Title: "Heat", Year: 1986, Popularity: 5},
		{TMDBID: 3, Title: "Heat Wave", Year: 1995, Popularity: 2},
	}
	ranked := rank([]reading{{"Heat", new(1995)}}, "", in)
	if ranked[0].TMDBID != 1 || !accept(ranked[0]) {
		t.Errorf("ranked = %+v", ranked)
	}
	// Without a year the two "Heat"s tie on title; popularity breaks it, but
	// an exact title with no year is still accepted.
	ranked = rank([]reading{{"Heat", nil}}, "", in)
	if ranked[0].TMDBID != 1 || !accept(ranked[0]) {
		t.Errorf("no-year ranked = %+v", ranked)
	}
	// A wrong year with a merely similar title is rejected.
	ranked = rank([]reading{{"Heat Waves", new(2010)}}, "", in)
	if accept(ranked[0]) {
		t.Errorf("should reject %+v", ranked[0])
	}
}

func TestRankReadings(t *testing.T) {
	// The folder says "The Connors", the file "The.Conners": the file's
	// reading finds the show.
	in := []scoreInput{{TMDBID: 1, Title: "The Conners", Year: 2018}}
	ranked := rank([]reading{{"The Connors", nil}, {"The Conners", nil}}, "", in)
	if !accept(ranked[0]) || ranked[0].Score < 0.88 {
		t.Errorf("file reading = %+v", ranked[0])
	}
	// A year from the file rules out an older show of the same name.
	in = []scoreInput{
		{TMDBID: 1, Title: "Queenie", Year: 1987, Popularity: 50},
		{TMDBID: 2, Title: "Queenie", Year: 2024, Popularity: 5},
	}
	ranked = rank([]reading{{"Queenie", new(2024)}}, "", in)
	if ranked[0].TMDBID != 2 || !accept(ranked[0]) || accept(ranked[1]) {
		t.Errorf("year from the file = %+v", ranked)
	}
}

func TestCountrySuffix(t *testing.T) {
	title, country := splitCountry("The Office (US)")
	if title != "The Office" || country != "US" {
		t.Errorf("got %q %q", title, country)
	}
	in := []scoreInput{
		{TMDBID: 1, Title: "The Office", Year: 2001, Countries: []string{"GB"}, Popularity: 50},
		{TMDBID: 2, Title: "The Office", Year: 2005, Countries: []string{"US"}, Popularity: 50},
	}
	if r := rank([]reading{{title, nil}}, country, in); r[0].TMDBID != 2 {
		t.Errorf("US bonus not applied: %+v", r)
	}
	// Names say UK where TMDB says GB.
	if r := rank([]reading{{"The Office", nil}}, "UK", in); r[0].TMDBID != 1 {
		t.Errorf("UK bonus not applied: %+v", r)
	}
	// TMDB may keep the country in the title of the version from there.
	in = []scoreInput{
		{TMDBID: 1, Title: "Married at First Sight", Year: 2014, Countries: []string{"US"}, Popularity: 90},
		{TMDBID: 2, Title: "Married at First Sight (NZ)", Year: 2017, Countries: []string{"NZ"}, Popularity: 2},
	}
	if r := rank([]reading{{"Married at First Sight", nil}}, "NZ", in); r[0].TMDBID != 2 || !accept(r[0]) {
		t.Errorf("country in the title = %+v", r)
	}
}
