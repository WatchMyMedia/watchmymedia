// Package hdhomerun is the HTTP-only HDHomeRun adapter. All outbound traffic
// stays on the configured device and its control (80) or stream (5004) port.
package hdhomerun

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"regexp"
	"strings"
	"time"

	"watchmymedia/server/internal/httpx"
)

var (
	ErrAddress    = errors.New("enter an HDHomeRun LAN hostname or IP address, without a path or credentials")
	ErrDevice     = errors.New("the address did not return a supported HTTP HDHomeRun device")
	ErrLineup     = errors.New("the tuner returned an invalid channel lineup; the previous lineup was kept")
	ErrOffline    = errors.New("the tuner did not respond")
	ErrIdentity   = errors.New("a different tuner is at this address; check its device ID")
	channelNumber = regexp.MustCompile(`^[0-9]{1,5}(\.[0-9]{1,5})?$`)
	deviceID      = regexp.MustCompile(`^[0-9A-Fa-f]{8}$`)
	// DeviceAuth is an opaque token; anything else is ignored rather than
	// sent on in a URL.
	deviceAuth = regexp.MustCompile(`^[A-Za-z0-9+/=._~-]{1,256}$`)
)

// StreamError is safe for clients and logs: never include upstream text or URLs.
type StreamError string

func (e StreamError) Error() string { return string(e) }

const (
	Busy        StreamError = "tuners_busy"
	NoSignal    StreamError = "no_signal"
	Protected   StreamError = "protected_channel"
	Unavailable StreamError = "tuner_unavailable"
)

type Device struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Model    string `json:"model"`
	Firmware string `json:"firmware"`
	Tuners   int64  `json:"tuners"`
}

type Channel struct {
	Key       string
	Number    string
	Name      string
	Path      string
	Protected bool
}

type Client struct {
	base   *url.URL
	http   *http.Client
	lookup func(context.Context, string, string) ([]netip.Addr, error)
}

// New accepts an internal transport seam for isolated tests. Production
// always uses the validating transport below; there is no config bypass.
func New(address string, transport http.RoundTripper) (*Client, error) {
	if !strings.Contains(address, "://") {
		address = "http://" + address
	}
	u, err := url.Parse(address)
	if err != nil || u.Scheme != "http" || u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" || (u.Path != "" && u.Path != "/") || (u.Port() != "" && u.Port() != "80") || strings.ContainsAny(u.Hostname(), "%\\") {
		return nil, ErrAddress
	}
	if ip, err := netip.ParseAddr(u.Hostname()); err == nil && !deviceAddress(ip) {
		return nil, ErrAddress
	}
	u.Host = strings.ToLower(u.Host)
	u.Path = ""
	if transport == nil {
		transport = deviceTransport
	}
	return &Client{base: u, http: &http.Client{Transport: transport, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}, lookup: net.DefaultResolver.LookupNetIP}, nil
}

func (c *Client) Address() string { return c.base.String() }

func deviceAddress(ip netip.Addr) bool {
	ip = ip.Unmap()
	return ip.IsPrivate() && !ip.IsLoopback() && !ip.IsLinkLocalUnicast() && ip.IsGlobalUnicast()
}

var deviceTransport = &http.Transport{
	Proxy: nil, MaxIdleConns: 8, MaxIdleConnsPerHost: 2, IdleConnTimeout: 30 * time.Second,
	ResponseHeaderTimeout: 8 * time.Second, MaxResponseHeaderBytes: 16 << 10,
	DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(address)
		if err != nil || (port != "80" && port != "5004") {
			return nil, ErrAddress
		}
		ips, err := net.DefaultResolver.LookupNetIP(ctx, "ip", host)
		if err != nil || len(ips) == 0 {
			return nil, ErrOffline
		}
		// Reject mixed safe/unsafe DNS answers, and dial the checked address
		// directly so a second lookup cannot rebind it between validation/dial.
		for _, ip := range ips {
			if !deviceAddress(ip) {
				return nil, ErrAddress
			}
		}
		d := net.Dialer{Timeout: 5 * time.Second}
		for _, ip := range ips {
			conn, err := d.DialContext(ctx, network, net.JoinHostPort(ip.String(), port))
			if err == nil {
				return conn, nil
			}
		}
		return nil, ErrOffline
	},
}

func (c *Client) get(ctx context.Context, path string) (*http.Response, error) {
	u := *c.base
	u.Path = path
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return nil, ErrAddress
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, ErrOffline
	}
	return resp, nil
}

func (c *Client) readJSON(ctx context.Context, path string, out any) error {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	resp, err := c.get(ctx, path)
	if err != nil {
		return err
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return ErrDevice
	}
	b, err := httpx.ReadLimited(resp.Body, 2<<20)
	if err != nil {
		return ErrDevice
	}
	if err := json.Unmarshal(b, out); err != nil {
		return ErrDevice
	}
	return nil
}

func (c *Client) Discover(ctx context.Context) (Device, error) {
	device, _, err := c.discover(ctx)
	return device, err
}

// GuideAuth reads the tuner's DeviceAuth for one listings fetch. It changes
// often, so it is read fresh each time and never kept. Firmware without one
// (20170815 has none) gives an empty Secret.
func (c *Client) GuideAuth(ctx context.Context) (Device, Secret, error) {
	return c.discover(ctx)
}

