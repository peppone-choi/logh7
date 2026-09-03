[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$RunId,[Parameter(Mandatory=$true)][string]$ExpectedZipSha256,[Parameter(Mandatory=$true)][string]$ReceiptPath)
# Delete THIS run's guest stage directory (C:\ProgramData\LOGH7\FreshRun\<RunId>) only if it holds nothing but the
# staged authority zip whose sha matches the host build (regenerable). Anything else -> refuse, report.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if ($RunId -notmatch '^2026090[23]T[0-9]{6}Z-natural-l1-relogin-v1$') { throw 'RUN_NOT_IN_LANE_SCOPE' }
$stage = "C:\ProgramData\LOGH7\FreshRun\$RunId"
$r=[ordered]@{ status='PENDING'; runId=$RunId; stage=$stage; files=@(); deleted=$false; operations=[ordered]@{ deletes=0 } }
try {
  if (-not (Test-Path -LiteralPath $stage)) { $r.status='STAGE_ABSENT' }
  else {
    $files=@(Get-ChildItem -LiteralPath $stage -Recurse -Force -File)
    $r.files=@($files | ForEach-Object { "$($_.Name) $([int]($_.Length/1MB))MB" })
    # allowed content: copies of lane guest scripts (*.ps1, staged by host-step) and the staged authority zip with the expected sha
    $ok = $true
    foreach ($f in $files) {
      if ($f.Extension -eq '.ps1' -and $f.Length -lt 1MB) { continue }
      if ($f.Name -eq 'logh7-server-win-x64.zip' -and (($ExpectedZipSha256 -split ',') -ccontains (Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash)) { continue }
      $ok = $false; break
    }
    if ($ok) { [IO.Directory]::Delete($stage, $true); $r.operations.deletes=1; $r.deleted = -not (Test-Path -LiteralPath $stage); $r.status = $(if ($r.deleted) { 'STAGE_DELETED' } else { 'STAGE_DELETE_INCOMPLETE' }) }
    else { $r.status='STAGE_REFUSED_UNEXPECTED_CONTENT' }
  }
} catch { $r.status='STAGE_CLEAN_FAILED'; $r.error=$_.Exception.Message }
$parent = Split-Path -Parent $ReceiptPath; if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
[IO.File]::WriteAllText($ReceiptPath, (($r | ConvertTo-Json -Depth 4) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
exit 0
