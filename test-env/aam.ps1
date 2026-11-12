# Runs the AIAssetMigrate CLI from the current build: test-env\aam.cmd <command> [options]
# Uses JAVA_HOME when it is JDK 21+, otherwise %USERPROFILE%\.jdks\ms-21.0.9.
# Compiles first if target\classes or the dependency classpath is missing. After editing code,
# rebuild (for example .\mvnw-local.cmd -q -B compile) so the CLI does not run stale classes.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

$jdk = $env:JAVA_HOME
$release = if ($jdk) { Join-Path $jdk 'release' } else { $null }
if (-not $release -or -not (Test-Path $release) -or -not (Select-String -Path $release -Pattern 'JAVA_VERSION="(2[1-9]|[3-9]\d)' -Quiet)) {
    $jdk = Join-Path $env:USERPROFILE '.jdks\ms-21.0.9'
}
$java = Join-Path $jdk 'bin\java.exe'
if (-not (Test-Path $java)) {
    [Console]::Error.WriteLine("aam: no JDK 21+ found (set JAVA_HOME or install to $jdk).")
    exit 1
}

$classes = Join-Path $root 'target\classes'
$classpathFile = Join-Path $root 'target\cp.txt'
if (-not (Test-Path (Join-Path $classes 'io\github\aiassetmigrate\cli\AssetMigrateCli.class')) -or -not (Test-Path $classpathFile)) {
    $maven = Join-Path $root 'mvnw-local.cmd'
    if (-not (Test-Path $maven)) { $maven = Join-Path $root 'mvnw.cmd'; $env:JAVA_HOME = $jdk }
    Push-Location $root
    try { & $maven -q -B compile dependency:build-classpath '-Dmdep.outputFile=target/cp.txt' }
    finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$env:CLASSPATH = $classes + ';' + (Get-Content $classpathFile -Raw).Trim()
& $java io.github.aiassetmigrate.cli.AssetMigrateCli @args
exit $LASTEXITCODE
