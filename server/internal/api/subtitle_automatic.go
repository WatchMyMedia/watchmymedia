package api

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/danielgtaylor/huma/v2"
	"golang.org/x/text/language"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/opensubtitles"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/subtitles"
)

type AutomaticSubtitleInput struct {
	ID   int64 `path:"id"`
	Body struct {
		PlayID string `json:"playId" pattern:"^[0-9a-f]{16}$" doc:"An active play started by this sign-in with subtitleMode auto, with automatic download enabled."`
		Cancel bool   `json:"cancel,omitempty" required:"false" doc:"Cancel this play's pending automatic attempt after a manual subtitle choice. Does not restart the video or contact the provider."`
	}
}

// AutomaticSubtitleResult never interrupts playback. Provider failures are a
// safe note, and a missing track means there is no suitable exact-file match.
type AutomaticSubtitleResult struct {
	Track *playback.SubtitleTrack `json:"track,omitempty" required:"false"`
	Error string                  `json:"error,omitempty" required:"false" doc:"Why an optional automatic download failed; playback continues without it."`
}

type AutomaticSubtitleOutput struct{ Body AutomaticSubtitleResult }

const (
	automaticSubtitleLifetime   = 12 * time.Hour
	automaticSubtitleTimeout    = 90 * time.Second
	maxAutomaticSubtitlePlays   = 1024
	maxAutomaticSubtitleRunning = 4
)

type automaticSubtitlePlay struct {
	user     auth.User
	fileID   int64
	at       time.Time
	request  playback.Request
	eligible bool
	done     chan struct{}
	result   AutomaticSubtitleResult
}

// One optional attempt per play, retained only in bounded memory. No jobs are
// launched by deciding a stream; the player asks after its first frame. A
// restart forgets these entries and never resumes quota-spending work.
type automaticSubtitlePlays struct {
	mu      sync.Mutex
	plays   map[string]*automaticSubtitlePlay
	running int
	now     func() time.Time
}

func newAutomaticSubtitlePlays() *automaticSubtitlePlays {
	return &automaticSubtitlePlays{plays: map[string]*automaticSubtitlePlay{}, now: time.Now}
}

func (s *automaticSubtitlePlays) remember(user auth.User, fileID int64, playID string, req playback.Request, eligible bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	old := s.plays[playID]
	if old != nil && !old.owns(user, fileID) {
		return
	}
	if user.SignInID == "" {
		return
	}
	if old != nil {
		old.eligible = eligible
		// A quality/seek reload does not buy another attempt.
		if old.done == nil {
			old.request = req
		}
		return
	}
	if !eligible {
		return
	}
	now := s.now()
	for id, p := range s.plays {
		if now.Sub(p.at) >= automaticSubtitleLifetime {
			if p.done != nil {
				select {
				case <-p.done:
				default:
					// Even a wall-clock jump cannot evict an in-flight
					// attempt and make its quota-spending replayable.
					continue
				}
			}
			delete(s.plays, id)
		}
	}
	if len(s.plays) >= maxAutomaticSubtitlePlays {
		// Fail closed under pressure: forgetting an existing play could
		// otherwise let a replay purchase another automatic download.
		return
	}
	s.plays[playID] = &automaticSubtitlePlay{user: user, fileID: fileID, at: now, request: req, eligible: true}
}

func (p *automaticSubtitlePlay) owns(user auth.User, fileID int64) bool {
	return p.user.ID == user.ID && p.user.SignInID == user.SignInID && p.fileID == fileID
}

func (s *automaticSubtitlePlays) cancel(user auth.User, fileID int64, playID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	p := s.plays[playID]
	if p == nil || !p.owns(user, fileID) || s.now().Sub(p.at) >= automaticSubtitleLifetime {
		return huma.Error409Conflict("this play cannot cancel an automatic subtitle")
	}
	// Retain its outcome/attempt marker so cancelling and then choosing
	// Automatic again cannot purchase a second download for the same play.
	p.eligible = false
	return nil
}

