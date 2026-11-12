# Fetches the pinned SignTool and Artifact Signing client, checks their
# NuGet packages by SHA-256 and writes the signing metadata for sign.ps1.
# The endpoint, account and profile come from the ARTIFACT_SIGNING_*
# repository variables; they name the profile and are not credentials.
# Bump a package by changing its version and checksum together, from a
# download you checked (the client also lists the .NET runtime it needs).
#Requires -Version 7.0
param([Parameter(Mandatory=$true)][string]$Destination)
$ErrorActionPreference = 'Stop'
if (!$IsWindows) { throw 'SignTool runs on Windows' }
$packages = @(
  @{ id = 'microsoft.windows.sdk.buildtools'; version = '10.0.28000.2957'; sha256 = 'B7163FC6F636172726D0985963E4F7F11C41AFE1C7FDFE78F8051877360ED696' },
  @{ id = 'microsoft.artifactsigning.client'; version = '1.0.151'; sha256 = '35375A4BC4E1102DA729FC3D8D9925F774C3BAAE6B8B7FF6398D948CA9AB0D8A' }
)
New-Item -ItemType Directory -Force $Destination | Out-Null
$ProgressPreference = 'SilentlyContinue'
foreach ($package in $packages) {
  # Expand-Archive takes only .zip names; a NuGet package is a zip file.
  $archive = Join-Path $Destination "$($package.id).$($package.version).zip"
  Invoke-WebRequest -Uri "https://api.nuget.org/v3-flatcontainer/$($package.id)/$($package.version)/$($package.id).$($package.version).nupkg" -OutFile $archive
  $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash
  if ($actual -ne $package.sha256) { throw "Checksum mismatch for $($package.id) $($package.version): $actual" }
  Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $Destination $package.id) -Force
}
$signtool = Join-Path $Destination 'microsoft.windows.sdk.buildtools\bin\10.0.28000.0\x64\signtool.exe'
$dlib = Join-Path $Destination 'microsoft.artifactsigning.client\bin\x64\Azure.CodeSigning.Dlib.dll'
foreach ($file in $signtool, $dlib) { if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing $file" } }
# Client 1.0.151 is built for .NET 10 and rolls forward to newer runtimes.
$runtime = & dotnet --list-runtimes | Where-Object { $_ -match '^Microsoft\.NETCore\.App (\d+)\.' -and [int]$Matches[1] -ge 10 }
if (!$runtime) { throw 'The Artifact Signing client needs the .NET 10 runtime' }

foreach ($name in 'ARTIFACT_SIGNING_ENDPOINT', 'ARTIFACT_SIGNING_ACCOUNT', 'ARTIFACT_SIGNING_PROFILE') {
  if (![Environment]::GetEnvironmentVariable($name)) { throw "$name is not set" }
}
$endpoint = [uri]$env:ARTIFACT_SIGNING_ENDPOINT
if ($endpoint.Scheme -ne 'https' -or !$endpoint.Host.EndsWith('.codesigning.azure.net')) { throw 'ARTIFACT_SIGNING_ENDPOINT is not an Artifact Signing endpoint' }
$metadata = [ordered]@{
  Endpoint = $endpoint.AbsoluteUri
  CodeSigningAccountName = $env:ARTIFACT_SIGNING_ACCOUNT
  CertificateProfileName = $env:ARTIFACT_SIGNING_PROFILE
  CorrelationId = "watchmymedia-$env:GITHUB_RUN_ID-$env:GITHUB_RUN_ATTEMPT"
  # Use only the Azure CLI sign-in azure/login made; probing the others
  # (a managed identity endpoint, for one) only slows each signature.
  ExcludeCredentials = @('ManagedIdentityCredential', 'WorkloadIdentityCredential', 'SharedTokenCacheCredential',
    'VisualStudioCredential', 'VisualStudioCodeCredential', 'AzurePowerShellCredential',
    'AzureDeveloperCliCredential', 'InteractiveBrowserCredential')
}
$metadataPath = Join-Path $Destination 'metadata.json'
$metadata | ConvertTo-Json | Set-Content -LiteralPath $metadataPath -Encoding utf8NoBOM
$settings = "WMM_SIGNTOOL=$signtool", "WMM_SIGNING_DLIB=$dlib", "WMM_SIGNING_METADATA=$metadataPath"
if ($env:GITHUB_ENV) { $settings | Add-Content -LiteralPath $env:GITHUB_ENV -Encoding utf8NoBOM } else { $settings }
"SignTool $((Get-Item -LiteralPath $signtool).VersionInfo.FileVersion), Artifact Signing client $($packages[1].version), $(@($runtime)[-1])"
