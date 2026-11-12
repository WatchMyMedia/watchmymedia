package playback

import "testing"

func TestEncoderTable(t *testing.T) {
	t.Parallel()
	for name, want := range map[string]string{"libx264": "software", "hevc_videotoolbox": "videotoolbox", "h264_amf": "amf", "fake_nvenc": "nvenc", "mpeg4": "software", "copy": "software"} {
		if got := hwaccelOf(name); got != want {
			t.Errorf("hwaccelOf(%s) = %s, want %s", name, got, want)
		}
	}
	// Every hardware path has an H.264 and an HEVC encoder; software has H.264 only.
	for _, hw := range []string{"videotoolbox", "nvenc", "qsv", "vaapi", "amf"} {
		h, ok1 := encoderOn(hw, "h264")
		v, ok2 := encoderOn(hw, "hevc")
		if !ok1 || !ok2 || h.name != "h264_"+hw || v.name != "hevc_"+hw {
			t.Errorf("%s: %s %v, %s %v", hw, h.name, ok1, v.name, ok2)
		}
	}
	if _, ok := encoderOn("software", "hevc"); ok {
		t.Error("software HEVC is listed")
	}
	if got := encoderArgs("mpeg4", 1_000_000, 720, false); len(got) != 2 || got[1] != "1000000" {
		t.Errorf("unknown encoder args = %v", got)
	}
}
