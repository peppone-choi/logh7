param([Parameter(Mandatory=$true)][string]$ReceiptPath)
$ErrorActionPreference='Continue'
$dirs = @(Get-ChildItem 'C:\ProgramData\Microsoft\Windows\WER\ReportQueue','C:\ProgramData\Microsoft\Windows\WER\ReportArchive' -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -match 'G7MT' } | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 3)
$out = @()
foreach ($d in $dirs) { $rep = Join-Path $d.FullName 'Report.wer'; $lines = @(); if (Test-Path -LiteralPath $rep) { $lines = @(Get-Content -LiteralPath $rep -ErrorAction SilentlyContinue | Where-Object { $_ -match '^(EventTime|EventType|AppPath|Sig\[6\]|Sig\[7\]|UI\[2\]|OriginalFilename|NsAppName|ReportStatus|UploadTime)' } | ForEach-Object { [string]$_ }) }; $out += [ordered]@{ dir=$d.FullName; lastWriteUtc=$d.LastWriteTimeUtc.ToString('o'); createdUtc=$d.CreationTimeUtc.ToString('o'); fields=$lines } }
[IO.File]::WriteAllText($ReceiptPath, (@{ reports = $out } | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false)); exit 0
