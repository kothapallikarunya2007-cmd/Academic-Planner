$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (!(Test-Path 'runtime/server.pid')) { Write-Output 'No background server PID was recorded. Use Ctrl+C in the terminal running the server.'; exit }
$campusPid = [int](Get-Content 'runtime/server.pid')
$campusProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$campusPid"
if (!$campusProcess) { Write-Output 'The recorded background server is already stopped.'; exit }
if ($campusProcess.Name -ne 'java.exe' -or $campusProcess.CommandLine -notmatch 'campus-connect-1\.0\.0\.jar') {
    throw 'The recorded PID belongs to a different process. Refusing to stop it.'
}
Stop-Process -Id $campusPid
Write-Output 'Campus Connect background server stopped.'
