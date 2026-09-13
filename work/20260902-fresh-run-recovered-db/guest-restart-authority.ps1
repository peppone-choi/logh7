[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$RunId,
    [Parameter(Mandatory=$true)][string]$ServerNoticeText,
    [string]$PrepFileName = 'fresh-run-prep.json',
    [string]$OutputPrepFileName = 'relaunch-prep.json',
    [string]$WireFileName = 'server-wire-2.jsonl',
    [string]$BindAddress = '202.8.80.179',
    [int]$AuthorityPort = 47900,
    [int]$PostgresPort = 55432
)
# Server-restart / reconnect leg for an existing run: stops THIS run's client and authority (PIDs from the
# run's own prep receipt), keeps the run's PostgreSQL copy running, rotates the DB password again (memory
# only), restarts the same authority binary on the same database, relaunches the original client, and
# writes a new prep receipt with the fresh PID/HWND. Must run in the interactive console session.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$phase = 'INIT'
$root = "C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1\$RunId"
$prep = Get-Content -LiteralPath (Join-Path $root $PrepFileName) -Raw -Encoding UTF8 | ConvertFrom-Json
$outPath = Join-Path $root $OutputPrepFileName
if (Test-Path -LiteralPath $outPath) { throw 'RELAUNCH_RECEIPT_EXISTS' }
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash }
if (-not ('RelaunchNative' -as [type])) { Add-Type -TypeDefinition @'
using System;using System.Runtime.InteropServices;using System.Text;
public static class RelaunchNative{
 public delegate bool EnumWindowsProc(IntPtr h,IntPtr p);[StructLayout(LayoutKind.Sequential)]public struct RECT{public int Left,Top,Right,Bottom;}
 [DllImport("user32.dll")]public static extern bool EnumWindows(EnumWindowsProc c,IntPtr p);[DllImport("user32.dll")]public static extern uint GetWindowThreadProcessId(IntPtr h,out uint p);
 [DllImport("user32.dll")]public static extern bool IsWindowVisible(IntPtr h);[DllImport("user32.dll")]public static extern bool GetWindowRect(IntPtr h,out RECT r);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)]public static extern int GetWindowText(IntPtr h,StringBuilder s,int n);[DllImport("kernel32.dll")]public static extern uint WTSGetActiveConsoleSessionId();}
