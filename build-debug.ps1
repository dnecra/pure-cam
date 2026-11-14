#requires -Version 5.1
# If PowerShell blocks this file, either run build-debug.bat or use:
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\build-debug.ps1

$ErrorActionPreference = "Stop"

$projectRoot = $PSScriptRoot
Set-Location $projectRoot

# Resolve a JDK: Android Studio's bundled JBR first, then JAVA_HOME, then PATH.
$androidStudioJbr = "C:\Program Files\Android\Android Studio\jbr"
$javaHome = $null
if (Test-Path (Join-Path $androidStudioJbr "bin\java.exe")) {
    $javaHome = $androidStudioJbr
} elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $javaHome = $env:JAVA_HOME
} else {
    $java = Get-Command java -ErrorAction SilentlyContinue
    if ($java) {
        $javaHome = Split-Path (Split-Path $java.Source -Parent) -Parent
    }
}
if (-not $javaHome) {
    throw "Java 17+ was not found. Install Android Studio or set JAVA_HOME to a JDK 17+ directory."
}

$env:JAVA_HOME = $javaHome
$env:GRADLE_USER_HOME = Join-Path $projectRoot ".gradle"
$env:ANDROID_USER_HOME = Join-Path $projectRoot ".android"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

Write-Host "Building debug APK..."
Write-Host "  JAVA_HOME=$env:JAVA_HOME"
Write-Host "  Project=$projectRoot"
Write-Host ""

& (Join-Path $projectRoot "gradlew.bat") :app:assembleDebug @args
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "BUILD FAILED (Gradle exit code $LASTEXITCODE)"
    exit $LASTEXITCODE
}

$apk = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) {
    throw "Gradle succeeded but the APK was not found at $apk"
}

$size = (Get-Item $apk).Length
Write-Host ""
Write-Host "BUILD SUCCESSFUL"
Write-Host "  Debug APK: $apk"
Write-Host "  Size:      $size bytes"
