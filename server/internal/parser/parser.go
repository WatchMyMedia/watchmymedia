// Package parser turns file and folder names into a best guess of what a
// media file is. It is deliberately pure: no I/O, no metadata lookups, so it
// can be tested exhaustively with tables. The metadata matcher (phase 2)
// turns a Guess into a TMDB match.
package parser

import (
	"path"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode"

	"golang.org/x/text/unicode/norm"
)

type Kind string

const (
	KindMovie   Kind = "movie"
	KindEpisode Kind = "episode"
	KindUnknown Kind = "unknown"
)

// Guess is what the names alone tell us about a file.
type Guess struct {
	Kind Kind `json:"kind" enum:"movie,episode,unknown"`
	// Title is the movie title or the show name.
	Title string `json:"title"`
	// Year is the movie year, or the show's year when the show folder carries one.
	Year   *int `json:"year,omitempty"`
	Season *int `json:"season,omitempty"`
	// Episodes lists every episode number the file covers (multi-episode files).
	Episodes     []int  `json:"episodes,omitempty"`
	EpisodeTitle string `json:"episodeTitle,omitempty"`
	// AirDate is set for episodes named by date, as daily shows are
	// ("Show - 2024-10-28"), in YYYY-MM-DD form.
	AirDate string `json:"airDate,omitempty"`
	// AirDateAlt is the other reading of a date whose day and month could
	// be either way round ("05.06.2024"); the matcher keeps the one that aired.
	AirDateAlt string `json:"airDateAlt,omitempty"`
	// FileTitle and FileYear are what the file's own name says when Title
	// and Year come from its folder and it says something else: folder
	// "The Connors" holding "The.Conners.S01E02", or "Rivals (2023)" holding
	// "Rivals.2024.S01E01". The matcher tries them when the folder's name
	// finds nothing.
	FileTitle string `json:"fileTitle,omitempty"`
	FileYear  *int   `json:"fileYear,omitempty"`
	// IDs written in the show or movie folder name, or a movie's file name,
	// as Plex, Jellyfin and Kodi read them: "{tmdb-2224}", "[tmdbid-2224]",
	// "{imdb-tt0115147}", "{tvdb=71256}". They say exactly what the file is.
	TMDBID     int    `json:"tmdbId,omitempty"`
	TVDBID     int    `json:"tvdbId,omitempty"`
	IMDBID     string `json:"imdbId,omitempty"`
	Resolution string `json:"resolution,omitempty" example:"1080p"`
	Source     string `json:"source,omitempty" example:"BluRay"`
	// Tags are the remaining release tokens (codec, HDR, group) kept for display.
	Tags []string `json:"tags,omitempty"`
}

var videoExts = map[string]bool{
	".mkv": true, ".mp4": true, ".m4v": true, ".mov": true, ".avi": true, ".wmv": true,
	".ts": true, ".m2ts": true, ".mts": true, ".webm": true, ".flv": true, ".mpg": true,
	".mpeg": true, ".vob": true, ".ogm": true, ".ogv": true, ".divx": true,
}

// IsVideo reports whether the file name has a video extension.
func IsVideo(name string) bool {
	return videoExts[strings.ToLower(path.Ext(name))]
}

var ignoredDirs = map[string]bool{
	"extras": true, "featurettes": true, "behind the scenes": true, "deleted scenes": true,
	"interviews": true, "scenes": true, "shorts": true, "trailers": true, "other": true,
	"samples": true, "sample": true, "subs": true, "subtitles": true,
	"@eadir": true, "#recycle": true, ".recycle": true, "lost+found": true,
	// At the root of a Windows (or Windows-formatted) drive: the Recycle
	// Bin holds deleted videos, and the other cannot be read.
	"$recycle.bin": true, "system volume information": true,
}

// IgnoreDir reports whether a directory (by base name) holds no library
// content. Scans skip it entirely, apart from a Trailers folder, whose own
// files they read as trailers (SkipDir).
func IgnoreDir(name string) bool {
	if strings.HasPrefix(name, ".") || strings.HasPrefix(name, "_") {
		return true
	}
	return ignoredDirs[strings.ToLower(name)]
}

// Samples and trailers follow the "-sample" / "-trailer" suffix convention,
// or are a bare "sample" file; a title that merely starts with the word
// ("Trailer Park Boys") is content.
var ignoredFileRe = regexp.MustCompile(`(?i)(^sample$|^sample[ ._-]|[ ._-](sample|trailer)$)`)

