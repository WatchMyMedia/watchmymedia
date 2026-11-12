package hdhrtest

import (
	"fmt"
	"net/http"
	"watchmymedia/server/internal/ffexec"
)

// Broadcast makes a paced fictional MPEG-2/interlaced + AC-3 broadcast.
// The generating process is fixture infrastructure, not a viewer encoder.
func Broadcast(ffmpeg string) http.HandlerFunc {
	return broadcast(ffmpeg, 320, 240, "1500k")
}

// BroadcastHD is opt-in acceptance material: its larger picture makes the
// fixed output caps differ meaningfully under a shaped native-client link.
func BroadcastHD(ffmpeg string) http.HandlerFunc {
	return broadcast(ffmpeg, 1280, 720, "5000k")
}

func broadcast(ffmpeg string, width, height int, bitrate string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		args := ffexec.FFmpegArgs("-re", "-f", "lavfi", "-i", fmt.Sprintf("testsrc2=size=%dx%d:rate=30000/1001", width, height), "-re", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
			"-map", "0:v:0", "-map", "1:a:0", "-c:v", "mpeg2video", "-threads", "1", "-flags", "+ilme+ildct", "-top", "1", "-b:v", bitrate, "-c:a", "ac3", "-ac", "2", "-b:a", "192k", "-f", "mpegts", "pipe:1")
		cmd := ffexec.Command(r.Context(), ffmpeg, args...)
		cmd.Stdout = w
		_ = cmd.Run()
	}
}
