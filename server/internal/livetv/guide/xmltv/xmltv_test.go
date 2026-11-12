package xmltv

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"
	"reflect"
	"regexp"
	"slices"
	"strings"
	"testing"
	"testing/iotest"
	"time"
	"unicode/utf8"

	"watchmymedia/server/internal/livetv/guide/guidetest"
)

func utc(s string) time.Time {
	t, err := time.Parse(time.RFC3339, s)
	if err != nil {
		panic(err)
	}
	return t
}

// feed wraps programmes in a feed with channels a (1.1) and b (2.1).
func feed(body string) string {
	return `<?xml version="1.0" encoding="UTF-8"?>
<tv>
  <channel id="a"><display-name>A</display-name><lcn>1.1</lcn></channel>
  <channel id="b"><display-name>B</display-name><lcn>2.1</lcn></channel>
` + body + `
</tv>
`
}

func prog(channel, start, stop, title string) string {
	return fmt.Sprintf(`  <programme start="%s" stop="%s" channel="%s"><title>%s</title></programme>`+"\n", start, stop, channel, title)
}

func mustParse(t *testing.T, s string, opt Options) Feed {
	t.Helper()
	f, err := Parse(strings.NewReader(s), opt)
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}
	return f
}

func titles(ps []Programme) []string {
	var out []string
	for _, p := range ps {
		out = append(out, p.Title)
	}
	return out
}

func TestParseCommonForms(t *testing.T) {
	t.Parallel()
	raw, err := os.ReadFile("testdata/silicondust.xml")
	if err != nil {
		t.Fatal(err)
	}
	f, err := Parse(bytes.NewReader(raw), Options{})
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}

	wantChannels := []Channel{
		{ID: "US10001.hdhomerun.com", Names: []string{"QMDWDT", "7.1 QMDWDT", "7.1 QMDW-DT", "7.1", "QMDW-DT", "Meadow News"}, LCN: "7.1", Logo: "https://img.hdhomerun.com/channels/US10001.png"},
		{ID: "US10002.hdhomerun.com", Names: []string{"QORCDT2", "7.2 QORCDT2", "7.2", "7.2", "Orchard Arts"}, LCN: "7.2", Logo: "https://img.hdhomerun.com/channels/US10002.png"},
		{ID: "US10003.hdhomerun.com", Names: []string{"QCDRDT", "22.1 QCDRDT", "Cedar Sports"}, LCN: "22.1"},
	}
	if !reflect.DeepEqual(f.Channels, wantChannels) {
		t.Errorf("channels:\n got %+v\nwant %+v", f.Channels, wantChannels)
	}

	const (
		one = "US10001.hdhomerun.com"
		two = "US10002.hdhomerun.com"
		img = "https://img.hdhomerun.com/"
	)
	want := []Programme{
		{
			Channel: one, Start: utc("2026-11-02T12:00:00Z"), Stop: utc("2026-11-02T13:00:00Z"),
			Title: "The Sleuth Files", EpisodeTitle: "The Low Tide", Season: new(2), Episode: new(4),
			Kind: "episode", SeriesKey: "C510005", AirDate: "2025-10-05",
			Categories: []string{"Series", "Drama", "Mystery"}, Repeat: true, HD: true,
			Description: "A body on the mudflats at dawn sends Inspector Marlow chasing the tide tables.",
			Picture:     img + "titles/C510005.jpg",
		},
		{
			Channel: one, Start: utc("2026-11-02T13:00:00Z"), Stop: utc("2026-11-02T13:30:00Z"),
			Title: "Garden Rounds", EpisodeTitle: "Autumn Bulbs", Season: new(2026), Episode: new(14),
			Kind: "episode", SeriesKey: "C520001", AirDate: "2026-11-02",
			Categories: []string{"Series", "Home & garden"}, New: true,
			Description: "A walk through community gardens with tips for the season ahead.",
			Picture:     img + "titles/C520001.jpg",
		},
		{
			Channel: one, Start: utc("2026-11-02T13:30:00Z"), Stop: utc("2026-11-02T14:30:00Z"),
			Title: "Meadow News Hour", Kind: "show", SeriesKey: "C510001", AirDate: "2026-11-02",
			Categories: []string{"News"}, New: true, Live: true, HD: true,
			Description: "Local headlines, traffic and community stories.",
			Picture:     img + "titles/C510001.jpg",
		},
		{
			Channel: one, Start: utc("2026-11-02T14:30:00Z"), Stop: utc("2026-11-02T16:30:00Z"),
			Title: "Copper Valley Run", Kind: "movie", SeriesKey: "MV00540002", AirDate: "1962",
			Categories: []string{"Movie", "Western"}, Repeat: true,
			Description: "A railway surveyor and a stubborn rancher race a flood across the valley. (3.0/4.0)",
			Picture:     img + "titles/MV00540002.jpg", Poster: img + "posters/MV00540002.jpg",
		},
		{
			Channel: one, Start: utc("2026-11-02T16:30:00Z"), Stop: utc("2026-11-02T17:00:00Z"),
			Title: "Recetas del Huerto", EpisodeTitle: "Pan de maíz", Season: new(1), Episode: new(8),
			Kind: "episode", SeriesKey: "C520002", AirDate: "2025-07-13",
			Categories: []string{"Cooking"}, Repeat: true,
			Description: "Recetas sencillas con lo que da el huerto en cada temporada.",
			Picture:     img + "titles/C520002.jpg",
		},
		{
			Channel: one, Start: utc("2026-11-02T17:00:00Z"), Stop: utc("2026-11-02T18:00:00Z"),
			Title: "Orchard Arts Spotlight", Kind: "show", SeriesKey: "SH00520004",
			Categories: []string{"Art", "Documentary"}, Repeat: true,
		},
		{
			Channel: one, Start: utc("2026-11-02T18:00:00Z"), Stop: utc("2026-11-02T18:30:00Z"),
			Title: "Dollar Dash", EpisodeTitle: "Coins in the Fountain", Kind: "episode", SeriesKey: "C510004",
			AirDate: "2026-03-02", Categories: []string{"Game show"}, Repeat: true,
			Description: "Shoppers dash through a pretend market.",
			Picture:     img + "titles/C510004.jpg",
		},
		{
			Channel: two, Start: utc("2026-11-02T12:00:00Z"), Stop: utc("2026-11-02T12:30:00Z"),
			Title: "Café Stories", EpisodeTitle: "Part Two", EpisodeLabel: "Part 2 of 3",
			Kind: "episode", SeriesKey: "C520005", Categories: []string{"Series"}, HD: true, Rating: "TV-PG",
			Description: "From Glass Harbor, the sequel. Second line, with a tab.",
			Picture:     img + "titles/C520005.jpg",
		},
		{
			Channel: two, Start: utc("2026-11-02T12:30:00Z"), Stop: utc("2026-11-02T13:00:00Z"),
			Title: "Brushwork Weekly", Season: new(3), Episode: new(7),
		},
		{
			Channel: two, Start: utc("2026-11-02T13:00:00Z"), Stop: utc("2026-11-02T13:30:00Z"),
			Title: "Fairway Notebook", Season: new(1), Episode: new(5),
		},
	}
	if len(f.Programmes) != len(want) {
		t.Fatalf("programmes = %q, want %q", titles(f.Programmes), titles(want))
	}
	for i := range want {
		if !reflect.DeepEqual(f.Programmes[i], want[i]) {
			t.Errorf("programme %d:\n got %+v\nwant %+v", i, f.Programmes[i], want[i])
		}
	}
	if f.Overlaps != 0 || f.Dropped != 0 || f.Unknown != 0 {
		t.Errorf("overlaps, dropped, unknown = %d, %d, %d; want none", f.Overlaps, f.Dropped, f.Unknown)
	}
}

