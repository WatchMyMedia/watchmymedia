# Promotion gate, run after signing inside out. Each binary, the signed
# uninstaller that NSIS embedded (kept by sign.ps1 -KeepCopy) and the installer
# must carry a valid, timestamped signature from the identity in
# signing-identity.json (signing-lib.ps1). Artifact Signing changes the leaf
# certificate daily, so no certificate thumbprint is pinned.
#Requires -Version 7.0
param(
  [Parameter(Mandatory=$true)][string]$AppDir,
  [Parameter(Mandatory=$true)][string]$Installer,
  [Parameter(Mandatory=$true)][string]$Uninstaller
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'signing-lib.ps1')
$identity = Read-WMMSigningIdentity
if ($Installer -notmatch '-unsigned\.exe$') { throw 'Expected an unsigned staging filename before promotion' }
$tray = Join-Path $AppDir 'WatchMyMedia.exe'
if ((Test-Path (Join-Path $AppDir 'CI-ONLY.txt')) -or
    (Get-Item -LiteralPath $tray).VersionInfo.FileDescription -ne 'WatchMyMedia' -or
    (Get-Item -LiteralPath $Installer).VersionInfo.FileDescription -eq 'WatchMyMedia CI host validation installer') {
  throw 'CI-only or unrecognized desktop build cannot be promoted'
}
$targets=@('WatchMyMedia.exe','watchmymedia-server.exe','ffmpeg.exe','ffprobe.exe','WinSparkle.dll') | ForEach-Object {Join-Path $AppDir $_}
$targets += @($Uninstaller,$Installer)
foreach($target in $targets) { Assert-WMMSignature $target $identity }
$public=$Installer -replace '-unsigned\.exe$','.exe'
if (Test-Path -LiteralPath $public) { throw 'A promoted installer already exists; refusing to replace it' }
Move-Item -LiteralPath $Installer -Destination $public
Write-Output $public
