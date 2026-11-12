package discovery

import (
	"context"
	"io"
	"log/slog"
	"strings"
	"testing"

	"github.com/brutella/dnssd"
)

func TestInstanceName(t *testing.T) {
	cases := map[string]string{
		"":                      "WatchMyMedia",
		"  Mac mini  ":          "Mac mini",
		"nas.local":             "nas local",
		strings.Repeat("é", 40): strings.Repeat("é", 31),
	}
	for in, want := range cases {
		if got := instanceName(in); got != want {
			t.Errorf("instanceName(%q) = %q, want %q", in, got, want)
		}
	}
}

// fakeResponder keeps the services added to it, without a network.
type fakeResponder struct {
	live map[dnssd.ServiceHandle]dnssd.Service
}

type fakeHandle struct{ s dnssd.Service }

func (h *fakeHandle) UpdateText(map[string]string, dnssd.Responder) {}
func (h *fakeHandle) Service() dnssd.Service                        { return h.s }

func (r *fakeResponder) Add(s dnssd.Service) (dnssd.ServiceHandle, error) {
	h := &fakeHandle{s}
	r.live[h] = s
	return h, nil
}
func (r *fakeResponder) Remove(h dnssd.ServiceHandle)          { delete(r.live, h) }
func (r *fakeResponder) Respond(ctx context.Context) error     { <-ctx.Done(); return nil }
func (r *fakeResponder) Debug(context.Context, dnssd.ReadFunc) {}

// Renaming the server announces it under the new name alone.
func TestRename(t *testing.T) {
	rp := &fakeResponder{live: map[dnssd.ServiceHandle]dnssd.Service{}}
	a := &Advertiser{rp: rp, info: Info{ID: "0123456789abcdef", Name: "Mac mini", Version: "1.0", APILevel: 9, Port: 27587},
		log: slog.New(slog.NewTextHandler(io.Discard, nil)), done: make(chan struct{})}
	if err := a.add(); err != nil {
		t.Fatal(err)
	}
	names := func() []string {
		var out []string
		for _, s := range rp.live {
			out = append(out, s.Name)
		}
		return out
	}
	only := func(want string) {
		t.Helper()
		if got := names(); len(got) != 1 || got[0] != want {
			t.Errorf("announced %v, want [%s]", got, want)
		}
	}
	only("Mac mini")
	first := a.hdl
	if s := first.Service(); s.Host != "watchmymedia-01234567" || s.Port != 27587 || s.Text["id"] != "0123456789abcdef" || s.Text["api"] != "9" || s.Text["path"] != "/" {
		t.Errorf("service %+v", s)
	}
	a.Rename(" Mac mini ") // the same instance name: nothing to do
	if a.hdl != first {
		t.Error("announced again under the same name")
	}
	a.Rename("Den.local")
	only("Den local")
	if a.info.Name != "Den.local" {
		t.Errorf("info name %q", a.info.Name)
	}
}
