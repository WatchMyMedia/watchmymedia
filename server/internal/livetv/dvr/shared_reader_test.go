package dvr

import (
	"bytes"
	"io"
	"testing"
)

func TestSharedCaptureReaderToleratesFragmentedTransport(t *testing.T) {
	// Small HTTP reads must not turn a four-MiB source budget into a few
	// milliseconds of video. A temporary encoder pause can use the whole bound.
	r := &liveReader{ctx: t.Context(), chunks: make(chan []byte, 64), coalesce: true}
	fragment := bytes.Repeat([]byte{0x47, 0x12, 0x34, 0x56}, 256)
	for range 4 * 1024 {
		if !r.offer(fragment) {
			t.Fatal("transport fragmentation exhausted the source queue")
		}
	}
	if len(r.chunks) != 64 || len(r.assembling) != 0 {
		t.Fatal("unexpected coalesced queue budget")
	}
	if r.offer(bytes.Repeat([]byte{0xff}, liveCaptureChunk)) {
		t.Fatal("unbounded source reader accepted bytes beyond capacity")
	}
	want := bytes.Repeat(fragment, 4*1024)
	got := make([]byte, len(want))
	if _, err := io.ReadFull(r, got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, want) {
		t.Fatal("coalescing reordered or lost source bytes")
	}
}
