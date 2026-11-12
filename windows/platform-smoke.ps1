# Check real production rejection before using a separate CI-only Server build.
param([switch]$CIHost)
$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted' -or $env:RUNNER_OS -ne 'Windows') {
  throw 'Platform smoke requires disposable hosted Windows CI'
}
$root = Split-Path $PSScriptRoot -Parent
$output = Join-Path $root 'build/windows-platform-smoke'
New-Item -ItemType Directory -Force $output | Out-Null
function Run-Check([string]$Exe, [string[]]$Arguments, [int]$Expected) {
  $p = Start-Process $Exe -ArgumentList $Arguments -PassThru
  if (!$p.WaitForExit(30000)) { $p.Kill(); throw "Platform check timed out: $Exe" }
  if ($p.ExitCode -ne $Expected) { throw "Platform check exit $($p.ExitCode), expected $Expected" }
}
if (!$CIHost) {
  $app = Join-Path $root 'build/windows-app/WatchMyMedia.exe'
  $report = Join-Path $output 'production.json'
  Run-Check $app @('-platform-report', ('"'+$report+'"')) 1633
  $hostReport = Get-Content -Raw $report | ConvertFrom-Json
  if ($hostReport.host.productType -eq 1 -or $hostReport.desktopSupported -or $hostReport.ciOnly -or $hostReport.allowed) {
    throw 'Normal desktop binary admitted the hosted Server or did not detect its product type'
  }
  $data = Join-Path $output 'must-not-create-data'
  Run-Check $app @('-no-browser', '-data-dir', ('"'+$data+'"')) 1633
  if (Test-Path $data) { throw 'Unsupported tray launch created data' }
  $install = Join-Path $output 'must-not-install'
  $installer = Join-Path $root 'build/windows-installer/WatchMyMedia-development-x64-setup-unsigned.exe'
  Run-Check $installer @('/S', ('/D='+$install)) 1633
  if ((Test-Path $install) -or (Test-Path (Join-Path $env:LOCALAPPDATA 'WatchMyMedia')) -or
      (Test-Path 'HKLM:/Software/WatchMyMedia') -or
      (Test-Path 'HKLM:/Software/Microsoft/Windows/CurrentVersion/Uninstall/WatchMyMedia') -or
      (Get-NetFirewallRule -DisplayName 'WatchMyMedia server' -ErrorAction SilentlyContinue)) {
    throw 'Unsupported installer changed application state'
  }
  Write-Output 'Normal tray and installer refused Windows Server without installation or data changes'
} else {
  $appDir = Join-Path $root 'build/windows-ci-app'
  $app = Join-Path $appDir 'WatchMyMedia.exe'
  $report = Join-Path $output 'ci-server.json'
  Run-Check $app @('-platform-report', ('"'+$report+'"')) 0
  $hostReport = Get-Content -Raw $report | ConvertFrom-Json
  if (!$hostReport.ciOnly -or !$hostReport.allowed -or $hostReport.desktopSupported) { throw 'CI build did not report its separate Server exception' }
  $actions = $env:GITHUB_ACTIONS
  try {
    $env:GITHUB_ACTIONS = ''
    Run-Check $app @('-check-platform') 1633
  } finally { $env:GITHUB_ACTIONS = $actions }
  $installer = Join-Path $root 'build/windows-ci-installer/WatchMyMedia-development-x64-setup-unsigned.exe'
  # Both marker and PE metadata protect promotion; removing a sidecar must not
  # allow a signed CI build to enter the ordinary release path.
  $marker = Join-Path $appDir 'CI-ONLY.txt'
  if (!(Test-Path $marker)) { throw 'CI build marker missing' }
  Move-Item $marker ($marker+'.held')
  try {
    # A mismatched normal AppDir must not hide the installer's own CI marker.
    foreach ($candidate in @($appDir, (Join-Path $root 'build/windows-app'))) {
      $rejected = $false
      try {
        & (Join-Path $PSScriptRoot 'verify-installer.ps1') -AppDir $candidate -Installer $installer -Uninstaller (Join-Path $appDir 'unused-uninstaller.exe')
      } catch {
        if ($_.Exception.Message -notlike 'CI-only or unrecognized desktop build*') { throw }
        $rejected = $true
      }
      if (!$rejected -or !(Test-Path $installer)) { throw 'CI installer was not held out of promotion' }
    }
  } finally { Move-Item ($marker+'.held') $marker }
  Write-Output 'CI-only Server build is restricted to hosted CI and rejected by release promotion'
}
