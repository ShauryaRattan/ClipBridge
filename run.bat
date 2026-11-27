@echo off
rem Starts ClipBridge from the jar (after build.bat has made it). No console window.
cd /d "%~dp0"
if not exist build\clipbridge.jar (
  echo build\clipbridge.jar is missing. Run build.bat first.
  pause
  exit /b 1
)
start "" javaw -jar build\clipbridge.jar
