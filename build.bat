@echo off
REM build.bat -- for people who would rather not open an IDE.
REM
REM This just calls the Gradle wrapper, which is the real build. Everything lands in build\dist\.
REM If you use IntelliJ, ignore this file: open the folder and press Run.

setlocal
cd /d "%~dp0"

if "%~1"=="" (
  call gradlew.bat dist
) else (
  call gradlew.bat %*
)

if errorlevel 1 (
  echo.
  echo Build failed -- the error is above.
  exit /b 1
)

echo.
echo done -- everything is in build\dist\
echo   1. start OSRS and log in
echo   2. run build\dist\0xClient.exe and press the button
echo.
echo   (or just: gradlew run)
endlocal
