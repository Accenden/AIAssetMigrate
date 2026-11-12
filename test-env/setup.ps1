# Rebuilds the manual testing environment: sample sources plus empty state and output folders.
# Everything it creates lives under test-env\ (sources, state, output) and is git-ignored.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File test-env\setup.ps1   (or test-env\setup.cmd)
param([switch]$KeepState)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$here = $PSScriptRoot
$sources = Join-Path $here 'sources'
$state = Join-Path $here 'state'
$output = Join-Path $here 'output'

# Reset only the generated folders inside test-env.
$reset = @($sources, $output)
if (-not $KeepState) { $reset += $state }
foreach ($dir in $reset) {
    if (Test-Path -LiteralPath $dir) { Remove-Item -LiteralPath $dir -Recurse -Force }
}
foreach ($dir in @($sources, $state, $output, "$output\clean", "$output\messy", "$output\demo")) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}

$utf8 = New-Object System.Text.UTF8Encoding $false

function Write-Text([string]$relative, [string[]]$lines) {
    $path = Join-Path $sources $relative
    New-Item -ItemType Directory -Force -Path (Split-Path $path -Parent) | Out-Null
    # OBJ/MTL must be UTF-8 without a BOM; LF endings like typical exporter output.
    [System.IO.File]::WriteAllText($path, (($lines -join "`n") + "`n"), $utf8)
}

function Write-Image([string]$relative, [int]$size, [string]$format, [scriptblock]$paint) {
    $path = Join-Path $sources $relative
    New-Item -ItemType Directory -Force -Path (Split-Path $path -Parent) | Out-Null
    $bitmap = New-Object System.Drawing.Bitmap $size, $size
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    & $paint $graphics $size
    $graphics.Dispose()
    $imageFormat = if ($format -eq 'jpg') { [System.Drawing.Imaging.ImageFormat]::Jpeg } else { [System.Drawing.Imaging.ImageFormat]::Png }
    $bitmap.Save($path, $imageFormat)
    $bitmap.Dispose()
}

function Brush([int]$r, [int]$g, [int]$b, [int]$a = 255) {
    New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb($a, $r, $g, $b))
}

function Checker($graphics, $size, $a, $b) {
    $cell = [int]($size / 8)
    for ($y = 0; $y -lt 8; $y++) { for ($x = 0; $x -lt 8; $x++) {
        $brush = if (($x + $y) % 2 -eq 0) { $a } else { $b }
        $graphics.FillRectangle($brush, $x * $cell, $y * $cell, $cell, $cell)
    } }
}

# A textured unit cube: positions, UVs and six quads, the shape exporters commonly produce.
function Cube-Obj([string]$name, [string]$mtllib, [string]$material) {
    $lines = @("# $name", "o $name")
    if ($mtllib) { $lines += "mtllib $mtllib" }
    $lines += @(
        'v -0.5 -0.5 0.5', 'v 0.5 -0.5 0.5', 'v 0.5 0.5 0.5', 'v -0.5 0.5 0.5',
        'v -0.5 -0.5 -0.5', 'v 0.5 -0.5 -0.5', 'v 0.5 0.5 -0.5', 'v -0.5 0.5 -0.5',
        'vt 0 0', 'vt 1 0', 'vt 1 1', 'vt 0 1')
    if ($material) { $lines += "usemtl $material" }
    $lines += @(
        'f 1/1 2/2 3/3 4/4', 'f 6/1 5/2 8/3 7/4', 'f 5/1 1/2 4/3 8/4',
        'f 2/1 6/2 7/3 3/4', 'f 4/1 3/2 7/3 8/4', 'f 5/1 6/2 2/3 1/4')
    return $lines
}

# ---- sources\clean: a project that should plan, approve, migrate and validate with no blockers ----
Write-Text 'clean\characters\hero.obj' (Cube-Obj 'Hero' 'hero.mtl' 'Skin')
Write-Text 'clean\characters\hero.mtl' @('newmtl Skin', 'Kd 1 1 1', 'map_Kd hero_diffuse.png')
Write-Image 'clean\characters\hero_diffuse.png' 64 'png' { param($g, $s) Checker $g $s (Brush 220 170 130) (Brush 160 110 80) }

Write-Text 'clean\props\crate.obj' (Cube-Obj 'Crate' 'crate.mtl' 'Wood')
Write-Text 'clean\props\crate.mtl' @('newmtl Wood', 'Kd 1 1 1', 'map_Kd crate_albedo.jpg')
Write-Image 'clean\props\crate_albedo.jpg' 64 'jpg' { param($g, $s) Checker $g $s (Brush 150 100 50) (Brush 110 70 35) }

