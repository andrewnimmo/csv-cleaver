@echo off
REM Builds an .msi on Windows.
REM
REM Requires JDK 25 (which ships jpackage), the Clojure CLI, Babashka, and the
REM WiX Toolset 3.x for .msi output. Change --type msi to --type exe below to
REM produce a plain installer without WiX.

setlocal enabledelayedexpansion
cd /d "%~dp0.."

for /f "usebackq delims=" %%i in (`bb -e "(:name (read-string (slurp \"resources/branding.edn\")))"`) do set APP_NAME=%%~i
for /f "usebackq delims=" %%i in (`bb -e "(:version (read-string (slurp \"resources/branding.edn\")))"`) do set APP_VERSION=%%~i
for /f "usebackq delims=" %%i in (`bb -e "(:vendor (read-string (slurp \"resources/branding.edn\")))"`) do set VENDOR=%%~i
for /f "usebackq delims=" %%i in (`bb -e "(:copyright (read-string (slurp \"resources/branding.edn\")))"`) do set COPYRIGHT=%%~i
for /f "usebackq delims=" %%i in (`bb -e "(get-in (read-string (slurp \"resources/branding.edn\")) [:icons :windows])"`) do set ICON=%%~i

set DIST_DIR=dist
set STAGE_DIR=target\jpackage-input

echo ==^> Building uberjar
call clojure -T:build uber || exit /b 1

echo ==^> Staging
if exist "%DIST_DIR%" rmdir /s /q "%DIST_DIR%"
if exist "%STAGE_DIR%" rmdir /s /q "%STAGE_DIR%"
mkdir "%DIST_DIR%"
mkdir "%STAGE_DIR%"
copy target\csv-cleaver-*.jar "%STAGE_DIR%\" >nul

for %%f in ("%STAGE_DIR%\*.jar") do set MAIN_JAR=%%~nxf

set ICON_OPT=
if exist "%ICON%" set ICON_OPT=--icon "%ICON%"

echo ==^> jpackage
jpackage ^
  --type msi ^
  --name "%APP_NAME%" ^
  --app-version "%APP_VERSION%" ^
  --vendor "%VENDOR%" ^
  --copyright "%COPYRIGHT%" ^
  --input "%STAGE_DIR%" ^
  --main-jar "%MAIN_JAR%" ^
  --main-class csv_cleaver.app ^
  --dest "%DIST_DIR%" ^
  --win-dir-chooser ^
  --win-menu ^
  --win-shortcut ^
  --java-options "--enable-native-access=ALL-UNNAMED" ^
  --java-options "-Dfile.encoding=UTF-8" ^
  %ICON_OPT% || exit /b 1

echo ==^> Done
dir "%DIST_DIR%"
