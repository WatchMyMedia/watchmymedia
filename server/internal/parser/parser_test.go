package parser

import (
	"reflect"
	"slices"
	"testing"
)

func TestParseMovie(t *testing.T) {
	cases := []struct {
		rel   string
		title string
		year  *int
		res   string
		src   string
	}{
		{"The Matrix (1999)/The Matrix (1999).mkv", "The Matrix", new(1999), "", ""},
		{"The Matrix (1999).mkv", "The Matrix", new(1999), "", ""},
		{"The Matrix (1999)/The.Matrix.1999.1080p.BluRay.x264-GROUP.mkv", "The Matrix", new(1999), "", ""},
		{"Action/The.Matrix.1999.2160p.UHD.BluRay.x265.HDR-GROUP.mkv", "The Matrix", new(1999), "2160p", "BluRay"},
		{"Blade Runner 2049 (2017)/Blade Runner 2049 (2017) [1080p].mp4", "Blade Runner 2049", new(2017), "", ""},
		{"Blade.Runner.2049.2017.1080p.WEB-DL.mkv", "Blade Runner 2049", new(2017), "1080p", "WEB-DL"},
		{"2001 A Space Odyssey (1968)/2001 A Space Odyssey (1968).mkv", "2001 A Space Odyssey", new(1968), "", ""},
		{"1917 (2019)/1917 (2019).mkv", "1917", new(2019), "", ""},
		{"Some Movie Without Year.mkv", "Some Movie Without Year", nil, "", ""},
		{"Some.Movie.720p.HDTV.x264.mkv", "Some Movie", nil, "720p", "HDTV"},
		{"Movie_With_Underscores_(2005).avi", "Movie With Underscores", new(2005), "", ""},
		{"Heat (1995)/Heat (1995) - Director's Cut 4K.mkv", "Heat", new(1995), "", ""},
		{"pitch.black.unrated.directors.cut.2000.1080p.bluray.x264-fihvid.mkv", "pitch black", new(2000), "1080p", "BluRay"},
		{"Caligula.The.Ultimate.Cut.2023.1080p.WEB.H264-SLOT.mkv", "Caligula", new(2023), "1080p", ""},
		{"The International (2009).mkv", "The International", new(2009), "", ""},
		{"See.How.They.Run.2022.2022.1080p.WEB-DL.mkv", "See How They Run", new(2022), "1080p", "WEB-DL"},
		{"Some_Movie_2005_1080p.mkv", "Some Movie", new(2005), "1080p", ""},
		{"1917.mkv", "1917", nil, "", ""},
		// Accents written decomposed (e + U+0301), as some file systems store them.
		{"Léon The Professional (1994)/Leon.1994.mkv", "Léon The Professional", new(1994), "", ""},
	}
	for _, c := range cases {
		t.Run(c.rel, func(t *testing.T) {
			g := ParseMovie(c.rel)
			if g.Kind != KindMovie {
				t.Errorf("kind = %q", g.Kind)
			}
			if g.Title != c.title {
				t.Errorf("title = %q, want %q", g.Title, c.title)
			}
			if !reflect.DeepEqual(g.Year, c.year) {
				t.Errorf("year = %v, want %v", deref(g.Year), deref(c.year))
			}
			if c.res != "" && g.Resolution != c.res {
				t.Errorf("resolution = %q, want %q", g.Resolution, c.res)
			}
			if c.src != "" && g.Source != c.src {
				t.Errorf("source = %q, want %q", g.Source, c.src)
			}
		})
	}
}

