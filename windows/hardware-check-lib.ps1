# Internal helpers shared with hardware-check-test.ps1. No app/server configuration is loaded.
Set-StrictMode -Version Latest

function Invoke-WMMNative {
  param([string]$File, [string[]]$Arguments, [ValidateRange(1, 150000)][int]$TimeoutMilliseconds)
  $p = [Diagnostics.Process]::new()
  $started = $false
  $p.StartInfo.FileName = $File
  $p.StartInfo.UseShellExecute = $false
  $p.StartInfo.CreateNoWindow = $true
  $p.StartInfo.RedirectStandardOutput = $true
  $p.StartInfo.RedirectStandardError = $true
  # Go JSON and our explicitly configured PowerShell child write UTF-8.
  $p.StartInfo.StandardOutputEncoding = [Text.Encoding]::UTF8
  $p.StartInfo.StandardErrorEncoding = [Text.Encoding]::UTF8
  foreach ($arg in $Arguments) { $p.StartInfo.ArgumentList.Add($arg) }
  try {
    if (!$p.Start()) { throw 'Could not start the packaged diagnostic command' }
    $started = $true
    # Drain both streams while waiting; sequential reads can deadlock a child.
    $stdout = $p.StandardOutput.ReadToEndAsync()
    $stderr = $p.StandardError.ReadToEndAsync()
    $timedOut = !$p.WaitForExit($TimeoutMilliseconds)
    if ($timedOut) { $p.Kill($true) }
    $p.WaitForExit()
    $out = $stdout.GetAwaiter().GetResult()
    $err = $stderr.GetAwaiter().GetResult()
    if ($out.Length -gt 1048576 -or $err.Length -gt 1048576) { throw 'Diagnostic output exceeded its limit' }
    return @{ exitCode = $p.ExitCode; stdout = $out; stderr = $err; timedOut = $timedOut }
  } finally {
    if ($started -and !$p.HasExited) { $p.Kill($true); $p.WaitForExit() }
    $p.Dispose()
  }
}

function Get-WMMProbeCases {
  param([System.Collections.IDictionary]$Inventory, [string]$Backend, [Nullable[int]]$Device)
  if ($null -ne $Device -and ($Device -lt 0 -or $Backend -in @('all', 'software'))) {
    throw 'Device requires nvenc, qsv or amf and a nonnegative index. Omit Device for the default.'
  }
  $cases = [Collections.Generic.List[object]]::new()
  $backends = if ($Backend -eq 'all') { @('software', 'nvenc', 'qsv', 'amf') } else { @($Backend) }
  foreach ($b in $backends) {
    $namespace = if ($b -eq 'nvenc') { 'cuda' } elseif ($b -ne 'software') { 'directx' } else { $null }
    $cases.Add(@{ backend = $b; device = $Device; namespace = $namespace; adapterName = $null })
    if ($null -ne $Device -or $b -eq 'software' -or !$Inventory.supported) { continue }
    $vendor = if ($b -eq 'qsv') { 0x8086 } elseif ($b -eq 'amf') { 0x1002 } else { $null }
    $seen = @{}
    foreach ($a in @($Inventory.adapters | Sort-Object index)) {
      if ($a.namespace -ne $namespace -or ($null -ne $vendor -and $a['vendorId'] -ne $vendor)) { continue }
      if ($a.index -isnot [long] -and $a.index -isnot [int]) { throw 'Invalid inventory ordinal' }
      if ($a.index -lt 0 -or $a.index -gt [int]::MaxValue -or $seen.ContainsKey($a.index)) { throw 'Invalid or duplicate inventory ordinal' }
      $seen[$a.index] = $true
      $cases.Add(@{ backend = $b; device = $a.index; namespace = $namespace; adapterName = $a.name })
    }
  }
  return ,$cases.ToArray()
}

function Get-WMMProbeOutcome {
  param([System.Collections.IDictionary]$Case, [System.Collections.IDictionary]$Report, [int]$ExitCode, [bool]$TimedOut)
  if ($TimedOut) { return 'timeout' }
  if ($ExitCode -ne 0 -or $Report.platform -ne 'windows/amd64' -or $Report.preference -ne $Case.backend -or
      $Report['requestedDevice'] -ne $Case.device) { return 'error' }
  $c = $Report.capabilities
  if (!$c -or $c.encoders -notcontains $c.encoder) { return 'error' }
  # The CLI lists libx264 availability; it emits encode diagnostics for hardware
  # candidates only. Software selection is not proof of a playback session.
  if ($c.hwaccel -eq 'software') {
    if ($c.encoder -ne 'libx264' -or $null -ne $c['device']) { return 'error' }
    if ($Case.backend -eq 'software') { return 'software' }
    return 'software-fallback'
  }
  $encoder = @{ nvenc = 'h264_nvenc'; qsv = 'h264_qsv'; amf = 'h264_amf' }[$Case.backend]
  if ($c.hwaccel -ne $Case.backend -or $c.encoder -ne $encoder -or $c['device'] -ne $Case.device) { return 'error' }
  $encoded = @($Report.diagnostics | Where-Object { $_.encoder -eq $encoder -and $_.stage -eq 'encode' -and $_.ok -and $_['device'] -eq $Case.device })
  if (!$encoded.Count) { return 'error' }
  return 'hardware'
}