func TestParseOverlapsAndGaps(t *testing.T) {
	t.Parallel()
	f := mustParse(t, feed(
		prog("a", "20261102140000 +0000", "20261102143000 +0000", "D")+
			prog("a", "20261102120000 +0000", "20261102130000 +0000", "A")+
			prog("a", "20261102121000 +0000", "20261102122000 +0000", "inside A")+
			prog("a", "20261102123000 +0000", "20261102133000 +0000", "B")+
			prog("a", "20261102131500 +0000", "20261102133030 +0000", "30 seconds left")+
			prog("a", "20261102142900 +0000", "20261102143100 +0000", "E")+
			prog("b", "20261102121500 +0000", "20261102124500 +0000", "other channel"),
	), Options{})

	type got struct {
		title       string
		start, stop time.Time
	}
	var have []got
	for _, p := range f.Programmes {
		have = append(have, got{p.Title, p.Start, p.Stop})
	}
	want := []got{
		{"A", utc("2026-11-02T12:00:00Z"), utc("2026-11-02T13:00:00Z")},
		{"B", utc("2026-11-02T13:00:00Z"), utc("2026-11-02T13:30:00Z")},
		{"D", utc("2026-11-02T14:00:00Z"), utc("2026-11-02T14:30:00Z")},
		{"E", utc("2026-11-02T14:30:00Z"), utc("2026-11-02T14:31:00Z")},
		{"other channel", utc("2026-11-02T12:15:00Z"), utc("2026-11-02T12:45:00Z")},
	}
	if !reflect.DeepEqual(have, want) {
		t.Errorf("programmes:\n got %v\nwant %v", have, want)
	}
	if f.Overlaps != 2 || f.Dropped != 2 {
		t.Errorf("overlaps, dropped = %d, %d; want 2, 2", f.Overlaps, f.Dropped)
	}
}

