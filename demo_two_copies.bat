@echo off
rem Demo on ONE computer: starts two separate copies of ClipBridge that talk to each other over the network
rem (127.0.0.1 and the automatic discovery), each with its own settings folder and its own "clipboard" file,
rem so they do not share the real Windows clipboard.
rem
rem  - In each window use Tools > Pairing code (the first one: Generate new; the second: type the same code).
rem  - Type text in "Send text" and press Enter; it appears in the other window's history.
rem  - Run  demo_two_copies.bat clean  afterwards to delete the demo files.
cd /d "%~dp0"
if /i "%~1"=="clean" (
  rmdir /s /q "%TEMP%\cb-demo-a" 2>nul
  rmdir /s /q "%TEMP%\cb-demo-b" 2>nul
  del "%TEMP%\cb-demo-a.txt" "%TEMP%\cb-demo-b.txt" 2>nul
  echo Demo files deleted.
  exit /b 0
)
if not exist build\clipbridge.jar (
  echo build\clipbridge.jar is missing. Run build.bat first.
  pause
  exit /b 1
)
start "" javaw -jar build\clipbridge.jar --name "Computer-A" --port 5005 --data-dir "%TEMP%\cb-demo-a" --clipboard-file "%TEMP%\cb-demo-a.txt"
start "" javaw -jar build\clipbridge.jar --name "Computer-B" --port 5006 --data-dir "%TEMP%\cb-demo-b" --clipboard-file "%TEMP%\cb-demo-b.txt"
