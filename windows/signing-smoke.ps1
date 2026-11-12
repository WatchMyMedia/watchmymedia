# Disposable hosted Windows CI, after verify-installer.ps1 promoted a signed
# installer: the promotion gate must refuse that installer with one byte
# changed, and a signature that copies the publisher name and identity EKU
# under another root. The look-alike root is trusted on this runner only for
# the test, so Windows itself calls the signature valid.
#Requires -Version 7.0
$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted' -or $env:RUNNER_OS -ne 'Windows') {
  throw 'Signing smoke requires disposable hosted Windows CI'
}
. (Join-Path $PSScriptRoot 'signing-lib.ps1')
$identity = Read-WMMSigningIdentity
$root = Split-Path $PSScriptRoot -Parent
$appDir = Join-Path $root 'build/windows-app'
$uninstaller = Join-Path $root 'build/windows-installer/uninstall.exe'
$signed = Join-Path $root 'build/windows-installer/WatchMyMedia-development-x64-setup.exe'
$output = Join-Path $root 'build/windows-signing-smoke'
Remove-Item -Recurse -Force -LiteralPath $output -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $output | Out-Null
function Assert-Rejected([scriptblock]$Action, [string]$Pattern) {
  try { & $Action } catch {
    if ($_.Exception.Message -notlike $Pattern) { throw "Unexpected failure: $($_.Exception.Message)" }
    Write-Output "Rejected: $($_.Exception.Message)"
    return
  }
  throw "Expected rejection: $Pattern"
}

# A changed byte inside the signed range. Keep the staging name so the gate
# reaches the signature checks, and check nothing was promoted.
$tampered = Join-Path $output 'WatchMyMedia-development-x64-setup-unsigned.exe'
Copy-Item -LiteralPath $signed -Destination $tampered
$bytes = [IO.File]::ReadAllBytes($tampered)
$bytes[[int]($bytes.Length / 2)] = $bytes[[int]($bytes.Length / 2)] -bxor 0xFF
[IO.File]::WriteAllBytes($tampered, $bytes)
$status = (Get-AuthenticodeSignature -LiteralPath $tampered).Status
if ($status -ne 'HashMismatch') { throw "Changed installer reported $status" }
Assert-Rejected { & (Join-Path $PSScriptRoot 'verify-installer.ps1') -AppDir $appDir -Installer $tampered -Uninstaller $uninstaller } 'Signature verification failed for WatchMyMedia-development-x64-setup-unsigned.exe: HashMismatch'
if (!(Test-Path -LiteralPath $tampered) -or (Test-Path -LiteralPath (Join-Path $output 'WatchMyMedia-development-x64-setup.exe'))) {
  throw 'Changed installer left staging'
}

# A look-alike: the same subject and EKUs from a root this runner trusts.
$store = [System.Security.Cryptography.X509Certificates.X509Store]::new('Root', 'LocalMachine')
$fakeRoot = $null; $fakeLeaf = $null
try {
  $fakeRoot = New-SelfSignedCertificate -Type Custom -Subject 'CN=WatchMyMedia CI look-alike root' -KeyUsage CertSign, CRLSign `
    -TextExtension '2.5.29.19={critical}{text}ca=1' -CertStoreLocation Cert:\CurrentUser\My -NotAfter (Get-Date).AddDays(1)
  $fakeLeaf = New-SelfSignedCertificate -Type Custom -Subject "CN=$($identity.publisher), O=$($identity.publisher)" -Signer $fakeRoot `
    -KeyUsage DigitalSignature -TextExtension "2.5.29.37={text}1.3.6.1.5.5.7.3.3,$($identity.identityEku)" `
    -CertStoreLocation Cert:\CurrentUser\My -NotAfter (Get-Date).AddDays(1)
  $store.Open('ReadWrite')
  $store.Add([System.Security.Cryptography.X509Certificates.X509Certificate2]::new($fakeRoot.RawData))
  $lookalike = Join-Path $output 'WatchMyMedia.exe'
  Copy-Item -LiteralPath (Join-Path $appDir 'WatchMyMedia.exe') -Destination $lookalike
  $result = Set-AuthenticodeSignature -LiteralPath $lookalike -Certificate $fakeLeaf -HashAlgorithm SHA256
  if ($result.Status -ne 'Valid') { throw "Look-alike signing reported $($result.Status)" }
  $mismatch = Get-WMMSignerMismatch (Get-AuthenticodeSignature -LiteralPath $lookalike).SignerCertificate $identity
  if ($mismatch) { throw "Look-alike certificate does not copy the identity: $mismatch" }
  Assert-Rejected { Assert-WMMSignature $lookalike $identity } 'Signature verification failed for WatchMyMedia.exe: not issued under the pinned Microsoft root'
  # Pinning the look-alike root instead reaches the timestamp check.
  $pinned = [pscustomobject]@{ publisher = $identity.publisher; identityEku = $identity.identityEku; rootThumbprint = $fakeRoot.Thumbprint }
  Assert-Rejected { Assert-WMMSignature $lookalike $pinned } 'Signature verification failed for WatchMyMedia.exe: not timestamped'
} finally {
  if ($fakeRoot) {
    if ($store.IsOpen) { $store.Remove([System.Security.Cryptography.X509Certificates.X509Certificate2]::new($fakeRoot.RawData)) }
    Remove-Item -LiteralPath "Cert:\CurrentUser\My\$($fakeRoot.Thumbprint)" -ErrorAction SilentlyContinue
  }
  if ($fakeLeaf) { Remove-Item -LiteralPath "Cert:\CurrentUser\My\$($fakeLeaf.Thumbprint)" -ErrorAction SilentlyContinue }
  $store.Dispose()
}
Write-Output 'The promotion gate refused a changed installer and a look-alike signature'