func TestParseDropsInvalid(t *testing.T) {
	t.Parallel()
	f := mustParse(t, feed(
		prog("a", "20261102100000 +0000", "20261102103000 +0000", strings.Repeat("é", 300))+
			prog("a", "20261102103000 +0000", "20261102110000 +0000", strings.Repeat("é", 301))+
			prog("a", "20261102110000 +0000", "20261102113000 +0000", " &lt;b&gt; &lt;/b&gt; ")+
			`<programme start="20261102113000 +0000" stop="20261102120000 +0000" channel="a"><desc>No title</desc></programme>`+
			prog("a", "20261102130000 +0000", "20261102120000 +0000", "stop before start")+
			prog("a", "20261102130000 +0000", "20261102130000 +0000", "no time at all")+
			prog("a", "2026-11-02 13:00", "20261102140000 +0000", "unreadable start")+
			prog("a", "20261102140000 EST", "20261102150000 +0000", "named zone")+
			prog("a", "20260230120000 +0000", "20260230130000 +0000", "30 February")+
			`<programme start="20261102150000 +0000" channel="a"><title>no stop</title></programme>`+
			prog("a", "20261103000000 +0000", "20261104000000 +0000", "a whole day")+
			prog("b", "20261103000000 +0000", "20261104000100 +0000", "a day and a minute"),
	), Options{})

	want := []string{strings.Repeat("é", 300), "a whole day"}
	if got := titles(f.Programmes); !reflect.DeepEqual(got, want) {
		t.Errorf("kept %q, want %q", got, want)
	}
	if f.Dropped != 10 {
		t.Errorf("dropped = %d, want 10", f.Dropped)
	}
}

func TestParseCapsDescription(t *testing.T) {
	t.Parallel()
	huge := strings.Repeat("The harbor fog rolls in again tonight. ", 300) // 11,700 characters
	whole := strings.Repeat("ñ", 4000)
	word := strings.Repeat("x", 5000)
	desc := func(start, stop, text string) string {
		return fmt.Sprintf(`<programme start="%s" stop="%s" channel="a"><title>T</title><desc lang="en">%s</desc></programme>`, start, stop, text)
	}
	f := mustParse(t, feed(
		desc("20261102120000 +0000", "20261102123000 +0000", huge)+
			desc("20261102123000 +0000", "20261102130000 +0000", whole)+
			desc("20261102130000 +0000", "20261102133000 +0000", word),
	), Options{})
	if len(f.Programmes) != 3 {
		t.Fatalf("kept %d programmes, want 3", len(f.Programmes))
	}

	cut := f.Programmes[0].Description
	body, ok := strings.CutSuffix(cut, "…")
	if !ok || utf8.RuneCountInString(cut) > 4000 || utf8.RuneCountInString(cut) < 3900 {
		t.Errorf("huge description is %d characters ending %q, want at most 4,000 ending in …", utf8.RuneCountInString(cut), cut[len(cut)-10:])
	}
	if !strings.HasPrefix(huge, body) || huge[len(body)] != ' ' || strings.HasSuffix(body, " ") {
		t.Errorf("huge description not cut at a space: ends %q", body[len(body)-20:])
	}
	if got := f.Programmes[1].Description; got != whole {
		t.Errorf("4,000-character description changed to %d characters", utf8.RuneCountInString(got))
	}
	if got := f.Programmes[2].Description; got != strings.Repeat("x", 3999)+"…" {
		t.Errorf("a description without spaces is %d characters, want 3,999 and …", utf8.RuneCountInString(got))
	}
}

func TestParseRefusesEntities(t *testing.T) {
	t.Parallel()
	external := `<!DOCTYPE tv [<!ENTITY x SYSTEM "file:///etc/passwd">]>` + "\n"
	for name, doc := range map[string]string{
		"external entity":     external + feed(prog("a", "20261102120000 +0000", "20261102130000 +0000", "&x;")),
		"internal entity":     `<!DOCTYPE tv [<!ENTITY x "Quiz Parlor">]>` + feed(prog("a", "20261102120000 +0000", "20261102130000 +0000", "&x;")),
		"entity in attribute": external + feed(prog("&x;", "20261102120000 +0000", "20261102130000 +0000", "Quiz Parlor")),
		"HTML entity":         feed(prog("a", "20261102120000 +0000", "20261102130000 +0000", "Quiz&nbsp;Parlor")),
	} {
		f, err := Parse(strings.NewReader(doc), Options{})
		if err == nil {
			t.Errorf("%s: parsed, want an error", name)
		}
		if !reflect.DeepEqual(f, Feed{}) {
			t.Errorf("%s: kept %+v, want nothing", name, f)
		}
	}

	// A DOCTYPE is ignored rather than processed.
	f := mustParse(t, strings.Replace(feed(prog("a", "20261102120000 +0000", "20261102130000 +0000", "Quiz Parlor &amp; Friends")), "<tv>", external+"<tv>", 1), Options{})
	if got := titles(f.Programmes); !reflect.DeepEqual(got, []string{"Quiz Parlor & Friends"}) {
		t.Errorf("with a DOCTYPE kept %q", got)
	}
}

