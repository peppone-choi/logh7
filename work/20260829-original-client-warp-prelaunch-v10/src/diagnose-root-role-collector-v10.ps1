[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$CollectorPath,
    [Parameter(Mandatory=$true)][string]$OutputPath,
    [Parameter(Mandatory=$true)][string]$DiagnosticPath
)
$ErrorActionPreference = 'Stop'
try {
    & $CollectorPath -TargetProcessId 3448 -ExpectedStartTimeUtc '2026-08-25T15:47:31.9489446Z' -ExpectedExecutableSha256 'BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16' -ExpectedWindowHandle '0x001A0490' -OutputPath $OutputPath | Out-Null
    [ordered]@{ status='PASS'; outputExists=(Test-Path -LiteralPath $OutputPath) } | ConvertTo-Json | Set-Content -LiteralPath $DiagnosticPath -Encoding UTF8
    exit 0
} catch {
    [ordered]@{
        status = 'FAIL'
        exceptionType = $_.Exception.GetType().FullName
        message = $_.Exception.Message
        scriptName = $_.InvocationInfo.ScriptName
        scriptLineNumber = $_.InvocationInfo.ScriptLineNumber
        positionMessage = $_.InvocationInfo.PositionMessage
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $DiagnosticPath -Encoding UTF8
    exit 1
}
