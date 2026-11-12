package plex

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

func pmsClient(t *testing.T, token string, handler http.HandlerFunc) *Client {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.Header.Get("Accept") != "application/json" || r.Header.Get("X-Plex-Client-Identifier") != "fixture-probe" {
			t.Errorf("unexpected PMS request method or headers")
		}
		handler(w, r)
	}))
	t.Cleanup(server.Close)
	client, err := NewClient(server.URL, token, Options{ClientIdentifier: "fixture-probe"})
	if err != nil {
		t.Fatal(err)
	}
	return client
}

func pmsFixture(t *testing.T, name string) string {
	t.Helper()
	data, err := os.ReadFile("testdata/pms-" + name + ".json")
	if err != nil {
		t.Fatal(err)
	}
	return string(data)
}

func pmsJSON(w http.ResponseWriter, body string) {
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write([]byte(body))
}

func pmsErrorKind(t *testing.T, err error, want string) {
	t.Helper()
	var problem *Error
	if !errors.As(err, &problem) || problem.Kind != want {
		t.Fatalf("error = %v, want kind %s", err, want)
	}
}

func TestPMSIdentityAndLibraries(t *testing.T) {
	client := pmsClient(t, "synthetic-owner-token", func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/identity":
			if r.Header.Get("X-Plex-Token") != "" {
				t.Error("identity verification sent a credential before establishing server identity")
			}
			pmsJSON(w, pmsFixture(t, "identity"))
		case "/library/sections":
			if r.Header.Get("X-Plex-Token") != "synthetic-owner-token" {
				t.Error("library read did not retain the selected credential")
			}
			pmsJSON(w, pmsFixture(t, "libraries"))
		default:
			t.Errorf("unexpected request path: %s", r.URL.Path)
		}
	})
	identity, err := client.VerifyIdentity(t.Context(), "fictional-plex-server")
	if err != nil || !identity.Claimed || identity.Version != "1.43.4.1234-synthetic" {
		t.Fatalf("identity = %+v, err = %v", identity, err)
	}
	_, err = client.VerifyIdentity(t.Context(), "different-fictional-server")
	pmsErrorKind(t, err, "identity_mismatch")
	libraries, err := client.Libraries(t.Context())
	if err != nil || len(libraries) != 2 || libraries[0].ID != "1" || libraries[1].ID != "2" || libraries[1].Type != "show" {
		t.Fatalf("libraries = %+v, err = %v", libraries, err)
	}
}

func TestPMSSnapshotPreservesSourceFacts(t *testing.T) {
	requests := 0
	client := pmsClient(t, "synthetic-member-token", func(w http.ResponseWriter, r *http.Request) {
		requests++
		q := r.URL.Query()
		if r.URL.Path != "/library/sections/1/all" || q.Get("type") != "1" || q.Get("includeGuids") != "1" || q.Get("X-Plex-Container-Size") != "2" || q.Has("accountID") || r.Header.Get("X-Plex-Token") != "synthetic-member-token" {
			t.Error("snapshot request lost section, leaf type, GUID expansion or user credential")
		}
		switch q.Get("X-Plex-Container-Start") {
		case "0":
			pmsJSON(w, pmsFixture(t, "movies-first"))
		case "2":
			pmsJSON(w, pmsFixture(t, "movies-last"))
		default:
			t.Error("unexpected page start")
		}
	})
	snapshot, err := client.Snapshot(t.Context(), LibrarySection{ID: "1", Type: "movie"}, Paging{PageSize: 2})
	if err != nil || len(snapshot.Items) != 3 || snapshot.Pages != 2 || requests != 2 {
		t.Fatalf("snapshot len=%d pages=%d requests=%d err=%v", len(snapshot.Items), snapshot.Pages, requests, err)
	}
	first, progress, missing := snapshot.Items[0], snapshot.Items[1], snapshot.Items[2]
	if watched, known := first.Watched(); !watched || !known || first.ExternalIDs.TMDB != "810001" || first.ExternalIDs.IMDb != "tt0810001" {
		t.Fatalf("watched/external-ID evidence lost: %+v", first)
	}
	if watched, known := progress.Watched(); watched || !known || progress.ViewOffsetMS == nil || *progress.ViewOffsetMS != 1200000 || progress.ExternalIDs.TMDB != "810002" {
		t.Fatalf("partial progress was not kept distinct from completion: %+v", progress)
	}
	if watched, known := missing.Watched(); watched || known || missing.EditionTitle != "Festival Cut" {
		t.Fatalf("missing view count was treated as authoritative: %+v", missing)
	}
}

