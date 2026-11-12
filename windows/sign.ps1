# Signs Windows files with the Artifact Signing profile, then checks each
# signature against signing-identity.json. Run setup-signing.ps1 and sign in
# to Azure first (the windows-signing CI job does both). installer.nsi runs
# this for the uninstaller and -KeepCopy keeps that file for verification.
#Requires -Version 7.0
param(
  [Parameter(Mandatory=$true)][string[]]$Path,
  [string]$KeepCopy
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'signing-lib.ps1')
$identity = Read-WMMSigningIdentity
foreach ($name in 'WMM_SIGNTOOL', 'WMM_SIGNING_DLIB', 'WMM_SIGNING_METADATA') {
  $value = [Environment]::GetEnvironmentVariable($name)
  if (!$value -or !(Test-Path -LiteralPath $value -PathType Leaf)) { throw "$name must name a file: run windows/setup-signing.ps1 first" }
}
if ($KeepCopy -and $Path.Count -ne 1) { throw '-KeepCopy keeps one signed file' }
foreach ($file in $Path) {
  $file = (Resolve-Path -LiteralPath $file).ProviderPath
  # Never replace someone else's signature, or sign a file twice.
  $status = (Get-AuthenticodeSignature -LiteralPath $file).Status
  if ($status -ne 'NotSigned') { throw "Refusing to sign $(Split-Path $file -Leaf): it is already $status" }
  & $env:WMM_SIGNTOOL sign /fd SHA256 /tr http://timestamp.acs.microsoft.com /td SHA256 /dlib $env:WMM_SIGNING_DLIB /dmdf $env:WMM_SIGNING_METADATA $file
  if ($LASTEXITCODE -ne 0) { throw "SignTool failed for $(Split-Path $file -Leaf) with exit code $LASTEXITCODE" }
  Assert-WMMSignature $file $identity
  Write-Output "Signed and verified $(Split-Path $file -Leaf)"
}
if ($KeepCopy) { Copy-Item -LiteralPath $Path[0] -Destination $KeepCopy -Force }
