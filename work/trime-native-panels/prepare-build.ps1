[CmdletBinding()]
param([string[]]$Tasks = @('help'))

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'Install JDK 17 and set JAVA_HOME before building.'
}
if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT -and
    -not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'local.properties'))) {
    throw 'Set ANDROID_HOME to the Android SDK directory, or create local.properties with Android Studio.'
}

$javaProxy = @()
if ($env:OS -eq 'Windows_NT') {
    $settings = Get-ItemProperty 'HKCU:/Software/Microsoft/Windows/CurrentVersion/Internet Settings' -ErrorAction SilentlyContinue
    if ($settings.ProxyEnable -eq 1 -and $settings.ProxyServer -match '^([a-zA-Z0-9.-]+):(\d+)$') {
        $proxyHost = $Matches[1]
        $proxyPort = $Matches[2]
        $javaProxy = @("-Dhttp.proxyHost=$proxyHost", "-Dhttp.proxyPort=$proxyPort",
            "-Dhttps.proxyHost=$proxyHost", "-Dhttps.proxyPort=$proxyPort")
    }
}
$savedJavaOpts = $env:JAVA_OPTS
Push-Location $PSScriptRoot
try {
    $env:JAVA_OPTS = (@($savedJavaOpts) + $javaProxy | Where-Object { $_ }) -join ' '
    if ($env:OS -eq 'Windows_NT') {
        & ./gradlew.bat @Tasks --console=plain --no-configuration-cache @javaProxy
    } else {
        & ./gradlew @Tasks --console=plain --no-configuration-cache @javaProxy
    }
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE." }
} finally {
    $env:JAVA_OPTS = $savedJavaOpts
    Pop-Location
}
