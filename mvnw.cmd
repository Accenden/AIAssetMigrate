<# : batch portion
@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM    http://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM ----------------------------------------------------------------------------

@REM ----------------------------------------------------------------------------
@REM Apache Maven Wrapper startup batch script, version 3.3.2
@REM
@REM Optional ENV vars
@REM   MVNW_REPOURL - repo url base for downloading maven distribution
@REM   MVNW_USERNAME/MVNW_PASSWORD - user and password for downloading maven
@REM   MVNW_VERBOSE - true: enable verbose log; others: silence the output
@REM ----------------------------------------------------------------------------

@IF "%__MVNW_ARG0_NAME__%"=="" (SET __MVNW_ARG0_NAME__=%~nx0)
@SET __MVNW_CMD__=
@SET __MVNW_ERROR__=
@SET __MVNW_PSMODULEP_SAVE=%PSModulePath%
@SET PSModulePath=
@FOR /F "usebackq tokens=1* delims==" %%A IN (`powershell -noprofile "& {$scriptDir='%~dp0'; $script='%__MVNW_ARG0_NAME__%'; icm -ScriptBlock ([Scriptblock]::Create((Get-Content -Raw '%~f0'))) -NoNewScope}"`) DO @(
  IF "%%A"=="MVN_CMD" (set __MVNW_CMD__=%%B) ELSE IF "%%A"=="MVNW_JAVA_HOME" (set "JAVA_HOME=%%B") ELSE IF "%%B"=="" (echo %%A) ELSE (echo %%A=%%B)
)
@SET PSModulePath=%__MVNW_PSMODULEP_SAVE%
@SET __MVNW_PSMODULEP_SAVE=
@SET __MVNW_ARG0_NAME__=
@SET MVNW_USERNAME=
@SET MVNW_PASSWORD=
@IF NOT "%__MVNW_CMD__%"=="" (%__MVNW_CMD__% %*)
@echo Cannot start maven from wrapper >&2 && exit /b 1
@GOTO :EOF
: end batch / begin powershell #>

$ErrorActionPreference = "Stop"
if ($env:MVNW_VERBOSE -eq "true") {
  $VerbosePreference = "Continue"
}

