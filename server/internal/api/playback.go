package api

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/danielgtaylor/huma/v2"
	"github.com/go-chi/chi/v5"

	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/netguard"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/probe"
	"watchmymedia/server/internal/segments"
	"watchmymedia/server/internal/subtitles"
	"watchmymedia/server/internal/upload"
)

type PlaybackInput struct {
	ID   int64 `path:"id" doc:"Media file ID."`
	Body playback.Request
}

type PlaybackOutput struct{ Body playback.Decision }

type CapabilitiesOutput struct{ Body playback.Capabilities }

// LocalStatus is what GET /api/playback/local returns to the menu bar app.
type LocalStatus struct {
	Version       string `json:"version"`
	SetupRequired bool   `json:"setupRequired"`
	// SetupIncomplete is SetupRequired, or a server with an account but no
	// library and no saved tuner: the menu bar app sends people to /setup.
	SetupIncomplete bool                `json:"setupIncomplete"`
	Sessions        []*playback.Session `json:"sessions"`
	// Plays are every play running, direct play included.
	Plays []LocalPlay `json:"plays"`
}

// setUp reports whether the server has something to play: a library or a
// saved tuner, as the web's first-run setup asks for. A database error
// counts as set up, so the menu doesn't send anyone to setup over a hiccup.
func setUp(ctx context.Context, deps Deps) bool {
	if has, err := deps.Queries.HasLibraries(ctx); err != nil || has {
		return true
	}
	return deps.LiveTV != nil && deps.LiveTV.SetupComplete(ctx)
}

// LocalPlay is a running play as the menu bar shows it.
type LocalPlay struct {
	ID        string    `json:"id"`
	User      string    `json:"user"`
	Title     string    `json:"title"`
	Mode      string    `json:"mode"`
	State     string    `json:"state"`
	StartedAt time.Time `json:"startedAt"`
}

// playTitle is "Show · S01E02 Title" for an episode, else the title.
func playTitle(it activity.Item) string {
	if it.Type == "episode" && it.SeasonNumber != nil && it.EpisodeNumber != nil {
		return it.ShowTitle + " · " + seCode(*it.SeasonNumber, *it.EpisodeNumber) + " " + it.Title
	}
	return it.Title
}

type SessionIDInput struct {
	Session string `path:"session" doc:"Playback session ID."`
}

func registerPlayback(h huma.API, r *chi.Mux, deps Deps) {
	registerRecordingPlayback(h, deps)
	huma.Register(h, huma.Operation{
		OperationID: "startPlayback",
		Method:      http.MethodPost,
		Path:        Prefix + "/files/{id}/playback",
		Summary:     "Decide how to play a file and start a session if needed",
		Description: "Given the client's capability profile, returns the cheapest playback mode that works: direct play of the original file, an HLS remux, an audio-only transcode, or a full transcode. HLS modes start an ffmpeg session; a new request that sends back the returned playId replaces that play's session (used for seeking), while other people watching the same file keep theirs.",
		Tags:        []string{"playback"},
	}, func(ctx context.Context, in *PlaybackInput) (*PlaybackOutput, error) {
		return startFilePlayback(ctx, deps, in)
	})

	huma.Register(h, huma.Operation{
		OperationID: "getPlaybackCapabilities",
		Method:      http.MethodGet,
		Path:        Prefix + "/playback/capabilities",
		Summary:     "What the server's ffmpeg can do",
		Tags:        []string{"playback"},
	}, func(ctx context.Context, _ *struct{}) (*CapabilitiesOutput, error) {
		if deps.Playback == nil {
			return nil, huma.Error503ServiceUnavailable("transcoder not available")
		}
		return &CapabilitiesOutput{Body: deps.Playback.Capabilities()}, nil
	})

	// Status for the macOS menu bar app, which runs the server as its child:
	// answered only to loopback clients and without a login (see
	// exemptPath), and kept out of the OpenAPI spec. A proxy on this machine
	// is loopback too, so what it forwards is refused unless it is trusted
	// and says the client is local.
	r.Get(Prefix+"/playback/local", func(w http.ResponseWriter, req *http.Request) {
		if info, ok := netguard.From(req.Context()); !ok || !info.Loopback() {
			http.Error(w, "local clients only", http.StatusForbidden)
			return
		}
		out := LocalStatus{Version: deps.Version, Sessions: []*playback.Session{}, Plays: []LocalPlay{}}
		if deps.Playback != nil {
			out.Sessions = deps.Playback.Sessions()
		}
		if deps.Activity != nil {
			plays, _ := deps.Activity.Live(activity.Viewer{Admin: true})
			for _, p := range plays {
				out.Plays = append(out.Plays, LocalPlay{ID: p.ID, User: p.User.Name, Title: playTitle(p.Item), Mode: p.Stream.Mode, State: p.State, StartedAt: p.StartedAt})
			}
		}
		if deps.LiveSessions != nil {
			for _, p := range deps.LiveSessions.Activity(livetv.Owner{}, true) {
				out.Plays = append(out.Plays, LocalPlay{ID: p.ID, User: p.UserName, Title: p.Channel.Number + " · " + p.Channel.Name, Mode: "transcode", State: p.State, StartedAt: p.StartedAt})
			}
		}
		if deps.Recordings != nil {
			for _, recording := range deps.Recordings.Activity() {
				started := recording.Start
				if recording.StartedAt != nil {
					started = *recording.StartedAt
				}
				out.Plays = append(out.Plays, LocalPlay{ID: recording.ID, User: recording.UserName, Title: recording.Title, Mode: "recording", State: "recording", StartedAt: started})
			}
		}
		if deps.Auth != nil {
			out.SetupRequired, _ = deps.Auth.SetupRequired(req.Context())
		}
		out.SetupIncomplete = out.SetupRequired || !setUp(req.Context(), deps)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(out)
	})

	huma.Register(h, huma.Operation{
		OperationID:   "stopPlayback",
		Method:        http.MethodDelete,
		Path:          Prefix + "/playback/{session}",
		Summary:       "Stop a playback session",
		Description:   "Only the user who started the session can stop it.",
		Tags:          []string{"playback"},
		DefaultStatus: http.StatusNoContent,
	}, func(ctx context.Context, in *SessionIDInput) (*struct{}, error) {
		if deps.Playback == nil {
			return nil, nil
		}
		s, ok := deps.Playback.Get(in.Session)
		if !ok || s.Download {
			return nil, nil
		}
		if !ownsSession(ctx, s) {
			return nil, huma.Error403Forbidden("this playback session belongs to someone else")
		}
		deps.Playback.Stop(in.Session)
		return nil, nil
	})

	// Byte-level routes stay on chi: range requests, streaming files, and
	// segment serving do not fit huma's typed responses.
	r.Get(Prefix+"/files/{id}/stream", streamFile(deps))
	r.Head(Prefix+"/files/{id}/stream", streamFile(deps))
	r.Get(Prefix+"/files/{id}/subtitles/{track}", serveSubtitle(deps))
	r.Get(Prefix+"/playback/{session}/{name}", serveSegment(deps))
}

