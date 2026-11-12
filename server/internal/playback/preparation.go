package playback

import "time"

// PreparedRange contains complete, indexed media on the server. It describes
// neither bytes already sent nor the player's decoder/network buffer.
type PreparedRange struct {
	StartMs int64 `json:"startMs"`
	EndMs   int64 `json:"endMs"`
}

type Preparation struct {
	Playlist       string          `json:"playlist"`
	Ranges         []PreparedRange `json:"ranges" nullable:"false"`
	RequestedEndMs *int64          `json:"requestedEndMs,omitempty" required:"false"`
	LeadMs         *int64          `json:"leadMs,omitempty" required:"false"`
	Paused         bool            `json:"paused"`
	ProgressSeen   bool            `json:"progressSeen"`
}

// Preparation is based on complete playlist entries or indexed VOD pieces,
// even when a fast encoder exits before its two-second progress report.
func (s *Session) Preparation() Preparation {
	out := Preparation{Ranges: []PreparedRange{}, ProgressSeen: s.progressSeen.Load()}
	if e := s.event; e != nil {
		// Activity may ask between player playlist polls. Refresh from the
		// latest published playlist so complete segments are visible then too.
		_, _ = s.ReadPlaylist()
		out.Playlist = "event"
		end := e.preparedMs.Load()
		start := e.evictedMs.Load()
		if end > start {
			out.Ranges = append(out.Ranges, PreparedRange{s.StartMs + start, s.StartMs + end})
		}
		requested := e.servedMs.Load()
		if requested > 0 {
			at := s.StartMs + requested
			out.RequestedEndMs = &at
			lead := max(end-requested, 0)
			out.LeadMs = &lead
		}
		out.Paused = !e.running() && !s.Finished()
		return out
	}
	if v := s.vod; v != nil {
		out.Playlist = "vod"
		v.mu.Lock()
		for _, p := range v.pieces {
			if p.dropped {
				continue
			}
			r := PreparedRange{p.start / 1000, p.end / 1000}
			if n := len(out.Ranges); n > 0 && r.StartMs <= out.Ranges[n-1].EndMs+int64(vodTolerance/time.Millisecond) {
				out.Ranges[n-1].EndMs = max(out.Ranges[n-1].EndMs, r.EndMs)
			} else {
				out.Ranges = append(out.Ranges, r)
			}
		}
		if v.head > 0 {
			at := v.head / 1000
			out.RequestedEndMs = &at
			for _, r := range out.Ranges {
				if r.StartMs <= at && r.EndMs >= at {
					lead := r.EndMs - at
					out.LeadMs = &lead
					break
				}
			}
		}
		out.Paused = !v.running()
		v.mu.Unlock()
	}
	return out
}
