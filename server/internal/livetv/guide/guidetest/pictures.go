package guidetest

import (
	"bytes"
	"hash/fnv"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"net/http"
	"regexp"
	"strconv"
	"sync"
)

// Pictures is a fake img.hdhomerun.com. Serve it with httptest and point
// Transport's "img.hdhomerun.com" at it.
//
// It answers /titles/<id>.jpg with a 480×270 JPEG, /posters/<id>.jpg with a
// 320×480 JPEG and /channels/<id>.png with a 360×270 PNG, the sizes the
// real host's pictures measured (PLAN-LIVE-TV-GUIDE.md §7.1), each a plain
// colour made from the id. In any of those folders a few names misbehave:
//
//   - big.jpg is a valid JPEG of over 1 MiB, sent without a length;
//   - page.jpg is an HTML page sent as image/jpeg;
//   - elsewhere.jpg redirects to https://pictures.example.net/x.jpg;
//   - slow.jpg sends nothing until the request is cancelled;
//   - gone.jpg is 404.
//
// Everything else is 404. It counts the requests for each path and is safe
// for concurrent use.
type Pictures struct {
	mu       sync.Mutex
	requests map[string]int
}

// NewPictures returns a picture host that has had no requests.
func NewPictures() *Pictures { return &Pictures{requests: map[string]int{}} }

// Requests is how many requests arrived for path, such as
// "/titles/SH0001.jpg", whatever they were answered.
func (p *Pictures) Requests(path string) int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.requests[path]
}

var picturePath = regexp.MustCompile(`^/(titles|posters|channels)/([A-Za-z0-9_-]{1,64})\.(jpg|png)$`)

func (p *Pictures) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	p.mu.Lock()
	p.requests[r.URL.Path]++
	p.mu.Unlock()
	m := picturePath.FindStringSubmatch(r.URL.Path)
	if m == nil {
		http.NotFound(w, r)
		return
	}
	folder, id, ext := m[1], m[2], m[3]
	switch id + "." + ext {
	case "big.jpg":
		w.Header().Set("Content-Type", "image/jpeg")
		_, _ = w.Write(bigJPEG(id))
		return
	case "page.jpg":
		w.Header().Set("Content-Type", "image/jpeg")
		_, _ = w.Write([]byte("<!DOCTYPE html>\n<html><head><title>Not here</title></head><body><p>No picture.</p></body></html>\n"))
		return
	case "elsewhere.jpg":
		http.Redirect(w, r, "https://pictures.example.net/x.jpg", http.StatusFound)
		return
	case "slow.jpg":
		<-r.Context().Done()
		return
	case "gone.jpg":
		http.NotFound(w, r)
		return
	}
	var b []byte
	switch folder + "." + ext {
	case "titles.jpg":
		b = encodeJPEG(480, 270, id)
	case "posters.jpg":
		b = encodeJPEG(320, 480, id)
	case "channels.png":
		b = encodePNG(360, 270, id)
	default:
		http.NotFound(w, r)
		return
	}
	h := w.Header()
	h.Set("Content-Type", "image/"+map[string]string{"jpg": "jpeg", "png": "png"}[ext])
	h.Set("Content-Length", strconv.Itoa(len(b)))
	h.Set("Cache-Control", "public, max-age=86400")
	_, _ = w.Write(b)
}

// plain is a w×h picture of one colour made from id.
func plain(w, h int, id string) *image.NRGBA {
	f := fnv.New32a()
	_, _ = f.Write([]byte(id))
	s := f.Sum32()
	c := color.NRGBA{R: uint8(s), G: uint8(s >> 8), B: uint8(s >> 16), A: 0xff}
	img := image.NewNRGBA(image.Rect(0, 0, w, h))
	for i := 0; i < len(img.Pix); i += 4 {
		img.Pix[i], img.Pix[i+1], img.Pix[i+2], img.Pix[i+3] = c.R, c.G, c.B, c.A
	}
	return img
}

func encodeJPEG(w, h int, id string) []byte {
	var b bytes.Buffer
	if err := jpeg.Encode(&b, plain(w, h, id), &jpeg.Options{Quality: 80}); err != nil {
		panic(err)
	}
	return b.Bytes()
}

func encodePNG(w, h int, id string) []byte {
	var b bytes.Buffer
	if err := png.Encode(&b, plain(w, h, id)); err != nil {
		panic(err)
	}
	return b.Bytes()
}

// bigJPEG is a small JPEG with over 1 MiB of comment segments after its
// start marker: valid, and too large.
func bigJPEG(id string) []byte {
	small := encodeJPEG(16, 16, id)
	var b bytes.Buffer
	b.Write(small[:2]) // SOI
	pad := make([]byte, 0xffff-2)
	for range 17 {
		b.Write([]byte{0xff, 0xfe, 0xff, 0xff}) // COM, length 65535
		b.Write(pad)
	}
	b.Write(small[2:])
	return b.Bytes()
}
