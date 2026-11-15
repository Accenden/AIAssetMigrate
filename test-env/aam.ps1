# Runs the AIAssetMigrate CLI from the current build: test-env\aam.cmd <command> [options]
# Finds a JDK 21+ the same way mvnw.cmd does, so it needs no setup: JAVA_HOME, then the java on PATH,
# then the JDK mvnw.cmd keeps in ~\.m2\wrapper\jdks (running mvnw.cmd downloads one if there is none).
# Compiles first if target\classes or the dependency classpath is missing. After editing code,
# rebuild (for example .\mvnw.cmd -q -B compile) so the CLI does not run stale classes.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

function Test-Jdk21([string]$jdkHome) {
    if (-not $jdkHome) { return $false }
    $release = Join-Path $jdkHome 'release'
    (Test-Path (Join-Path $jdkHome 'bin\java.exe')) -and (Test-Path $release) -and
        (Select-String -Path $release -Pattern 'JAVA_VERSION="(2[1-9]|[3-9]\d)' -Quiet)
}
function Find-WrapperJdk {
    $jdkRoot = if ($env:MAVEN_USER_HOME) { Join-Path $env:MAVEN_USER_HOME 'wrapper\jdks' } else { Join-Path $HOME '.m2\wrapper\jdks' }
    Get-ChildItem -LiteralPath $jdkRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Jdk21 $_.FullName } | Sort-Object Name -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}

$jdk = $env:JAVA_HOME
if (-not (Test-Jdk21 $jdk)) {
    $onPath = Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    $jdk = if ($onPath) { Split-Path (Split-Path $onPath.Source -Parent) -Parent } else { $null }
}
if (-not (Test-Jdk21 $jdk)) { $jdk = Find-WrapperJdk }
if (-not $jdk) {
    # No JDK 21+ anywhere: let the Maven wrapper download one, then use it.
    & (Join-Path $root 'mvnw.cmd') -q -v | Out-Null
    $jdk = Find-WrapperJdk
}
if (-not $jdk) {
    [Console]::Error.WriteLine("aam: no JDK 21+ found and the download failed. Install a JDK 21+ and set JAVA_HOME.")
    exit 1
}
$java = Join-Path $jdk 'bin\java.exe'

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
