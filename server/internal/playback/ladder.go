package playback

import (
	"cmp"
	"context"
	"errors"
	"fmt"
	"os"
	"slices"
	"strings"
	"time"
)

// vodFamily is published only after every member has a process claim and a
// validated first segment. All members belong to the same viewer and logical
// play; each URL still passes that viewer's session authorization.
type vodFamily struct {
	members  []*Session
	master   []byte
	warm     chan int
	warmDone chan struct{}
	manager  *Manager
}

const ladderWarmQueue = 6

// AdaptiveMemberIDs names only this viewer's authorized sessions. Shared cache
// identities and filesystem paths never leave the playback manager.
func (s *Session) AdaptiveMemberIDs() []string {
	var ids []string
	if family := s.ladder.Load(); family != nil {
		for _, member := range family.members {
			ids = append(ids, member.ID)
		}
	}
	return ids
}

// ErrLadderNotReady leaves the caller's existing single rendition available.
// A process claim alone does not prove that a cold rendition can keep up.
var ErrLadderNotReady = errors.New("VOD ladder cannot meet its preparation budget")

const (
	ladderStartupDeadline = 3 * time.Second
	ladderEvidenceAge     = 30 * time.Second
	ladderSegmentDeadline = 6 * time.Second
)

// Require measured headroom for the entire configured process budget, even
// if only one encoder happened to run during this startup measurement. This
// conservative software admission rule may reject an otherwise usable host;
// it must never treat a quick cache hit as proof of cold encoding capacity.
func ladderPreparationReady(production, media time.Duration, producedAt, now time.Time, slots int) bool {
	age := time.Duration(now.UnixNano() - producedAt.UnixNano())
	return production > 0 && media > 0 && slots > 0 && age >= 0 && age <= ladderEvidenceAge &&
		float64(production)*float64(slots)*1.25 <= float64(media)
}

// A later CPU slowdown must fail within a segment's media duration, not hold
// a player through the ordinary VOD wait. The caller can retry or retain its
// previous single rendition; failed work never becomes a ready cache entry.
func (f *vodFamily) segment(ctx context.Context, v *vodState, k int) ([]byte, error) {
	bounded, cancel := context.WithTimeout(ctx, f.manager.ladderDeadline(ladderSegmentDeadline))
	defer cancel()
	b, err := v.Segment(bounded, k)
	if ctx.Err() == nil && errors.Is(err, context.DeadlineExceeded) {
		return nil, ErrLadderNotReady
	}
	return b, err
}

// LadderPreparationTook makes the manager judge every family as if each
// rung's first segment had taken d to encode, measured just now, and gives a
// family's startup and segments the ordinary VOD wait in place of the
// ladder's deadlines. Zero measures the encoder again. It is for tests, and
// the server never calls it. Admission (ladderPreparationReady) and the
// deadlines measure this machine's encoder, so a test that needs a family,
// or needs one refused, otherwise passes or fails with the machine's load:
// with four slots a six-second segment must encode in 1.2 s, and under the
// race detector on a 10-core Mac with every core busy it took 1.2 to 1.9 s.
// That is how TestAdaptiveVODConcurrentColdSeekAndIndependentStop got a
// single rendition on the CI runners, which run several jobs at once
// (2026-11-11 and 2026-11-12). The rule itself still decides, on d and the
// configured slots; the tests that slow ffmpeg down (slowLadderFFmpeg) check
// the measurement and the deadlines.
func (m *Manager) LadderPreparationTook(d time.Duration) { m.ladderTook.Store(int64(d)) }

// ladderDeadline is d, or the ordinary VOD wait when a test has taken the
// encoder's speed out of a family's admission (LadderPreparationTook).
func (m *Manager) ladderDeadline(d time.Duration) time.Duration {
	if m.ladderTook.Load() != 0 {
		return vodWait
	}
	return d
}

