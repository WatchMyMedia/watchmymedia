package livetv

import (
	"bufio"
	"context"
	"io"
	"time"
)

// inspectedTuner probes an unshared tuner from a bounded read-ahead. Peek
// leaves every byte available to the HLS process. Shared channels use the
// DVR fan-out inspector instead, so no viewer opens another tuner.
type inspectedTuner struct {
	reader *bufio.Reader
	body   io.ReadCloser
}

const tunerInspectBytes = 256 << 10

func newInspectedTuner(body io.ReadCloser) *inspectedTuner {
	return &inspectedTuner{reader: bufio.NewReaderSize(body, tunerInspectBytes), body: body}
}

func (r *inspectedTuner) Read(p []byte) (int, error) { return r.reader.Read(p) }
func (r *inspectedTuner) Close() error               { return r.body.Close() }

func (r *inspectedTuner) Inspect(ctx context.Context, ffprobe string) (SourceInfo, error) {
	start := time.Now()
	sample, _ := r.reader.Peek(tunerInspectBytes)
	rate := int64(0)
	if elapsed := time.Since(start); elapsed >= 500*time.Millisecond && elapsed <= InspectWait {
		rate = int64(len(sample)) * 8 * int64(time.Second) / int64(elapsed)
	}
	return InspectSample(ctx, ffprobe, sample, rate)
}

var _ io.ReadCloser = (*inspectedTuner)(nil)
