@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "JAR_URL=https://github.com/Sm0keSkreen/mcrl/releases/latest/download/mcrl.jar"

goto :main

:find_existing_jar
REM Sets JAR_PATH and JAR_PATH_FOUND=1 if an install is found, else JAR_PATH_FOUND=0.
set "JAR_PATH_FOUND=0"
set "CURRENT="
for /f "usebackq delims=" %%A in (`powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('JDK_JAVA_OPTIONS','User')"`) do set "CURRENT=%%A"
echo "%CURRENT%" | findstr /i "mcrl.jar" >nul
if errorlevel 1 goto :eof
set "JAR_PATH=%CURRENT:-javaagent:=%"
if "%JAR_PATH:~-7%"=="=extras" set "JAR_PATH=%JAR_PATH:~0,-7%"
set JAR_PATH=%JAR_PATH:"=%
set "JAR_PATH_FOUND=1"
goto :eof

:download_jar
REM %1 = target jar path
echo.
echo Fetching mcrl.jar into %~1 ...
where curl >nul 2>nul
if %ERRORLEVEL%==0 (
    curl -fL -o "%~1" "%JAR_URL%"
) else (
    powershell -NoProfile -Command "Invoke-WebRequest -Uri '%JAR_URL%' -OutFile '%~1'"
)
if not exist "%~1" (
    echo.
    echo Download failed, mcrl.jar isn't at %~1.
    exit /b 1
)
goto :eof

:prompt_and_write_config
REM %1 = target install directory; %2/%3/%4 = optional true|false overrides for
REM extras/telemetry/profanity that skip that one prompt, for non-interactive/scripted use.
if /i "%~2"=="true" (
    set "EXTRAS_BOOL=true"
) else if /i "%~2"=="false" (
    set "EXTRAS_BOOL=false"
) else (
    set "EXTRAS="
    set /p "EXTRAS=Also unlock Realms, the multiplayer server list, and friends where the account API supports it? (y/N): "
    if /i "!EXTRAS!"=="y" (set "EXTRAS_BOOL=true") else (set "EXTRAS_BOOL=false")
)

if /i "%~3"=="true" (
    set "TELEMETRY_BOOL=true"
) else if /i "%~3"=="false" (
    set "TELEMETRY_BOOL=false"
) else (
    set "TELEMETRY="
    set /p "TELEMETRY=Allow telemetry reporting to Mojang? (y/N): "
    if /i "!TELEMETRY!"=="y" (set "TELEMETRY_BOOL=true") else (set "TELEMETRY_BOOL=false")
)

if /i "%~4"=="true" (
    set "PROFANITY_BOOL=true"
) else if /i "%~4"=="false" (
    set "PROFANITY_BOOL=false"
) else (
    set "PROFANITY="
    set /p "PROFANITY=Allow the in-game chat profanity filter? (y/N): "
    if /i "!PROFANITY!"=="y" (set "PROFANITY_BOOL=true") else (set "PROFANITY_BOOL=false")
)

(
    echo {
    echo   "extras": %EXTRAS_BOOL%,
    echo   "allowTelemetry": %TELEMETRY_BOOL%,
    echo   "allowProfanityFilter": %PROFANITY_BOOL%
    echo }
) > "%~1\config.json"
echo Wrote %~1\config.json.
goto :eof

:main
echo.
echo mcrl, chat restrictions lifted
echo.

if /i "%~1"=="configure" goto :configure_only
if /i "%~1"=="status" goto :status_only
if /i "%~1"=="help" goto :show_help
if /i "%~1"=="/?" goto :show_help
if /i "%~1"=="--help" goto :show_help
if /i "%~1"=="-h" goto :show_help

echo What would you like to do?
echo   [1] Install (default)
echo   [2] Uninstall
echo   [3] Reconfigure (change Realms/telemetry/profanity choices)
echo   [4] Upgrade (re-download the jar, keep everything else)
echo   [5] Status (show current install/config)
set /p "CHOICE=Choose 1-5: "
if "%CHOICE%"=="2" goto :uninstall
if "%CHOICE%"=="3" goto :reconfigure
if "%CHOICE%"=="4" goto :upgrade
if "%CHOICE%"=="5" goto :status
goto :install

:show_help
echo mcrl installer for Windows.
echo.
echo Usage:
echo   Mcrl.bat
echo       Interactive menu: install, uninstall, reconfigure, upgrade, or status.
echo.
echo   Mcrl.bat status
echo       Show the registered jar path, whether it exists, config.json's contents
echo       (or its absence), and JDK_JAVA_OPTIONS. Same as menu option 5.
echo.
echo   Mcrl.bat configure "C:\path\to\directory-containing-mcrl.jar" [extras true/false] [telemetry true/false] [profanity true/false]
echo       Write config.json into an existing install directory without the
echo       interactive menu; any value left out still prompts for that one choice.
echo       Used by package managers that manage the jar themselves.
echo.
echo   Mcrl.bat help ^| /? ^| --help ^| -h
echo       Show this message.
endlocal
exit /b 0

