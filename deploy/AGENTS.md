# deploy/AGENTS.md

Packaging, CI support and the test library. Read the root
[AGENTS.md](../AGENTS.md) first; the README's Install section is the human
instructions. Where `AGENTS.private.md` sits beside this file, read it too:
it covers releases, the downloads Worker and the CI runners.

## Layout

| Path | What |
|---|---|
| `Dockerfile`, `entrypoint.sh`, `docker-compose*.yml`, `Caddyfile`, `swag/` | The image (built from source; see `../.dockerignore`) and example setups behind proxies. The image's ffmpeg is jellyfin-ffmpeg from Jellyfin's apt repository, which carries the Intel, AMD and Vulkan user-mode drivers; the entrypoint runs the server as `PUID`/`PGID` in the groups that own `/dev/dri` |
| `unraid/` | The Unraid Docker template (`watchmymedia.xml`), the templates repository's profile, their test, and `mirror.sh`, which assembles the public `WatchMyMedia/unraid-templates` repository Community Applications reads; [its README](unraid/README.md) has the hand install, the test list for an Unraid server and the submission checklist |
| `ci/docker-smoke.sh` | Checks a built image without a GPU: ffmpeg's hardware encoders and filters, the entrypoint's device groups, `--group-add` and `UMASK`, and that the server comes up healthy (`make docker-smoke`; CI's `docker` job runs it) |
| `config.example.yaml` | Every server option, commented; update it with any new option |
| `release/` | `tag.sh` (`make release-tag`) and `notes.sh` (a version's CHANGELOG section), with `version_test.py` for `apple/scripts/version.sh` |
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
python3 -I -m unittest discover -s deploy/release -p '*_test.py'
python3 -I -m unittest discover -s deploy/ci -p '*_test.py'
python3 -I -m unittest discover -s deploy/fixture -p '*_test.py'
python3 -I -m unittest discover -s deploy/unraid -p '*_test.py'   # the Unraid template
make docker      # when the Dockerfile, entrypoint or .dockerignore change
make docker-smoke  # then this, against the image make docker built
```

## How a release goes

1. A `## X.Y.Z` section in `CHANGELOG.md`, merged to `main` in its own PR,
   written for the people who run WatchMyMedia. A pre-release (`vX.Y.Z-rc.N`)
   uses the section of the release it leads to. Use a paragraph and top-level
   `- ` items only: `release/notes.sh` joins every indented line onto the
   line before it, because the site, Settings and Sparkle read each `- `
   line as one item, so a nested list comes out as a single item. Run
   `deploy/release/notes.sh vX.Y.Z` to see the section as they will.
2. `make release-tag VERSION=vX.Y.Z` tags `origin/main` (not this checkout)
   after checking the version shape (`apple/scripts/version.sh`), that it is
   newer than every tag, and that the notes exist. **Only on the user's
   explicit go-ahead.**
3. `release.yml`: `check` creates the GitHub release; `binaries`, `macos`
   (signed, notarised DMG), `image` (GHCR, two architectures), `ios`
   (TestFlight), `android` (APK and bundle) and `windows` (the installer,
   signed with Artifact Signing) add to it. It runs on GitHub's runners with
   no caches; the jobs that read a secret run in the `release` or
   `windows-signing` environment, which admit only `v*` tags. Keep it that
   way: `ci/security_test.py` checks it.
4. The release is not offered to anyone until the maintainers publish its
   files to downloads.watchmy.media, which happens outside this repository.

Changes to `release.yml` are only fully exercised by a tag. Say what could
not be checked, and leave tagging an rc to the user.

Version numbers come from the tag everywhere: `apple/scripts/version.sh` for
Apple and the Mac app, `versionCodeOf` in `android/app/build.gradle.kts` for
Android, using the same build number so pre-releases sort before their
release. The iPhone, iPad and Apple TV apps' version stays on 1.x: `v0.M.P`
is 1.M.P, `v1.0.0` is 1.6.0, and from `v1.3.0` `v1.M.P` is 1.(M + 4).P.
`version.sh` refuses a `v2` tag until a line for it is added.

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
