[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$CollectorPath,
    [Parameter(Mandatory=$true)][string]$OutputPath,
    [Parameter(Mandatory=$true)][string]$DiagnosticPath
)
$ErrorActionPreference = 'Stop'
try {
    & $CollectorPath -OutputPath $OutputPath | Out-Null
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