func TestParseEpisode(t *testing.T) {
	cases := []struct {
		rel      string
		kind     Kind
		show     string
		year     *int
		season   *int
		episodes []int
		epTitle  string
	}{
		{"Breaking Bad (2008)/Season 01/Breaking Bad - S01E01 - Pilot.mkv", KindEpisode, "Breaking Bad", new(2008), new(1), []int{1}, "Pilot"},
		{"Breaking Bad/Season 1/Breaking.Bad.S01E02.1080p.BluRay.x264.mkv", KindEpisode, "Breaking Bad", nil, new(1), []int{2}, ""},
		{"The Office (US)/Season 02/The Office - 2x05 - Halloween.mkv", KindEpisode, "The Office (US)", nil, new(2), []int{5}, "Halloween"},
		{"The Office (UK) (2001)/Season 1/The Office (UK) - S01E01.mkv", KindEpisode, "The Office (UK)", new(2001), new(1), []int{1}, ""},
		{"Show/Season 03/Show - S03E01E02 - Two Parter.mkv", KindEpisode, "Show", nil, new(3), []int{1, 2}, "Two Parter"},
		{"Show/Season 03/Show - S03E05-E07 - Three Parter.mkv", KindEpisode, "Show", nil, new(3), []int{5, 6, 7}, "Three Parter"},
		{"Show/Specials/Show - S00E01 - Christmas Special.mkv", KindEpisode, "Show", nil, new(0), []int{1}, "Christmas Special"},
		{"Show/Season 02/03 - Just A Number.mkv", KindEpisode, "Show", nil, new(2), []int{3}, "Just A Number"},
		{"Show/Season 02/s2e4.mkv", KindEpisode, "Show", nil, new(2), []int{4}, ""},
		{"Show.Name.S01E03.720p.HDTV.x264-GROUP.mkv", KindEpisode, "Show Name", nil, new(1), []int{3}, ""},
		{"Show/Season 01/Show - S01E01 - Title - 1080p WEB-DL.mkv", KindEpisode, "Show", nil, new(1), []int{1}, "Title"},
		{"Show/Random File.mkv", KindUnknown, "Show", nil, nil, nil, ""},
		{"The Daily Show/Season 2024/The Daily Show - S2024E123 - Guest.mkv", KindEpisode, "The Daily Show", nil, new(2024), []int{123}, "Guest"},
	}
	for _, c := range cases {
		t.Run(c.rel, func(t *testing.T) {
			g := ParseEpisode(c.rel)
			if g.Kind != c.kind {
				t.Errorf("kind = %q, want %q", g.Kind, c.kind)
			}
			if g.Title != c.show {
				t.Errorf("show = %q, want %q", g.Title, c.show)
			}
			if !reflect.DeepEqual(g.Year, c.year) {
				t.Errorf("year = %v, want %v", deref(g.Year), deref(c.year))
			}
			if !reflect.DeepEqual(g.Season, c.season) {
				t.Errorf("season = %v, want %v", deref(g.Season), deref(c.season))
			}
			if !reflect.DeepEqual(g.Episodes, c.episodes) {
				t.Errorf("episodes = %v, want %v", g.Episodes, c.episodes)
			}
			if g.EpisodeTitle != c.epTitle {
				t.Errorf("episode title = %q, want %q", g.EpisodeTitle, c.epTitle)
			}
		})
	}
}

