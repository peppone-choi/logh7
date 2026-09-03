[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$RunId,
    [Parameter(Mandatory=$true)][string]$ZipPath,
    [Parameter(Mandatory=$true)][string]$ExpectedZipSha256,
    [Parameter(Mandatory=$true)][string]$ReceiptPath
)
# Restore files missing from THIS run's own PostgreSQL runtime copy (postgresql\pgsql) from the sealed runtime zip.
# Read-only census first; only files that are ABSENT are written; existing files are never overwritten; nothing is
# deleted. Purpose: a guest temp cleaner removed unused files (e.g. pg_ctl.exe) from the run copy while postgres.exe
# kept running, which blocked the clean stop. The sealed zip (sha-verified) is the regenerable source.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$r = [ordered]@{ status = 'PENDING'; runId = $RunId; zip = $ZipPath; census = $null; restored = @(); restoredCount = 0; skippedExisting = 0; operations = [ordered]@{ writes = 0; deletes = 0 } }
try {
    $root = "C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1\$RunId"; $pgRoot = Join-Path $root 'postgresql\pgsql'
    if (-not (Test-Path -LiteralPath $pgRoot)) { throw 'RUN_PG_ROOT_MISSING' }
    $sha = (Get-FileHash -LiteralPath $ZipPath -Algorithm SHA256).Hash
    if ($sha -cne $ExpectedZipSha256) { throw "ZIP_SHA_MISMATCH:$sha" }
    $binDirs = @(Get-ChildItem -LiteralPath $pgRoot -Recurse -Directory -Filter 'bin' | Select-Object -ExpandProperty FullName)
    $present = @(Get-ChildItem -LiteralPath $pgRoot -Recurse -File -Force | Select-Object -ExpandProperty FullName)
    $r.census = [ordered]@{ binDirs = $binDirs; fileCount = $present.Count; pgCtlPresent = [bool]($present | Where-Object { $_ -like '*\pg_ctl.exe' }); postgresPresent = [bool]($present | Where-Object { $_ -like '*\postgres.exe' }) }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        foreach ($e in $zip.Entries) {
            if ($e.Name -eq '') { continue }
            $dest = Join-Path $pgRoot ($e.FullName -replace '/', '\')
            if (Test-Path -LiteralPath $dest) { $r.skippedExisting++; continue }
            $parent = Split-Path -Parent $dest; if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
            [IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dest, $false)
            $r.operations.writes++; $r.restoredCount++
            if ($r.restored.Count -lt 400) { $r.restored += $e.FullName }
        }
    } finally { $zip.Dispose() }
    $r.pgCtlAfter = [bool](Get-ChildItem -LiteralPath $pgRoot -Recurse -File -Filter 'pg_ctl.exe' | Select-Object -First 1)
    $r.status = $(if ($r.pgCtlAfter) { 'RUNTIME_RESTORED' } else { 'RUNTIME_RESTORE_INCOMPLETE' })
} catch { $r.status = 'RUNTIME_RESTORE_FAILED'; $r.error = $_.Exception.Message }
[IO.File]::WriteAllText($ReceiptPath, (($r | ConvertTo-Json -Depth 6) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
if ($r.status -ne 'RUNTIME_RESTORED') { exit 1 }
exit 0