REM Read-only: prints the registered jar path, whether it actually exists, config.json's
REM contents (or its absence), and the persisted JDK_JAVA_OPTIONS value, so a user with "chat
REM still blocked" has somewhere to look before opening an issue.
:show_status
call :find_existing_jar
if "%JAR_PATH_FOUND%"=="0" (
    echo No mcrl install found, JDK_JAVA_OPTIONS isn't pointed at an mcrl.jar.
    goto :eof
)
echo Registered jar path: %JAR_PATH%
if exist "%JAR_PATH%" (
    echo   jar file exists.
) else (
    echo   WARNING: jar file does not exist at that path. Run Upgrade or Install again.
)
for %%F in ("%JAR_PATH%") do set "STATUS_DIR=%%~dpF"
if exist "%STATUS_DIR%config.json" (
    echo config.json at %STATUS_DIR%config.json:
    type "%STATUS_DIR%config.json"
) else (
    echo No config.json next to the jar; extras off, telemetry/profanity left alone.
)
set "STATUS_ENV="
for /f "usebackq delims=" %%A in (`powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('JDK_JAVA_OPTIONS','User')"`) do set "STATUS_ENV=%%A"
if "%STATUS_ENV%"=="" (
    echo JDK_JAVA_OPTIONS is not set.
) else (
    echo JDK_JAVA_OPTIONS: %STATUS_ENV%
)
goto :eof

:status_only
call :show_status
endlocal
exit /b 0

:status
echo.
call :show_status
goto :end

:configure_only
REM For package managers (Chocolatey, etc.) that already know exactly where their own jar
REM lives: writes config.json there directly, skipping the interactive menu entirely.
REM Usage: Mcrl.bat configure "C:\path\to\directory-containing-mcrl.jar" [extras true|false] [telemetry true|false] [profanity true|false]
set "CONFIGURE_DIR=%~2"
if "%CONFIGURE_DIR%"=="" (
    echo Usage: Mcrl.bat configure "C:\path\to\directory-containing-mcrl.jar" [extras true/false] [telemetry true/false] [profanity true/false]
    goto :configure_only_end
)
call :prompt_and_write_config "%CONFIGURE_DIR%" "%~3" "%~4" "%~5"
:configure_only_end
endlocal
exit /b 0

:install
set "DEFAULT_DIR=%LOCALAPPDATA%\Mcrl"
set /p "INSTALL_DIR=Install folder (Enter for default: %DEFAULT_DIR%): "
if "%INSTALL_DIR%"=="" set "INSTALL_DIR=%DEFAULT_DIR%"

if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
set "JAR_PATH=%INSTALL_DIR%\mcrl.jar"

echo.
call :prompt_and_write_config "%INSTALL_DIR%"

call :download_jar "%JAR_PATH%"
if errorlevel 1 goto :end

powershell -NoProfile -Command "[Environment]::SetEnvironmentVariable('JDK_JAVA_OPTIONS', '-javaagent:\"%JAR_PATH%\"', 'User')"

echo.
echo Installed. JDK_JAVA_OPTIONS now points at %JAR_PATH%
echo Options are read from %INSTALL_DIR%\config.json at game launch; rerun this
echo script and choose Reconfigure to change them without reinstalling.
echo Close every Minecraft launcher window (official launcher, PrismLauncher,
echo CurseForge, whatever) and reopen.
goto :end

:reconfigure
echo.
call :find_existing_jar
if "%JAR_PATH_FOUND%"=="0" (
    echo Didn't find an existing mcrl install to reconfigure. Run install first.
    goto :end
)
for %%F in ("%JAR_PATH%") do set "INSTALL_DIR=%%~dpF"
echo Found existing install at %JAR_PATH%
echo.
call :prompt_and_write_config "%INSTALL_DIR%"
goto :end

:upgrade
echo.
call :find_existing_jar
if "%JAR_PATH_FOUND%"=="0" (
    echo Didn't find an existing mcrl install to upgrade. Run install first.
    goto :end
)
echo Found existing install at %JAR_PATH%
call :download_jar "%JAR_PATH%"
if errorlevel 1 goto :end
echo.
echo Upgraded %JAR_PATH%. Config and environment setup unchanged.
echo Close every Minecraft launcher window and reopen.
goto :end

:uninstall
echo.
echo Looking for an existing install...
call :find_existing_jar
if "%JAR_PATH_FOUND%"=="0" (
    echo Didn't find an mcrl install, JDK_JAVA_OPTIONS isn't pointed at an mcrl.jar.
    echo Nothing to do.
    goto :end
)

powershell -NoProfile -Command "[Environment]::SetEnvironmentVariable('JDK_JAVA_OPTIONS', $null, 'User')"
echo Removed the JDK_JAVA_OPTIONS environment variable.

for %%F in ("%JAR_PATH%") do set "INSTALL_DIR=%%~dpF"
if exist "%INSTALL_DIR%" (
    set /p "REMOVE=Also delete %INSTALL_DIR% (jar and config.json)? (y/N): "
    if /i "!REMOVE!"=="y" (
        rd /s /q "%INSTALL_DIR%"
        echo Deleted %INSTALL_DIR%.
    )
)

echo.
echo All done. Close every Minecraft launcher window and reopen.
goto :end

:end
echo.
pause
endlocal
