$ErrorActionPreference = 'Stop'

# Only touch JDK_JAVA_OPTIONS if it's still pointed at this package's jar; leave it alone
# otherwise, same care Mcrl.sh/Mcrl.bat already take before clearing it.
$current = [Environment]::GetEnvironmentVariable('JDK_JAVA_OPTIONS', 'User')
if ($current -and $current -like '*mcrl.jar*') {
    [Environment]::SetEnvironmentVariable('JDK_JAVA_OPTIONS', $null, 'User')
    Write-Host "Removed the JDK_JAVA_OPTIONS environment variable."
}
