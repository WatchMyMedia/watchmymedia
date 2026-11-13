package auth

import (
	"context"
	"errors"
	"fmt"
	"net/netip"
	"testing"

	"watchmymedia/server/internal/netguard"
	"watchmymedia/server/internal/turnstile"
)

// viaProxy is a context as the netguard middleware leaves it for a client
// of a reverse proxy on this machine that is not in trusted proxies: the
// address is the proxy's, and forwarded is the client its headers name
// ("" for none).
func viaProxy(forwarded string) context.Context {
	info := netguard.Info{IP: netip.MustParseAddr("127.0.0.1"), Proxied: true}
	if forwarded != "" {
		info.Forwarded = netip.MustParseAddr(forwarded)
	}
	return netguard.NewContext(context.Background(), info)
}

func TestLoginBehindUntrustedProxy(t *testing.T) {
	s, _ := setup(t)
	if _, err := s.CreateUser(context.Background(), "kid", "kidpass1", false); err != nil {
		t.Fatal(err)
	}
	attacker, owner := viaProxy("203.0.113.9"), viaProxy("198.51.100.7")
	var te *ThrottledError

	// Guessing one account: the guesser is locked, the owner behind the
	// same proxy is not.
	for i := 0; i <= pairPolicy.free; i++ {
		if _, _, err := s.Login(attacker, Credentials{Name: "kid", Password: "wrong"}, Client{}); !errors.Is(err, ErrInvalidCredentials) {
			t.Fatalf("failure %d -> %v", i+1, err)
		}
	}
	if _, _, err := s.Login(attacker, Credentials{Name: "kid", Password: "kidpass1"}, Client{}); !errors.As(err, &te) {
		t.Errorf("guesser after %d failures -> %v", pairPolicy.free+1, err)
	}
	if _, _, err := s.Login(owner, Credentials{Name: "kid", Password: "kidpass1"}, Client{}); err != nil {
		t.Fatalf("owner behind the same proxy -> %v", err)
	}

	// Spraying names: the sprayer is locked, everyone else is not.
	sprayer := viaProxy("203.0.113.10")
	for i := 0; i <= addrPolicy.free; i++ {
		_, _, _ = s.Login(sprayer, Credentials{Name: fmt.Sprintf("name%d", i), Password: "wrong"}, Client{})
	}
	if _, _, err := s.Login(sprayer, Credentials{Name: "fresh", Password: "wrong"}, Client{}); !errors.As(err, &te) {
		t.Errorf("sprayer after %d failures -> %v", addrPolicy.free+1, err)
	}
	if _, err := s.CheckPassword(owner, "kid", "kidpass1"); err != nil {
		t.Errorf("owner's password check after spraying -> %v", err)
	}

	// The proxy's own address is not anyone's key: a client on this
	// machine itself, or one whose forged header names someone's real
	// address, is unaffected.
	direct := from("198.51.100.20")
	forger := viaProxy("198.51.100.20")
	for i := 0; i <= pairPolicy.free; i++ {
		_, _, _ = s.Login(forger, Credentials{Name: "kid", Password: "wrong"}, Client{})
	}
	if _, _, err := s.Login(direct, Credentials{Name: "kid", Password: "kidpass1"}, Client{}); err != nil {
		t.Errorf("direct client whose address was forged -> %v", err)
	}
	if _, _, err := s.Login(from("127.0.0.1"), Credentials{Name: "kid", Password: "kidpass1"}, Client{}); err != nil {
		t.Errorf("this machine -> %v", err)
	}

	// Sessions record no address for a proxied client, not the proxy's.
	list, err := s.Sessions(context.Background(), 0, "")
	if err != nil {
		t.Fatal(err)
	}
	var proxied int
	for _, x := range list {
		if x.LastIP == "127.0.0.1" {
			proxied++
		}
	}
	if len(list) != 3 || proxied != 1 {
		t.Errorf("sessions' addresses: %+v", list)
	}
}

