// Package dvr owns durable recording schedules and only the files they create.
package dvr

import (
	"errors"
	"log/slog"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/scanner"
	"watchmymedia/server/internal/settings"
	"watchmymedia/server/internal/watchstate"
)

const ChangedEvent = "live.recordings.changed"

type Changed struct{}

var ErrInvalid = errors.New("invalid recording request")
var ErrUnavailable = errors.New("recording is unavailable")
var ErrDenied = errors.New("recording permission required")
var ErrMissing = errors.New("recording not found")

// Padding is in minutes. Times are UTC instants; repeat rules retain a local
// wall clock and IANA zone so daylight saving changes do not move a programme.
type Settings struct {
	Enabled        bool   `json:"enabled"`
	Access         string `json:"access" enum:"none,admins,everyone"`
	TVLibraryID    int64  `json:"tvLibraryId"`
	TVPath         string `json:"tvPath"`
	MovieLibraryID int64  `json:"movieLibraryId,omitempty" required:"false"`
	MoviePath      string `json:"moviePath,omitempty" required:"false"`
	PadBefore      int    `json:"padBefore" minimum:"0" maximum:"60"`
	PadAfter       int    `json:"padAfter" minimum:"0" maximum:"60"`
	MinFreeBytes   int64  `json:"minFreeBytes" minimum:"0"`
	WarningSeconds int    `json:"warningSeconds" minimum:"0" maximum:"600"`
	FindAds        bool   `json:"findAds"`
	TunersReserved int    `json:"tunersReserved" minimum:"0" maximum:"16"`
	WakeSupported  bool   `json:"wakeSupported" required:"false"`
}
type Gap struct {
	Start  time.Time `json:"start"`
	End    time.Time `json:"end"`
	Reason string    `json:"reason"`
}
type Recording struct {
	ID           string     `json:"id"`
	RuleID       string     `json:"ruleId,omitempty" required:"false"`
	UserID       *int64     `json:"userId,omitempty" required:"false"`
	UserName     string     `json:"userName"`
	SourceID     string     `json:"sourceId"`
	ChannelID    string     `json:"channelId"`
	Number       string     `json:"number"`
	ChannelName  string     `json:"channelName"`
	ProgrammeID  string     `json:"programmeId,omitempty" required:"false"`
	Title        string     `json:"title"`
	EpisodeTitle string     `json:"episodeTitle,omitempty" required:"false"`
	Season       *int       `json:"season,omitempty" required:"false"`
	Episode      *int       `json:"episode,omitempty" required:"false"`
	Start        time.Time  `json:"start"`
	End          time.Time  `json:"end"`
	StartedAt    *time.Time `json:"startedAt,omitempty" required:"false"`
	EndedAt      *time.Time `json:"endedAt,omitempty" required:"false"`
	State        string     `json:"state"`
	Reason       string     `json:"reason,omitempty" required:"false"`
	Series       bool       `json:"series"`
	PadBefore    int        `json:"padBefore"`
	PadAfter     int        `json:"padAfter"`
	Bytes        int64      `json:"bytes"`
	Gaps         []Gap      `json:"gaps" nullable:"false"`
	MediaFileID  *int64     `json:"mediaFileId,omitempty" required:"false"`
	CanCancel    bool       `json:"canCancel"`
	CanDelete    bool       `json:"canDelete" required:"false" doc:"The current delete policy permits removing this finished recording's owned file."`
	PictureURL   string     `json:"pictureUrl,omitempty" required:"false"`
	// ConflictsWith is the plan's snapshot of who holds the tuners, so a
	// clash can be shown and resolved without another request per row.
	ConflictsWith []ConflictRef `json:"conflictsWith,omitempty" required:"false" nullable:"false" doc:"For a recording that won't record (state conflict): the recordings holding the tuners while it would run. Choosing one with PATCH {prefer, over} makes that one give way."`

	// New, Repeat and Categories are copied from the listing when the
	// recording is scheduled, as the rest of its listing is (API level 34).
	New        bool     `json:"new,omitempty" required:"false" doc:"The listing called it new. From API level 34; absent for a recording scheduled before, whose listing was gone."`
	Repeat     bool     `json:"repeat,omitempty" required:"false" doc:"The listing called it a repeat (API level 34)."`
	Categories []string `json:"categories,omitempty" required:"false" nullable:"false" doc:"The listing's categories, such as Documentary or Sports (API level 34)."`

	// The fields below are worked out for each reply and never stored.
	ProgrammeKind string          `json:"kind,omitempty" required:"false" doc:"What was recorded, from its listing: episode, show (a programme without episodes, such as news) or movie; absent for a recording made by time. Other values may follow (API level 34)."`
	Year          int             `json:"year,omitempty" required:"false" doc:"A movie's year, or the year an episode first aired, from the listing (API level 34)."`
	Progress      *ViewerProgress `json:"progress,omitempty" required:"false" doc:"Where the signed-in person is in it: its own position while it isn't in the library, then the watch state of the movie or episode it became. Absent when they haven't started it (API level 34)."`
	Watched       bool            `json:"watched,omitempty" required:"false" doc:"The signed-in person finished the movie or episode it became (API level 34)."`
	NextAiring    *Airing         `json:"nextAiring,omitempty" required:"false" doc:"For a repeat that won't record (state conflict): when the same episode airs again in the listings (API level 34)."`
}

