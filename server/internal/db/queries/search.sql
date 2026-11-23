-- name: SearchMovieTitles :many
-- Search runs in two steps. The first (Search...Titles, or Scan...Titles
-- for short words) finds the titles that might match and have a file to
-- play, and reads only what ranking them needs; internal/api ranks them and
-- the second (List...ByIDs) reads the rows of the few that are shown.
--
-- This one asks the trigram index (migration 00045) for the titles holding
-- every one of the words given, each a quoted string of three characters
-- or more: a word of the search as typed, or the first letters of a word
-- after the index's two marks or after a mark a word can follow. The index
-- looks in the title, the original title and both without their hyphens
-- and apostrophes.
--
-- legacy is what search matched before the index: the words as typed,
-- anywhere in the title or the original title. Those titles are always
-- kept, however the words are read now.
--
-- The order is pinned. Without statistics SQLite starts "has a present
-- file" from every present file in the library, once for each title found
-- (69 s for one search of the large seeded library), so each EXISTS reads
-- the title's own files first, and the index is read before the table it
-- describes. EXISTS asks by the index's rowid, so a title with no file is
-- dropped before its row is read: most episodes TMDB lists have none.
SELECT m.id, m.title, m.original_title, m.year,
       CAST(m.title LIKE sqlc.arg(legacy) OR COALESCE(m.original_title LIKE sqlc.arg(legacy), 0) AS INTEGER) AS legacy
FROM movies_search(CAST(sqlc.arg(words) AS TEXT)) x
CROSS JOIN movies m ON m.id = x.rowid
WHERE EXISTS (SELECT 1 FROM media_files f WHERE f.movie_id = x.rowid AND f.status = 'present');

-- name: ScanMovieTitles :many
-- SearchMovieTitles for words of one or two letters, anywhere in a title,
-- which the index cannot look up: it reads every title the index keeps,
-- a few bytes each where the table's rows hold the descriptions and
-- credits too. A title is a candidate when it holds each of up to three
-- words, as LIKE patterns; a word not used is '%'. Every title that
-- matched before is among them: it holds what was typed, so it holds each
-- word of it. A search with no letters or digits at all has no words, and
-- gives what was typed as marks, to look for in the titles themselves.
SELECT m.id, m.title, m.original_title, m.year,
       CAST(m.title LIKE sqlc.arg(legacy) OR COALESCE(m.original_title LIKE sqlc.arg(legacy), 0) AS INTEGER) AS legacy
FROM movies_search x
CROSS JOIN movies m ON m.id = x.rowid
WHERE ((x.words LIKE sqlc.arg(word1) AND x.words LIKE sqlc.arg(word2) AND x.words LIKE sqlc.arg(word3))
       OR x.title LIKE sqlc.narg(marks) OR x.original_title LIKE sqlc.narg(marks))
  AND EXISTS (SELECT 1 FROM media_files f WHERE f.movie_id = x.rowid AND f.status = 'present');

-- name: ListMoviesByIDs :many
SELECT * FROM movies WHERE id IN (sqlc.slice(ids));

-- name: SearchShowTitles :many
-- SearchMovieTitles for shows. A show is listed when one of its episodes
-- has a present file.
SELECT s.id, s.title, s.original_title, s.year,
       CAST(s.title LIKE sqlc.arg(legacy) OR COALESCE(s.original_title LIKE sqlc.arg(legacy), 0) AS INTEGER) AS legacy
FROM shows_search(CAST(sqlc.arg(words) AS TEXT)) x
CROSS JOIN shows s ON s.id = x.rowid
WHERE EXISTS (SELECT 1 FROM episodes e
              CROSS JOIN episode_files ef ON ef.episode_id = e.id
              CROSS JOIN media_files f ON f.id = ef.media_file_id
              WHERE e.show_id = x.rowid AND f.status = 'present');

-- name: ScanShowTitles :many
-- ScanMovieTitles for shows.
SELECT s.id, s.title, s.original_title, s.year,
       CAST(s.title LIKE sqlc.arg(legacy) OR COALESCE(s.original_title LIKE sqlc.arg(legacy), 0) AS INTEGER) AS legacy
