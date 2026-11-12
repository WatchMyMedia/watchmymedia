// Package activity records who watches what: the plays running now, a
// history of every play that searches as fast as you type, and statistics
// over it (PLAN.md §10b). The server is the source, so nothing is guessed:
// players announce each play and report their state, bytes are counted where
// they are written, and ffmpeg reports its own speed.
package activity

import (
	"errors"
	"time"

	"watchmymedia/server/internal/playback"
)

// Play states a player reports. A play also ends ("stopped" in the table)
// when the viewer leaves, the page closes, or nothing is heard for Timeout.
const (
	StatePlaying   = "playing"
	StatePaused    = "paused"
	StateBuffering = "buffering"
	StateStopped   = "stopped"
	// StateClosed is a stop sent as the page unloads.
	StateClosed = "closed"
)

// Why a play ended.
const (
	EndStopped    = "stopped"
	EndEnded      = "ended"
	EndClosed     = "closed"
	EndTimeout    = "timeout"
	EndTerminated = "terminated"
)

const (
	// Timeout ends a play that has not reported for this long. It ends at
	// its last report, not at the timeout.
	Timeout = 2 * time.Minute
	// Players report every playInterval while playing and pauseInterval
	// while paused or buffering; time between reports counts up to twice
	// that, so a stalled tab cannot pile up played time.
	playInterval  = 10 * time.Second
	pauseInterval = 30 * time.Second
	// writeEvery bounds what a crash can lose.
	writeEvery = 30 * time.Second
	// publishEvery limits position updates per play on the event stream.
	publishEvery = 5 * time.Second
	// groupSlack lets a resumed play start a little before where the last
	// one ended and still count as picking up from it.
	groupSlack = 30 * time.Second
)

var (
	// ErrUnknownPlay means the play is not running: it ended, timed out, or
	// the server restarted. The caller starts a new one.
	ErrUnknownPlay = errors.New("no such play")
	ErrStaleSwitch = errors.New("quality switch generation is stale")
)

// TerminatedError is returned for a play an admin stopped.
type TerminatedError struct{ Message string }

func (e *TerminatedError) Error() string {
	if e.Message == "" {
		return "this play was stopped by the server's admin"
	}
	return "this play was stopped by the server's admin: " + e.Message
}

// Config is the Activity section of the settings.
type Config struct {
	// MinPlay drops plays shorter than this when they end, unless they
	// reached the credits. Zero keeps every play.
	MinPlay time.Duration
	// Group joins a play that picks up an unfinished one into its row.
	Group bool
	// Household lets every user see what everyone is playing now (history
	// and statistics stay private).
	Household bool
	// RetentionDays deletes plays older than this many days; 0 keeps them.
	RetentionDays int
	// TrimStats also deletes the daily totals of trimmed days.
	TrimStats bool
}

// DefaultConfig is Tautulli's: plays under two minutes are not kept, and
// resumed plays are grouped.
func DefaultConfig() Config {
	return Config{MinPlay: 2 * time.Minute, Group: true}
}

// Item is what was played, as the history keeps it: a movie, an episode, or
// a file not matched to either.
type Item struct {
	Type          string `json:"type" enum:"movie,episode,file"`
	ItemID        int64  `json:"-"`
	MovieID       *int64 `json:"movieId,omitempty"`
	ShowID        *int64 `json:"showId,omitempty"`
	EpisodeID     *int64 `json:"episodeId,omitempty"`
	LibraryID     *int64 `json:"libraryId,omitempty"`
	Title         string `json:"title"`
	ShowTitle     string `json:"showTitle,omitempty"`
	SeasonNumber  *int64 `json:"seasonNumber,omitempty"`
	EpisodeNumber *int64 `json:"episodeNumber,omitempty"`
	Year          *int64 `json:"year,omitempty"`
	PosterPath    string `json:"posterPath,omitempty"`
	// ArtPath is the episode still, else the movie's backdrop.
	ArtPath string `json:"artPath,omitempty"`
}

