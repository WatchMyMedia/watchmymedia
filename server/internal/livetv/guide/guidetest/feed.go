package guidetest

import (
	"bytes"
	"encoding/xml"
	"fmt"
	"hash/crc32"
	"strings"
	"time"
)

// FeedChannel is one channel of a fictional feed.
type FeedChannel struct {
	Number string // the lineup's GuideNumber, which <lcn> carries
	Names  []string
	NoLogo bool
}

// StandardChannels match hdhrtest's lineup (7.1 Meadow News, 12.2 Orchard
// Arts) and the Live TV fixture's extra channels (22.1 Cedar Sports, 42.3
// Willow Cinema); 42.3 has no logo.
//
// Their display names follow SiliconDust's order (PLAN-LIVE-TV-GUIDE.md
// §7.1), with Q callsigns, which no station has: 3 to 6 names, one of them
// the lineup's GuideName, and 42.3 has the blank callsign ("42.3 " and "").
var StandardChannels = []FeedChannel{
	{Number: "7.1", Names: []string{"QMDWDT", "7.1 QMDWDT", "7.1 QMDW-DT", "7.1", "QMDW-DT", "Meadow News"}},
	{Number: "12.2", Names: []string{"QORCDT2", "12.2 QORCDT2", "12.2 QORC-DT", "12.2", "Orchard Arts"}},
	{Number: "22.1", Names: []string{"QCDRDT", "22.1 QCDRDT", "Cedar Sports"}},
	{Number: "42.3", Names: []string{"QWLWDT3", "42.3 QWLWDT3", "42.3 ", "42.3", "", "Willow Cinema"}, NoLogo: true},
}

// Feed writes a fictional XMLTV feed in SiliconDust's form for channels,
// from 4 hours before start to 48 hours after it, as gzip-free XML bytes.
//
// Channels take four schedules in turn: news and game shows, arts, sport
// and films. Every channel's programmes begin 4 hours before the half hour
// start falls in and run without gaps or overlaps past 48 hours after
// start, and the same start always gives the same feed. On the first
// schedule "The Sleuth Files" S2 E4 "The Low Tide" begins at that half hour.
func Feed(start time.Time, channels []FeedChannel) []byte {
	start = start.UTC()
	first := start.Truncate(30 * time.Minute).Add(-4 * time.Hour)
	end := start.Add(48 * time.Hour)
	ids := feedChannelIDs(channels)

	w := new(feedWriter)
	w.b.WriteString(xml.Header)
	w.b.WriteString("<tv>\n")
	for i, c := range channels {
		w.channel(ids[i], c)
	}
	for i := range channels {
		sched := feedSchedules[i%len(feedSchedules)]
		aired := map[*feedShow]int{}
		t := first
		for k := 0; t.Before(end); k++ {
			show := sched[k%len(sched)]
			stop := t.Add(time.Duration(show.minutes) * time.Minute)
			w.programme(ids[i], show, aired[show], t, stop)
			aired[show]++
			t = stop
		}
	}
	w.b.WriteString("</tv>\n")
	return w.b.Bytes()
}

// feedChannelIDs gives each channel a US<digits>.hdhomerun.com id made from
// its number, so a channel keeps its id whichever others are in the feed.
func feedChannelIDs(channels []FeedChannel) []string {
	ids := make([]string, len(channels))
	used := map[string]bool{}
	for i, c := range channels {
		n := crc32.ChecksumIEEE([]byte(c.Number)) % 90000
		for {
			ids[i] = fmt.Sprintf("US%d.hdhomerun.com", 10000+n)
			if !used[ids[i]] {
				break
			}
			n = (n + 1) % 90000
		}
		used[ids[i]] = true
	}
	return ids
}

// Airings of a show: always new, new the first time in the feed, or always
// a repeat.
const (
	feedRepeat = iota
	feedFirstNew
	feedAlwaysNew
)

// feedShow is a fictional series, show or film.
type feedShow struct {
	title    string
	kind     string // "EP", "SH" or "MV"
	series   int    // the number in its ids
	minutes  int
	season   int      // 0 for none; a year for a season numbered by year
	first    int      // the episode aired first
	numbered bool     // whether onscreen and xmltv_ns are written
	subs     []string // sub-titles: by episode when numbered, else by airing
	descs    map[int]string
	desc     string // the description when descs has none; "" for none
	lang     string
	cats     []string
	airing   int
	live, hd bool
	aired    string // the first episode's original air date, YYYYMMDD; "" for none
	year     string // a film's year
	stars    string // a film's star rating, "3/4"
}

