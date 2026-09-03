param([Parameter(Mandatory=$true)][string]$RunId,[Parameter(Mandatory=$true)][int]$ExpectedPid,[Parameter(Mandatory=$true)][string]$ReceiptPath,[string]$PrepFileName='fresh-run-prep.json')
$root="C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1\$RunId"; if (-not $PrepFileName) { $PrepFileName = 'fresh-run-prep.json' }; $prep=Get-Content (Join-Path $root $PrepFileName) -Raw -Encoding UTF8 | ConvertFrom-Json
$cim=Get-CimInstance Win32_Process -Filter "ProcessId=$ExpectedPid" -ErrorAction SilentlyContinue
if ($null -eq $cim -or [int]$prep.client.pid -ne $ExpectedPid -or $cim.ExecutablePath -cne [string]$prep.client.path) { [IO.File]::WriteAllText($ReceiptPath,'{"status":"OWN_CLIENT_NOT_MATCHED"}'); exit 1 }
Stop-Process -Id $ExpectedPid -Force; Start-Sleep -Seconds 2
[IO.File]::WriteAllText($ReceiptPath, (([ordered]@{ status='OWN_CLIENT_STOPPED'; pid=$ExpectedPid; gone=($null -eq (Get-Process -Id $ExpectedPid -ErrorAction SilentlyContinue)); reason='faction screen unresponsive to 次へ; run closed by process stop'; stoppedAtUtc=[datetime]::UtcNow.ToString('o') } | ConvertTo-Json)), [Text.UTF8Encoding]::new($false)); exit 0
