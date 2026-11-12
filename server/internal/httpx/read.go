package httpx

import (
	"errors"
	"fmt"
	"io"
)

// ErrTooLarge is a reply larger than the caller allows. Reading only up to a
// limit would cut the reply short without a word, and a cut JSON document
// fails later as "unexpected end of input", or a cut image is kept.
var ErrTooLarge = errors.New("the reply is larger than allowed")

// ReadLimited reads r to the end. It fails with ErrTooLarge when r holds
// more than limit bytes, rather than returning the first limit of them.
func ReadLimited(r io.Reader, limit int64) ([]byte, error) {
	b, err := io.ReadAll(io.LimitReader(r, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(b)) > limit {
		return nil, tooLarge(limit)
	}
	return b, nil
}

// CopyLimited copies r to w as ReadLimited reads it: it fails with
// ErrTooLarge once r has more than limit bytes. What was written by then is
// the caller's to discard.
func CopyLimited(w io.Writer, r io.Reader, limit int64) (int64, error) {
	n, err := io.Copy(w, io.LimitReader(r, limit+1))
	if err != nil {
		return n, err
	}
	if n > limit {
		return n, tooLarge(limit)
	}
	return n, nil
}

func tooLarge(limit int64) error {
	return fmt.Errorf("%w (over %d bytes)", ErrTooLarge, limit)
}