func (s *feedShow) seriesID() string {
	if s.kind == "MV" {
		return fmt.Sprintf("MV%08d", s.series)
	}
	return fmt.Sprintf("C%d", s.series)
}

var (
	feedMeadowNews = &feedShow{
		title: "Meadow News Hour", kind: "SH", series: 510001, minutes: 60,
		desc: "Local headlines, traffic and community stories from the Meadow News team.",
		cats: []string{"News"}, airing: feedAlwaysNew, live: true, hd: true,
	}
	feedHarborWeather = &feedShow{
		title: "Harbor Weather", kind: "SH", series: 510002, minutes: 30,
		desc: "Forecasts, tide tables and the week's outlook for the coast.",
		cats: []string{"Weather", "News"}, airing: feedAlwaysNew, live: true,
	}
	feedQuizParlor = &feedShow{
		title: "Quiz Parlor", kind: "EP", series: 510003, minutes: 30, season: 5, first: 21, numbered: true,
		subs: []string{"Librarians Week", "The Lightning Round", "Champions Return", "Family Night", "The Puzzle Box", "Rookie Day"},
		desc: "Three contestants race through trivia rounds for the parlor's grand prize.",
		cats: []string{"Game show"}, airing: feedRepeat, aired: "20260112",
	}
	feedDollarDash = &feedShow{
		title: "Dollar Dash", kind: "EP", series: 510004, minutes: 30,
		subs: []string{"Coins in the Fountain", "Close to the Price", "Sprint for the Safe", "Double Dash"},
		desc: "Shoppers dash through a pretend market to fill their carts before the bell.",
		cats: []string{"Game show", "Series"}, airing: feedFirstNew, aired: "20260302",
	}
	feedSleuthFiles = &feedShow{
		title: "The Sleuth Files", kind: "EP", series: 510005, minutes: 60, season: 2, first: 4, numbered: true,
		subs: []string{"Ink on the Pier", "The Second Lantern", "Salt and Cinders", "The Low Tide", "A Quiet Ledger", "Glass Harbor",
			"The Missing Oar", "Fog Bell", "Paper Alibi", "The Tidewater Clock", "North Pier Confession", "Last Ferry Out"},
		descs: map[int]string{
			4: "A body on the mudflats at dawn sends Inspector Marlow chasing the tide tables, and a ferryman who swears he never left the harbor.",
			5: "A bookkeeper's tidy accounts hide a debt nobody in the village will admit to.",
		},
		desc: "Inspector Marlow and her partner solve crimes in a fog-bound fishing village.",
		cats: []string{"Series", "Drama", "Mystery"}, airing: feedFirstNew, hd: true, aired: "20250914",
	}

	feedGardenRounds = &feedShow{
		title: "Garden Rounds", kind: "EP", series: 520001, minutes: 30, season: 2026, first: 14, numbered: true,
		subs: []string{"Autumn Bulbs", "Raised Beds", "Cold Frames", "Seed Swap", "Winter Mulch", "Pruning Day"},
		desc: "A walk through community gardens with tips for the season ahead.",
		cats: []string{"Series", "Home & garden"}, airing: feedFirstNew, aired: "20260105",
	}
	feedRecetas = &feedShow{
		title: "Recetas del Huerto", kind: "EP", series: 520002, minutes: 30, season: 1, first: 8, numbered: true,
		subs: []string{"Sopa de calabaza", "Pan de maíz", "Tarta de manzana", "Ensalada de otoño"},
		desc: "Recetas sencillas con lo que da el huerto en cada temporada.",
		lang: "es", cats: []string{"Cooking"}, airing: feedRepeat, aired: "20250601",
	}
	feedSleuthClassic = &feedShow{
		title: "The Sleuth Files", kind: "EP", series: 510005, minutes: 60, season: 1, first: 1, numbered: true,
		subs: []string{"Pilot", "The Harbormaster's Key", "Gulls at Midnight", "A Net Full of Secrets", "The Drowned Bell", "Low Water Mark"},
		desc: "Inspector Marlow and her partner solve crimes in a fog-bound fishing village.",
		cats: []string{"Series", "Drama", "Mystery"}, airing: feedRepeat, hd: true, aired: "20240908",
	}
	feedBrushwork = &feedShow{
		title: "Brushwork Weekly", kind: "EP", series: 520003, minutes: 60, season: 3, first: 2, numbered: true,
		subs: []string{"Watercolor Skies", "Charcoal Portraits", "Ink and Wash", "Palette Knife Seas", "Gouache Gardens"},
		desc: "A painter works through one technique each week, start to finish.",
		cats: []string{"Series", "Art"}, airing: feedRepeat, hd: true, aired: "20251002",
	}
	feedOrchardSpotlight = &feedShow{
		title: "Orchard Arts Spotlight", kind: "SH", series: 520004, minutes: 60,
		cats: []string{"Art", "Documentary"}, airing: feedRepeat,
	}

	feedSportsDesk = &feedShow{
		title: "Sports Desk Tonight", kind: "SH", series: 530001, minutes: 60,
		desc: "Scores, highlights and talk from around the conference.",
		cats: []string{"Sports talk"}, airing: feedAlwaysNew, live: true, hd: true,
	}
	feedFairway = &feedShow{
		title: "Fairway Notebook", kind: "EP", series: 530002, minutes: 30, season: 4, first: 9, numbered: true,
		subs: []string{"Links by the Sea", "Bunker Lessons", "The Long Par Five", "Putting in the Rain"},
		desc: "Lessons and course walks from a touring golf coach.",
		cats: []string{"Golf", "Sport"}, airing: feedRepeat, aired: "20250418",
	}
	feedRewind = &feedShow{
		title: "Cedar Sports Rewind", kind: "SH", series: 530003, minutes: 120,
		desc: "Classic games from the archive, replayed in full.",
		cats: []string{"Sport"}, airing: feedRepeat,
	}
	feedFootball = &feedShow{
		title: "Conference Football", kind: "EP", series: 530004, minutes: 180,
		subs: []string{"Copperline Otters at Bramblewood Owls", "Fenmoor Herons at Stonegate Badgers", "Larkspur Foxes at Millbrook Bison"},
		desc: "College football from the conference's weekly slate.",
		cats: []string{"Football", "Sport"}, airing: feedAlwaysNew, live: true, hd: true,
	}

	feedMatinee = &feedShow{
		title: "Retro Reels Matinee", kind: "SH", series: 540001, minutes: 30,
		desc: "A host introduces the next classic film with stories from the set.",
		cats: []string{"Entertainment"}, airing: feedRepeat,
	}
	feedCopperValley = &feedShow{
		title: "Copper Valley Run", kind: "MV", series: 540002, minutes: 120, year: "1962", stars: "3/4",
		desc: "A railway surveyor and a stubborn rancher race a flood across the valley to warn the town below. (3.0/4.0)",
		cats: []string{"Movie", "Western"}, airing: feedRepeat, hd: true,
	}
	feedSmallRivers = &feedShow{
		title: "A Map of Small Rivers", kind: "MV", series: 540003, minutes: 90, year: "1974", stars: "3.5/4",
		desc: "Two sisters retrace their late father's canoe trips, one river at a time. (3.5/4.0)",
		cats: []string{"Movie", "Drama"}, airing: feedRepeat,
	}
	feedClockmaker = &feedShow{
		title: "The Lantern Clockmaker", kind: "MV", series: 540004, minutes: 150, year: "1949", stars: "2.5/4",
		desc: "A clockmaker's apprentice suspects the town's new lamplighter of more than lighting lamps. (2.5/4.0)",
		cats: []string{"Movie", "Mystery"}, airing: feedRepeat,
	}
)

