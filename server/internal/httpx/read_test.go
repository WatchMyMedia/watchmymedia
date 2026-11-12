package httpx

import (
	"bytes"
	"errors"
	"strings"
	"testing"
)

func TestReadLimited(t *testing.T) {
	if b, err := ReadLimited(strings.NewReader("12345"), 5); err != nil || string(b) != "12345" {
		t.Errorf("at the limit = %q, %v", b, err)
	}
	if b, err := ReadLimited(strings.NewReader("123456"), 5); !errors.Is(err, ErrTooLarge) || b != nil {
		t.Errorf("over the limit = %q, %v", b, err)
	}
	var w bytes.Buffer
	if n, err := CopyLimited(&w, strings.NewReader("12345"), 5); err != nil || n != 5 || w.String() != "12345" {
		t.Errorf("copy at the limit = %d %q %v", n, w.String(), err)
	}
	w.Reset()
	if _, err := CopyLimited(&w, strings.NewReader("123456"), 5); !errors.Is(err, ErrTooLarge) {
		t.Errorf("copy over the limit = %v", err)
	}
}
