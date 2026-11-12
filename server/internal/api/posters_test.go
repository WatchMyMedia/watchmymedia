package api

import (
	"bytes"
	"fmt"
	"image"
	"image/png"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"watchmymedia/server/internal/artwork"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/images"
	"watchmymedia/server/internal/tmdb"
	"watchmymedia/server/internal/tmdb/tmdbtest"
)

// An admin chooses a poster: it goes out as posterPath on the routes every
// client already uses, stays through a refresh from TMDB, and goes back to
// TMDB's on reset (PLAN-POSTERS.md).
func TestPosters(t *testing.T) {
	t.Parallel()
	fake := tmdbtest.New(t)
	posters := func(paths ...string) any {
		list := make([]tmdb.Poster, 0, len(paths))
		for i, p := range paths {
			lang := "en"
			if i == 0 {
				lang = "de" // other languages sort after English
			}
			list = append(list, tmdb.Poster{FilePath: p, Width: 1000, Height: 1500, Language: lang})
		}
		return map[string]any{"posters": list}
	}
	fake.Handle("/movie/700101/images", func(*http.Request) any { return posters("/movieDE.jpg", "/movieA.jpg", "/movieB.jpg") })
	fake.Handle("/tv/700201/images", func(*http.Request) any { return posters("/showDE.jpg", "/showA.jpg", "/showB.jpg") })
	fake.Handle("/tv/700201/season/2/images", func(*http.Request) any { return posters("/seasonDE.jpg", "/seasonA.jpg") })

	var store *artwork.Store
	var imagesDir string
	srv := newTestServerWith(t, func(d *Deps) {
		store = artwork.New(filepath.Join(d.DataDir, "artwork"))
		cache := images.New(filepath.Join(d.DataDir, "images"), fake.URL).WithCustom(store)
		imagesDir = cache.Dir()
		d.Artwork, d.ImagesDir, d.Images, d.TMDB = store, imagesDir, cache.Handler(), fake.Client()
	})
	ctx := t.Context()
	q := srv.q

	upsertMovie := func(poster string) db.Movie {
		t.Helper()
		m, err := q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: 1, TmdbID: 700101, Title: "Ninety Fathoms", Genres: "[]", Credits: "[]", PosterPath: &poster})
		if err != nil {
			t.Fatal(err)
		}
		return m
	}
	movie := upsertMovie("/movieA.jpg")
	tvLib, err := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: "/tv"})
	if err != nil {
		t.Fatal(err)
	}
	showPoster, seasonPoster := "/showA.jpg", "/seasonA.jpg"
	show, err := q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: tvLib.ID, TmdbID: 700201, Title: "Harbor Lights", Genres: "[]", Credits: "[]", PosterPath: &showPoster})
	if err != nil {
		t.Fatal(err)
	}
	upsertSeason := func(poster string) db.Season {
		t.Helper()
		se, err := q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: show.ID, Number: 2, Name: "Season 2", PosterPath: &poster})
		if err != nil {
			t.Fatal(err)
		}
		return se
	}
	season := upsertSeason(seasonPoster)
	if _, err := srv.conn.ExecContext(ctx, `INSERT INTO history_items (item_type, item_id, movie_id, library_id, title, poster_path, updated_at) VALUES ('movie', ?, ?, 1, 'Ninety Fathoms', '/movieA.jpg', '')`, movie.ID, movie.ID); err != nil {
		t.Fatal(err)
	}

	moviePath := fmt.Sprintf("/api/movies/%d", movie.ID)
	showPath := fmt.Sprintf("/api/shows/%d", show.ID)
	seasonPath := fmt.Sprintf("/api/seasons/%d", season.ID)
	call := func(c *http.Client, method, path, body string, out any, header ...string) int {
		t.Helper()
		if r, ok := out.(*PosterResult); ok {
			*r = PosterResult{} // absent fields must not keep the last reply's
		}
		return doJSON(t, c, method, srv.URL+path, body, out, header...).StatusCode
	}

	// Members get none of it.
	if code := call(srv.client, "POST", "/api/users", `{"name":"casey","password":"password1"}`, nil); code != 201 {
		t.Fatal(code)
	}
	jar, _ := cookiejar.New(nil)
	member := &http.Client{Jar: jar}
	if code := call(member, "POST", "/api/auth/login", `{"name":"casey","password":"password1"}`, nil); code != 200 {
		t.Fatal(code)
	}
	for _, base := range []string{moviePath, showPath, seasonPath} {
		for _, r := range [][3]string{{"GET", "/posters", ""}, {"PUT", "/poster", `{"tmdbPath":"/movieB.jpg"}`}, {"POST", "/poster/upload", "x"}, {"DELETE", "/poster", ""}} {
			if code := doJSON(t, member, r[0], srv.URL+base+r[1], r[2], nil).StatusCode; code != http.StatusForbidden {
				t.Errorf("member %s %s%s: %d", r[0], base, r[1], code)
			}
		}
	}

	// What the dialog lists.
	var opts PosterOptions
	if code := call(srv.client, "GET", moviePath+"/posters", "", &opts); code != 200 {
		t.Fatal(code)
	}
	if !opts.TMDBAvailable || opts.PosterPath != "/movieA.jpg" || opts.TMDBPath != "/movieA.jpg" || opts.PosterSource != "" ||
		len(opts.Posters) != 3 || opts.Posters[0].Path != "/movieA.jpg" || opts.Posters[2].Path != "/movieDE.jpg" || len(opts.ShowPosters) != 0 {
		t.Fatalf("the movie's posters: %+v", opts)
	}
	if code := call(srv.client, "GET", seasonPath+"/posters", "", &opts); code != 200 || len(opts.Posters) != 2 || len(opts.ShowPosters) != 3 {
		t.Fatalf("the season's posters: %d, %+v", code, opts)
	}

	ch, cancel := srv.bus.Subscribe()
	defer cancel()
	changed := func() {
		t.Helper()
		select {
		case e := <-ch:
			if e.Type != "library.changed" {
				t.Fatalf("event %q", e.Type)
			}
		default:
			t.Fatal("no library.changed event")
		}
	}

	// Another of TMDB's posters; one TMDB does not list is refused.
	var res PosterResult
	if code := call(srv.client, "PUT", moviePath+"/poster", `{"tmdbPath":"/movieB.jpg"}`, &res); code != 200 || res.PosterPath != "/movieB.jpg" || res.PosterSource != "tmdb" {
		t.Fatalf("choosing TMDB's: %d, %+v", code, res)
	}
	changed()
	for _, body := range []string{`{"tmdbPath":"/showA.jpg"}`, `{"tmdbPath":"/c-0123456789abcdef01234567.jpg"}`, `{"tmdbPath":"../x.jpg"}`, `{}`, `{"tmdbPath":"/movieA.jpg","url":"https://example.com/a.jpg"}`} {
		if code := call(srv.client, "PUT", moviePath+"/poster", body, nil); code != http.StatusUnprocessableEntity {
			t.Errorf("%s: %d, want 422", body, code)
		}
	}
	// A season may take one of its show's.
	if code := call(srv.client, "PUT", seasonPath+"/poster", `{"tmdbPath":"/showB.jpg"}`, &res); code != 200 || res.PosterPath != "/showB.jpg" {
		t.Fatalf("a season with its show's poster: %d, %+v", code, res)
	}
	changed()

	// A refresh from TMDB keeps the choice and remembers TMDB's latest.
	if m := upsertMovie("/movieNew.jpg"); deref(m.PosterPath) != "/movieB.jpg" || deref(m.PosterTmdb) != "/movieNew.jpg" || m.PosterSource != "tmdb" {
		t.Fatalf("after a refresh: %+v", m)
	}
	if se := upsertSeason("/seasonNew.jpg"); deref(se.PosterPath) != "/showB.jpg" || deref(se.PosterTmdb) != "/seasonNew.jpg" {
		t.Fatalf("the season after a refresh: %+v", se)
	}
	var detail MovieDetail
	if code := call(srv.client, "GET", moviePath, "", &detail); code != 200 || detail.PosterPath != "/movieB.jpg" || detail.PosterSource != "tmdb" {
		t.Fatalf("the movie page: %d, %q %q", code, detail.PosterPath, detail.PosterSource)
	}
	// Choosing TMDB's own poster is going back to it.
	if code := call(srv.client, "PUT", moviePath+"/poster", `{"tmdbPath":"/movieNew.jpg"}`, &res); code != 200 || res.PosterPath != "/movieNew.jpg" || res.PosterSource != "" {
		t.Fatalf("choosing TMDB's own: %d, %+v", code, res)
	}
	changed()

	// An upload.
	var pic bytes.Buffer
	if err := png.Encode(&pic, image.NewRGBA(image.Rect(0, 0, 600, 900))); err != nil {
		t.Fatal(err)
	}
	if code := call(srv.client, "POST", moviePath+"/poster/upload", pic.String(), &res, "Content-Type", "image/png"); code != 200 || res.PosterSource != "upload" ||
		!artwork.IsName(strings.TrimPrefix(res.PosterPath, "/")) {
		t.Fatalf("an upload: %d, %+v", code, res)
	}
	changed()
	uploaded := res.PosterPath
	if got := doJSON(t, srv.client, "GET", srv.URL+"/api/images/w342"+uploaded, "", nil); got.StatusCode != 200 || !strings.HasPrefix(got.Header.Get("Content-Type"), "image/jpeg") {
		t.Fatalf("the uploaded poster at w342: %d %s", got.StatusCode, got.Header.Get("Content-Type"))
	}
	var copyPath string
	if err := srv.conn.QueryRowContext(ctx, `SELECT poster_path FROM history_items WHERE movie_id = ?`, movie.ID).Scan(&copyPath); err != nil || copyPath != uploaded {
		t.Fatalf("history's copy: %q, %v", copyPath, err)
	}
	if m := upsertMovie("/movieNewer.jpg"); deref(m.PosterPath) != uploaded {
		t.Fatalf("an upload after a refresh: %+v", m)
	}
	// The same picture on the show shares the file.
	if code := call(srv.client, "POST", showPath+"/poster/upload", pic.String(), &res, "Content-Type", "image/png"); code != 200 || res.PosterPath != uploaded {
		t.Fatalf("the same upload on a show: %d, %+v", code, res)
	}
	changed()

	for what, body := range map[string]string{"text": "not a picture", "a GIF": "GIF89a\x01\x00\x01\x00\x00\x00\x00;"} {
		if code := call(srv.client, "POST", moviePath+"/poster/upload", body, nil, "Content-Type", "image/jpeg"); code != http.StatusUnprocessableEntity {
			t.Errorf("uploading %s: %d, want 422", what, code)
		}
	}
	if code := call(srv.client, "POST", moviePath+"/poster/upload", strings.Repeat("x", artwork.MaxBytes+1), nil, "Content-Type", "image/jpeg"); code != http.StatusRequestEntityTooLarge {
		t.Errorf("uploading over the limit: %d, want 413", code)
	}

	// A link into this network is refused, whatever it serves.
	host := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write(pic.Bytes()) }))
	defer host.Close()
	got := doJSON(t, srv.client, "PUT", srv.URL+moviePath+"/poster", fmt.Sprintf(`{"url":%q}`, host.URL+"/a.png"), nil)
	if got.StatusCode != http.StatusUnprocessableEntity || !strings.Contains(string(got.Body), "this network") {
		t.Errorf("a link to this machine: %d %s", got.StatusCode, got.Body)
	}

	// Reset goes back to TMDB's latest; the file stays while the show uses
	// it and goes when nothing does.
	file := filepath.Join(store.Dir(), strings.TrimPrefix(uploaded, "/"))
	if code := call(srv.client, "DELETE", moviePath+"/poster", "", &res); code != 200 || res.PosterPath != "/movieNewer.jpg" || res.PosterSource != "" {
		t.Fatalf("reset: %d, %+v", code, res)
	}
	changed()
	if _, err := os.Stat(file); err != nil {
		t.Fatalf("the picture the show still uses: %v", err)
	}
	if code := call(srv.client, "DELETE", showPath+"/poster", "", &res); code != 200 || res.PosterPath != "/showA.jpg" {
		t.Fatalf("the show's reset: %d, %+v", code, res)
	}
	if _, err := os.Stat(file); !os.IsNotExist(err) {
		t.Fatalf("the unused picture was kept: %v", err)
	}
	if _, err := os.Stat(filepath.Join(imagesDir, "w342", strings.TrimPrefix(uploaded, "/"))); !os.IsNotExist(err) {
		t.Fatalf("the unused picture's w342 was kept: %v", err)
	}
	if code := call(srv.client, "GET", "/api/movies/999999/posters", "", nil); code != http.StatusNotFound {
		t.Errorf("a movie that is not there: %d", code)
	}
}

// Without a TMDB key the dialog still opens, for uploads and links.
func TestPostersWithoutTMDB(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	poster := "/a.jpg"
	m, err := srv.q.UpsertMovie(t.Context(), db.UpsertMovieParams{LibraryID: 1, TmdbID: 700102, Title: "Quiet Field", Genres: "[]", Credits: "[]", PosterPath: &poster})
	if err != nil {
		t.Fatal(err)
	}
	var opts PosterOptions
	if code := doJSON(t, srv.client, "GET", fmt.Sprintf("%s/api/movies/%d/posters", srv.URL, m.ID), "", &opts).StatusCode; code != 200 ||
		opts.TMDBAvailable || opts.Posters == nil || len(opts.Posters) != 0 || opts.PosterPath != "/a.jpg" {
		t.Fatalf("%d, %+v", code, opts)
	}
}