func TestParseFailsOnBrokenFeeds(t *testing.T) {
	t.Parallel()
	raw, err := os.ReadFile("testdata/silicondust.xml")
	if err != nil {
		t.Fatal(err)
	}
	s := string(raw)
	end := strings.LastIndex(s, "</tv>")
	cuts := map[string]int{
		"nothing":             0,
		"after the header":    strings.Index(s, "\n"),
		"inside a channel":    strings.Index(s, "<lcn>"),
		"inside a tag":        strings.Index(s, "<programme") + 5,
		"inside an end tag":   strings.Index(s, "</programme>") + 4,
		"halfway":             len(s) / 2,
		"after a programme":   strings.LastIndex(s, "</programme>") + len("</programme>"),
		"before the last >":   end + len("</tv"),
		"inside an attribute": strings.Index(s, `start="`) + 10,
	}
	for name, n := range cuts {
		f, err := Parse(strings.NewReader(s[:n]), Options{})
		if err == nil {
			t.Errorf("cut off %s: parsed, want an error", name)
		}
		if !reflect.DeepEqual(f, Feed{}) {
			t.Errorf("cut off %s: kept %d programmes, want nothing", name, len(f.Programmes))
		}
	}
	if _, err := Parse(strings.NewReader(s[:end+len("</tv>")]), Options{}); err != nil {
		t.Errorf("whole feed without its last line break: %v", err)
	}

	broken := errors.New("connection reset")
	if _, err := Parse(io.MultiReader(strings.NewReader(s[:len(s)/2]), iotest.ErrReader(broken)), Options{}); !errors.Is(err, broken) {
		t.Errorf("a read failing part-way gave %v, want %v", err, broken)
	}

	for name, doc := range map[string]string{
		"another root":       `<rss><channel id="a"/></rss>`,
		"two roots":          feed("") + "<tv></tv>",
		"text outside":       feed("") + "listings",
		"Latin-1":            strings.Replace(feed(""), "UTF-8", "ISO-8859-1", 1),
		"invalid UTF-8":      feed(prog("a", "20261102120000 +0000", "20261102130000 +0000", "Caf\xe9")),
		"mismatched tags":    feed(`<programme start="20261102120000 +0000" stop="20261102130000 +0000" channel="a"><title>T</desc></programme>`),
		"unquoted attribute": feed(`<programme start=20261102120000 channel="a"><title>T</title></programme>`),
	} {
		if _, err := Parse(strings.NewReader(doc), Options{}); err == nil {
			t.Errorf("%s: parsed, want an error", name)
		}
	}
	if _, err := Parse(strings.NewReader(strings.Replace(feed(""), "UTF-8", "US-ASCII", 1)), Options{}); err != nil {
		t.Errorf("US-ASCII: %v", err)
	}
}

func TestParseUnknownAndKeep(t *testing.T) {
	t.Parallel()
	doc := `<tv>
  <channel id="a"><display-name>A</display-name><lcn>1.1</lcn></channel>
  <channel id="b"><display-name>B</display-name><lcn>2.1</lcn></channel>
  <channel id=" c "><display-name> C
  </display-name><lcn> 3.1 </lcn></channel>
` + prog("a", "20261102120000 +0000", "20261102130000 +0000", "a1") +
		prog("b", "20261102120000 +0000", "20261102130000 +0000", "b1") +
		prog("z", "20261102120000 +0000", "20261102130000 +0000", "z1") +
		prog("d", "20261102120000 +0000", "20261102130000 +0000", "d before its channel") +
		prog("c", "20261102120000 +0000", "20261102130000 +0000", "c1") +
		prog("z", "20261102130000 +0000", "20261102140000 +0000", "z2") +
		prog("b", "20261102130000 +0000", "20261102140000 +0000", "b2") +
		`  <channel id="d"><display-name>D</display-name><lcn>4.1</lcn></channel>
  <channel id="a"><display-name>Another A</display-name><lcn>9.9</lcn></channel>
` + prog("a", "20261102130000 +0000", "20261102140000 +0000", "a2") +
		prog("d", "20261102130000 +0000", "20261102140000 +0000", "d1") +
		`</tv>`

	var offered []Channel
	f := mustParse(t, doc, Options{Keep: func(c Channel) bool {
		offered = append(offered, c)
		return c.LCN != "2.1"
	}})
	wantChannels := []Channel{
		{ID: "a", Names: []string{"A"}, LCN: "1.1"},
		{ID: "b", Names: []string{"B"}, LCN: "2.1"},
		{ID: "c", Names: []string{"C"}, LCN: "3.1"},
		{ID: "d", Names: []string{"D"}, LCN: "4.1"},
	}
	if !reflect.DeepEqual(f.Channels, wantChannels) {
		t.Errorf("channels = %+v, want %+v", f.Channels, wantChannels)
	}
	if !reflect.DeepEqual(offered, wantChannels) {
		t.Errorf("Keep was offered %+v, want %+v", offered, wantChannels)
	}
	if got, want := titles(f.Programmes), []string{"a1", "a2", "c1", "d1"}; !reflect.DeepEqual(got, want) {
		t.Errorf("kept %q, want %q", got, want)
	}
	if f.Unknown != 5 {
		t.Errorf("unknown = %d, want 5 (z twice, d before its channel, b twice)", f.Unknown)
	}
}

