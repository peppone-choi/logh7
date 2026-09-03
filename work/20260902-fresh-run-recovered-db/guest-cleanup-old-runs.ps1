[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$RunId,
    [Parameter(Mandatory=$true)][string]$ListPath,
    [Parameter(Mandatory=$true)][string]$ExpectedListSha256,
    [Parameter(Mandatory=$true)][string]$ReceiptPath,
    [string]$LaneRoot = 'C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1',
    [switch]$Delete
)
# Removes the DERIVED PostgreSQL data copies (postgres-data) and server deployments (server) of OLD run directories
# listed in $ListPath (one run id per line; the list is hash-verified). Receipts, logs and client copies stay.
# The sealed source chain must never be in the list (checked here again). User decision 2026-09-03 ("옛 런 DB 사본 29GB 정리").
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
function Hash([string]$p) { (Get-FileHash -Algorithm SHA256 -LiteralPath $p).Hash }
$protected = @('20260902T083838Z-natural-l1-relogin-v1')
$r = [ordered]@{ status = 'PENDING'; runId = $RunId; delete = [bool]$Delete; laneRoot = $LaneRoot; listSha256 = $null; freeMBBefore = $null; freeMBAfter = $null; removed = @(); skipped = @(); errors = @(); totalMBRemoved = 0 }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReceiptPath) | Out-Null
try {
    if ((Hash $ListPath) -cne $ExpectedListSha256) { throw 'LIST_SHA_MISMATCH' }
    $ids = Get-Content -LiteralPath $ListPath | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '^[0-9]{8}T[0-9]{6}Z-[a-z0-9-]+$' }
    $r.freeMBBefore = [int]((Get-PSDrive C).Free / 1MB)
    foreach ($id in $ids) {
        if ($protected -contains $id) { $r.skipped += ("PROTECTED " + $id); continue }
        $root = Join-Path $LaneRoot $id
        if (-not (Test-Path -LiteralPath $root)) { $r.skipped += ("MISSING " + $id); continue }
        $mb = 0; $parts = @()
        foreach ($sub in @('postgres-data','server')) {
            $p = Join-Path $root $sub
            if (Test-Path -LiteralPath $p) {
                $size = (Get-ChildItem -LiteralPath $p -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
                $mb += [math]::Round($size / 1MB, 1); $parts += $sub
                if ($Delete) {
                    $pg = Get-Process -Name postgres -ErrorAction SilentlyContinue | Where-Object { $_.Path -like ($root + '*') }
                    if ($pg) { throw ('POSTGRES_RUNNING_IN ' + $id) }
                    Remove-Item -LiteralPath $p -Recurse -Force -Confirm:$false
                }
            }
        }
        if ($parts.Count -gt 0) { $r.removed += [ordered]@{ runId = $id; parts = $parts; mb = $mb }; $r.totalMBRemoved += $mb } else { $r.skipped += ("NO_DATA " + $id) }
    }
    $r.freeMBAfter = [int]((Get-PSDrive C).Free / 1MB)
    $r.status = $(if ($Delete) { 'OLD_RUN_DATA_DELETED' } else { 'OLD_RUN_DATA_DRY_RUN' })
} catch { $r.status = 'FAILED'; $r.errors += [string]$_ }
[IO.File]::WriteAllText($ReceiptPath, (($r | ConvertTo-Json -Depth 5) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
if ($r.status -eq 'FAILED') { exit 1 }