func TestPMSSnapshotUsesSeparateUserCredentials(t *testing.T) {
	owner := pmsClient(t, "synthetic-owner-token", func(w http.ResponseWriter, r *http.Request) {
		count := 1
		if r.Header.Get("X-Plex-Token") == "synthetic-member-token" {
			count = 0
		} else if r.Header.Get("X-Plex-Token") != "synthetic-owner-token" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		pmsJSON(w, fmt.Sprintf(`{"MediaContainer":{"size":1,"totalSize":1,"offset":0,"Metadata":[{"ratingKey":"101","librarySectionID":"1","type":"movie","viewCount":%d}]}}`, count))
	})
	member, err := NewClient(owner.baseURL.String(), "synthetic-member-token", Options{ClientIdentifier: "fixture-probe"})
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		client  *Client
		watched bool
	}{{owner, true}, {member, false}} {
		snapshot, err := tc.client.Snapshot(t.Context(), LibrarySection{ID: "1", Type: "movie"}, Paging{})
		if err != nil {
			t.Fatal(err)
		}
		if watched, known := snapshot.Items[0].Watched(); !known || watched != tc.watched {
			t.Errorf("wrong user's completion: watched=%v known=%v", watched, known)
		}
	}
}

func TestPMSContainerSectionScope(t *testing.T) {
	for _, tc := range []struct {
		name string
		body string
		kind string
	}{
		{"inherited", `{"MediaContainer":{"size":1,"totalSize":1,"offset":0,"librarySectionID":1,"Metadata":[{"ratingKey":"101","type":"movie","viewCount":1}]}}`, ""},
		{"conflicting child", `{"MediaContainer":{"size":1,"totalSize":1,"offset":0,"librarySectionID":1,"Metadata":[{"ratingKey":"101","librarySectionID":"2","type":"movie"}]}}`, "scope_mismatch"},
		{"wrong empty container", `{"MediaContainer":{"size":0,"totalSize":0,"offset":0,"librarySectionID":2}}`, "scope_mismatch"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			client := pmsClient(t, "synthetic-token", func(w http.ResponseWriter, _ *http.Request) {
				pmsJSON(w, tc.body)
			})
			snapshot, err := client.Snapshot(t.Context(), LibrarySection{ID: "1", Type: "movie"}, Paging{})
			if tc.kind != "" {
				pmsErrorKind(t, err, tc.kind)
				return
			}
			if err != nil || len(snapshot.Items) != 1 || snapshot.Items[0].LibrarySectionID != "1" {
				t.Fatalf("inherited section: %+v %v", snapshot, err)
			}
		})
	}
}

func TestPMSEpisodeAndShowIdentifiersStaySeparate(t *testing.T) {
	client := pmsClient(t, "synthetic-member-token", func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/library/sections/2/all":
			if r.URL.Query().Get("type") != "4" {
				t.Error("show section did not request episode leaves")
			}
			pmsJSON(w, pmsFixture(t, "episodes"))
		case "/library/metadata/301":
			pmsJSON(w, pmsFixture(t, "show"))
		default:
			t.Error("unexpected parent lookup")
		}
	})
	snapshot, err := client.Snapshot(t.Context(), LibrarySection{ID: "2", Type: "show"}, Paging{})
	if err != nil || len(snapshot.Items) != 2 {
		t.Fatalf("episode snapshot: %+v %v", snapshot, err)
	}
	episode := snapshot.Items[0]
	if episode.ExternalIDs.TMDB != "820101" || episode.SeasonNumber == nil || *episode.SeasonNumber != 0 || episode.EpisodeNumber == nil || *episode.EpisodeNumber != 1 || episode.GrandparentRatingKey != "301" {
		t.Fatalf("episode facts lost: %+v", episode)
	}
	show, err := client.Metadata(t.Context(), episode.GrandparentRatingKey)
	if err != nil || show.Type != "show" || show.ExternalIDs.TMDB != "820001" || show.EpisodeOrdering != "tvdbAiring" {
		t.Fatalf("show facts lost: %+v %v", show, err)
	}
	legacy := snapshot.Items[1]
	if !legacy.ExternalIDs.Unknown || legacy.ExternalIDs.TMDB != "" || legacy.ExternalIDs.TVDB != "" {
		t.Fatal("legacy show/season/episode path became an item ID")
	}
}

