package enrich

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/guide"
	"watchmymedia/server/internal/livetv/guide/guidetest"
	"watchmymedia/server/internal/livetv/hdhomerun"
	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
	"watchmymedia/server/internal/settings"
	"watchmymedia/server/internal/tmdb"
	"watchmymedia/server/internal/tmdb/tmdbtest"
)

// TestFeedThroughTheGuide runs the fictional SiliconDust feed through the
// guide's fetch, with the enricher as its hook, and reads the result as
// clients do: the guide's window.
func TestFeedThroughTheGuide(t *testing.T) {
	ctx := t.Context()
	_, q := dbtest.Open(t)
	store := settings.New(q)
	start := time.Now()

	tuner := hdhrtest.New(nil)
	tuner.Channels = append(tuner.Channels,
		hdhrtest.Channel{GuideNumber: "22.1", GuideName: "Cedar Sports", URL: "http://tuner.test:5004/auto/v22.1"},
		hdhrtest.Channel{GuideNumber: "42.3", GuideName: "Willow Cinema", URL: "http://tuner.test:5004/auto/v42.3"})
	sd := &guidetest.SiliconDust{Feed: guidetest.Feed(start, guidetest.StandardChannels)}
	tunerSrv, sdSrv, picSrv := httptest.NewServer(tuner), httptest.NewServer(sd), httptest.NewServer(guidetest.NewPictures())
	t.Cleanup(func() { tunerSrv.Close(); sdSrv.Close(); picSrv.Close() })
	hosts := guidetest.Transport(map[string]string{"tuner.test": tunerSrv.URL, "api.hdhomerun.com": sdSrv.URL, "img.hdhomerun.com": picSrv.URL})
	live := livetv.New(q, store, func(a string) (*hdhomerun.Client, error) { return hdhomerun.New(a, hosts) })
	if _, err := live.Add(ctx, "tuner.test", ""); err != nil {
		t.Fatal(err)
	}

	// TMDB knows the Sleuth Files and one of the films; the feed's other
	// series find nothing.
	fake := fixtureTMDB(t)
	quiet := slog.New(slog.DiscardHandler)
	var g *guide.Service
	e := New(q, fake.Client(), Options{Log: quiet, On: func(ctx context.Context) bool { return g.TMDB(ctx) }})
	g = guide.New(q, store, live, guide.Options{Log: quiet, Transport: hosts, ArtDir: filepath.Join(t.TempDir(), "art"), Enrich: e.Enrich})
	if err := g.Configure(ctx, true, true, ""); err != nil {
		t.Fatal(err)
	}
	g.Fetch(ctx)

	window := func(number string) []livetv.Programme {
		t.Helper()
		channels, err := live.Channels(ctx, true)
		if err != nil {
			t.Fatal(err)
		}
		for _, c := range channels {
			if c.Number == number {
				w, err := g.Window(ctx, []string{c.ID}, start.Add(-5*time.Hour), start.Add(53*time.Hour))
				if err != nil {
					t.Fatal(err)
				}
				return w[c.ID]
			}
		}
		t.Fatalf("no channel %s", number)
		return nil
	}
	sleuth := map[int]livetv.Programme{}
	for _, p := range window("7.1") {
		if p.Title == "The Sleuth Files" && p.Season != nil && *p.Season == 2 {
			sleuth[*p.Episode] = p
		}
	}
	if len(sleuth) < 3 {
		t.Fatalf("the feed has %d Sleuth Files episodes; want 3 or more", len(sleuth))
	}
	// Its own description stays; the series' repeated one gives way.
	if p := sleuth[4]; p.StillURL != "/api/images/w780/sleuth2e4.jpg" || p.DescriptionSource != SourceProvider || p.PictureSource != SourceProvider {
		t.Errorf("S2E4: still %q, description from %q, picture from %q", p.StillURL, p.DescriptionSource, p.PictureSource)
	}
	if p := sleuth[6]; p.StillURL != "/api/images/w780/sleuth2e6.jpg" || p.Description != "TMDB's episode 6." || p.DescriptionSource != SourceTMDB {
		t.Errorf("S2E6: still %q, description %q from %q", p.StillURL, p.Description, p.DescriptionSource)
	}
	var film livetv.Programme
	for _, p := range window("42.3") {
		if p.Title == "Copper Valley Run" {
			film = p
		}
	}
	if film.StillURL != "/api/images/w780/copper.jpg" || film.PictureSource != SourceProvider || film.DescriptionSource != SourceProvider {
		t.Errorf("Copper Valley Run: still %q, picture from %q, description from %q", film.StillURL, film.PictureSource, film.DescriptionSource)
	}
	if n := fake.Count("/search/tv?The Sleuth Files&"); n != 1 {
		t.Errorf("asked TMDB about the Sleuth Files %d times; want 1", n)
	}
	for _, title := range []string{"Meadow News Hour", "Harbor Weather", "Fairway Notebook", "Conference Football", "Sports Desk Tonight"} {
		if n := fake.Count("/search/tv?" + title + "&"); n != 0 {
			t.Errorf("asked TMDB about %s", title)
		}
	}

	// The admin's switch off: the guide's next Enrich takes TMDB's out.
	if err := g.Configure(ctx, true, false, ""); err != nil {
		t.Fatal(err)
	}
	if err := e.Enrich(ctx); err != nil {
		t.Fatal(err)
	}
	for _, p := range window("7.1") {
		if p.StillURL != "" || p.DescriptionSource == SourceTMDB {
			t.Errorf("%s with the switch off: still %q, description from %q", p.Title, p.StillURL, p.DescriptionSource)
		}
	}
}

