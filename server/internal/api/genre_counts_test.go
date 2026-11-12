package api

import (
	"database/sql"
	"fmt"
	"maps"
	"net/http"
	"slices"
	"testing"
)

// Counts cover the whole library after the other filters, regardless of the
// selected genre or page. Exercise both query paths, including watch states.
func TestLibraryGenreCounts(t *testing.T) {
	t.Parallel()
	for _, kind := range []string{"movie", "tv"} {
		t.Run(kind, func(t *testing.T) {
			var conn *sql.DB
			srv := newTestServerWith(t, func(d *Deps) { conn = d.Conn })
			exec := func(query string, args ...any) int64 {
				t.Helper()
				res, err := conn.ExecContext(t.Context(), query, args...)
				if err != nil {
					t.Fatal(err)
				}
				id, err := res.LastInsertId()
				if err != nil {
					t.Fatal(err)
				}
				return id
			}
			lib := exec(`INSERT INTO libraries (type, name, path) VALUES (?, 'Counts', '/counts')`, kind)
			seed := func(library int64, title string, year int, genres string, height int, hdr, status string, watched, present bool) {
				t.Helper()
				itemType := "movie"
				var item, episode int64
				if kind == "movie" {
					item = exec(`INSERT INTO movies (library_id, tmdb_id, title, year, genres) VALUES (?, ?, ?, ?, ?)`, library, year, title, year, genres)
				} else {
					item = exec(`INSERT INTO shows (library_id, tmdb_id, title, year, genres, status) VALUES (?, ?, ?, ?, ?, ?)`, library, year, title, year, genres, status)
					season := exec(`INSERT INTO seasons (show_id, number, episode_count) VALUES (?, 1, 1)`, item)
					episode = exec(`INSERT INTO episodes (show_id, season_id, number, title) VALUES (?, ?, 1, 'Pilot')`, item, season)
					itemType = "episode"
				}
				fileStatus := "present"
				if !present {
					fileStatus = "missing"
				}
				var movieID any
				if kind == "movie" {
					movieID = item
				}
				file := exec(`INSERT INTO media_files (library_id, path, size, mtime, status, height, hdr, match_status, movie_id)
					VALUES (?, ?, 1, '2026-01-01T00:00:00Z', ?, ?, ?, 'matched', ?)`, library, fmt.Sprintf("/counts/%d/%d.mkv", library, item), fileStatus, height, hdr, movieID)
				if kind == "tv" {
					exec(`INSERT INTO episode_files (episode_id, media_file_id) VALUES (?, ?)`, episode, file)
					item = episode
				}
				if watched {
					exec(`INSERT INTO watch_state (user_id, item_type, item_id, completed) VALUES (1, ?, ?, 1)`, itemType, item)
				}
			}
			seed(lib, "Aurora", 1999, `["Drama","Comedy"]`, 2160, "hdr10", "Ended", true, true)
			seed(lib, "Beacon", 1995, `["Drama"]`, 1080, "", "Returning Series", false, true)
			seed(lib, "Beacon sequel", 2002, `["Comedy","Comedy"]`, 2160, "", "Canceled", false, true)
			seed(lib, "Cinder", 1998, `["Thriller"]`, 720, "hdr10", "Returning Series", false, true)
			seed(lib, "No genre", 1997, `[]`, 1080, "", "Returning Series", false, true)
			seed(lib, "Missing", 2001, `["Fantasy"]`, 1080, "", "Returning Series", false, false)
			other := exec(`INSERT INTO libraries (type, name, path) VALUES (?, 'Other', '/other')`, kind)
			seed(other, "Other library", 2000, `["Mystery"]`, 1080, "", "Returning Series", false, true)
			// Unmatched files belong in the item list, but have no genres to count.
			exec(`INSERT INTO media_files (library_id, path, size, mtime, parsed)
				VALUES (?, '/counts/Unknown.mkv', 1, '2026-01-01T00:00:00Z', '{}')`, lib)

			all := map[string]int64{"Comedy": 2, "Drama": 2, "Thriller": 1}
			type countCase struct {
				query string
				want  map[string]int64
			}
			cases := []countCase{
				{"limit=1", all},
				{"genre=dRaMa&limit=1&offset=1", all},
				{"genre=Unknown&limit=1&offset=100", all},
				{"sort=added&limit=1", all},
				{"filter=4k", map[string]int64{"Comedy": 2, "Drama": 1, "Thriller": 0}},
				{"filter=hdr", map[string]int64{"Comedy": 1, "Drama": 1, "Thriller": 1}},
				{"filter=unwatched", map[string]int64{"Comedy": 1, "Drama": 1, "Thriller": 1}},
				{"decade=1990", map[string]int64{"Comedy": 1, "Drama": 2, "Thriller": 1}},
				{"q=%20bEaCoN%20", map[string]int64{"Comedy": 1, "Drama": 1, "Thriller": 0}},
				{"filter=4k&decade=1990&q=aurora&genre=Thriller", map[string]int64{"Comedy": 1, "Drama": 1, "Thriller": 0}},
				{"q=no-such-title", map[string]int64{"Comedy": 0, "Drama": 0, "Thriller": 0}},
			}
			if kind == "tv" {
				cases = append(cases,
					countCase{"filter=continuing", map[string]int64{"Comedy": 0, "Drama": 1, "Thriller": 1}},
					countCase{"filter=ended", map[string]int64{"Comedy": 2, "Drama": 1, "Thriller": 0}})
			}
			base := fmt.Sprintf("%s/api/libraries/%d/items?", srv.URL, lib)
			for _, tc := range cases {
				t.Run(tc.query, func(t *testing.T) {
					var got ItemList
					doJSON(t, srv.client, http.MethodGet, base+tc.query, "", &got)
					if !maps.Equal(got.GenreCounts, tc.want) {
						t.Errorf("genre counts = %v, want %v", got.GenreCounts, tc.want)
					}
					if !slices.Equal(got.Facets.Genres, []string{"Comedy", "Drama", "Thriller"}) {
						t.Errorf("facets = %v", got.Facets.Genres)
					}
				})
			}
			var page ItemList
			doJSON(t, srv.client, http.MethodGet, base+"genre=dRaMa&limit=1&offset=1", "", &page)
			if page.Total != 2 || len(page.Items) != 1 || page.Items[0].Title != "Beacon" {
				t.Errorf("genre filter or pagination changed: %+v", page)
			}
		})
	}
}

func TestGenreCountsOptionalContract(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	// The fixture has only an unmatched file: known counts are an empty object,
	// distinct from an absent field on an older server.
	var page ItemList
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/libraries/1/items", "", &page)
	if page.GenreCounts == nil || len(page.GenreCounts) != 0 {
		t.Errorf("unmatched-only library counts = %v, want an empty object", page.GenreCounts)
	}
	var doc struct {
		Components struct {
			Schemas struct {
				ItemList struct {
					Required   []string
					Properties struct {
						GenreCounts struct {
							Type                 string
							AdditionalProperties struct{ Type, Format string }
						}
					}
				}
			}
		}
	}
	doJSON(t, srv.client, http.MethodGet, srv.URL+OpenAPIPath+".json", "", &doc)
	schema := doc.Components.Schemas.ItemList
	if slices.Contains(schema.Required, "genreCounts") {
		t.Error("genreCounts must stay optional for older servers")
	}
	counts := schema.Properties.GenreCounts
	if counts.Type != "object" || counts.AdditionalProperties.Type != "integer" || counts.AdditionalProperties.Format != "int64" {
		t.Errorf("genreCounts schema = %+v", counts)
	}
}
