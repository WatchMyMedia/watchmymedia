-- +goose Up
-- The Seerr user each person requests as when Seerr carries requests out
-- (PLAN-ARR.md §7.3). NULL when not linked: that person can't request while
-- Seerr is the backend. Seerr's own user ID, not a foreign key.
ALTER TABLE users ADD COLUMN seerr_user_id INTEGER;

-- +goose Down
ALTER TABLE users DROP COLUMN seerr_user_id;
