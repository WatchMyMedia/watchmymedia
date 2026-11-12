# Windows desktop app

The notification-area app supervises the same Go server used on other platforms.
It is under development and not yet a signed public Windows release.

Build on the development Mac or Mac Studio:

```sh
make windows-test
make windows-app VERSION=development
```

`build/windows-app/` contains `WatchMyMedia.exe`, `watchmymedia-server.exe`,
FFmpeg, FFprobe and its version/licence files. The desktop app requires **Windows 11
25H2 or later on a native x64 PC** (build 26200+, Intel/AMD). Windows 10, older Windows 11,
Windows Server and ARM64 emulation are outside the desktop policy. The portable server
keeps its separate platform support. Copy that folder to a supported PC and open
`WatchMyMedia.exe`. The tray icon opens the browser with a left
click; right click or keyboard activation opens the native menu. First launch
opens setup once. All library and transcoding settings stay in the web app.

Data and logs live in `%LOCALAPPDATA%\WatchMyMedia`, with `Logs\server.log` and
one rotated `.1` file. Start at sign-in is optional, stored in this user's Run
registry key. Quit closes the server's stdin, waits up to five seconds and then
kills its job if necessary. A tray crash closes the job and also ends the server
and its descendants. The PC stays awake while the server reports playback.

One instance runs per Windows user. A second launch opens its browser. If another
user or program owns the port, choose **Use another port**; the app saves its new
port in `tray-port`. It does not attach to the other server. **Check for updates**
currently opens the server's Settings page; signed automatic installer updates
come in W4. Do not claim that this development build has automatic updates.

Developer/test arguments:

- `-data-dir <folder>` and `-port <number>` select isolated data and a port.
- `-no-browser` suppresses automatic setup opening and error dialogs.
- `-quit` asks the current user's running tray to stop, including for upgrades.
- `-check-platform` checks native OS/product/architecture and exits with 0 or
  1633 (unsupported), without a dialog or starting the server.
- `-platform-report <file>` also writes those checks as local JSON, with no user
  or library data. It exits after the check.

On hosted Windows, `windows/smoke.ps1` uses a fresh synthetic server with discovery
off. It checks first run, one instance, graceful quit and force-kill cleanup.
The supervisor tests also exercise crash/restart, a stuck child, port conflicts,
setup opening once, invalid local replies and log rotation.

Still required before public release: real Windows menu/keyboard/DPI/theme and
sign-in checks, GPU/network-share tests, different-account UAC acceptance, signing
and verified installer updates. See [the Windows plan](../PLAN-WINDOWS.md).

## Collect hardware test evidence

With PowerShell 7 (`pwsh`) on Windows, point the collector at a complete normal app
folder from a build or installation. Run when other transcodes have stopped; test
concurrent playback separately. Use a new report directory for each run:

```powershell
pwsh -NoProfile -File windows/hardware-check.ps1 -AppDir build/windows-app -ReportDir build/hardware-local
# One explicit CUDA index, including zero:
pwsh -NoProfile -File windows/hardware-check.ps1 -AppDir build/windows-app -ReportDir build/hardware-cuda-0 -Backend nvenc -Device 0
```

The default run probes software, the unnamed NVENC/Quick Sync/AMF defaults, then every
matching named native adapter. `-Backend software`, `nvenc`, `qsv` or `amf` narrows it;
`-Device` requires an explicit hardware backend. Defaults are not assigned a guessed
GPU identity, and CUDA and DirectX indices are never translated. The run has a
15-minute total budget (`-MaxMinutes 1` through `60`), with at most 150 seconds per
probe. Timeouts stop that command and its descendants, retaining partial results.

