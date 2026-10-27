$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (!(Test-Path 'target/campus-connect-1.0.0.jar')) { & "$PSScriptRoot/build.ps1" }
& java -jar target/campus-connect-1.0.0.jar
