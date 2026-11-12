package livetv

import "time"

// Programme is one listing on a channel (PLAN-LIVE-TV-GUIDE.md §4.1). The
// provider may leave out any field but the first four, and every field added
// later is optional too.
type Programme struct {
	SeriesKey    string    `json:"seriesKey,omitempty" required:"false" doc:"The provider series identity, for recording a series (API level 19)."`
	ID           string    `json:"id" doc:"Stable for this channel and start time."`
	Start        time.Time `json:"start"`
	End          time.Time `json:"end"`
	Title        string    `json:"title"`
	EpisodeTitle string    `json:"episodeTitle,omitempty" required:"false"`
	Season       *int      `json:"season,omitempty" required:"false" doc:"A season the provider numbers by year is that year, such as 2026."`
	Episode      *int      `json:"episode,omitempty" required:"false"`
	EpisodeLabel string    `json:"episodeLabel,omitempty" required:"false" doc:"The provider's own episode numbering when it isn't a season and episode."`
	AirDate      string    `json:"airDate,omitempty" required:"false" doc:"The original air date as YYYY-MM-DD, or a movie's year as YYYY."`
	Kind         string    `json:"kind,omitempty" required:"false" doc:"episode, show (a programme without episodes, such as news) or movie. Other values may follow."`
	New          bool      `json:"new,omitempty" required:"false"`
	Live         bool      `json:"live,omitempty" required:"false"`
	Repeat       bool      `json:"repeat,omitempty" required:"false"`
	HD           bool      `json:"hd,omitempty" required:"false"`
	Rating       string    `json:"rating,omitempty" required:"false" doc:"A parental rating, when the provider gives one."`
	Categories   []string  `json:"categories,omitempty" required:"false" nullable:"false"`
	Description  string    `json:"description,omitempty" required:"false"`
	// Sources say where a description or picture came from, so clients can
	// credit TMDB's.
	DescriptionSource string `json:"descriptionSource,omitempty" required:"false" doc:"provider or tmdb."`
	PictureURL        string `json:"pictureUrl,omitempty" required:"false" doc:"A 16:9 picture: a path on this server, fetched with the usual authentication."`
	PictureSource     string `json:"pictureSource,omitempty" required:"false" doc:"provider or tmdb."`
	PosterURL         string `json:"posterUrl,omitempty" required:"false" doc:"A movie's 2:3 poster: a path on this server."`
	// The provider's picture is the series' card on every episode, so a
	// matched TMDB still goes first where there is room for it.
	StillURL string `json:"stillUrl,omitempty" required:"false" doc:"A TMDB episode still or movie backdrop matched to this programme: a path on this server. The wide details panel and the TVs' hero show it before pictureUrl, credited as from TMDB; everywhere else shows pictureUrl."`
	// The viewer's own (§5.7).
	Reminder  bool           `json:"reminder,omitempty" required:"false" doc:"The viewer has a reminder for this programme (API level 15), in the guide and search replies."`
	Recording *RecordingMark `json:"recording,omitempty" required:"false" doc:"Household recording state, only for people who may record (API level 19)."`
}

type RecordingMark struct {
	ID        string `json:"id"`
	State     string `json:"state"`
	Series    bool   `json:"series"`
	CanCancel bool   `json:"canCancel" required:"false"`
	// Clients draw the record dots only for scheduled, recording and
	// conflict; the other states explain why a programme won't record.
	Reason string `json:"reason,omitempty" required:"false" doc:"Why the programme won't record or didn't, a reason code such as already_recorded, already_scheduled, in_library, not_new, tuners_busy or listing_gone (API level 19)."`
}
