# Builds the sideloaded "full" APKs into dist\. With -Play it also builds the Google Play bundle,
# signed with the upload key named in keystore.properties (see docs/development.md, "Two versions").
param([switch]$SkipChecks, [switch]$Play)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$javaCandidates = @(
    "$env:USERPROFILE\.jdks\jbr-21.0.11",
    $env:JAVA_HOME
)
$javaRuntime = $javaCandidates | Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\java.exe')) } | Select-Object -First 1
if (-not $javaRuntime) { throw 'Install JDK 21 and set JAVA_HOME to its directory.' }
$env:JAVA_HOME = $javaRuntime
if ($Play -and -not (Test-Path (Join-Path $projectRoot 'keystore.properties'))) {
    throw 'keystore.properties is missing. Create the upload key first (docs/development.md, "Release signing"); an unsigned bundle cannot be uploaded to Google Play.'
}
Push-Location $projectRoot
try {
    $tasks = @(':app:assembleFullDebug', ':app:assembleFullOptimized')
    if ($Play) { $tasks += ':app:bundlePlayRelease' }
    if (-not $SkipChecks) { $tasks += ':app:testFullDebugUnitTest', ':app:lintFullDebug' }
    & .\gradlew.bat @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android build or checks failed.' }
    New-Item -ItemType Directory -Path (Join-Path $projectRoot 'dist') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\full\debug\app-full-debug.apk') -Destination (Join-Path $projectRoot 'dist\Gallery-debug.apk') -Force
    Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\full\optimized\app-full-optimized.apk') -Destination (Join-Path $projectRoot 'dist\Gallery-optimized.apk') -Force
    Write-Output 'Built dist\Gallery-optimized.apk and dist\Gallery-debug.apk'
    if ($Play) {
        Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\outputs\bundle\playRelease\app-play-release.aab') -Destination (Join-Path $projectRoot 'dist\LocalGallery-play.aab') -Force
        Write-Output 'Built dist\LocalGallery-play.aab for Google Play'
    }
} finally { Pop-Location }