func TestPMSHistoryIsScopedAndNotCurrentState(t *testing.T) {
	client := pmsClient(t, "synthetic-owner-token", func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		if r.URL.Path != "/status/sessions/history/all" || q.Get("accountID") != "7" || q.Get("librarySectionID") != "1" || q.Get("viewedAt>") != "1699999999" || q.Get("sort") != "viewedAt:asc" {
			t.Error("history request lost its identity, library or time filter")
		}
		if q.Get("X-Plex-Container-Start") == "0" {
			pmsJSON(w, pmsFixture(t, "history-first"))
		} else {
			pmsJSON(w, pmsFixture(t, "history-last"))
		}
	})
	history, err := client.History(t.Context(), HistoryQuery{AccountID: 7, SectionID: "1", Since: time.Unix(1700000000, 0), Paging: Paging{PageSize: 1}})
	if err != nil || len(history.Events) != 2 || history.Pages != 2 {
		t.Fatalf("history: %+v %v", history, err)
	}
	if history.Events[0].HistoryKey == history.Events[1].HistoryKey || !history.Events[0].ViewedAt.Equal(history.Events[1].ViewedAt) {
		t.Fatal("same-second separate viewings were collapsed")
	}
	for _, event := range history.Events {
		if _, known := event.Item.Watched(); known || event.Item.ViewOffsetMS != nil || event.Item.LastViewedAt != nil {
			t.Fatal("historical payload supplied current-state evidence")
		}
	}
}

func TestPMSHistoryRejectsIgnoredFilters(t *testing.T) {
	for _, tc := range []struct {
		name string
		row  string
		kind string
	}{
		{"other account", `{"historyKey":"/status/sessions/history/12","ratingKey":"101","librarySectionID":"1","type":"movie","viewedAt":1700000000,"accountID":8}`, "scope_mismatch"},
		{"missing account", `{"historyKey":"/status/sessions/history/12","ratingKey":"101","librarySectionID":"1","type":"movie","viewedAt":1700000000}`, "scope_mismatch"},
		{"other library", `{"historyKey":"/status/sessions/history/12","ratingKey":"101","librarySectionID":"2","type":"movie","viewedAt":1700000000,"accountID":7}`, "scope_mismatch"},
		{"missing time", `{"historyKey":"/status/sessions/history/12","ratingKey":"101","librarySectionID":"1","type":"movie","accountID":7}`, "invalid_response"},
		{"earlier time", `{"historyKey":"/status/sessions/history/12","ratingKey":"101","librarySectionID":"1","type":"movie","viewedAt":1600000000,"accountID":7}`, "scope_mismatch"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			client := pmsClient(t, "synthetic-owner-token", func(w http.ResponseWriter, _ *http.Request) {
				pmsJSON(w, `{"MediaContainer":{"size":1,"totalSize":1,"offset":0,"Metadata":[`+tc.row+`]}}`)
			})
			history, err := client.History(t.Context(), HistoryQuery{AccountID: 7, SectionID: "1", Since: time.Unix(1700000000, 0)})
			pmsErrorKind(t, err, tc.kind)
			if len(history.Events) != 0 {
				t.Fatal("failed scoped read exposed partial history")
			}
		})
	}
}

