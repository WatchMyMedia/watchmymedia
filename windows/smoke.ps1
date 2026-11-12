# Isolated actual-app/actual-server smoke on a hosted Windows desktop.
param([switch]$CIHost)
$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted' -or $env:RUNNER_OS -ne 'Windows') { throw 'This smoke requires disposable hosted Windows CI' }
$root = Split-Path $PSScriptRoot -Parent
$appDir = if ($CIHost) { 'build/windows-ci-app' } else { 'build/windows-app' }
$app = Join-Path $root "$appDir/WatchMyMedia.exe"
$data = Join-Path $root 'build/windows-smoke/run'
New-Item -ItemType Directory -Force $data | Out-Null
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = $listener.LocalEndpoint.Port
$listener.Stop()
$env:WATCHMYMEDIA_DISCOVERY = 'false'
$env:WATCHMYMEDIA_HOST = '127.0.0.1'
$env:WATCHMYMEDIA_TRANSCODE_HWACCEL = 'software'
$env:WATCHMYMEDIA_TMDB_API_KEY = ''
$env:WATCHMYMEDIA_OPENSUBTITLES_API_KEY = ''
$env:WATCHMYMEDIA_UPDATES_ENABLED = 'false'
$tray = $null
try {
  $tray = Start-Process $app -ArgumentList @('-no-browser', '-data-dir', ('"' + $data + '"'), '-port', $port) -PassThru
  $status = $null
  $deadline = (Get-Date).AddSeconds(60)
  while ((Get-Date) -lt $deadline) {
    if ($tray.HasExited) { throw "Tray exited: $($tray.ExitCode)" }
    try { $status = Invoke-RestMethod "http://127.0.0.1:$port/api/playback/local" -TimeoutSec 2; break } catch { Start-Sleep -Milliseconds 250 }
  }
  if (!$status -or !$status.setupRequired) { throw 'Fresh server did not require setup' }
  if (!(Test-Path (Join-Path $data 'watchmymedia.db'))) { throw 'Database was not created in the selected data directory' }
  $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($tray.Id)" | Where-Object Name -eq 'watchmymedia-server.exe')
  if ($children.Count -ne 1) { throw "Expected one owned server, got $($children.Count)" }
  $childId = $children[0].ProcessId
  $second = Start-Process $app -ArgumentList @('-no-browser', '-data-dir', ('"' + $data + '"'), '-port', $port) -PassThru
  if (!$second.WaitForExit(10000) -or $second.ExitCode -ne 0) { throw 'Second launch did not hand off to the running tray' }
  # Quit follows stdin EOF and waits for the actual server to exit.
  $quit = Start-Process $app -ArgumentList '-quit' -PassThru
  if (!$quit.WaitForExit(10000) -or !$tray.WaitForExit(15000)) { throw 'Graceful quit timed out' }
  if (Get-Process -Id $childId -ErrorAction SilentlyContinue) { throw 'Server survived tray quit' }
  # Force-killing the tray must close its job and kill the server too.
  $tray = Start-Process $app -ArgumentList @('-no-browser', '-data-dir', ('"' + $data + '"'), '-port', $port) -PassThru
  $deadline = (Get-Date).AddSeconds(60)
  $childId = $null
  while ((Get-Date) -lt $deadline) {
    try { $null = Invoke-RestMethod "http://127.0.0.1:$port/api/playback/local" -TimeoutSec 2; break } catch { Start-Sleep -Milliseconds 250 }
  }
  $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($tray.Id)" | Where-Object Name -eq 'watchmymedia-server.exe')
  if ($children.Count -ne 1) { throw 'Server did not restart' }
  $childId = $children[0].ProcessId
  Stop-Process -Id $tray.Id -Force
  $deadline = (Get-Date).AddSeconds(10)
  while ((Get-Date) -lt $deadline -and (Get-Process -Id $childId -ErrorAction SilentlyContinue)) { Start-Sleep -Milliseconds 100 }
  if (Get-Process -Id $childId -ErrorAction SilentlyContinue) { throw 'Server survived force-killing the tray' }
  Write-Output 'Tray smoke passed: fresh setup, one instance, graceful quit, crash cleanup'
} finally {
  if ($tray -and !$tray.HasExited) { Stop-Process -Id $tray.Id -Force }
}