// A proxy that names no client gets no per-address limit, so one visitor's
// failures lock out nobody else; the name's own limit still holds.
func TestLoginBehindUntrustedProxyNamingNoClient(t *testing.T) {
	s, _ := setup(t)
	if _, err := s.CreateUser(context.Background(), "kid", "kidpass1", false); err != nil {
		t.Fatal(err)
	}
	ctx := viaProxy("")
	for i := 0; i <= pairPolicy.free; i++ {
		_, _, _ = s.Login(ctx, Credentials{Name: "kid", Password: "wrong"}, Client{})
	}
	if _, _, err := s.Login(ctx, Credentials{Name: "kid", Password: "kidpass1"}, Client{}); err != nil {
		t.Errorf("after another visitor's failures -> %v", err)
	}
	for i := 0; i < namePolicy.free; i++ {
		s.nameLimit.fail("kid")
	}
	var te *ThrottledError
	for _, c := range []context.Context{ctx, viaProxy("198.51.100.7")} {
		if _, _, err := s.Login(c, Credentials{Name: "kid", Password: "kidpass1"}, Client{}); !errors.As(err, &te) {
			t.Errorf("past the name's limit -> %v", err)
		}
	}
}

func TestChallengeBehindUntrustedProxy(t *testing.T) {
	s, _ := setup(t)
	if _, err := s.CreateUser(context.Background(), "kid", "kidpass1", false); err != nil {
		t.Fatal(err)
	}
	ch := &fakeChallenge{err: fmt.Errorf("%w (test)", turnstile.ErrRejected)}
	s.SetChallenge(ch)
	bot, owner := viaProxy("203.0.113.9"), viaProxy("198.51.100.7")
	for i := 0; i <= addrPolicy.free; i++ {
		if _, _, err := s.Login(bot, Credentials{Name: "kid", Password: "x"}, Client{}); !errors.Is(err, turnstile.ErrRejected) {
			t.Fatalf("bad token %d -> %v", i+1, err)
		}
	}
	var te *ThrottledError
	if _, _, err := s.Login(bot, Credentials{Name: "kid", Password: "x"}, Client{}); !errors.As(err, &te) {
		t.Errorf("bot after %d rejections -> %v", addrPolicy.free+1, err)
	}
	if _, _, err := s.Login(owner, Credentials{Name: "kid", Password: "kidpass1", Challenge: "ok"}, Client{}); err != nil {
		t.Errorf("owner behind the same proxy -> %v", err)
	}
	// Cloudflare is not told the proxy's address as the visitor's.
	for _, ip := range ch.ips {
		if ip != "" {
			t.Errorf("remoteip = %q, want none", ip)
		}
	}
}

func TestPairingBehindUntrustedProxy(t *testing.T) {
	s, _ := setup(t)
	starts := func(ctx context.Context, n int) error {
		for range n {
			if _, _, _, err := s.StartPairing(ctx, Client{Name: "Test TV"}); err != nil {
				return err
			}
		}
		return nil
	}
	if err := starts(viaProxy("203.0.113.9"), pairStartsPerIP); err != nil {
		t.Fatal(err)
	}
	var te *ThrottledError
	if err := starts(viaProxy("203.0.113.9"), 1); !errors.As(err, &te) {
		t.Errorf("start over the limit -> %v", err)
	}
	code, _, _, err := s.StartPairing(viaProxy("198.51.100.7"), Client{Name: "Test TV"})
	if err != nil {
		t.Fatalf("another visitor behind the same proxy -> %v", err)
	}
	if ip := s.pair.byCode[code].IP; ip != "" {
		t.Errorf("pairing address = %q, want none", ip)
	}
	if err := starts(from("127.0.0.1"), 1); err != nil {
		t.Errorf("this machine -> %v", err)
	}
	// Without a named client, the proxy's visitors share one allowance.
	if err := starts(viaProxy(""), pairStartsPerIP); err != nil {
		t.Fatal(err)
	}
	if err := starts(viaProxy(""), 1); !errors.As(err, &te) {
		t.Errorf("unnamed start over the limit -> %v", err)
	}
}