# AIAssetMigrate addition: make sure Maven runs on a JDK 21 or newer without any setup.
# Uses JAVA_HOME or the java on PATH when either is a JDK 21+; otherwise uses (or downloads once)
# Eclipse Temurin 21 into ~/.m2/wrapper/jdks, verified against Adoptium's published SHA-256.
# Progress goes to stderr; the chosen JDK is passed to the batch portion as MVNW_JAVA_HOME.
$MVNW_MIN_JAVA = 21
function Get-JdkMajor([string]$jdkHome) {
  if (!$jdkHome) { return 0 }
  $release = Join-Path $jdkHome 'release'
  if (!(Test-Path -LiteralPath $release -PathType Leaf)) { return 0 }
  $match = [regex]::Match([IO.File]::ReadAllText($release), 'JAVA_VERSION="(\d+)')
  if ($match.Success) { return [int]$match.Groups[1].Value }
  return 0
}
$javaOnPath = Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
$pathJdkHome = if ($javaOnPath) { Split-Path (Split-Path $javaOnPath.Source -Parent) -Parent } else { $null }
if ((Get-JdkMajor $env:JAVA_HOME) -ge $MVNW_MIN_JAVA) {
  Write-Verbose "using JAVA_HOME $env:JAVA_HOME"
} elseif ((Get-JdkMajor $pathJdkHome) -ge $MVNW_MIN_JAVA) {
  Write-Verbose "using the JDK on PATH at $pathJdkHome"
  if ($env:JAVA_HOME) { Write-Output "MVNW_JAVA_HOME=$pathJdkHome" }
} else {
  $jdkRoot = if ($env:MAVEN_USER_HOME) { "$env:MAVEN_USER_HOME/wrapper/jdks" } else { "$HOME/.m2/wrapper/jdks" }
  $jdk = Get-ChildItem -LiteralPath $jdkRoot -Directory -ErrorAction SilentlyContinue |
    Where-Object { (Get-JdkMajor $_.FullName) -ge $MVNW_MIN_JAVA -and (Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe')) } |
    Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
  if (!$jdk) {
    $osArch = if ($env:PROCESSOR_ARCHITEW6432) { $env:PROCESSOR_ARCHITEW6432 } else { $env:PROCESSOR_ARCHITECTURE }
    $arch = switch ($osArch) { 'AMD64' { 'x64' } 'ARM64' { 'aarch64' } default { '' } }
    if (!$arch) { Write-Error "No JDK $MVNW_MIN_JAVA+ found and no download for '$osArch' Windows. Install a JDK $MVNW_MIN_JAVA+ and set JAVA_HOME." }
    [Console]::Error.WriteLine("No JDK $MVNW_MIN_JAVA or newer found. Downloading Eclipse Temurin $MVNW_MIN_JAVA once (about 200 MB) into $jdkRoot ...")
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $asset = @(Invoke-RestMethod -UseBasicParsing -Uri "https://api.adoptium.net/v3/assets/latest/$MVNW_MIN_JAVA/hotspot?architecture=$arch&image_type=jdk&os=windows&vendor=eclipse") | Select-Object -First 1
    if (!$asset) { Write-Error "Adoptium lists no JDK $MVNW_MIN_JAVA for Windows $arch. Install a JDK $MVNW_MIN_JAVA+ and set JAVA_HOME." }
    $package = $asset.binary.package
    $jdkTmp = New-Item -ItemType Directory -Path (Join-Path ([IO.Path]::GetTempPath()) ("mvnw-jdk-" + [Guid]::NewGuid()))
    try {
      $zip = Join-Path $jdkTmp $package.name
      (New-Object System.Net.WebClient).DownloadFile($package.link, $zip)
      $stream = [IO.File]::OpenRead($zip)
      try { $hash = ([Security.Cryptography.SHA256]::Create().ComputeHash($stream) | ForEach-Object { $_.ToString("x2") }) -join '' }
      finally { $stream.Dispose() }
      if ($hash -ne $package.checksum.ToLower()) { Write-Error "The downloaded JDK failed its SHA-256 check; nothing was installed." }
      Add-Type -AssemblyName System.IO.Compression.FileSystem
      [IO.Compression.ZipFile]::ExtractToDirectory($zip, (Join-Path $jdkTmp 'jdk'))
      $extracted = Get-ChildItem -LiteralPath (Join-Path $jdkTmp 'jdk') -Directory | Select-Object -First 1
      New-Item -ItemType Directory -Force -Path $jdkRoot | Out-Null
      $jdk = Join-Path $jdkRoot $extracted.Name
      if (!(Test-Path -LiteralPath $jdk)) { Move-Item -LiteralPath $extracted.FullName -Destination $jdk }
    } finally {
      Remove-Item -LiteralPath $jdkTmp -Recurse -Force -ErrorAction SilentlyContinue
    }
    [Console]::Error.WriteLine("Installed $($asset.release_name) at $jdk")
  }
  Write-Verbose "using downloaded JDK $jdk"
  Write-Output "MVNW_JAVA_HOME=$jdk"
}

# calculate distributionUrl, requires .mvn/wrapper/maven-wrapper.properties
$distributionUrl = (Get-Content -Raw "$scriptDir/.mvn/wrapper/maven-wrapper.properties" | ConvertFrom-StringData).distributionUrl
if (!$distributionUrl) {
  Write-Error "cannot read distributionUrl property in $scriptDir/.mvn/wrapper/maven-wrapper.properties"
}

switch -wildcard -casesensitive ( $($distributionUrl -replace '^.*/','') ) {
  "maven-mvnd-*" {
    $USE_MVND = $true
    $distributionUrl = $distributionUrl -replace '-bin\.[^.]*$',"-windows-amd64.zip"
    $MVN_CMD = "mvnd.cmd"
    break
  }
  default {
    $USE_MVND = $false
    $MVN_CMD = $script -replace '^mvnw','mvn'
    break
  }
}

# apply MVNW_REPOURL and calculate MAVEN_HOME
# maven home pattern: ~/.m2/wrapper/dists/{apache-maven-<version>,maven-mvnd-<version>-<platform>}/<hash>
if ($env:MVNW_REPOURL) {
  $MVNW_REPO_PATTERN = if ($USE_MVND) { "/org/apache/maven/" } else { "/maven/mvnd/" }
  $distributionUrl = "$env:MVNW_REPOURL$MVNW_REPO_PATTERN$($distributionUrl -replace '^.*'+$MVNW_REPO_PATTERN,'')"
}
$distributionUrlName = $distributionUrl -replace '^.*/',''
$distributionUrlNameMain = $distributionUrlName -replace '\.[^.]*$','' -replace '-bin$',''
$MAVEN_HOME_PARENT = "$HOME/.m2/wrapper/dists/$distributionUrlNameMain"
if ($env:MAVEN_USER_HOME) {
  $MAVEN_HOME_PARENT = "$env:MAVEN_USER_HOME/wrapper/dists/$distributionUrlNameMain"
}
$MAVEN_HOME_NAME = ([System.Security.Cryptography.MD5]::Create().ComputeHash([byte[]][char[]]$distributionUrl) | ForEach-Object {$_.ToString("x2")}) -join ''
$MAVEN_HOME = "$MAVEN_HOME_PARENT/$MAVEN_HOME_NAME"

if (Test-Path -Path "$MAVEN_HOME" -PathType Container) {
  Write-Verbose "found existing MAVEN_HOME at $MAVEN_HOME"
  Write-Output "MVN_CMD=$MAVEN_HOME/bin/$MVN_CMD"
  exit $?
}

if (! $distributionUrlNameMain -or ($distributionUrlName -eq $distributionUrlNameMain)) {
  Write-Error "distributionUrl is not valid, must end with *-bin.zip, but found $distributionUrl"
}

# prepare tmp dir
$TMP_DOWNLOAD_DIR_HOLDER = New-TemporaryFile
$TMP_DOWNLOAD_DIR = New-Item -Itemtype Directory -Path "$TMP_DOWNLOAD_DIR_HOLDER.dir"
$TMP_DOWNLOAD_DIR_HOLDER.Delete() | Out-Null
trap {
  if ($TMP_DOWNLOAD_DIR.Exists) {
    try { Remove-Item $TMP_DOWNLOAD_DIR -Recurse -Force | Out-Null }
    catch { Write-Warning "Cannot remove $TMP_DOWNLOAD_DIR" }
  }
}

New-Item -Itemtype Directory -Path "$MAVEN_HOME_PARENT" -Force | Out-Null

# Download and Install Apache Maven
Write-Verbose "Couldn't find MAVEN_HOME, downloading and installing it ..."
Write-Verbose "Downloading from: $distributionUrl"
Write-Verbose "Downloading to: $TMP_DOWNLOAD_DIR/$distributionUrlName"

$webclient = New-Object System.Net.WebClient
if ($env:MVNW_USERNAME -and $env:MVNW_PASSWORD) {
  $webclient.Credentials = New-Object System.Net.NetworkCredential($env:MVNW_USERNAME, $env:MVNW_PASSWORD)
}
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$webclient.DownloadFile($distributionUrl, "$TMP_DOWNLOAD_DIR/$distributionUrlName") | Out-Null

# If specified, validate the SHA-256 sum of the Maven distribution zip file
$distributionSha256Sum = (Get-Content -Raw "$scriptDir/.mvn/wrapper/maven-wrapper.properties" | ConvertFrom-StringData).distributionSha256Sum
if ($distributionSha256Sum) {
  if ($USE_MVND) {
    Write-Error "Checksum validation is not supported for maven-mvnd. `nPlease disable validation by removing 'distributionSha256Sum' from your maven-wrapper.properties."
  }
  Import-Module $PSHOME\Modules\Microsoft.PowerShell.Utility -Function Get-FileHash
  if ((Get-FileHash "$TMP_DOWNLOAD_DIR/$distributionUrlName" -Algorithm SHA256).Hash.ToLower() -ne $distributionSha256Sum) {
    Write-Error "Error: Failed to validate Maven distribution SHA-256, your Maven distribution might be compromised. If you updated your Maven version, you need to update the specified distributionSha256Sum property."
  }
}

# unzip and move
Expand-Archive "$TMP_DOWNLOAD_DIR/$distributionUrlName" -DestinationPath "$TMP_DOWNLOAD_DIR" | Out-Null
Rename-Item -Path "$TMP_DOWNLOAD_DIR/$distributionUrlNameMain" -NewName $MAVEN_HOME_NAME | Out-Null
try {
  Move-Item -Path "$TMP_DOWNLOAD_DIR/$MAVEN_HOME_NAME" -Destination $MAVEN_HOME_PARENT | Out-Null
} catch {
  if (! (Test-Path -Path "$MAVEN_HOME" -PathType Container)) {
    Write-Error "fail to move MAVEN_HOME"
  }
} finally {
  try { Remove-Item $TMP_DOWNLOAD_DIR -Recurse -Force | Out-Null }
  catch { Write-Warning "Cannot remove $TMP_DOWNLOAD_DIR" }
}

Write-Output "MVN_CMD=$MAVEN_HOME/bin/$MVN_CMD"
