-- +goose Up
-- Automatic quality's record of a play (PLAN-AUTO-QUALITY.md) as JSON: the
-- step it ended at and the steps down it took, or for a fixed quality the
-- steps down Automatic would have taken. Empty when there is nothing to say.
ALTER TABLE plays ADD COLUMN auto TEXT NOT NULL DEFAULT '';

-- +goose Down
ALTER TABLE plays DROP COLUMN auto;
