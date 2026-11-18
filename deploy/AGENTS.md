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
| `ci/baseline_profile.py` | Fails when the Android app's committed baseline and startup profiles have many rules for app classes that are gone (CI's `android` job runs it; `baseline_profile_test.py` tests it) |
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
| `linux/nfpm.yaml`, `linux/build.py`, `linux/fetch-nfpm.sh`, `linux/packages.mk` | The Debian, Ubuntu and Fedora packages (`make linux-packages`): one nfpm file for the `.deb` and the `.rpm`, amd64 and arm64; `build.py` writes what differs per package (the `/usr/bin` wrapper's `deb` or `rpm`, the commented-out `config.yaml`, the repository's key) and runs the nfpm that `fetch-nfpm.sh` pins by version and checksum; `packages.mk` holds the make targets, which the root `Makefile` includes |
| `linux/repository-key.asc` | The public half of the OpenPGP key that signs the `.rpm` files and the apt and dnf repositories. Every package carries it. Its secret parts are the user's and are never in the repository |
| `linux/watchmymedia.service`, `linux/watchmymedia.sh`, `linux/sysusers.conf`, `linux/scripts/`, `linux/firewalld.xml`, `linux/ufw.profile`, `linux/watchmymedia.sources`, `linux/watchmymedia.repo` | What the packages install: the systemd service, the wrapper that sets the folders, the `watchmymedia` user, the install, update and remove scripts (one set for dpkg and rpm), the firewall definitions, and the apt and dnf repository entries |
| `linux/package_test.py` | Builds packages from stand-in files and reads them without dpkg or rpm: paths, modes, config files, scripts, the repository entry, versions. With `WMM_LINUX_SMOKE` set it also reads the packages `make linux-smoke-packages` built |
| `linux/smoke.sh`, `linux/smoke-check.sh`, `linux/throwaway-key.sh` | `make linux-packages-smoke`: installs, updates, removes and purges the packages in a container of one release that runs systemd; the key script makes the throwaway key the test packages are signed with |

## Checks

```bash
python3 -I -m unittest discover -s deploy/release -p '*_test.py'
python3 -I -m unittest discover -s deploy/ci -p '*_test.py'
python3 -I -m unittest discover -s deploy/fixture -p '*_test.py'
python3 -I -m unittest discover -s deploy/unraid -p '*_test.py'   # the Unraid template
python3 -I -m unittest discover -s deploy/linux -p '*_test.py'    # the Linux packages, from stand-in files
make docker      # when the Dockerfile, entrypoint or .dockerignore change
make docker-smoke  # then this, against the image make docker built
make linux-smoke-packages  # when deploy/linux, config.example.yaml or how the server starts change (needs gpg)
make linux-packages-smoke IMAGE=debian:13  # then this for each release: also debian:12, ubuntu:24.04, ubuntu:26.04, fedora:44, fedora:45
```

The Linux package test reads a `.deb`'s files with Python 3.14 or the `zstd`
command; with neither it skips those checks and says so. The smoke test
needs Docker and runs this machine's architecture; CI's
`linux-packages-install` job runs it on amd64 and arm64.

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
Apple and the Mac app, `versionCodeOf` in `android/build-logic` (`VersionCode.kt`) for
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
- **Linux packages.** Built and tested in CI, and nothing more yet: they are
  not on a release, signed with a real key or published. What to keep:
  - One `linux/nfpm.yaml` for both formats and both architectures. A file for one
    format takes `packager: deb` or `rpm`. nfpm 2.49 leaves a
    `type: license` file out of a `.deb`, so the licences are plain files.
  - The four scripts run under dpkg and rpm, which pass different words:
    `install`, `upgrade`, `configure`, `remove` and `purge` from dpkg, and a
    count from rpm (1 install, 2 update; 0 removal, 1 update). A new branch
    needs both.
  - dpkg runs the scripts with dash, which stops a script at a failed
    redirection on a builtin whatever follows it: `: > file || true` ends
    the install where the file cannot be written. Use a command
    (`touch file 2>/dev/null || true`); the package test refuses the other.
    macOS's `sh` does not behave this way, so only Linux shows it.
  - Nothing starts where systemd is not running, the scripts never open a
    firewall or change SELinux, and the package owns neither
    `/var/lib/watchmymedia` nor `/var/cache/watchmymedia`, so removing it
    keeps the library. `linux/package_test.py` and the smoke test check
    these.
  - The account is written twice, in `linux/sysusers.conf` and in
    `linux/scripts/preinstall.sh`, which runs before the package unpacks.
    Change both.
  - The service closes `/var/lib/watchmymedia` and
    `/var/cache/watchmymedia` to other local users (mode 750): the database
    holds password hashes and the cache what people are watching. It sets
    no `UMask`, on purpose: what the server writes into library folders
    (recordings, subtitles, trailers) has to stay readable by whoever
    shares those folders. systemd applies the mode each time the service
    starts, so an update closes folders an earlier package left open; the
    smoke test opens them before the update to see that.
  - Both packages recommend `acl`, because the web's folder message gives
    a `setfacl` command and `setfacl` is not on every install. The smoke
    test runs that command.
  - The supported releases are named in `ci.yml`'s matrix,
    `linux/smoke.sh` and the `Makefile`; add or drop one in all three
    (`ci/security_test.py` checks).
  - The nfpm pin: bump `VERSION` and every checksum in
    `linux/fetch-nfpm.sh` together, from the digests GitHub lists for the release's files.
  - CI's two Linux package jobs are thirteen jobs, so the path list that
    starts them (`linux-packages` in `ci.yml`'s `changes` job) is
    deliberate, and `ci/security_test.py` holds it to exactly this:
    `deploy/linux/**`, the files packed from elsewhere
    (`macos/fetch-ffmpeg.sh`, `macos/FFMPEG-LICENSE.md`,
    `config.example.yaml`, the root `LICENSE`), `server/cmd/server/**` and
    `server/internal/config/**` (how the server starts and where it looks
    for its folders, config and ffmpeg, which the wrapper and the service
    rely on), and `ci.yml`. Don't add the root `Makefile` or the rest of
    `server/` or `web/`: the `server`, `web` and `docker` jobs cover what
    the packages hold of those. The Linux make targets are in
    `linux/packages.mk` for this reason; keep new ones there.
  - `linux/repository-key.asc` is the repositories' real public key, and
    the default `LINUX_REPOSITORY_KEY`, so every package carries it and its
    repository entry (`LINUX_REPOSITORY_KEY=` builds without either).
    `linux/package_test.py` holds the file to the key's fingerprints: a
    change to it changes what every install trusts, and is the user's to
    make. Only the public half is ever committed.
  - The key has a primary key that only certifies and two signing subkeys:
    one for the `.rpm` files, one for the repositories' lists. A signing
    key is always given with its subkey's id (`LINUX_SIGNING_KEY_ID`):
    without one nfpm signs with the last signing subkey, which is the
    lists'. The `.rpm` subkey's id is `BD64FACE28C2EF71`. CI and the smoke
    test sign with a throwaway key of the same shape
    (`linux/throwaway-key.sh`), whose secret part is never uploaded; the
    real one is only ever in a release.
  - The key file holds one key and `build.py` insists on that. dnf never
    refreshes a key it already holds for a repository, so replacing the
    lists' signer one day means adding a second key to the file, and
    `build.py` then has to take more than one.
