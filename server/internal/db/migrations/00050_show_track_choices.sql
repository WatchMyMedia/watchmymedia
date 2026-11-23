-- +goose Up
-- The sound and subtitles a person picked while watching a show, so its
-- next episodes start the same way. Keyed by the show's TMDB id, as
-- follows are: a show in two libraries is one choice, and the choice
-- outlives a rematch or a library made again.
--
-- audio_language is BCP 47; NULL leaves the sound to the person's audio
-- languages. subtitle_language is BCP 47; with subtitle_off = 1 the show
-- plays without subtitles; both unset leave subtitles to the person's
-- subtitle settings.
CREATE TABLE show_track_choices (
    user_id           INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tmdb_id           INTEGER NOT NULL,
    audio_language    TEXT,
    subtitle_language TEXT,
    subtitle_off      INTEGER NOT NULL DEFAULT 0,
    updated_at        TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    PRIMARY KEY (user_id, tmdb_id)
) WITHOUT ROWID;

-- +goose Down
DROP TABLE show_track_choices;
