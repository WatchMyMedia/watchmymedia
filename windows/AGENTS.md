# Windows desktop app

Read the root [AGENTS.md](../AGENTS.md) and [PLAN-WINDOWS.md](../PLAN-WINDOWS.md).
This is a separate, pure-Go module. The server remains in `server/`.

- `cmd/tray/`: Windows GUI entry point; `internal/winui/`: native message loop,
  menu, startup registry value, power request and per-user launch/quit events.
- `internal/server/`: supervisor, status polling, bounded logs and child/job lifecycle.
  Its process tests run on the Mac too; Windows runs the suspended/job path.
- `build.sh` and `resources.py`: package the tray, server and pinned FFmpeg under
  `build/windows-app/`. Generated resource objects are temporary, never committed.
- `smoke.ps1`: hosted-Windows test with a fresh data directory and its own port.
- `hardware-check.ps1`: opt-in local synthetic evidence collector for a complete app
  folder; helpers in `hardware-check-lib.ps1`, cross-platform PowerShell 7 tests in
  `hardware-check-test.ps1`. Collection success never means release acceptance.
- `internal/platform/`: the Windows 11 25H2+ native-x64 desktop gate shared by
  normal tray startup and the installer's headless preflight. The portable server
  does not import it. Quit/uninstall cleanup must stay available.

Commands from the repository root: `make windows-app`, `make windows-installer`,
`make windows-test`.
On Windows: `cd windows && go vet ./... && go test ./...`; after fetching the
pinned FFmpeg, `bash windows/build.sh --smoke`. Smoke scripts require disposable
hosted Windows CI, with `-CIHost` for its separate Server validation builds.
The optional `--smoke` flag omits the web build for process-only development.
CI downloads the web artifact from the same run and builds the full app;
`make windows-app` always builds and embeds the web app.

Keep the message loop on one OS thread and move blocking process/HTTP operations
out of callbacks. Never inherit elevation for the normal tray/server. Suspend the
server before assigning its job; fail closed if job setup fails. Closing stdin
is graceful shutdown; after five seconds terminate the job. Never poll an occupied
port belonging to another user's server. Keep Windows CI GitHub-hosted.

The approved repository boards are in `design/mockups/windows/`; they are not on
a design canvas. Use fictional data in screenshots and fixture tests.

`installer.nsi` and `installer.sh` build the development NSIS installer under
`build/windows-installer/`. `installer-smoke.ps1` is for disposable hosted Windows.
Use the desktop user's token and environment for post-elevation launch/configuration;
never fall back to the installing administrator. Only explicit interactive opt-in
may delete the desktop user's data; silent uninstall keeps it.

Signing ([SIGNING.md](SIGNING.md), README "Signing") runs only in jobs with the
`windows-signing` environment, from `v*` tags: release.yml's `windows` job and
CI's `windows-signing` job on a manual run that asks for it. Both sign as Eldwick
Software LLC, so a manual signing run needs the user's go-ahead like a release.
`setup-signing.ps1` pins SignTool and the Artifact Signing client by version and
SHA-256 (change both together); `sign.ps1` signs and checks; `installer.sh --sign`
signs the uninstaller through `!uninstfinalize` and then the installer;
`verify-installer.ps1` promotes. `signing-lib.ps1` holds the identity checks against
`signing-identity.json`: the leaf certificate changes daily, so never pin a
thumbprint, and keep the timestamp, EKU and root checks. Run
`pwsh -NoProfile -File windows/signing-test.ps1` after changing them;
`signing-smoke.ps1` is for disposable hosted Windows. Never sign CI-only builds.

`deploy/windows/fetch-winsparkle.sh` pins the official updater DLL and licences.
The update integration is dormant without a compiled HTTPS feed and Ed25519 public
key; release builds compile both. Keep the secure DLL lookup and signature
requirement. `deploy/release/publish.sh` writes the Windows update feed from
`releases.json` with stable releases only (WinSparkle has no beta channel), and
publishes Windows files and the feed only when the `PUBLISH_WINDOWS_APP`
repository variable is `true`: setting it is a production change for the user.
No public feed or installer promotion is authorised by compiling a build.

Normal artifacts reject Windows Server. `platform-smoke.ps1` proves app/installer
rejection before mutation; `--ci-server-host` builds separate development-only CI
outputs with a compile-time exception and CI-only marker/PE metadata. Preserve the
build and runtime hosted-CI checks, and reject those outputs from promotion even
when signed or their sidecar is removed. Do not use this exception to widen the
desktop support policy or restrict the portable server. CI still embeds the same
run's built web app, and the normal development installer stays the manual-test
artifact for supported Windows 11 PCs.
