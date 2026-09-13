[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$RunId,
    [Parameter(Mandatory=$true)][string]$ReceiptPath,
    [string]$WireFileName = 'server-wire.jsonl',
    [int]$Tail = 40
)
# READ-ONLY: summarise the authority wire receipt (frame-processed / frame-rejected events) for this run.
# Values only: request type, status, error code, rejected payload hex (no credentials are ever on this path).
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = "C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1\$RunId"
$wire = Join-Path $root $WireFileName
$rows = @()
if (Test-Path -LiteralPath $wire) {
    $lines = @(Get-Content -LiteralPath $wire -Encoding UTF8)
    foreach ($l in ($lines | Select-Object -Last $Tail)) {
        try { $o = $l | ConvertFrom-Json } catch { continue }
        if ($o.eventName -in @('frame-processed','frame-rejected','connection-accepted','connection-closed')) {
            $row = [ordered]@{ t = [string]$o.timestampUtc; ev = [string]$o.eventName }
            foreach ($k in @('outerControl','payloadLength','status','ErrorCode','ObservedApplicationType','RejectedApplicationPayloadHex','ResponseMetadata','requestPayloadHex','ResponseOuterControl','responsePayloadLength','stateBefore','stateAfter','bodyLength','errorCode')) {
                if ($o.PSObject.Properties.Name -contains $k -and $null -ne $o.$k) { $row[$k] = $o.$k }
            }
            $rows += $row
        }
    }
}
$r = [ordered]@{ status = $(if (Test-Path -LiteralPath $wire) { 'WIRE_READ' } else { 'WIRE_MISSING' }); runId = $RunId; wire = $wire; lineCount = $(if (Test-Path -LiteralPath $wire) { @(Get-Content -LiteralPath $wire).Count } else { 0 }); rows = $rows; operations = [ordered]@{ writes = 0; gameInputs = 0 } }
[IO.File]::WriteAllText($ReceiptPath, (($r | ConvertTo-Json -Depth 5) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
exit 0
