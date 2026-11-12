package playback

import "watchmymedia/server/internal/probe"

// BroadcastDeinterlace passes progressive frames through and doubles only
// interlaced frames. Live TV and recorded-file transcodes use the same filter.
const BroadcastDeinterlace = "bwdif=mode=send_field:parity=auto:deint=interlaced"

func Interlaced(v probe.VideoStream) bool {
	switch v.FieldOrder {
	case "tt", "bb", "tb", "bt":
		return true
	}
	return false
}

// DeinterlaceFilter is empty for progressive or older, unknown probes.
func DeinterlaceFilter(v probe.VideoStream) string {
	if Interlaced(v) {
		return BroadcastDeinterlace
	}
	return ""
}