func (s *automaticSubtitlePlays) run(ctx context.Context, user auth.User, fileID int64, playID string, work func(context.Context, playback.Request) AutomaticSubtitleResult) (AutomaticSubtitleResult, error) {
	s.mu.Lock()
	p := s.plays[playID]
	if p == nil || !p.eligible || !p.owns(user, fileID) || s.now().Sub(p.at) >= automaticSubtitleLifetime {
		s.mu.Unlock()
		return AutomaticSubtitleResult{}, huma.Error409Conflict("this play cannot request an automatic subtitle")
	}
	if p.done != nil {
		s.mu.Unlock()
		select {
		case <-p.done:
			return s.result(playID, p), nil
		case <-ctx.Done():
			return AutomaticSubtitleResult{}, ctx.Err()
		}
	}
	if s.running >= maxAutomaticSubtitleRunning {
		s.mu.Unlock()
		return AutomaticSubtitleResult{}, huma.Error503ServiceUnavailable("automatic subtitle downloads are busy")
	}
	p.done = make(chan struct{})
	s.running++
	req := p.request
	s.mu.Unlock()
	// The first caller owns this bounded attempt; cancelling one HTTP
	// request cannot poison the other callers waiting for its outcome.
	workCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), automaticSubtitleTimeout)
	result := work(workCtx, req)
	cancel()
	s.mu.Lock()
	p.result = result
	s.running--
	close(p.done)
	s.mu.Unlock()
	return s.result(playID, p), nil
}

func (s *automaticSubtitlePlays) result(playID string, p *automaticSubtitlePlay) AutomaticSubtitleResult {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.plays[playID] != p || !p.eligible {
		// A manual pick/Off while the provider worked wins.
		return AutomaticSubtitleResult{}
	}
	return p.result
}

func registerAutomaticSubtitle(h huma.API, deps Deps) {
	huma.Register(h, huma.Operation{
		OperationID: "automaticSubtitle",
		Method:      http.MethodPost,
		Path:        Prefix + "/files/{id}/subtitles/automatic",
		Summary:     "Try one exact-file subtitle download for an opted-in active play",
		Description: "Call in the background after playback starts, only while automatic subtitle selection remains active. Validates the original sign-in, active play, current preferences and download permission. Searches the first preferred language and downloads only a hash match. Repeated calls share and reuse one attempt. Send cancel true after a manual subtitle choice to prevent a pending search spending quota; cancellation does not contact the provider and remains available if download permission changes. A provider download already started cannot be unspent. Provider failures return an error note without failing playback. A player must discard a late result after any manual subtitle choice, play change or sign-out. Cast and media tokens cannot call this quota-spending route.",
		Tags:        []string{"playback"},
	}, func(ctx context.Context, in *AutomaticSubtitleInput) (*AutomaticSubtitleOutput, error) {
		user, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		if deps.Activity == nil || deps.Activity.Known(in.Body.PlayID, user.ID, in.ID) != nil {
			return nil, huma.Error409Conflict("this play is no longer active")
		}
		if in.Body.Cancel {
			if err := deps.automaticSubtitles.cancel(user, in.ID, in.Body.PlayID); err != nil {
				return nil, err
			}
			return &AutomaticSubtitleOutput{}, nil
		}
		if !deps.OnlineSubtitles.Available() {
			return nil, huma.Error503ServiceUnavailable("online subtitles are not available on this server")
		}
		if !effectiveAdmin(ctx, user) && deps.OnlineSubtitles.Downloads(ctx) != subtitles.DownloadsEveryone {
			return nil, huma.Error403Forbidden("only admins can download subtitles on this server")
		}
		result, err := deps.automaticSubtitles.run(ctx, user, in.ID, in.Body.PlayID, func(workCtx context.Context, req playback.Request) AutomaticSubtitleResult {
			return automaticSubtitle(workCtx, deps, user, in.ID, in.Body.PlayID, req)
		})
		if err != nil {
			return nil, err
		}
		return &AutomaticSubtitleOutput{Body: result}, nil
	})
}

