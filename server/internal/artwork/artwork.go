// Package artwork keeps the pictures an admin gave the server in place of
// TMDB's (PLAN-POSTERS.md): an upload or a link, copied once, re-encoded as
// JPEG and named by its hash under <data_dir>/artwork. internal/images
// serves them, scaled, on the route that serves TMDB's.
package artwork

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"image"
	"image/jpeg"
	_ "image/png" // accepted uploads
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"golang.org/x/image/draw"
	_ "golang.org/x/image/webp" // accepted uploads

	"watchmymedia/server/internal/httpx"
)

const (
	// MaxBytes bounds an upload or a linked picture.
	MaxBytes = 20 << 20
	// maxPixels bounds what is decoded: a small file can declare a huge
	// picture, and decoding allocates all of it.
	maxPixels = 50_000_000
	// maxWidth and maxHeight bound what is kept: twice the largest poster a
	// client asks for.
	maxWidth, maxHeight = 2000, 3000
	jpegQuality         = 90
)

var (
	// ErrNotPicture is a file that is not a JPEG, PNG or WebP picture.
	ErrNotPicture = errors.New("not a JPEG, PNG or WebP picture")
	// ErrTooLarge is a picture over MaxBytes or over the pixel limit.
	ErrTooLarge = errors.New("the picture is too large")
)

// nameRe is a stored picture's name: "c-" and 24 hex digits of its hash.
var nameRe = regexp.MustCompile(`^c-[0-9a-f]{24}\.jpg$`)

// IsName reports whether file is a name this package gives its pictures.
// TMDB's file names never start with "c-".
func IsName(file string) bool { return nameRe.MatchString(file) }

// Store keeps the pictures in one folder.
type Store struct {
	dir string
	// allowAddr is the check on each address a link's host resolves to;
	// tests replace it to reach a local server.
	allowAddr func(addr string) bool
}

// New keeps pictures under dir, which is made on first use.
func New(dir string) *Store { return &Store{dir: dir, allowAddr: publicAddr} }

// Dir is the folder the pictures are in, for backups.
func (s *Store) Dir() string { return s.dir }

// Save reads a picture, turns it upright, scales it down to the kept size
// and stores it as a JPEG. It returns the stored name. The same picture
// twice gives the same name and one file.
func (s *Store) Save(r io.Reader) (string, error) {
	raw, err := io.ReadAll(io.LimitReader(r, MaxBytes+1))
	if err != nil {
		return "", err
	}
	if len(raw) > MaxBytes {
		return "", ErrTooLarge
	}
	cfg, format, err := image.DecodeConfig(bytes.NewReader(raw))
	if err != nil || (format != "jpeg" && format != "png" && format != "webp") {
		return "", ErrNotPicture
	}
	if cfg.Width <= 0 || cfg.Height <= 0 || int64(cfg.Width)*int64(cfg.Height) > maxPixels {
		return "", ErrTooLarge
	}
	img, _, err := image.Decode(bytes.NewReader(raw))
	if err != nil {
		return "", ErrNotPicture
	}
	// Scale before turning: turning copies the picture, and the copy of a
	// 50-megapixel one is large. A picture stored on its side is as tall
	// as it will be wide.
	orientation := 1
	if format == "jpeg" {
		orientation = jpegOrientation(raw)
	}
	if orientation >= 5 {
		img = fit(img, maxHeight, maxWidth)
	} else {
		img = fit(img, maxWidth, maxHeight)
	}
	img = upright(img, orientation)

	var out bytes.Buffer
	if err := jpeg.Encode(&out, opaque(img), &jpeg.Options{Quality: jpegQuality}); err != nil {
		return "", err
	}
	sum := sha256.Sum256(out.Bytes())
	name := "c-" + hex.EncodeToString(sum[:])[:24] + ".jpg"
	if err := os.MkdirAll(s.dir, 0o755); err != nil {
		return "", err
	}
	local := filepath.Join(s.dir, name)
	if _, err := os.Stat(local); err == nil {
		return name, nil
	}
	return name, writeFile(local, out.Bytes())
}

// Scaled writes the picture named file, at most width wide, to out. A
// width of 0, or one the picture is not wider than, copies the picture.
// It returns os.ErrNotExist when the picture is gone.
func (s *Store) Scaled(file string, width int, out string) error {
	if !IsName(file) {
		return os.ErrNotExist
	}
	raw, err := os.ReadFile(filepath.Join(s.dir, file)) //nolint:gosec // G304: file passed IsName
	if err != nil {
		return err
	}
	if width > 0 {
		cfg, err := jpeg.DecodeConfig(bytes.NewReader(raw))
		if err != nil {
			return err
		}
		if cfg.Width > width {
			img, err := jpeg.Decode(bytes.NewReader(raw))
			if err != nil {
				return err
			}
			var buf bytes.Buffer
			if err := jpeg.Encode(&buf, fit(img, width, 0), &jpeg.Options{Quality: jpegQuality}); err != nil {
				return err
			}
			raw = buf.Bytes()
		}
	}
	if err := os.MkdirAll(filepath.Dir(out), 0o755); err != nil {
		return err
	}
	return writeFile(out, raw)
}

