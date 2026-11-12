// Package hdhrtest provides a fictional two-tuner HTTP device. It never
// discovers or contacts hardware; callers supply the MPEG-TS stream.
package hdhrtest

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"
)

type Channel struct {
	GuideNumber string
	GuideName   string
	URL         string
	DRM         int    `json:",omitempty"`
	VideoCodec  string `json:",omitempty"`
	AudioCodec  string `json:",omitempty"`
}

type Tuner struct {
	mu                  sync.Mutex
	active, peak, opens int
	Capacity            int
	DeviceID            string
	Channels            []Channel
	Stream              http.HandlerFunc
	Error               string
	Malformed           bool
	Changed             chan struct{}
	// OldFirmware answers discover.json as firmware 20170815 does, without
	// DeviceAuth.
	OldFirmware bool
	auths       int
}

// DeviceAuth is the DeviceAuth the last discover.json gave; it changes on
// every request, as a real tuner's does over time.
func (t *Tuner) DeviceAuth() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	return deviceAuth(t.auths)
}

func deviceAuth(n int) string { return fmt.Sprintf("fictional-never-return-to-viewers-%d", n) }

func New(stream http.HandlerFunc) *Tuner {
	return &Tuner{Capacity: 2, DeviceID: "12345678", Stream: stream,
		Channels: []Channel{
			{GuideNumber: "7.1", GuideName: "Meadow News", URL: "http://tuner.test:5004/auto/v7.1", VideoCodec: "MPEG2", AudioCodec: "AC3"},
			{GuideNumber: "12.2", GuideName: "Orchard Arts", URL: "http://tuner.test:5004/auto/v12.2", VideoCodec: "H264", AudioCodec: "AAC"},
		}, Changed: make(chan struct{})}
}

func (t *Tuner) Counts() (active, peak, opens int) {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.active, t.peak, t.opens
}

// WaitIdle lets tests wait for closure without guessing a sleep interval.
func (t *Tuner) Idle() (bool, <-chan struct{}) {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.active == 0, t.Changed
}

func (t *Tuner) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	switch r.URL.Path {
	case "/discover.json":
		t.mu.Lock()
		t.auths++
		reply := map[string]any{"FriendlyName": "Meadow Antenna", "ModelNumber": "HDHR4-2US", "FirmwareVersion": "20260101", "DeviceID": t.DeviceID, "TunerCount": t.Capacity, "BaseURL": "http://tuner.test", "LineupURL": "http://tuner.test/lineup.json", "DeviceAuth": deviceAuth(t.auths)}
		if t.OldFirmware {
			reply["FirmwareVersion"] = "20170815"
			delete(reply, "DeviceAuth")
		}
		t.mu.Unlock()
		_ = json.NewEncoder(w).Encode(reply)
	case "/lineup.json":
		if t.Malformed {
			_, _ = w.Write([]byte("[{"))
			return
		}
		_ = json.NewEncoder(w).Encode(t.Channels)
	default:
		if !strings.HasPrefix(r.URL.Path, "/auto/v") {
			http.NotFound(w, r)
			return
		}
		t.mu.Lock()
		if t.Error != "" || t.active >= t.Capacity {
			reason := t.Error
			if reason == "" {
				reason = "805 All Tuners In Use"
			}
			t.mu.Unlock()
			w.Header().Set("X-HDHomeRun-Error", reason)
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		t.active++
		t.opens++
		t.peak = max(t.peak, t.active)
		close(t.Changed)
		t.Changed = make(chan struct{})
		t.mu.Unlock()
		defer func() { t.mu.Lock(); t.active--; close(t.Changed); t.Changed = make(chan struct{}); t.mu.Unlock() }()
		w.Header().Set("Content-Type", "video/mp2t")
		w.WriteHeader(http.StatusOK)
		_ = http.NewResponseController(w).Flush()
		if t.Stream != nil {
			t.Stream(w, r)
		} else {
			<-r.Context().Done()
		}
	}
}