func (m *Manager) startLadderMember(ctx context.Context, spec Spec) (*Session, error) {
	deadline := m.ladderDeadline(ladderStartupDeadline)
	bounded, cancel := context.WithTimeout(ctx, deadline)
	defer cancel()
	s, err := m.Start(bounded, spec)
	if err != nil {
		if ctx.Err() == nil && errors.Is(err, context.DeadlineExceeded) {
			return nil, fmt.Errorf("%w: startup exceeded %s", ErrLadderNotReady, deadline)
		}
		return nil, err
	}
	v := s.vod
	v.mu.Lock()
	k := v.startupIndex
	production, producedAt := v.startupProduction, v.startupProducedAt
	media := time.Duration(v.bounds[k+1]-v.bounds[k]) * time.Microsecond
	v.mu.Unlock()
	now := time.Now()
	if took := time.Duration(m.ladderTook.Load()); took != 0 {
		production, producedAt = took, now
	}
	ready := ladderPreparationReady(production, media, producedAt, now, m.limit)
	if !ready {
		m.Stop(s.ID)
		return nil, fmt.Errorf("%w: %s media took %s with %d configured slots, or evidence expired", ErrLadderNotReady, media, production.Round(time.Millisecond), m.limit)
	}
	return s, nil
}

// The common audio owner also makes the lower video. As its timeline
// advances, prepare the next higher video segment without making a seek in
// the audio owner. Only one bounded worker follows this viewer's timeline.
func (f *vodFamily) warmNext(k int) {
	if k >= f.members[1].Segments() {
		return
	}
	select {
	case f.warm <- k:
	default:
		// Keep the nearest already queued boundaries; an aggressively
		// buffering client cannot enqueue an unbounded transcode backlog.
	}
}

func (f *vodFamily) warmLoop() {
	defer close(f.warmDone)
	higher := f.members[1]
	for {
		select {
		case <-higher.vod.viewerCtx.Done():
			return
		case k := <-f.warm:
			_, err := f.segment(higher.vod.viewerCtx, higher.vod, k)
			if err != nil && higher.vod.viewerCtx.Err() == nil {
				f.manager.log.Warn("VOD ladder warm segment unavailable", "session", higher.ID, "segment", k, "err", err)
			}
		}
	}
}

// StartVODLadder is the internal A0 two-rung prototype. It does not change
// ordinary playback decisions and has no public request flag yet. Both
// compatible software variants must prepare under reserved capacity before
// either is advertised. A failed second start releases the first claim.
func (m *Manager) StartVODLadder(ctx context.Context, lower, higher Spec) (*Session, error) {
	s, err := m.startVODLadder(ctx, lower, higher)
	if err != nil {
		// The API answers a refusal with an ordinary single rendition and no
		// error, so this line is the only place the reason is kept.
		m.log.Debug("VOD ladder refused", "file", lower.FileID, "play", lower.PlayID, "err", err)
	}
	return s, err
}

func (m *Manager) startVODLadder(ctx context.Context, lower, higher Spec) (*Session, error) {
	if err := m.checkVODLadder(lower, higher); err != nil {
		return nil, err
	}
	if !m.ladderFits() {
		return nil, ErrTooManySessions
	}
	before, err := os.Stat(lower.Path)
	if err != nil {
		return nil, err
	}
	for _, spec := range []*Spec{&lower, &higher} {
		spec.ReserveSlot = true
		spec.SharedOutput = true
		spec.PreservePrevious = true
	}
	first, err := m.startLadderMember(ctx, lower)
	if err != nil {
		return nil, err
	}
	second, err := m.startLadderMember(ctx, higher)
	if err != nil {
		m.Stop(first.ID)
		return nil, err
	}
	after, err := os.Stat(lower.Path)
	if err != nil || before.Size() != after.Size() || !before.ModTime().Equal(after.ModTime()) {
		m.Stop(first.ID)
		m.Stop(second.ID)
		return nil, errors.New("VOD source changed while the ladder prepared")
	}
	if err := ctx.Err(); err != nil {
		m.Stop(first.ID)
		m.Stop(second.ID)
		return nil, err
	}
	codecs, err := ladderOutputCodecs(ctx, []*Session{first, second})
	if err != nil {
		m.Stop(first.ID)
		m.Stop(second.ID)
		return nil, err
	}
	family := &vodFamily{members: []*Session{first, second}, warm: make(chan int, ladderWarmQueue), warmDone: make(chan struct{}), manager: m}
	family.master = vodLadderMaster(family.members, codecs)
	m.mu.Lock()
	// A stop can run while the second variant prepares. Never return a
	// manifest that refers to a session already removed in that window.
	if m.sessions[first.ID] != first || m.sessions[second.ID] != second {
		m.mu.Unlock()
		m.Stop(first.ID)
		m.Stop(second.ID)
		return nil, ErrStopped
	}
	first.ladder.Store(family)
	second.ladder.Store(family)
	m.mu.Unlock()
	go family.warmLoop()
	return first, nil
}