// Sweep deletes the stored pictures keep does not name, and their scaled
// copies under each folder of sizesDir. Names in keep may carry the leading
// slash a poster path has.
func (s *Store) Sweep(keep []string, sizesDir string) (removed int, err error) {
	kept := make(map[string]bool, len(keep))
	for _, k := range keep {
		kept[strings.TrimPrefix(k, "/")] = true
	}
	entries, err := os.ReadDir(s.dir)
	if errors.Is(err, os.ErrNotExist) {
		return 0, nil
	}
	if err != nil {
		return 0, err
	}
	sizes, _ := os.ReadDir(sizesDir)
	for _, e := range entries {
		name := e.Name()
		if !IsName(name) || kept[name] {
			continue
		}
		if err := os.Remove(filepath.Join(s.dir, name)); err != nil && !errors.Is(err, os.ErrNotExist) {
			return removed, err
		}
		removed++
		for _, size := range sizes {
			if size.IsDir() {
				_ = os.Remove(filepath.Join(sizesDir, size.Name(), name))
			}
		}
	}
	return removed, nil
}

// writeFile writes through a temporary file, so a reader never sees a
// picture cut short: the files are served as immutable.
func writeFile(path string, data []byte) error {
	tmp, err := os.CreateTemp(filepath.Dir(path), ".write-*")
	if err != nil {
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		_ = os.Remove(tmp.Name())
		return err
	}
	if err := tmp.Close(); err != nil {
		_ = os.Remove(tmp.Name())
		return err
	}
	if err := os.Rename(tmp.Name(), path); err != nil {
		_ = os.Remove(tmp.Name())
		return err
	}
	return nil
}

// fit scales img down to fit maxW by maxH, keeping its shape; a limit of 0
// is no limit. A picture already inside is returned as it is.
func fit(img image.Image, maxW, maxH int) image.Image {
	b := img.Bounds()
	w, h := b.Dx(), b.Dy()
	nw, nh := w, h
	if maxW > 0 && nw > maxW {
		nh = max(1, nh*maxW/nw)
		nw = maxW
	}
	if maxH > 0 && nh > maxH {
		nw = max(1, nw*maxH/nh)
		nh = maxH
	}
	if nw == w && nh == h {
		return img
	}
	dst := image.NewRGBA(image.Rect(0, 0, nw, nh))
	draw.CatmullRom.Scale(dst, dst.Bounds(), img, b, draw.Src, nil)
	return dst
}

// opaque draws img over black: JPEG has no transparency, and a PNG's clear
// pixels would otherwise come out in whatever colour they hide.
func opaque(img image.Image) image.Image {
	if o, ok := img.(interface{ Opaque() bool }); ok && o.Opaque() {
		return img
	}
	b := img.Bounds()
	dst := image.NewRGBA(image.Rect(0, 0, b.Dx(), b.Dy()))
	draw.Draw(dst, dst.Bounds(), image.Black, image.Point{}, draw.Src)
	draw.Draw(dst, dst.Bounds(), img, b.Min, draw.Over)
	return dst
}

// FetchError is a link the server could not get a picture from; Blocked
// marks one refused for pointing into the server's own network.
type FetchError struct {
	Blocked bool
	Err     error
}

func (e *FetchError) Error() string {
	if e.Blocked {
		return "the link points to this network"
	}
	return fmt.Sprintf("could not get the picture: %v", e.Err)
}

func (e *FetchError) Unwrap() error { return e.Err }

// Fetch gets the picture at rawURL once and stores it as Save does. The
// link must be http or https and must not lead, directly or through a
// redirect, to a loopback, private, link-local or tailnet address.
func (s *Store) Fetch(ctx context.Context, rawURL string) (string, error) {
	body, err := s.get(ctx, rawURL)
	if err != nil {
		return "", err
	}
	defer func() { _ = body.Close() }()
	name, err := s.Save(body)
	if err != nil && !errors.Is(err, ErrNotPicture) && !errors.Is(err, ErrTooLarge) {
		return "", &FetchError{Err: httpx.Redact(err)}
	}
	return name, err
}
