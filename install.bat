@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "JAR_URL=https://github.com/Sm0keSkreen/mcrl/releases/latest/download/mcrl.jar"

echo.
echo mcrl, chat restrictions lifted
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

set "AGENT_ARGS="
set /p "EXTRAS=Also unlock Realms, the multiplayer server list, and friends where the account API supports it? (y/N): "
if /i "%EXTRAS%"=="y" set "AGENT_ARGS==extras"

echo.
echo Fetching mcrl.jar into %JAR_PATH% ...
where curl >nul 2>nul
if %ERRORLEVEL%==0 (
    curl -fL -o "%JAR_PATH%" "%JAR_URL%"
) else (
    powershell -NoProfile -Command "Invoke-WebRequest -Uri '%JAR_URL%' -OutFile '%JAR_PATH%'"
)

if not exist "%JAR_PATH%" (
    echo.
    echo Download failed, mcrl.jar isn't at %JAR_PATH%.
    goto :end
)

powershell -NoProfile -Command "[Environment]::SetEnvironmentVariable('JDK_JAVA_OPTIONS', '-javaagent:\"%JAR_PATH%\"%AGENT_ARGS%', 'User')"

echo.
echo Installed. JDK_JAVA_OPTIONS now points at %JAR_PATH%%AGENT_ARGS%
if defined AGENT_ARGS (
    echo Realms/servers/friends unlock enabled, skipped automatically on versions
    echo whose account API doesn't have a given flag yet.
)
echo Close every Minecraft launcher window (official launcher, PrismLauncher,
echo CurseForge, whatever) and reopen.
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
if "%JAR_PATH:~-7%"=="=extras" set "JAR_PATH=%JAR_PATH:~0,-7%"
set JAR_PATH=%JAR_PATH:"=%
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
echo All done. Close every Minecraft launcher window and reopen.
goto :end

:end
echo.
pause
endlocal
