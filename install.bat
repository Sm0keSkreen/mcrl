@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem mcrl installer/uninstaller. Download and double-click this file, don't
rem paste anything into Win+R, a pasted download-and-run one-liner is exactly
rem the shape of the "ClickFix" malware technique and gets flagged by
rem Defender for that reason alone, regardless of payload. A saved file you
rem can open, read, and run avoids that pattern entirely.

set "JAR_URL=https://raw.githubusercontent.com/Dylanthedabber/mcrl/master/mcrl.jar"

set "BLINK=0"
:banner_loop
set /a BLINK+=1
cls
echo.
echo       /\_/\
echo      ( o.o )   mcrl, chat restrictions lifted
echo       (")(")
echo.
timeout /t 1 /nobreak >nul
cls
echo.
echo       /\_/\
echo      ( -.- )   mcrl, chat restrictions lifted
echo       (")(")
echo.
timeout /t 1 /nobreak >nul
if %BLINK% LSS 2 goto :banner_loop
cls
echo.
echo       /\_/\
echo      ( o.o )   mcrl, chat restrictions lifted
echo       (")(")
echo.

echo What would you like to do?
echo   [1] Install (default)
echo   [2] Uninstall
set /p "CHOICE=Choose 1 or 2: "
if "%CHOICE%"=="2" goto :uninstall
goto :install

:install
set "DEFAULT_DIR=%LOCALAPPDATA%\Mcrl"
set /p "INSTALL_DIR=Install folder (Enter for default: %DEFAULT_DIR%): "
if "%INSTALL_DIR%"=="" set "INSTALL_DIR=%DEFAULT_DIR%"

if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
set "JAR_PATH=%INSTALL_DIR%\mcrl.jar"

echo.
echo Fetching mcrl.jar into %JAR_PATH% ...
where curl >nul 2>nul
if %ERRORLEVEL%==0 (
    curl -L -o "%JAR_PATH%" "%JAR_URL%"
) else (
    powershell -NoProfile -Command "Invoke-WebRequest -Uri '%JAR_URL%' -OutFile '%JAR_PATH%'"
)

if not exist "%JAR_PATH%" (
    echo.
    echo Download failed, mcrl.jar isn't at %JAR_PATH%.
    goto :end
)

powershell -NoProfile -Command "[Environment]::SetEnvironmentVariable('JDK_JAVA_OPTIONS', '-javaagent:%JAR_PATH%', 'User')"

echo.
echo Installed. JDK_JAVA_OPTIONS now points at %JAR_PATH%
echo Close every Minecraft launcher window (official launcher, PrismLauncher,
echo CurseForge, whatever) and reopen. Purrs.
goto :end

:uninstall
echo.
echo Looking for an existing install...
set "CURRENT="
for /f "usebackq delims=" %%A in (`powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('JDK_JAVA_OPTIONS','User')"`) do set "CURRENT=%%A"

echo "%CURRENT%" | findstr /i "mcrl.jar" >nul
if errorlevel 1 (
    echo Didn't find an mcrl install, JDK_JAVA_OPTIONS isn't pointed at an mcrl.jar.
    echo Nothing to do.
    goto :end
)

set "JAR_PATH=%CURRENT:-javaagent:=%"
powershell -NoProfile -Command "[Environment]::SetEnvironmentVariable('JDK_JAVA_OPTIONS', $null, 'User')"
echo Removed the JDK_JAVA_OPTIONS environment variable.

for %%F in ("%JAR_PATH%") do set "INSTALL_DIR=%%~dpF"
if exist "%INSTALL_DIR%" (
    set /p "REMOVE=Also delete %INSTALL_DIR% ? (y/N): "
    if /i "!REMOVE!"=="y" (
        rd /s /q "%INSTALL_DIR%"
        echo Deleted %INSTALL_DIR%.
    )
)

echo.
echo All done, meow. Close every Minecraft launcher window and reopen.
goto :end

:end
echo.
pause
endlocal