func automaticSubtitle(ctx context.Context, deps Deps, user auth.User, fileID int64, playID string, req playback.Request) AutomaticSubtitleResult {
	p, err := loadPreferences(ctx, deps, user.ID)
	if err != nil {
		return AutomaticSubtitleResult{Error: "Couldn't check your subtitle preferences."}
	}
	if !p.Subtitles.AutomaticDownload || len(p.Subtitles.Languages) == 0 ||
		(p.Subtitles.Mode != "on" && (p.Subtitles.Mode != "forced" || !p.Subtitles.ForeignAudio)) {
		return AutomaticSubtitleResult{}
	}
	row, _, info, err := playableFile(ctx, deps, fileID)
	if err != nil {
		return AutomaticSubtitleResult{}
	}
	// Another play may have downloaded a local match since admission.
	d, _ := decideTracks(ctx, deps, row, info, req, playback.FindSidecars(row.Path), playback.Capabilities{}, p.tracks())
	audioLanguage := selectedAudioLanguage(d)
	// foreignAudio downloads only for audio the person doesn't follow.
	if mode, _ := playback.StartingSubtitles(audioLanguage, p.Audio.Languages, p.Subtitles); mode != "on" {
		return AutomaticSubtitleResult{}
	}
	if d.SubtitleID != "" {
		for _, track := range d.Subtitles {
			if track.ID == d.SubtitleID {
				if track.Format == "image" {
					return AutomaticSubtitleResult{}
				}
				track.URL = subtitleTrackURL(fileID, track.ID)
				return AutomaticSubtitleResult{Track: &track}
			}
		}
	}
	want := p.Subtitles.Languages[0]
	search := deps.OnlineSubtitles.Search(ctx, fileID, []string{providerSubtitleLanguage(want)})
	if search.Error != "" {
		return AutomaticSubtitleResult{Error: "Couldn't search for an automatic subtitle."}
	}
	var selected *subtitles.OnlineSubtitle
	for _, result := range search.Results {
		if !result.HashMatch || playback.LanguageMatch(result.Language, want) == 0 {
			continue
		}
		if selected == nil || (selected.Forced && !result.Forced) ||
			(selected.Forced == result.Forced && playback.LanguageMatch(result.Language, want) > playback.LanguageMatch(selected.Language, want)) {
			copy := result
			selected = &copy
		}
	}
	if selected == nil {
		return AutomaticSubtitleResult{}
	}
	// Search may take time: recheck the original session, current prefs,
	// permission, play and manual selection immediately before spending quota.
	if !automaticSubtitleStillAllowed(ctx, deps, user, fileID, playID, want, audioLanguage) {
		return AutomaticSubtitleResult{}
	}
	if search.Quota.Remaining == 0 && selected.TrackID == "" {
		return AutomaticSubtitleResult{Error: "No subtitle downloads left today."}
	}
	kept, err := deps.OnlineSubtitles.Download(ctx, fileID, selected.ID, user.ID)
	if errors.Is(err, opensubtitles.ErrQuota) {
		return AutomaticSubtitleResult{Error: "No subtitle downloads left today."}
	}
	if err != nil {
		deps.Log.WarnContext(ctx, "automatic subtitle download failed", "file", fileID, "err", err)
		return AutomaticSubtitleResult{Error: "Couldn't download an automatic subtitle."}
	}
	if !automaticSubtitleStillAllowed(ctx, deps, user, fileID, playID, want, audioLanguage) {
		return AutomaticSubtitleResult{}
	}
	track := downloadedTrack(kept)
	track.URL = subtitleTrackURL(fileID, track.ID)
	return AutomaticSubtitleResult{Track: &track}
}

func automaticSubtitleStillAllowed(ctx context.Context, deps Deps, user auth.User, fileID int64, playID string, language, audioLanguage string) bool {
	if deps.Activity == nil || deps.Activity.Known(playID, user.ID, fileID) != nil {
		return false
	}
	if deps.Auth != nil {
		fresh, err := deps.Auth.ValidateSignIn(ctx, user.SignInID)
		if err != nil || fresh.ID != user.ID {
			return false
		}
		user = fresh
	}
	if !effectiveAdmin(ctx, user) && deps.OnlineSubtitles.Downloads(ctx) != subtitles.DownloadsEveryone {
		return false
	}
	p, err := loadPreferences(ctx, deps, user.ID)
	if err != nil || !p.Subtitles.AutomaticDownload || len(p.Subtitles.Languages) == 0 || p.Subtitles.Languages[0] != language {
		return false
	}
	if mode, _ := playback.StartingSubtitles(audioLanguage, p.Audio.Languages, p.Subtitles); mode != "on" {
		return false
	}
	s := deps.automaticSubtitles
	s.mu.Lock()
	defer s.mu.Unlock()
	current := s.plays[playID]
	return current != nil && current.eligible && current.owns(user, fileID)
}

func subtitleTrackURL(fileID int64, trackID string) string {
	return Prefix + "/files/" + strconv.FormatInt(fileID, 10) + "/subtitles/" + trackID + ".vtt"
}

// OpenSubtitles names a few regions itself; other BCP 47 preferences use
// their base language for search and still match against the full preference.
func providerSubtitleLanguage(want string) string {
	lower := strings.ToLower(want)
	switch lower {
	case "pt-br", "pt-pt", "zh-cn", "zh-tw":
		return lower
	}
	tag, _ := language.Parse(want)
	base, _ := tag.Base()
	return base.String()
}
