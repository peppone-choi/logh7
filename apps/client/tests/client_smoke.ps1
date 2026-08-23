$ErrorActionPreference = 'Stop'

$exe = Join-Path $PSScriptRoot '..\..\..\build\windows-release\bin\Logh7Client.exe'
if (-not (Test-Path -LiteralPath $exe)) { throw "missing $exe" }
$version = Start-Process -FilePath $exe -ArgumentList '--version' -Wait -PassThru
if ($version.ExitCode -ne 0) { throw 'version failed' }
$smoke = Start-Process -FilePath $exe -ArgumentList '--profile','client-a','--server','http://127.0.0.1:47910','--resolution','1280x720','--smoke-exit-ms','1000' -Wait -PassThru
if ($smoke.ExitCode -ne 0) { throw 'window smoke failed' }

foreach ($invalidServer in @('http:///', 'https://?', 'http://[::::]')) {
    $invalid = Start-Process -FilePath $exe -ArgumentList '--server',$invalidServer,'--smoke-exit-ms','50' -Wait -PassThru
    if ($invalid.ExitCode -ne 2) { throw "invalid server authority accepted: $invalidServer" }
}

$longServer = 'https://authority.example.invalid:47910/api/' + ('segment-0123456789/' * 8) + 'session?transport=winhttp&profile=client-a'
$longEndpoint = Start-Process -FilePath $exe -ArgumentList '--profile','client-long','--server',$longServer,'--resolution','1280x720','--smoke-exit-ms','100' -Wait -PassThru
if ($longEndpoint.ExitCode -ne 0) { throw 'long server endpoint smoke failed' }
