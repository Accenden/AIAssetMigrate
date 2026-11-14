# Creates (or resets) the local testing area: copies the committed sample projects from test-env\samples into
# test-env\local\sources and creates empty state and output folders. test-env\local is git-ignored; the samples
# themselves are never changed by testing.
# Usage: test-env\setup.cmd              (resets sources, state and output)
#        test-env\setup.cmd -KeepState   (keeps saved projects; resets sources and output)

param([switch]$KeepState)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$samples = Join-Path $here 'samples'
$local = Join-Path $here 'local'
$sources = Join-Path $local 'sources'
$state = Join-Path $local 'state'
$output = Join-Path $local 'output'

if (-not (Test-Path -LiteralPath (Join-Path $samples 'messy'))) { throw "The committed samples are missing: $samples" }

$reset = @($sources, $output)
if (-not $KeepState) { $reset += $state }
foreach ($dir in $reset) {
    if (Test-Path -LiteralPath $dir) { Remove-Item -LiteralPath $dir -Recurse -Force }
}
New-Item -ItemType Directory -Force -Path $sources, $state | Out-Null
foreach ($name in @('clean', 'messy', 'benchmark')) {
    Copy-Item -LiteralPath (Join-Path $samples $name) -Destination (Join-Path $sources $name) -Recurse
    New-Item -ItemType Directory -Force -Path (Join-Path $output $name) | Out-Null
}
New-Item -ItemType Directory -Force -Path (Join-Path $output 'demo') | Out-Null

Write-Host "Local test environment ready in $local"
Write-Host "  sources\clean   sources\messy   sources\benchmark   (copies of test-env\samples)"
Write-Host "  state           (log directory: state\logs)"
Write-Host "  output\clean    output\messy    output\benchmark    output\demo"
if ($KeepState) { Write-Host "State was kept; sources and outputs were reset." }
