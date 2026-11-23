-- Related titles at the end of movie and show pages.
-- Tags are what a title is like; internal/related writes them and works
-- out the rows from them.

-- name: DeleteMovieTags :exec
DELETE FROM movie_tags WHERE movie_id = ?;

-- name: InsertMovieTag :exec
INSERT INTO movie_tags (movie_id, kind, value, rank) VALUES (?, ?, ?, ?)
ON CONFLICT (movie_id, kind, value) DO NOTHING;

-- name: SetMovieRelated :execrows
UPDATE movies SET collection_id = ?, collection_name = ?, related_checked_at = ? WHERE id = ?;

-- name: DeleteShowTags :exec
DELETE FROM show_tags WHERE show_id = ?;

-- name: InsertShowTag :exec
INSERT INTO show_tags (show_id, kind, value, rank) VALUES (?, ?, ?, ?)
ON CONFLICT (show_id, kind, value) DO NOTHING;

-- name: SetShowRelatedChecked :execrows
UPDATE shows SET related_checked_at = ? WHERE id = ?;

-- name: ListMoviesForRelated :many
-- Movies whose related data was never fetched, for the fill-in job.
SELECT id, tmdb_id FROM movies WHERE related_checked_at IS NULL ORDER BY id LIMIT ?;

-- name: ListShowsForRelated :many
-- Shows whose related data was never fetched, for the fill-in job.
SELECT id, tmdb_id FROM shows WHERE related_checked_at IS NULL ORDER BY id LIMIT ?;

-- name: GetMovieRelatedInfo :one
SELECT id, tmdb_id, library_id, year, release_date, collection_id, collection_name, related_checked_at
FROM movies WHERE id = ?;

-- name: GetShowRelatedInfo :one
SELECT id, tmdb_id, library_id, year, first_air_date, related_checked_at
FROM shows WHERE id = ?;

-- name: CountRelatedMovies :one
-- How many movies have tags, for how rare a tag is.
SELECT COUNT(*) FROM movies WHERE related_checked_at IS NOT NULL;

-- name: CountRelatedShows :one
SELECT COUNT(*) FROM shows WHERE related_checked_at IS NOT NULL;

-- name: ListCollectionMovies :many
-- The movies of a collection, every copy, but none of the TMDB title the
-- page is for, in release order.
SELECT id, tmdb_id, library_id FROM movies
WHERE collection_id = sqlc.arg(collection_id) AND tmdb_id <> sqlc.arg(tmdb_id)
ORDER BY release_date IS NULL, release_date, id;

-- name: CountMovieTagTitles :many
-- How many movies share each of a movie's keywords, genres and language.
SELECT a.kind, a.value,
    (SELECT COUNT(*) FROM movie_tags c WHERE c.kind = a.kind AND c.value = a.value) AS titles
FROM movie_tags a
WHERE a.movie_id = ? AND a.kind IN ('keyword', 'genre', 'language');

-- name: CountShowTagTitles :many
SELECT a.kind, a.value,
    (SELECT COUNT(*) FROM show_tags c WHERE c.kind = a.kind AND c.value = a.value) AS titles
FROM show_tags a
WHERE a.show_id = ? AND a.kind IN ('keyword', 'genre', 'language');

-- name: ListMovieTagMatches :many
-- Movies sharing a keyword, a cast member or a director with a movie, with
-- both billing ranks. Genres and language are read only for the movies
-- found here or by recommendation (ListMovieTagsOf), so a genre shared by
-- half the library isn't read for every page.
SELECT b.movie_id AS other_id, a.kind, a.value, a.rank AS rank_a, b.rank AS rank_b
FROM movie_tags a
CROSS JOIN movie_tags b ON b.kind = a.kind AND b.value = a.value
WHERE a.movie_id = ? AND a.kind IN ('keyword', 'person', 'director') AND b.movie_id <> a.movie_id;

-- name: ListShowTagMatches :many
SELECT b.show_id AS other_id, a.kind, a.value, a.rank AS rank_a, b.rank AS rank_b
FROM show_tags a
CROSS JOIN show_tags b ON b.kind = a.kind AND b.value = a.value
WHERE a.show_id = ? AND a.kind IN ('keyword', 'person', 'creator') AND b.show_id <> a.show_id;

-- name: ListMovieRecommended :many
-- The library's movies a movie's TMDB recommendations name, every copy,
-- with TMDB's order.
SELECT m.id, t.rank
FROM movie_tags t
CROSS JOIN movies m ON m.tmdb_id = CAST(t.value AS INTEGER)
WHERE t.movie_id = ? AND t.kind = 'rec';

