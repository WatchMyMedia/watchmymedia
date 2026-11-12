# Browser subtitle fixture

From `server/`, run `go run ./cmd/subtitlefixture`. It generates a fictional
H264/AAC clip without subtitles, creates an isolated server and stand-in provider
on unused loopback ports, and prints their addresses and fixture identity. Stop
with Ctrl-C; all temporary media, accounts, database and downloaded subtitles are
removed. It never contacts a real provider, discovers a server, or reads app data.

For faster startup, `-media /path/to/a/fictional.mp4` copies that single H264/AAC
MP4 into the temporary directory; sidecars are not copied. Inputs with embedded
subtitles or other codecs are refused. `-port` and `-provider-port` can select
explicit unused ports. The normal server binary has no fixture control routes.

Sign in as **Fixture Viewer** or **Fixture Admin**, both with `fixture-password`.
Both accounts start with subtitles On, English preferred and automatic downloads
enabled. Files 1–3 are Cedar Lanterns, Riverglass and Hollow Shore. They have no
local subtitles. Search begins paused so a browser can make an explicit Off choice
before an exact-file result is returned.

`GET /_fixture/status` on either address identifies
`fixture: subtitle-browser-fixture-v1`, `version: subtitle-browser-fixture` and the
same `serverId` as `/api/server`. It lists file IDs and counters:

- `searches`: requests received by the fictional provider;
- `searchPaused`: whether provider search is held at its barrier;
- `downloadAttempts`: quota-spending requests attempted;
- `downloads`: successful fictional downloads only.
- `automaticAttempts`, `automaticCancels`, `automaticActive`: actual production
  automatic-subtitle requests (distinct from the picker's manual search).

Control the provider address with these POST routes:

| Route | Effect |
|---|---|
| `/_fixture/search/release` (or `resume`) | Release the pending search and let new searches complete |
| `/_fixture/search/pause` | Hold subsequent uncached searches |
| `/_fixture/provider/success` | Downloads return the exact fictional SRT |
| `/_fixture/provider/quota` | Downloads return the provider's quota-exhausted response |
| `/_fixture/provider/error` | Downloads return a service-unavailable response |

For browser acceptance, point a task-owned Vite proxy at the printed server:

1. Play Cedar Lanterns, wait for `searches > 0` while search is paused, choose
   explicit Off directly, then release search. Check `downloads == 0`, subtitles
   remain Off and the video continues.
2. Play Riverglass with account defaults. Its independent search should download
   one exact match and select it after playback starts.
3. Set provider mode to quota (or error), then play Hollow Shore. Video should
   continue with a useful note; no successful download is added.

Use a distinct file for each case, or restart the fixture, because the production
subtitle service intentionally caches searches and deduplicates downloads.