// ViewerProgress is how far one person has watched a recording.
type ViewerProgress struct {
	PositionMs int64 `json:"positionMs"`
	DurationMs int64 `json:"durationMs"`
}

// Airing is another showing of a programme in the listings.
type Airing struct {
	ChannelID   string    `json:"channelId"`
	Number      string    `json:"number"`
	ChannelName string    `json:"channelName"`
	ProgrammeID string    `json:"programmeId"`
	Start       time.Time `json:"start"`
}

// ConflictRef names a recording that holds a tuner another one needed.
type ConflictRef struct {
	ID          string    `json:"id"`
	Title       string    `json:"title"`
	Number      string    `json:"number"`
	ChannelName string    `json:"channelName"`
	Start       time.Time `json:"start"`
	End         time.Time `json:"end"`
	UserName    string    `json:"userName"`
	Running     bool      `json:"running" doc:"Already recording, so it can't give way."`
	Repeat      bool      `json:"repeat,omitempty" required:"false" doc:"Its listing called it a repeat (API level 34)."`
	// NextAiring is worked out for each reply and never stored.
	NextAiring *Airing `json:"nextAiring,omitempty" required:"false" doc:"For a repeat: when the same episode airs again in the listings, so it could give way and be recorded then (API level 34)."`
}
type RecordingRule struct {
	ID         string     `json:"id"`
	Kind       string     `json:"kind"`
	Title      string     `json:"title"`
	UserID     *int64     `json:"userId,omitempty" required:"false"`
	UserName   string     `json:"userName"`
	ChannelID  string     `json:"channelId,omitempty" required:"false"`
	Number     string     `json:"number,omitempty" required:"false"`
	SeriesKey  string     `json:"seriesKey,omitempty" required:"false"`
	NewOnly    bool       `json:"newOnly"`
	AnyChannel bool       `json:"anyChannel"`
	Keep       int        `json:"keep"`
	PadBefore  int        `json:"padBefore"`
	PadAfter   int        `json:"padAfter"`
	Repeat     string     `json:"repeat,omitempty" required:"false"`
	Days       int        `json:"days,omitempty" required:"false"`
	Zone       string     `json:"zone,omitempty" required:"false"`
	Start      *time.Time `json:"start,omitempty" required:"false"`
	End        *time.Time `json:"end,omitempty" required:"false"`
	CanCancel  bool       `json:"canCancel"`
}
type Recordings struct {
	Items      []Recording     `json:"items" nullable:"false"`
	Rules      []RecordingRule `json:"rules,omitempty" required:"false" nullable:"false"`
	NextCursor string          `json:"nextCursor,omitempty" required:"false"`
	Tuners     int             `json:"tuners,omitempty" required:"false"`
	FreeBytes  int64           `json:"freeBytes,omitempty" required:"false"`
	// Counts, ReserveBytes and TotalBytes come with the first page, as
	// Tuners and FreeBytes do (API level 34).
	Counts       *SectionCounts `json:"counts,omitempty" required:"false" doc:"The recordings in each section and their bytes, and the rules, for the tabs. On the first page only (API level 34)."`
	ReserveBytes int64          `json:"reserveBytes,omitempty" required:"false" doc:"Space the recorder keeps free on the disk: freeBytes less this is the room recordings can use. On the first page, with freeBytes (API level 34)."`
	TotalBytes   int64          `json:"totalBytes,omitempty" required:"false" doc:"The size of the disk recordings go to. On the first page, with freeBytes (API level 34)."`
}

// SectionCounts are the recordings in each section of Recordings.
type SectionCounts struct {
	Now       SectionCount `json:"now"`
	Conflicts SectionCount `json:"conflicts"`
	Upcoming  SectionCount `json:"upcoming"`
	Recorded  SectionCount `json:"recorded"`
	Series    SectionCount `json:"series" doc:"The recording rules; bytes is 0."`
}

