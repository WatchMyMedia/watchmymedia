package playback

// AdaptiveStream describes the complete admitted family, never a cached path.
// Children are still fetched through each viewer's authorized media routes.
type AdaptiveStream struct {
	Renditions []AdaptiveRendition `json:"renditions" nullable:"false"`
}

type AdaptiveRendition struct {
	SessionID string `json:"sessionId,omitempty" required:"false" doc:"This viewer’s authorized VOD member, for attributing transfer timing. Absent for a live family."`
	Width     int    `json:"width"`
	Height    int    `json:"height"`
	Bitrate   int64  `json:"bitrate" doc:"Advertised total peak bandwidth, including audio."`
}

// audioAllowance is the controlled AAC audio's share of a declared peak.
const audioAllowance = 256_000

// PeakBandwidth is the HLS BANDWIDTH declared for a transcoded rendition:
// twice the video rate, which the encoder's buffer may reach, plus audio.
// Offers, masters and admission checks must all use it to stay equal.
func PeakBandwidth(videoBps int64) int64 { return 2*videoBps + audioAllowance }

// VideoRateWithin is the largest video rate whose PeakBandwidth fits peak.
func VideoRateWithin(peak int64) int64 { return (peak - audioAllowance) / 2 }

func (a *AdaptiveStream) Accepts(height int, bitrate int64) bool {
	if a == nil {
		return false
	}
	for _, r := range a.Renditions {
		if r.Height == height && r.Bitrate == bitrate {
			return true
		}
	}
	return false
}