// ladderFits says both rungs can claim a slot now, counting the downloads a
// play may displace, as each member's own start does. Checking first spares
// the low rung an encode that the high rung's refusal would throw away.
func (m *Manager) ladderFits() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	free := m.limit - m.claimedLocked(nil)
	for _, s := range m.sessions {
		if s.Download && s.runningLocked() {
			free++
		}
	}
	return free >= 2
}

func (m *Manager) checkVODLadder(a, b Spec) error {
	if a.VOD == nil || b.VOD == nil || a.Growing != nil || b.Growing != nil || a.Download || b.Download ||
		a.PlayID == "" || a.PlayID != b.PlayID || a.UserID == 0 || a.UserID != b.UserID ||
		a.FileID == 0 || a.FileID != b.FileID || a.Path == "" || a.Path != b.Path ||
		a.Mode != ModeTranscode || b.Mode != ModeTranscode || a.Video.Copy || b.Video.Copy ||
		a.Video.Codec == "" || a.Video.Codec != b.Video.Codec || a.Video.HDR != b.Video.HDR ||
		a.Video.ToneMap != b.Video.ToneMap || a.Audio != b.Audio ||
		// The declared audio allowance covers the controlled 192 kbps AAC
		// encoder. Copied AAC can greatly exceed it (including valid LC
		// surround), so it needs a separately qualified peak contract later.
		a.Audio.Copy || a.Audio.Codec != "aac" || a.Audio.Channels < 1 || a.Audio.Channels > 2 ||
		a.VOD.AudioChannels != a.Audio.Channels ||
		a.BurnIn != b.BurnIn || a.BurnInImage != b.BurnInImage || a.BurnInSub != b.BurnInSub || a.BurnInFile != b.BurnInFile ||
		a.StartMs != b.StartMs || a.VOD.DurationMs <= 0 || a.VOD.DurationMs != b.VOD.DurationMs ||
		a.VOD.FrameRate <= 0 || a.VOD.FrameRate != b.VOD.FrameRate ||
		a.VOD.Codecs == "" || a.VOD.Codecs != b.VOD.Codecs || a.VOD.VideoRange == "" || a.VOD.VideoRange != b.VOD.VideoRange ||
		!a.VOD.SeparateAudio || !b.VOD.SeparateAudio ||
		a.VOD.AudioChannels != b.VOD.AudioChannels || a.VOD.AudioLanguage != b.VOD.AudioLanguage ||
		len(a.VOD.Subtitles) > 0 || len(b.VOD.Subtitles) > 0 ||
		a.Video.Codec != "h264" || a.Video.HDR != "" || a.Video.ToneMap || b.Video.ToneMap ||
		a.Video.Encoder != b.Video.Encoder || a.Video.KeepDV || b.Video.KeepDV || a.Video.StripDV || b.Video.StripDV ||
		a.Video.Deinterlace != b.Video.Deinterlace || a.Video.BitDepth != b.Video.BitDepth ||
		a.BurnIn >= 0 || b.BurnIn >= 0 || a.BurnInImage || b.BurnInImage || a.BurnInFile != "" ||
		a.VOD.IFrames != nil || b.VOD.IFrames != nil || !slices.Equal(a.VOD.Keyframes, b.VOD.Keyframes) ||
		a.VOD.SupplementalCodecs != b.VOD.SupplementalCodecs ||
		!strings.HasPrefix(a.VOD.Codecs, "avc1.") || a.VOD.VideoRange != "SDR" ||
		!strings.HasSuffix(a.VOD.Codecs, ",mp4a.40.2") ||
		a.Video.Width <= 0 || a.Video.Height <= 0 || b.Video.Width <= a.Video.Width || b.Video.Height <= a.Video.Height ||
		a.MaxBitrate <= 0 || b.MaxBitrate <= a.MaxBitrate ||
		a.VOD.Bandwidth <= 0 || b.VOD.Bandwidth <= a.VOD.Bandwidth ||
		a.VOD.Bandwidth <= audioAllowance || a.MaxBitrate > VideoRateWithin(a.VOD.Bandwidth) ||
		b.VOD.Bandwidth <= audioAllowance || b.MaxBitrate > VideoRateWithin(b.VOD.Bandwidth) {
		return errors.New("VOD ladder variants have incompatible or unsafe media contracts")
	}
	if hwaccelOf(m.encoderFor(a)) != "software" || hwaccelOf(m.encoderFor(b)) != "software" {
		return errors.New("VOD ladder encoder is not yet validated for shared startup")
	}
	return nil
}

