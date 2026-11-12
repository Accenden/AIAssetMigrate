# Happy path, end to end, on sources\clean: import -> plan -> approve -> migrate (validates) -> history.
# Writes only to test-env\state and test-env\output\demo (emptied first). Run setup.ps1 once beforehand.
param([string]$Target = 'unity', [string]$TargetVersion = '6000.0')

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$source = Join-Path $here 'sources\clean'
$state = Join-Path $here 'state'
$logs = Join-Path $state 'logs'
$output = Join-Path $here 'output\demo'
if (-not (Test-Path $source)) { throw "Run test-env\setup.ps1 first." }
if (Test-Path $output) { Get-ChildItem -LiteralPath $output -Force | Remove-Item -Recurse -Force }
New-Item -ItemType Directory -Force -Path $output | Out-Null

function Step([string]$title, [string[]]$arguments, [int[]]$ok = @(0)) {
    Write-Host ""
    Write-Host "== $title" -ForegroundColor Cyan
    Write-Host "aam $($arguments -join ' ')" -ForegroundColor DarkGray
    # stderr goes straight to the console: in Windows PowerShell, redirecting a native command's
    # stderr under ErrorActionPreference=Stop would turn warnings into terminating errors.
    $text = & (Join-Path $here 'aam.ps1') @arguments | ForEach-Object { "$_" }
    $code = $LASTEXITCODE
    $text | ForEach-Object { Write-Host $_ }
    if ($ok -notcontains $code) { throw "$title failed with exit code $code" }
    return ($text -join "`n")
}

function Field([string]$text, [string]$name) {
    $match = [regex]::Match($text, "(?m)^$([regex]::Escape($name)): (\S+)")
    if (-not $match.Success) { throw "Could not find '$name' in the output." }
    return $match.Groups[1].Value
}

$common = @('--state-directory', $state, '--log-directory', $logs)

$imported = Step 'Import sources\clean' (@('import', $source) + $common)
$project = Field $imported 'Project ID'

$plan = Step 'Create draft plan' (@('plan', '--project', $project, '--target', $Target, '--target-version', $TargetVersion,
    '--output', $output) + $common)
$planId = Field $plan 'Plan ID'
$version = Field $plan 'Version'

Step 'Approve the displayed version' (@('approve', '--plan', $planId, '--version', $version) + $common) | Out-Null
Step 'Migrate (validates on completion)' (@('migrate', '--plan', $planId) + $common) | Out-Null
Step 'History' (@('history', '--project', $project) + $common) | Out-Null

Write-Host ""
Write-Host "Done. Project $project, plan $planId. Outputs in $output" -ForegroundColor Green