func (c *Client) discover(ctx context.Context) (Device, Secret, error) {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	var raw struct {
		DeviceID, FriendlyName, ModelNumber, FirmwareVersion, BaseURL, LineupURL, DeviceAuth string
		TunerCount                                                                           int64
	}
	if err := c.readJSON(ctx, "/discover.json", &raw); err != nil {
		return Device{}, Secret{}, err
	}
	if !deviceID.MatchString(raw.DeviceID) || !strings.HasPrefix(raw.ModelNumber, "HDHR") || raw.TunerCount < 1 || raw.TunerCount > 32 || len(raw.FriendlyName) > 128 || len(raw.ModelNumber) > 64 || len(raw.FirmwareVersion) > 64 {
		return Device{}, Secret{}, ErrDevice
	}
	// Discovery URLs are untrusted even though requests use fixed paths.
	hostOK := c.sameHost(ctx)
	if !sameDevice(raw.BaseURL, 80, "/", hostOK) || !sameDevice(raw.LineupURL, 80, "/lineup.json", hostOK) {
		return Device{}, Secret{}, ErrDevice
	}
	var auth Secret
	if deviceAuth.MatchString(raw.DeviceAuth) {
		auth = Secret{raw.DeviceAuth}
	}
	return Device{ID: strings.ToUpper(raw.DeviceID), Name: raw.FriendlyName, Model: raw.ModelNumber, Firmware: raw.FirmwareVersion, Tuners: raw.TunerCount}, auth, nil
}

func sameDevice(raw string, port int, path string, hostOK func(string) bool) bool {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "http" || !hostOK(u.Hostname()) || u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.RawPath != "" || u.ForceQuery {
		return false
	}
	p := u.Port()
	if p == "" {
		p = "80"
	}
	if (port == 80 && p != "80") || (port == 5004 && p != "5004") {
		return false
	}
	return u.Path == path || (path == "/" && u.Path == "")
}

// A tuner reached by hostname commonly advertises its numeric LAN address.
// Accept only a private IP in that hostname's checked DNS answer. Requests
// still use the configured host and the dial-time containment check.
//
// Resolve once per complete document, not once for every channel. A large
// lineup from a hostname-addressed tuner must not multiply DNS timeouts.
func (c *Client) sameHost(ctx context.Context) func(string) bool {
	var addresses map[netip.Addr]bool
	return func(host string) bool {
		if strings.EqualFold(host, c.base.Hostname()) {
			return true
		}
		advertised, err := netip.ParseAddr(host)
		if err != nil || !deviceAddress(advertised) {
			return false
		}
		if addresses == nil {
			addresses = map[netip.Addr]bool{}
			lookupCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
			defer cancel()
			ips, err := c.lookup(lookupCtx, "ip", c.base.Hostname())
			if err != nil {
				return false
			}
			for _, ip := range ips {
				if !deviceAddress(ip) {
					clear(addresses)
					return false
				}
				addresses[ip.Unmap()] = true
			}
		}
		return addresses[advertised.Unmap()]
	}
}

func (c *Client) Lineup(ctx context.Context) ([]Channel, error) {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	var raw []struct {
		GuideNumber, GuideName, URL, Tags string
		DRM                               int
	}
	if err := c.readJSON(ctx, "/lineup.json", &raw); err != nil {
		return nil, ErrLineup
	}
	if raw == nil || len(raw) > 1000 {
		return nil, ErrLineup
	}
	out := make([]Channel, 0, len(raw))
	seen := map[string]bool{}
	hostOK := c.sameHost(ctx)
	for _, r := range raw {
		if !channelNumber.MatchString(r.GuideNumber) || strings.TrimSpace(r.GuideName) == "" || len(r.GuideName) > 200 || seen[r.GuideNumber] {
			return nil, ErrLineup
		}
		path := "/auto/v" + r.GuideNumber
		if !sameDevice(r.URL, 5004, path, hostOK) {
			return nil, ErrLineup
		}
		protected := r.DRM != 0
		for tag := range strings.SplitSeq(strings.ToLower(r.Tags), ",") {
			protected = protected || strings.TrimSpace(tag) == "drm"
		}
		out = append(out, Channel{Key: r.GuideNumber, Number: r.GuideNumber, Name: r.GuideName, Path: path, Protected: protected})
		seen[r.GuideNumber] = true
	}
	return out, nil
}

// Open allocates exactly one tuner. The caller owns and must close its body.
// It has no total timeout: the session's stalled-input watchdog bounds reads.
func (c *Client) Open(ctx context.Context, path string) (io.ReadCloser, error) {
	number, ok := strings.CutPrefix(path, "/auto/v")
	if !ok || !channelNumber.MatchString(number) {
		return nil, ErrLineup
	}
	u := *c.base
	u.Host = net.JoinHostPort(u.Hostname(), "5004")
	u.Path = path
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return nil, ErrAddress
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, Unavailable
	}
	if resp.StatusCode == http.StatusOK {
		return resp.Body, nil
	}
	_ = resp.Body.Close()
	reason := strings.ToLower(strings.TrimSpace(resp.Header.Get("X-HDHomeRun-Error")))
	code, _, _ := strings.Cut(reason, " ")
	// https://info.hdhomerun.com/info/http_api#errors
	// 806 is a tuning/hardware failure, not copy protection (811).
	switch code {
	case "803", "804", "805":
		return nil, Busy
	case "807":
		return nil, NoSignal
	case "811":
		return nil, Protected
	case "806":
		return nil, Unavailable
	}
	// Older firmware may provide only a textual reason.
	switch {
	case strings.Contains(reason, "all tuners"):
		return nil, Busy
	case strings.Contains(reason, "no signal"), strings.Contains(reason, "no video data"):
		return nil, NoSignal
	case strings.Contains(reason, "protected"), strings.Contains(reason, "content protection"), strings.Contains(reason, "drm"):
		return nil, Protected
	default:
		return nil, Unavailable
	}
}
