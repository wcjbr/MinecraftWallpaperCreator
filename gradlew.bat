@echo off
setlocal

set APP_HOME=%~dp0
set PROPERTIES_FILE=%APP_HOME%gradle\wrapper\gradle-wrapper.properties

if not exist "%PROPERTIES_FILE%" (
  echo Missing %PROPERTIES_FILE%
  exit /b 1
)

for /f "tokens=1,* delims==" %%A in ('findstr /b distributionUrl "%PROPERTIES_FILE%"') do set DIST_URL=%%B
set DIST_URL=%DIST_URL:\:=:%

if "%DIST_URL%"=="" (
  echo Missing distributionUrl in %PROPERTIES_FILE%
  exit /b 1
)

for %%F in ("%DIST_URL%") do set DIST_ZIP=%%~nxF
set DIST_NAME=%DIST_ZIP:.zip=%

if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%APP_HOME%.gradle
set DIST_ROOT=%GRADLE_USER_HOME%\wrapper\dists\%DIST_NAME%
set INSTALL_ROOT=%DIST_ROOT%\%DIST_NAME%
set ZIP_PATH=%DIST_ROOT%\%DIST_ZIP%

if not exist "%INSTALL_ROOT%\bin\gradle.bat" (
  if not exist "%DIST_ROOT%" mkdir "%DIST_ROOT%"
  if not exist "%ZIP_PATH%" (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing '%DIST_URL%' -OutFile '%ZIP_PATH%'"
  )
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$tmp='%DIST_ROOT%\.extract-%DIST_NAME%';" ^
    "if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp };" ^
    "Expand-Archive -Path '%ZIP_PATH%' -DestinationPath $tmp -Force;" ^
    "$dir=(Get-ChildItem $tmp | Where-Object { $_.PSIsContainer } | Select-Object -First 1).FullName;" ^
    "if (Test-Path '%INSTALL_ROOT%') { Remove-Item -Recurse -Force '%INSTALL_ROOT%' };" ^
    "Move-Item $dir '%INSTALL_ROOT%';" ^
    "Remove-Item -Recurse -Force $tmp"
)

call "%INSTALL_ROOT%\bin\gradle.bat" %*
