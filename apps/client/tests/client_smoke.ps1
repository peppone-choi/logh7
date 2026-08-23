$ErrorActionPreference = 'Stop'

$exe = Join-Path $PSScriptRoot '..\..\..\build\windows-release\bin\Logh7Client.exe'
if (-not (Test-Path -LiteralPath $exe)) { throw "missing $exe" }
$version = Start-Process -FilePath $exe -ArgumentList '--version' -Wait -PassThru
if ($version.ExitCode -ne 0) { throw 'version failed' }
$smoke = Start-Process -FilePath $exe -ArgumentList '--profile','client-a','--server','http://127.0.0.1:47910','--resolution','1280x720','--smoke-exit-ms','1000' -Wait -PassThru
if ($smoke.ExitCode -ne 0) { throw 'window smoke failed' }
