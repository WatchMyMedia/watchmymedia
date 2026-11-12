# Dependency-free helper tests; run with PowerShell 7 on macOS or Windows.
#Requires -Version 7.0
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'hardware-check-lib.ps1')
function Assert($Condition, [string]$Message) { if (!$Condition) { throw $Message } }
function Assert-Throws([scriptblock]$Action, [string]$Pattern) {
  try { & $Action } catch { Assert ($_.Exception.Message -like $Pattern) "Unexpected failure: $_"; return }
  throw "Expected failure: $Pattern"
}
function New-TestProbe([string]$Backend, $Device, [string]$Actual = 'software') {
  $encoder = if ($Actual -eq 'software') { 'libx264' } else { 'h264_' + $Actual }
  $caps = @{ hwaccel = $Actual; encoder = $encoder; encoders = @($encoder); maxHeight = 1080 }
  $diag = @{ encoder = $encoder; stage = 'encode'; ok = $true }
  if ($Actual -ne 'software' -and $null -ne $Device) { $caps.device = $Device; $diag.device = $Device }
  $diagnostics = if ($Actual -eq 'software') { @() } else { @($diag) }
  $r = @{ platform = 'windows/amd64'; preference = $Backend; capabilities = $caps; diagnostics = $diagnostics; hardwareAdapters = $inventory }
  if ($null -ne $Device) { $r.requestedDevice = $Device }
  return $r
}
$inventory = @{
  supported = $true; diagnostics = @(@{ namespace = 'cuda'; ok = $true }, @{ namespace = 'directx'; ok = $true })
  adapters = @(
    @{ namespace = 'cuda'; index = 2; name = 'Orion secondary GPU' },
    @{ namespace = 'directx'; index = 3; vendorId = 0x1002; name = 'Harbor AMD GPU' },
    @{ namespace = 'cuda'; index = 0; name = 'Orion primary GPU' },
    @{ namespace = 'directx'; index = 1; vendorId = 0x10de; name = 'Orion DirectX GPU' },
    @{ namespace = 'directx'; index = 0; vendorId = 0x8086; name = 'Aurora Intel GPU' }
  )
}
$cases = Get-WMMProbeCases $inventory all $null
$labels = @($cases | ForEach-Object { "$($_.backend):$($_.device)" }) -join ','
Assert ($labels -eq 'software:,nvenc:,nvenc:0,nvenc:2,qsv:,qsv:0,amf:,amf:3') "Lost namespace/ordinal mapping: $labels"
Assert ($null -eq $cases[1].adapterName) 'The backend default was assigned a guessed GPU name'
Assert ($cases[3].adapterName -eq 'Orion secondary GPU') 'The CUDA name was joined to the wrong ordinal'
Assert (@(Get-WMMProbeCases $inventory nvenc 0).Count -eq 1) 'Explicit adapter zero was treated as the default'
Assert-Throws { Get-WMMProbeCases $inventory all 0 } '*Device requires*'
Assert-Throws { Get-WMMProbeCases $inventory nvenc -1 } '*Device requires*'
$case = @{ backend = 'nvenc'; device = 0 }
$probe = New-TestProbe nvenc 0 nvenc
Assert ((Get-WMMProbeOutcome $case $probe 0 $false) -eq 'hardware') 'A matching hardware probe was not recognized'
Assert ((Get-WMMProbeOutcome $case (New-TestProbe nvenc 0) 0 $false) -eq 'software-fallback') 'Software fallback was called a successful hardware probe'
$probe.capabilities.device = 2
Assert ((Get-WMMProbeOutcome $case $probe 0 $false) -eq 'error') 'A different GPU was accepted'
$probe = New-TestProbe nvenc 0 nvenc
$probe.diagnostics = @()
Assert ((Get-WMMProbeOutcome $case $probe 0 $false) -eq 'error') 'A name/encoder without a successful encode probe was accepted'
Assert ((Get-WMMProbeOutcome $case (New-TestProbe nvenc 0 nvenc) 1 $false) -eq 'error') 'A failed command was accepted'
Assert ((Get-WMMProbeOutcome $case (New-TestProbe nvenc 0 nvenc) 0 $true) -eq 'timeout') 'A timed-out command was accepted'

