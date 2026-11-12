-- +goose Up
-- Keep numeric account IDs stable without rebuilding users (and cascading
-- deletes through its foreign keys). Include retained statistics from users
-- deleted before this migration. This singleton contains no personal data.
CREATE TABLE account_sequence (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    last_id INTEGER NOT NULL
);
INSERT INTO account_sequence VALUES (1, max(
    coalesce((SELECT max(id) FROM users), 0),
    coalesce((SELECT max(user_id) FROM activity_daily), 0),
    coalesce((SELECT max(user_id) FROM activity_monthly), 0)
));

-- +goose StatementBegin
CREATE TRIGGER users_identity AFTER INSERT ON users BEGIN
    SELECT CASE WHEN NEW.id <= (SELECT last_id FROM account_sequence WHERE singleton = 1)
        THEN RAISE(ABORT, 'account identity cannot be reused') END;
    UPDATE account_sequence SET last_id = NEW.id WHERE singleton = 1;
END;
-- +goose StatementEnd

-- Account deletion promises to delete watch history, including rollups that
-- outlive detailed history. Do this before ON DELETE SET NULL erases ownership.
-- +goose StatementBegin
CREATE TRIGGER users_delete_history BEFORE DELETE ON users BEGIN
    DELETE FROM plays WHERE user_id = OLD.id;
    DELETE FROM activity_daily WHERE user_id = OLD.id;
    DELETE FROM activity_monthly WHERE user_id = OLD.id;
END;
-- +goose StatementEnd

-- +goose Down
DROP TRIGGER users_delete_history;
DROP TRIGGER users_identity;
DROP TABLE account_sequence;
