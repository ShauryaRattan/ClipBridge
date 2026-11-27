@echo off
rem ============================================================================
rem  ClipBridge - build script for Windows
rem
rem    build.bat              compile, run the tests, make the jar and the app folder
rem    build.bat skiptests    same, without the tests
rem    build.bat installer    also make ClipBridge-1.0.exe (needs the WiX Toolset, see README)
rem
rem  Needs a JDK 17 or newer (javac and jpackage). See README.md, section "Build".
rem ============================================================================
setlocal
cd /d "%~dp0"

where javac >nul 2>nul
if errorlevel 1 goto nojdk
where jpackage >nul 2>nul
if errorlevel 1 goto nojdk

if exist build rmdir /s /q build
if exist dist rmdir /s /q dist
mkdir build\classes build\testclasses build\input dist

echo.
echo [1/5] Compiling...
rem (-sourcepath makes javac find every class that Main needs, and avoids problems with spaces in folder names)
javac -encoding UTF-8 -d build\classes -sourcepath src src\clipbridge\Main.java
if errorlevel 1 goto failed
xcopy /y /q src\clipbridge\gui\*.png build\classes\clipbridge\gui\ >nul

if /i "%~1"=="skiptests" goto jar
echo.
echo [2/5] Running the tests (about one minute)...
javac -encoding UTF-8 -d build\testclasses -cp build\classes -sourcepath test test\clipbridge\AllTests.java
if errorlevel 1 goto failed
java -cp build\classes;build\testclasses clipbridge.AllTests
if errorlevel 1 echo.
if errorlevel 1 echo WARNING: some tests failed - read the lines above. The build continues anyway.

:jar
echo.
echo [3/5] Making build\clipbridge.jar...
jar --create --file build\clipbridge.jar --main-class clipbridge.Main -C build\classes .
if errorlevel 1 goto failed
copy /y build\clipbridge.jar build\input\ >nul

echo.
echo [4/5] Making the app folder dist\ClipBridge\ClipBridge.exe (includes its own Java, nothing else to install)...
jpackage --type app-image --name ClipBridge --input build\input --main-jar clipbridge.jar --main-class clipbridge.Main ^
  --icon assets\clipbridge.ico --app-version 1.0 --vendor "Shaurya Rattan" ^
  --description "Shared clipboard for your computers" --dest dist ^
  --add-modules java.base,java.desktop,java.logging
if errorlevel 1 goto failed

echo.
if /i "%~1"=="installer" goto installer
if /i "%~2"=="installer" goto installer
echo [5/5] Skipped the installer. Run  build.bat installer  or use installer\ClipBridge.iss with Inno Setup.
goto done

:installer
echo [5/5] Making the installer dist\ClipBridge-1.0.exe (needs the WiX Toolset)...
jpackage --type exe --name ClipBridge --input build\input --main-jar clipbridge.jar --main-class clipbridge.Main ^
  --icon assets\clipbridge.ico --app-version 1.0 --vendor "Shaurya Rattan" ^
  --description "Shared clipboard for your computers" --dest dist ^
  --add-modules java.base,java.desktop,java.logging ^
  --win-menu --win-menu-group ClipBridge --win-shortcut --win-dir-chooser --win-per-user-install
if errorlevel 1 goto wixproblem

:done
echo.
echo ===== Done. Look in the dist folder. =====
dir /b dist
pause
exit /b 0

:nojdk
echo.
echo A JDK 17 or newer is needed (javac and jpackage were not found).
echo Install one with:   winget install EclipseAdoptium.Temurin.21.JDK
echo Then close this window, open a new one, and run build.bat again.
pause
exit /b 1

:wixproblem
echo.
echo The installer could not be made. Most likely the WiX Toolset is not installed.
echo Either install it (see README.md, "Build") or use the Inno Setup script installer\ClipBridge.iss.
echo The app folder dist\ClipBridge\ClipBridge.exe was made and works without an installer.
pause
exit /b 1

:failed
echo.
echo ===== The build stopped because of the error above. =====
pause
exit /b 1
