// Package segments finds skippable parts of episodes (intros, credits) by
// matching audio across a season, and stores what it and the chapter
// reader find. Fingerprints follow the Philips scheme (Haitsma and Kalker):
// for each short frame, 32 bits say how the energy difference between
// neighbouring frequency bands changed since the previous frame. The same
// music in two episodes gives nearly the same bits even through different
// encodes; unrelated audio differs in about half of them.
package segments

import (
	"math"
	"math/cmplx"
)

// Audio is decoded at this rate, mono; nothing above 2 kHz is used.
const SampleRate = 5512

const (
	fftSize  = 2048 // 0.37 s window
	hop      = 172  // 0.0312 s between frames
	bands    = 33   // 32 bits per frame from 33 bands
	minFreq  = 300.0
	maxFreq  = 2000.0
	quietDB  = -50.0 // silence threshold for snapping, dBFS
	quietMin = 330   // shortest silence that counts, ms
)

// FrameMs is the time between fingerprint points.
const FrameMs = 1000.0 * hop / SampleRate

// FingerprintAlgoVersion changes whenever Fingerprint's output does, so
// cached fingerprints are rebuilt.
const FingerprintAlgoVersion = 1

// Fingerprint turns mono samples at SampleRate into one 32-bit point per
// frame. Point k describes audio around FrameTime(k).
func Fingerprint(samples []int16) []uint32 {
	if len(samples) < fftSize+hop {
		return nil
	}
	window := make([]float64, fftSize)
	for i := range window {
		window[i] = 0.5 - 0.5*math.Cos(2*math.Pi*float64(i)/float64(fftSize-1))
	}
	// Band edges in FFT bins, spaced evenly on a log scale.
	edges := make([]int, bands+1)
	for b := range edges {
		f := minFreq * math.Pow(maxFreq/minFreq, float64(b)/bands)
		edges[b] = int(math.Round(f * fftSize / SampleRate))
	}
	buf := make([]complex128, fftSize)
	prev := make([]float64, bands)
	cur := make([]float64, bands)
	var out []uint32
	for n, start := 0, 0; start+fftSize <= len(samples); n, start = n+1, start+hop {
		for i := range buf {
			buf[i] = complex(float64(samples[start+i])*window[i], 0)
		}
		fft(buf)
		for b := range bands {
			e := 0.0
			for k := edges[b]; k < edges[b+1]; k++ {
				m := cmplx.Abs(buf[k])
				e += m * m
			}
			cur[b] = e
		}
		if n > 0 {
			var bits uint32
			for m := range 32 {
				if (cur[m]-cur[m+1])-(prev[m]-prev[m+1]) > 0 {
					bits |= 1 << m
				}
			}
			out = append(out, bits)
		}
		prev, cur = cur, prev
	}
	return out
}

// FrameTime is where point k sits, in ms from the start of the audio: the
// middle of the later of the two frames it compares.
func FrameTime(k int) int64 {
	return int64(math.Round(float64(k+1)*FrameMs + 1000.0*fftSize/2/SampleRate))
}

// Silences lists where stretches of near silence start, in ms, used to snap
// a detected intro end onto the quiet before the episode proper.
func Silences(samples []int16) []int64 {
	const block = SampleRate / 20 // 50 ms
	limit := math.Pow(10, quietDB/20) * 32768
	need := (quietMin + 49) / 50 // blocks
	var out []int64
	run := 0
	for i := 0; (i+1)*block <= len(samples); i++ {
		sum := 0.0
		for _, s := range samples[i*block : (i+1)*block] {
			sum += float64(s) * float64(s)
		}
		if math.Sqrt(sum/block) >= limit {
			run = 0
			continue
		}
		if run++; run == need {
			out = append(out, int64(i-run+1)*50)
		}
	}
	return out
}

// fft is an in-place iterative radix-2 transform; len(x) is a power of two.
func fft(x []complex128) {
	n := len(x)
	for i, j := 1, 0; i < n; i++ {
		bit := n >> 1
		for ; j&bit != 0; bit >>= 1 {
			j ^= bit
		}
		j ^= bit
		if i < j {
			x[i], x[j] = x[j], x[i]
		}
	}
	for size := 2; size <= n; size <<= 1 {
		step := cmplx.Exp(complex(0, -2*math.Pi/float64(size)))
		for start := 0; start < n; start += size {
			w := complex(1, 0)
			for k := 0; k < size/2; k++ {
				a, b := x[start+k], x[start+k+size/2]*w
				x[start+k], x[start+k+size/2] = a+b, a-b
				w *= step
			}
		}
	}
}

// Points is how many fingerprint points cover ms of audio.
func Points(ms int64) int { return int(float64(ms) / FrameMs) }
