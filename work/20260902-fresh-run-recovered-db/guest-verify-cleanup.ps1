[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$RunId,
    [Parameter(Mandatory=$true)][string]$ReceiptPath,
    [string[]]$CheckRunIds = @()
)
# Read-only: reports whether each listed run's regenerable copies (postgres-data / server / client) still exist,
# the system drive free space, and any leftover lane processes/listeners. Writes nothing but the receipt.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'SilentlyContinue'
$root = 'C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1'
$rows = @()
$ids = @($CheckRunIds | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim().Trim('"') } | Where-Object { $_ })
foreach ($r in $ids) {
    $d = Join-Path $root $r
    $rc = Join-Path $root ($r + '-cleanup-data.json'); $rcObj = $null
    if (Test-Path -LiteralPath $rc) { try { $rcObj = Get-Content -LiteralPath $rc -Raw -Encoding UTF8 | ConvertFrom-Json } catch { $rcObj = @{ status = 'RECEIPT_UNREADABLE' } } }
    $rows += [ordered]@{ runId = $r; runDirExists = (Test-Path -LiteralPath $d); postgresData = (Test-Path -LiteralPath (Join-Path $d 'postgres-data')); server = (Test-Path -LiteralPath (Join-Path $d 'server')); client = (Test-Path -LiteralPath (Join-Path $d 'client')); postgresql = (Test-Path -LiteralPath (Join-Path $d 'postgresql')); cleanupDataReceipt = $rcObj }
}
$procs = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -like 'G7MTClient*.exe' -or $_.Name -in @('Logh7.Server.exe','postgres.exe') } | ForEach-Object { "$($_.Name):$($_.ProcessId)" })
$listen = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in @(47900,55432) } | ForEach-Object { "$($_.LocalAddress):$($_.LocalPort)" })
$receipt = [ordered]@{ status = 'VERIFIED'; runId = $RunId; checkedAtUtc = [datetime]::UtcNow.ToString('o'); freeMB = [int]([IO.DriveInfo]::new($env:SystemDrive).AvailableFreeSpace / 1MB); runs = $rows; leftoverProcesses = $procs; laneListeners = $listen; operations = [ordered]@{ writes = 0; gameInputs = 0 } }
[IO.File]::WriteAllText($ReceiptPath, (($receipt | ConvertTo-Json -Depth 6) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
exit 0
