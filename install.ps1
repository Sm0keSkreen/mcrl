# mcrl installer/uninstaller. Download this file and run it (right-click ->
# "Run with PowerShell"), don't paste it into Win+R, a pasted download-and-run
# one-liner is exactly the shape of the "ClickFix" malware technique and gets
# flagged by Defender for that reason alone, regardless of payload. A saved
# file you can open, read, and run avoids that pattern entirely.

param(
    [string]$Path,
    [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'
$jarUrl = 'https://raw.githubusercontent.com/Dylanthedabber/mcrl/master/mcrl.jar'

function Show-Frame([string]$Eyes) {
    Write-Host ""
    Write-Host "        /\_/\ " -ForegroundColor Cyan
    Write-Host "       ( $Eyes )  " -ForegroundColor Cyan -NoNewline
    Write-Host "mcrl" -ForegroundColor White -NoNewline
    Write-Host ", chat restrictions lifted" -ForegroundColor DarkGray
    Write-Host "        > ^ < " -ForegroundColor Cyan
    Write-Host ""
}

function Show-Banner {
    for ($i = 0; $i -lt 3; $i++) {
        Clear-Host
        Show-Frame "^.^"
        Start-Sleep -Milliseconds 300
        Clear-Host
        Show-Frame "-.-"
        Start-Sleep -Milliseconds 300
    }
    Clear-Host
    Show-Frame "^.^"
}

Show-Banner

if (-not $Uninstall -and -not $PSBoundParameters.ContainsKey('Path')) {
    Write-Host "What would you like to do?"
    Write-Host "  [1] Install (default)"
    Write-Host "  [2] Uninstall"
    $choice = Read-Host "Choose 1 or 2"
    if ($choice -eq '2') { $Uninstall = $true }
}

if ($Uninstall) {
    Write-Host "Looking for an existing install..."
    $current = [Environment]::GetEnvironmentVariable('JDK_JAVA_OPTIONS', 'User')

    if ($current -and $current -match '-javaagent:(.*mcrl\.jar)') {
        $jarPath = $matches[1]
        [Environment]::SetEnvironmentVariable('JDK_JAVA_OPTIONS', $null, 'User')
        Write-Host "Removed the JDK_JAVA_OPTIONS environment variable." -ForegroundColor Green

        $installDir = Split-Path $jarPath -Parent
        if (Test-Path $installDir) {
            $remove = Read-Host "Also delete $installDir ? (y/N)"
            if ($remove -eq 'y' -or $remove -eq 'Y') {
                Remove-Item -Recurse -Force $installDir
                Write-Host "Deleted $installDir." -ForegroundColor Green
            }
        }
        Write-Host ""
        Write-Host "All done, meow. Close every Minecraft launcher window and reopen." -ForegroundColor Cyan
    }
    else {
        Write-Host "Didn't find an mcrl install (JDK_JAVA_OPTIONS isn't pointed at an mcrl.jar)." -ForegroundColor Yellow
        Write-Host "Nothing to do."
    }
    exit
}

if (-not $Path) {
    $defaultDir = Join-Path $env:LOCALAPPDATA 'Mcrl'
    $entered = Read-Host "Install folder (Enter for default: $defaultDir)"
    $Path = if ([string]::IsNullOrWhiteSpace($entered)) { $defaultDir } else { $entered }
}

New-Item -ItemType Directory -Force -Path $Path | Out-Null
$jarPath = Join-Path $Path 'mcrl.jar'

Write-Host "Fetching mcrl.jar into $jarPath ..."
Invoke-WebRequest -Uri $jarUrl -OutFile $jarPath

[Environment]::SetEnvironmentVariable('JDK_JAVA_OPTIONS', "-javaagent:$jarPath", 'User')

Write-Host ""
Write-Host "Installed. JDK_JAVA_OPTIONS now points at $jarPath" -ForegroundColor Green
Write-Host "Close every Minecraft launcher window (official launcher, PrismLauncher," -ForegroundColor DarkGray
Write-Host "CurseForge, whatever) and reopen. Purrs." -ForegroundColor Cyan
Write-Host ""
