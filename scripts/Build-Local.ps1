param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [switch]$UseWindowsTrust
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$portableRoot = Join-Path (Split-Path -Parent $projectRoot) '.tooling'
if (-not $PSBoundParameters.ContainsKey('JavaHome') -and (Test-Path (Join-Path $portableRoot 'jdk'))) {
    $JavaHome = (Get-ChildItem (Join-Path $portableRoot 'jdk') -Directory | Select-Object -First 1).FullName
}
if (-not $SdkRoot -and (Test-Path (Join-Path $portableRoot 'android-sdk'))) { $SdkRoot = Join-Path $portableRoot 'android-sdk' }
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin/java.exe'))) { throw 'Set JAVA_HOME to a Java 17 JDK or pass -JavaHome.' }
if (-not (Select-String -Path (Join-Path $JavaHome 'release') -Pattern '^JAVA_VERSION="17(?:\.|"|$)' -Quiet)) { throw 'This build requires Java 17. Pass -JavaHome with a Java 17 JDK.' }
if (-not $SdkRoot -or -not (Test-Path (Join-Path $SdkRoot 'platforms/android-35/android.jar'))) { throw 'Set ANDROID_HOME to an SDK with platform 35 or pass -SdkRoot.' }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $SdkRoot
if (Test-Path $portableRoot) { $env:GRADLE_USER_HOME = Join-Path $portableRoot 'gradle-home' }
if ($UseWindowsTrust) { $env:JAVA_TOOL_OPTIONS = ($env:JAVA_TOOL_OPTIONS + ' -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE').Trim() }
Push-Location $projectRoot
try { & .\gradlew.bat testDebugUnitTest lintDebug lintCompact assembleDebug assembleCompact assembleDebugAndroidTest --no-daemon; $buildExit = $LASTEXITCODE } finally { Pop-Location }
exit $buildExit
