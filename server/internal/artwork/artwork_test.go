package artwork

import (
	"bytes"
	"encoding/binary"
	"errors"
	"hash/crc32"
	"image"
	"image/color"
	"image/gif"
	"image/jpeg"
	"image/png"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// picture is a w by h picture, white on the left half and black on the
// right.
func picture(w, h int) *image.RGBA {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := range h {
		for x := range w {
			c := color.RGBA{A: 255}
			if x < w/2 {
				c = color.RGBA{R: 255, G: 255, B: 255, A: 255}
			}
			img.SetRGBA(x, y, c)
		}
	}
	return img
}

func jpegBytes(t *testing.T, img image.Image) []byte {
	t.Helper()
	var b bytes.Buffer
	if err := jpeg.Encode(&b, img, nil); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}

func pngBytes(t *testing.T, img image.Image) []byte {
	t.Helper()
	var b bytes.Buffer
	if err := png.Encode(&b, img); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}

// withOrientation puts an EXIF segment with the orientation after a JPEG's
// start marker.
func withOrientation(raw []byte, orientation uint16) []byte {
	tiff := []byte("MM\x00\x2a\x00\x00\x00\x08\x00\x01\x01\x12\x00\x03\x00\x00\x00\x01")
	tiff = binary.BigEndian.AppendUint16(tiff, orientation)
	tiff = append(tiff, 0, 0, 0, 0, 0, 0)
	seg := append([]byte("Exif\x00\x00"), tiff...)
	out := append([]byte{}, raw[:2]...)
	out = append(out, 0xFF, 0xE1)
	out = binary.BigEndian.AppendUint16(out, uint16(len(seg)+2))
	out = append(out, seg...)
	return append(out, raw[2:]...)
}

func stored(t *testing.T, s *Store, name string) (image.Image, []byte) {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join(s.Dir(), name))
	if err != nil {
		t.Fatal(err)
	}
	img, err := jpeg.Decode(bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	return img, raw
}

func bright(img image.Image, x, y int) bool {
	r, _, _, _ := img.At(x, y).RGBA()
	return r > 0x8000
}

func TestSaveAcceptsPicturesAndRefusesTheRest(t *testing.T) {
	t.Parallel()
	s := New(filepath.Join(t.TempDir(), "artwork"))

	name, err := s.Save(bytes.NewReader(jpegBytes(t, picture(400, 600))))
	if err != nil || !IsName(name) {
		t.Fatalf("a JPEG: %q, %v", name, err)
	}
	again, err := s.Save(bytes.NewReader(jpegBytes(t, picture(400, 600))))
	if err != nil || again != name {
		t.Fatalf("the same picture again: %q, %v, want %q", again, err, name)
	}
	if entries, _ := os.ReadDir(s.Dir()); len(entries) != 1 {
		t.Fatalf("files kept for one picture: %d", len(entries))
	}

	// A PNG with clear pixels comes out as a JPEG on black.
	clear := image.NewNRGBA(image.Rect(0, 0, 40, 60))
	name, err = s.Save(bytes.NewReader(pngBytes(t, clear)))
	if err != nil {
		t.Fatalf("a PNG: %v", err)
	}
	if img, _ := stored(t, s, name); bright(img, 5, 5) {
		t.Fatal("clear pixels did not come out black")
	}

	// 1x1 lossless WebP.
	webp := "RIFF\x1a\x00\x00\x00WEBPVP8L\x0d\x00\x00\x00\x2f\x00\x00\x00\x10\x07\x10\x11\x11\x88\x88\xfe\x07\x00"
	if _, err := s.Save(strings.NewReader(webp)); err != nil {
		t.Fatalf("a WebP: %v", err)
	}

	var g bytes.Buffer
	if err := gif.Encode(&g, picture(4, 4), nil); err != nil {
		t.Fatal(err)
	}
	whole := jpegBytes(t, picture(400, 600))
	for what, raw := range map[string][]byte{
		"a GIF":            g.Bytes(),
		"an SVG":           []byte(`<svg xmlns="http://www.w3.org/2000/svg" width="2" height="3"></svg>`),
		"HTML":             []byte("<!doctype html><title>poster.jpg</title>"),
		"a cut-short JPEG": whole[:len(whole)/2],
		"nothing":          nil,
	} {
		if _, err := s.Save(bytes.NewReader(raw)); !errors.Is(err, ErrNotPicture) {
			t.Errorf("%s: %v, want ErrNotPicture", what, err)
		}
	}

	if _, err := s.Save(bytes.NewReader(make([]byte, MaxBytes+1))); !errors.Is(err, ErrTooLarge) {
		t.Errorf("over the byte limit: %v", err)
	}
	// A small file that declares a huge picture is refused before decoding:
	// a PNG header for 20000 by 20000.
	huge := pngBytes(t, picture(1, 1))
	binary.BigEndian.PutUint32(huge[16:], 20000)
	binary.BigEndian.PutUint32(huge[20:], 20000)
	binary.BigEndian.PutUint32(huge[29:], crc32.ChecksumIEEE(huge[12:29]))
	if _, err := s.Save(bytes.NewReader(huge)); !errors.Is(err, ErrTooLarge) {
		t.Errorf("over the pixel limit: %v", err)
	}
}

func TestSaveScalesTurnsAndDropsEXIF(t *testing.T) {
	t.Parallel()
	s := New(filepath.Join(t.TempDir(), "artwork"))

	name, err := s.Save(bytes.NewReader(jpegBytes(t, picture(3000, 6000))))
	if err != nil {
		t.Fatal(err)
	}
	if img, _ := stored(t, s, name); img.Bounds().Dx() != 1500 || img.Bounds().Dy() != 3000 {
		t.Fatalf("kept size: %v, want 1500x3000", img.Bounds())
	}

	// Orientation 6: the stored left edge is the picture's top.
	name, err = s.Save(bytes.NewReader(withOrientation(jpegBytes(t, picture(160, 80)), 6)))
	if err != nil {
		t.Fatal(err)
	}
	img, raw := stored(t, s, name)
	if b := img.Bounds(); b.Dx() != 80 || b.Dy() != 160 {
		t.Fatalf("turned size: %v, want 80x160", b)
	}
	if !bright(img, 40, 10) || bright(img, 40, 150) {
		t.Fatal("the picture was not turned upright")
	}
	if bytes.Contains(raw, []byte("Exif")) {
		t.Fatal("EXIF was kept")
	}
}

func TestScaledAndSweep(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	s := New(filepath.Join(dir, "artwork"))
	sizes := filepath.Join(dir, "images")
	name, err := s.Save(bytes.NewReader(jpegBytes(t, picture(1000, 1500))))
	if err != nil {
		t.Fatal(err)
	}
	width := func(size string, w int) int {
		t.Helper()
		out := filepath.Join(sizes, size, name)
		if err := s.Scaled(name, w, out); err != nil {
			t.Fatal(err)
		}
		f, err := os.Open(out)
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = f.Close() }()
		cfg, err := jpeg.DecodeConfig(f)
		if err != nil {
			t.Fatal(err)
		}
		return cfg.Width
	}
	if got := width("w342", 342); got != 342 {
		t.Errorf("w342: %d wide", got)
	}
	if got := width("w1280", 1280); got != 1000 {
		t.Errorf("a size wider than the picture: %d wide, want it as stored", got)
	}
	if got := width("original", 0); got != 1000 {
		t.Errorf("original: %d wide", got)
	}
	if err := s.Scaled("c-000000000000000000000000.jpg", 342, filepath.Join(sizes, "w342", "x.jpg")); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("a picture that is gone: %v", err)
	}
	if err := s.Scaled("../artwork/"+name, 0, filepath.Join(sizes, "x.jpg")); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("a name with a path: %v", err)
	}

	if n, err := s.Sweep([]string{"/" + name}, sizes); err != nil || n != 0 {
		t.Fatalf("sweep with the picture in use: %d, %v", n, err)
	}
	if n, err := s.Sweep(nil, sizes); err != nil || n != 1 {
		t.Fatalf("sweep with it unused: %d, %v", n, err)
	}
	for _, p := range []string{filepath.Join(s.Dir(), name), filepath.Join(sizes, "w342", name), filepath.Join(sizes, "original", name)} {
		if _, err := os.Stat(p); !errors.Is(err, os.ErrNotExist) {
			t.Errorf("%s was left behind", p)
		}
	}
}

