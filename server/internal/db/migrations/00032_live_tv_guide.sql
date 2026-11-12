-- Programme listings for live channels (PLAN-LIVE-TV-GUIDE.md §5.4). A
-- refresh replaces each listed channel's programmes in one transaction, so a
-- reader sees the old listings or the new, never a mix. Pictures from the
-- feed are fetched on first request and kept apart from TMDB's artwork.
-- +goose Up
CREATE TABLE live_tv_guide_channels (
 channel_id TEXT PRIMARY KEY REFERENCES live_tv_channels(id) ON DELETE CASCADE,
 -- The feed's channel id; '' while the feed doesn't list the channel.
 provider_id TEXT NOT NULL DEFAULT '',
 feed_name TEXT NOT NULL DEFAULT '',
 logo TEXT NOT NULL DEFAULT '',
 -- 'number' (the name wasn't checked), 'name' (it agreed) or '' (missing).
 matched_by TEXT NOT NULL DEFAULT '',
 flagged INTEGER NOT NULL DEFAULT 0,
 -- The admin's switch for this channel's listings.
 listings INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE live_tv_programmes (
 id TEXT PRIMARY KEY,
 channel_id TEXT NOT NULL REFERENCES live_tv_channels(id) ON DELETE CASCADE,
 start_at INTEGER NOT NULL,
 end_at INTEGER NOT NULL CHECK(end_at > start_at),
 title TEXT NOT NULL,
 episode_title TEXT NOT NULL DEFAULT '',
 season INTEGER,
 episode INTEGER,
 episode_label TEXT NOT NULL DEFAULT '',
 air_date TEXT NOT NULL DEFAULT '',
 is_new INTEGER NOT NULL DEFAULT 0,
 is_live INTEGER NOT NULL DEFAULT 0,
 is_repeat INTEGER NOT NULL DEFAULT 0,
 hd INTEGER NOT NULL DEFAULT 0,
 rating TEXT NOT NULL DEFAULT '',
 -- A JSON array of the feed's category names.
 categories TEXT NOT NULL DEFAULT '[]',
 -- 'episode', 'show' or 'movie', from the feed's programme id.
 kind TEXT NOT NULL DEFAULT '',
 series_key TEXT NOT NULL DEFAULT '',
 description TEXT NOT NULL DEFAULT '',
 description_source TEXT NOT NULL DEFAULT '',
 -- Keys into live_tv_guide_art.
 picture TEXT NOT NULL DEFAULT '',
 poster TEXT NOT NULL DEFAULT '',
 picture_source TEXT NOT NULL DEFAULT '',
 -- A matched TMDB still or backdrop's path on this server (G3).
 still TEXT NOT NULL DEFAULT '',
 -- The TMDB match (G3): 'tv' or 'movie' and its id.
 tmdb_kind TEXT NOT NULL DEFAULT '',
 tmdb_id INTEGER
);
-- No programme is longer than 24 hours, so a window query bounds start_at
-- on both sides and reads only this index's range for each channel.
CREATE INDEX live_tv_programme_window ON live_tv_programmes(channel_id, start_at);
CREATE TABLE live_tv_guide_art (
 id TEXT PRIMARY KEY,
 url TEXT NOT NULL UNIQUE,
 -- 0 until the picture is fetched, and again once it is evicted.
 bytes INTEGER NOT NULL DEFAULT 0,
 content_type TEXT NOT NULL DEFAULT '',
 width INTEGER NOT NULL DEFAULT 0,
 height INTEGER NOT NULL DEFAULT 0,
 -- Unix seconds: when the last programme or channel using it ends, and
 -- when it was last served.
 used_until INTEGER NOT NULL DEFAULT 0,
 last_used_at INTEGER NOT NULL DEFAULT 0
);
-- +goose Down
DROP TABLE live_tv_guide_art;
DROP TABLE live_tv_programmes;
DROP TABLE live_tv_guide_channels;