// addMarkers puts the file's skippable segments on a decision, with where
// the credits start, where the item counts as watched, and the next
// episode with when to offer it.
func addMarkers(ctx context.Context, deps Deps, row db.MediaFile, info *probe.MediaInfo, d *playback.Decision) {
	d.Segments = fileSegments(ctx, deps, row, info)
	var storyEnd int64
	d.CreditsMs, storyEnd = playback.CreditsAndStoryEnd(d.Segments, info.DurationMs)
	d.WatchedAtMs = playback.WatchedAt(d.Segments, info.DurationMs)
	if n, err := deps.Queries.NextEpisodeAfterFile(ctx, row.ID); err == nil {
		d.NextEpisode = &playback.UpNext{FileID: toInt64(n.FileID), EpisodeID: n.EpisodeID, ShowID: n.ShowID, ShowTitle: n.ShowTitle,
			SeasonNumber: n.SeasonNumber, EpisodeNumber: n.EpisodeNumber, Title: n.Title, StillPath: deref(n.StillPath),
			RuntimeMin: deref(n.RuntimeMin), SeasonFinale: toInt64(n.SeasonFinale) == 1}
		if ms := toInt64(n.DurationMs); ms > 0 {
			d.NextEpisode.RuntimeMin = (ms + 30_000) / 60_000
		}
		d.UpNextMs = playback.UpNextAt(d.CreditsMs, storyEnd, info.DurationMs)
	}
}