func TestFetch(t *testing.T) {
	t.Parallel()
	body := jpegBytes(t, picture(200, 300))
	host := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/poster.jpg":
			_, _ = w.Write(body)
		case "/moved":
			http.Redirect(w, r, "/poster.jpg", http.StatusFound)
		case "/page":
			_, _ = w.Write([]byte("<html>not a picture</html>"))
		case "/endless":
			for range 3 * MaxBytes / 4096 {
				if _, err := w.Write(make([]byte, 4096)); err != nil {
					return
				}
			}
		default:
			http.NotFound(w, r)
		}
	}))
	defer host.Close()
	ctx := t.Context()

	// As shipped, a host on this machine is refused, by address and by name.
	s := New(filepath.Join(t.TempDir(), "artwork"))
	var fe *FetchError
	for _, link := range []string{host.URL + "/poster.jpg", strings.Replace(host.URL, "127.0.0.1", "localhost", 1) + "/poster.jpg"} {
		if _, err := s.Fetch(ctx, link); !errors.As(err, &fe) || !fe.Blocked {
			t.Errorf("%s: %v, want it blocked", link, err)
		}
	}
	for _, addr := range []string{"127.0.0.1:80", "[::1]:80", "10.0.0.5:80", "192.168.1.20:443", "172.16.4.4:80", "169.254.169.254:80",
		"100.100.1.1:80", "[fe80::1]:80", "[fd00::1]:80", "[::ffff:192.168.1.20]:80", "0.0.0.0:80", "224.0.0.1:80", "nonsense"} {
		if publicAddr(addr) {
			t.Errorf("%s counts as the internet", addr)
		}
	}
	for _, addr := range []string{"93.184.216.34:443", "[2606:2800:220:1:248:1893:25c8:1946]:443"} {
		if !publicAddr(addr) {
			t.Errorf("%s does not count as the internet", addr)
		}
	}
	for _, link := range []string{"file:///etc/passwd", "ftp://example.com/a.jpg", "/poster.jpg", "http://"} {
		if _, err := s.Fetch(ctx, link); !errors.As(err, &fe) || fe.Blocked {
			t.Errorf("%s: %v, want a refused link", link, err)
		}
	}

	// With the host allowed, the rest of the path.
	s.allowAddr = func(string) bool { return true }
	name, err := s.Fetch(ctx, host.URL+"/poster.jpg")
	if err != nil || !IsName(name) {
		t.Fatalf("a picture: %q, %v", name, err)
	}
	if moved, err := s.Fetch(ctx, host.URL+"/moved"); err != nil || moved != name {
		t.Errorf("through a redirect: %q, %v", moved, err)
	}
	if _, err := s.Fetch(ctx, host.URL+"/page"); !errors.Is(err, ErrNotPicture) {
		t.Errorf("a web page: %v", err)
	}
	if _, err := s.Fetch(ctx, host.URL+"/endless"); !errors.Is(err, ErrTooLarge) {
		t.Errorf("a body with no end: %v", err)
	}
	if _, err := s.Fetch(ctx, host.URL+"/missing"); !errors.As(err, &fe) || fe.Blocked {
		t.Errorf("a 404: %v", err)
	}

	// A redirect is checked like the first address.
	s.allowAddr = func(addr string) bool { return !strings.HasPrefix(addr, "127.0.0.1:") }
	away := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, host.URL+"/poster.jpg", http.StatusFound)
	}))
	_ = away.Listener.Close()
	l, err := (&net.ListenConfig{}).Listen(ctx, "tcp6", "[::1]:0")
	if err != nil {
		t.Skip("no IPv6 loopback to redirect from")
	}
	away.Listener = l
	away.Start()
	defer away.Close()
	if _, err := s.Fetch(ctx, away.URL+"/"); !errors.As(err, &fe) || !fe.Blocked {
		t.Errorf("a redirect into the blocked range: %v", err)
	}
}
