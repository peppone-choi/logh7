[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$RunId,
    [Parameter(Mandatory=$true)][string]$ReceiptPath,
    [string]$PrepFileName = 'fresh-run-prep.json'   # relaunched runs MUST pass -PrepFileName relaunch-prep.json explicitly
)
# Clean stop of THIS run's authority server and PostgreSQL copy (never the sealed source). The client is
# expected to have exited through its own ゲーム終了 path already; if it is still alive it is reported, not killed.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = "C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1\$RunId"
# The receipt is the diagnostic: every failure (including prep load / pg_ctl discovery) must land in it.
$r = [ordered]@{ status = 'PENDING'; runId = $RunId; prepFileName = $PrepFileName; stoppedAtUtc = $null; client = [ordered]@{ pid = $null; aliveBefore = $null }; authority = [ordered]@{ pid = $null }; postgres = [ordered]@{ dataDirectory = $null }; operations = [ordered]@{ processStops = 0; gameInputs = 0 } }
try {
    $prep = Get-Content -LiteralPath (Join-Path $root $PrepFileName) -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($prep.runId -cne $RunId) { throw 'PREP_RUN_MISMATCH' }
    $data = [string]$prep.database.dataDirectory; $serverPid = [int]$prep.authority.pid; $clientPid = [int]$prep.client.pid
    $r.client.pid = $clientPid; $r.client.aliveBefore = ($null -ne (Get-Process -Id $clientPid -ErrorAction SilentlyContinue)); $r.authority.pid = $serverPid; $r.postgres.dataDirectory = $data
    $pgCtlItem = Get-ChildItem -LiteralPath (Join-Path $root 'postgresql') -Recurse -Filter 'pg_ctl.exe' | Select-Object -First 1
    if ($null -eq $pgCtlItem) { throw 'PG_CTL_NOT_FOUND_IN_RUN_COPY' }
    $bin = Split-Path -Parent $pgCtlItem.FullName
    $pgCtl = Join-Path $bin 'pg_ctl.exe'; $pgControlData = Join-Path $bin 'pg_controldata.exe'
    $srv = Get-CimInstance Win32_Process -Filter "ProcessId=$serverPid" -ErrorAction SilentlyContinue
    if ($srv -and $srv.ExecutablePath -cne [string]$prep.authority.path) { throw 'SERVER_IDENTITY_MISMATCH' }
    if ($srv) { Stop-Process -Id $serverPid -Force; $r.operations.processStops++ }
    $deadline = [datetime]::UtcNow.AddSeconds(15); do { Start-Sleep -Milliseconds 250 } while ((Get-Process -Id $serverPid -ErrorAction SilentlyContinue) -and [datetime]::UtcNow -lt $deadline)
    $r.authority.gone = ($null -eq (Get-Process -Id $serverPid -ErrorAction SilentlyContinue))
    $r.authority.listenerGone = (@(Get-NetTCPConnection -State Listen -LocalPort 47900 -ErrorAction SilentlyContinue | Where-Object { $_.LocalAddress -eq '202.8.80.179' }).Count -eq 0)
    $owned = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -ceq 'postgres.exe' -and $_.CommandLine -and $_.CommandLine.Replace('/','\').ToLowerInvariant().Contains($data.ToLowerInvariant()) })
    $r.postgres.ownedBefore = $owned.Count
    if ($owned.Count -gt 0) { & $pgCtl -D $data -m fast -s -w stop; $r.postgres.stopExitCode = $LASTEXITCODE; $r.operations.processStops++ }
    $r.postgres.gone = (@(Get-CimInstance Win32_Process | Where-Object { $_.Name -ceq 'postgres.exe' }).Count -eq 0)
    $r.postgres.listenerGone = (@(Get-NetTCPConnection -State Listen -LocalPort 55432 -ErrorAction SilentlyContinue).Count -eq 0)
    $r.postgres.postmasterPidGone = -not (Test-Path -LiteralPath (Join-Path $data 'postmaster.pid'))
    $env:LC_MESSAGES = 'C'; $env:LANG = 'C'
    $lines = @(& $pgControlData $data 2>&1 | ForEach-Object { [string]$_ }); $st = @($lines | Where-Object { $_ -match '^Database cluster state:' }) | Select-Object -First 1
    $r.postgres.pgControlState = $(if ($st) { ($st -replace '^Database cluster state:\s*', '').Trim() } else { 'UNPARSED' })
    $r.postgres.pgControlSha256 = (Get-FileHash -LiteralPath (Join-Path $data 'global\pg_control') -Algorithm SHA256).Hash
    $r.client.aliveAfter = ($null -ne (Get-Process -Id $clientPid -ErrorAction SilentlyContinue))
    $r.stoppedAtUtc = [datetime]::UtcNow.ToString('o')
    $r.status = $(if ($r.authority.gone -and $r.postgres.gone -and $r.postgres.pgControlState -eq 'shut down') { 'RUN_RUNTIME_CLEANLY_STOPPED' } else { 'RUN_RUNTIME_STOP_INCOMPLETE' })
} catch { $r.status = 'RUN_RUNTIME_STOP_FAILED'; $r.error = $_.Exception.Message; $r.errorLine = [string]$_.InvocationInfo.ScriptLineNumber }
[IO.File]::WriteAllText($ReceiptPath, (($r | ConvertTo-Json -Depth 6) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
if ($r.status -ne 'RUN_RUNTIME_CLEANLY_STOPPED') { exit 1 }
exit 0
