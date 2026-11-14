$ErrorActionPreference = "Stop"

$androidStudioJbr = "C:\Program Files\Android\Android Studio\jbr"
if (-not (Test-Path (Join-Path $androidStudioJbr "bin\java.exe"))) {
    throw "Java 17+ was not found at '$androidStudioJbr'. Install Android Studio or update `$androidStudioJbr in this script."
}

$env:JAVA_HOME = $androidStudioJbr
$env:GRADLE_USER_HOME = Join-Path $PSScriptRoot ".gradle"
$env:ANDROID_USER_HOME = Join-Path $PSScriptRoot ".android"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

& (Join-Path $PSScriptRoot "gradlew.bat") :app:assembleDebug
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "Debug APK: $(Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\PureCam-debug.apk')"