func TestParseWindow(t *testing.T) {
	t.Parallel()
	doc := feed(
		prog("a", "20261102100000 +0000", "20261102110000 +0000", "before") +
			prog("a", "20261102110000 +0000", "20261102120000 +0000", "ends at From") +
			prog("a", "20261102120000 +0000", "20261102135900 +0000", "inside") +
			prog("a", "20261102135900 +0000", "20261102140000 +0000", "last minute") +
			prog("a", "20261102140000 +0000", "20261102150000 +0000", "starts at To") +
			prog("b", "20261102115900 +0000", "20261102120100 +0000", "across From") +
			prog("b", "20261102135959 +0000", "20261102143000 +0000", "across To"),
	)
	from, to := utc("2026-11-02T12:00:00Z"), utc("2026-11-02T14:00:00Z")
	for _, c := range []struct {
		name     string
		from, to time.Time
		want     []string
	}{
		{"no bounds", time.Time{}, time.Time{}, []string{"before", "ends at From", "inside", "last minute", "starts at To", "across From", "across To"}},
		{"both", from, to, []string{"inside", "last minute", "across From", "across To"}},
		{"From only", from, time.Time{}, []string{"inside", "last minute", "starts at To", "across From", "across To"}},
		{"To only", time.Time{}, to, []string{"before", "ends at From", "inside", "last minute", "across From", "across To"}},
	} {
		f := mustParse(t, doc, Options{From: c.from, To: c.to})
		if got := titles(f.Programmes); !reflect.DeepEqual(got, c.want) {
			t.Errorf("%s: kept %q, want %q", c.name, got, c.want)
		}
		if f.Dropped != 0 || f.Overlaps != 0 {
			t.Errorf("%s: dropped, overlaps = %d, %d", c.name, f.Dropped, f.Overlaps)
		}
	}

	// A programme an overlap pushes past To is out of the window, not dropped.
	f := mustParse(t, feed(
		prog("a", "20261102130000 +0000", "20261102150000 +0000", "long")+
			prog("a", "20261102133000 +0000", "20261102160000 +0000", "pushed past To"),
	), Options{From: from, To: to})
	if got := titles(f.Programmes); !reflect.DeepEqual(got, []string{"long"}) || f.Dropped != 0 || f.Overlaps != 0 {
		t.Errorf("kept %q, dropped %d, overlaps %d; want only long", got, f.Dropped, f.Overlaps)
	}
}

func TestParseTime(t *testing.T) {
	t.Parallel()
	noon := utc("2026-11-02T12:00:00Z")
	for _, s := range []string{
		"20261102120000 +0000",
		"20261102080000 -0400",
		"20261102170000 +0500",
		"20261102120000",
		"202611021200",
		"202611021400 +0200",
		"20261102120000+0000",
		"  20261102120000   +0000 ",
	} {
		got, ok := parseTime(s)
		if !ok || !got.Equal(noon) || got.Location() != time.UTC {
			t.Errorf("parseTime(%q) = %v, %v; want %v in UTC", s, got, ok, noon)
		}
	}
	for _, s := range []string{
		"",
		"2026110212",
		"20261102120000 EST",
		"2026-11-02T12:00:00Z",
		"20261302120000 +0000",
		"20261102250000 +0000",
		"20261102120000 +00:00",
		"20261102120000 +2500",
		"2026110212000x +0000",
	} {
		if got, ok := parseTime(s); ok {
			t.Errorf("parseTime(%q) = %v, want unreadable", s, got)
		}
	}
}