func TestPMSHistoryRetainsDeletedItemAndSkipsMusic(t *testing.T) {
	client := pmsClient(t, "synthetic-owner-token", func(w http.ResponseWriter, _ *http.Request) {
		pmsJSON(w, `{"MediaContainer":{"size":2,"totalSize":2,"offset":0,"Metadata":[{"historyKey":"/status/sessions/history/12","type":"movie","title":"Gone from the Library","viewedAt":1700000000,"accountID":7},{"historyKey":"/status/sessions/history/13","ratingKey":"901","type":"track","viewedAt":1700000001,"accountID":7}]}}`)
	})
	history, err := client.History(t.Context(), HistoryQuery{AccountID: 7})
	if err != nil || len(history.Events) != 1 || history.SkippedUnsupported != 1 || history.Events[0].Item.RatingKey != "" {
		t.Fatalf("history without current metadata: %+v %v", history, err)
	}
}

func TestPMSPaginationRejectsIncompleteTraversal(t *testing.T) {
	first := `{"MediaContainer":{"size":1,"totalSize":2,"offset":0,"Metadata":[{"ratingKey":"101","librarySectionID":"1","type":"movie","viewCount":1}]}}`
	for _, tc := range []struct {
		name    string
		initial string
		next    string
		paging  Paging
		kind    string
	}{
		{"missing container", `{}`, "", Paging{}, "invalid_response"},
		{"missing metadata", `{"MediaContainer":{"size":1,"totalSize":1,"offset":0}}`, "", Paging{}, "invalid_response"},
		{"missing total", `{"MediaContainer":{"size":0}}`, "", Paging{}, "unsupported_paging"},
		{"changed total", first, `{"MediaContainer":{"size":1,"totalSize":3,"offset":1,"Metadata":[{"ratingKey":"102","librarySectionID":"1","type":"movie"}]}}`, Paging{PageSize: 1}, "pagination_drift"},
		{"repeated row", first, `{"MediaContainer":{"size":1,"totalSize":2,"offset":1,"Metadata":[{"ratingKey":"101","librarySectionID":"1","type":"movie"}]}}`, Paging{PageSize: 1}, "pagination_drift"},
		{"ignored offset", first, first, Paging{PageSize: 1}, "pagination_drift"},
		{"early empty", first, `{"MediaContainer":{"size":0,"totalSize":2,"offset":1}}`, Paging{PageSize: 1}, "pagination_drift"},
		{"max pages", first, "", Paging{PageSize: 1, MaxPages: 1}, "limit_exceeded"},
		{"max items", first, "", Paging{MaxItems: 1}, "limit_exceeded"},
		{"page too large", pmsFixture(t, "movies-first"), "", Paging{PageSize: 1}, "invalid_response"},
		{"wrong section", `{"MediaContainer":{"size":1,"totalSize":1,"offset":0,"Metadata":[{"ratingKey":"101","librarySectionID":"2","type":"movie"}]}}`, "", Paging{}, "scope_mismatch"},
		{"wrong type", `{"MediaContainer":{"size":1,"totalSize":1,"offset":0,"Metadata":[{"ratingKey":"101","librarySectionID":"1","type":"show"}]}}`, "", Paging{}, "scope_mismatch"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			client := pmsClient(t, "synthetic-token", func(w http.ResponseWriter, _ *http.Request) {
				calls++
				if calls == 1 {
					pmsJSON(w, tc.initial)
				} else {
					pmsJSON(w, tc.next)
				}
			})
			snapshot, err := client.Snapshot(t.Context(), LibrarySection{ID: "1", Type: "movie"}, tc.paging)
			pmsErrorKind(t, err, tc.kind)
			if len(snapshot.Items) != 0 || calls > 2 {
				t.Fatal("incomplete traversal escaped as a snapshot or performed unbounded requests")
			}
		})
	}
}

func TestPMSEmptySnapshotAndRevokedAccess(t *testing.T) {
	for _, status := range []int{http.StatusOK, http.StatusUnauthorized, http.StatusForbidden} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			calls := 0
			client := pmsClient(t, "synthetic-member-token", func(w http.ResponseWriter, r *http.Request) {
				calls++
				if r.Header.Get("X-Plex-Token") != "synthetic-member-token" {
					t.Error("credential fallback")
					w.WriteHeader(http.StatusUnauthorized)
					return
				}
				w.WriteHeader(status)
				if status == http.StatusOK {
					_, _ = w.Write([]byte(`{"MediaContainer":{"size":0,"totalSize":0,"offset":0}}`))
				}
			})
			snapshot, err := client.Snapshot(t.Context(), LibrarySection{ID: "1", Type: "movie"}, Paging{})
			if status == http.StatusOK && (err != nil || len(snapshot.Items) != 0 || snapshot.Pages != 1) {
				t.Fatalf("empty snapshot = %+v %v", snapshot, err)
			}
			if status != http.StatusOK && err == nil {
				t.Fatal("denied access became an empty snapshot")
			}
			if calls != 1 {
				t.Fatal("denied access retried with another credential")
			}
		})
	}
}