// feedSchedules are the four channels' days, repeated: 6, 4, 8 and 8 hours.
// The first has the Sleuth Files at 4 hours, which is the half hour of the
// feed's start.
var feedSchedules = [][]*feedShow{
	{feedMeadowNews, feedHarborWeather, feedQuizParlor, feedDollarDash, feedDollarDash, feedQuizParlor, feedHarborWeather, feedSleuthFiles, feedMeadowNews},
	{feedGardenRounds, feedRecetas, feedSleuthClassic, feedBrushwork, feedOrchardSpotlight},
	{feedSportsDesk, feedFairway, feedFairway, feedRewind, feedFootball, feedSportsDesk},
	{feedMatinee, feedCopperValley, feedMatinee, feedSmallRivers, feedMatinee, feedClockmaker, feedMatinee},
}

type feedWriter struct{ b bytes.Buffer }

// feedEscaper escapes text for an element or a double-quoted attribute,
// leaving apostrophes as they are, as the real feed does.
var feedEscaper = strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;", `"`, "&quot;")

func (w *feedWriter) text(s string) {
	_, _ = feedEscaper.WriteString(&w.b, s)
}

// elem writes <name attrs…>text</name> on its own line; attrs are name,
// value pairs.
func (w *feedWriter) elem(indent, name, text string, attrs ...string) {
	w.open(indent, name, attrs...)
	w.b.WriteString(">")
	w.text(text)
	w.b.WriteString("</" + name + ">\n")
}

// empty writes <name attrs…/> on its own line.
func (w *feedWriter) empty(indent, name string, attrs ...string) {
	w.open(indent, name, attrs...)
	w.b.WriteString("/>\n")
}

func (w *feedWriter) open(indent, name string, attrs ...string) {
	w.b.WriteString(indent + "<" + name)
	for i := 0; i+1 < len(attrs); i += 2 {
		w.b.WriteString(" " + attrs[i] + `="`)
		w.text(attrs[i+1])
		w.b.WriteString(`"`)
	}
}