func startFilePlayback(ctx context.Context, deps Deps, in *PlaybackInput) (*PlaybackOutput, error) {
	row, lib, info, err := playableFile(ctx, deps, in.ID)
	if err != nil {
		return nil, err
	}
	if in.Body.SwitchGeneration != 0 {
		user, ok := auth.UserFrom(ctx)
		if !ok || !in.Body.AutoRecovery || in.Body.Quality != playback.QualityAuto || deps.Activity == nil ||
			!deps.Activity.ValidateSwitch(in.Body.PlayID, user.ID, row.ID, in.Body.SwitchGeneration) {
			return nil, huma.Error409Conflict("quality switch generation is stale; keep the current stream")
		}
	}
	sidecars := playback.FindSidecars(row.Path)
	var caps playback.Capabilities
	if deps.Playback != nil {
		caps = deps.Playback.Capabilities()
	}
	// Automatic quality: the server picks the step and caps the profile
	// to it, as a client does for a fixed quality.
	var auto *playback.AutoChoice
	uncappedRequest := in.Body
	if in.Body.Quality == playback.QualityAuto {
		step, reason := autoStep(ctx, deps, in, row, info)
		in.Body.Profile = step.Cap(in.Body.Profile)
		auto = &playback.AutoChoice{Step: step.ID, Reason: reason, Generation: in.Body.SwitchGeneration}
	}
	user, signedIn := auth.UserFrom(ctx)
	switchUpgrade := false
	if in.Body.SwitchGeneration != 0 && signedIn && deps.Activity != nil {
		current := deps.Activity.AutoHint(in.Body.PlayID, user.ID, row.ID, "", netip.Addr{})
		for i, rung := range playback.Ladder {
			if rung.ID == current.Step {
				for _, higher := range playback.Ladder[:i] {
					if higher.ID == auto.Step {
						switchUpgrade = true
					}
				}
			}
		}
	}
	originalRequest := in.Body
	// Audio languages apply to any request that names no track; subtitle
	// preferences only to players that ask for them (subtitleMode auto).
	preferences := defaultTrackPreferences()
	if signedIn && (in.Body.SubtitleMode == "auto" || in.Body.AudioIndex == nil) {
		p, err := loadPreferences(ctx, deps, user.ID)
		if err != nil {
			return nil, err
		}
		preferences = p.tracks()
	}
	d, selectedRequest := decideTracks(ctx, deps, row, info, in.Body, sidecars, caps, preferences)
	in.Body = selectedRequest
	d.Auto = auto
	// A request with a playId continues that play. If the play has
	// ended meanwhile (it timed out, or the server restarted), a new one
	// takes over from it; if an admin stopped it, the player may not
	// carry on.
	d.PlayID = in.Body.PlayID
	continues := ""
	if deps.Activity != nil && signedIn && d.PlayID != "" {
		switch err := deps.Activity.Known(d.PlayID, user.ID, row.ID); {
		case errors.Is(err, activity.ErrUnknownPlay):
			continues, d.PlayID = d.PlayID, ""
		case err != nil:
			return nil, terminatedError(err)
		}
	}
	if d.PlayID == "" {
		d.PlayID = playback.NewPlayID()
	}
	var undoUpload func()
	committedUpload := false
	defer func() {
		if undoUpload != nil && !committedUpload {
			undoUpload()
		}
	}()
	if deps.Upload != nil && deps.Activity != nil && signedIn && remoteUpload(ctx) {
		undoUpload, err = deps.Upload.Reserve(d.PlayID, uploadDemand(d, info))
		if errors.Is(err, upload.ErrLimit) && auto != nil && in.Body.SwitchGeneration == 0 {
			found := false
			for i, step := range playback.Ladder {
				if step.ID != auto.Step {
					continue
				}
				for _, lower := range playback.Ladder[i+1:] {
					candidateRequest := uncappedRequest
					candidateRequest.Profile = lower.Cap(candidateRequest.Profile)
					candidate, selected := decideTracks(ctx, deps, row, info, candidateRequest, sidecars, caps, preferences)
					candidate.PlayID = d.PlayID
					undoUpload, err = deps.Upload.Reserve(d.PlayID, uploadDemand(candidate, info))
					if err == nil {
						candidate.Auto = &playback.AutoChoice{Step: lower.ID, Reason: "remote upload capacity"}
						d, in.Body = candidate, selected
						found = true
						break
					}
				}
				break
			}
			if !found && err == nil {
				err = upload.ErrLimit
			}
		}
		if err != nil {
			return nil, huma.Error429TooManyRequests("remote upload limit is full; stop another stream or choose a lower quality")
		}
	}
	// Playing an item makes it the user's own choice before any progress
	// arrives (or with none, if they stop at once), so an import cannot
	// mark it watched underneath them.
	if signedIn {
		itemType, ids, err := itemsForFile(ctx, deps, row)
		if err != nil {
			return nil, err
		}
		if err := deps.Watch.Claim(ctx, user.ID, itemType, ids); err != nil {
			return nil, err
		}
	}
	var watch *WatchState
	if signedIn {
		watch = watchForFile(ctx, deps, user, row)
	}
	encoder := ""
	for i := range d.Subtitles {
		t := &d.Subtitles[i]
		if t.Format != "image" {
			t.URL = fmt.Sprintf("%s/files/%d/subtitles/%s.%s", Prefix, row.ID, t.ID, subtitleExt(t.Format))
		}
	}
	switch d.Protocol {
	case "file":
		d.URL = fmt.Sprintf("%s/files/%d/stream", Prefix, row.ID)
		if deps.Activity != nil {
			// The play ID lets the stream count its bytes and refuse a
			// stopped play.
			d.URL += "?play=" + d.PlayID
		}
		// A play that moves to direct play (say, to Original quality)
		// no longer needs its transcode.
		if deps.Playback != nil && in.Body.PlayID != "" && !switchUpgrade {
			deps.Playback.StopPlay(in.Body.PlayID, user.ID)
		}
	case "hls":
		if deps.Playback == nil {
			return nil, huma.Error503ServiceUnavailable("transcoder not available")
		}
		spec := sessionSpec(row, user.ID, d, in.Body.Profile.MaxBitrate, sidecars)
		spec.PreservePrevious = switchUpgrade
		if continues != "" {
			deps.Playback.StopPlay(continues, user.ID)
		}
		d.Playlist = "event"
		if in.Body.Playlist == "vod" {
			if opts, err := vodOptions(ctx, deps, row, info, d, in.Body, sidecars); err == nil {
				opts.IFrames = iframeStream(ctx, deps, row, info.DurationMs)
				spec.VOD = opts
				d.Playlist = "vod"
				// Start where the viewer will: the requested position,
				// else where they left off. The playlist covers the
				// whole file either way.
				if spec.StartMs == 0 && watch != nil && !watch.Completed && watch.PositionMs > 5000 {
					spec.StartMs = watch.PositionMs
				}
			}
		}
		s, adaptive := startAdaptiveVOD(ctx, deps, in.Body, info, spec, d.Bitrate)
		if s == nil {
			s, err = deps.Playback.Start(ctx, spec)
		}
		d.Adaptive = adaptive
		if adaptive != nil {
			r := adaptive.Renditions[len(adaptive.Renditions)-1]
			d.Video = playback.VideoPlan{Codec: "h264", Encoder: "libx264", Width: r.Width, Height: r.Height}
			d.Audio = playback.AudioPlan{Index: spec.Audio.Index, Codec: "aac", Channels: 2}
			if spec.Audio.Channels == 1 || in.Body.Profile.MaxAudioChannels == 1 {
				d.Audio.Channels = 1
			}
		}
		if err != nil {
			return nil, startError(err)
		}
		d.SessionID = s.ID
		encoder = s.Encoder
		if s.Fallback != "" {
			d.Video = s.Video
			d.Reasons = append(d.Reasons, s.Fallback)
		}
		d.URL = fmt.Sprintf("%s/playback/%s/%s", Prefix, s.ID, playback.PlaylistName)
		if s.IsVOD() {
			d.URL = fmt.Sprintf("%s/playback/%s/%s", Prefix, s.ID, playback.VODMaster)
			d.StartMs = 0
		}
		d.MimeType = "application/vnd.apple.mpegurl"
	}
	_ = lib
	// An event playlist's time starts at the session's start, the
	// subtitles' at the file's: the server moves the cues to match.
	if d.Protocol == "hls" && d.Playlist == "event" && d.StartMs > 0 {
		for i := range d.Subtitles {
			if d.Subtitles[i].URL != "" {
				d.Subtitles[i].URL += "?shift=" + strconv.FormatInt(d.StartMs, 10)
			}
		}
	}
	prepareSubtitles(deps, row, d)
	addMarkers(ctx, deps, row, info, &d)
	if watch != nil && !watch.Completed {
		d.ResumeMs = watch.PositionMs
	}
	if m, ok := fileTrickplay(ctx, deps, row); ok {
		d.Trickplay = m
	}
	if deps.Activity != nil && signedIn {
		stream := streamInfo(info, filepath.Ext(row.Path), d, in.Body)
		stream.Encoder = encoder
		if d.Adaptive != nil {
			original := in.Body
			original.Profile = uncappedRequest.Profile
			choice, _ := decideTracks(ctx, deps, row, info, original, sidecars, caps, preferences)
			if choice.Protocol != "file" && !choice.Video.Copy {
				stream.OriginalBps = 0
			}
		}
		if in.Body.PlayID != "" && continues == "" {
			if err := deps.Activity.ContinueWithSwitch(ctx, d.PlayID, user.ID, row.ID, stream, info.DurationMs, d.WatchedAtMs, in.Body.SwitchGeneration); err != nil {
				if d.SessionID != "" {
					deps.Playback.Stop(d.SessionID)
				}
				if errors.Is(err, activity.ErrStaleSwitch) {
					return nil, huma.Error409Conflict("quality switch generation is stale; keep the current stream")
				}
				return nil, terminatedError(err)
			}
		} else {
			start := playStart(ctx, deps, requestFrom(ctx), user, row, in.Body.Client)
			start.ID, start.Continues, start.Stream = d.PlayID, continues, stream
			start.DurationMs, start.WatchedAtMs, start.PositionMs = info.DurationMs, d.WatchedAtMs, d.StartMs
			if _, err := deps.Activity.Begin(ctx, start); err != nil {
				deps.Log.Error("could not record play", "file", row.ID, "err", err)
				if undoUpload != nil {
					undoUpload()
					undoUpload = nil
				}
				if deps.Upload != nil && remoteUpload(ctx) && deps.Upload.Snapshot().Enforcing {
					if d.SessionID != "" {
						deps.Playback.Stop(d.SessionID)
					}
					return nil, huma.Error503ServiceUnavailable("playback accounting is unavailable; try again")
				}
			}
		}
	}
	if signedIn {
		mode, _ := playback.StartingSubtitles(selectedAudioLanguage(d), preferences.audio, preferences.subtitles)
		deps.automaticSubtitles.remember(user, row.ID, d.PlayID, originalRequest,
			originalRequest.SubtitleMode == "auto" && originalRequest.SubtitleID == "" &&
				mode == "on" && preferences.subtitles.AutomaticDownload && d.SubtitleID == "")
	}
	committedUpload = true
	return &PlaybackOutput{Body: d}, nil
}

