// Package xmltv reads SiliconDust's XMLTV listings feed
// (PLAN-LIVE-TV-GUIDE.md §5.2, §7.1). It streams the feed, keeping only the
// channels and window asked for, and resolves no entities: a feed that
// defines its own fails, and nothing outside the feed is ever read.
package xmltv

import (
	"bytes"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net/url"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"golang.org/x/text/unicode/norm"
)

const (
	// maxLength is the longest a programme may run.
	maxLength = 24 * time.Hour
	// maxTitle is the longest title kept, in characters.
	maxTitle = 300
	// maxDescription is the longest description kept, in characters.
	maxDescription = 4000
	// minLeft is how much of an overlapped programme must be left for it to
	// be kept.
	minLeft = time.Minute
)

// Channel is a <channel> in the feed.
type Channel struct {
	ID    string   // the feed's id, e.g. "US12345.hdhomerun.com"
	Names []string // <display-name>s in feed order, cleaned, blanks dropped
	LCN   string   // <lcn>, the channel number, e.g. "7.2"
	Logo  string   // the first <icon src>, "" when none
}

// Programme is a kept <programme>.
type Programme struct {
	Channel               string    // the feed's channel id
	Start, Stop           time.Time // UTC
	Title                 string
	EpisodeTitle          string   // <sub-title>
	Season                *int     // 1-based; a season numbered by year is that year (S2026E14 -> 2026)
	Episode               *int     // 1-based
	EpisodeLabel          string   // the onscreen episode-num text when it isn't SxxEyy and there is no xmltv_ns
	Kind                  string   // "episode" (dd_progid EP…), "show" (SH…), "movie" (MV…) or ""
	SeriesKey             string   // <series-id> text (system="cseries" C… id, or a movie's MV… id); else the dd_progid before its "."
	AirDate               string   // <date>: YYYYMMDD -> "YYYY-MM-DD", YYYY -> "YYYY", anything else -> ""
	Categories            []string // <category>s, cleaned, de-duplicated, in order
	New, Live, Repeat, HD bool     // <new/>, <live/>, <previously-shown/>, <video><quality>HDTV (or HD…)</quality>
	Rating                string   // the first <rating><value>, "" when none
	Description           string   // the first <desc>, cleaned and capped at 4,000 characters
	Picture               string   // the first <icon src> whose path isn't under /posters/
	Poster                string   // the first <icon src> under /posters/
}

// Options bound what Parse keeps.
type Options struct {
	// Keep says whether a channel's programmes are kept; nil keeps every channel.
	Keep func(Channel) bool
	// From and To bound the programmes kept: those ending after From and
	// starting before To. A zero bound is no bound.
	From, To time.Time
}

// Feed is what Parse read.
type Feed struct {
	Channels   []Channel   // every channel in the feed, in feed order
	Programmes []Programme // kept programmes, by channel in feed order, then start
	Overlaps   int         // programmes moved to start when the one before ended
	Dropped    int         // programmes left out as invalid or almost entirely overlapped
	Unknown    int         // programmes for channels not in the feed or not kept
}

// Parse reads a whole feed. It fails, keeping nothing, on XML it can't read,
// including a feed cut off part-way and undefined entities.
func Parse(r io.Reader, opt Options) (Feed, error) {
	d := xml.NewDecoder(r)
	d.Strict = true
	d.CharsetReader = charsetReader
	p := parser{opt: opt, seen: map[string]bool{}, kept: map[string]int{}}
	if err := p.read(d); err != nil {
		return Feed{}, fmt.Errorf("xmltv: %w", err)
	}
	return p.finish(), nil
}

// charsetReader accepts US-ASCII, which is UTF-8 already; the decoder reads
// UTF-8 itself and refuses every other encoding.
func charsetReader(label string, r io.Reader) (io.Reader, error) {
	switch strings.ToLower(label) {
	case "us-ascii", "ascii":
		return r, nil
	}
	return nil, fmt.Errorf("unsupported encoding %q", label)
}

type parser struct {
	opt      Options
	channels []Channel
	seen     map[string]bool
	kept     map[string]int // channel id to its index in lists
	lists    [][]Programme  // each kept channel's programmes, in feed order
	dropped  int
	unknown  int
}

// read reads the document: one <tv> element, with nothing but comments,
// processing instructions, a DOCTYPE (not processed) and white space around it.
func (p *parser) read(d *xml.Decoder) error {
	root := false
	for {
		tok, err := d.Token()
		if errors.Is(err, io.EOF) {
			if !root {
				return errors.New("no <tv> element")
			}
			return nil
		}
		if err != nil {
			return err
		}
		switch t := tok.(type) {
		case xml.StartElement:
			if root {
				return fmt.Errorf("<%s> after </tv>", t.Name.Local)
			}
			if t.Name.Local != "tv" {
				return fmt.Errorf("<%s> where <tv> should be", t.Name.Local)
			}
			if err := p.readTV(d); err != nil {
				return err
			}
			root = true
		case xml.CharData:
			if len(bytes.TrimSpace(t)) > 0 {
				return errors.New("text outside <tv>")
			}
		}
	}
}

