-- +goose Up
-- TMDB requests that could not be sent (no network, DNS) were stored in
-- match_error as Go prints them, with the request URL and so the TMDB API
-- key in its query: tmdb: Get "https://api.themoviedb.org/3/search/movie?
-- api_key=…&query=…": dial tcp: …. Settings shows these errors and backups
-- carry them. Keep the URL up to its query, which says what was asked for,
-- and the error after it.
UPDATE media_files
SET match_error =
    substr(match_error, 1, instr(match_error, '?') - 1) ||
    substr(match_error, instr(match_error, '?') + instr(substr(match_error, instr(match_error, '?')), '"') - 1)
WHERE instr(match_error, 'api_key=') > 0
  AND instr(match_error, '"') > 0
  AND instr(match_error, '?') > instr(match_error, '"')
  AND instr(substr(match_error, instr(match_error, '?')), '"') > 0;

-- Anything else that still holds a key says no more than this.
UPDATE media_files
SET match_error = 'tmdb: request failed'
WHERE instr(match_error, 'api_key=') > 0;

-- +goose Down
-- The keys are gone for good; going back leaves the errors as they are.
SELECT 1;
