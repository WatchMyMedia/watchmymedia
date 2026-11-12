# Run on the disposable hosted Windows runner, never on a user's PC.
param([switch]$CIHost)
$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted') { throw 'This smoke requires a disposable GitHub-hosted Windows runner' }
$root = Split-Path $PSScriptRoot -Parent
$installerDir = if ($CIHost) { 'build/windows-ci-installer' } else { 'build/windows-installer' }
$installer = Join-Path $root "$installerDir/WatchMyMedia-development-x64-setup-unsigned.exe"
$install = Join-Path $root 'build/windows-installed'
$data = Join-Path $env:LOCALAPPDATA 'WatchMyMedia'
if (Test-Path $data) { throw 'Expected a fresh hosted user profile' }
New-Item -ItemType Directory -Force $data | Out-Null
Set-Content (Join-Path $data 'keep-data.txt') 'fictional library data'
function Install-App {
  $p = Start-Process $installer -ArgumentList @('/S', ('/D=' + $install)) -Wait -PassThru
  if ($p.ExitCode -ne 0) { throw "Installer exit $($p.ExitCode)" }
  if (!(Test-Path (Join-Path $install 'uninstall.exe'))) { throw 'Uninstaller missing' }
}
Install-App
try {
  $rule = Get-NetFirewallRule -DisplayName 'WatchMyMedia server'
  if (!$rule -or [int]$rule.Profile -ne 3) { throw 'Expected private/domain firewall rule' }
  $program = ($rule | Get-NetFirewallApplicationFilter).Program
  if ($program -ne (Join-Path $install 'watchmymedia-server.exe')) { throw 'Firewall rule targets the wrong program' }
  $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback,0)
  $listener.Start(); $port=$listener.LocalEndpoint.Port; $listener.Stop()
  $env:WATCHMYMEDIA_DISCOVERY='false'
  $env:WATCHMYMEDIA_HOST='127.0.0.1'
  $env:WATCHMYMEDIA_UPDATES_ENABLED='false'
  $env:WATCHMYMEDIA_TRANSCODE_HWACCEL='software'
  $app = Join-Path $install 'WatchMyMedia.exe'
  $tray = Start-Process $app -ArgumentList @('-no-browser','-data-dir',('"'+$data+'"'),'-port',$port) -PassThru
  $ready=$false
  $deadline=(Get-Date).AddSeconds(60)
  while ((Get-Date) -lt $deadline) {
    try { $s=Invoke-RestMethod "http://127.0.0.1:$port/api/playback/local" -TimeoutSec 2; $ready=$s.setupRequired; if($ready){break} } catch {}
    Start-Sleep -Milliseconds 200
  }
  if (!$ready) { throw 'Installed server did not answer with fresh setup' }
  $setup=Invoke-WebRequest "http://127.0.0.1:$port/setup" -TimeoutSec 5
  if ($setup.StatusCode -ne 200 -or !$setup.Content.Contains('<div id="root">')) { throw 'Installed web setup app is missing' }
  # Upgrade must stop the running tray before replacing its executable.
  Install-App
  if (!$tray.WaitForExit(15000)) { throw 'Upgrade left the old tray running' }
  if (!(Test-Path (Join-Path $data 'keep-data.txt'))) { throw 'Upgrade lost data' }
  $uninstaller=Join-Path $install 'uninstall.exe'
  # NSIS copies its uninstaller to TEMP; _?= keeps it in place for a waited smoke.
  $p=Start-Process $uninstaller -ArgumentList @('/S',('_?='+$install)) -Wait -PassThru
  if($p.ExitCode -ne 0) { throw "Uninstall exit $($p.ExitCode)" }
  if (Test-Path $app) { throw 'Uninstall left the tray executable' }
  if (Get-NetFirewallRule -DisplayName 'WatchMyMedia server' -ErrorAction SilentlyContinue) { throw 'Uninstall left its firewall rule' }
  if (!(Test-Path (Join-Path $data 'keep-data.txt'))) { throw 'Uninstall lost data' }
  Write-Output 'Installer smoke passed: install, program-scoped firewall, launch, running upgrade, uninstall, data retained'
} finally {
  if ($tray -and !$tray.HasExited) { Stop-Process -Id $tray.Id -Force }
  $logs=Join-Path $root 'build/windows-smoke/installed/Logs'
  New-Item -ItemType Directory -Force $logs | Out-Null
  Copy-Item (Join-Path $data 'Logs/*.log*') $logs -ErrorAction SilentlyContinue
}
