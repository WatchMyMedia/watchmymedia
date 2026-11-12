-- +goose Up
-- Sessions say what they belong to, so people can see and revoke their
-- sign-ins. kind is browser (cookie) or device (a native app holding a
-- bearer token, with a longer lifetime). public_id names a session in the
-- API without revealing its token hash.
ALTER TABLE sessions ADD COLUMN public_id TEXT NOT NULL DEFAULT '';
ALTER TABLE sessions ADD COLUMN kind TEXT NOT NULL DEFAULT 'browser';
ALTER TABLE sessions ADD COLUMN device_key TEXT NOT NULL DEFAULT '';
ALTER TABLE sessions ADD COLUMN name TEXT NOT NULL DEFAULT '';
ALTER TABLE sessions ADD COLUMN product TEXT NOT NULL DEFAULT '';
ALTER TABLE sessions ADD COLUMN platform TEXT NOT NULL DEFAULT '';
ALTER TABLE sessions ADD COLUMN version TEXT NOT NULL DEFAULT '';
ALTER TABLE sessions ADD COLUMN last_ip TEXT NOT NULL DEFAULT '';
UPDATE sessions SET public_id = lower(hex(randomblob(8)));
CREATE UNIQUE INDEX sessions_public_id_idx ON sessions (public_id);

-- +goose Down
DROP INDEX sessions_public_id_idx;
ALTER TABLE sessions DROP COLUMN last_ip;
ALTER TABLE sessions DROP COLUMN version;
ALTER TABLE sessions DROP COLUMN platform;
ALTER TABLE sessions DROP COLUMN product;
ALTER TABLE sessions DROP COLUMN name;
ALTER TABLE sessions DROP COLUMN device_key;
ALTER TABLE sessions DROP COLUMN kind;
ALTER TABLE sessions DROP COLUMN public_id;
