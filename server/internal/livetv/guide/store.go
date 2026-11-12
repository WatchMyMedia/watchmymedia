package guide

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"strconv"
	"strings"
	"time"
	"unicode"

	"golang.org/x/text/unicode/norm"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/livetv/guide/art"
	"watchmymedia/server/internal/livetv/guide/xmltv"
)

// keepEnded is how long a programme stays after it ends.
const keepEnded = 6 * time.Hour

// stored is what a refresh wrote.
type stored struct {
	programmes, matched, flagged, missing int
	// reminded are the people whose reminders the refresh moved or marked
	// gone.
	reminded []int64
}

// save matches the feed to the lineup and replaces the listed channels'
// programmes in one transaction, so readers see the old listings or the
// new. Programmes that ended before the new listings start are kept until
// they expire; a channel the feed no longer lists loses what is still to
// come.
func (s *Service) save(ctx context.Context, feed xmltv.Feed, now time.Time) (stored, error) {
	var out stored
	byFeed := map[string][]xmltv.Programme{}
	for _, p := range feed.Programmes {
		byFeed[p.Channel] = append(byFeed[p.Channel], p)
	}
	byNumber := map[string]*xmltv.Channel{}
	for i := range feed.Channels {
		c := &feed.Channels[i]
		if _, ok := byNumber[c.LCN]; !ok && c.LCN != "" {
			byNumber[c.LCN] = c
		}
	}
	err := s.q.Transaction(ctx, func(q *db.Queries) error {
		channels, err := q.ListLiveChannels(ctx)
		if err != nil {
			return err
		}
		for _, ch := range channels {
			fc := byNumber[ch.Number]
			if fc == nil {
				out.missing++
				if err := q.UpsertGuideChannel(ctx, db.UpsertGuideChannelParams{ChannelID: ch.ID}); err != nil {
					return err
				}
				if err := q.DeleteChannelProgrammesAfter(ctx, db.DeleteChannelProgrammesAfterParams{ChannelID: ch.ID, EndAt: now.Unix()}); err != nil {
					return err
				}
				continue
			}
			out.matched++
			by, flagged := matchName(ch.Name, fc.Names)
			if flagged {
				out.flagged++
			}
			programmes := byFeed[fc.ID]
			until := now.Add(48 * time.Hour).Unix()
			from := now.Unix()
			if len(programmes) > 0 {
				from = programmes[0].Start.Unix()
				until = max(until, programmes[len(programmes)-1].Stop.Unix())
			}
			logo, err := register(ctx, q, fc.Logo, until)
			if err != nil {
				return err
			}
			if err := q.UpsertGuideChannel(ctx, db.UpsertGuideChannelParams{ChannelID: ch.ID, ProviderID: fc.ID, FeedName: feedName(fc.Names), Logo: logo, MatchedBy: by, Flagged: flag(flagged)}); err != nil {
				return err
			}
			if err := q.DeleteChannelProgrammesAfter(ctx, db.DeleteChannelProgrammesAfterParams{ChannelID: ch.ID, EndAt: from}); err != nil {
				return err
			}
			for _, p := range programmes {
				if err := insert(ctx, q, ch.ID, p); err != nil {
					return err
				}
				out.programmes++
			}
		}
		if out.reminded, err = reconcile(ctx, q, channels, now); err != nil {
			return err
		}
		_, err = q.ExpireProgrammes(ctx, now.Add(-keepEnded).Unix())
		return err
	})
	if err == nil && s.afterSave != nil {
		s.afterSave(ctx)
	}
	return out, err
}

