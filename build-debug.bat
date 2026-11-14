@echo off
setlocal EnableExtensions
cd /d "%~dp0"

rem ---------------------------------------------------------------------------
rem  PureCam - debug build
rem
rem  Use this instead of build-debug.ps1 when PowerShell script execution is
rem  disabled (the default "Restricted" policy on many Windows machines).
rem  Batch files are not affected by PowerShell's execution policy.
rem
rem  Usage:
rem    build-debug.bat                 Build the debug APK
rem    build-debug.bat --stacktrace    Pass extra arguments to Gradle
rem ---------------------------------------------------------------------------

rem Prefer the JDK bundled with Android Studio.
set "ANDROID_STUDIO_JBR=%ProgramFiles%\Android\Android Studio\jbr"
if exist "%ANDROID_STUDIO_JBR%\bin\java.exe" set "JAVA_HOME=%ANDROID_STUDIO_JBR%"

rem Drop an invalid inherited JAVA_HOME so the fallbacks below can run.
if defined JAVA_HOME if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME="

rem Last resort: a java.exe already on PATH.
if not defined JAVA_HOME (
    where java >nul 2>nul
    if errorlevel 1 (
        echo ERROR: Java 17+ was not found.
        echo Install Android Studio or set JAVA_HOME to a JDK 17+ directory.
        exit /b 1
    )
    for /f "delims=" %%I in ('where java') do (
        if not defined JAVA_HOME set "JAVA_HOME=%%~dpI.."
    )
)

set "GRADLE_USER_HOME=%CD%\.gradle"
set "ANDROID_USER_HOME=%CD%\.android"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo Building debug APK...
echo   JAVA_HOME=%JAVA_HOME%
echo   Project=%CD%
echo.

call "%CD%\gradlew.bat" :app:assembleDebug %*
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo.
    echo BUILD FAILED ^(Gradle exit code %EXIT_CODE%^)
    exit /b %EXIT_CODE%
)

set "APK=%CD%\app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" (
    echo.
    echo BUILD FAILED: Gradle succeeded but the APK was not found at:
    echo   %APK%
    exit /b 1
)

for %%A in ("%APK%") do set "APK_SIZE=%%~zA"
echo.
echo BUILD SUCCESSFUL
echo   Debug APK: %APK%
echo   Size:      %APK_SIZE% bytes
exit /b 0
