package probe

import "testing"

func TestFieldOrderSurvivesStoredProbeOnly(t *testing.T) {
	info := reduce(rawOutput{Streams: []rawStream{{CodecType: "video", FieldOrder: "tt"}}})
	b, err := Encode(info)
	if err != nil {
		t.Fatal(err)
	}
	got, err := Parse(string(b))
	if err != nil {
		t.Fatal(err)
	}
	if got.Video[0].FieldOrder != "tt" {
		t.Fatalf("lost order: %s", b)
	}
}
