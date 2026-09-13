[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$RunId,[Parameter(Mandatory=$true)][string]$ReceiptPath,[switch]$Delete)
# Copy the item115 client's debug log (lane root) into THIS run's directory so host-step can copy it back.
# With -Delete, remove the lane-root log afterwards (it is a regenerable diagnostic file created by this task).
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$root="C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1"; $log=Join-Path $root 'g7mt-debug.log'; $dst=Join-Path (Join-Path $root $RunId) 'g7mt-debug.log'
$r=[ordered]@{ status='PENDING'; runId=$RunId; source=$log; exists=(Test-Path -LiteralPath $log); bytes=0; lines=0; copied=$false; deleted=$false; operations=[ordered]@{ writes=0; deletes=0 } }
if ($r.exists) {
  Copy-Item -LiteralPath $log -Destination $dst -Force; $r.copied=$true; $r.operations.writes=1
  $r.bytes=(Get-Item -LiteralPath $dst).Length; $r.lines=@(Get-Content -LiteralPath $dst -Encoding Default).Count
  if ($Delete) { Remove-Item -LiteralPath $log -Force; $r.deleted=-not (Test-Path -LiteralPath $log); $r.operations.deletes=1 }
}
$r.status = $(if ($r.exists) { 'DEBUGLOG_COLLECTED' } else { 'DEBUGLOG_ABSENT' })
[IO.File]::WriteAllText($ReceiptPath, (($r | ConvertTo-Json -Depth 4) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
exit 0