// Names from a real library's unmatched files: the season and episode, and
// what the file's own name adds to its folder's.
func TestParseEpisodeNames(t *testing.T) {
	cases := []struct {
		rel       string
		show      string
		year      *int
		season    *int
		episodes  []int
		fileTitle string
		fileYear  *int
	}{
		{"Its Always Sunny in Philadelphia/Its_Always_Sunny_in_Philadelphia_S04E01-CLUE.mkv", "Its Always Sunny in Philadelphia", nil, new(4), []int{1}, "", nil},
		{"Horace and Pete/HORACE_AND_PETE_01x01_1080.mkv", "Horace and Pete", nil, new(1), []int{1}, "", nil},
		{"Doctor Who/doctor_who_2005.07x15_2013_christmas_special.720p_hdtv_x264-fov.mkv", "Doctor Who", nil, new(7), []int{15}, "", new(2005)},
		{"Breaking Bad/Breaking.Bad.Season.1.Episode.01.Pilot.REMUX.mkv", "Breaking Bad", nil, new(1), []int{1}, "", nil},
		{"Moonshine (CA)/Moonshine.2021.Season.3.Episode.2.720p.WEB-DL.mkv", "Moonshine (CA)", nil, new(3), []int{2}, "Moonshine", new(2021)},
		{"Irma Vep/Irma.Vep.E01.The.Severed.Head.1080p.HMAX.WEB-DL.mkv", "Irma Vep", nil, new(1), []int{1}, "", nil},
		{"Show/Season 02/Show - E05.mkv", "Show", nil, new(2), []int{5}, "", nil},
		{"Muhammad Ali/Muhammad.Ali.Part2.720p.BluRay.x264-MiMiC.mkv", "Muhammad Ali", nil, new(1), []int{2}, "", nil},
		{"Reconstruction/Reconstruction.2019.Part.3-Part.4.720p.HDTV.mkv", "Reconstruction", nil, new(1), []int{3, 4}, "", new(2019)},
		{"Show/Season 01/Show - S01E01 - Part 2.mkv", "Show", nil, new(1), []int{1}, "", nil},
		{"1923/1923.S01E01.1080p.AMZN.WEB-DL.mkv", "1923", nil, new(1), []int{1}, "", nil},
		{"E.B. White's Charlotte's Web/E.B.Whites.Charlottes.Web.S01E01.Part.1.1080p.mkv", "E.B. White's Charlotte's Web", nil, new(1), []int{1}, "E B Whites Charlottes", nil},
		{"The Connors/The.Conners.S01E02.720p.HDTV.x264-AVS.mkv", "The Connors", nil, new(1), []int{2}, "The Conners", nil},
		{"Rivals (2023)/Rivals.2024.S01E01.1080p.WEB.H264.mkv", "Rivals", new(2023), new(1), []int{1}, "", new(2024)},
		{"Queenie/queenie.2024.s01e03.1080p.web.h264.mkv", "Queenie", nil, new(1), []int{3}, "", new(2024)},
		{"Breaking Bad (2008)/Season 1/Breaking.Bad.S01E02.1080p.mkv", "Breaking Bad", new(2008), new(1), []int{2}, "", nil},
		// Accents written decomposed (o + U+0304), as some file systems store them.
		{"Shōgun (2024)/Shogun.2024.S01E03.1080p.WEB.mkv", "Shōgun", new(2024), new(1), []int{3}, "Shogun", nil},
		// A loose file: the title and year come from its own name.
		{"Bookie.2023.S01E03.1080p.WEB.h264-ETHEL.mkv", "Bookie", new(2023), new(1), []int{3}, "", nil},
	}
	for _, c := range cases {
		t.Run(c.rel, func(t *testing.T) {
			g := ParseEpisode(c.rel)
			if g.Kind != KindEpisode || g.Title != c.show || !reflect.DeepEqual(g.Year, c.year) {
				t.Errorf("got %q %q %v, want episode %q %v", g.Kind, g.Title, deref(g.Year), c.show, deref(c.year))
			}
			if !reflect.DeepEqual(g.Season, c.season) || !reflect.DeepEqual(g.Episodes, c.episodes) {
				t.Errorf("season %v episodes %v, want %v %v", deref(g.Season), g.Episodes, deref(c.season), c.episodes)
			}
			if g.FileTitle != c.fileTitle || !reflect.DeepEqual(g.FileYear, c.fileYear) {
				t.Errorf("file title %q year %v, want %q %v", g.FileTitle, deref(g.FileYear), c.fileTitle, deref(c.fileYear))
			}
		})
	}
}