function Get-WMMInventoryKey {
  param([System.Collections.IDictionary]$Inventory)
  if (!$Inventory -or $Inventory.supported -ne $true -or $null -eq $Inventory.adapters) { throw 'Probe omitted native inventory' }
  $rows = @($Inventory.adapters | Sort-Object namespace, index | ForEach-Object {
    [ordered]@{ namespace = $_.namespace; index = $_.index; name = $_.name; vendorId = $_['vendorId'] }
  })
  return ConvertTo-Json -InputObject $rows -Depth 5 -Compress
}

function Invoke-WMMHardwareCheck {
  param(
    [string]$AppDir, [string]$ReportDir,
    [ValidateSet('all', 'software', 'nvenc', 'qsv', 'amf')][string]$Backend = 'all',
    [Nullable[int]]$Device,
    [ValidateRange(1, 60)][int]$MaxMinutes = 15,
    [scriptblock]$RunNative = ${function:Invoke-WMMNative}
  )
  # Validate arguments and paths before creating a report or running any binary.
  $Backend = $Backend.ToLowerInvariant()
  $null = Get-WMMProbeCases @{ supported = $false; adapters = @() } $Backend $Device
  # PowerShell's location can differ from the process working directory.
  $base = (Get-Location).ProviderPath
  $app = [IO.Path]::GetFullPath($AppDir, $base).TrimEnd([IO.Path]::DirectorySeparatorChar)
  $output = [IO.Path]::GetFullPath($ReportDir, $base)
  if ($output.Equals($app, [StringComparison]::OrdinalIgnoreCase) -or
      $output.StartsWith($app + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'ReportDir must be outside the application directory'
  }
  if (Test-Path -LiteralPath $output) { throw 'ReportDir already exists; choose a new directory' }
  $files = @('WatchMyMedia.exe', 'watchmymedia-server.exe', 'ffmpeg.exe', 'ffprobe.exe', 'WinSparkle.dll')
  $hashes = foreach ($name in $files) {
    $path = Join-Path $app $name
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing packaged file: $name" }
    @{ name = $name; sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
  }
  $server = Join-Path $app 'watchmymedia-server.exe'
  $ffmpeg = Join-Path $app 'ffmpeg.exe'
  New-Item -ItemType Directory -Path $output | Out-Null
  $timer = [Diagnostics.Stopwatch]::StartNew()
  $limit = $MaxMinutes * 60000
  $results = [Collections.Generic.List[object]]::new()
  $report = [ordered]@{
    schemaVersion = 1; collectedAt = [DateTime]::UtcNow.ToString('o')
    source = @{ files = @($hashes); serverVersion = $null }
    desktopPlatform = $null; displayDrivers = $null; inventory = $null; probes = @()
    collectionComplete = $false; acceptanceComplete = $false
    manualChecks = @('Windows 11 desktop/menu/sign-in/DPI/theme', 'NVIDIA/Intel/AMD/hybrid driver coverage',
      'HDR/Dolby Vision picture quality and subtitles', 'Concurrent playback and software fallback in real sessions',
      'Local/UNC/mapped-drive access, reconnect and different-account UAC', 'Signed install, upgrade, tamper rejection and uninstall')
  }
  try {
    $v = & $RunNative $server @('version') 10000
    if ($v.timedOut -or $v.exitCode -ne 0 -or $v.stdout.Trim() -notmatch '^[\w.+-]{1,64}$') { throw 'Could not identify the packaged server version' }
    $report.source.serverVersion = $v.stdout.Trim()
    $platformFile = Join-Path $output 'platform.json'
    $p = & $RunNative (Join-Path $app 'WatchMyMedia.exe') @('-platform-report', $platformFile) 10000
    if ($p.timedOut -or $p.exitCode -notin @(0, 1633)) { throw 'Could not collect the desktop platform report' }
    $platform = Get-Content -LiteralPath $platformFile -Raw | ConvertFrom-Json -AsHashtable
    if ($platform.desktopSupported -isnot [bool] -or $platform.ciOnly -isnot [bool] -or !$platform.host) { throw 'Invalid desktop platform report' }
    $report.desktopPlatform = $platform
    # Local display-driver metadata has no CUDA/DXGI ordinals. Never join its
    # order to the native inventory. Bound CIM and the separate pwsh process.
    $driverCommand = '$ErrorActionPreference="Stop"; [Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); @(Get-CimInstance -ClassName Win32_VideoController -Property Name,DriverVersion,DriverDate -OperationTimeoutSec 5 | Select-Object Name,DriverVersion,DriverDate) | ConvertTo-Json -Depth 4 -Compress'
    $drivers = & $RunNative (Join-Path $PSHOME 'pwsh.exe') @('-NoProfile', '-NonInteractive', '-Command', $driverCommand) 15000
    $report.displayDrivers = @{ mappedToAdapterIndices = $false; items = @() }
    try {
      if ($drivers.timedOut -or $drivers.exitCode -ne 0) { throw 'Driver query failed' }
      $driverRows = @($drivers.stdout | ConvertFrom-Json -AsHashtable)
      if ($driverRows.Count -gt 64) { throw 'Too many display drivers' }
      $report.displayDrivers.items = @($driverRows | ForEach-Object {
        $row = @{}
        foreach ($key in @('Name', 'DriverVersion', 'DriverDate')) {
          $value = ([string]$_[$key] -replace '\p{Cc}', ' ').Trim()
          $row[$key] = $value.Substring(0, [Math]::Min(128, $value.Length))
        }
        $row
      })
    } catch { $report.displayDrivers['error'] = 'Local display-driver metadata could not be collected.' }
    $inventoryResult = & $RunNative $server @('gpu-adapters') 15000
    if ($inventoryResult.timedOut -or $inventoryResult.exitCode -ne 0) { throw 'Could not collect native GPU inventory' }
    $inventory = $inventoryResult.stdout | ConvertFrom-Json -AsHashtable
    if ($inventory.supported -ne $true -or $null -eq $inventory.adapters -or @($inventory.diagnostics).Count -ne 2) { throw 'Invalid native Windows GPU inventory' }
    $report.inventory = $inventory
    $inventoryKey = Get-WMMInventoryKey $inventory
    $cases = Get-WMMProbeCases $inventory $Backend $Device
    $index = 0
    foreach ($case in $cases) {
      $entry = [ordered]@{ backend = $case.backend; device = $case.device; namespace = $case.namespace; adapterName = $case.adapterName; outcome = 'not-run'; report = $null }
      $results.Add($entry)
      $remaining = $limit - $timer.ElapsedMilliseconds
      if ($remaining -le 0) { continue }
      $arguments = @('probe-transcoder', '-hwaccel', $case.backend, '-ffmpeg', $ffmpeg)
      if ($null -ne $case.device) { $arguments += @('-device', [string]$case.device) }
      $index++
      $native = & $RunNative $server $arguments ([int][Math]::Min(150000, $remaining))
      $stem = 'probe-{0:d3}-{1}' -f $index, $case.backend
      $native.stdout | Set-Content -LiteralPath (Join-Path $output ($stem + '.json')) -Encoding utf8
      $native.stderr | Set-Content -LiteralPath (Join-Path $output ($stem + '.stderr.txt')) -Encoding utf8
      $entry.outcome = if ($native.timedOut) { 'timeout' } else { 'error' }
      try {
        $probe = $native.stdout | ConvertFrom-Json -AsHashtable
        $entry.report = $probe
        $entry.outcome = Get-WMMProbeOutcome $case $probe $native.exitCode $native.timedOut
        if ((Get-WMMInventoryKey $probe['hardwareAdapters']) -ne $inventoryKey) {
          $entry.outcome = 'inventory-changed'
        }
      } catch {
        $entry.outcome = if ($native.timedOut) { 'timeout' } else { 'error' }
        $entry['error'] = 'The diagnostic command did not return a valid probe report.'
      }
    }
    $report.collectionComplete = @($results | Where-Object { $_.outcome -in @('error', 'timeout', 'not-run', 'inventory-changed') }).Count -eq 0
    foreach ($file in $hashes) {
      if ((Get-FileHash -LiteralPath (Join-Path $app $file.name) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $file.sha256) {
        $report.collectionComplete = $false
        $report.source['changedDuringCollection'] = $true
      }
    }
  } catch {
    $report.collectionComplete = $false
    $report['error'] = $_.Exception.Message
  }
  finally {
    $timer.Stop()
    $report['durationMs'] = $timer.ElapsedMilliseconds
    $report.probes = $results.ToArray()
    $report | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath (Join-Path $output 'report.json') -Encoding utf8
  }
  return $report
}