// TestParseDaylightSavingEnd covers 2026-11-01 in New York, when 01:00–02:00
// came twice: first in EDT (-0400), then in EST (-0500).
func TestParseDaylightSavingEnd(t *testing.T) {
	t.Parallel()
	f := mustParse(t, feed(
		prog("a", "20261101000000 -0400", "20261101010000 -0400", "midnight")+
			prog("a", "20261101010000 -0400", "20261101020000 -0400", "1 am EDT")+
			prog("a", "20261101010000 -0500", "20261101020000 -0500", "1 am EST")+
			prog("a", "20261101020000 -0500", "20261101030000 -0500", "2 am"),
	), Options{})
	want := []time.Time{
		utc("2026-11-01T04:00:00Z"), utc("2026-11-01T05:00:00Z"), utc("2026-11-01T06:00:00Z"), utc("2026-11-01T07:00:00Z"),
	}
	if len(f.Programmes) != len(want) || f.Overlaps != 0 || f.Dropped != 0 {
		t.Fatalf("kept %q, overlaps %d, dropped %d; want all four", titles(f.Programmes), f.Overlaps, f.Dropped)
	}
	for i, p := range f.Programmes {
		if !p.Start.Equal(want[i]) || !p.Stop.Equal(want[i].Add(time.Hour)) || p.Start.Location() != time.UTC {
			t.Errorf("%s: %v to %v, want %v for an hour in UTC", p.Title, p.Start, p.Stop, want[i])
		}
	}
	if ny, err := time.LoadLocation("America/New_York"); err == nil {
		a, b := f.Programmes[1].Start.In(ny), f.Programmes[2].Start.In(ny)
		if a.Hour() != 1 || b.Hour() != 1 || a.Equal(b) {
			t.Errorf("the repeated hour starts at %v and %v, want 01:00 twice", a, b)
		}
	}
}

func TestEpisodeNumbers(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		onscreen, ns, progid string
		season, episode      *int
		label, kind          string
	}{
		{onscreen: "S02E40", ns: "1.39.", season: new(2), episode: new(40)},
		{onscreen: "S2E4", season: new(2), episode: new(4)},
		{onscreen: "s02e40", season: new(2), episode: new(40)},
		{onscreen: "S02 E40", season: new(2), episode: new(40)},
		{onscreen: "S2026E14", ns: "2025.13.", season: new(2026), episode: new(14)},
		{onscreen: "S00E03", season: new(0), episode: new(3)},
		{onscreen: "S02E40", ns: "8.8.", season: new(2), episode: new(40)},
		{ns: "1.39.", season: new(2), episode: new(40)},
		{ns: "1.39", season: new(2), episode: new(40)},
		{ns: ".39.", episode: new(40)},
		{ns: "1..", season: new(2)},
		{ns: "1.39.0/1", season: new(2), episode: new(40)},
		{ns: "1/4 . 39/40 . ", season: new(2), episode: new(40)},
		{ns: ".."},
		{ns: "x.39."},
		{ns: "1.39.0.1"},
		{onscreen: "Part 2", label: "Part 2"},
		{onscreen: "Part 2", ns: "..", label: "Part 2"},
		{onscreen: "Part 2", ns: "a.b.", label: "Part 2"},
		{onscreen: "Part 2", ns: "0.4.", season: new(1), episode: new(5)},
		{onscreen: "S02E40-E41", label: "S02E40-E41"},
		{progid: "EP01234567.0001", kind: "episode"},
		{progid: "ep01234567.0001", kind: "episode"},
		{progid: "SH01234567.0000", kind: "show"},
		{progid: "MV01234567.0000", kind: "movie"},
		{progid: "SP01234567.0000"},
		{},
	} {
		var nums []rawSystem
		for system, v := range map[string]string{"onscreen": c.onscreen, "xmltv_ns": c.ns, "dd_progid": c.progid} {
			if v != "" {
				nums = append(nums, rawSystem{System: system, Value: v})
			}
		}
		var p Programme
		episodeNumbers(&p, nums)
		if !reflect.DeepEqual(p.Season, c.season) || !reflect.DeepEqual(p.Episode, c.episode) || p.EpisodeLabel != c.label || p.Kind != c.kind {
			t.Errorf("onscreen %q, xmltv_ns %q, dd_progid %q: season %v, episode %v, label %q, kind %q; want %v, %v, %q, %q",
				c.onscreen, c.ns, c.progid, deref(p.Season), deref(p.Episode), p.EpisodeLabel, p.Kind, deref(c.season), deref(c.episode), c.label, c.kind)
		}
	}
}

func deref(n *int) any {
	if n == nil {
		return nil
	}
	return *n
}