'@ }
function Get-OwnedWindows([int]$OwnerPid) { $rows = [Collections.Generic.List[object]]::new(); $cb = [RelaunchNative+EnumWindowsProc]{ param([IntPtr]$h, [IntPtr]$u); $o = [uint32]0; [void][RelaunchNative]::GetWindowThreadProcessId($h, [ref]$o); if ([int]$o -eq $OwnerPid -and [RelaunchNative]::IsWindowVisible($h)) { $rc = [RelaunchNative+RECT]::new(); [void][RelaunchNative]::GetWindowRect($h, [ref]$rc); $sb = [Text.StringBuilder]::new(256); [void][RelaunchNative]::GetWindowText($h, $sb, 256); $rows.Add([ordered]@{ hwnd = ('0x{0:X16}' -f $h.ToInt64()); ownerPid = [int]$o; visible = $true; title = $sb.ToString(); rect = [ordered]@{ left = $rc.Left; top = $rc.Top; right = $rc.Right; bottom = $rc.Bottom } }) }; $true }; [void][RelaunchNative]::EnumWindows($cb, [IntPtr]::Zero); @($rows) }
$r = [ordered]@{ schemaVersion = 1; status = 'PENDING'; runId = $RunId; previousPrep = $PrepFileName; phase = $null; error = $null; session = [ordered]@{}; stop = [ordered]@{}; database = [ordered]@{}; authority = [ordered]@{}; client = [ordered]@{}; stability = [ordered]@{}; operations = [ordered]@{ processStops = 0; launches = 0; gameInputs = 0; automaticGameInputs = 0; inputRetries = 0; sourceWrites = 0 } }
$dbPassword = $null
try {
    $phase = 'PREFLIGHT'
    $self = [Diagnostics.Process]::GetCurrentProcess().SessionId; $console = [int][RelaunchNative]::WTSGetActiveConsoleSessionId()
    $r.session = [ordered]@{ selfSessionId = $self; activeConsoleSessionId = $console }
    if ($self -ne $console) { throw "RELAUNCH_NOT_IN_INTERACTIVE_SESSION:$self/$console" }
    if ($prep.runId -cne $RunId) { throw 'PREP_RUN_MISMATCH' }
    $oldClientPid = [int]$prep.client.pid; $oldServerPid = [int]$prep.authority.pid
    $data = [string]$prep.database.dataDirectory; $serverExe = [string]$prep.authority.path; $clientPath = [string]$prep.client.path; $clientDir = [string]$prep.client.workingDirectory
    $oldClient = Get-CimInstance Win32_Process -Filter "ProcessId=$oldClientPid" -ErrorAction SilentlyContinue
    $oldServer = Get-CimInstance Win32_Process -Filter "ProcessId=$oldServerPid" -ErrorAction SilentlyContinue
    if ($oldClient -and $oldClient.ExecutablePath -cne $clientPath) { throw 'OLD_CLIENT_IDENTITY_MISMATCH' }
    if ($oldServer -and $oldServer.ExecutablePath -cne $serverExe) { throw 'OLD_SERVER_IDENTITY_MISMATCH' }
    $pgOwned = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -ceq 'postgres.exe' -and $_.CommandLine -and $_.CommandLine.Replace('/','\').ToLowerInvariant().Contains($data.ToLowerInvariant()) })
    if ($pgOwned.Count -lt 1) { throw 'RUN_POSTGRES_NOT_RUNNING' }
    $bin = Split-Path -Parent ((Get-ChildItem -LiteralPath (Join-Path $root 'postgresql') -Recurse -Filter 'psql.exe' | Select-Object -First 1).FullName)
    $psql = Join-Path $bin 'psql.exe'; $pgCtl = Join-Path $bin 'pg_ctl.exe'

    $phase = 'STOP_OLD'
    $stopped = [ordered]@{ clientPid = $oldClientPid; clientWasAlive = ($null -ne $oldClient); serverPid = $oldServerPid; serverWasAlive = ($null -ne $oldServer) }
    if ($oldClient) { Stop-Process -Id $oldClientPid -Force; $r.operations.processStops++ }
    if ($oldServer) { Stop-Process -Id $oldServerPid -Force; $r.operations.processStops++ }
    $deadline = [datetime]::UtcNow.AddSeconds(15)
    do { Start-Sleep -Milliseconds 250; $cg = $null -eq (Get-Process -Id $oldClientPid -ErrorAction SilentlyContinue); $sg = $null -eq (Get-Process -Id $oldServerPid -ErrorAction SilentlyContinue); $lg = @(Get-NetTCPConnection -State Listen -LocalPort $AuthorityPort -ErrorAction SilentlyContinue | Where-Object { $_.LocalAddress -eq $BindAddress }).Count -eq 0 } while ((-not $cg -or -not $sg -or -not $lg) -and [datetime]::UtcNow -lt $deadline)
    $stopped.clientGone = $cg; $stopped.serverGone = $sg; $stopped.listenerGone = $lg; $stopped.stoppedAtUtc = [datetime]::UtcNow.ToString('o'); $r.stop = $stopped
    if (-not ($cg -and $sg -and $lg)) { throw 'OLD_RUNTIME_NOT_STOPPED' }

    $phase = 'ROTATE_PASSWORD'
    $hba = Join-Path $data 'pg_hba.conf'; $backup = Join-Path $root 'pg_hba.original.conf'; $backupHash = Hash $backup
    $text = [IO.File]::ReadAllText($hba, [Text.Encoding]::UTF8); $pattern = '(?m)^(\s*host\s+all\s+all\s+127\.0\.0\.1/32\s+)scram-sha-256\s*$'
    if ([Text.RegularExpressions.Regex]::Matches($text, $pattern).Count -ne 1) { throw 'HBA_PATTERN_INVALID' }
    [IO.File]::WriteAllText($hba, [Text.RegularExpressions.Regex]::Replace($text, $pattern, '$1trust'), [Text.UTF8Encoding]::new($false))
    & $pgCtl -D $data -s reload; if ($LASTEXITCODE -ne 0) { throw 'HBA_RELOAD_FAILED' }; Start-Sleep -Milliseconds 400
    $random = New-Object byte[] 24; $rng = [Security.Cryptography.RandomNumberGenerator]::Create(); try { $rng.GetBytes($random) } finally { $rng.Dispose() }
    $dbPassword = [Convert]::ToBase64String($random).TrimEnd('='); [Array]::Clear($random, 0, $random.Length); $escaped = $dbPassword.Replace("'", "''")
    & $psql -X -h 127.0.0.1 -p $PostgresPort -U logh7 -d logh7 -v ON_ERROR_STOP=1 -c "ALTER ROLE logh7 PASSWORD '$escaped';" *> (Join-Path $root 'alter-role-2.log'); if ($LASTEXITCODE -ne 0) { throw 'DB_PASSWORD_ROTATION_FAILED' }
    $cell = ([string](@(& $psql -X -h 127.0.0.1 -p $PostgresPort -U logh7 -d logh7 -t -A -c "select unit_id||'|'||current_cell_id||'|'||authority_version from original_grid_unit;") | Select-Object -First 1)).Trim()
    Copy-Item -LiteralPath $backup -Destination $hba -Force; & $pgCtl -D $data -s reload; if ($LASTEXITCODE -ne 0) { throw 'HBA_RESTORE_RELOAD_FAILED' }
    $r.database = [ordered]@{ dataDirectory = $data; port = $PostgresPort; keptRunning = $true; gridUnitBeforeRestart = $cell; hbaRestored = ((Hash $hba) -ceq $backupHash); passwordRotated = $true; passwordRecorded = $false }

    $phase = 'AUTHORITY_RESTART'
    $wire = Join-Path $root $WireFileName
    $env:LOGH7_DB_CONNECTION = "Host=127.0.0.1;Port=$PostgresPort;Database=logh7;Username=logh7;Password=$dbPassword;SSL Mode=Disable;Timeout=5;Command Timeout=5"; $env:LOGH7_SERVER_NOTICE = $ServerNoticeText
    $server = Start-Process -FilePath $serverExe -ArgumentList @('serve-original','--bind',$BindAddress,'--port',"$AuthorityPort",'--advertise',$BindAddress,'--session-bind','127.0.0.2','--session-advertise','127.0.0.2','--receipt',$wire) -WorkingDirectory (Split-Path -Parent $serverExe) -RedirectStandardOutput (Join-Path $root 'server-2.stdout') -RedirectStandardError (Join-Path $root 'server-2.stderr') -PassThru
    $null = $server.Handle; $r.operations.launches++
    $env:LOGH7_DB_CONNECTION = $null; $env:LOGH7_SERVER_NOTICE = $null
    $deadline = [datetime]::UtcNow.AddSeconds(25)
    do { Start-Sleep -Milliseconds 250; $l = @(Get-NetTCPConnection -State Listen -LocalPort $AuthorityPort -ErrorAction SilentlyContinue | Where-Object { $_.LocalAddress -eq $BindAddress -and $_.OwningProcess -eq $server.Id }) } while ($l.Count -ne 1 -and [datetime]::UtcNow -lt $deadline)
    if ($l.Count -ne 1) { throw "AUTHORITY_LISTENER_NOT_READY:$(((Get-Content -LiteralPath (Join-Path $root 'server-2.stderr') -Tail 5 -ErrorAction SilentlyContinue) | ForEach-Object { [string]$_ }) -join ' | ')" }
    $r.authority = [ordered]@{ pid = $server.Id; path = $serverExe; sha256 = (Hash $serverExe); bindAddress = $BindAddress; port = $AuthorityPort; sessionBindAddress = '127.0.0.2'; wireReceiptPath = $wire; serverNoticeText = $ServerNoticeText; listenerCount = 1; previousPid = $oldServerPid }

    $phase = 'CLIENT_RELAUNCH'
    if ((Hash $clientPath) -cne [string]$prep.client.sha256) { throw 'CLIENT_HASH_DRIFT' }
    $launchedAt = [datetime]::UtcNow
    $client = Start-Process -FilePath $clientPath -WorkingDirectory $clientDir -PassThru; $null = $client.Handle; $r.operations.launches++
    $deadline = [datetime]::UtcNow.AddSeconds(30); $windows = @()
    do { Start-Sleep -Milliseconds 200; if ($client.HasExited) { throw "CLIENT_EXITED_BEFORE_HWND:exit=$($client.ExitCode)" }; $windows = @(Get-OwnedWindows $client.Id) } while ($windows.Count -lt 1 -and [datetime]::UtcNow -lt $deadline)
    if ($windows.Count -lt 1) { throw 'CLIENT_HWND_NOT_READY' }
    $main = $windows | Sort-Object { ($_.rect.right - $_.rect.left) * ($_.rect.bottom - $_.rect.top) } -Descending | Select-Object -First 1
    $phase = 'STABILITY'
    $stableStart = [datetime]::UtcNow; $samples = 0
    do { Start-Sleep -Milliseconds 250; if ($client.HasExited) { throw 'CLIENT_EXITED_DURING_STABILITY' }; $now = @(Get-OwnedWindows $client.Id | Where-Object { $_.hwnd -eq $main.hwnd }); $nl = @(Get-NetTCPConnection -State Listen -LocalPort $AuthorityPort -ErrorAction SilentlyContinue | Where-Object { $_.LocalAddress -eq $BindAddress -and $_.OwningProcess -eq $server.Id }); if ($now.Count -ne 1 -or $nl.Count -ne 1) { throw 'CONTINUOUS_LIVENESS_BROKEN' }; $samples++ } while (([datetime]::UtcNow - $stableStart).TotalMilliseconds -lt 5000)
    $cim = Get-CimInstance Win32_Process -Filter "ProcessId=$($client.Id)"
    $r.client = [ordered]@{ variant = [string]$prep.client.variant; mode = [string]$prep.client.mode; pid = $client.Id; previousPid = $oldClientPid; startTimeUtc = (Get-Process -Id $client.Id).StartTime.ToUniversalTime().ToString('o'); path = $cim.ExecutablePath; workingDirectory = $clientDir; sha256 = (Hash $clientPath); sessionId = $cim.SessionId; hwnd = $main.hwnd; title = $main.title; windowRect = $main.rect; ownedWindowCount = $windows.Count; koreanRuntimeEnv = $false }
    $r.stability = [ordered]@{ durationMilliseconds = [int]([datetime]::UtcNow - $stableStart).TotalMilliseconds; sampleCount = $samples; continuous = $true }
    $r.status = 'FRESH_RUN_PREINPUT_READY'
} catch { $r.error = $_.Exception.Message; $r.status = 'RELAUNCH_FAILED' }
finally {
    $r.phase = $phase; $dbPassword = $null; $env:LOGH7_DB_CONNECTION = $null; $env:LOGH7_SERVER_NOTICE = $null
    $r.wireTail = @(Get-Content -LiteralPath (Join-Path $root $WireFileName) -Tail 5 -ErrorAction SilentlyContinue | ForEach-Object { [string]$_ })
    $target = $outPath; if (Test-Path -LiteralPath $target) { $target = $target + '.' + [guid]::NewGuid().ToString('N') + '.json' }
    [IO.File]::WriteAllText($target, (($r | ConvertTo-Json -Depth 12) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
}
if ($r.status -ne 'FRESH_RUN_PREINPUT_READY') { exit 1 }
exit 0
