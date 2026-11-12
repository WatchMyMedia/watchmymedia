package auth

import (
	"context"
	"crypto/rand"
	"errors"
	"math/big"
	"strings"
	"sync"
	"time"

	"watchmymedia/server/internal/netguard"
)

// Pairing signs a TV in without typing a password with a remote. The TV
// starts a pairing and shows its short code (and a QR code of the link page);
// someone signed in on a phone or computer opens the link page, checks the
// device's name and approves it; the TV, polling with the secret only it
// knows, then receives a device session of that person.
//
// Pairings live in memory: they last minutes, and a restart only means the
// TV shows a new code.
const (
	PairingTTL = 10 * time.Minute
	// PairingPoll is how often the TV should ask whether it was approved.
	PairingPoll = 2 * time.Second
	// The code avoids 0/O and 1/I, which look alike on a TV across the room.
	pairCodeAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	pairCodeLen      = 6
	// Starts allowed per client address within PairingTTL, and pairings
	// pending at once across the server.
	pairStartsPerIP = 10
	maxPairings     = 500
)

var (
	ErrPairingNotFound = errors.New("pairing code not found or expired")
	ErrPairingUsed     = errors.New("pairing code already approved")
	ErrPairingBusy     = errors.New("too many devices are pairing right now")
)

// Pairing is a device waiting to be approved.
type Pairing struct {
	Code      string
	Client    Client
	IP        string
	ExpiresAt time.Time
	Approved  bool
}

// PairingStatus is what the polling device learns.
type PairingStatus struct {
	Approved bool
	User     User
	Grant    Grant
}

type pairing struct {
	Pairing
	secretHash   string
	userID       int64
	approverHash string
}

type pairings struct {
	mu       sync.Mutex
	byCode   map[string]*pairing
	bySecret map[string]*pairing
	starts   map[string][]time.Time // per client address
}

func newPairings() *pairings {
	return &pairings{byCode: map[string]*pairing{}, bySecret: map[string]*pairing{}, starts: map[string][]time.Time{}}
}

// pruneLocked drops expired pairings and old start times.
func (p *pairings) pruneLocked(now time.Time) {
	for code, x := range p.byCode {
		if !now.Before(x.ExpiresAt) {
			delete(p.byCode, code)
			delete(p.bySecret, x.secretHash)
		}
	}
	for ip, ts := range p.starts {
		kept := ts[:0]
		for _, t := range ts {
			if now.Sub(t) < PairingTTL {
				kept = append(kept, t)
			}
		}
		if len(kept) == 0 {
			delete(p.starts, ip)
		} else {
			p.starts[ip] = kept
		}
	}
}

// NormalizePairCode upper-cases a code and drops spaces and dashes, so
// "abc-234" and "ABC 234" both work.
func NormalizePairCode(code string) string {
	var b strings.Builder
	for _, r := range strings.ToUpper(code) {
		if r != ' ' && r != '-' {
			b.WriteRune(r)
		}
	}
	return b.String()
}

func newPairCode() (string, error) {
	b := make([]byte, pairCodeLen)
	n := big.NewInt(int64(len(pairCodeAlphabet)))
	for i := range b {
		k, err := rand.Int(rand.Reader, n)
		if err != nil {
			return "", err
		}
		b[i] = pairCodeAlphabet[k.Int64()]
	}
	return string(b), nil
}

// StartPairing registers a device and returns its code and the secret it
// polls with. Each client address may start a few pairings per PairingTTL.
func (s *Service) StartPairing(ctx context.Context, c Client) (code, secret string, exp time.Time, err error) {
	c.Kind = KindDevice
	ip := clientIP(ctx)
	key, ok := limitAddr(ctx)
	if !ok {
		// An untrusted proxy named no client: its visitors share one
		// allowance, as they did before, since nothing else bounds starts.
		info, _ := netguard.From(ctx)
		key = "via " + info.IP.String()
	}
	now := time.Now()
	p := s.pair
	p.mu.Lock()
	defer p.mu.Unlock()
	p.pruneLocked(now)
	if ts := p.starts[key]; len(ts) >= pairStartsPerIP {
		return "", "", time.Time{}, &ThrottledError{RetryAfter: PairingTTL - now.Sub(ts[0]), What: "pairing attempts from this address"}
	}
	if len(p.byCode) >= maxPairings {
		return "", "", time.Time{}, ErrPairingBusy
	}
	for {
		if code, err = newPairCode(); err != nil {
			return "", "", time.Time{}, err
		}
		if _, taken := p.byCode[code]; !taken {
			break
		}
	}
	if secret, err = newToken(); err != nil {
		return "", "", time.Time{}, err
	}
	exp = now.Add(PairingTTL).Truncate(time.Second)
	x := &pairing{Code: code, Client: c, IP: ip, ExpiresAt: exp, secretHash: hashToken(secret)}
	p.byCode[code] = x
	p.bySecret[x.secretHash] = x
	p.starts[key] = append(p.starts[key], now)
	return code, secret, exp, nil
}

