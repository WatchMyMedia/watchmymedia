# deploy/AGENTS.md

Packaging, CI support and the test library. Read the root
[AGENTS.md](../AGENTS.md) first; the README's Install section is the human
instructions. Where `AGENTS.private.md` sits beside this file, read it too:
it covers releases, the downloads Worker and the CI runners.

## Layout

| Path | What |
|---|---|
| `Dockerfile`, `entrypoint.sh`, `docker-compose*.yml`, `Caddyfile`, `swag/` | The image (built from source; see `../.dockerignore`) and example setups behind proxies. The image's ffmpeg is jellyfin-ffmpeg from Jellyfin's apt repository, which carries the Intel, AMD and Vulkan user-mode drivers; the entrypoint runs the server as `PUID`/`PGID` in the groups that own `/dev/dri` |
| `unraid/` | The Unraid Docker template (`watchmymedia.xml`), the templates repository's profile, their test, and `mirror.sh`, which assembles the public `taimoorq/unraid-templates` repository Community Applications reads; [its README](unraid/README.md) has the hand install, the test list for an Unraid server and the submission checklist |
| `ci/docker-smoke.sh` | Checks a built image without a GPU: ffmpeg's hardware encoders and filters, the entrypoint's device groups, `--group-add` and `UMASK`, and that the server comes up healthy (`make docker-smoke`; CI's `docker` job runs it) |
| `config.example.yaml` | Every server option, commented; update it with any new option |
| `fixture/fixture.sh` | The synthetic test library and its test accounts |
| `fixture/golden-replies.sh` | Captures a server's replies on a copy of the fixture for the Android contract tests (`android/core/data/src/test/resources/golden/`) |
| `fixture/streaming.py`, `fixture/streaming.md` | Owned fictional file/Live TV concurrency and bandwidth-change harness (`make streaming-check`); model timings and decoded sample frames are separate from native-device evidence |
| `fixture/adaptive_caf.py` | Visible local CAF qualification page for A2: drives the development receiver through CAF's public player API against a verified loopback fixture, shapes the aggregate rate, corrupts only an offered VOD family's encoded samples and writes token-free evidence ([fixture/streaming.md](fixture/streaming.md)) |
| `fixture/link_proxy.py` | Loopback-only rate-limited TCP proxy for native client link-change checks against a verified disposable fixture; accepts rate changes on stdin |
| `fixture/ladder_ios.py`, `fixture/live_ladder_ios.py`, `fixture/ladder_ios_player.swift`, `fixture/ladder_ios_info.plist` | Optional visible iOS simulator A0 VOD/A1 Live TV ladder checks; builds an ad hoc app, creates and deletes its own simulator and fictional fixture, and writes token-free bitrate/progress evidence |
| `samsung/` | Isolated synthetic QN90D fixture and prepared package helper; current status and commands in [the prototype README](../web/tizen/README.md) |
| `arr/compose.yaml` | A throwaway Radarr and Sonarr, pinned, for testing the integrations |
| `arr/seerr.compose.yaml`, `arr/seerr-setup.sh` | A throwaway Seerr and the Jellyfin it needs, pinned, and the script that finishes their setup and prints Seerr's API key; `go test ./internal/integrations/seerr -run Live` runs against it |
| `windows/fetch-winsparkle.sh` | The pinned official Windows updater DLL and licence files |
| `macos/fetch-ffmpeg.sh` | The pinned jellyfin-ffmpeg builds the releases and CI use, with checksums |

## Checks

```bash
python3 -I -m unittest discover -s deploy/ci -p '*_test.py'
python3 -I -m unittest discover -s deploy/fixture -p '*_test.py'
python3 -I -m unittest discover -s deploy/unraid -p '*_test.py'   # the Unraid template
make docker      # when the Dockerfile, entrypoint or .dockerignore change
make docker-smoke  # then this, against the image make docker built
```

## Rules

- **ffmpeg pin.** Bump `VERSION` and every checksum in `fetch-ffmpeg.sh`
  together, from downloads you checked, and `JELLYFIN_FFMPEG_VERSION` in the
  `Dockerfile` to the same release (the apt repository's version is the
  release plus `-trixie`; check it exists for amd64 and arm64 at
  `https://repo.jellyfin.org/debian/dists/trixie/main/`). The server tests,
  the Windows job, every release and the image use it. The Dockerfile also
  pins the fingerprint of Jellyfin's signing key; a changed key fails the
  build on purpose.
- **Fixture.** Synthetic media only, rendered by ffmpeg, with made-up titles:
  no real files and nothing under copyright. Its accounts are for fixture
  servers only.