// readTV reads <tv>'s children up to its end.
func (p *parser) readTV(d *xml.Decoder) error {
	for {
		tok, err := d.Token()
		if errors.Is(err, io.EOF) {
			return io.ErrUnexpectedEOF
		}
		if err != nil {
			return err
		}
		switch t := tok.(type) {
		case xml.StartElement:
			switch t.Name.Local {
			case "channel":
				var c rawChannel
				if err := d.DecodeElement(&c, &t); err != nil {
					return err
				}
				p.channel(c)
			case "programme":
				var pr rawProgramme
				if err := d.DecodeElement(&pr, &t); err != nil {
					return err
				}
				p.programme(pr)
			default:
				if err := d.Skip(); err != nil {
					return err
				}
			}
		case xml.EndElement:
			return nil
		}
	}
}

type rawIcon struct {
	Src string `xml:"src,attr"`
}

type rawChannel struct {
	ID    string    `xml:"id,attr"`
	Names []string  `xml:"display-name"`
	LCN   []string  `xml:"lcn"`
	Icons []rawIcon `xml:"icon"`
}

type rawSystem struct {
	System string `xml:"system,attr"`
	Value  string `xml:",chardata"`
}

type rawVideo struct {
	Quality []string `xml:"quality"`
}

type rawRating struct {
	Values []string `xml:"value"`
}

type rawProgramme struct {
	Start       string      `xml:"start,attr"`
	Stop        string      `xml:"stop,attr"`
	Channel     string      `xml:"channel,attr"`
	Titles      []string    `xml:"title"`
	SubTitles   []string    `xml:"sub-title"`
	Descs       []string    `xml:"desc"`
	Dates       []string    `xml:"date"`
	Categories  []string    `xml:"category"`
	Icons       []rawIcon   `xml:"icon"`
	EpisodeNums []rawSystem `xml:"episode-num"`
	SeriesIDs   []rawSystem `xml:"series-id"`
	Video       []rawVideo  `xml:"video"`
	Ratings     []rawRating `xml:"rating"`
	New         *struct{}   `xml:"new"`
	Live        *struct{}   `xml:"live"`
	Repeat      *struct{}   `xml:"previously-shown"`
}

// channel records a channel. A second channel with an id already seen, or
// with no id, is ignored.
func (p *parser) channel(raw rawChannel) {
	c := Channel{ID: clean(raw.ID), LCN: firstText(raw.LCN), Logo: firstIcon(raw.Icons, func(string) bool { return true })}
	if c.ID == "" || p.seen[c.ID] {
		return
	}
	for _, n := range raw.Names {
		if n = clean(n); n != "" {
			c.Names = append(c.Names, n)
		}
	}
	p.seen[c.ID] = true
	p.channels = append(p.channels, c)
	if p.opt.Keep == nil || p.opt.Keep(c) {
		p.kept[c.ID] = len(p.lists)
		p.lists = append(p.lists, nil)
	}
}

// programme keeps a programme if its channel is kept, it is valid and it is
// in the window.
func (p *parser) programme(raw rawProgramme) {
	i, ok := p.kept[clean(raw.Channel)]
	if !ok {
		p.unknown++
		return
	}
	prog, ok := build(raw)
	if !ok {
		p.dropped++
		return
	}
	if !p.inWindow(prog) {
		return
	}
	p.lists[i] = append(p.lists[i], prog)
}

func (p *parser) inWindow(prog Programme) bool {
	return (p.opt.From.IsZero() || prog.Stop.After(p.opt.From)) &&
		(p.opt.To.IsZero() || prog.Start.Before(p.opt.To))
}

// finish orders each channel's programmes and settles overlaps: a programme
// that starts before the one before it ends starts when that one ends, if a
// minute of it is left, and is dropped otherwise. Gaps stay.
func (p *parser) finish() Feed {
	f := Feed{Channels: p.channels, Dropped: p.dropped, Unknown: p.unknown}
	for _, list := range p.lists {
		slices.SortStableFunc(list, func(a, b Programme) int { return a.Start.Compare(b.Start) })
		var last time.Time
		for _, prog := range list {
			if !last.IsZero() && prog.Start.Before(last) {
				if prog.Stop.Sub(last) < minLeft {
					f.Dropped++
					continue
				}
				prog.Start = last
				if !p.inWindow(prog) {
					continue
				}
				f.Overlaps++
			}
			f.Programmes = append(f.Programmes, prog)
			last = prog.Stop
		}
	}
	return f
}

