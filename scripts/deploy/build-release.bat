@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul

rem Wanjing Academy - Windows release builder.
rem Keep this file saved as UTF-8 without BOM on Windows 10/11.
rem Requires git, Node/npm, Java 17, Maven Wrapper, Windows PowerShell 5.1+.

rem All release artifacts use an ASCII-only, stable output path.
set "OUTPUT_ROOT=%WANJING_OUTPUT_ROOT%"
if not defined OUTPUT_ROOT set "OUTPUT_ROOT=D:\Deploy"
set "CONFIG_FILE=%OUTPUT_ROOT%\repo-path.txt"
if not exist "%OUTPUT_ROOT%\" (
  mkdir "%OUTPUT_ROOT%" || goto :failed
)

rem Project location is configurable: no fixed drive letter or Chinese path.
rem Priority: WANJING_REPO environment variable, saved repo-path.txt, first-run prompt.
set "REPO=%WANJING_REPO%"
if not defined REPO if exist "%CONFIG_FILE%" (
  <"%CONFIG_FILE%" set /p "REPO="
)
if not defined REPO goto :ask_repo
if not exist "%REPO%\.git" (
  echo [WARN] Saved repository not found: %REPO%
  goto :ask_repo
)
goto :repo_ready

:ask_repo
set "REPO="
echo [SETUP] Enter the local project Git repository folder one time.
echo [SETUP] Example: D:\Projects\tihaishitu
set /p "REPO=Repository full path: "
if not defined REPO goto :failed
if not exist "%REPO%\.git" (
  echo [ERROR] Git repository not found: %REPO%
  goto :failed
)
>"%CONFIG_FILE%" echo(%REPO%
echo [INFO] Saved repository path to %CONFIG_FILE%

:repo_ready
echo [INFO] Repository: %REPO%
echo [INFO] Release output root: %OUTPUT_ROOT%
where git >nul 2>&1 || (echo [ERROR] git is missing & goto :failed)
where npm >nul 2>&1 || (echo [ERROR] npm is missing & goto :failed)
where java >nul 2>&1 || (echo [ERROR] Java is missing & goto :failed)
where powershell >nul 2>&1 || (echo [ERROR] Windows PowerShell is missing & goto :failed)

pushd "%REPO%" || goto :failed

set "DIRTY="
for /f "delims=" %%L in ('git status --porcelain 2^>nul') do set "DIRTY=1"
if defined DIRTY (
  echo [ERROR] Working tree is not clean. Commit/stash changes before releasing.
  goto :failed_repo
)

git switch main || goto :failed_repo
git pull --ff-only origin main || goto :failed_repo

set "BRANCH="
for /f "delims=" %%L in ('git branch --show-current') do set "BRANCH=%%L"
if /i not "!BRANCH!"=="main" (
  echo [ERROR] Expected main branch, got !BRANCH!
  goto :failed_repo
)

set "DIRTY="
for /f "delims=" %%L in ('git status --porcelain 2^>nul') do set "DIRTY=1"
if defined DIRTY (
  echo [ERROR] Working tree is dirty after pull.
  goto :failed_repo
)

set "SHA="
set "SHORT_SHA="
for /f "delims=" %%L in ('git rev-parse HEAD') do set "SHA=%%L"
if not defined SHA goto :failed_repo
rem Avoid a second git rev-parse inside FOR /F; derive the release short SHA from the validated full SHA.
set "SHORT_SHA=!SHA:~0,7!"
if not defined SHORT_SHA goto :failed_repo

echo [INFO] Building main at !SHA!

echo [1/4] Installing npm dependencies...
call npm ci || goto :failed_repo

echo [2/4] Building frontend for same-origin production API...
set "VITE_API_MODE=http"
set "VITE_API_BASE_URL=/api/v1"
call npm run build || goto :failed_repo
if not exist "%REPO%\frontend\dist\index.html" (
  echo [ERROR] Missing frontend/dist/index.html
  goto :failed_repo
)
if not exist "%REPO%\frontend\dist\assets" (
  echo [ERROR] Missing frontend/dist/assets
  goto :failed_repo
)

echo [3/4] Packaging backend (tests are assumed to have passed in CI)...
pushd "%REPO%\backend" || goto :failed_repo
call mvnw.cmd -DskipTests clean package
if errorlevel 1 goto :failed_backend
popd

set "BACKEND_JAR=%REPO%\backend\target\tihaishitu-backend-0.1.0-SNAPSHOT.jar"
if not exist "!BACKEND_JAR!" (
  echo [ERROR] Missing Spring Boot packaged jar: !BACKEND_JAR!
  goto :failed_repo
)

rem One folder per release. Never silently reuse old release files.
set "STAMP="
for /f "delims=" %%L in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "STAMP=%%L"
if not defined STAMP goto :failed_repo
set "OUT_DIR=%OUTPUT_ROOT%\release-!SHORT_SHA!-!STAMP!"
if exist "!OUT_DIR!" (
  echo [ERROR] Release folder already exists: !OUT_DIR!
  goto :failed_repo
)
mkdir "!OUT_DIR!" || goto :failed_repo

echo [4/4] Creating upload files...
copy /Y "!BACKEND_JAR!" "!OUT_DIR!\tihaishitu-backend.jar" >nul || goto :failed_repo
set "FRONTEND_DIST=%REPO%\frontend\dist"
set "ZIP_FILE=!OUT_DIR!\frontend-dist.zip"
powershell -NoProfile -Command "$ErrorActionPreference='Stop'; Compress-Archive -Path (Join-Path $env:FRONTEND_DIST '*') -DestinationPath $env:ZIP_FILE -Force; if (-not (Test-Path -LiteralPath $env:ZIP_FILE)) { exit 1 }"
if errorlevel 1 goto :failed_repo

set "JAR_FILE=!OUT_DIR!\tihaishitu-backend.jar"
set "JAR_HASH="
set "ZIP_HASH="
for /f "delims=" %%H in ('powershell -NoProfile -Command "(Get-FileHash -LiteralPath $env:JAR_FILE -Algorithm SHA256).Hash.ToLowerInvariant()"') do set "JAR_HASH=%%H"
for /f "delims=" %%H in ('powershell -NoProfile -Command "(Get-FileHash -LiteralPath $env:ZIP_FILE -Algorithm SHA256).Hash.ToLowerInvariant()"') do set "ZIP_HASH=%%H"
if not defined JAR_HASH goto :failed_repo
if not defined ZIP_HASH goto :failed_repo

> "!OUT_DIR!\release.sha256" (
  echo !JAR_HASH! *tihaishitu-backend.jar
  echo !ZIP_HASH! *frontend-dist.zip
)
> "!OUT_DIR!\release.info" (
  echo COMMIT_SHA=!SHA!
  echo BRANCH=main
)

if not exist "!OUT_DIR!\release.sha256" goto :failed_repo
if not exist "!OUT_DIR!\release.info" goto :failed_repo

echo.
echo [SUCCESS] Files ready in:
echo !OUT_DIR!
echo.
echo Upload ONLY these four files to /usr/local/deploy/ on Linux:
echo    tihaishitu-backend.jar
echo    frontend-dist.zip
echo    release.sha256
echo    release.info
echo Then run: bash /usr/local/deploy/deploy-release.sh
echo.
echo [IMPORTANT] Before Linux deployment:
echo   1. Manually back up the LIVE database ^(schema + data + flyway_schema_history^).
echo   2. Confirm production QUESTION_IMAGE_DIR is configured and writable.
echo      deploy-release.sh will snapshot the image directory before stopping the old backend.
popd
pause
exit /b 0

:failed_backend
popd
:failed_repo
popd
:failed
echo.
echo [FAILED] Build stopped. Do not upload a partially-built package.
echo Copy the error output to your AI assistant for diagnosis.
pause
exit /b 1