Write-Image 'clean\ui\menu_icon.png' 32 'png' { param($g, $s)
    $g.Clear([System.Drawing.Color]::Transparent)
    $g.FillEllipse((Brush 80 160 255), 2, 2, $s - 4, $s - 4) }

# ---- sources\messy: each file exercises a specific finding (see test-env\README.md) ----
# Case-mismatched material library: the OBJ asks for Hero.mtl, the file is hero.mtl.
Write-Text 'messy\characters\Hero Model FINAL.obj' (Cube-Obj 'HeroModelFinal' 'Hero.mtl' 'Skin')
Write-Text 'messy\characters\hero.mtl' @('newmtl Skin', 'Kd 1 1 1', 'map_Kd hero_diffuse.png')
Write-Image 'messy\characters\hero_diffuse.png' 64 'png' { param($g, $s) Checker $g $s (Brush 220 170 130) (Brush 160 110 80) }

# Broken texture path: sword.mtl expects sword_tex.png next to it; it actually lives in textures\.
Write-Text 'messy\weapons\sword.obj' (Cube-Obj 'Sword' 'sword.mtl' 'Steel')
Write-Text 'messy\weapons\sword.mtl' @('newmtl Steel', 'Kd 0.8 0.8 0.85', 'map_Kd sword_tex.png')
Write-Image 'messy\textures\sword_tex.png' 64 'png' { param($g, $s) Checker $g $s (Brush 200 200 210) (Brush 120 120 130) }

# Conflicting category words (props + weapon) and an untextured model.
Write-Text 'messy\props\weapon_rack.obj' (Cube-Obj 'WeaponRack' $null $null)

# FBX cannot be inspected: needs an ACKNOWLEDGMENT review (or exclusion) before planning.
$fbx = [byte[]](@([System.Text.Encoding]::ASCII.GetBytes('Kaydara FBX Binary  ')) + @(0x00, 0x1A, 0x00, 0xE8, 0x1C, 0x00, 0x00))
New-Item -ItemType Directory -Force -Path (Join-Path $sources 'messy\props') | Out-Null
[System.IO.File]::WriteAllBytes((Join-Path $sources 'messy\props\crate_01.fbx'), $fbx)

# Unreferenced texture whose name starts with a model name: a NAME_RELATIONSHIP_CANDIDATE.
Write-Text 'messy\environment\rock.obj' (Cube-Obj 'Rock' $null $null)
Write-Image 'messy\environment\rock_albedo.jpg' 64 'jpg' { param($g, $s) Checker $g $s (Brush 120 120 115) (Brush 90 90 85) }

# Transparent texture: converting it to JPEG needs a background colour.
Write-Image 'messy\environment\grass_alpha.png' 64 'png' { param($g, $s)
    $g.Clear([System.Drawing.Color]::Transparent)
    $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255, 60, 170, 60)), 3
    for ($x = 6; $x -lt $s; $x += 9) { $g.DrawLine($pen, $x, $s, $x + 4, 10) }
    $pen.Dispose() }

# Poor names for the names/folders analysis and rename rules.
Write-Image 'messy\textures\IMG_0042.PNG' 32 'png' { param($g, $s) Checker $g $s (Brush 240 200 60) (Brush 200 150 30) }
Write-Image 'messy\ui\MainMenu Button.png' 32 'png' { param($g, $s)
    $g.Clear([System.Drawing.Color]::Transparent)
    $g.FillRectangle((Brush 70 70 80), 2, 8, $s - 4, $s - 16) }

# Unsupported and corrupted files: skipped/flagged, making the scan PARTIAL.
Write-Text 'messy\misc\notes.txt' @('Unsupported file type; the scanner should skip and flag it.')
$validPng = [System.IO.File]::ReadAllBytes((Join-Path $sources 'messy\textures\IMG_0042.PNG'))
New-Item -ItemType Directory -Force -Path (Join-Path $sources 'messy\misc') | Out-Null
[System.IO.File]::WriteAllBytes((Join-Path $sources 'messy\misc\broken.png'), $validPng[0..39])

Write-Host "Test environment ready in $here"
Write-Host "  sources\clean   sources\messy"
Write-Host "  state           (log directory: state\logs)"
Write-Host "  output\clean    output\messy    output\demo"
if ($KeepState) { Write-Host "State was kept; sources and outputs were regenerated." }