// fixtureTMDB is a TMDB with the fixture feed's Sleuth Files, both seasons, and
// Copper Valley Run.
func fixtureTMDB(t *testing.T) *tmdbtest.Server {
	fake := tmdbtest.New(t)
	fake.Handle("/search/tv", func(r *http.Request) any {
		if r.URL.Query().Get("query") == "The Sleuth Files" {
			return results(tmdb.TVResult{ID: 9001, Name: "The Sleuth Files", FirstAirDate: "2024-09-08", BackdropPath: "/sleuth.jpg"})
		}
		return results[tmdb.TVResult]()
	})
	fake.Handle("/search/movie", func(r *http.Request) any {
		if r.URL.Query().Get("query") == "Copper Valley Run" {
			return results(tmdb.MovieResult{ID: 9101, Title: "Copper Valley Run", ReleaseDate: "1962-04-01", BackdropPath: "/copper.jpg", Overview: "A flood."})
		}
		return results[tmdb.MovieResult]()
	})
	names := []string{"Ink on the Pier", "The Second Lantern", "Salt and Cinders", "The Low Tide", "A Quiet Ledger", "Glass Harbor",
		"The Missing Oar", "Fog Bell", "Paper Alibi", "The Tidewater Clock", "North Pier Confession", "Last Ferry Out"}
	for season, first := range map[int]time.Time{1: time.Date(2024, 9, 8, 0, 0, 0, 0, time.UTC), 2: time.Date(2025, 8, 24, 0, 0, 0, 0, time.UTC)} {
		var eps []tmdb.Episode
		for i, name := range names {
			eps = append(eps, tmdb.Episode{
				EpisodeNumber: i + 1, Name: name, AirDate: first.AddDate(0, 0, 7*i).Format(time.DateOnly),
				StillPath: fmt.Sprintf("/sleuth%de%d.jpg", season, i+1), Overview: fmt.Sprintf("TMDB's episode %d.", i+1),
			})
		}
		fake.JSON(fmt.Sprintf("/tv/9001/season/%d", season), tmdb.Season{SeasonNumber: season, Episodes: eps})
	}
	return fake
}
