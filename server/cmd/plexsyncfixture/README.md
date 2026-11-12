# Fictional Plex import server

From `server/`, run `go run ./cmd/plexsyncfixture -port 28374`. Stop it with
Ctrl-C; its temporary database, private grant and fictional media rows are removed.
It binds loopback only and talks to stand-in Plex services in the same process.
There is no discovery and no real Plex or media-server connection.

Sign in as **Fixture Admin** or **Fixture Viewer**, both with the password
`fixture-password`. The fictional server is already connected and its people and
libraries mapped. Importing and recurring sync are off. Open Settings › Plex,
preview and import, inspect the results and imported history, and try Undo.
Disconnecting removes that prepared connection; restart the fixture to reset it.
Do not use Plex's real sign-in page for this fixture.

`GET /_fixture/status` identifies `plex-sync-fixture-v1` and returns the server
version/name, fictional account IDs, connection ID, movie/show/episode IDs and
current runs. Native opt-in probes must verify this identity before signing in.
The normal server binary does not expose this route.

The three movies are Harbor Lights, Copper Sky and Quiet Harbor. The owner has
watched Harbor Lights and the first Signal Keepers episode; the managed profile
has watched Copper Sky and episode two. Quiet Harbor is watched in Plex but was
explicitly marked unwatched locally, so it stays unwatched. Each person has one
separate imported movie-history event. The media files are metadata placeholders,
so this fixture tests browsing, watch state, history and recovery, not playback.

For the embedded web app, build it first with `make web` from the repository root.
Alternatively run Vite against this loopback port using a task-specific proxy.
`go test ./cmd/plexsyncfixture` checks the production administrative routes,
existing client replies, private events, separate history/statistics and guarded
undo on this same fixture. It uses disposable databases and HTTP servers.

For the native decoder and production Swift repository check, start a fresh
fixture, then run from `apple/Kit/`:

```sh
WMM_PLEX_FIXTURE_SERVER=http://127.0.0.1:28374 swift test --filter PlexImportFixtureTests
```

The opt-in test verifies the loopback endpoint, fixture manifest, server ID and
version before fixed fictional sign-ins. It imports through the real admin routes
and reads watched marks, per-person Play next and season rows through the shipped
generated client and production repositories, then checks guarded undo. It uses
in-memory local storage, never the app's Keychain or saved servers. Restart the
fixture before another run; the test refuses a fixture that already has imports.