$scratch = Join-Path ([IO.Path]::GetTempPath()) ('wmm-hardware-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch | Out-Null
try {
  $app = Join-Path $scratch "Fictional app ' [α]"
  New-Item -ItemType Directory -Path $app | Out-Null
  foreach ($file in @('WatchMyMedia.exe', 'watchmymedia-server.exe', 'ffmpeg.exe', 'ffprobe.exe', 'WinSparkle.dll')) {
    'synthetic test executable content' | Set-Content -LiteralPath (Join-Path $app $file)
  }
  $script:calls = [Collections.Generic.List[object]]::new()
  $script:badProbe = $false
  $script:changedInventory = $false
  $script:omitInventory = $false
  $fake = {
    param($File, $Arguments, $TimeoutMilliseconds)
    Assert ($TimeoutMilliseconds -gt 0 -and $TimeoutMilliseconds -le 150000) 'Unbounded native process timeout'
    $script:calls.Add(@{ file = $File; arguments = $Arguments })
    $r = @{ exitCode = 0; stdout = ''; stderr = ''; timedOut = $false }
    switch ($Arguments[0]) {
      '-NoProfile' { $r.stdout = '[{"Name":"Fictional display driver","DriverVersion":"1.2.3","DriverDate":"2026-11-03"}]' }
      'version' { $r.stdout = 'fixture-version' }
      '-platform-report' {
        @{ host = @{ major = 10; build = 26200; nativeMachine = 0x8664; productType = 3 }; desktopSupported = $false; ciOnly = $false; allowed = $false } |
          ConvertTo-Json | Set-Content -LiteralPath $Arguments[1]
        $r.exitCode = 1633
      }
      'gpu-adapters' { $r.stdout = $inventory | ConvertTo-Json -Depth 10 }
      'probe-transcoder' {
        $b = $Arguments[2]
        Assert ($Arguments[3] -eq '-ffmpeg' -and $Arguments[4] -eq (Join-Path $app 'ffmpeg.exe')) 'The probe did not use bundled FFmpeg'
        $d = if ($Arguments.Count -eq 7) { Assert ($Arguments[5] -eq '-device') 'Missing explicit-device flag'; [int]$Arguments[6] } else { $null }
        $actual = if ($b -eq 'nvenc' -and $d -eq 2) { 'nvenc' } else { 'software' }
        $probeResult = New-TestProbe $b $d $actual
        if ($script:changedInventory) {
          $probeResult.hardwareAdapters = $inventory | ConvertTo-Json -Depth 10 | ConvertFrom-Json -AsHashtable
          $probeResult.hardwareAdapters.adapters[0].name = 'Changed fictional adapter'
        }
        if ($script:omitInventory) { $probeResult.Remove('hardwareAdapters') }
        $r.stdout = $probeResult | ConvertTo-Json -Depth 10
        if ($script:badProbe -and $b -eq 'qsv') { $r.stdout = '{invalid' }
      }
      default { throw 'Collector attempted an unexpected command' }
    }
    return $r
  }
  $output = Join-Path $scratch 'report'
  $report = Invoke-WMMHardwareCheck -AppDir $app -ReportDir $output -RunNative $fake
  Assert $report.collectionComplete ('Collection failed: ' + $report['error'] + ' outcomes: ' + (($report.probes | ForEach-Object { $_.outcome + ':' + $_['error'] }) -join ','))
  Assert (!$report.acceptanceComplete -and !$report.desktopPlatform.desktopSupported) 'A hosted Server/software run was called desktop/release acceptance'
  Assert ($report.probes.Count -eq 8) 'The collector silently omitted requested probes'
  Assert (@($report.probes | Where-Object outcome -eq 'hardware').Count -eq 1) 'Unexpected hardware pass'
  Assert (@($report.probes | Where-Object outcome -eq 'software-fallback').Count -eq 6) 'Unavailable backends did not retain their fallback result'
  Assert ($report.source.files.Count -eq 5 -and $report.source.serverVersion -eq 'fixture-version') 'The report lost binary provenance'
  Assert (!$report.displayDrivers.mappedToAdapterIndices -and $report.displayDrivers.items[0].DriverVersion -eq '1.2.3') 'Driver versions were lost or joined to native ordinals'
  Assert ($report.manualChecks.Count -ge 5) 'Manual acceptance requirements were lost'
  Assert (Test-Path -LiteralPath (Join-Path $output 'report.json')) 'No durable aggregate report'
  Assert-Throws { Invoke-WMMHardwareCheck -AppDir $app -ReportDir $output -RunNative $fake } '*already exists*'
  Assert-Throws { Invoke-WMMHardwareCheck -AppDir $app -ReportDir (Join-Path $app 'report') -RunNative $fake } '*outside the application directory*'
  Assert (!(Test-Path -LiteralPath (Join-Path $app 'report'))) 'Preflight changed the app directory'
  Push-Location -LiteralPath $scratch
  try {
    $relative = Invoke-WMMHardwareCheck -AppDir "Fictional app ' [α]" -ReportDir 'relative-report' -Backend SOFTWARE -RunNative $fake
    Assert ($relative.collectionComplete -and $relative.probes[0].backend -ceq 'software' -and (Test-Path -LiteralPath (Join-Path $scratch 'relative-report/report.json'))) 'Paths ignored the PowerShell location or backend casing was not normalized'
  } finally { Pop-Location }
  $script:badProbe = $true
  $bad = Invoke-WMMHardwareCheck -AppDir $app -ReportDir (Join-Path $scratch 'invalid') -RunNative $fake
  Assert (!$bad.collectionComplete -and $bad.probes.Count -eq 8) 'Malformed JSON erased healthy results or looked complete'
  $script:badProbe = $false
  $script:changedInventory = $true
  $swapped = Invoke-WMMHardwareCheck -AppDir $app -ReportDir (Join-Path $scratch 'swapped') -Backend software -RunNative $fake
  Assert (!$swapped.collectionComplete -and $swapped.probes[0].outcome -eq 'inventory-changed') 'An adapter change was reported under its old inventory'
  $script:changedInventory = $false
  $script:omitInventory = $true
  $missingInventory = Invoke-WMMHardwareCheck -AppDir $app -ReportDir (Join-Path $scratch 'missing-inventory') -Backend software -RunNative $fake
  Assert (!$missingInventory.collectionComplete -and $missingInventory.probes[0].outcome -eq 'error') 'A probe without inventory was accepted as complete'
  $script:omitInventory = $false
  $mutating = {
    param($File, $Arguments, $TimeoutMilliseconds)
    $r = & $fake $File $Arguments $TimeoutMilliseconds
    if ($Arguments[0] -eq 'probe-transcoder') { 'changed synthetic binary' | Add-Content -LiteralPath (Join-Path $app 'ffmpeg.exe') }
    return $r
  }
  $changed = Invoke-WMMHardwareCheck -AppDir $app -ReportDir (Join-Path $scratch 'changed') -Backend software -RunNative $mutating
  Assert (!$changed.collectionComplete -and $changed.source.changedDuringCollection) 'A changed binary was accepted under its previous hash'
  $missingFile = {
    param($File, $Arguments, $TimeoutMilliseconds)
    $r = & $fake $File $Arguments $TimeoutMilliseconds
    if ($Arguments[0] -eq 'probe-transcoder') { Remove-Item -LiteralPath (Join-Path $app 'ffprobe.exe') }
    return $r
  }
  $removed = Invoke-WMMHardwareCheck -AppDir $app -ReportDir (Join-Path $scratch 'removed') -Backend software -RunNative $missingFile
  Assert (!$removed.collectionComplete -and $removed['error']) 'A removed packaged binary was reported as complete'

  # Exercise real process quoting, simultaneous output draining and forced timeout.
  $child = Join-Path $scratch 'child script.ps1'
  'param([string]$Value); [Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); [Console]::Out.Write($Value); [Console]::Error.Write("synthetic stderr")' | Set-Content -LiteralPath $child
  $pwsh = (Get-Process -Id $PID).Path
  $quoted = Invoke-WMMNative $pwsh @('-NoProfile', '-File', $child, '-Value', "spaces ' [α] and `$dollars") 10000
  Assert ($quoted.exitCode -eq 0 -and $quoted.stdout -eq "spaces ' [α] and `$dollars" -and $quoted.stderr -eq 'synthetic stderr') ('Arguments or output were corrupted: ' + ($quoted | ConvertTo-Json -Compress))
  $large = Invoke-WMMNative $pwsh @('-NoProfile', '-Command', '[Console]::Error.Write("e" * 200000); [Console]::Out.Write("o" * 200000)') 10000
  Assert (!$large.timedOut -and $large.stdout.Length -eq 200000 -and $large.stderr.Length -eq 200000) 'The output pipes did not drain concurrently'
  $timed = Invoke-WMMNative $pwsh @('-NoProfile', '-Command', 'Start-Sleep -Seconds 30') 300
  Assert $timed.timedOut 'The native child was allowed to hang'
} finally { Remove-Item -LiteralPath $scratch -Recurse -Force }
Write-Output 'Hardware collector helper tests passed'
exit 0