func TestMovieFileName(t *testing.T) {
	cases := []struct {
		rel       string
		title     string
		fileTitle string
		fileYear  *int
	}{
		{"Fantastic Beasts 3 (2022)/Fantastic.Beasts.The.Secrets.of.Dumbledore.2022.1080p.WEB-DL.mkv", "Fantastic Beasts 3", "Fantastic Beasts The Secrets of Dumbledore", nil},
		{"Sator (2021)/Sator.2019.720p.AMZN.WEB-DL.mkv", "Sator", "", new(2019)},
		{"The Matrix (1999)/The.Matrix.1999.1080p.BluRay.x264-GROUP.mkv", "The Matrix", "", nil},
		{"The.Matrix.1999.1080p.mkv", "The Matrix", "", nil},
	}
	for _, c := range cases {
		g := ParseMovie(c.rel)
		if g.Title != c.title || g.FileTitle != c.fileTitle || !reflect.DeepEqual(g.FileYear, c.fileYear) {
			t.Errorf("%s: %q, file %q %v; want %q, file %q %v", c.rel, g.Title, g.FileTitle, deref(g.FileYear), c.title, c.fileTitle, deref(c.fileYear))
		}
	}
}

func TestIgnore(t *testing.T) {
	for _, d := range []string{"Extras", "featurettes", ".hidden", "@eaDir", "Samples"} {
		if !IgnoreDir(d) {
			t.Errorf("IgnoreDir(%q) = false", d)
		}
	}
	for _, d := range []string{"Season 01", "The Matrix (1999)"} {
		if IgnoreDir(d) {
			t.Errorf("IgnoreDir(%q) = true", d)
		}
	}
	for _, f := range []string{"movie-sample.mkv", "Sample.mkv", "Movie Trailer.mp4", "._resource.mkv", "movie.sample.mkv"} {
		if !IgnoreFile(f) {
			t.Errorf("IgnoreFile(%q) = false", f)
		}
	}
	for _, f := range []string{"The Sampler (2010).mkv", "Trailer Park Boys - S01E01.mkv"} {
		if IgnoreFile(f) {
			t.Errorf("IgnoreFile(%q) = true", f)
		}
	}
	if !IsVideo("a.MKV") || IsVideo("a.srt") || IsVideo("a.jpg") {
		t.Error("IsVideo wrong")
	}
}

// Scans read trailers (as extras) but still leave out samples and hidden
// files, and of the folders IgnoreDir names, read only a Trailers folder's
// own files.
func TestScanRules(t *testing.T) {
	for _, f := range []string{"movie-sample.mkv", "Sample.mkv", "._resource.mkv", "movie.sample.mkv", "sample-heat.mkv"} {
		if !SkipFile(f) {
			t.Errorf("SkipFile(%q) = false", f)
		}
	}
	for _, f := range []string{"Movie Trailer.mp4", "trailer.mp4", "Heat (1995)-trailer.mkv", "The Sampler (2010).mkv", "Heat (1995).mkv"} {
		if SkipFile(f) {
			t.Errorf("SkipFile(%q) = true", f)
		}
	}
	for rel, skip := range map[string]bool{
		"":                              false,
		".":                             false,
		"Heat (1995)":                   false,
		"Heat (1995)/Trailers":          false,
		"Trailers":                      false,
		"Heat (1995)/trailers":          false,
		"Heat (1995)/Trailers/Teasers":  true, // only a Trailers folder's own files
		"Heat (1995)/Extras":            true,
		"Heat (1995)/Extras/Trailers":   true,
		"Heat (1995)/.hidden/Trailers":  true,
		`Heat (1995)\Trailers\Teasers`:  true,
		"Heat (1995)/Featurettes":       true,
		"Collection/Heat (1995)/Sample": true,
	} {
		if got := SkipDir(rel); got != skip {
			t.Errorf("SkipDir(%q) = %v, want %v", rel, got, skip)
		}
	}
}