func TestPMSRejectsInvalidInputWithoutNetwork(t *testing.T) {
	client := pmsClient(t, "synthetic-token", func(_ http.ResponseWriter, _ *http.Request) {
		t.Error("invalid input reached network")
	})
	_, err := client.Snapshot(t.Context(), LibrarySection{ID: "../accounts", Type: "movie"}, Paging{})
	pmsErrorKind(t, err, "invalid_input")
	_, err = client.Snapshot(t.Context(), LibrarySection{ID: "1", Type: "artist"}, Paging{})
	pmsErrorKind(t, err, "invalid_input")
	_, err = client.Snapshot(t.Context(), LibrarySection{ID: "1", Type: "movie"}, Paging{PageSize: -1})
	pmsErrorKind(t, err, "invalid_input")
	_, err = client.History(t.Context(), HistoryQuery{})
	pmsErrorKind(t, err, "invalid_input")
	_, err = client.Metadata(t.Context(), "https://example.invalid/steal")
	pmsErrorKind(t, err, "invalid_input")
	_, err = client.VerifyIdentity(t.Context(), "")
	pmsErrorKind(t, err, "invalid_input")
}

func TestPMSCancelledRead(t *testing.T) {
	client := pmsClient(t, "synthetic-token", func(_ http.ResponseWriter, _ *http.Request) {
		t.Error("already-cancelled read reached server")
	})
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	_, err := client.Snapshot(ctx, LibrarySection{ID: "1", Type: "movie"}, Paging{})
	if err == nil {
		t.Fatal("cancelled read succeeded")
	}
}

func TestPMSExternalIDsKeepNamespacesAndConflicts(t *testing.T) {
	for _, tc := range []struct {
		name  string
		guids []string
		want  ExternalIDs
	}{
		{"modern", []string{"tmdb://810001", "tvdb://820001", "imdb://tt0810001"}, ExternalIDs{TMDB: "810001", TVDB: "820001", IMDb: "tt0810001"}},
		{"legacy", []string{"com.plexapp.agents.themoviedb://810001?lang=en", "com.plexapp.agents.imdb://tt0810001?lang=en"}, ExternalIDs{TMDB: "810001", IMDb: "tt0810001"}},
		{"duplicate", []string{"tmdb://810001", "tmdb://810001"}, ExternalIDs{TMDB: "810001"}},
		{"conflict", []string{"tmdb://810001", "tmdb://810002"}, ExternalIDs{TMDB: "810001", Conflict: true}},
		{"legacy episode", []string{"com.plexapp.agents.thetvdb://820001/1/2?lang=en"}, ExternalIDs{Unknown: true}},
		{"plex only", []string{"plex://movie/fictional-id"}, ExternalIDs{Unknown: true}},
		{"malformed", []string{"tmdb://invalid", "imdb://ttabc", "tmdb://1:99", "tmdb://owner@810001", "tmdb://810001#extra"}, ExternalIDs{Unknown: true}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := NormalizeGUIDs(tc.guids); got != tc.want {
				t.Fatalf("IDs = %+v, want %+v", got, tc.want)
			}
		})
	}
}

func TestPMSIdentifiersRejectLossyNumbers(t *testing.T) {
	for _, raw := range []string{`1.0`, `1e3`, `-1`, `null`, `"../1"`, `"01"`, `9223372036854775808`} {
		t.Run(strings.ReplaceAll(raw, "/", "_"), func(t *testing.T) {
			var item rawMetadata
			if err := json.Unmarshal([]byte(`{"ratingKey":`+raw+`,"type":"movie"}`), &item); err == nil {
				t.Fatal("invalid identifier accepted")
			}
		})
	}
}
