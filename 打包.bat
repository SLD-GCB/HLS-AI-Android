@echo off
rem ============================================================
rem  Honglusi  -  one-click build script
rem
rem  Double-click          -> build release APK (signed)
rem  this file with: debug -> build debug APK
rem  this file with: clean -> clean build outputs
rem
rem  NOTE: all text here is ASCII on purpose. Chinese text in a .bat
rem  gets garbled by cmd.exe (it reads the file as GBK), which turns
rem  the lines into bogus commands.
rem ============================================================
setlocal

set "PROJ=%~dp0"
if "%PROJ:~-1%"=="\" set "PROJ=%PROJ:~0,-1%"

rem ---- local paths (change these on another machine) ----
set "JAVA_HOME=D:\Android_Studio\jbr"
set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "PATH=%JAVA_HOME%\bin;%PATH%"

set "TASK=assembleRelease"
set "TAG=release"
set "APKNAME=app-release.apk"
if /I "%~1"=="debug" (
  set "TASK=assembleDebug"
  set "TAG=debug"
  set "APKNAME=app-debug.apk"
)
if /I "%~1"=="clean" set "TASK=clean"

echo.
echo ==== Honglusi build (%TASK%) ====
echo Project : %PROJ%
echo Java    : %JAVA_HOME%
echo SDK     : %ANDROID_HOME%
echo.

rem ninja cannot handle the Chinese project path, so map it to an ASCII drive
subst X: "%PROJ%" 2>nul
if not exist "X:\settings.gradle.kts" (
  echo [ERROR] cannot map drive X: . Run this once by hand:
  echo         subst X: "%PROJ%"
  pause
  exit /b 1
)

cd /d X:\
call gradlew.bat %TASK% --console=plain
if errorlevel 1 (
  echo.
  echo [FAILED] build did not pass - scroll up for the red lines.
  pause
  exit /b 1
)

if /I "%TASK%"=="clean" (
  echo [OK] build outputs cleaned.
  pause
  exit /b 0
)

set "OUT=%PROJ%\app\build\outputs\apk\%TAG%\%APKNAME%"
if not exist "%OUT%" (
  echo [ERROR] APK not found: %OUT%
  pause
  exit /b 1
)

set "RESULT=%PROJ%\honglusi-%TAG%.apk"
copy /y "%OUT%" "%RESULT%" >nul

echo.
echo ==== BUILD OK ====
echo APK : %RESULT%
echo Src : %OUT%
echo.
echo Install to phone:
echo   "%ANDROID_HOME%\platform-tools\adb.exe" install -r "%RESULT%"
echo.
start "" explorer /select,"%RESULT%"
pause
