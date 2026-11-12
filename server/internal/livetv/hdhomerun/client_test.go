package hdhomerun

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"testing"
	"time"

	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
)

func TestLineupHostnameIsResolvedOncePerSnapshot(t *testing.T) {
	fake := hdhrtest.New(nil)
	fake.Channels = nil
	for i := range 1000 {
		fake.Channels = append(fake.Channels, hdhrtest.Channel{GuideNumber: fmt.Sprint(i), GuideName: "Meadow", URL: fmt.Sprintf("http://192.168.10.20:5004/auto/v%d", i)})
	}
	server := httptest.NewServer(fake)
	defer server.Close()
	c, _ := New("tuner.test", hdhrtest.Transport(server.URL))
	lookups := 0
	c.lookup = func(ctx context.Context, _, host string) ([]netip.Addr, error) {
		lookups++
		if deadline, ok := ctx.Deadline(); !ok || time.Until(deadline) > 3*time.Second || host != "tuner.test" {
			t.Fatal("unbounded or unexpected hostname lookup")
		}
		return []netip.Addr{netip.MustParseAddr("192.168.10.20")}, nil
	}
	for want := 1; want <= 2; want++ {
		channels, err := c.Lineup(t.Context())
		if err != nil || len(channels) != 1000 || lookups != want {
			t.Fatalf("channels=%d lookups=%d want=%d err=%v", len(channels), lookups, want, err)
		}
	}
	// A new snapshot must recheck DNS, including mixed safe/unsafe answers.
	c.lookup = func(context.Context, string, string) ([]netip.Addr, error) {
		return []netip.Addr{netip.MustParseAddr("192.168.10.20"), netip.MustParseAddr("127.0.0.1")}, nil
	}
	if _, err := c.Lineup(t.Context()); !errors.Is(err, ErrLineup) {
		t.Fatalf("accepted mixed DNS answer: %v", err)
	}
}

func TestAddressContainment(t *testing.T) {
	for _, s := range []string{"http://127.0.0.1", "http://[::1]", "http://169.254.169.254", "http://0.0.0.0", "http://224.0.0.1", "http://8.8.8.8", "https://tuner.test", "http://user:secret@tuner.test", "http://tuner.test:5004", "http://tuner.test/path", "http://tuner.test?secret=x", "http://tuner.test#x"} {
		if _, err := New(s, nil); !errors.Is(err, ErrAddress) {
			t.Errorf("accepted %q: %v", s, err)
		}
	}
	for _, s := range []string{"192.168.10.20", "tuner.local", "http://[fd00::10]"} {
		if _, err := New(s, nil); err != nil {
			t.Errorf("refused %q: %v", s, err)
		}
	}
	for _, s := range []string{"127.0.0.1", "::ffff:127.0.0.1", "169.254.169.254", "fe80::1", "0.0.0.0", "::", "224.0.0.1", "8.8.8.8"} {
		if deviceAddress(netip.MustParseAddr(s)) {
			t.Errorf("unsafe dial address %s", s)
		}
	}
}

func TestDeviceAndLineup(t *testing.T) {
	fake := hdhrtest.New(nil)
	server := httptest.NewServer(fake)
	defer server.Close()
	client, err := New("tuner.test", hdhrtest.Transport(server.URL))
	if err != nil {
		t.Fatal(err)
	}
	device, err := client.Discover(t.Context())
	if err != nil || device.Tuners != 2 || device.Model != "HDHR4-2US" {
		t.Fatalf("device=%+v err=%v", device, err)
	}
	channels, err := client.Lineup(t.Context())
	if err != nil || len(channels) != 2 {
		t.Fatalf("channels=%v err=%v", channels, err)
	}
	for _, bad := range []string{"http://evil.test:5004/auto/v7.1", "http://tuner.test/auto/v7.1", "file:///auto/v7.1", "http://u:p@tuner.test:5004/auto/v7.1", "http://tuner.test:5004/auto/v7.1?secret=x", "http://tuner.test:5004/auto/v7.2", "http://tuner.test:5004/auto/%767.1"} {
		fake.Channels[0].URL = bad
		if _, err := client.Lineup(t.Context()); !errors.Is(err, ErrLineup) {
			t.Errorf("accepted hostile URL %q: %v", bad, err)
		}
	}
	if active, _, opens := fake.Counts(); active != 0 || opens != 0 {
		t.Fatal("inspect/lineup tuned a channel")
	}
}

func TestRedirectAndUnboundedReply(t *testing.T) {
	for _, handler := range []http.HandlerFunc{
		func(w http.ResponseWriter, r *http.Request) {
			http.Redirect(w, r, "http://evil.test/discover.json", http.StatusFound)
		},
		func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write(make([]byte, (2<<20)+1)) },
	} {
		server := httptest.NewServer(handler)
		c, _ := New("tuner.test", hdhrtest.Transport(server.URL))
		if _, err := c.Discover(t.Context()); err == nil {
			t.Error("accepted redirect/oversized response")
		}
		server.Close()
	}
}

func TestVendorErrors(t *testing.T) {
	for _, tc := range []struct {
		header string
		want   error
	}{
		{"803 System Busy", Busy}, {"804 Tuner In Use", Busy}, {"805 All Tuners In Use", Busy},
		{"806 Tune Failed", Unavailable}, {"807 No Video Data", NoSignal}, {"811 Content Protection Required", Protected},
		{"807", NoSignal}, {"811", Protected}, {"No Signal", NoSignal}, {"DRM", Protected},
		{"unknown private details", Unavailable},
	} {
		fake := hdhrtest.New(nil)
		fake.Error = tc.header
		server := httptest.NewServer(fake)
		c, _ := New("tuner.test", hdhrtest.Transport(server.URL))
		body, err := c.Open(t.Context(), "/auto/v7.1")
		if body != nil {
			_ = body.Close()
		}
		if !errors.Is(err, tc.want) {
			t.Errorf("%q: %v", tc.header, err)
		}
		server.Close()
	}
}