func insert(ctx context.Context, q *db.Queries, channel string, p xmltv.Programme) error {
	picture, err := register(ctx, q, p.Picture, p.Stop.Unix())
	if err != nil {
		return err
	}
	poster, err := register(ctx, q, p.Poster, p.Stop.Unix())
	if err != nil {
		return err
	}
	categories, _ := json.Marshal(append([]string{}, p.Categories...))
	row := db.InsertProgrammeParams{
		ID: programmeID(channel, p.Start), ChannelID: channel, StartAt: p.Start.Unix(), EndAt: p.Stop.Unix(),
		Title: p.Title, EpisodeTitle: p.EpisodeTitle, Season: number(p.Season), Episode: number(p.Episode),
		EpisodeLabel: p.EpisodeLabel, AirDate: p.AirDate, IsNew: flag(p.New), IsLive: flag(p.Live), IsRepeat: flag(p.Repeat),
		Hd: flag(p.HD), Rating: p.Rating, Categories: string(categories), Kind: p.Kind, SeriesKey: p.SeriesKey,
		Description: p.Description, Picture: picture, Poster: poster, Search: searchText(p.Title, p.EpisodeTitle),
	}
	if row.Description != "" {
		row.DescriptionSource = "provider"
	}
	if row.Picture != "" {
		row.PictureSource = "provider"
	}
	return q.InsertProgramme(ctx, row)
}

// register records a picture the feed names and returns its key, or ""
// for none or one from a host pictures may not come from.
func register(ctx context.Context, q *db.Queries, rawURL string, until int64) (string, error) {
	if rawURL == "" || !art.Allowed(rawURL) {
		return "", nil
	}
	key := art.Key(rawURL)
	return key, q.UpsertGuideArt(ctx, db.UpsertGuideArtParams{ID: key, Url: rawURL, UsedUntil: until})
}

// programmeID is stable for a channel and start, so a refresh that lists
// the same programme again gives it the same id.
func programmeID(channel string, start time.Time) string {
	sum := sha256.Sum256([]byte("silicondust\x00" + channel + "\x00" + strconv.FormatInt(start.Unix(), 10)))
	return hex.EncodeToString(sum[:12])
}

func number(n *int) *int64 {
	if n == nil {
		return nil
	}
	v := int64(*n)
	return &v
}

func flag(b bool) int64 {
	if b {
		return 1
	}
	return 0
}

// feedName is the feed's most telling name for a channel: the first that
// isn't the bare number or the number with a callsign.
func feedName(names []string) string {
	for _, n := range names {
		if n != "" && !unicode.IsDigit(rune(n[0])) {
			return n
		}
	}
	if len(names) > 0 {
		return names[0]
	}
	return ""
}

// matchName checks the lineup's name for a channel matched by number
// against the feed's display names (§5.3). It says how the channel matched
// and whether the names disagree. A tuner names channels it can't name
// "Unknown", which skips the check.
func matchName(guideName string, names []string) (by string, flagged bool) {
	want := canonical(guideName)
	if strings.EqualFold(strings.TrimSpace(guideName), "unknown") || want == "" {
		return "number", false
	}
	for _, n := range names {
		got := canonical(n)
		if got == "" {
			continue
		}
		if got == want || (len(got) >= 3 && len(want) >= 3 && (strings.Contains(got, want) || strings.Contains(want, got))) {
			return "name", false
		}
	}
	return "number", true
}

// canonical drops what the lineup and the feed write differently: case,
// accents' forms, a leading channel number, dashes and spaces, and
// suffixes such as -HD, DT and LD with a subchannel digit.
func canonical(name string) string {
	name = norm.NFKC.String(strings.ToUpper(strings.TrimSpace(name)))
	if i := strings.IndexByte(name, ' '); i > 0 && strings.Trim(name[:i], "0123456789.") == "" {
		name = name[i+1:]
	}
	var b strings.Builder
	for _, r := range name {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			b.WriteRune(r)
		}
	}
	s := b.String()
	for {
		t := strings.TrimRight(s, "0123456789")
		for _, suffix := range []string{"HD", "DT", "LD", "CD"} {
			t = strings.TrimSuffix(t, suffix)
		}
		if t == s || len(t) < 2 {
			return s
		}
		s = t
	}
}
