[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$RunId,
    [Parameter(Mandatory=$true)][string]$ReceiptPath,
    [switch]$DeleteData
)
# Cleans ONE of this lane's own guest run roots: stops a leftover PostgreSQL of that run through its own
# pg_ctl, removes junctions (data/doc) without following them, then deletes the regenerable directories
# (postgresql runtime, server deployment, client copy) and, only with -DeleteData, the derived DB copy.
# Never touches C:\LOGH7_ORACLE, the sealed source runs, receipts, or captures.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'SilentlyContinue'
$root = Join-Path $env:SystemDrive 'Users\logh7-oracle\AppData\Local\Temp\logh7-l1'
$run = Join-Path $root $RunId
if ($RunId -notmatch '^2026090[23]T([01][0-9]|2[0-3])[0-5][0-9][0-5][0-9]Z-natural-l1-relogin-v1$' -or $RunId -like '20260902T121817Z*') { [IO.File]::WriteAllText($ReceiptPath, '{"status":"RUN_NOT_IN_LANE_SCOPE"}'); exit 3 }
$r = [ordered]@{ status = 'PENDING'; runId = $RunId; deleteData = [bool]$DeleteData; steps = @(); freeMBBefore = [int]([IO.DriveInfo]::new($env:SystemDrive).AvailableFreeSpace / 1MB) }
function Add([string]$s) { $script:r.steps += $s }
$data = Join-Path $run 'postgres-data'
if (Test-Path -LiteralPath (Join-Path $data 'postmaster.pid')) {
    $pgCtl = (Get-ChildItem -LiteralPath (Join-Path $run 'postgresql') -Recurse -Filter 'pg_ctl.exe' | Select-Object -First 1).FullName
    if ($pgCtl) { & $pgCtl -D $data -m fast -s -w stop; Add "pg_ctl stop exit=$LASTEXITCODE" } else { Add 'pg_ctl missing; cannot stop' }
}
$leftover = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'postgres.exe' -and $_.CommandLine -and $_.CommandLine.Replace('/','\').ToLowerInvariant().Contains($data.ToLowerInvariant()) })
foreach ($p in $leftover) { Stop-Process -Id $p.ProcessId -Force; Add "stopped leftover postgres pid=$($p.ProcessId)" }
Start-Sleep -Milliseconds 500
$client = Join-Path $run 'client'
if (Test-Path -LiteralPath $client) {
    foreach ($j in @('data','doc')) {
        $jp = Join-Path $client $j
        $item = Get-Item -LiteralPath $jp -Force
        if ($item -and ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { [IO.Directory]::Delete($jp); Add "junction removed (target untouched): $jp gone=$(-not (Test-Path -LiteralPath $jp))" }
    }
}
foreach ($d in @('postgresql','server','client')) {
    $p = Join-Path $run $d
    if (Test-Path -LiteralPath $p) {
        $mb = [int](((Get-ChildItem -LiteralPath $p -Recurse -File -Force | Measure-Object -Property Length -Sum).Sum) / 1MB)
        Get-ChildItem -LiteralPath $p -Recurse -File -Force | ForEach-Object { if ($_.Attributes -band [IO.FileAttributes]::ReadOnly) { $_.Attributes = $_.Attributes -bxor [IO.FileAttributes]::ReadOnly } }
        try { [IO.Directory]::Delete($p, $true) } catch { Add "delete error ${d}: $($_.Exception.Message)" }
        Add "$d ${mb}MB gone=$(-not (Test-Path -LiteralPath $p))"
    }
}
if ($DeleteData -and (Test-Path -LiteralPath $data)) {
    $mb = [int](((Get-ChildItem -LiteralPath $data -Recurse -File -Force | Measure-Object -Property Length -Sum).Sum) / 1MB)
    try { [IO.Directory]::Delete($data, $true) } catch { Add "delete error postgres-data: $($_.Exception.Message)" }
    Add "postgres-data (derived copy) ${mb}MB gone=$(-not (Test-Path -LiteralPath $data))"
}
$r.freeMBAfter = [int]([IO.DriveInfo]::new($env:SystemDrive).AvailableFreeSpace / 1MB)
$r.status = 'RUN_CLEANED'
$parent = Split-Path -Parent $ReceiptPath; if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
[IO.File]::WriteAllText($ReceiptPath, (($r | ConvertTo-Json -Depth 4) -replace "`r`n", "`n"), [Text.UTF8Encoding]::new($false))
exit 0
