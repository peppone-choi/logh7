[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$RunId,
    [Parameter(Mandatory=$true)][string]$ZipPath,
    [Parameter(Mandatory=$true)][string]$ExpectedZipSha256,
    [Parameter(Mandatory=$true)][string]$ReceiptPath,
    [string]$InstallRoot = 'C:\LOGH7_ORACLE',
    [string]$OverlayRoot = '',
    [switch]$Apply
)
# Applies the official update's DATA files (G7UPD040514: data\model\images\{Hi,Lo,Mid}, data\model\strategy\*.mdx)
# to the guest install tree's data folder (the run copies junction their data to it). User decision 2026-09-03.
# - never touches exe files; never touches the CD; replaced originals are backed up under $InstallRoot\data-backup-<stamp>\
# - dry run (no -Apply) only classifies: new / identical / replace
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
function Hash([string]$p) { (Get-FileHash -Algorithm SHA256 -LiteralPath $p).Hash }
$r = [ordered]@{ status = 'PENDING'; runId = $RunId; apply = [bool]$Apply; zip = $ZipPath; zipSha256 = $null; installRoot = $InstallRoot; overlayRoot = $OverlayRoot; overlayCopy = $null; new = @(); identical = @(); replaced = @(); errors = @(); backupRoot = $null }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReceiptPath) | Out-Null
try {
    if (-not (Test-Path -LiteralPath $ZipPath)) { throw 'ZIP_MISSING' }
    $r.zipSha256 = Hash $ZipPath
    if ($r.zipSha256 -cne $ExpectedZipSha256) { throw 'ZIP_SHA_MISMATCH' }
    $dataRoot = Join-Path $InstallRoot 'data'
    if (-not (Test-Path -LiteralPath $dataRoot)) { throw 'INSTALL_DATA_MISSING' }
    if ($OverlayRoot) {
        # OVERLAY: the sealed install is ACL-protected and must stay untouched; runs junction their data to this copy.
        $ovData = Join-Path $OverlayRoot 'data'
        if (-not (Test-Path -LiteralPath $ovData)) {
            if ($Apply) {
                New-Item -ItemType Directory -Force -Path $OverlayRoot | Out-Null
                $rc = & robocopy.exe $dataRoot $ovData /E /COPY:DAT /R:1 /W:1 /NFL /NDL /NJH /NJS /NP
                if ($LASTEXITCODE -ge 8) { throw ('OVERLAY_COPY_FAILED ' + $LASTEXITCODE) }
            } else { $r.overlayCopy = 'WOULD_COPY' }
        }
        if (Test-Path -LiteralPath $ovData) {
            $srcCount = (Get-ChildItem -LiteralPath $dataRoot -Recurse -File | Measure-Object).Count
            $ovCount = (Get-ChildItem -LiteralPath $ovData -Recurse -File | Measure-Object).Count
            $r.overlayCopy = [ordered]@{ sourceFiles = $srcCount; overlayFiles = $ovCount }
        }
        $InstallRoot = $OverlayRoot
    }
    $tmp = Join-Path $env:TEMP ("g7upd-" + $RunId); if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Recurse -Force -Confirm:$false }
    Expand-Archive -LiteralPath $ZipPath -DestinationPath $tmp
    $stamp = [datetime]::UtcNow.ToString('yyyyMMddTHHmmssZ'); $backup = Join-Path $InstallRoot ("data-backup-preupdate-" + $stamp)
    $tmpFull = (Get-Item -LiteralPath $tmp).FullName.TrimEnd('\')
    $files = Get-ChildItem -LiteralPath (Join-Path $tmpFull 'data') -Recurse -File
    foreach ($f in $files) {
        if ($f.Extension -in @('.exe','.dll')) { $r.errors += ("REFUSED_EXECUTABLE " + $f.FullName); continue }
        if (-not $f.FullName.StartsWith($tmpFull, [StringComparison]::OrdinalIgnoreCase)) { $r.errors += ("UNEXPECTED_ROOT " + $f.FullName); continue }
        $rel = $f.FullName.Substring($tmpFull.Length).TrimStart('\')
        if (-not $rel.StartsWith('data\')) { $r.errors += ("UNEXPECTED_PATH " + $rel); continue }
        $target = Join-Path $InstallRoot $rel
        $entry = [ordered]@{ path = $rel; size = $f.Length; sha256 = (Hash $f.FullName) }
        if (Test-Path -LiteralPath $target) {
            $old = Hash $target
            if ($old -ceq $entry.sha256) { $r.identical += $entry; continue }
            $entry.replacedSha256 = $old; $entry.replacedSize = (Get-Item -LiteralPath $target).Length
            if ($Apply) {
                $bk = Join-Path $backup $rel; New-Item -ItemType Directory -Force -Path (Split-Path -Parent $bk) | Out-Null
                Copy-Item -LiteralPath $target -Destination $bk
                if ((Hash $bk) -cne $old) { throw ('BACKUP_HASH_MISMATCH ' + $rel) }
                Copy-Item -LiteralPath $f.FullName -Destination $target -Force
                if ((Hash $target) -cne $entry.sha256) { throw ('COPY_HASH_MISMATCH ' + $rel) }
                $entry.backup = $bk
            }
            $r.replaced += $entry
        } else {
            if ($Apply) {
                New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
                Copy-Item -LiteralPath $f.FullName -Destination $target
                if ((Hash $target) -cne $entry.sha256) { throw ('COPY_HASH_MISMATCH ' + $rel) }
            }
            $r.new += $entry
        }
    }
    if ($Apply -and $r.replaced.Count -gt 0) { $r.backupRoot = $backup }
    Remove-Item -LiteralPath $tmp -Recurse -Force -Confirm:$false
    $r.status = $(if ($Apply) { 'UPDATE_DATA_APPLIED' } else { 'UPDATE_DATA_DRY_RUN' })
} catch { $r.status = 'FAILED'; $r.errors += [string]$_ }
[IO.File]::WriteAllText($ReceiptPath, (($r | ConvertTo-Json -Depth 6) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
if ($r.status -eq 'FAILED') { exit 1 }