func uploadDemand(d playback.Decision, info *probe.MediaInfo) int64 {
	bps := d.Bitrate
	if bps <= 0 && info != nil {
		bps = info.BitRate
	}
	if bps <= 0 {
		bps = 8_000_000
	} // unknown source: reserve a conservative baseline
	bps = min(bps, 1_000_000_000_000)
	if d.Video.Copy {
		return bps + bps*2/5
	}
	return bps + bps/4
}

// sessionSpec is the ffmpeg session for an HLS decision, with the subtitle
// it burns in.
func sessionSpec(row db.MediaFile, userID int64, d playback.Decision, maxBitrate int64, sidecars []playback.Sidecar) playback.Spec {
	spec := playback.Spec{
		FileID: row.ID, PlayID: d.PlayID, UserID: userID, Path: row.Path, Mode: d.Mode, StartMs: d.StartMs, Video: d.Video, Audio: d.Audio,
		MaxBitrate: maxBitrate, BurnIn: -1,
	}
	if d.BurnIn != nil {
		switch {
		case strings.HasPrefix(d.BurnIn.ID, "s"):
			spec.BurnIn, _ = strconv.Atoi(d.BurnIn.ID[1:])
			spec.BurnInImage = d.BurnIn.Format == "image"
			// Embedded tracks are listed in file order, so the
			// position among them is ffmpeg's subtitle stream index.
			for _, t := range d.Subtitles {
				if t.ID == d.BurnIn.ID {
					break
				}
				if !t.External {
					spec.BurnInSub++
				}
			}
		case strings.HasPrefix(d.BurnIn.ID, "x"):
			if n, err := strconv.Atoi(d.BurnIn.ID[1:]); err == nil && n < len(sidecars) {
				spec.BurnInFile = sidecars[n].Path
			}
		}
	}
	return d.BindCapabilities(spec)
}

