package api

import (
	"fmt"
	"net/http"
	"sync"
	"testing"
	"time"

	"watchmymedia/server/internal/lists/mdblisttest"
)

// A member's lists can't spend the admin's MDBList quota in a loop:
// uncached previews, new lists and refreshes take from a bucket per
// member, and a list refreshes at most every ten minutes. Admins are not
// limited.
func TestMemberMDBListFetchesAreLimited(t *testing.T) {
	t.Parallel()
	var mu sync.Mutex
	now := time.Date(2026, 11, 12, 9, 0, 0, 0, time.UTC)
	advance := func(d time.Duration) {
		mu.Lock()
		now = now.Add(d)
		mu.Unlock()
	}
	e := newListsEnv(t, func(d *Deps) {
		d.listsState = newListsState()
		d.listsState.fetches.burst = 3
		d.listsState.fetches.now = func() time.Time {
			mu.Lock()
			defer mu.Unlock()
			return now
		}
	})
	e.mdb.Add(mdblisttest.List{ID: 5, User: "critic", Slug: "best", Name: "Best", Items: []mdblisttest.Item{
		{Title: "Lantern 4", Year: 2004, MediaType: "movie", TMDB: 9005},
	}})
	if res := e.admin(http.MethodPut, "/api/settings", fmt.Sprintf(`{"listsMdblistKey":%q}`, mdblisttest.Key), nil); res.StatusCode != http.StatusOK {
		t.Fatalf("key = %d %s", res.StatusCode, res.Body)
	}
	preview := func(limit int) string {
		return fmt.Sprintf(`{"mediaType":"all","limit":%d,"source":{"kind":"mdblist","ref":"critic/best"}}`, limit)
	}

	// The burst: a new list and two previews.
	var l MediaList
	if res := e.asMember(http.MethodPost, "/api/lists", `{"name":"Best","mediaType":"all","screen":"home","source":{"kind":"mdblist","ref":"critic/best"}}`, &l); res.StatusCode != http.StatusCreated {
		t.Fatalf("create = %d %s", res.StatusCode, res.Body)
	}
	for _, limit := range []int{10, 11} {
		if res := e.asMember(http.MethodPost, "/api/lists/preview", preview(limit), nil); res.StatusCode != http.StatusOK {
			t.Fatalf("preview %d = %d %s", limit, res.StatusCode, res.Body)
		}
	}
	spent := e.mdb.Total()
	res := e.asMember(http.MethodPost, "/api/lists/preview", preview(12), nil)
	if res.StatusCode != http.StatusTooManyRequests || res.Header.Get("Retry-After") != "300" {
		t.Errorf("preview past the burst = %d %q %s", res.StatusCode, res.Header.Get("Retry-After"), res.Body)
	}
	if res := e.asMember(http.MethodPost, "/api/lists/preview", preview(10), nil); res.StatusCode != http.StatusOK {
		t.Errorf("a cached preview = %d %s", res.StatusCode, res.Body)
	}
	if res := e.asMember(http.MethodPost, fmt.Sprintf("/api/lists/%d/refresh", l.ID), "", nil); res.StatusCode != http.StatusTooManyRequests {
		t.Errorf("refresh past the burst = %d %s", res.StatusCode, res.Body)
	}
	if res := e.asMember(http.MethodPatch, fmt.Sprintf("/api/lists/%d", l.ID), `{"limit":20}`, nil); res.StatusCode != http.StatusTooManyRequests {
		t.Errorf("a new limit past the burst = %d %s", res.StatusCode, res.Body)
	}
	if got := e.mdb.Total(); got != spent {
		t.Errorf("refused requests reached MDBList: %d more", got-spent)
	}
	// The admin isn't limited.
	for _, limit := range []int{12, 13, 14, 15} {
		if res := e.admin(http.MethodPost, "/api/lists/preview", preview(limit), nil); res.StatusCode != http.StatusOK {
			t.Errorf("admin preview %d = %d %s", limit, res.StatusCode, res.Body)
		}
	}

	// One more fetch every five minutes; a list refreshes at most every ten.
	advance(listFetchEvery)
	if res := e.asMember(http.MethodPost, fmt.Sprintf("/api/lists/%d/refresh", l.ID), "", nil); res.StatusCode != http.StatusOK {
		t.Fatalf("refresh = %d %s", res.StatusCode, res.Body)
	}
	advance(listFetchEvery)
	res = e.asMember(http.MethodPost, fmt.Sprintf("/api/lists/%d/refresh", l.ID), "", nil)
	if res.StatusCode != http.StatusTooManyRequests || res.Header.Get("Retry-After") != "300" {
		t.Errorf("a second refresh within ten minutes = %d %q %s", res.StatusCode, res.Header.Get("Retry-After"), res.Body)
	}
	// The cooldown refused it before it took a fetch: one is still there.
	if res := e.asMember(http.MethodPost, "/api/lists/preview", preview(16), nil); res.StatusCode != http.StatusOK {
		t.Errorf("preview after a refused refresh = %d %s", res.StatusCode, res.Body)
	}
	advance(listRefreshEvery)
	if res := e.asMember(http.MethodPost, fmt.Sprintf("/api/lists/%d/refresh", l.ID), "", nil); res.StatusCode != http.StatusOK {
		t.Errorf("refresh after ten minutes = %d %s", res.StatusCode, res.Body)
	}
	// An admin refreshes the household's lists without a wait.
	var shared MediaList
	if res := e.admin(http.MethodPost, "/api/lists", `{"name":"Shared","mediaType":"all","screen":"home","shared":true,"source":{"kind":"mdblist","ref":"critic/best"}}`, &shared); res.StatusCode != http.StatusCreated {
		t.Fatalf("shared create = %d %s", res.StatusCode, res.Body)
	}
	for range 2 {
		if res := e.admin(http.MethodPost, fmt.Sprintf("/api/lists/%d/refresh", shared.ID), "", nil); res.StatusCode != http.StatusOK {
			t.Errorf("admin refresh = %d %s", res.StatusCode, res.Body)
		}
	}
}
