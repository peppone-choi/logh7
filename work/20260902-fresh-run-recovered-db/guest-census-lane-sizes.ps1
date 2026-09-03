[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$RunId,[Parameter(Mandatory=$true)][string]$ReceiptPath)
# READ-ONLY size census of the lane root: MB per run directory (and which subfolders exist), totals, free space.
$ErrorActionPreference='SilentlyContinue'
$root="C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1"
$rows=@(); $total=0
foreach ($d in (Get-ChildItem -LiteralPath $root -Directory -Force)) {
  $mb=[int](((Get-ChildItem -LiteralPath $d.FullName -Recurse -File -Force | Measure-Object -Property Length -Sum).Sum)/1MB); $total+=$mb
  $sub=@(Get-ChildItem -LiteralPath $d.FullName -Directory -Force | Select-Object -ExpandProperty Name) -join ','
  $rows += [ordered]@{ run=$d.Name; mb=$mb; sub=$sub; pgData=(Test-Path -LiteralPath (Join-Path $d.FullName 'postgres-data\global\pg_control')) }
}
$files=@(Get-ChildItem -LiteralPath $root -File -Force | ForEach-Object { "$($_.Name) $([int]($_.Length/1MB))MB" })
$r=[ordered]@{ status='SIZE_CENSUS'; runId=$RunId; freeMB=[int]([IO.DriveInfo]::new('C').AvailableFreeSpace/1MB); totalMBUnderLaneRoot=$total; laneRootFiles=$files; dirCount=$rows.Count; rows=$rows; operations=[ordered]@{ writes=0 } }
[IO.File]::WriteAllText($ReceiptPath, (($r | ConvertTo-Json -Depth 5) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
exit 0