// autoStep is where an Automatic decision is: the step the play is at when
// it continues one, else where a new play starts, from what is known of the
// device's link from this address.
func autoStep(ctx context.Context, deps Deps, in *PlaybackInput, row db.MediaFile, info *probe.MediaInfo) (playback.Step, string) {
	// Behind a proxy that is not trusted the client is unknown, and counts as
	// remote, as its play does in activity.
	var ip netip.Addr
	if n, ok := netguard.From(ctx); ok && !n.Proxied {
		ip = n.IP
	}
	var h activity.AutoHint
	if user, ok := auth.UserFrom(ctx); ok && deps.Activity != nil {
		h = deps.Activity.AutoHintForSwitch(in.Body.PlayID, user.ID, row.ID, clientDevice(requestFrom(ctx), in.Body.Client).Key, ip, in.Body.SwitchGeneration)
	}
	if h.Step != "" {
		return playback.StepByID(h.Step), h.Reason
	}
	fileBps := info.BitRate
	if fileBps == 0 && len(info.Video) > 0 {
		fileBps = info.Video[0].BitRate
	}
	return playback.StartStep(netguard.Classify(ip) == netguard.NetLocal, h.Estimate, h.Proven, fileBps)
}

// startError is the reply to a session that did not start: busy (try again
// shortly), replaced by a newer request for the play while it started, or
// broken.
func startError(err error) error {
	switch {
	case errors.Is(err, playback.ErrStorageBudget):
		return huma.ErrorWithHeaders(huma.Error503ServiceUnavailable("could not start playback: temporary storage unavailable"), http.Header{"Retry-After": {"10"}})
	case errors.Is(err, playback.ErrTooManySessions), errors.Is(err, playback.ErrRenditionBusy), errors.Is(err, playback.ErrLadderNotReady):
		return huma.ErrorWithHeaders(huma.Error503ServiceUnavailable("could not start playback: "+err.Error()), http.Header{"Retry-After": {"5"}})
	case errors.Is(err, playback.ErrStopped):
		return huma.Error409Conflict("a newer request for this play replaced this one")
	}
	return huma.Error500InternalServerError("could not start playback: " + err.Error())
}

// ownsSession reports whether the signed-in user started the session.
func ownsSession(ctx context.Context, s *playback.Session) bool {
	u, ok := auth.UserFrom(ctx)
	return ok && u.ID == s.UserID
}

func subtitleExt(format string) string {
	if format == "ass" {
		return "ass"
	}
	return "vtt"
}

// fileSegments returns the segments to play for a file, reading its
// chapters first when this version of the file has not been looked at.
func fileSegments(ctx context.Context, deps Deps, row db.MediaFile, info *probe.MediaInfo) []playback.Segment {
	analyzeChapters(ctx, deps, row, info)
	return storedSegments(ctx, deps, row.ID, info.DurationMs)
}

// storedSegments returns the file's segments as they stand, without
// analysing anything.
func storedSegments(ctx context.Context, deps Deps, fileID, durationMs int64) []playback.Segment {
	rows, err := deps.Queries.ListFileSegments(ctx, fileID)
	if err != nil {
		deps.Log.Warn("could not list segments", "file", fileID, "err", err)
		return []playback.Segment{}
	}
	var active []playback.Segment
	for _, r := range rows {
		if r.State == "active" {
			active = append(active, segmentFromDB(r))
		}
	}
	if picked := playback.PickSegments(active, durationMs); picked != nil {
		return picked
	}
	return []playback.Segment{}
}

func segmentFromDB(r db.MediaSegment) playback.Segment {
	return playback.Segment{ID: r.ID, Kind: r.Kind, StartMs: r.StartMs, EndMs: deref(r.EndMs), Final: r.Final == 1, Source: r.Source, Confidence: r.Confidence}
}

