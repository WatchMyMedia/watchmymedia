package opensubtitles

import (
	"encoding/binary"
	"fmt"
	"io"
	"os"
)

const hashChunk = 64 << 10

// Hash is the OpenSubtitles "moviehash" of a video: its size plus the
// 64-bit little-endian sums of its first and last 64 KiB, as 16 hex
// digits. It reads 128 KiB whatever the file's size. The API only knows
// hashes of files between 128 KiB and 9 GB, so outside that range it
// returns "" and searches go by ID alone.
func Hash(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer func() { _ = f.Close() }()
	st, err := f.Stat()
	if err != nil {
		return "", err
	}
	size := st.Size()
	if size <= 2*hashChunk || size >= 9_000_000_000 {
		return "", nil
	}
	sum := uint64(size)
	buf := make([]byte, hashChunk)
	for _, off := range []int64{0, size - hashChunk} {
		if _, err := f.ReadAt(buf, off); err != nil && err != io.EOF {
			return "", err
		}
		for i := 0; i < hashChunk; i += 8 {
			sum += binary.LittleEndian.Uint64(buf[i:])
		}
	}
	return fmt.Sprintf("%016x", sum), nil
}