// PendingPairing describes the device behind a code, for the approval page.
// Wrong codes count against the person asking, together with those
// ApprovePairing sees, so looking codes up cannot guess them either.
func (s *Service) PendingPairing(ctx context.Context, code string) (Pairing, error) {
	token, _ := SessionTokenFrom(ctx)
	u, err := s.ValidateSession(ctx, token)
	if err != nil {
		return Pairing{}, err
	}
	key := pairGuessKey(u)
	if d := s.limit.wait(key); d > 0 {
		return Pairing{}, &ThrottledError{RetryAfter: d, What: "wrong pairing codes"}
	}
	p := s.pair
	p.mu.Lock()
	defer p.mu.Unlock()
	p.pruneLocked(time.Now())
	x, ok := p.byCode[NormalizePairCode(code)]
	if !ok {
		s.limit.fail(key)
		return Pairing{}, ErrPairingNotFound
	}
	return x.Pairing, nil
}

// pairGuessKey is what wrong pairing codes count under: the person.
func pairGuessKey(u User) string { return "pair:" + u.Name }

// ApprovePairing binds the device behind a code to a user. Wrong codes count
// against the approver like failed sign-ins, so codes cannot be guessed.
// It does not take grantMu: the session it issues is made in ClaimPairing,
// which checks under grantMu that the approver is still signed in.
func (s *Service) ApprovePairing(ctx context.Context, code string) (Pairing, error) {
	token, _ := SessionTokenFrom(ctx)
	u, err := s.ValidateSession(ctx, token)
	if err != nil {
		return Pairing{}, err
	}
	key := pairGuessKey(u)
	if d := s.limit.wait(key); d > 0 {
		return Pairing{}, &ThrottledError{RetryAfter: d, What: "wrong pairing codes"}
	}
	p := s.pair
	p.mu.Lock()
	defer p.mu.Unlock()
	p.pruneLocked(time.Now())
	x, ok := p.byCode[NormalizePairCode(code)]
	if !ok {
		s.limit.fail(key)
		return Pairing{}, ErrPairingNotFound
	}
	if x.Approved {
		return x.Pairing, ErrPairingUsed
	}
	s.limit.succeed(key)
	x.Approved, x.userID = true, u.ID
	x.approverHash = hashToken(token)
	return x.Pairing, nil
}

// ClaimPairing is the device's poll. Until approval it reports not approved;
// once approved it creates the device session, returns it once, and forgets
// the pairing.
func (s *Service) ClaimPairing(ctx context.Context, secret string) (PairingStatus, error) {
	s.grantMu.Lock()
	defer s.grantMu.Unlock()
	p := s.pair
	p.mu.Lock()
	p.pruneLocked(time.Now())
	x, ok := p.bySecret[hashToken(secret)]
	if !ok {
		p.mu.Unlock()
		return PairingStatus{}, ErrPairingNotFound
	}
	if !x.Approved {
		p.mu.Unlock()
		return PairingStatus{}, nil
	}
	approver, userID, client := x.approverHash, x.userID, x.Client
	p.mu.Unlock()

	u, err := s.validateSessionHash(ctx, approver)
	if err != nil && !errors.Is(err, ErrUnauthenticated) {
		return PairingStatus{}, err // the device asks again on its next poll
	}
	// Single use: a second poll with the same secret finds nothing. Nothing
	// else removes it meanwhile: forgetUser runs under grantMu too.
	p.mu.Lock()
	delete(p.byCode, x.Code)
	delete(p.bySecret, x.secretHash)
	p.mu.Unlock()
	if err != nil || u.ID != userID {
		return PairingStatus{}, ErrPairingNotFound // the approver signed out or was deleted since
	}
	g, err := s.CreateSession(ctx, u.ID, client)
	if err != nil {
		return PairingStatus{}, err
	}
	return PairingStatus{Approved: true, User: u, Grant: g}, nil
}

func (p *pairings) forgetUser(id int64) {
	p.mu.Lock()
	defer p.mu.Unlock()
	for code, x := range p.byCode {
		if x.userID == id {
			delete(p.byCode, code)
			delete(p.bySecret, x.secretHash)
		}
	}
}