// SectionCount is one section's recordings and the bytes they hold.
type SectionCount struct {
	Items int64 `json:"items"`
	Bytes int64 `json:"bytes"`
}
type Create struct {
	ProgrammeID string `json:"programmeId" minLength:"1" maxLength:"128"`
	Scope       string `json:"scope" enum:"once,series"`
	NewOnly     *bool  `json:"newOnly,omitempty"`
	AnyChannel  bool   `json:"anyChannel,omitempty"`
	Keep        int    `json:"keep,omitempty" minimum:"0" maximum:"10"`
	PadBefore   *int   `json:"padBefore,omitempty" minimum:"0" maximum:"60"`
	PadAfter    *int   `json:"padAfter,omitempty" minimum:"0" maximum:"60"`
}
type Manual struct {
	ChannelID string    `json:"channelId" minLength:"1" maxLength:"128"`
	Start     time.Time `json:"start"`
	End       time.Time `json:"end"`
	Repeat    string    `json:"repeat" enum:"once,daily,weekdays,weekly"`
	Days      int       `json:"days,omitempty" minimum:"0" maximum:"127"`
	Zone      string    `json:"zone" maxLength:"128"`
	Name      string    `json:"name" minLength:"1" maxLength:"200"`
}
type Update struct {
	PadBefore *int `json:"padBefore,omitempty" minimum:"0" maximum:"60"`
	PadAfter  *int `json:"padAfter,omitempty" minimum:"0" maximum:"60"`
	Prefer    bool `json:"prefer,omitempty"`
	// Over names the clashing recording that gives way: the two swap places
	// in the plan, and every other recording keeps its tuner.
	Over string `json:"over,omitempty" required:"false" maxLength:"128" doc:"With prefer: the clashing recording (one of conflictsWith, not yet recording) that gives way instead. Without it, this recording goes ahead of every recording not yet recording."`
}
type UpdateRule struct {
	NewOnly    *bool   `json:"newOnly,omitempty"`
	AnyChannel *bool   `json:"anyChannel,omitempty"`
	Keep       *int    `json:"keep,omitempty" minimum:"0" maximum:"10"`
	PadBefore  *int    `json:"padBefore,omitempty" minimum:"0" maximum:"60"`
	PadAfter   *int    `json:"padAfter,omitempty" minimum:"0" maximum:"60"`
	Name       *string `json:"name,omitempty" minLength:"1" maxLength:"200"`
}
type record struct {
	Recording
	SeriesKey     string
	Kind          string
	AirDate       string
	TMDBID        int
	TMDBKind      string
	Path          string
	PartialPath   string
	LibraryID     int64
	Priority      int64
	CreatedAt     int64
	FileMtimeNano int64
	RecordAgain   bool
	// FinalState and FinalReason are what a recording ends as once its
	// publication, checkpointed as partial and finish_pending, succeeds.
	FinalState  string
	FinalReason string
}
type rule struct {
	RecordingRule
	CreatedAt       int64
	LocalMinute     int
	DurationSeconds int64
	FirstDate       string
}
type Options struct {
	Now        func() time.Time
	FreeBytes  func(string) (uint64, error)
	TotalBytes func(string) (uint64, error)
	Log        *slog.Logger
	Scanner    *scanner.Scanner
	Bus        *events.Bus
	Watch      *watchstate.Service
}
type Source struct {
	Path      string
	Recording Recording
	Done      <-chan struct{}
}
type dependencies struct {
	q        *db.Queries
	store    *settings.Store
	live     *livetv.Service
	sessions *livetv.Sessions
	opts     Options
}
type sourceKey struct{ source, channel string }
type Actor = livetv.Owner

func (r record) from() time.Time  { return r.Start.Add(-time.Duration(r.PadBefore) * time.Minute) }
func (r record) until() time.Time { return r.End.Add(time.Duration(r.PadAfter) * time.Minute) }
func section(state string) string {
	switch state {
	case "recording":
		return "now"
	case "conflict":
		return "conflicts"
	case "scheduled", "gone":
		return "upcoming"
	case "done", "partial", "failed":
		return "recorded"
	default:
		return "archived"
	}
}
func owner(id *int64, a Actor) bool { return a.Admin || id != nil && *id == a.UserID }
func validPadding(before, after int) bool {
	return before >= 0 && before <= 60 && after >= 0 && after <= 60
}

//go:fix inline