FROM shows_search x
CROSS JOIN shows s ON s.id = x.rowid
WHERE ((x.words LIKE sqlc.arg(word1) AND x.words LIKE sqlc.arg(word2) AND x.words LIKE sqlc.arg(word3))
       OR x.title LIKE sqlc.narg(marks) OR x.original_title LIKE sqlc.narg(marks))
  AND EXISTS (SELECT 1 FROM episodes e
              CROSS JOIN episode_files ef ON ef.episode_id = e.id
              CROSS JOIN media_files f ON f.id = ef.media_file_id
              WHERE e.show_id = x.rowid AND f.status = 'present');

-- name: ListShowsByIDs :many
SELECT * FROM shows WHERE id IN (sqlc.slice(ids));

-- name: SearchEpisodeTitles :many
-- SearchMovieTitles for episodes, with what orders episodes that match
-- equally well: the show's title, the season and the number.
SELECT e.id, e.title, e.number, se.number AS season_number, s.title AS show_title,
       CAST(e.title LIKE sqlc.arg(legacy) AS INTEGER) AS legacy
FROM episodes_search(CAST(sqlc.arg(words) AS TEXT)) x
CROSS JOIN episodes e ON e.id = x.rowid
CROSS JOIN seasons se ON se.id = e.season_id
CROSS JOIN shows s ON s.id = e.show_id
WHERE EXISTS (SELECT 1 FROM episode_files ef
              CROSS JOIN media_files f ON f.id = ef.media_file_id
              WHERE ef.episode_id = x.rowid AND f.status = 'present');

-- name: ScanEpisodeTitles :many
-- ScanMovieTitles for episodes.
SELECT e.id, e.title, e.number, se.number AS season_number, s.title AS show_title,
       CAST(e.title LIKE sqlc.arg(legacy) AS INTEGER) AS legacy
FROM episodes_search x
CROSS JOIN episodes e ON e.id = x.rowid
CROSS JOIN seasons se ON se.id = e.season_id
CROSS JOIN shows s ON s.id = e.show_id
WHERE ((x.words LIKE sqlc.arg(word1) AND x.words LIKE sqlc.arg(word2) AND x.words LIKE sqlc.arg(word3))
       OR x.title LIKE sqlc.narg(marks))
  AND EXISTS (SELECT 1 FROM episode_files ef
              CROSS JOIN media_files f ON f.id = ef.media_file_id
              WHERE ef.episode_id = x.rowid AND f.status = 'present');

-- name: ListSearchEpisodesByIDs :many
-- The episodes search shows, each with its newest present file (0 when it
-- has none any more).
SELECT e.*, se.number AS season_number, s.title AS show_title, s.poster_path AS show_poster_path,
       CAST(COALESCE((SELECT MAX(f.id) FROM episode_files ef
                      CROSS JOIN media_files f ON f.id = ef.media_file_id
                      WHERE ef.episode_id = e.id AND f.status = 'present'), 0) AS INTEGER) AS file_id
FROM episodes e
JOIN seasons se ON se.id = e.season_id
JOIN shows s ON s.id = e.show_id
WHERE e.id IN (sqlc.slice(ids));

-- name: ListWatchStatesForItems :many
-- The signed-in person's state for the few titles a search shows.
SELECT item_id, completed, position_ms, duration_ms FROM watch_state
WHERE user_id = sqlc.arg(user_id) AND item_type = sqlc.arg(item_type) AND item_id IN (sqlc.slice(ids));

-- name: ListShowWatchedCountsByIDs :many
-- ListShowWatchedCounts for the few shows a search shows: each show's
-- watched episodes that have a file present.
SELECT e.show_id, COUNT(DISTINCT e.id) AS watched
FROM episodes e
CROSS JOIN watch_state w ON w.user_id = sqlc.arg(user_id) AND w.item_type = 'episode' AND w.item_id = e.id AND w.completed = 1
WHERE e.show_id IN (sqlc.slice(ids))
  AND EXISTS (SELECT 1 FROM episode_files ef CROSS JOIN media_files f ON f.id = ef.media_file_id
      WHERE ef.episode_id = e.id AND f.status = 'present')
GROUP BY e.show_id;
