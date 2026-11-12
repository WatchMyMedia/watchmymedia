-- +goose Up
-- Connections to Radarr, Sonarr and Seerr (PLAN-ARR.md §3). A table rather
-- than settings keys, since people run more than one of a kind (a 4K Radarr).
-- path_map and targets are JSON: how the app's paths map to the server's, and
-- where requests for each library go.
CREATE TABLE integrations (
    id            INTEGER PRIMARY KEY,
    kind          TEXT NOT NULL CHECK (kind IN ('radarr', 'sonarr', 'seerr')),
    name          TEXT NOT NULL,
    url           TEXT NOT NULL,
    api_key       TEXT NOT NULL,
    webhook_token TEXT NOT NULL,
    path_map      TEXT NOT NULL DEFAULT '[]',
    targets       TEXT NOT NULL DEFAULT '[]',
    created_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- +goose Down
DROP TABLE integrations;