// IgnoreFile reports whether a video file (by base name) is a sample, trailer
// or hidden file rather than library content. Scans still read trailers, as
// extras (ParseExtra); SkipFile is what they leave out.
func IgnoreFile(name string) bool {
	if strings.HasPrefix(name, ".") {
		return true
	}
	stem := strings.TrimSuffix(name, path.Ext(name))
	return ignoredFileRe.MatchString(stem)
}

var sampleRe = regexp.MustCompile(`(?i)(^sample$|^sample[ ._-]|[ ._-]sample$)`)

// SkipFile reports whether scans leave out a video file (by base name):
// hidden files and samples.
func SkipFile(name string) bool {
	return strings.HasPrefix(name, ".") || sampleRe.MatchString(stem(name))
}

// TrailersDir reports whether a folder (by base name) holds a movie's
// trailers.
func TrailersDir(name string) bool { return strings.EqualFold(name, "trailers") }

// SkipDir reports whether scans leave out the folder at rel, a path relative
// to the library root, and everything below it: folders IgnoreDir names, and
// what is inside them. A Trailers folder is read, but only its own files,
// which are trailers; its subfolders are left out.
func SkipDir(rel string) bool {
	parts := strings.Split(strings.Trim(filepathToSlash(rel), "/"), "/")
	if len(parts) == 1 && (parts[0] == "" || parts[0] == ".") {
		return false // the library root
	}
	last := len(parts) - 1
	if slices.ContainsFunc(parts[:last], IgnoreDir) {
		return true
	}
	return IgnoreDir(parts[last]) && !TrailersDir(parts[last])
}

