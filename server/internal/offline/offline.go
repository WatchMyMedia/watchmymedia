// Package offline holds what the server keeps for offline downloads
// (PLAN-DOWNLOADS.md) outside the database: who may download, and the
// transfers running now, which Activity › Downloads shows. A download is
// not a play: it has no row in History and no heartbeats, so its transfer
// is tracked here, from the bytes its routes send.
package offline

import (
	"context"
	"crypto/rand"
	"encoding/base32"
	"errors"
	"sort"
	"strings"
	"sync"
	"time"

	"watchmymedia/server/internal/settings"
)

// KeyAccess in settings says who may download to watch offline: "none",
// "admins" (the default) or "everyone".
const KeyAccess = "offline.access"

// Who may download.
const (
	AccessNone     = "none"
	AccessAdmins   = "admins"
	AccessEveryone = "everyone"
)

// Access reads who may download; anything unknown is the default, admins.
func Access(ctx context.Context, store *settings.Store) string {
	if store == nil {
		return AccessAdmins
	}
	v, _ := store.Get(ctx, KeyAccess, AccessAdmins)
	switch v {
	case AccessNone, AccessEveryone:
		return v
	}
	return AccessAdmins
}

// ErrBadAccess is a value SetAccess does not take.
var ErrBadAccess = errors.New("access is none, admins or everyone")

// SetAccess changes who may download.
func SetAccess(ctx context.Context, store *settings.Store, v string) error {
	switch v {
	case AccessNone, AccessAdmins, AccessEveryone:
		return store.Set(ctx, KeyAccess, v)
	}
	return ErrBadAccess
}

// Allowed says whether a person may download under access. Admin is the
// account's role, wherever the device is: a phone syncing away from home
// must not lose an admin's downloads.
func Allowed(access string, admin bool) bool {
	switch access {
	case AccessEveryone:
		return true
	case AccessAdmins:
		return admin
	}
	return false
}

// NewID returns a download record's public ID: 16 random letters and digits.
func NewID() string {
	b := make([]byte, 10)
	_, _ = rand.Read(b)
	return strings.ToLower(base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(b))
}

// Info describes a download's transfer: what is sent, to whom.
type Info struct {
	Record     string
	UserID     int64
	UserName   string
	DeviceName string
	FileID     int64
	// Item is the download's snapshot of the item, as its record keeps it.
	Item    string
	Quality string
	Mode    string
	// Network is local, tailnet or remote, for the address fetching.
	Network string
	// SessionID is the download session's, for a transcode's speed.
	SessionID string
	Encoder   string
	// SizeBytes is the download's size, an estimate for HLS.
	SizeBytes int64
	Estimated bool
}

// Transfer is a download being fetched now.
type Transfer struct {
	Info
	StartedAt  time.Time
	LastSeenAt time.Time
	// BytesSent is what this transfer sent since it started.
	BytesSent int64
	// Bandwidth is bits per second over the last 30 seconds.
	Bandwidth int64
	// Progress is how far through the download the device has fetched, 0
	// to 1: the furthest byte of the file, or segment of the playlist.
	Progress float64
}

// Window is how long a transfer stays listed after its last request:
// downloaders pause between segments, and wait out a busy server.
const Window = time.Minute

// Transfers keeps the downloads being fetched now. The zero value is not
// usable; use NewTransfers.
type Transfers struct {
	now func() time.Time
	mu  sync.Mutex
	m   map[string]*transfer
}

type transfer struct {
	Transfer
	// buckets are bytes per second, the last bucketSecs seconds, by unix
	// second modulo bucketSecs.
	buckets [bucketSecs]int64
	stamps  [bucketSecs]int64
}

const bucketSecs = 30

// NewTransfers makes an empty list; now is the clock (nil for time.Now).
func NewTransfers(now func() time.Time) *Transfers {
	if now == nil {
		now = time.Now
	}
	return &Transfers{now: now, m: map[string]*transfer{}}
}

// Add counts n bytes sent for a download and moves its progress to at
// least progress (0 to 1; a negative value leaves it).
func (t *Transfers) Add(info Info, n int64, progress float64) {
	now := t.now()
	t.mu.Lock()
	defer t.mu.Unlock()
	t.pruneLocked(now)
	tr, ok := t.m[info.Record]
	if !ok {
		tr = &transfer{}
		tr.StartedAt = now
		t.m[info.Record] = tr
	}
	tr.Info = info
	tr.LastSeenAt = now
	tr.BytesSent += n
	tr.Progress = max(tr.Progress, min(progress, 1))
	sec := now.Unix()
	i := sec % bucketSecs
	if tr.stamps[i] != sec {
		tr.stamps[i], tr.buckets[i] = sec, 0
	}
	tr.buckets[i] += n
}

// Forget drops a download's transfer, as when it is revoked or removed.
func (t *Transfers) Forget(record string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	delete(t.m, record)
}

func (t *Transfers) pruneLocked(now time.Time) {
	for id, tr := range t.m {
		if now.Sub(tr.LastSeenAt) > Window {
			delete(t.m, id)
		}
	}
}

// List returns the transfers of the last Window, oldest first; userID 0
// lists everyone's.
func (t *Transfers) List(userID int64) []Transfer {
	now := t.now()
	t.mu.Lock()
	defer t.mu.Unlock()
	t.pruneLocked(now)
	out := []Transfer{}
	for _, tr := range t.m {
		if userID != 0 && tr.UserID != userID {
			continue
		}
		v := tr.Transfer
		v.Bandwidth = tr.bandwidth(now)
		out = append(out, v)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].StartedAt.Before(out[j].StartedAt) })
	return out
}

// bandwidth is bits per second over the last bucketSecs seconds, or since
// the transfer started when that is sooner.
func (tr *transfer) bandwidth(now time.Time) int64 {
	sec := now.Unix()
	var sum int64
	for i := range bucketSecs {
		if sec-tr.stamps[i] < bucketSecs {
			sum += tr.buckets[i]
		}
	}
	span := min(max(sec-tr.StartedAt.Unix(), 1), bucketSecs)
	return sum * 8 / span
}