func TestParseExtra(t *testing.T) {
	cases := []struct {
		rel     string
		trailer bool
		dir, of string
	}{
		// Next to the movie, named after its file.
		{"Heat (1995)/Heat (1995)-trailer.mkv", true, "Heat (1995)", "Heat (1995)"},
		{"Heat (1995)/Heat (1995).trailer.mp4", true, "Heat (1995)", "Heat (1995)"},
		{"Heat (1995)/Heat (1995)_trailer.mp4", true, "Heat (1995)", "Heat (1995)"},
		{"Heat (1995)/Heat (1995) trailer.mp4", true, "Heat (1995)", "Heat (1995)"},
		{"Heat (1995)/Heat (1995) - Trailer.mp4", true, "Heat (1995)", "Heat (1995)"},
		{"Heat.1995.1080p.BluRay-TRAILER.mkv", true, "", "Heat.1995.1080p.BluRay"},
		{"Movies/Heat (1995) TRAILER.mkv", true, "Movies", "Heat (1995)"},
		// In the movie's own folder.
		{"Heat (1995)/trailer.mp4", true, "Heat (1995)", ""},
		{"Heat (1995)/Trailer.MKV", true, "Heat (1995)", ""},
		{"Heat (1995)/movie-trailer.mkv", true, "Heat (1995)", ""},
		{"trailer.mp4", true, "", ""},
		{"Heat (1995)/-trailer.mkv", true, "Heat (1995)", ""},
		// In a Trailers folder.
		{"Heat (1995)/Trailers/Official Trailer.mp4", true, "Heat (1995)", ""},
		{"Heat (1995)/trailers/Teaser 2.mkv", true, "Heat (1995)", ""},
		{"Trailers/Heat.mp4", true, "", ""},
		{`Heat (1995)\Trailers\Teaser.mkv`, true, "Heat (1995)", ""},
		// Content.
		{"Heat (1995)/Heat (1995).mkv", false, "", ""},
		{"Trailer Park Boys (2014)/Trailer Park Boys (2014).mkv", false, "", ""},
		{"The Trailer (2020)/The Trailer (2020).mkv", false, "", ""},
		{"Heat (1995)/Heat (1995) - Trailers.mkv", false, "", ""},
		{"Heat (1995)/trailered.mkv", false, "", ""},
	}
	for _, c := range cases {
		t.Run(c.rel, func(t *testing.T) {
			e, ok := ParseExtra(c.rel)
			if ok != c.trailer {
				t.Fatalf("trailer = %v, want %v", ok, c.trailer)
			}
			if !ok {
				return
			}
			if e.Kind != ExtraTrailer || e.Dir != c.dir || e.Of != c.of {
				t.Errorf("got %+v, want dir %q of %q", e, c.dir, c.of)
			}
		})
	}
}

func deref(p *int) any {
	if p == nil {
		return nil
	}
	return *p
}

func TestMatchFolder(t *testing.T) {
	cases := []struct{ typ, rel, want string }{
		{"tv", "Show (2008)/Season 01/Show - S01E01.mkv", "Show (2008)"},
		{"tv", "Show/S01E01.mkv", "Show"},
		{"tv", "Show.S01E01.mkv", ""},
		{"movie", "Heat (1995)/Heat.mkv", "Heat (1995)"},
		{"movie", "A-Z/Heat (1995)/Heat.1080p.mkv", "A-Z/Heat (1995)"},
		{"movie", "Collection/Heat (1995).mkv", ""}, // an undated folder holds several movies
		{"movie", "Heat (1995).mkv", ""},
		{"tv", "The.Daily.Show.2026.10.27.Guest.1080p.WEB.h264-EDITH/The.Daily.Show.2026.10.27.Guest.mkv", ""}, // a release folder, not a show folder
		{"tv", "Show.S01E02.720p-GRP/Show.S01E02.720p-GRP.mkv", ""},
		{"tv", "The Daily Show/The.Daily.Show.2026.10.27.Guest.1080p-GRP/file.mkv", "The Daily Show"},
	}
	for _, c := range cases {
		if got := MatchFolder(c.typ, c.rel); got != c.want {
			t.Errorf("MatchFolder(%q, %q) = %q, want %q", c.typ, c.rel, got, c.want)
		}
	}
}