// analyzeChapters stores segments from the file's chapter names, once per
// version of the file and of the rules, on its first play. The stored probe
// has the chapters; a file probed before it kept them has them read here,
// and a failed read is retried next time.
func analyzeChapters(ctx context.Context, deps Deps, row db.MediaFile, info *probe.MediaInfo) {
	f := segments.FileVersion{ID: row.ID, Mtime: row.Mtime, Size: row.Size}
	if (!info.Extended && deps.Prober == nil) || segments.Analysed(ctx, deps.Queries, f, "chapter", playback.ChapterAlgoVersion) {
		return
	}
	chapters := info.Chapters
	if !info.Extended {
		readCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
		defer cancel()
		var err error
		if chapters, err = deps.Prober.Chapters(readCtx, row.Path); err != nil {
			deps.Log.Warn("could not read chapters", "file", row.ID, "err", err)
			return
		}
	}
	found := playback.ChapterSegments(chapters, info.DurationMs)
	if err := segments.Replace(ctx, deps.Conn, deps.Queries, f, "chapter", playback.ChapterAlgoVersion, found); err != nil {
		deps.Log.Warn("could not store chapter segments", "file", row.ID, "err", err)
	}
}

// playableFile loads a present, probed media file.
func playableFile(ctx context.Context, deps Deps, id int64) (db.MediaFile, db.Library, *probe.MediaInfo, error) {
	row, err := deps.Queries.GetMediaFile(ctx, id)
	if err != nil {
		return row, db.Library{}, nil, huma.Error404NotFound("file not found")
	}
	if row.Status != "present" {
		return row, db.Library{}, nil, huma.Error409Conflict("file is missing from disk")
	}
	lib, err := deps.Queries.GetLibrary(ctx, row.LibraryID)
	if err != nil {
		return row, lib, nil, err
	}
	if err := playback.ValidateLibraryPath(lib.Path, row.Path); err != nil {
		return row, lib, nil, huma.Error403Forbidden("file is outside its library")
	}
	if row.Probe == nil {
		return row, lib, nil, huma.Error422UnprocessableEntity("file has not been probed yet")
	}
	info, err := probe.Parse(*row.Probe)
	if err != nil {
		return row, lib, nil, huma.Error422UnprocessableEntity("stored probe data is unreadable")
	}
	return row, lib, info, nil
}

func streamFile(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, err := strconv.ParseInt(chi.URLParam(r, "id"), 10, 64)
		if err != nil {
			http.NotFound(w, r)
			return
		}
		row, err := deps.Queries.GetMediaFile(r.Context(), id)
		if err != nil || row.Status != "present" {
			http.NotFound(w, r)
			return
		}
		play := r.URL.Query().Get("play")
		if deps.Activity != nil && play != "" {
			u, _ := auth.UserFrom(r.Context())
			if err := deps.Activity.CheckStream(play, u.ID, id); err != nil {
				http.Error(w, err.Error(), http.StatusForbidden)
				return
			}
		}
		w, done := countBytes(deps, w, play)
		markServerPaced(deps, r.Context(), play)
		defer done()
		lib, err := deps.Queries.GetLibrary(r.Context(), row.LibraryID)
		if err != nil {
			http.NotFound(w, r)
			return
		}
		f, err := playback.OpenLibraryFile(lib.Path, row.Path)
		if err != nil {
			http.Error(w, "file not readable", http.StatusNotFound)
			return
		}
		defer func() { _ = f.Close() }()
		st, err := f.Stat()
		if err != nil {
			http.Error(w, "file not readable", http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", playback.MimeType(filepath.Ext(row.Path)))
		w.Header().Set("Accept-Ranges", "bytes")
		w.Header().Set("Cache-Control", "private, max-age=3600")
		http.ServeContent(w, r, filepath.Base(row.Path), st.ModTime(), f)
	}
}

var trackRe = regexp.MustCompile(`^([sxd])(\d+)\.(vtt|ass)$`)

func serveSubtitle(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, err := strconv.ParseInt(chi.URLParam(r, "id"), 10, 64)
		if err != nil {
			http.NotFound(w, r)
			return
		}
		row, err := deps.Queries.GetMediaFile(r.Context(), id)
		if err != nil || row.Status != "present" {
			http.NotFound(w, r)
			return
		}
		writeSubtitle(w, r, deps, row, chi.URLParam(r, "track"))
	}
}

