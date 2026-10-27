$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (!$env:JAVA_HOME -or !(Test-Path "$env:JAVA_HOME/bin/javac.exe")) {
    $savedErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $javaSettings = & java -XshowSettings:properties -version 2>&1 | Out-String
    $ErrorActionPreference = $savedErrorActionPreference
    if ($javaSettings -match 'java.home\s*=\s*([^\r\n]+)') { $env:JAVA_HOME = $Matches[1].Trim() }
}
$maven = Join-Path (Get-Location) '.tools/apache-maven-3.9.11/bin/mvn.cmd'
if (Get-Command mvn -ErrorAction SilentlyContinue) { $maven = 'mvn' }
if (!(Test-Path $maven) -and $maven -ne 'mvn') {
    New-Item -ItemType Directory -Force .tools | Out-Null
    $url = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip'
    Invoke-WebRequest $url -OutFile '.tools/maven.zip'
    $expected = (Invoke-WebRequest "$url.sha512").Content.Trim().Split(' ')[0]
    if ((Get-FileHash '.tools/maven.zip' -Algorithm SHA512).Hash -ne $expected) { throw 'Maven checksum mismatch' }
    Expand-Archive '.tools/maven.zip' -DestinationPath '.tools' -Force
}
# OneDrive may expose target directories as read-only reparse points, which makes
# Maven's clean phase fail even though Maven can update the build outputs.
& $maven '-Dmaven.repo.local=.m2' --no-transfer-progress verify
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
