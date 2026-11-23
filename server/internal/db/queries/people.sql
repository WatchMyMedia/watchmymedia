-- The people behind a title's cast and crew, and the titles a person is in.

-- name: SetMovieCredits :execrows
UPDATE movies SET credits = ?, crew = ?, cast_checked_at = ? WHERE id = ?;

-- name: SetShowCredits :execrows
UPDATE shows SET credits = ?, crew = ?, cast_checked_at = ? WHERE id = ?;

-- name: MarkMovieCastChecked :exec
UPDATE movies SET cast_checked_at = ? WHERE id = ?;

-- name: MarkShowCastChecked :exec
UPDATE shows SET cast_checked_at = ? WHERE id = ?;

-- name: DeleteMovieCast :exec
DELETE FROM movie_cast WHERE movie_id = ?;

-- name: DeleteShowCast :exec
DELETE FROM show_cast WHERE show_id = ?;

-- name: InsertMovieCast :exec
INSERT INTO movie_cast (movie_id, billing, person_id, name, character, profile_path) VALUES (?, ?, ?, ?, ?, ?);

-- name: InsertShowCast :exec
INSERT INTO show_cast (show_id, billing, person_id, name, character, profile_path) VALUES (?, ?, ?, ?, ?, ?);

-- name: ListMoviesForCast :many
-- Movies whose cast was stored without person IDs, for the fill-in job,
-- from the partial index of exactly those.
SELECT id, tmdb_id FROM movies INDEXED BY movies_cast_unchecked_idx
WHERE cast_checked_at IS NULL ORDER BY id LIMIT ?;

-- name: ListShowsForCast :many
SELECT id, tmdb_id FROM shows INDEXED BY shows_cast_unchecked_idx
WHERE cast_checked_at IS NULL ORDER BY id LIMIT ?;

-- name: ListPersonMovieCast :many
-- A person's parts in movies, from the person index, with when each
-- movie's cast was stored, so the newest names the person.
SELECT c.movie_id, c.billing, c.name, c.character, c.profile_path, COALESCE(m.cast_checked_at, '') AS checked_at
FROM movie_cast c INDEXED BY movie_cast_person_idx
CROSS JOIN movies m ON m.id = c.movie_id
WHERE c.person_id = ?
ORDER BY c.movie_id, c.billing;

-- name: ListPersonShowCast :many
SELECT c.show_id, c.billing, c.name, c.character, c.profile_path, COALESCE(s.cast_checked_at, '') AS checked_at
FROM show_cast c INDEXED BY show_cast_person_idx
CROSS JOIN shows s ON s.id = c.show_id
WHERE c.person_id = ?
ORDER BY c.show_id, c.billing;