// A ladder may advertise only the video configuration written by the actual
// encoder. The common audio is AAC-LC in this private prototype; reject a
// missing or mismatched audio track before publishing either URL.
func ladderOutputCodecs(ctx context.Context, members []*Session) (map[*Session]string, error) {
	audio, _, err := members[0].VODFile(ctx, VODAudioInit)
	if err != nil {
		return nil, err
	}
	audioTrack, err := parseInit(audio)
	if err != nil || audioTrack.Audio == 0 || audioTrack.Video != 0 {
		return nil, errors.New("VOD ladder common AAC audio init is unavailable")
	}
	channels, err := ladderAudioChannels(audio)
	if err != nil {
		return nil, err
	}
	if expected := members[0].vod.opts.AudioChannels; expected > 0 && expected != channels {
		return nil, errors.New("VOD ladder encoded audio channels do not match its contract")
	}
	codecs := make(map[*Session]string, len(members))
	for _, s := range members {
		init, _, err := s.VODFile(ctx, VODInit)
		if err != nil {
			return nil, err
		}
		track, err := parseInit(init)
		if err != nil || track.Video == 0 || track.Audio != 0 {
			return nil, errors.New("VOD ladder video init is unavailable")
		}
		codec, err := ladderVideoCodec(init, s.Video.Width, s.Video.Height)
		if err != nil {
			return nil, err
		}
		codecs[s] = codec + ",mp4a.40.2"
	}
	return codecs, nil
}

func vodLadderMaster(members []*Session, codecs map[*Session]string) []byte {
	ordered := slices.Clone(members)
	slices.SortFunc(ordered, func(a, b *Session) int {
		return cmp.Compare(a.vod.opts.Bandwidth, b.vod.opts.Bandwidth)
	})
	var b strings.Builder
	b.WriteString("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n")
	audio := ordered[0].vod.opts
	b.WriteString("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Audio\"")
	if audio.AudioLanguage != "" {
		fmt.Fprintf(&b, ",LANGUAGE=%q", audio.AudioLanguage)
	}
	if audio.AudioChannels > 0 {
		fmt.Fprintf(&b, ",CHANNELS=\"%d\"", audio.AudioChannels)
	}
	fmt.Fprintf(&b, ",DEFAULT=YES,AUTOSELECT=YES,URI=%q\n", "../"+ordered[0].ID+"/"+VODAudio)
	for _, s := range ordered {
		o := s.vod.opts
		v := s.vod.spec.Video
		fmt.Fprintf(&b, "#EXT-X-STREAM-INF:BANDWIDTH=%d,CODECS=%q,RESOLUTION=%dx%d,FRAME-RATE=%.3f,VIDEO-RANGE=%s,AUDIO=\"audio\"\n",
			o.Bandwidth, codecs[s], v.Width, v.Height, o.FrameRate, o.VideoRange)
		fmt.Fprintf(&b, "../%s/%s\n", s.ID, VODMedia)
	}
	return []byte(b.String())
}
