package api

import (
	"context"
	"fmt"
	"math"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/danielgtaylor/huma/v2"
	"golang.org/x/time/rate"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/lists"
)

// Members keep lists of their own, but MDBList runs on the admin's key and
// its daily quota (about a thousand requests on a free account), so what a
// member's lists fetch is limited; admins are not. Two limits:
//
//   - Each member has a bucket of MDBList fetches: listFetchBurst at once,
//     then one more every listFetchEvery. Previews (those not already
//     cached), making a list, changing its source or limit, and Refresh
//     take one when the list is from MDBList. A member can spend at most
//     about 300 requests a day this way, and making and previewing a list
//     or two never meets the limit.
//   - Refresh, for a list from TMDB or MDBList, runs at most once every
//     listRefreshEvery per list. Lists are fetched again on their own every
//     day, so this only stops a member looping the button.
//
// The limits are kept in memory: a restart forgets them.
const (
	listFetchBurst   = 20
	listFetchEvery   = 5 * time.Minute
	listRefreshEvery = 10 * time.Minute
	// maxListLimitKeys caps the members and lists remembered, so the maps
	// cannot grow without bound.
	maxListLimitKeys = 10000
)

type listFetchLimits struct {
	mu           sync.Mutex
	burst        int
	every        time.Duration
	refreshEvery time.Duration
	now          func() time.Time
	users        map[int64]*rate.Limiter
	refreshed    map[int64]time.Time
}

func newListFetchLimits() *listFetchLimits {
	return &listFetchLimits{burst: listFetchBurst, every: listFetchEvery, refreshEvery: listRefreshEvery, now: time.Now,
		users: map[int64]*rate.Limiter{}, refreshed: map[int64]time.Time{}}
}

// fetch takes one of u's MDBList fetches for a list of kind, or says how
// long to wait. Admins with access on this request, and other sources,
// are not limited.
func (l *listFetchLimits) fetch(ctx context.Context, u auth.User, kind string) error {
	if kind != lists.KindMDBList || effectiveAdmin(ctx, u) {
		return nil
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	lim := l.users[u.ID]
	if lim == nil {
		if len(l.users) >= maxListLimitKeys {
			for id, old := range l.users {
				if old.TokensAt(now) >= float64(l.burst) {
					delete(l.users, id)
				}
			}
		}
		lim = rate.NewLimiter(rate.Every(l.every), l.burst)
		l.users[u.ID] = lim
	}
	r := lim.ReserveN(now, 1)
	if d := r.DelayFrom(now); d > 0 {
		r.CancelAt(now)
		return tooSoon("too many MDBList fetches from your lists", d)
	}
	return nil
}

// refresh notes a Refresh of list id by u, or says how long to wait.
func (l *listFetchLimits) refresh(ctx context.Context, u auth.User, id int64) error {
	if effectiveAdmin(ctx, u) {
		return nil
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	if last, ok := l.refreshed[id]; ok {
		if d := last.Add(l.refreshEvery).Sub(now); d > 0 {
			return tooSoon("this list was refreshed a moment ago", d)
		}
	}
	if len(l.refreshed) >= maxListLimitKeys {
		for k, at := range l.refreshed {
			if now.Sub(at) >= l.refreshEvery {
				delete(l.refreshed, k)
			}
		}
	}
	l.refreshed[id] = now
	return nil
}

// forget undoes a refresh noted for id that did not fetch.
func (l *listFetchLimits) forget(id int64) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.refreshed, id)
}

// tooSoon is a 429 with Retry-After.
func tooSoon(what string, d time.Duration) error {
	secs := int(math.Ceil(d.Seconds()))
	msg := fmt.Sprintf("%s; try again in %s", what, (time.Duration(secs) * time.Second).String())
	return huma.ErrorWithHeaders(huma.Error429TooManyRequests(msg), http.Header{"Retry-After": {strconv.Itoa(secs)}})
}