// filepathToSlash turns Windows separators into slashes, so rel can be
// either form.
func filepathToSlash(rel string) string { return strings.ReplaceAll(rel, `\`, "/") }

// ExtraTrailer is a trailer's kind (media_files.extra_kind).
const ExtraTrailer = "trailer"

// Extra is what a file's name says about a file that belongs to a movie
// rather than being one (PLAN-TRAILERS.md §5.2).
type Extra struct {
	// Kind is ExtraTrailer.
	Kind string
	// Dir is the folder the extra's movie is in, relative to the library
	// root with forward slashes; empty at the root.
	Dir string
	// Of is the name, without its extension, of the movie file the extra
	// belongs to: "Heat (1995)" for "Heat (1995)-trailer.mkv". It is empty
	// when the extra belongs to the folder's only movie.
	Of string
}

// trailerOfRe is a trailer named after its movie's file:
// "Heat (1995)-trailer", also with ".trailer", "_trailer" or " trailer".
var trailerOfRe = regexp.MustCompile(`(?i)^(.*[^ ._-])[ ._-]+trailer$`)

// ParseExtra reports whether the video file at rel, a path relative to the
// library root, is an extra rather than library content, and whose, as
// Jellyfin, Plex, Emby and Kodi read trailers:
//   - "<movie file name>-trailer.ext" (or ".trailer", "_trailer",
//     " trailer") next to the movie;
//   - "trailer.ext" or "movie-trailer.ext" in the movie's own folder;
//   - any video in a Trailers folder in the movie's folder.
func ParseExtra(rel string) (Extra, bool) {
	parts := strings.Split(strings.Trim(filepathToSlash(rel), "/"), "/")
	name := stem(parts[len(parts)-1])
	dir := strings.Join(parts[:len(parts)-1], "/")
	if len(parts) >= 2 && TrailersDir(parts[len(parts)-2]) {
		return Extra{Kind: ExtraTrailer, Dir: strings.Join(parts[:len(parts)-2], "/")}, true
	}
	switch strings.ToLower(strings.Trim(name, " ._-")) {
	case "trailer", "movie-trailer":
		return Extra{Kind: ExtraTrailer, Dir: dir}, true
	}
	if m := trailerOfRe.FindStringSubmatch(name); m != nil {
		return Extra{Kind: ExtraTrailer, Dir: dir, Of: m[1]}, true
	}
	return Extra{}, false
}

// ParseMovie guesses a movie from a path relative to the movie library root
// (forward slashes). It prefers the parent folder's name when that folder
// carries a year, since folders are usually cleaner than release file names.
func ParseMovie(rel string) Guess {
	parts := strings.Split(strings.Trim(norm.NFC.String(rel), "/"), "/")
	base := stem(parts[len(parts)-1])
	parent := ""
	if len(parts) >= 2 {
		parent = parts[len(parts)-2]
	}
	g := Guess{Kind: KindMovie}
	readIDs(&g, parent, base)
	base, parent = spaced(stripIDs(base)), spaced(stripIDs(parent))
	fromFolder := yearRe.FindStringIndex(parent) != nil
	candidate := base
	if fromFolder {
		candidate = parent
	}
	title, year, rest := splitMovieTitle(candidate)
	g.Resolution, g.Source, g.Tags = releaseTags(rest)
	g.Title, g.Year = title, year
	if fromFolder {
		ft, fy, _ := splitMovieTitle(base)
		g.FileTitle, g.FileYear = otherName(title, year, ft, fy)
	}
	return g
}

// splitMovieTitle is splitTitleYear for a movie's name, leaving out an
// edition ("Unrated Director's Cut") and a year written twice
// ("See.How.They.Run.2022.2022").
func splitMovieTitle(s string) (title string, year *int, rest string) {
	title, year, rest = splitTitleYear(s)
	if title == "" {
		title = cleanTitle(s)
	}
	if year != nil {
		title = strings.TrimSuffix(title, " "+strconv.Itoa(*year))
	}
	if t := editionRe.ReplaceAllString(title, ""); t != "" {
		title = t
	}
	return title, year, rest
}

// otherName returns a file's own title and year for FileTitle and
// FileYear, each only when it says something its folder does not.
func otherName(title string, year *int, fileTitle string, fileYear *int) (string, *int) {
	if squash(fileTitle) == squash(title) {
		fileTitle = ""
	}
	if fileYear != nil && year != nil && *fileYear == *year {
		fileYear = nil
	}
	return fileTitle, fileYear
}

// squash keeps only the letters and digits of a name, lower-cased, to tell
// whether two names are the same apart from punctuation and case.
func squash(s string) string {
	return strings.Map(func(r rune) rune {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			return unicode.ToLower(r)
		}
		return -1
	}, s)
}

// spaced turns underscores into spaces, so "_S04E01" and "_2005_" have the
// word boundaries the patterns look for.
func spaced(s string) string { return strings.ReplaceAll(s, "_", " ") }

// idTagRe finds an ID in a name: "{tmdb-438631}", "[tmdbid-438631]",
// "(imdbid=tt0133093)", "{tvdb-81189}", in any bracket and either case.
var idTagRe = regexp.MustCompile(`(?i)[\[({]\s*(tmdb|tvdb|imdb)(?:id)?\s*[-=]\s*([a-z0-9]+)\s*[\])}]`)

var imdbRe = regexp.MustCompile(`(?i)^tt\d{6,10}$`)

// readIDs fills in the IDs written in the names, the first name first.
func readIDs(g *Guess, names ...string) {
	for _, name := range names {
		for _, m := range idTagRe.FindAllStringSubmatch(name, -1) {
			source, value := strings.ToLower(m[1]), m[2]
			n, err := strconv.Atoi(value)
			switch {
			case source == "tmdb" && err == nil && n > 0 && g.TMDBID == 0:
				g.TMDBID = n
			case source == "tvdb" && err == nil && n > 0 && g.TVDBID == 0:
				g.TVDBID = n
			case source == "imdb" && imdbRe.MatchString(value) && g.IMDBID == "":
				g.IMDBID = strings.ToLower(value)
			}
		}
	}
}

// stripIDs removes ID tags from a name, so their digits are not read as a
// year or an episode.
func stripIDs(name string) string {
	return strings.TrimSpace(idTagRe.ReplaceAllString(name, " "))
}

// MatchFolder is the folder that names a file's movie or show, relative to
// the library root: the top-level folder for TV, and for movies the parent
// folder when it carries a year (the same folders the parsers read titles
// from). It is empty for files whose name alone says what they are, such as
// files at the library root or in an undated collection folder.
func MatchFolder(libraryType, rel string) string {
	parts := strings.Split(strings.Trim(rel, "/"), "/")
	if len(parts) < 2 {
		return ""
	}
	if libraryType == "tv" {
		if isReleaseName(parts[0]) {
			return "" // one episode's download folder, not the show's
		}
		return parts[0]
	}
	if yearRe.FindStringIndex(stripIDs(parts[len(parts)-2])) == nil {
		return ""
	}
	return strings.Join(parts[:len(parts)-1], "/")
}

var (
	// S01E02, S01E02E03, S01E02-E03, s1e2, and year seasons as daily shows
	// are numbered on TheTVDB: S2024E123
	seRe = regexp.MustCompile(`(?i)\bS(\d{1,2}|(?:19|20)\d{2})[ ._-]?E(\d{1,3})((?:[ ._-]?-?E\d{1,3})*)`)
	// 1x02
	xRe = regexp.MustCompile(`(?i)\b(\d{1,2})x(\d{2,3})\b`)
	// Season 01, Series 1, S01, Season 2024, Specials
	seasonDirRe = regexp.MustCompile(`(?i)^(?:season|series|s)[ ._-]*(\d{1,3}|(?:19|20)\d{2})$`)
	// 2024-10-28, 2024.10.28, 2024 10 28: an air date
	dateRe = regexp.MustCompile(`\b((?:19|20)\d{2})[-. _](\d{1,2})[-. _](\d{1,2})\b`)
	// 20241028
	compactDateRe = regexp.MustCompile(`\b((?:19|20)\d{2})(\d{2})(\d{2})\b`)
	// 28.10.2024 or 10.28.2024: an air date with the year last
	yearLastDateRe = regexp.MustCompile(`\b(\d{2})[-. _](\d{2})[-. _]((?:19|20)\d{2})\b`)
	// A bare leading episode number inside a season folder: "03 - Title.mkv"
	leadingEpRe = regexp.MustCompile(`^(\d{1,3})\b`)
	eNumRe      = regexp.MustCompile(`(?i)E(\d{1,3})`)
	// Season 1 Episode 01, Season.3.Ep.2
	seasonEpRe = regexp.MustCompile(`(?i)\bseason[ ._-]*(\d{1,3})[ ._-]*(?:episode|ep|e)[ ._-]*(\d{1,3})\b`)
	// E01, Ep 5, Episode.11 with no season: a miniseries' only season
	bareEpRe = regexp.MustCompile(`(?i)(?:^|[ ._-])(?:episode|ep|e)[ ._-]?(\d{1,3})(?:$|[ ._-])`)
	// Part2, Part.3-Part.4, Pt 1: a miniseries' parts
	partRe = regexp.MustCompile(`(?i)(?:^|[ ._-])(?:part|pt)[ ._-]?(\d{1,2})(?:[ ._]*-[ ._]*(?:part|pt)?[ ._]?(\d{1,2}))?(?:$|[ ._-])`)
)

// ParseEpisode guesses show, season and episode(s) from a path relative to
// the TV library root: "Show (2008)/Season 01/Show - S01E01 - Title.mkv".
func ParseEpisode(rel string) Guess {
	parts := strings.Split(strings.Trim(norm.NFC.String(rel), "/"), "/")
	file := spaced(stripIDs(stem(parts[len(parts)-1])))
	g := Guess{Kind: KindUnknown}

	// Show name, year and IDs from the top-level folder when there is one,
	// unless that folder is one episode's download
	// ("Show.2024.10.28.Guest.1080p-GRP/"), whose numbers are not the
	// show's year; the file name says the same.
	fromFolder := len(parts) >= 2 && !isReleaseName(parts[0])
	if fromFolder {
		readIDs(&g, parts[0])
		show := stripIDs(parts[0])
		title, year, _ := splitTitleYear(show)
		if title == "" || (year == nil && plainName(show)) {
			title = cleanTitle(show)
		}
		g.Title, g.Year = title, year
	}
	// Season from an intermediate folder.
	var seasonFromDir *int
	for _, dir := range parts[1:max(len(parts)-1, 1)] {
		if strings.EqualFold(dir, "specials") {
			seasonFromDir = new(0)
		} else if m := seasonDirRe.FindStringSubmatch(dir); m != nil {
			seasonFromDir = atoiPtr(m[1])
		}
	}

	var before, after string
	if m := seRe.FindStringSubmatchIndex(file); m != nil {
		g.Season = atoiPtr(file[m[2]:m[3]])
		g.Episodes = []int{atoi(file[m[4]:m[5]])}
		if m[6] >= 0 {
			g.Episodes = expandEpisodes(g.Episodes[0], file[m[6]:m[7]])
		}
		before, after = file[:m[0]], file[m[1]:]
	} else if m := xRe.FindStringSubmatchIndex(file); m != nil {
		g.Season = atoiPtr(file[m[2]:m[3]])
		g.Episodes = []int{atoi(file[m[4]:m[5]])}
		before, after = file[:m[0]], file[m[1]:]
	} else if m := seasonEpRe.FindStringSubmatchIndex(file); m != nil {
		g.Season = atoiPtr(file[m[2]:m[3]])
		g.Episodes = []int{atoi(file[m[4]:m[5]])}
		before, after = file[:m[0]], file[m[1]:]
	} else if date, alt, loc := findDate(file); date != "" {
		g.AirDate, g.AirDateAlt = date, alt
		before, after = file[:loc[0]], file[loc[1]:]
	} else if m := leadingEpRe.FindStringSubmatchIndex(file); m != nil && seasonFromDir != nil {
		g.Episodes = []int{atoi(file[m[2]:m[3]])}
		before, after = "", file[m[1]:]
	} else if m := bareEpRe.FindStringSubmatchIndex(file); m != nil {
		g.Episodes = []int{atoi(file[m[2]:m[3]])}
		before, after = file[:m[0]], file[m[1]:]
	} else if m := partRe.FindStringSubmatchIndex(file); m != nil {
		g.Episodes = []int{atoi(file[m[2]:m[3]])}
		if m[4] >= 0 {
			g.Episodes = partRange(g.Episodes[0], atoi(file[m[4]:m[5]]))
		}
		before, after = file[:m[0]], file[m[1]:]
	}

	if g.Season == nil && seasonFromDir != nil {
		g.Season = seasonFromDir
	}
	if g.Season == nil && len(g.Episodes) > 0 {
		g.Season = new(1) // "E01" or "Part 2" alone: a miniseries' only season
	}
	if len(g.Episodes) > 0 || g.AirDate != "" {
		g.Kind = KindEpisode
	}
	if before != "" {
		title, year, _ := splitTitleYear(before)
		if title == "" {
			title = cleanTitle(before)
		}
		if g.Title == "" {
			g.Title, g.Year = title, year
		} else {
			g.FileTitle, g.FileYear = otherName(g.Title, g.Year, title, year)
		}
	}
	if g.Title == "" {
		g.Title = cleanTitle(file)
	}
	if after != "" {
		title, res, src, tags := splitEpisodeTitle(after)
		g.EpisodeTitle, g.Resolution, g.Source, g.Tags = title, res, src, tags
	} else if g.Kind == KindUnknown {
		_, g.Resolution, g.Source, g.Tags = splitEpisodeTitle(file)
	}
	return g
}

// isReleaseName reports whether a folder is named like an episode release
// (it carries S01E02, 1x02 or an air date) rather than after a show.
func isReleaseName(name string) bool {
	name = spaced(name)
	if seRe.MatchString(name) || xRe.MatchString(name) {
		return true
	}
	date, _, _ := findDate(name)
	return date != ""
}

// findDate finds an air date in a name. A year first is read as
// year-month-day ("2024.10.28", "20241028"). A year last can be day first,
// as Plex and Jellyfin expect ("28.10.2024"), or month first, as Kodi reads
// it ("10.28.2024"); when both readings are real dates, the day-first one
// is the date and alt the other. loc is where the date is in the name.
func findDate(s string) (date, alt string, loc []int) {
	if m := dateRe.FindStringSubmatchIndex(s); m != nil {
		y, a, b := s[m[2]:m[3]], s[m[4]:m[5]], s[m[6]:m[7]]
		if d := validDate(y, a, b); d != "" {
			return d, "", m[:2]
		}
		if d := validDate(y, b, a); d != "" { // 2024.28.10
			return d, "", m[:2]
		}
	}
	if m := compactDateRe.FindStringSubmatchIndex(s); m != nil {
		if d := validDate(s[m[2]:m[3]], s[m[4]:m[5]], s[m[6]:m[7]]); d != "" {
			return d, "", m[:2]
		}
	}
	if m := yearLastDateRe.FindStringSubmatchIndex(s); m != nil {
		a, b, y := s[m[2]:m[3]], s[m[4]:m[5]], s[m[6]:m[7]]
		dayFirst, monthFirst := validDate(y, b, a), validDate(y, a, b)
		switch {
		case dayFirst != "" && monthFirst != "" && dayFirst != monthFirst:
			return dayFirst, monthFirst, m[:2]
		case dayFirst != "":
			return dayFirst, "", m[:2]
		case monthFirst != "":
			return monthFirst, "", m[:2]
		}
	}
	return "", "", nil
}

// validDate returns the date in YYYY-MM-DD form, or "" when it is not a real date.
func validDate(y, m, d string) string {
	t, err := time.Parse("2006-1-2", y+"-"+m+"-"+d)
	if err != nil {
		return ""
	}
	return t.Format("2006-01-02")
}

// maxEpisodeRange is the longest range a multi-episode name may cover, as
// in Kodi; "S01E01-E99" is more likely a typo than 99 episodes in a file.
const maxEpisodeRange = 50

// expandEpisodes turns the trailing "E02E03" / "-E04" part of a match into
// a full episode list, expanding ranges written with a dash. Numbers must
// go up; at the first that does not ("E05-E02"), or a range that is too
// long, the rest is ignored.
func expandEpisodes(first int, extra string) []int {
	eps := []int{first}
	for i, seg := range strings.Split(extra, "-") {
		for _, m := range eNumRe.FindAllStringSubmatch(seg, -1) {
			n, last := atoi(m[1]), eps[len(eps)-1]
			if n == last {
				continue
			}
			if n < last || (i > 0 && n-last > maxEpisodeRange) {
				return eps
			}
			if i > 0 {
				for k := last + 1; k < n; k++ {
					eps = append(eps, k)
				}
			}
			eps = append(eps, n)
		}
	}
	return eps
}

// partRange lists the parts a "Part.3-Part.4" file covers, or only the
// first when the range runs backwards or is too long.
func partRange(first, last int) []int {
	eps := []int{first}
	if last <= first || last-first > maxEpisodeRange {
		return eps
	}
	for n := first + 1; n <= last; n++ {
		eps = append(eps, n)
	}
	return eps
}

func splitEpisodeTitle(s string) (title, res, src string, tags []string) {
	s = strings.TrimLeft(s, " ._-")
	cut := len(s)
	if loc := firstTagIndex(s); loc >= 0 {
		cut = loc
	}
	title = cleanTitle(s[:cut])
	res, src, tags = releaseTags(s[cut:])
	return
}

// --- shared helpers --------------------------------------------------------

var yearRe = regexp.MustCompile(`\b((?:19|20)\d{2})\b`)

// splitTitleYear finds the last plausible year in s and returns the cleaned
// text before it as the title, the year, and the raw text after it.
func splitTitleYear(s string) (title string, year *int, rest string) {
	maxYear := time.Now().Year() + 1
	matches := yearRe.FindAllStringSubmatchIndex(s, -1)
	for _, m := range slices.Backward(matches) {

		y := atoi(s[m[2]:m[3]])
		if y < 1900 || y > maxYear {
			continue
		}
		if len(matches) == 1 && cleanTitle(s[:m[0]]) == "" {
			// A lone year with no title before it is the title ("2012.1080p",
			// a show folder named "1923"), not a year. "1917 (2019)" still
			// works because it matches twice.
			continue
		}
		title = cleanTitle(s[:m[0]])
		return title, &y, s[m[1]:]
	}
	// No year: the title stops at the first release tag.
	if loc := firstTagIndex(s); loc >= 0 {
		return cleanTitle(s[:loc]), nil, s[loc:]
	}
	return cleanTitle(s), nil, ""
}

var (
	resolutionRe = regexp.MustCompile(`(?i)\b(2160p|1080p|1080i|720p|576p|480p|4k|uhd)\b`)
	sourceRe     = regexp.MustCompile(`(?i)\b(blu-?ray|bdrip|brrip|bdremux|remux|web-?dl|webrip|web|hdtv|dvdrip|dvd|hdrip|cam|ts|telesync|hdcam)\b`)
	tagRe        = regexp.MustCompile(`(?i)\b(x264|x265|h\.?264|h\.?265|hevc|avc|av1|xvid|divx|10bit|8bit|hdr10\+?|hdr|dv|dovi|dolby[ .]?vision|sdr|aac|ac3|eac3|dd5\.1|ddp5\.1|dts(?:-?hd)?(?:[ .]?ma)?|truehd|atmos|flac|proper|repack|extended|unrated|remastered|imax|multi|dual|dubbed|subbed|amzn|nf|dsnp|hmax|atvp|internal|limited|complete)\b`)
	groupRe      = regexp.MustCompile(`-([A-Za-z0-9]+)$`)
	bracketRe    = regexp.MustCompile(`[\[({]([^\])}]*)[\])}]`)
	// A short upper-case code in brackets is a country disambiguator worth
	// keeping: "The Office (US)" and "The Office (UK)" are different shows.
	countryRe = regexp.MustCompile(`^[A-Z]{2,3}$`)
	// Release tokens that are never words of a title, unlike "web" or "ts".
	strongTagRe = regexp.MustCompile(`(?i)\b(2160p|1080p|1080i|720p|576p|480p|x26[45]|h\.?26[45]|hevc|xvid|blu-?ray|web-?dl|webrip|hdtv|bdrip|brrip|remux)\b`)
	// An edition written after a movie's title: "Unrated Director's Cut",
	// "The Ultimate Cut". Words that are titles on their own ("The
	// International") count only with cut, edition or version after them.
	editionRe = regexp.MustCompile(`(?i)(?:\s+the)?(?:\s+(?:unrated|uncut|remastered|(?:theatrical|international|ultimate|final|special|extended|directors|director'?s|collectors|collector'?s|imax)\s+(?:cut|edition|version)))+$`)
)

// plainName reports whether a folder name reads as a person wrote it
// ("E.B. White's Charlotte's Web") rather than as a release, so that words
// such as "Web" in it are part of the title, not release tags.
func plainName(s string) bool {
	return strings.Contains(s, " ") && !strongTagRe.MatchString(s)
}

// firstTagIndex returns the index where release tags begin, or -1.
func firstTagIndex(s string) int {
	idx := -1
	for _, re := range []*regexp.Regexp{resolutionRe, sourceRe, tagRe} {
		for _, loc := range re.FindAllStringIndex(s, -1) {
			// "web" and "ts" are common words; only treat them as tags when
			// something tag-like follows or a separator precedes them.
			if loc[0] > 0 && !strings.ContainsRune(" ._-([", rune(s[loc[0]-1])) {
				continue
			}
			if idx < 0 || loc[0] < idx {
				idx = loc[0]
			}
			break
		}
	}
	return idx
}

// releaseTags extracts resolution, source and leftover tags from a release string.
func releaseTags(s string) (res, src string, tags []string) {
	if m := resolutionRe.FindString(s); m != "" {
		res = normalizeResolution(m)
	}
	if m := sourceRe.FindString(s); m != "" {
		src = normalizeSource(m)
	}
	for _, m := range tagRe.FindAllString(s, -1) {
		tags = append(tags, strings.ToUpper(strings.ReplaceAll(m, ".", "")))
	}
	if m := groupRe.FindStringSubmatch(strings.TrimSpace(s)); m != nil && !isKnownToken(m[1]) {
		tags = append(tags, "group:"+m[1])
	}
	return
}

func isKnownToken(s string) bool {
	return resolutionRe.MatchString(s) || sourceRe.MatchString(s) || tagRe.MatchString(s) || yearRe.MatchString(" "+s+" ")
}

func normalizeResolution(s string) string {
	s = strings.ToLower(s)
	switch s {
	case "4k", "uhd":
		return "2160p"
	}
	return s
}

func normalizeSource(s string) string {
	switch l := strings.ToLower(strings.ReplaceAll(s, "-", "")); l {
	case "bluray", "bdrip", "brrip":
		return "BluRay"
	case "bdremux", "remux":
		return "Remux"
	case "webdl", "web":
		return "WEB-DL"
	case "webrip":
		return "WEBRip"
	case "hdtv":
		return "HDTV"
	case "dvdrip", "dvd":
		return "DVD"
	case "hdrip":
		return "HDRip"
	case "cam", "ts", "telesync", "hdcam":
		return "CAM"
	}
	return s
}

// cleanTitle normalises separators and strips release noise from a title.
func cleanTitle(s string) string {
	s = bracketRe.ReplaceAllStringFunc(s, func(m string) string {
		if inner := bracketRe.FindStringSubmatch(m)[1]; countryRe.MatchString(inner) {
			return " (" + inner + ")"
		}
		return " "
	})
	if !strings.Contains(s, " ") {
		s = strings.ReplaceAll(s, ".", " ")
	}
	s = strings.ReplaceAll(s, "_", " ")
	s = strings.Join(strings.Fields(s), " ")
	s = strings.Trim(s, " -([")
	return s
}

func stem(name string) string { return strings.TrimSuffix(name, path.Ext(name)) }

func atoi(s string) int { n, _ := strconv.Atoi(s); return n }

func atoiPtr(s string) *int { n := atoi(s); return &n }
