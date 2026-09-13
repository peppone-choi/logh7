[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$RunId,[Parameter(Mandatory=$true)][string]$ReceiptPath,[string]$FromUtc='2026-09-02T23:30:00Z',[string]$ToUtc='2026-09-02T23:50:00Z')
# READ-ONLY census of possible temp cleaners: Storage Sense policy values, scheduled tasks that ran in the window,
# and Storage Sense / disk cleanup event log entries. No settings are changed.
$ErrorActionPreference='SilentlyContinue'
$from=[datetime]::Parse($FromUtc).ToUniversalTime(); $to=[datetime]::Parse($ToUtc).ToUniversalTime()
$r=[ordered]@{ status='CENSUS'; runId=$RunId; window=@($FromUtc,$ToUtc); storageSense=$null; tasksRanInWindow=@(); storageSenseEvents=@(); tempRootAttrs=$null; operations=[ordered]@{ writes=0 } }
$k='HKCU:\Software\Microsoft\Windows\CurrentVersion\StorageSense\Parameters\StoragePolicy'
if (Test-Path $k) { $p=Get-ItemProperty $k; $r.storageSense=[ordered]@{}; foreach ($n in ($p.PSObject.Properties | Where-Object { $_.Name -match '^\d+$' })) { $r.storageSense[$n.Name]=$n.Value } }
$gp='HKLM:\SOFTWARE\Policies\Microsoft\Windows\StorageSense'; if (Test-Path $gp) { $r.storageSensePolicy=(Get-ItemProperty $gp | Select-Object -Property * -ExcludeProperty PS* | ConvertTo-Json -Compress) }
foreach ($t in (Get-ScheduledTask)) { $i=Get-ScheduledTaskInfo -TaskName $t.TaskName -TaskPath $t.TaskPath; if ($i.LastRunTime) { $lr=$i.LastRunTime.ToUniversalTime(); if ($lr -ge $from -and $lr -le $to) { $r.tasksRanInWindow += "$($t.TaskPath)$($t.TaskName) lastRun=$($lr.ToString('o')) result=$($i.LastTaskResult) state=$($t.State)" } } }
$ev=Get-WinEvent -FilterHashtable @{ LogName='Microsoft-Windows-Storage-Storport/Operational','Microsoft-Windows-StorageSpaces-Driver/Operational','Application'; StartTime=$from.ToLocalTime(); EndTime=$to.ToLocalTime() } -MaxEvents 400 | Where-Object { $_.ProviderName -match 'Storage|Cleanup|Cleanmgr|Sense' } | Select-Object -First 40
foreach ($e in $ev) { $r.storageSenseEvents += "$($e.TimeCreated.ToUniversalTime().ToString('o')) $($e.ProviderName) id=$($e.Id): $(($e.Message -split "`n")[0])" }
$ss=Get-WinEvent -ListLog '*Storage*' | Where-Object { $_.RecordCount -gt 0 } | Select-Object -ExpandProperty LogName; $r.storageLogsWithRecords=@($ss)
$root="C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1"; $it=Get-Item -LiteralPath $root -Force; $r.tempRootAttrs="$($it.Attributes) created=$($it.CreationTimeUtc.ToString('o'))"
$r.dirsUnderLaneRoot=@(Get-ChildItem -LiteralPath $root -Directory -Force | ForEach-Object { "$($_.Name) files=$((Get-ChildItem -LiteralPath $_.FullName -Recurse -File -Force | Measure-Object).Count)" })
[IO.File]::WriteAllText($ReceiptPath, (($r | ConvertTo-Json -Depth 6) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
exit 0