`report.json` records binary hashes checked before/after the run, the native desktop
policy report, driver versions, the native inventory, and each probe's diagnostics and
capabilities. [Local CIM display-driver metadata](https://learn.microsoft.com/en-us/windows/win32/cimwin32prov/win32-videocontroller)
has no CUDA/DXGI index mapping and is recorded separately. An unavailable metadata query
is reported explicitly. Per-probe JSON/stderr files remain beside the aggregate report.
These are private diagnostic files, including executable paths; the collector uploads
nothing and does not read libraries/configuration, start the server or change Settings.

Exit 0 means collection completed, including any `software-fallback` outcomes. Hardware
success requires the requested backend, encoder, explicit device (when given) and a
successful encode diagnostic. Software rows describe the CLI's selected encoder and
availability; the CLI does not run a libx264 encode check, so actual software playback
is still a separate acceptance check. Missing/invalid results, changed binaries/inventory and
timeouts make collection incomplete and return 1. `acceptanceComplete` always stays
false: real desktop operation, vendor/hybrid coverage, HDR/subtitle picture checks,
concurrent sessions, network shares/UAC and signed updates still require acceptance.
Windows Server can collect portable-server evidence but stays outside desktop acceptance.

Run the dependency-free helper checks with
`pwsh -NoProfile -File windows/hardware-check-test.ps1` on the Mac or Windows.
Hosted Windows CI also runs the actual packaged collector in software mode and retains
its private `windows-hardware-check` artifact for seven days.

## Development installer

Install NSIS 3 (`brew install makensis` on the Mac), then run
`make windows-installer VERSION=development`. It writes
`build/windows-installer/WatchMyMedia-development-x64-setup-unsigned.exe`, without deleting
kept files in `dist/`. Local installers are unsigned; signing runs only in CI
(see Signing below).

The installer invokes the packaged app's native platform check before changing
the installation, firewall, registry or user data. An unsupported system receives
the Windows 11 25H2+ x64 requirement; silent installation returns 1633 without
showing a dialog. Uninstall and quit helpers remain available for cleanup.

The installer runs elevated to write Program Files, shortcuts and the server's
private/domain network firewall rule. Its `-desktop-action` helper obtains the
interactive Explorer user's token and environment before launching the normal app
or changing that user's sign-in setting. It refuses to launch an elevated server
through an elevated desktop shell. No interactive shell or failed identity
transition produces an error and leaves launch to the Start menu; it does not
fall back to running the server as the installing administrator.

Upgrades ask the running app to quit and wait for its instance handle to disappear
before replacing files. Quit the app in other signed-in sessions too; a locked
executable stops installation rather than deleting another user's processes.
The silent installer accepts `/S` and an optional `/START`; a silent update keeps
the previous sign-in choice. Interactive installation offers the sign-in choice
and an Open WatchMyMedia finish checkbox. Uninstall retains data by default;
interactive uninstall can explicitly delete the desktop user's default data.
Data belonging to other Windows users is never removed.

`windows/installer-smoke.ps1` is for disposable hosted Windows only. It checks
installation, firewall scope, an installed server, upgrade while running and
uninstall with retained synthetic data. Different-account UAC credentials and
multiple signed-in users still need real Windows acceptance testing.

## Signed updates

The build bundles WinSparkle 0.9.4 from its official archive, pinned by SHA-256.
It loads the DLL by its full installed path with dependency lookup restricted to
that directory and System32. The integration is inactive unless a release build
sets both `WINDOWS_UPDATE_FEED_URL` (HTTPS) and `SPARKLE_PUBLIC_KEY` (base64 Ed25519
public key). These are build inputs, not runtime environment overrides.

Configured releases use the tray's **Check for updates** action to open WinSparkle;
automatic background checks are disabled. WinSparkle verifies the installer's
EdDSA signature. Version comparisons use the same numeric release order as the
Mac/Android builds, including prereleases. The shutdown callback asks the tray to
close its server before the installer runs. Development builds keep opening the
web Settings update check instead. The tray starts its server with
`WATCHMYMEDIA_INSTALL=windows-app`, so that check (Settings › General) links to the
installer, never the portable archive, and offers only releases that have one.

Release builds set both: the feed is `https://downloads.watchmy.media/windows/appcast.xml`
and the key is the Mac app's Sparkle key. `deploy/release/publish.sh` writes that feed
from `releases.json` (`manifest.py windows-appcast`): every stable, unyanked release
whose installer has an EdDSA signature, which `publish.sh` makes with Sparkle's
`sign_update` and the `SPARKLE_PRIVATE_KEY` secret. Each item carries the build number
as `sparkle:version`, `sparkle:os="windows-x64"` and Windows 11 25H2
(`10.0.26200`) as the minimum. WinSparkle has no beta channel, so pre-release
installers can be downloaded but are never offered as updates. Yanking a release in
the Downloads workflow writes the feed again without it.

The feed and the Windows downloads go out only when the `PUBLISH_WINDOWS_APP`
repository variable is `true` (Releasing below), as it has been since 2026-11-06.
1.0.0 was the first release published with the feed; update acceptance between two
signed versions on a real PC follows the launch. Never put an unsigned installer in
the feed: only the release job's verified installer reaches the publisher.

## Signing

Builds are signed with Azure Artifact Signing as Eldwick Software LLC, only in
jobs that use the `windows-signing` environment, which admits only `v*` tags
([SIGNING.md](SIGNING.md) has the Azure and GitHub setup):

- release.yml's `windows` job signs every release (Releasing below);
- CI's `windows-signing` job signs a development build on request: run the CI
  workflow by hand on a `v*` tag with **Also sign and verify a Windows installer**
  (`gh workflow run ci.yml --ref vX.Y.Z -f sign_windows=true`). v0.5.9 and older
  tags predate the job.

Both sign inside out:

1. `setup-signing.ps1` fetches the pinned SignTool and Artifact Signing client
   (NuGet packages checked by SHA-256) and writes their metadata.
2. `sign.ps1` signs the tray app, server, FFmpeg, FFprobe and WinSparkle DLL,
   with a timestamp, and checks each signature. It refuses a file that already
   has a signature.
3. `installer.sh --sign` has NSIS sign the uninstaller before embedding it
   (`!uninstfinalize` in `installer.nsi`), keeps that signed copy as
   `build/windows-installer/uninstall.exe`, then signs the installer.
4. `verify-installer.ps1` checks all seven signatures and only then renames the
   `-unsigned.exe` staging file to the public `-setup.exe` name. The staging
   name keeps an unchecked installer out of the release manifest even when it
   is signed; filename recognition by the manifest is not signature validation.
5. In CI only, `signing-smoke.ps1` checks that the gate refuses the installer
   with one byte changed, and a look-alike signature with the same name and EKU
   under another root.

Artifact Signing issues a new three-day certificate every day, so
`verify-installer.ps1` pins no thumbprint. `signing-lib.ps1` accepts a signature
only when Windows reports it valid, the signer's CN and O are the publisher in
`signing-identity.json`, the certificate carries code signing and that file's
identity-validation EKU, its chain ends at the pinned Microsoft Identity
Verification Root Certificate Authority 2020, and it is timestamped (without a
timestamp it would stop verifying when the certificate expires).
`signing-test.ps1` tests the identity rules on the Mac or Windows.

The CI job uploads its promoted installer as the private `windows-signed-installer`
artifact for seven days, for testing on a real Windows 11 PC. It is not a release
or a download.

## Releasing

Each `v*` tag's `windows` job in `release.yml` builds the app with the release
version, the feed URL and the update key, signs and verifies it as above, and attaches
`WatchMyMedia-X.Y.Z-x64-setup.exe` to the GitHub release. The maintainers then publish
the release to downloads.watchmy.media, outside this repository: the installer goes
under `windows/`, into `releases.json`'s `windows-app-amd64` asset, the update feed and
`/windows/latest`, as it has for every release since the Windows launch on 2026-11-06.
Before a release has a Windows installer, `/windows/latest` redirects to the download
page and the feed is 404. Publishing a version again replaces its files and its entry.

Successful Windows CI keeps the unsigned installer as the
`windows-development-installer` artifact for seven days, for manual Windows testing.
It includes the embedded web app from the same CI run. The installer smoke checks
its actual `/setup` page, not just the local status endpoint. It is not a GitHub
release or a public R2 download.

## Hosted Windows Server validation

The normal desktop build rejects Windows Server, including GitHub's hosted runner.
CI proves this with `platform-smoke.ps1` before running lifecycle tests. It then
uses `windows/build.sh --ci-server-host` and `windows/installer.sh --ci-server-host`
to make separate development-only outputs under `build/windows-ci-app/` and
`build/windows-ci-installer/`. These flags require disposable hosted Windows CI.
The exception also checks that environment at runtime, and admits only native
x64 Windows Server; it never relaxes the older-client or ARM64 checks.

These CI outputs carry a marker and distinct app/installer PE metadata. `verify-installer.ps1`
rejects them before signature validation, including when the marker is removed.
It also rejects a CI installer supplied alongside a normal app directory.
They are not the normal manual-testing installer. The usual private
`windows-development-installer` artifact retains the Windows 11 requirement.
`platform-smoke.ps1 -CIHost` verifies exception boundaries and promotion rejection;
`smoke.ps1 -CIHost` and `installer-smoke.ps1 -CIHost` exercise the separate CI build.
Platform reports are private CI artifacts retained for seven days. This is software
coverage on Windows Server; real Windows 11 desktop acceptance is still required.