func TestParseDatedEpisode(t *testing.T) {
	cases := []struct{ rel, show, date, epTitle string }{
		{"The Daily Show/The Daily Show - 2024-10-28 - Guest.mkv", "The Daily Show", "2024-10-28", "Guest"},
		{"The Daily Show (1996)/Season 2024/The.Daily.Show.2024.10.28.Guest.Name.1080p.WEB.h264-GRP.mkv", "The Daily Show", "2024-10-28", "Guest Name"},
		{"The.Daily.Show.2024.10.28.1080p.WEB.mkv", "The Daily Show", "2024-10-28", ""},
		{"Show/Show - 2024-13-40.mkv", "Show", "", ""}, // not a date
		// Each episode in its own download folder at the top of the library.
		{"The.Daily.Show.2026.10.27.Morgana.Robinson.1080p.WEB.h264-EDITH/The.Daily.Show.2026.10.27.Morgana.Robinson.1080p.WEB.h264-EDITH.mkv", "The Daily Show", "2026-10-27", "Morgana Robinson"},
	}
	for _, c := range cases {
		t.Run(c.rel, func(t *testing.T) {
			g := ParseEpisode(c.rel)
			if g.Title != c.show || g.AirDate != c.date || g.EpisodeTitle != c.epTitle {
				t.Errorf("got show %q date %q title %q", g.Title, g.AirDate, g.EpisodeTitle)
			}
			if (c.date != "") != (g.Kind == KindEpisode) {
				t.Errorf("kind = %s", g.Kind)
			}
		})
	}
}

// Dates written without separators or with the year last. A year-last date
// whose day and month could be either way round keeps both readings.
func TestParseOtherDateOrders(t *testing.T) {
	cases := []struct{ rel, date, alt, epTitle string }{
		{"The Daily Show/The.Daily.Show.20261027.Guest.mkv", "2026-10-27", "", "Guest"},
		{"The Daily Show/The.Daily.Show.27.10.2026.Guest.mkv", "2026-10-27", "", "Guest"},
		{"The Daily Show/The.Daily.Show.10.27.2026.Guest.mkv", "2026-10-27", "", "Guest"},
		{"The Daily Show/The Daily Show - 05-06-2024 - Guest.mkv", "2024-06-05", "2024-05-06", "Guest"},
		{"The Daily Show/The Daily Show - 06.06.2024.mkv", "2024-06-06", "", ""},
		{"The Daily Show/The Daily Show - 2024.28.10.mkv", "2024-10-28", "", ""},
		{"Show/Show - 20241399.mkv", "", "", ""}, // not a date
		{"Show/Show - 31.31.2024.mkv", "", "", ""},
		// Release folders dated this way are not show folders either.
		{"The.Daily.Show.27.10.2026.Guest.1080p-GRP/The.Daily.Show.27.10.2026.Guest.1080p-GRP.mkv", "2026-10-27", "", "Guest"},
	}
	for _, c := range cases {
		t.Run(c.rel, func(t *testing.T) {
			g := ParseEpisode(c.rel)
			if g.AirDate != c.date || g.AirDateAlt != c.alt || g.EpisodeTitle != c.epTitle {
				t.Errorf("got date %q alt %q title %q", g.AirDate, g.AirDateAlt, g.EpisodeTitle)
			}
			if c.date != "" && (g.Title != "The Daily Show" || g.Year != nil) {
				t.Errorf("got show %q year %v", g.Title, deref(g.Year))
			}
		})
	}
}

