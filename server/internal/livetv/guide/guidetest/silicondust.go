package guidetest

import (
	"compress/gzip"
	"net/http"
	"strings"
	"sync"
)

// SiliconDust is a fake api.hdhomerun.com serving one XMLTV feed at
// /api/xmltv. Serve it with httptest and point Transport's
// "api.hdhomerun.com" at it. It is safe for concurrent use; set its fields
// before serving or under Lock.
type SiliconDust struct {
	sync.Mutex
	// Feed is the XML served, gzipped on the wire.
	Feed []byte
	// Raw, when set, is sent as the body as it is, with Content-Encoding
	// gzip: a gzip bomb or a cut-off stream.
	Raw []byte
	// DeviceAuth says whether a DeviceAuth is accepted; nil accepts any.
	DeviceAuth func(string) bool
	// Emails are the accounts the Email form accepts, with the DeviceIDs
	// they must come with.
	Emails map[string]string
	// Status, when set, is answered instead of the feed, with RetryAfter.
	Status     int
	RetryAfter string
	// Slow holds every request until it is cancelled.
	Slow bool

	requests int
	queries  []string
}

// Requests is how many feed requests arrived.
func (s *SiliconDust) Requests() int {
	s.Lock()
	defer s.Unlock()
	return s.requests
}

// Queries are the raw query strings of the requests, for tests that check
// what was sent.
func (s *SiliconDust) Queries() []string {
	s.Lock()
	defer s.Unlock()
	return append([]string(nil), s.queries...)
}

func (s *SiliconDust) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/api/xmltv" {
		http.NotFound(w, r)
		return
	}
	s.Lock()
	s.requests++
	s.queries = append(s.queries, r.URL.RawQuery)
	slow, status, retry, feed, raw := s.Slow, s.Status, s.RetryAfter, s.Feed, s.Raw
	accept := s.DeviceAuth
	emails := s.Emails
	s.Unlock()
	if slow {
		<-r.Context().Done()
		return
	}
	if status != 0 {
		if retry != "" {
			w.Header().Set("Retry-After", retry)
		}
		w.WriteHeader(status)
		return
	}
	// The real service sends gzip only when asked; the client must ask.
	if !strings.Contains(r.Header.Get("Accept-Encoding"), "gzip") {
		http.Error(w, "gzip required by this fake", http.StatusNotAcceptable)
		return
	}
	q := r.URL.Query()
	switch {
	case q.Get("DeviceAuth") != "":
		if accept != nil && !accept(q.Get("DeviceAuth")) {
			w.WriteHeader(http.StatusForbidden)
			return
		}
	case q.Get("Email") != "":
		if ids, ok := emails[q.Get("Email")]; !ok || ids != q.Get("DeviceIDs") {
			w.WriteHeader(http.StatusForbidden)
			return
		}
	default:
		w.WriteHeader(http.StatusBadRequest)
		return
	}
	w.Header().Set("Content-Type", "application/xml")
	w.Header().Set("Content-Encoding", "gzip")
	if raw != nil {
		_, _ = w.Write(raw)
		return
	}
	gz := gzip.NewWriter(w)
	_, _ = gz.Write(feed)
	_ = gz.Close()
}