-- name: ListShowRecommended :many
SELECT s.id, t.rank
FROM show_tags t
CROSS JOIN shows s ON s.tmdb_id = CAST(t.value AS INTEGER)
WHERE t.show_id = ? AND t.kind = 'rec';

-- name: ListMoviesRecommending :many
-- The movies whose TMDB recommendations name a TMDB ID (as text).
SELECT movie_id FROM movie_tags WHERE kind = 'rec' AND value = ?;

-- name: ListShowsRecommending :many
SELECT show_id FROM show_tags WHERE kind = 'rec' AND value = ?;

-- name: ListMovieTagsOf :many
-- Some movies' genres and language.
SELECT movie_id, kind, value FROM movie_tags
WHERE kind IN ('genre', 'language') AND movie_id IN (sqlc.slice(ids));

-- name: ListShowTagsOf :many
SELECT show_id, kind, value FROM show_tags
WHERE kind IN ('genre', 'language') AND show_id IN (sqlc.slice(ids));

-- name: ListMovieRelatedRefs :many
-- What ordering and one card per title need of some movies.
SELECT id, tmdb_id, library_id, title, year, rating FROM movies WHERE id IN (sqlc.slice(ids));

-- name: ListShowRelatedRefs :many
SELECT id, tmdb_id, library_id, title, year, rating FROM shows WHERE id IN (sqlc.slice(ids));

-- name: ListLibraryCollectionMovieIDs :many
-- A library's movies of one TMDB collection, for its grid of them.
SELECT id FROM movies INDEXED BY movies_collection_idx
WHERE collection_id = sqlc.arg(collection_id) AND library_id = sqlc.arg(library_id);

-- name: ListCollectionsToFetch :many
-- TMDB collections the library's movies name that aren't kept, or were
-- fetched before stale, for the refresher, from the movies that have one.
SELECT DISTINCT m.collection_id
FROM movies m INDEXED BY movies_collection_idx
LEFT JOIN tmdb_collections c ON c.id = m.collection_id
WHERE m.collection_id IS NOT NULL AND (c.id IS NULL OR c.fetched_at < sqlc.arg(stale))
LIMIT sqlc.arg(max_rows);

-- name: UpsertTMDBCollection :exec
INSERT INTO tmdb_collections (id, name, poster_path, backdrop_path, parts, fetched_at) VALUES (?, ?, ?, ?, ?, ?)
ON CONFLICT (id) DO UPDATE SET name = excluded.name, poster_path = excluded.poster_path,
    backdrop_path = excluded.backdrop_path, parts = excluded.parts, fetched_at = excluded.fetched_at;

-- name: MarkTMDBCollectionFetched :exec
-- A collection TMDB no longer has: kept as it was, so it isn't asked for
-- again until it is stale; one never kept gets the name the movies gave it.
INSERT INTO tmdb_collections (id, name, fetched_at) VALUES (?, ?, ?)
ON CONFLICT (id) DO UPDATE SET fetched_at = excluded.fetched_at;

-- name: GetTMDBCollection :one
SELECT * FROM tmdb_collections WHERE id = ?;

-- name: ListLibraryCollections :many
-- A library's collections with two of their movies or more with a present
-- file, with what TMDB lists in each when it is kept, and the first film's
-- picture for one without its own.
SELECT m.collection_id, CAST(MAX(m.collection_name) AS TEXT) AS collection_name, COUNT(*) AS movies,
    MIN(m.year) AS first_year, MAX(m.year) AS last_year,
    (SELECT m2.poster_path FROM movies m2 INDEXED BY movies_collection_idx
        WHERE m2.collection_id = m.collection_id AND m2.library_id = m.library_id
        ORDER BY m2.release_date, m2.id LIMIT 1) AS first_poster_path,
    (SELECT m2.backdrop_path FROM movies m2 INDEXED BY movies_collection_idx
        WHERE m2.collection_id = m.collection_id AND m2.library_id = m.library_id
        ORDER BY m2.release_date, m2.id LIMIT 1) AS first_backdrop_path,
    c.name AS tmdb_name, c.poster_path, c.backdrop_path, c.parts
FROM movies m INDEXED BY movies_collection_idx
LEFT JOIN tmdb_collections c ON c.id = m.collection_id
WHERE m.collection_id IS NOT NULL AND m.library_id = sqlc.arg(library_id)
  AND EXISTS (SELECT 1 FROM media_files f WHERE f.movie_id = m.id AND f.status = 'present')
GROUP BY m.collection_id
HAVING COUNT(*) >= 2;

-- name: GetCollectionName :one
-- The name a movie of the collection gave it.
SELECT collection_name FROM movies INDEXED BY movies_collection_idx WHERE collection_id = ? LIMIT 1;
