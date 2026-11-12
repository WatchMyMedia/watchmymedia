-- A failed scan (the folder unmounted or unreadable, as after a PUID change
-- in Docker) was only an event, so once its toast was gone the library just
-- showed no files. Each library now keeps why its last scan failed, and
-- when, until a full scan succeeds (PLAN-ONBOARDING.md §4).

-- +goose Up
ALTER TABLE libraries ADD COLUMN last_scan_error TEXT;
ALTER TABLE libraries ADD COLUMN last_scan_error_at TEXT;

-- +goose Down
ALTER TABLE libraries DROP COLUMN last_scan_error_at;
ALTER TABLE libraries DROP COLUMN last_scan_error;
