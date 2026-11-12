-- TMDB pictures and descriptions for listings (PLAN-LIVE-TV-GUIDE.md §4.2,
-- G3). A refresh replaces each channel's programmes about once a day, so
-- matches are kept apart from them: a show or movie is looked up once a
-- month, not once a day, and each run stays within its 200 TMDB calls.
-- feed_description keeps the feed's text while a matched TMDB overview
-- replaces it, so turning TMDB off puts it back.

-- +goose Up
CREATE TABLE live_tv_guide_matches (
 -- The feed's series id: a show's cseries C… id or a movie's MV… id.
 series_key TEXT PRIMARY KEY,
 -- 'tv' or 'movie' and its id; '' and NULL when TMDB had no single sure match.
 tmdb_kind TEXT NOT NULL DEFAULT '',
 tmdb_id INTEGER,
 -- TMDB's backdrop path ("/abc.jpg") and a movie's overview.
 backdrop TEXT NOT NULL DEFAULT '',
 overview TEXT NOT NULL DEFAULT '',
 -- Unix seconds.
 checked_at INTEGER NOT NULL
);
CREATE TABLE live_tv_guide_seasons (
 tmdb_id INTEGER NOT NULL,
 season INTEGER NOT NULL,
 -- A JSON array of the season's episodes as TMDB lists them; '[]' when
 -- TMDB has no such season.
 episodes TEXT NOT NULL DEFAULT '[]',
 checked_at INTEGER NOT NULL,
 PRIMARY KEY (tmdb_id, season)
);
ALTER TABLE live_tv_programmes ADD COLUMN feed_description TEXT NOT NULL DEFAULT '';

-- +goose Down
ALTER TABLE live_tv_programmes DROP COLUMN feed_description;
DROP TABLE live_tv_guide_seasons;
DROP TABLE live_tv_guide_matches;
