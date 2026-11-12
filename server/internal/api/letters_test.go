package api

import (
	"fmt"
	"net/http"
	"net/url"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
)

func TestLetterOf(t *testing.T) {
	t.Parallel()
	for title, want := range map[string]string{
		"The Matrix":    "M",
		"the office":    "O",
		"A Quiet Place": "Q",
		"An Education":  "E",
		"Alien":         "A",
		"Annie Hall":    "A",
		"A.I.":          "A",
		"Theodore":      "T",
		"9 Songs":       "#",
		"(500) Days":    "#",
		"'Allo 'Allo!":  "#",
		"Élite":         "#",
		"Ōoku":          "#",
		"The 100":       "#",
		"zoo":           "Z",
		"":              "#",
	} {
		if got := letterOf(title); got != want {
			t.Errorf("letterOf(%q) = %q, want %q", title, got, want)
		}
	}
}

func TestLetterOffsets(t *testing.T) {
	t.Parallel()
	titles := []string{"(500) Days", "9 Songs", "Alien", "The Avengers", "Brazil", "The Matrix", "Zoo", "Élite"}
	items := make([]BrowseItem, len(titles))
	for i, title := range titles {
		items[i] = BrowseItem{Title: title}
	}
	sortItems(items, "title")
	got := letterOffsets(items)
	// # comes first and again at the end (É sorts after z); it keeps the first.
	want := []LetterOffset{{"#", 0}, {"A", 2}, {"B", 4}, {"M", 5}, {"Z", 6}}
	if fmt.Sprint(got) != fmt.Sprint(want) {
		t.Errorf("letterOffsets = %v, want %v", got, want)
	}
	if got := letterOffsets(nil); got == nil || len(got) != 0 {
		t.Errorf("no items: %v", got)
	}
}

// TestItemLetters checks the A–Z offsets cover the whole filtered list, not
// just the page asked for, and that each one lands on its letter's first item.
func TestItemLetters(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx := t.Context()
	q := srv.q
	now := db.FormatTime(time.Now())
	// Twelve movies a letter (with articles on some), so Z is past the first
	// page of 200; three in four are dramas, so they fill more than a page too.
	n := 0
	for c := 'A'; c <= 'Z'; c++ {
		for i := range 12 {
			title := fmt.Sprintf("%c%c Movie %d", c, 'a'+i, i)
			switch i {
			case 3:
				title = "The " + title
			case 6:
				title = "An " + title
			}
			genres := `["Drama"]`
			if n%4 == 0 {
				genres = `["Comedy"]`
			}
			n++
			m, err := q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: 1, TmdbID: int64(1000 + n), Title: title, Overview: "o", Genres: genres, Credits: "[]"})
			if err != nil {
				t.Fatal(err)
			}
			f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: 1, Path: fmt.Sprintf("/m/%d.mkv", n), Size: 1, Mtime: now, AddedAt: now,
				Parsed: `{"kind":"movie"}`, LastSeenAt: now, SeenInScan: 1})
			if err != nil {
				t.Fatal(err)
			}
			if err := q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &m.ID, ID: f.ID}); err != nil {
				t.Fatal(err)
			}
		}
	}
	for _, title := range []string{"9 Songs", "(500) Days of Summer"} {
		n++
		m, _ := q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: 1, TmdbID: int64(1000 + n), Title: title, Overview: "o", Genres: `["Drama"]`, Credits: "[]"})
		f, _ := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: 1, Path: fmt.Sprintf("/m/%d.mkv", n), Size: 1, Mtime: now, AddedAt: now,
			Parsed: `{"kind":"movie"}`, LastSeenAt: now, SeenInScan: 1})
		_ = q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &m.ID, ID: f.ID})
	}

	check := func(query string) []LetterOffset {
		t.Helper()
		base := srv.URL + "/api/libraries/1/items?" + query
		var page, all ItemList
		doJSON(t, srv.client, http.MethodGet, base+"&limit=200", "", &page)
		doJSON(t, srv.client, http.MethodGet, base+"&limit=1000", "", &all)
		if all.Total <= 200 || int64(len(all.Items)) != all.Total {
			t.Fatalf("%s: want more than a page, got %d of %d", query, len(all.Items), all.Total)
		}
		if fmt.Sprint(page.Letters) != fmt.Sprint(all.Letters) {
			t.Errorf("%s: the first page's letters %v differ from the whole list's %v", query, page.Letters, all.Letters)
		}
		var want []LetterOffset
		for i, it := range all.Items {
			if l := letterOf(it.Title); len(want) == 0 || want[len(want)-1].Letter != l {
				want = append(want, LetterOffset{l, int64(i)})
			}
		}
		if fmt.Sprint(page.Letters) != fmt.Sprint(want) {
			t.Errorf("%s: letters = %v, want %v", query, page.Letters, want)
		}
		// The page holding a letter's offset has the letter's first item there.
		for _, lo := range page.Letters {
			var p ItemList
			doJSON(t, srv.client, http.MethodGet, fmt.Sprintf("%s&limit=200&offset=%d", base, lo.Offset/200*200), "", &p)
			if it := p.Items[lo.Offset%200]; letterOf(it.Title) != lo.Letter {
				t.Errorf("%s: %s at %d is %q", query, lo.Letter, lo.Offset, it.Title)
			}
		}
		return page.Letters
	}

	letters := check("sort=title")
	// Digits and brackets, then the fixture's unmatched "Heat (1995).mkv" with
	// the H movies, then A–Z.
	if len(letters) != 27 || letters[0] != (LetterOffset{"#", 0}) || letters[1] != (LetterOffset{"A", 2}) {
		t.Errorf("letters = %v", letters)
	}
	if z := letters[26]; z.Letter != "Z" || z.Offset != 2+25*12+1 {
		t.Errorf("Z = %v, want offset %d", z, 2+25*12+1)
	}

	// The search leaves out the # titles and Heat, and the genre a quarter of the rest.
	drama := check("sort=title&genre=Drama&q=" + url.QueryEscape("movie"))
	if len(drama) != 26 || drama[0] != (LetterOffset{"A", 0}) || drama[25] != (LetterOffset{"Z", 25 * 9}) {
		t.Errorf("drama letters = %v", drama)
	}

	var added ItemList
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/libraries/1/items?sort=added", "", &added)
	if added.Letters != nil {
		t.Errorf("letters when sorting by date added: %v", added.Letters)
	}
}
