package dvr

import (
	"context"
	"time"

	"watchmymedia/server/internal/livetv"
)

type sourceFormat struct {
	info       livetv.SourceInfo
	err        error
	at         time.Time
	generation int64
}

// Inspect samples only this subscriber of the existing capture. One probe per
// source generation is shared among concurrent viewers; a stale or unknown
// result expires so a broadcast format change is eventually re-inspected.
func (r *liveReader) Inspect(ctx context.Context, ffprobe string) (livetv.SourceInfo, error) {
	return r.inspect(ctx, ffprobe, time.Time{})
}

// InspectFresh samples current capture bytes when a new shared output starts.
// Concurrent callers may reuse the same in-flight result, but an old cached
// format must not authorize a new encoder after a midstream format change.
func (r *liveReader) InspectFresh(ctx context.Context, ffprobe string) (livetv.SourceInfo, error) {
	return r.inspect(ctx, ffprobe, time.Now())
}

func (r *liveReader) inspect(ctx context.Context, ffprobe string, since time.Time) (livetv.SourceInfo, error) {
	p := r.source
	if p == nil {
		return livetv.SourceInfo{}, livetv.ErrSourceUnknown
	}
	for {
		generation := p.generation.Load()
		p.formatMu.Lock()
		if cached := p.format; cached != nil && cached.generation == generation {
			age := time.Since(cached.at)
			maxAge := livetv.InspectMaxAge
			if cached.err != nil {
				maxAge = 10 * time.Second
			}
			if age < maxAge && !cached.at.Before(since) {
				p.formatMu.Unlock()
				return cached.info, cached.err
			}
		}
		if pending := p.inspecting; pending != nil {
			p.formatMu.Unlock()
			select {
			case <-pending:
				continue
			case <-ctx.Done():
				return livetv.SourceInfo{}, ctx.Err()
			case <-p.ctx.Done():
				return livetv.SourceInfo{}, livetv.ErrSourceUnknown
			}
		}
		pending := make(chan struct{})
		p.inspecting = pending
		p.formatMu.Unlock()

		sample, observed, err := r.sample(ctx)
		info := livetv.SourceInfo{}
		if err == nil {
			info, err = livetv.InspectSample(ctx, ffprobe, sample, observed)
		}
		p.formatMu.Lock()
		if p.generation.Load() != generation {
			info, err = livetv.SourceInfo{}, livetv.ErrSourceUnknown
		} else {
			if old := p.format; err == nil && old != nil && old.err == nil && old.generation == generation && sourceChanged(old.info, info) {
				generation = p.generation.Add(1)
			}
			info.Generation = generation
			p.format = &sourceFormat{info: info, err: err, at: time.Now(), generation: generation}
		}
		p.inspecting = nil
		close(pending)
		p.formatMu.Unlock()
		return info, err
	}
}

func sourceChanged(a, b livetv.SourceInfo) bool {
	return a.VideoCodec != b.VideoCodec || a.AudioCodec != b.AudioCodec ||
		a.Width != b.Width || a.Height != b.Height || a.AudioChannels != b.AudioChannels ||
		a.Interlaced != b.Interlaced || a.SquarePixels != b.SquarePixels || a.HDRType != b.HDRType
}

func (r *liveReader) sample(ctx context.Context) ([]byte, int64, error) {
	timer := time.NewTimer(livetv.InspectWait)
	defer timer.Stop()
	r.active.Store(true)
	defer r.active.Store(false)
	sample := make([]byte, 0, livetv.InspectBytes)
	var first, last time.Time
	for len(sample) < livetv.InspectBytes {
		select {
		case <-ctx.Done():
			return nil, 0, ctx.Err()
		case <-r.ctx.Done():
			return nil, 0, r.ctx.Err()
		case <-r.source.ctx.Done():
			return nil, 0, livetv.ErrSourceUnknown
		case <-timer.C:
			return sample, observedRate(len(sample), first, last), nil
		case chunk, ok := <-r.chunks:
			if !ok {
				return nil, 0, livetv.ErrSourceUnknown
			}
			now := time.Now()
			if first.IsZero() {
				first = now
			}
			last = now
			remaining := livetv.InspectBytes - len(sample)
			if len(chunk) > remaining {
				chunk = chunk[:remaining]
			}
			sample = append(sample, chunk...)
		}
	}
	return sample, observedRate(len(sample), first, last), nil
}

func observedRate(bytes int, first, last time.Time) int64 {
	span := last.Sub(first)
	if span < 500*time.Millisecond || span > 10*time.Second {
		return 0
	}
	return int64(bytes) * 8 * int64(time.Second) / int64(span)
}

var _ interface {
	Inspect(context.Context, string) (livetv.SourceInfo, error)
} = (*liveReader)(nil)
