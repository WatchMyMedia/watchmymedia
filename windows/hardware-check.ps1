# Local synthetic probes only. This does not start the app/server or change Settings.
# PowerShell 7: pwsh -File windows/hardware-check.ps1 -AppDir build/windows-app
# Exit 0 means the report was collected, not that Windows release acceptance passed.
#Requires -Version 7.0
param(
  [Parameter(Mandatory)][string]$AppDir,
  [string]$ReportDir = (Join-Path (Get-Location) ('build/windows-hardware-' + [guid]::NewGuid().ToString('N'))),
  [ValidateSet('all', 'software', 'nvenc', 'qsv', 'amf')][string]$Backend = 'all',
  [Nullable[int]]$Device,
  [ValidateRange(1, 60)][int]$MaxMinutes = 15
)
$ErrorActionPreference = 'Stop'
if (!$IsWindows) { throw 'Hardware collection requires Windows; run the helper tests on other platforms.' }
. (Join-Path $PSScriptRoot 'hardware-check-lib.ps1')
$options = @{ AppDir = $AppDir; ReportDir = $ReportDir; Backend = $Backend; MaxMinutes = $MaxMinutes }
if ($PSBoundParameters.ContainsKey('Device')) { $options.Device = $Device }
$report = Invoke-WMMHardwareCheck @options
Write-Output "Report: $ReportDir/report.json"
Write-Output 'Release acceptance is incomplete. Inspect each probe outcome and complete the manual checklist.'
if (!$report.collectionComplete) { exit 1 }
exit 0