func TestSeriesKey(t *testing.T) {
	t.Parallel()
	progid := []rawSystem{{System: "dd_progid", Value: "EP01234567.0001"}}
	for _, c := range []struct {
		ids  []rawSystem
		nums []rawSystem
		want string
	}{
		{[]rawSystem{{System: "cseries", Value: "C510005"}}, progid, "C510005"},
		{[]rawSystem{{System: "tms", Value: "SH510005"}, {System: "cseries", Value: "C510005"}}, progid, "C510005"},
		{[]rawSystem{{Value: "MV00540002"}}, []rawSystem{{System: "dd_progid", Value: "MV00540002.0000"}}, "MV00540002"},
		{[]rawSystem{{System: "tms", Value: "SH510005"}}, progid, "EP01234567"},
		{nil, progid, "EP01234567"},
		{nil, []rawSystem{{System: "dd_progid", Value: "SH01234567"}}, "SH01234567"},
		{nil, nil, ""},
	} {
		if got := seriesKey(c.ids, c.nums); got != c.want {
			t.Errorf("seriesKey(%v, %v) = %q, want %q", c.ids, c.nums, got, c.want)
		}
	}
}

func TestAirDate(t *testing.T) {
	t.Parallel()
	for in, want := range map[string]string{
		"20251005":   "2025-10-05",
		" 20251005 ": "2025-10-05",
		"1962":       "1962",
		"2026-11-02": "",
		"20261340":   "",
		"202611":     "",
		"19620":      "",
		"":           "",
	} {
		if got := airDate([]string{in}); got != want {
			t.Errorf("airDate(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestClean(t *testing.T) {
	t.Parallel()
	for in, want := range map[string]string{
		"":                                   "",
		"  Quiz   Parlor  ":                  "Quiz Parlor",
		"Line\nbreak\tand\r\nreturn":         "Line break and return",
		"bell\x07here":                       "bellhere",
		"C1\u009fcontrol":                    "C1control",
		"Cafe\u0301 Stories":                 "Café Stories",
		"<b>Bold</b> move":                   "Bold move",
		"One<br/>Two<br>Three<p>Four</p>":    "One Two Three Four",
		"<i>Glass Harbor</i>, again":         "Glass Harbor, again",
		`<a href="https://x.test/">link</a>`: "link",
		`<span class="x">a</span>b`:          "ab",
		"3 < 5 and 7 > 2":                    "3 < 5 and 7 > 2",
		"x<3":                                "x<3",
		"\u00a0spaced\u00a0":                 "spaced",
		"<b>\n</b>":                          "",
	} {
		if got := clean(in); got != want {
			t.Errorf("clean(%q) = %q, want %q", in, got, want)
		}
	}
}

// TestParseGuidetestFeed checks the fictional feed other tests and the
// fixture use reads cleanly and covers every channel for its whole span.
func TestParseGuidetestFeed(t *testing.T) {
	t.Parallel()
	start := utc("2026-11-02T12:17:00Z")
	raw := guidetest.Feed(start, guidetest.StandardChannels)
	if !bytes.Equal(raw, guidetest.Feed(start, guidetest.StandardChannels)) {
		t.Error("two feeds for one start differ")
	}
	for _, form := range []string{
		`<desc lang="es">`,
		`/titles/C510005.jpg" width="360" height="270"/>`,
		`/posters/MV00540002.jpg" width="240" height="360"/>`,
		`<series-id system="cseries">`,
		`<episode-num system="xmltv_ns">1.3.</episode-num>`,
		`<previously-shown/>`,
		`<quality>HDTV</quality>`,
	} {
		if !bytes.Contains(raw, []byte(form)) {
			t.Errorf("feed has no %s", form)
		}
	}

	f, err := Parse(bytes.NewReader(raw), Options{From: start.Add(-6 * time.Hour), To: start.Add(15 * 24 * time.Hour)})
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}
	if f.Dropped != 0 || f.Overlaps != 0 || f.Unknown != 0 {
		t.Errorf("dropped, overlaps, unknown = %d, %d, %d; want none", f.Dropped, f.Overlaps, f.Unknown)
	}
	if len(f.Channels) != len(guidetest.StandardChannels) {
		t.Fatalf("%d channels, want %d", len(f.Channels), len(guidetest.StandardChannels))
	}
	idRe := regexp.MustCompile(`^US\d+\.hdhomerun\.com$`)
	for i, c := range f.Channels {
		std := guidetest.StandardChannels[i]
		if c.LCN != std.Number || !idRe.MatchString(c.ID) || len(c.Names) < 3 || len(c.Names) > 6 {
			t.Errorf("channel %d = %+v", i, c)
		}
		if wantLogo := !std.NoLogo; (c.Logo != "") != wantLogo || (wantLogo && c.Logo != "https://img.hdhomerun.com/channels/"+strings.TrimSuffix(c.ID, ".hdhomerun.com")+".png") {
			t.Errorf("channel %s logo %q", c.LCN, c.Logo)
		}
	}
	if got, want := f.Channels[3].Names, []string{"QWLWDT3", "42.3 QWLWDT3", "42.3", "42.3", "Willow Cinema"}; !reflect.DeepEqual(got, want) {
		t.Errorf("42.3's names = %q, want %q", got, want)
	}

	byChannel := map[string][]Programme{}
	for _, p := range f.Programmes {
		byChannel[p.Channel] = append(byChannel[p.Channel], p)
	}
	from, to := start.Add(-4*time.Hour), start.Add(48*time.Hour)
	pictures := map[string]string{}
	for _, c := range f.Channels {
		ps := byChannel[c.ID]
		if len(ps) == 0 {
			t.Errorf("channel %s has no programmes", c.LCN)
			continue
		}
		if first := ps[0].Start; first.After(from) || !first.After(from.Add(-30*time.Minute)) {
			t.Errorf("channel %s starts at %v, want the half hour at or before %v", c.LCN, first, from)
		}
		if last := ps[len(ps)-1].Stop; last.Before(to) {
			t.Errorf("channel %s ends at %v, before %v", c.LCN, last, to)
		}
		for i, p := range ps {
			if i > 0 && !p.Start.Equal(ps[i-1].Stop) {
				t.Errorf("channel %s: %s starts at %v, not when %s ends (%v)", c.LCN, p.Title, p.Start, ps[i-1].Title, ps[i-1].Stop)
			}
			if !strings.HasPrefix(p.Picture, "https://img.hdhomerun.com/titles/") || p.SeriesKey == "" || p.Kind == "" {
				t.Errorf("%s: picture %q, series %q, kind %q", p.Title, p.Picture, p.SeriesKey, p.Kind)
			}
			if (p.Kind == "movie") != (p.Poster != "") || (p.Kind == "movie" && len(p.AirDate) != 4) {
				t.Errorf("%s: kind %s, poster %q, date %q", p.Title, p.Kind, p.Poster, p.AirDate)
			}
			if seen, ok := pictures[p.SeriesKey]; ok && seen != p.Picture {
				t.Errorf("%s: picture %q, but another of its series has %q", p.Title, p.Picture, seen)
			}
			pictures[p.SeriesKey] = p.Picture
		}
	}

	has := func(what string, match func(Programme) bool) {
		t.Helper()
		if !slices.ContainsFunc(f.Programmes, match) {
			t.Errorf("no programme is %s", what)
		}
	}
	half := start.Truncate(30 * time.Minute)
	has("The Sleuth Files S2 E4 on 7.1 now", func(p Programme) bool {
		return p.Channel == f.Channels[0].ID && p.Title == "The Sleuth Files" && p.EpisodeTitle == "The Low Tide" &&
			reflect.DeepEqual(p.Season, new(2)) && reflect.DeepEqual(p.Episode, new(4)) && p.Start.Equal(half) && p.New && p.HD
	})
	has("a season numbered by year", func(p Programme) bool { return p.Season != nil && *p.Season == 2026 })
	has("live sport", func(p Programme) bool { return p.Live && p.New && slices.Contains(p.Categories, "Football") })
	has("live news", func(p Programme) bool { return p.Live && p.Kind == "show" && slices.Contains(p.Categories, "News") })
	has("a show without numbers", func(p Programme) bool { return p.Kind == "show" && p.Season == nil && p.Episode == nil })
	has("an episode without numbers", func(p Programme) bool { return p.Kind == "episode" && p.Season == nil && p.EpisodeTitle != "" })
	has("a movie with a star rating", func(p Programme) bool {
		return p.Kind == "movie" && p.AirDate == "1962" && strings.HasSuffix(p.Description, "(3.0/4.0)")
	})
	has("described in Spanish", func(p Programme) bool { return strings.HasPrefix(p.Description, "Recetas") })
	has("without a description", func(p Programme) bool { return p.Description == "" })
	has("a repeat with an air date", func(p Programme) bool { return p.Repeat && len(p.AirDate) == len("2006-01-02") })

	// Any channel list works, a channel without names showing its number.
	f, err = Parse(bytes.NewReader(guidetest.Feed(start, []guidetest.FeedChannel{{Number: "5.1"}})), Options{})
	if err != nil {
		t.Fatalf("Parse one channel: %v", err)
	}
	if len(f.Channels) != 1 || !reflect.DeepEqual(f.Channels[0].Names, []string{"5.1"}) || f.Channels[0].Logo == "" || len(f.Programmes) == 0 || f.Dropped != 0 {
		t.Errorf("one channel feed: %+v with %d programmes", f.Channels, len(f.Programmes))
	}
}
