package segments

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"math"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"

	"watchmymedia/server/internal/bgwork"
	"watchmymedia/server/internal/ffexec"
	"watchmymedia/server/internal/playback"
)

// AdBreakAlgoVersion is stored with each analysis; raising it redoes older
// ones. 2: runs longer than six minutes are dropped, not split into breaks,
// and evidence is read only from whole lines.
const AdBreakAlgoVersion int64 = 2

type quietSpan struct{ start, end float64 }
type adEvidence struct {
	black, silence           []quietSpan
	blackStart, silenceStart float64
	haveBlack, haveSilence   bool
}

// The two metadata printers, one on the video and one on the audio, share
// ffmpeg's stdout. With direct=1 each writes a line in one write, so lines
// interleave whole; buffered, they tore mid-line (a 20-minute broadcast
// gave ten torn lines, one a wrong but readable time). Every line must be
// a frame's header or one key=value entry; anything else means the output
// was garbled and the pass is not trusted.
var (
	adFrameLine = regexp.MustCompile(`^frame:\d+ +pts:(-?\d+|NOPTS) +pts_time:(-?\d+(\.\d+)?(e[-+]?\d+)?|NOPTS)$`)
	adEntryLine = regexp.MustCompile(`^[A-Za-z0-9_.]+=[^=]*$`)
	adTimeValue = regexp.MustCompile(`^-?\d+(\.\d+)?(e[-+]?\d+)?$`)
	// noAdTime are the values ffmpeg writes for a time it doesn't have.
	noAdTime = map[string]bool{"nan": true, "inf": true, "-inf": true, "nopts": true}
)

// readAdEvidence keeps only black/silence boundaries, not per-frame output.
func readAdEvidence(r io.Reader) (adEvidence, error) {
	var e adEvidence
	scan := bufio.NewScanner(r)
	scan.Buffer(make([]byte, 4096), 64<<10)
	for scan.Scan() {
		line := scan.Text()
		if adFrameLine.MatchString(line) {
			continue
		}
		if !adEntryLine.MatchString(line) {
			return e, fmt.Errorf("garbled metadata line %.80q", line)
		}
		key, value, _ := strings.Cut(line, "=")
		black := key == "lavfi.black_start" || key == "lavfi.black_end"
		if !black && key != "lavfi.silence_start" && key != "lavfi.silence_end" {
			continue
		}
		n, err := strconv.ParseFloat(value, 64)
		if number := adTimeValue.MatchString(value); !number || err != nil || n < 0 {
			if !number && !noAdTime[strings.ToLower(value)] {
				return e, fmt.Errorf("garbled metadata line %.80q", line)
			}
			// No usable time (none, or one before the start): the open
			// quiet span is forgotten rather than paired across it.
			if black {
				e.haveBlack = false
			} else {
				e.haveSilence = false
			}
			continue
		}
		switch key {
		case "lavfi.black_start":
			e.blackStart, e.haveBlack = n, true
		case "lavfi.black_end":
			if e.haveBlack && n >= e.blackStart {
				e.black = append(e.black, quietSpan{e.blackStart, n})
			}
			e.haveBlack = false
		case "lavfi.silence_start":
			e.silenceStart, e.haveSilence = n, true
		case "lavfi.silence_end":
			if e.haveSilence && n >= e.silenceStart {
				e.silence = append(e.silence, quietSpan{e.silenceStart, n})
			}
			e.haveSilence = false
		}
		// A malformed file cannot grow evidence without bound. Twelve hours
		// of one transition every two seconds is already well beyond useful TV.
		if len(e.black) > 21600 || len(e.silence) > 21600 {
			return e, errors.New("too many ad boundaries")
		}
	}
	return e, scan.Err()
}

func adBreaks(e adEvidence) []playback.Segment {
	var boundaries []float64
	j := 0
	for _, black := range e.black {
		mid := (black.start + black.end) / 2
		// A fade or entire black scene is not an advert boundary.
		if black.end-black.start > 2 {
			continue
		}
		for j < len(e.silence) && e.silence[j].end < black.start-.5 {
			j++
		}
		if j >= len(e.silence) {
			break
		}
		silence := e.silence[j]
		if silence.start > black.end+.5 || silence.end < black.start-.5 {
			continue
		}
		if len(boundaries) == 0 || mid-boundaries[len(boundaries)-1] > 2 {
			boundaries = append(boundaries, mid)
		}
	}
	var found []playback.Segment
	// At least three boundaries, with consecutive advertisement-like gaps,
	// make a break of 30 seconds to 6 minutes. Nothing is inferred from a
	// single black or quiet scene, and a longer run of regular cuts (a
	// countdown, a music channel) is no break at all: it is dropped whole,
	// never cut into breaks of the longest length (PLAN-LIVE-TV-DVR.md §4.8).
	for i := 0; i < len(boundaries)-2; {
		end := i
		for end+1 < len(boundaries) {
			gap := boundaries[end+1] - boundaries[end]
			if gap < 13 || gap > 92 || math.Abs(gap-math.Round(gap/15)*15) > 2 {
				break
			}
			end++
		}
		if length := boundaries[end] - boundaries[i]; end-i >= 2 && length >= minAdBreak && length <= maxAdBreak {
			found = append(found, playback.Segment{Kind: "commercial", Source: "blackframe", StartMs: int64(math.Round(boundaries[i] * 1000)), EndMs: int64(math.Round(boundaries[end] * 1000)), Confidence: .99})
		}
		i = end + 1
	}
	return found
}

