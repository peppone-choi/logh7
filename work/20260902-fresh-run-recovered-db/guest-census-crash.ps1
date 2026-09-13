[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$RunId,[Parameter(Mandatory=$true)][string]$ReceiptPath,[int]$Minutes=20)
# READ-ONLY: recent application crash records (Application Error 1000 / WER 1001) for the original client.
$ErrorActionPreference='SilentlyContinue'
$since=(Get-Date).AddMinutes(-$Minutes)
$ev=@(Get-WinEvent -FilterHashtable @{ LogName='Application'; StartTime=$since; Id=@(1000,1001,1026) } -MaxEvents 30)
$rows=@()
foreach ($e in $ev) { $rows += [ordered]@{ t=$e.TimeCreated.ToUniversalTime().ToString('o'); id=$e.Id; provider=$e.ProviderName; message=(($e.Message -split "`r?`n" | Select-Object -First 14) -join ' | ') } }
$wer=@(Get-ChildItem -Path "$env:ProgramData\Microsoft\Windows\WER\ReportArchive","$env:ProgramData\Microsoft\Windows\WER\ReportQueue","$env:LOCALAPPDATA\Microsoft\Windows\WER\ReportArchive","$env:LOCALAPPDATA\Microsoft\Windows\WER\ReportQueue" -Directory -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -ge $since } | ForEach-Object { "$($_.FullName) $($_.LastWriteTime.ToUniversalTime().ToString('o'))" })
$dumps=@(Get-ChildItem -Path "$env:LOCALAPPDATA\CrashDumps" -File -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -ge $since } | ForEach-Object { "$($_.FullName) $([int]($_.Length/1KB))KB" })
$r=[ordered]@{ status='CRASH_CENSUS'; runId=$RunId; sinceUtc=$since.ToUniversalTime().ToString('o'); events=$rows; werReports=$wer; crashDumps=$dumps; operations=[ordered]@{ writes=0 } }
[IO.File]::WriteAllText($ReceiptPath, (($r | ConvertTo-Json -Depth 5) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
exit 0