// Multi-episode ranges must go up and stay short.
func TestEpisodeRanges(t *testing.T) {
	cases := []struct {
		rel  string
		want []int
	}{
		{"Show/Season 01/Show.S01E01-E03.mkv", []int{1, 2, 3}},
		{"Show/Season 01/Show.S01E01E02E03.mkv", []int{1, 2, 3}},
		{"Show/Season 01/Show.S01E05-E02.mkv", []int{5}},
		{"Show/Season 01/Show.S01E01-E99.mkv", []int{1}},
		{"Show/Season 01/Show.S01E01-E51.mkv", []int{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51}},
		{"Show/Season 01/Show.S01E02E02.mkv", []int{2}},
	}
	for _, c := range cases {
		if got := ParseEpisode(c.rel).Episodes; !slices.Equal(got, c.want) {
			t.Errorf("%s: episodes %v, want %v", c.rel, got, c.want)
		}
	}
}

// IDs in folder and file names, in the styles Plex, Jellyfin and Kodi use.
// Their digits are never read as a year or an episode.
func TestIDTags(t *testing.T) {
	cases := []struct {
		rel, kind, title string
		year             *int
		tmdb, tvdb       int
		imdb             string
	}{
		{"The Office (UK) (2001) {tmdb-2996}/Season 01/S01E01.mkv", "tv", "The Office (UK)", new(2001), 2996, 0, ""},
		{"Andor [tmdbid-83867]/Season 01/Andor S01E01.mkv", "tv", "Andor", nil, 83867, 0, ""},
		{"Severance {tvdb-371980}/Season 01/Severance - S01E01.mkv", "tv", "Severance", nil, 0, 371980, ""},
		{"The Daily Show {imdb-tt0115147} {tvdb=71256}/The.Daily.Show.2024.10.28.mkv", "tv", "The Daily Show", nil, 0, 71256, "tt0115147"},
		{"Show {TMDB-1998}/Season 01/S01E01.mkv", "tv", "Show", nil, 1998, 0, ""},
		{"Show {tmdb-abc}/Season 01/S01E01.mkv", "tv", "Show", nil, 0, 0, ""}, // not an ID
		{"Dune (2021) {tmdb-438631}/Dune (2021) {tmdb-438631}.mkv", "movie", "Dune", new(2021), 438631, 0, ""},
		{"Collection/Heat (1995) [imdbid-tt0113277].mkv", "movie", "Heat", new(1995), 0, 0, "tt0113277"},
		{"Collection/Heat {tmdb-2001}.mkv", "movie", "Heat", nil, 2001, 0, ""},
	}
	for _, c := range cases {
		t.Run(c.rel, func(t *testing.T) {
			g := ParseEpisode(c.rel)
			if c.kind == "movie" {
				g = ParseMovie(c.rel)
			}
			if g.Title != c.title || deref(g.Year) != deref(c.year) || g.TMDBID != c.tmdb || g.TVDBID != c.tvdb || g.IMDBID != c.imdb {
				t.Errorf("got title %q year %v tmdb %d tvdb %d imdb %q", g.Title, deref(g.Year), g.TMDBID, g.TVDBID, g.IMDBID)
			}
		})
	}
	// An ID's digits do not make an undated folder a movie's own folder.
	if got := MatchFolder("movie", "Heat {tmdb-1995}/Heat.mkv"); got != "" {
		t.Errorf("MatchFolder = %q", got)
	}
}

// A download folder's date is not the show's year: "The Daily Show (2026)"
// would not match the show TMDB lists as starting in 1996.
func TestReleaseFolderYearIsNotTheShows(t *testing.T) {
	g := ParseEpisode("The.Daily.Show.2026.10.27.Guest.1080p.WEB.h264-EDITH/The.Daily.Show.2026.10.27.Guest.1080p.WEB.h264-EDITH.mkv")
	if g.Year != nil || g.Title != "The Daily Show" || g.AirDate != "2026-10-27" {
		t.Errorf("got title %q year %v date %q", g.Title, deref(g.Year), g.AirDate)
	}
}