// writeSubtitle sends one of a present file's subtitle tracks, by the
// name its URL gives it (s2.vtt, x0.ass, d7.vtt), in that format.
func writeSubtitle(w http.ResponseWriter, r *http.Request, deps Deps, row db.MediaFile, track string) {
	m := trackRe.FindStringSubmatch(track)
	if m == nil || deps.Subtitles == nil {
		http.NotFound(w, r)
		return
	}
	lib, err := deps.Queries.GetLibrary(r.Context(), row.LibraryID)
	if err != nil || playback.ValidateLibraryPath(lib.Path, row.Path) != nil {
		http.NotFound(w, r)
		return
	}
	n, parseErr := strconv.Atoi(m[2])
	if parseErr != nil {
		http.NotFound(w, r)
		return
	}
	format := "webvtt"
	if m[3] == "ass" {
		format = "ass"
	}
	var data []byte
	switch m[1] {
	case "s":
		if row.Probe == nil {
			http.NotFound(w, r)
			return
		}
		if info, err := probe.Parse(*row.Probe); err != nil || !playback.HasTextSubtitle(info, n) {
			http.NotFound(w, r)
			return
		}
		data, err = deps.Subtitles.Embedded(r.Context(), row.ID, row.Path, n, format)
	case "x":
		sidecars := playback.FindSidecars(row.Path)
		if n >= len(sidecars) {
			http.NotFound(w, r)
			return
		}
		data, err = deps.Subtitles.Sidecar(r.Context(), row.ID, n, sidecars[n], format)
	case "d":
		d, getErr := deps.OnlineSubtitles.Get(r.Context(), row.ID, int64(n))
		if getErr != nil {
			http.NotFound(w, r)
			return
		}
		data, err = deps.Subtitles.Downloaded(r.Context(), d.ID, d.Path, format)
	}
	if err != nil {
		if errors.Is(err, playback.ErrSubtitleBusy) {
			w.Header().Set("Retry-After", "2")
			http.Error(w, err.Error(), http.StatusServiceUnavailable)
			return
		}
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	if ms, _ := strconv.ParseInt(r.URL.Query().Get("shift"), 10, 64); ms > 0 {
		data = playback.ShiftSubtitle(data, format, ms)
	}
	if format == "ass" {
		w.Header().Set("Content-Type", "text/x-ssa; charset=utf-8")
	} else {
		w.Header().Set("Content-Type", "text/vtt; charset=utf-8")
	}
	w.Header().Set("Cache-Control", "private, max-age=3600")
	_, _ = w.Write(data)
}

var segmentRe = regexp.MustCompile(`^(index\.m3u8|init\.mp4|seg\d{5}\.m4s|master\.m3u8|video\.m3u8|audio\.m3u8|audio-init\.mp4|[va]\d{5}\.m4s|sub-[sxd]\d+\.m3u8|sub-[sxd]\d+-\d{5}\.vtt|iframes\.m3u8|iframes\.mp4)$`)

// serveVOD serves a VOD session's playlists and segments. Segments may wait
// for ffmpeg, which the session starts or moves as needed.
func serveVOD(w http.ResponseWriter, r *http.Request, s *playback.Session, name string) {
	if err := s.ResourceError(); err != nil {
		w.Header().Set("Retry-After", "10")
		http.Error(w, err.Error(), http.StatusServiceUnavailable)
		return
	}
	if name == playback.VODIFramesData {
		serveIFrames(w, r, s)
		return
	}
	b, ct, err := s.VODFile(r.Context(), name)
	switch {
	case errors.Is(err, os.ErrNotExist):
		http.NotFound(w, r)
		return
	case errors.Is(err, playback.ErrStopped):
		http.Error(w, "playback session not found", http.StatusNotFound)
		return
	case errors.Is(err, playback.ErrTooManySessions), errors.Is(err, playback.ErrRenditionBusy), errors.Is(err, playback.ErrLadderNotReady):
		w.Header().Set("Retry-After", "5")
		http.Error(w, "playback failed: "+err.Error(), http.StatusServiceUnavailable)
		return
	case err != nil:
		if r.Context().Err() == nil {
			slog.Default().Warn("vod file", "session", s.ID, "name", name, "err", err)
		}
		http.Error(w, "playback failed: "+err.Error(), http.StatusServiceUnavailable)
		return
	}
	if strings.HasSuffix(name, ".m3u8") {
		// The playlists never change, but a fresh copy costs nothing.
		w.Header().Set("Cache-Control", "no-cache")
		if mt, ok := auth.MediaTokenFromContext(r.Context()); ok {
			b = tokenizePlaylist(b, mt)
		}
	} else {
		w.Header().Set("Cache-Control", "private, max-age=3600")
	}
	w.Header().Set("Content-Type", ct)
	w.Header().Set("Content-Length", strconv.Itoa(len(b)))
	_, _ = w.Write(b) //nolint:gosec // G705: a playlist or media segment the server made, sent with its media type
}

// serveSegment serves a session's playlist and segments, waiting briefly for
// a segment ffmpeg has not finished yet.
func serveSegment(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		name := chi.URLParam(r, "name")
		if deps.Playback == nil || !segmentRe.MatchString(name) {
			http.NotFound(w, r)
			return
		}
		s, ok := deps.Playback.Get(chi.URLParam(r, "session"))
		// A download's session is reached only through its record's routes.
		if !ok || s.Download || !ownsSession(r.Context(), s) {
			http.Error(w, "playback session not found", http.StatusNotFound)
			return
		}
		s.Touch()
		w, done := countBytes(deps, w, s.PlayID)
		markServerPaced(deps, r.Context(), s.PlayID)
		defer done()
		if s.IsVOD() {
			serveVOD(w, r, s, name)
			return
		}
		path := filepath.Join(s.Dir, name)
		if name == playback.PlaylistName {
			w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
			w.Header().Set("Cache-Control", "no-store")
		} else {
			w.Header().Set("Content-Type", "video/mp4")
			w.Header().Set("Cache-Control", "private, max-age=3600")
		}
		deadline := time.Now().Add(20 * time.Second)
		for {
			if err := s.ResourceError(); err != nil {
				w.Header().Set("Retry-After", "10")
				http.Error(w, err.Error(), http.StatusServiceUnavailable)
				return
			}
			if name == playback.PlaylistName {
				if err := s.Err(); err != nil {
					http.Error(w, "playback failed: "+err.Error(), http.StatusInternalServerError)
					return
				}
				b, err := s.EventPlaylist()
				if err == nil {
					// Segment links in the playlist are relative, and relative
					// links drop the query string, so a token-authenticated
					// player would lose access after the playlist.
					if mt, ok := auth.MediaTokenFromContext(r.Context()); ok {
						b = tokenizePlaylist(b, mt)
					}
					http.ServeContent(w, r, name, time.Time{}, bytes.NewReader(b))
					return
				}
				if !errors.Is(err, os.ErrNotExist) {
					http.Error(w, err.Error(), http.StatusInternalServerError)
					return
				}
			} else if _, err := os.Stat(path); err == nil { //nolint:gosec // G703: name matched segmentRe, so path stays in the session folder
				if serveShared(w, r, path) {
					s.EventSegmentRequested(name)
				}
				return
			}
			if err := s.Err(); err != nil {
				http.Error(w, "playback failed: "+err.Error(), http.StatusInternalServerError)
				return
			}
			if s.Finished() || time.Now().After(deadline) {
				http.NotFound(w, r)
				return
			}
			select {
			case <-r.Context().Done():
				return
			case <-time.After(100 * time.Millisecond):
			}
		}
	}
}