// MediaInfo summarises one side of a stream: the source file, or what the
// client receives.
type MediaInfo struct {
	Container     string `json:"container,omitempty"`
	VideoCodec    string `json:"videoCodec,omitempty"`
	Width         int    `json:"width,omitempty"`
	Height        int    `json:"height,omitempty"`
	HDR           string `json:"hdr,omitempty"`
	Bitrate       int64  `json:"bitrate,omitempty" doc:"Bits per second."`
	AudioCodec    string `json:"audioCodec,omitempty"`
	AudioChannels int    `json:"audioChannels,omitempty"`
	AudioLanguage string `json:"audioLanguage,omitempty"`
	Subtitle      string `json:"subtitle,omitempty" doc:"Language and format of the subtitle shown, if any."`
	// For the stream side: copy or transcode, and how subtitles arrive.
	VideoDecision string `json:"videoDecision,omitempty" enum:",copy,transcode"`
	AudioDecision string `json:"audioDecision,omitempty" enum:",copy,transcode"`
	SubtitleMode  string `json:"subtitleMode,omitempty" enum:",text,burn"`
}

// Stream is how a play is delivered, as last decided.
type Stream struct {
	Mode    string    `json:"mode" enum:"direct,remux,audio,transcode"`
	Reasons []string  `json:"reasons" nullable:"false"`
	Source  MediaInfo `json:"source"`
	Stream  MediaInfo `json:"stream"`
	Encoder string    `json:"encoder,omitempty"`
	Quality string    `json:"quality,omitempty"`
	// Step is the step an Automatic play's stream was decided at.
	Step string `json:"step,omitempty" enum:",original,2160,1080,720,480,360"`
	// StepReason is why the server started at that step.
	StepReason string `json:"-"`
	// SessionID is the HLS session, for transcoder progress.
	SessionID    string                   `json:"-"`
	Adaptive     *playback.AdaptiveStream `json:"-"`
	AutoRecovery bool                     `json:"-"`
	OriginalBps  int64                    `json:"-"`
}

// AutoSwitch is a step down Automatic quality made, or for a fixed quality
// would have made.
type AutoSwitch struct {
	At         time.Time `json:"at"`
	From       string    `json:"from"`
	To         string    `json:"to"`
	Reason     string    `json:"reason" example:"buffer draining, measured 3.1 Mbps"`
	Server     bool      `json:"server" doc:"The transcoder, not the network, could not keep up."`
	Generation uint64    `json:"generation,omitempty" required:"false" doc:"Negotiated switch generation; absent for older players."`
	Outcome    string    `json:"outcome,omitempty" required:"false" doc:"Negotiated switch state: proposed, applied, failed or canceled."`
}

// PlayAuto is a play's Automatic quality, as history keeps it.
type PlayAuto struct {
	On       bool         `json:"on" doc:"The viewer picked Automatic. Otherwise this is what Automatic would have done at the quality they picked."`
	Step     string       `json:"step" doc:"The step the stream is at."`
	Floor    bool         `json:"floor" doc:"At the lowest step and still stalling."`
	Switches []AutoSwitch `json:"switches" nullable:"false" doc:"Oldest first. For a fixed quality, the first step down Automatic would have taken for each stream."`
}

// LiveAuto is Automatic quality for a play in progress.
type LiveAuto struct {
	PlayAuto
	Estimate int64 `json:"estimate" doc:"The link speed Automatic works from, in bits per second; 0 before it knows."`
}

// Device is the app or browser a play runs on.
type Device struct {
	ID       int64  `json:"id,omitempty"`
	Key      string `json:"-"`
	Name     string `json:"name"`
	Product  string `json:"product,omitempty"`
	Platform string `json:"platform,omitempty"`
	Version  string `json:"version,omitempty"`
}

// Report is a player's heartbeat.
type Report struct {
	RenditionHeight  int
	RenditionBitrate int64
	PositionMs       int64
	State            string
	BufferedMs       int64
	// What players measure for Automatic quality: their own estimate of the
	// link (0 when they have none), rebuffers since the decision (-1 when
	// they do not count them) and how long those took, and the time from
	// the decision to its first frame.
	ThroughputBps          int64
	Stalls                 int
	StallMs                int64
	StartupMs              int64
	ThroughputAgeMs        *int64
	SwitchGeneration       uint64
	SwitchFailedGeneration uint64
}

// Directive tells a player what to do: stop, with a message from the admin,
// or, playing at Automatic quality, reload at a lower step.
type Directive struct {
	Action  string `json:"action" enum:"stop,quality"`
	Message string `json:"message,omitempty"`
	// Quality is the step to reload at, for quality: the player asks for
	// the decision again at its position, with quality auto and the same
	// playId, and the server applies the step.
	Quality    string `json:"quality,omitempty" enum:",original,2160,1080,720,480,360"`
	Generation uint64 `json:"generation,omitempty" required:"false" doc:"Negotiated quality switch generation. Older step-down-only players do not receive it."`
}