// build makes a Programme of a <programme>, or says it is invalid: no
// readable start before its stop, longer than a day, or no title or a title
// over 300 characters.
func build(raw rawProgramme) (Programme, bool) {
	start, ok1 := parseTime(raw.Start)
	stop, ok2 := parseTime(raw.Stop)
	if !ok1 || !ok2 || !start.Before(stop) || stop.Sub(start) > maxLength {
		return Programme{}, false
	}
	title := firstText(raw.Titles)
	if title == "" || utf8.RuneCountInString(title) > maxTitle {
		return Programme{}, false
	}
	prog := Programme{
		Channel:      clean(raw.Channel),
		Start:        start,
		Stop:         stop,
		Title:        title,
		EpisodeTitle: firstText(raw.SubTitles),
		AirDate:      airDate(raw.Dates),
		Categories:   categories(raw.Categories),
		New:          raw.New != nil,
		Live:         raw.Live != nil,
		Repeat:       raw.Repeat != nil,
		HD:           hd(raw.Video),
		Rating:       rating(raw.Ratings),
		Description:  capDescription(firstText(raw.Descs)),
		Picture:      firstIcon(raw.Icons, func(path string) bool { return !strings.HasPrefix(path, "/posters/") }),
		Poster:       firstIcon(raw.Icons, func(path string) bool { return strings.HasPrefix(path, "/posters/") }),
	}
	episodeNumbers(&prog, raw.EpisodeNums)
	prog.SeriesKey = seriesKey(raw.SeriesIDs, raw.EpisodeNums)
	return prog, true
}

// parseTime reads an XMLTV time, 20261102120000 +0000: to the second or the
// minute, with an offset or without one (UTC).
func parseTime(s string) (time.Time, bool) {
	s = strings.TrimSpace(s)
	stamp, offset := s, ""
	if i := strings.IndexAny(s, "+-"); i >= 0 {
		stamp, offset = strings.TrimSpace(s[:i]), s[i:]
	}
	var layout string
	switch len(stamp) {
	case len("20060102150405"):
		layout = "20060102150405"
	case len("200601021504"):
		layout = "200601021504"
	default:
		return time.Time{}, false
	}
	if strings.Trim(stamp, "0123456789") != "" {
		return time.Time{}, false
	}
	if offset != "" {
		layout += " -0700"
		stamp += " " + offset
	}
	t, err := time.Parse(layout, stamp)
	if err != nil {
		return time.Time{}, false
	}
	return t.UTC(), true
}

var onscreenRe = regexp.MustCompile(`(?i)^S(\d{1,4}) ?E(\d{1,5})$`)

// episodeNumbers fills the season, episode, label and kind. An onscreen
// SxxEyy wins over xmltv_ns, which counts from zero; G0 found they always
// agree when both are there.
func episodeNumbers(prog *Programme, nums []rawSystem) {
	onscreen, ns, progid := "", "", ""
	for _, n := range nums {
		v := clean(n.Value)
		switch strings.ToLower(clean(n.System)) {
		case "onscreen":
			if onscreen == "" {
				onscreen = v
			}
		case "xmltv_ns":
			if ns == "" {
				ns = v
			}
		case "dd_progid":
			if progid == "" {
				progid = v
			}
		}
	}
	if m := onscreenRe.FindStringSubmatch(onscreen); m != nil {
		prog.Season, prog.Episode = number(m[1], 0), number(m[2], 0)
	} else if season, episode, ok := parseNS(ns); ok {
		prog.Season, prog.Episode = season, episode
	} else {
		prog.EpisodeLabel = onscreen
	}
	switch strings.ToUpper(prefix(progid, 2)) {
	case "EP":
		prog.Kind = "episode"
	case "SH":
		prog.Kind = "show"
	case "MV":
		prog.Kind = "movie"
	}
}

// parseNS reads xmltv_ns, "season.episode.part", each counted from zero,
// any of them empty and any with a "/total". It is usable when it gives a
// season or an episode and nothing in it is unreadable.
func parseNS(s string) (season, episode *int, ok bool) {
	parts := strings.Split(s, ".")
	if len(parts) < 2 || len(parts) > 3 {
		return nil, nil, false
	}
	var bad bool
	part := func(s string) *int {
		s, _, _ = strings.Cut(s, "/")
		if s = strings.TrimSpace(s); s == "" {
			return nil
		}
		n := number(s, 1)
		if n == nil {
			bad = true
		}
		return n
	}
	season, episode = part(parts[0]), part(parts[1])
	if bad || (season == nil && episode == nil) {
		return nil, nil, false
	}
	return season, episode, true
}

// number reads a small whole number and adds add, or gives nil.
func number(s string, add int) *int {
	n, err := strconv.Atoi(s)
	if err != nil || n < 0 || n > 99999 {
		return nil
	}
	n += add
	return &n
}