// minAdBreak and maxAdBreak bound a break's length in seconds.
const minAdBreak, maxAdBreak = 30, 360

// errAdUnreadable is a pass that ran but could not read a file to the end:
// ffmpeg failed on it, it was not done within its time, or its output
// could not be trusted. Trying the same file again would learn nothing.
var errAdUnreadable = errors.New("could not read the recording for ad breaks")

// adTimeout is how long one pass over a file may run: two hours, plus twice
// its length, so a machine decoding at half real time still finishes (a
// Mac decoded 1080i H.264 at about 4.7 times real time at background
// priority, and a 12-hour recording took longer than two hours). A file
// of unknown length is taken as 8 Mb/s, a broadcast's usual rate.
func adTimeout(durationMs, size int64) time.Duration {
	length := time.Duration(durationMs) * time.Millisecond
	if length <= 0 {
		length = time.Duration(size/1_000_000) * time.Second
	}
	return 2*time.Hour + 2*length
}

// DetectAdBreaks is an opt-in, conservative, single decode pass. It is
// restarted when a play or recording needs resources; interrupted evidence
// is never committed. The caller stores successful results with Replace.
// A file the pass could not read gives an errAdUnreadable error; ffmpeg not
// starting at all, ErrNoDecoder.
func DetectAdBreaks(ctx context.Context, ffmpeg, path string, timeout time.Duration, gate bgwork.Gate) ([]playback.Segment, error) {
	if ffmpeg == "" {
		ffmpeg = "ffmpeg"
	}
	if timeout <= 0 {
		timeout = 2 * time.Hour
	}
	var result []playback.Segment
	err := gate.Run(ctx, func(ctx context.Context) error {
		evidence, err := adPass(ctx, ffmpeg, path, timeout, true)
		if err != nil {
			return err
		}
		result = adBreaks(evidence)
		return nil
	})
	if err != nil {
		return nil, err
	}
	return slices.Clone(result), nil
}

// adPass decodes path once, scaled down, and reads where it goes black and
// where it goes quiet. A real pass is low; only a test that checks what
// ffmpeg prints, not how it yields, runs at normal priority.
func adPass(ctx context.Context, ffmpeg, path string, timeout time.Duration, low bool) (adEvidence, error) {
	run, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	// A background pass needs one decoder/filter worker, regardless of
	// the server's core count. Leave the rest available for foreground work.
	args := ffexec.FFmpegArgs("-threads", "1", "-filter_threads", "1", "-i", path, "-map", "0:v:0?", "-map", "0:a:0?", "-vf", `scale=64:-2,blackdetect=d=0.04:pic_th=0.98:pix_th=0.04,metadata=print:file='pipe\:1':direct=1`, "-af", `silencedetect=n=-45dB:d=0.05,ametadata=print:file='pipe\:1':direct=1`, "-threads", "1", "-f", "null", "-")
	cmd := ffexec.Command(run, ffmpeg, args...)
	cmd.LowPriority = low
	out, err := cmd.StdoutPipe()
	if err != nil {
		return adEvidence{}, err
	}
	defer func() { _ = out.Close() }()
	if err = cmd.Start(); err != nil {
		return adEvidence{}, fmt.Errorf("%w: %w", ErrNoDecoder, err)
	}
	evidence, readErr := readAdEvidence(out)
	if readErr != nil {
		cancel()
	}
	waitErr := cmd.Wait()
	switch {
	case ctx.Err() != nil:
		// Stopped from outside: the gate gave way to a play, or the
		// server is stopping. Nothing is said about the file.
		return adEvidence{}, ctx.Err()
	case readErr != nil:
		return adEvidence{}, fmt.Errorf("%w: %w", errAdUnreadable, readErr)
	case run.Err() != nil:
		return adEvidence{}, fmt.Errorf("%w: not done after %s", errAdUnreadable, timeout)
	case waitErr != nil && !ffexec.RanOnFile(waitErr, cmd.Tail()):
		// Under nice, a missing ffmpeg is nice exiting 127.
		return adEvidence{}, fmt.Errorf("%w: %w", ErrNoDecoder, waitErr)
	case waitErr != nil:
		return adEvidence{}, fmt.Errorf("%w: %w", errAdUnreadable, waitErr)
	}
	return evidence, nil
}
