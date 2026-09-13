param([Parameter(Mandatory=$true)][string]$RunId,[Parameter(Mandatory=$true)][string]$ReceiptPath)
$ErrorActionPreference='SilentlyContinue'
$root="C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1"; $run=Join-Path $root $RunId; $fail=Join-Path $root ($RunId+'-fresh-run-failure.json')
function T($p){ if(Test-Path -LiteralPath $p){ [string](Get-Content -LiteralPath $p -Raw) } else { $null } }
$res=[ordered]@{ failureExists=(Test-Path -LiteralPath $fail); failure=(T $fail); runRootExists=(Test-Path -LiteralPath $run); runRootFiles=@(Get-ChildItem -LiteralPath $run | ForEach-Object { $_.Name }); procs=@(Get-CimInstance Win32_Process | Where-Object { $_.Name -like 'G7MTClient*' -or $_.Name -in 'Logh7.Server.exe','postgres.exe' } | ForEach-Object { "$($_.Name):$($_.ProcessId)" }); provisionOut=(T (Join-Path $run 'account-provision.stdout')); provisionErr=(T (Join-Path $run 'account-provision.stderr')); serverErr=(T (Join-Path $run 'server.stderr')); prepInRun=(T (Join-Path $run 'fresh-run-prep.json')) }
$parent=Split-Path -Parent $ReceiptPath; if(-not (Test-Path -LiteralPath $parent)){ New-Item -ItemType Directory -Path $parent -Force | Out-Null }
[IO.File]::WriteAllText($ReceiptPath, ($res | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
exit 0
