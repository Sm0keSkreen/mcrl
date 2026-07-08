$ErrorActionPreference = 'Stop'

$toolsDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$jarUrl = 'https://github.com/Sm0keSkreen/mcrl/releases/download/v1.3.0/mcrl.jar'
$jarPath = Join-Path $toolsDir 'mcrl.jar'

# lib\mcrl\tools stays at this exact path across `choco upgrade mcrl` (Chocolatey upgrades in
# place, unlike Homebrew/Scoop's versioned-then-symlinked layout), so this only needs doing once.
Get-ChocolateyWebFile -PackageName 'mcrl' -FileFullPath $jarPath -Url $jarUrl `
  -Checksum '5AEF45822D86B2ADF8CCFAE1E01CB780BC22778E22D37B46A42A005F878AD9AD' -ChecksumType 'sha256'

[Environment]::SetEnvironmentVariable('JDK_JAVA_OPTIONS', "-javaagent:`"$jarPath`"", 'User')

Write-Host ""
Write-Host "mcrl.jar installed at $jarPath"
Write-Host "JDK_JAVA_OPTIONS now points at it. Close every open Minecraft launcher window and reopen."
Write-Host "Want the Realms/telemetry/profanity extras? Writes config.json right next to this jar,"
Write-Host "no separate download or install directory (run from an elevated PowerShell, same as"
Write-Host "choco install itself, since this is a system-wide location under ProgramData):"
Write-Host "  irm https://github.com/Sm0keSkreen/mcrl/releases/latest/download/install.bat -outfile install.bat"
Write-Host "  .\install.bat configure `"$toolsDir`""