// serveShared serves immutable media with delete sharing on Windows, so an
// active reader does not prevent cleanup. Mutable playlists use ReadPlaylist.
func serveShared(w http.ResponseWriter, r *http.Request, path string) bool {
	f, err := playback.OpenShared(path)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return false
	}
	defer func() { _ = f.Close() }()
	st, err := f.Stat()
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return false
	}
	delivered := &segmentDeliveryWriter{ResponseWriter: w}
	http.ServeContent(delivered, r, filepath.Base(path), st.ModTime(), f)
	if r.Context().Err() != nil || delivered.status >= 400 {
		return false
	}
	if delivered.bytes >= st.Size() {
		return true
	}
	// A player may fetch a segment in ranges. Its final range is enough to
	// advance the preparation window, even if no single response was full.
	if delivered.status != http.StatusPartialContent {
		return false
	}
	contentRange := w.Header().Get("Content-Range")
	value, ok := strings.CutPrefix(contentRange, "bytes ")
	if !ok {
		return false
	}
	span, total, ok := strings.Cut(value, "/")
	if !ok || total != strconv.FormatInt(st.Size(), 10) {
		return false
	}
	start, end, ok := strings.Cut(span, "-")
	if !ok {
		return false
	}
	first, firstErr := strconv.ParseInt(start, 10, 64)
	last, lastErr := strconv.ParseInt(end, 10, 64)
	return firstErr == nil && lastErr == nil && last == st.Size()-1 && delivered.bytes == last-first+1
}

type segmentDeliveryWriter struct {
	http.ResponseWriter
	bytes  int64
	status int
}

func (w *segmentDeliveryWriter) WriteHeader(status int) {
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}
func (w *segmentDeliveryWriter) Write(b []byte) (int, error) {
	n, err := w.ResponseWriter.Write(b)
	w.bytes += int64(n)
	return n, err
}
func (w *segmentDeliveryWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

// tokenizePlaylist appends ?access_token= to every URI in an HLS playlist:
// segment lines and URI="..." attributes such as EXT-X-MAP.
func tokenizePlaylist(b []byte, token string) []byte {
	q := auth.QueryParam + "=" + url.QueryEscape(token)
	add := func(u string) string {
		if strings.Contains(u, "?") {
			return u + "&" + q
		}
		return u + "?" + q
	}
	lines := strings.Split(string(b), "\n")
	for i, line := range lines {
		t := strings.TrimRight(line, "\r")
		switch {
		case t == "":
		case !strings.HasPrefix(t, "#"):
			lines[i] = add(t)
		default:
			lines[i] = uriAttr.ReplaceAllStringFunc(t, func(m string) string {
				return `URI="` + add(m[len(`URI="`):len(m)-1]) + `"`
			})
		}
	}
	return []byte(strings.Join(lines, "\n"))
}

var uriAttr = regexp.MustCompile(`URI="[^"]*"`)

// downloadedTrack is a downloaded subtitle as a decision's track.
func downloadedTrack(r db.DownloadedSubtitle) playback.SubtitleTrack {
	return playback.SubtitleTrack{
		ID: subtitles.TrackID(r.ID), Language: r.Language, Codec: "subrip", Format: "webvtt", External: true,
		Forced: r.Forced == 1, Source: subtitles.Provider, Release: r.Release, HearingImpaired: r.HearingImpaired == 1,
	}
}

// prepareSubtitles starts extracting the file's embedded text tracks in the
// background, in the formats this play will ask for, so a pick finds them
// ready instead of waiting for a read of the whole file.
func prepareSubtitles(deps Deps, row db.MediaFile, d playback.Decision) {
	if deps.Subtitles == nil {
		return
	}
	var tracks []playback.Track
	for _, t := range d.Subtitles {
		if t.Source != "embedded" || t.Format == "image" || (d.BurnIn != nil && d.BurnIn.ID == t.ID) {
			continue
		}
		n, err := strconv.Atoi(t.ID[1:])
		if err != nil {
			continue
		}
		tracks = append(tracks, playback.Track{Index: n, Format: t.Format})
		if d.Playlist == "vod" && t.Format != "webvtt" {
			tracks = append(tracks, playback.Track{Index: n, Format: "webvtt"})
		}
	}
	deps.Subtitles.Prepare(row.ID, row.Path, tracks)
}
