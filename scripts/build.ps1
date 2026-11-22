# Builds the sideloaded "full" APKs into dist\. Signing keys are named in keystore.properties
# (see docs/development.md, "Release signing").
#   -Play    also builds the Google Play bundle, signed with the upload key.
#   -GitHub  also builds the full version's release APK for GitHub, signed with the GitHub key,
#            as dist\Gallery-<version>.apk.
param([switch]$SkipChecks, [switch]$Play, [switch]$GitHub)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$javaCandidates = @(
    "$env:USERPROFILE\.jdks\jbr-21.0.11",
    $env:JAVA_HOME
)
$javaRuntime = $javaCandidates | Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\java.exe')) } | Select-Object -First 1
if (-not $javaRuntime) { throw 'Install JDK 21 and set JAVA_HOME to its directory.' }
$env:JAVA_HOME = $javaRuntime
$keysFile = Join-Path $projectRoot 'keystore.properties'
$keys = if (Test-Path $keysFile) { Get-Content $keysFile -Raw } else { '' }
if ($Play -and $keys -notmatch '(?m)^\s*storeFile\s*=') {
    throw 'keystore.properties has no upload key (storeFile ...). See docs/development.md, "Release signing"; an unsigned bundle cannot be uploaded to Google Play.'
}
if ($GitHub -and $keys -notmatch '(?m)^\s*githubStoreFile\s*=') {
    throw 'keystore.properties has no GitHub key (githubStoreFile ...). See docs/development.md, "Release signing".'
}
$version = (Select-String -Path (Join-Path $projectRoot 'app\build.gradle.kts') -Pattern 'versionName = "([^"]+)"').Matches[0].Groups[1].Value
Push-Location $projectRoot
try {
    $tasks = @(':app:assembleFullDebug', ':app:assembleFullOptimized')
    if ($Play) { $tasks += ':app:bundlePlayRelease' }
    if ($GitHub) { $tasks += ':app:assembleFullRelease' }
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
    if ($GitHub) {
        Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\full\release\app-full-release.apk') -Destination (Join-Path $projectRoot "dist\Gallery-$version.apk") -Force
        Write-Output "Built dist\Gallery-$version.apk for GitHub"
    }
} finally { Pop-Location }
