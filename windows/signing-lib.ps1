# Authenticode checks shared by sign.ps1, verify-installer.ps1 and their tests.
# Artifact Signing issues a new three-day leaf certificate every day, so a
# signature is matched to the validated identity in signing-identity.json,
# never to a thumbprint: the publisher's CN and O, the EKU Microsoft issues for
# this identity validation, a chain to Microsoft's identity verification root,
# and a timestamp that keeps the signature valid after the leaf expires.
#Requires -Version 7.0

$script:CodeSigningEku = '1.3.6.1.5.5.7.3.3'

function Read-WMMSigningIdentity([string]$Path = (Join-Path $PSScriptRoot 'signing-identity.json')) {
  $identity = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
  if ([string]::IsNullOrWhiteSpace($identity.publisher) -or
      $identity.identityEku -cnotmatch '^1\.3\.6\.1\.4\.1\.311\.97\.\d+(\.\d+)+$' -or
      $identity.rootThumbprint -cnotmatch '^[A-F0-9]{40}$') {
    throw "Invalid signing identity in $Path"
  }
  return $identity
}

# Why the certificate is not this identity's code signing certificate, or $null.
function Get-WMMSignerMismatch($Certificate, $Identity) {
  if ($Certificate -isnot [System.Security.Cryptography.X509Certificates.X509Certificate2]) { return 'no signer certificate' }
  $names = @{}
  foreach ($rdn in $Certificate.SubjectName.EnumerateRelativeDistinguishedNames()) {
    if ($rdn.HasMultipleElements) { return "subject is $($Certificate.Subject)" }
    $names[$rdn.GetSingleElementType().Value] += @($rdn.GetSingleElementValue())
  }
  foreach ($oid in '2.5.4.3', '2.5.4.10') { # CN and O
    $values = @($names[$oid])
    if ($values.Count -ne 1 -or $values[0] -cne $Identity.publisher) { return "subject is $($Certificate.Subject)" }
  }
  $usages = @($Certificate.Extensions |
    Where-Object { $_ -is [System.Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension] } |
    ForEach-Object { $_.EnhancedKeyUsages } | ForEach-Object Value)
  if ($usages -cnotcontains $script:CodeSigningEku) { return 'certificate is not for code signing' }
  if ($usages -cnotcontains $Identity.identityEku) { return 'certificate is not from this identity validation' }
  return $null
}

# The thumbprint at the top of the signer's chain, built from the certificates
# in the file's own signature and the machine's roots, without downloads.
function Get-WMMSignatureRoot([string]$Path, $Certificate) {
  $embedded = [System.Security.Cryptography.X509Certificates.X509Certificate2Collection]::new()
  $embedded.Import($Path)
  $chain = [System.Security.Cryptography.X509Certificates.X509Chain]::new()
  try {
    $chain.ChainPolicy.RevocationMode = [System.Security.Cryptography.X509Certificates.X509RevocationMode]::NoCheck
    $chain.ChainPolicy.DisableCertificateDownloads = $true
    # The leaf expires three days after signing; the timestamp carries it.
    $chain.ChainPolicy.VerificationFlags = [System.Security.Cryptography.X509Certificates.X509VerificationFlags]::IgnoreNotTimeValid
    $chain.ChainPolicy.ExtraStore.AddRange($embedded)
    if (!$chain.Build($Certificate)) { return $null }
    return $chain.ChainElements[$chain.ChainElements.Count - 1].Certificate.Thumbprint
  } finally { $chain.Dispose() }
}

# Throws unless the file carries a valid, timestamped signature from the identity.
function Assert-WMMSignature([string]$Path, $Identity) {
  $signature = Get-AuthenticodeSignature -LiteralPath $Path
  $reason = if ($signature.Status -ne 'Valid') { "$($signature.Status)" }
    else { Get-WMMSignerMismatch $signature.SignerCertificate $Identity }
  if (!$reason -and (Get-WMMSignatureRoot $Path $signature.SignerCertificate) -cne $Identity.rootThumbprint) {
    $reason = 'not issued under the pinned Microsoft root'
  }
  if (!$reason -and !$signature.TimeStamperCertificate) { $reason = 'not timestamped' }
  if ($reason) { throw "Signature verification failed for $(Split-Path $Path -Leaf): $reason" }
}