func (w *feedWriter) channel(id string, c FeedChannel) {
	w.b.WriteString("  <channel id=\"")
	w.text(id)
	w.b.WriteString("\">\n")
	names := c.Names
	if len(names) == 0 {
		names = []string{c.Number}
	}
	for _, n := range names {
		w.elem("    ", "display-name", n)
	}
	if !c.NoLogo {
		w.empty("    ", "icon", "src", "https://img.hdhomerun.com/channels/"+strings.TrimSuffix(id, ".hdhomerun.com")+".png", "width", "360", "height", "270")
	}
	w.elem("    ", "lcn", c.Number)
	w.b.WriteString("  </channel>\n")
}

// programme writes the show's nth airing in the feed, from start to stop.
func (w *feedWriter) programme(channel string, s *feedShow, n int, start, stop time.Time) {
	const in = "    "
	const layout = "20060102150405 -0700"
	w.b.WriteString("  <programme start=\"" + start.Format(layout) + "\" stop=\"" + stop.Format(layout) + "\" channel=\"")
	w.text(channel)
	w.b.WriteString("\">\n")

	episode := s.first + n
	sub := ""
	if len(s.subs) > 0 {
		i := n
		if s.numbered {
			i = episode - 1
		}
		sub = s.subs[i%len(s.subs)]
	}
	fresh := s.airing == feedAlwaysNew || (s.airing == feedFirstNew && n == 0)

	w.elem(in, "title", s.title)
	if sub != "" {
		w.elem(in, "sub-title", sub)
	}
	desc := s.descs[episode]
	if desc == "" {
		desc = s.desc
	}
	lang := s.lang
	if lang == "" {
		lang = "en"
	}
	if desc != "" {
		w.elem(in, "desc", desc, "lang", lang)
	}
	switch {
	case s.year != "":
		w.elem(in, "date", s.year)
	case fresh && s.kind != "MV":
		w.elem(in, "date", start.Format("20060102"))
	case s.aired != "":
		first, err := time.Parse("20060102", s.aired)
		if err != nil {
			panic(err)
		}
		week := n
		if s.numbered {
			week = episode - s.first
		}
		w.elem(in, "date", first.AddDate(0, 0, 7*week).Format("20060102"))
	}
	for _, c := range s.cats {
		w.elem(in, "category", c)
	}
	w.elem(in, "language", lang)
	if s.kind == "MV" {
		w.empty(in, "icon", "src", "https://img.hdhomerun.com/posters/"+s.seriesID()+".jpg", "width", "240", "height", "360")
	}
	w.empty(in, "icon", "src", "https://img.hdhomerun.com/titles/"+s.seriesID()+".jpg", "width", "360", "height", "270")
	if s.kind == "MV" {
		w.elem(in, "series-id", s.seriesID())
	} else {
		w.elem(in, "series-id", s.seriesID(), "system", "cseries")
	}
	part := 0
	if s.kind == "EP" {
		part = (s.season%100)*100 + episode
		if !s.numbered {
			part = n + 1
		}
	}
	w.elem(in, "episode-num", fmt.Sprintf("%s%08d.%04d", s.kind, s.series, part%10000), "system", "dd_progid")
	if s.numbered {
		w.elem(in, "episode-num", fmt.Sprintf("S%02dE%02d", s.season, episode), "system", "onscreen")
		w.elem(in, "episode-num", fmt.Sprintf("%d.%d.", s.season-1, episode-1), "system", "xmltv_ns")
	}
	if s.hd {
		w.b.WriteString(in + "<video>\n" + in + "  <quality>HDTV</quality>\n" + in + "</video>\n")
	}
	if fresh {
		w.empty(in, "new")
	} else {
		w.empty(in, "previously-shown")
	}
	if s.live {
		w.empty(in, "live")
	}
	if s.stars != "" {
		w.b.WriteString(in + "<star-rating>\n")
		w.elem(in+"  ", "value", s.stars)
		w.b.WriteString(in + "</star-rating>\n")
	}
	w.b.WriteString("  </programme>\n")
}
