# Signer identity tests; run with PowerShell 7 on macOS or Windows. The
# Authenticode, chain and timestamp checks need Windows: signing-smoke.ps1.
#Requires -Version 7.0
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'signing-lib.ps1')
function Assert($Condition, [string]$Message) { if (!$Condition) { throw $Message } }
function Assert-Throws([scriptblock]$Action, [string]$Pattern) {
  try { & $Action } catch { Assert ($_.Exception.Message -like $Pattern) "Unexpected failure: $_"; return }
  throw "Expected failure: $Pattern"
}
function New-TestCertificate([string]$Subject, [string[]]$Usages) {
  $key = [System.Security.Cryptography.ECDsa]::Create()
  try {
    $request = [System.Security.Cryptography.X509Certificates.CertificateRequest]::new(
      $Subject, $key, [System.Security.Cryptography.HashAlgorithmName]::SHA256)
    if ($Usages) {
      $oids = [System.Security.Cryptography.OidCollection]::new()
      foreach ($usage in $Usages) { [void]$oids.Add([System.Security.Cryptography.Oid]::new($usage)) }
      $request.CertificateExtensions.Add([System.Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension]::new($oids, $false))
    }
    return $request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddMinutes(-5), [DateTimeOffset]::UtcNow.AddDays(1))
  } finally { $key.Dispose() }
}

$identity = Read-WMMSigningIdentity
Assert ($identity.publisher -ceq 'Eldwick Software LLC') 'Unexpected publisher'
$codeSigning = '1.3.6.1.5.5.7.3.3'
$publisher = 'CN=Eldwick Software LLC, O=Eldwick Software LLC, L=Example City, C=US'

$scratch = Join-Path ([IO.Path]::GetTempPath()) "wmm-signing-test-$PID"
New-Item -ItemType Directory -Force $scratch | Out-Null
try {
  $bad = Join-Path $scratch 'identity.json'
  foreach ($case in @(
      @{ publisher = ''; identityEku = $identity.identityEku; rootThumbprint = $identity.rootThumbprint },
      @{ publisher = 'Eldwick Software LLC'; identityEku = '1.3.6.1.5.5.7.3.3'; rootThumbprint = $identity.rootThumbprint },
      @{ publisher = 'Eldwick Software LLC'; identityEku = $identity.identityEku; rootThumbprint = 'f40042e2e5f7e8ef8189fed15519aece42c3bfa2' })) {
    $case | ConvertTo-Json | Set-Content -LiteralPath $bad
    Assert-Throws { Read-WMMSigningIdentity $bad } 'Invalid signing identity*'
  }
} finally { Remove-Item -Recurse -Force -LiteralPath $scratch }

$cases = @(
  @{ subject = $publisher; usages = @($codeSigning, $identity.identityEku); expected = $null },
  @{ subject = 'O=Eldwick Software LLC, CN=Eldwick Software LLC'; usages = @($identity.identityEku, $codeSigning); expected = $null },
  @{ subject = 'CN=Eldwick Software LLC, O=Someone Else LLC, C=US'; usages = @($codeSigning, $identity.identityEku); expected = 'subject is *' },
  @{ subject = 'CN=Someone Else LLC, O=Eldwick Software LLC, C=US'; usages = @($codeSigning, $identity.identityEku); expected = 'subject is *' },
  @{ subject = 'CN=Eldwick Software LLC, C=US'; usages = @($codeSigning, $identity.identityEku); expected = 'subject is *' },
  @{ subject = 'CN=eldwick software llc, O=eldwick software llc'; usages = @($codeSigning, $identity.identityEku); expected = 'subject is *' },
  @{ subject = 'CN=Eldwick Software LLC, CN=Eldwick Software LLC, O=Eldwick Software LLC'; usages = @($codeSigning, $identity.identityEku); expected = 'subject is *' },
  @{ subject = $publisher; usages = @($codeSigning); expected = 'certificate is not from this identity validation' },
  @{ subject = $publisher; usages = @($codeSigning, '1.3.6.1.4.1.311.97.1.0'); expected = 'certificate is not from this identity validation' },
  @{ subject = $publisher; usages = @($codeSigning, ($identity.identityEku + '.1')); expected = 'certificate is not from this identity validation' },
  @{ subject = $publisher; usages = @($identity.identityEku); expected = 'certificate is not for code signing' },
  @{ subject = $publisher; usages = @(); expected = 'certificate is not for code signing' }
)
foreach ($case in $cases) {
  $certificate = New-TestCertificate $case.subject $case.usages
  try {
    $reason = Get-WMMSignerMismatch $certificate $identity
    if ($null -eq $case.expected) { Assert ($null -eq $reason) "Rejected $($case.subject): $reason" }
    else { Assert ($reason -like $case.expected) "Expected '$($case.expected)' for $($case.subject) [$($case.usages -join ',')], got '$reason'" }
  } finally { $certificate.Dispose() }
}
Assert ((Get-WMMSignerMismatch $null $identity) -eq 'no signer certificate') 'Accepted a missing certificate'
Write-Output "Signing identity tests passed ($($cases.Count + 4) cases)"