// seriesKey is the cseries id, else a <series-id> without a system (a
// movie's MV… id), else the dd_progid before its ".".
func seriesKey(ids, nums []rawSystem) string {
	var plain string
	for _, id := range ids {
		v := clean(id.Value)
		if v == "" {
			continue
		}
		switch strings.ToLower(clean(id.System)) {
		case "cseries":
			return v
		case "":
			if plain == "" {
				plain = v
			}
		}
	}
	if plain != "" {
		return plain
	}
	for _, n := range nums {
		if strings.EqualFold(clean(n.System), "dd_progid") {
			if v := clean(n.Value); v != "" {
				key, _, _ := strings.Cut(v, ".")
				return key
			}
		}
	}
	return ""
}

// airDate gives <date> as YYYY-MM-DD or YYYY, or "" for any other form.
func airDate(dates []string) string {
	s := firstText(dates)
	if strings.Trim(s, "0123456789") != "" {
		return ""
	}
	switch len(s) {
	case 8:
		t, err := time.Parse("20060102", s)
		if err != nil {
			return ""
		}
		return t.Format(time.DateOnly)
	case 4:
		return s
	}
	return ""
}

// categories cleans the categories and keeps each once, ignoring case.
func categories(raw []string) []string {
	var out []string
	seen := map[string]bool{}
	for _, c := range raw {
		c = clean(c)
		if c == "" || seen[strings.ToLower(c)] {
			continue
		}
		seen[strings.ToLower(c)] = true
		out = append(out, c)
	}
	return out
}

func hd(video []rawVideo) bool {
	for _, v := range video {
		for _, q := range v.Quality {
			if strings.HasPrefix(strings.ToUpper(clean(q)), "HD") {
				return true
			}
		}
	}
	return false
}

func rating(ratings []rawRating) string {
	for _, r := range ratings {
		if v := firstText(r.Values); v != "" {
			return v
		}
	}
	return ""
}

// firstIcon is the first icon whose URL path matches.
func firstIcon(icons []rawIcon, match func(path string) bool) string {
	for _, icon := range icons {
		src := clean(icon.Src)
		if src == "" {
			continue
		}
		path := src
		if u, err := url.Parse(src); err == nil {
			path = u.Path
		}
		if match(path) {
			return src
		}
	}
	return ""
}

// firstText is the first text that isn't blank once cleaned.
func firstText(texts []string) string {
	for _, s := range texts {
		if s = clean(s); s != "" {
			return s
		}
	}
	return ""
}

func prefix(s string, n int) string {
	if len(s) < n {
		return s
	}
	return s[:n]
}

// capDescription cuts a description over 4,000 characters at the last space
// that leaves room for an ellipsis. Shorter ones are kept whole.
func capDescription(s string) string {
	if utf8.RuneCountInString(s) <= maxDescription {
		return s
	}
	runes := []rune(s)[:maxDescription-1]
	cut := string(runes)
	if i := strings.LastIndexByte(cut, ' '); i > 0 {
		cut = cut[:i]
	}
	return strings.TrimRight(cut, " ") + "…"
}

// tagRe matches anything that looks like a markup tag: <b>, </i>, <br/>,
// <a href="…">.
var tagRe = regexp.MustCompile(`</?[A-Za-z][A-Za-z0-9:-]*(?:\s[^<>]*)?/?>`)

// inlineTags are removed without a space, so "<i>Title</i>," stays "Title,";
// every other tag, such as <br> or <p>, leaves a space.
var inlineTags = map[string]bool{
	"a": true, "abbr": true, "b": true, "big": true, "cite": true, "em": true,
	"font": true, "i": true, "mark": true, "q": true, "s": true, "small": true,
	"span": true, "strong": true, "sub": true, "sup": true, "u": true,
}

// clean tidies a feed string: control characters removed (line breaks and
// tabs become spaces), anything like a markup tag removed, runs of white
// space made one space, trimmed and in Unicode NFC.
func clean(s string) string {
	if s == "" {
		return ""
	}
	s = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			if unicode.IsSpace(r) {
				return ' '
			}
			return -1
		}
		return r
	}, s)
	if strings.IndexByte(s, '<') >= 0 {
		s = tagRe.ReplaceAllStringFunc(s, func(tag string) string {
			name := strings.TrimLeft(tag, "</")
			if i := strings.IndexFunc(name, func(r rune) bool { return unicode.IsSpace(r) || r == '/' || r == '>' }); i >= 0 {
				name = name[:i]
			}
			if inlineTags[strings.ToLower(name)] {
				return ""
			}
			return " "
		})
	}
	return norm.NFC.String(strings.Join(strings.Fields(s), " "))
}
