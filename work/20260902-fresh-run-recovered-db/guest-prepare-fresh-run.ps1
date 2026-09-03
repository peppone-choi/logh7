[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$RunId,
    [Parameter(Mandatory=$true)][string]$ServerNoticeText,
    [ValidateSet('Install','Copy')][string]$ClientMode = 'Install',
    [switch]$KoreanRuntime,
    [string]$ProxyPath,
    [string]$SidecarPath,
    [string]$SourceRunId = '20260902T083838Z-natural-l1-relogin-v1',
    [string]$RunRoot = 'C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1',
    [string]$InstallRoot = 'C:\LOGH7_ORACLE',
    [string]$ClientVariantFile = 'G7MTClient.item114.exe',
    [string]$ExpectedClientSha256 = 'F93592F369F131617B216FD10E66988C144AC56698859817F8FEB034EA95528F',
    [string]$ExpectedSourcePgControlSha256 = '348153D848E464C416983638B69EA84508C35B0598BA3BD46467DFC4BF94BC09',
    [string]$ExpectedPostgresRuntimeSha256 = '37E1C5CF4EC85D49E4B2E95065C4DCD516B8C69523378BEC8F40EACC3A91E599',
    [string]$ExpectedServerZipSha256 = 'DE6456A100DF3186EF49C4497C0007E011F80BA1654B440939CF226BC6C1A97B',
    [string]$ExpectedServerExeSha256 = 'D214CF575FC43D243D0018D76EE3E5E428ABBECAE190D95D61A0F50EB2C5E7DB',
    [string]$ExpectedServerDllSha256 = '8BB4CA653264254F710B5212FF2FDEC7CCA8DADDA18933427186E77F457B6624',
    [string]$ExpectedMigration0011Sha256 = '9750CEFDFA7D5C2327AFD5C46B71D63CBE644944D5460C7AE32E9B5599AFA92B',
    [string]$ExpectedProxySha256 = 'B5AA1848BE5618BABE19E5A827578CA9C336BD86ABCCD0E2CB1F5716D1942128',
    [string]$ExpectedSidecarSha256 = '0A8959DDC56DA7B61E79D582AB937907123DCE09F4D5D6D2AC8C3F27A2282956',
    [string]$BindAddress = '202.8.80.179',
    [int]$AuthorityPort = 47900,
    [int]$PostgresPort = 55432,
    [string]$PostgresRuntimeZipPath = '',
    [string]$ServerZipPath = '',
    [string]$AccountSecretRoot = '',
    [switch]$ProvisionNewAccount,
    [switch]$KoreanDiag,
    [string]$SsLoginOk = '',
    [switch]$SkipMigrationCheck,
    [string]$CelestialKlass = '',
    [string]$CelestialVariant = '',
    [string]$CelestialTwoDistinct = '',
    [string]$ExtraCardCommands = '',
    [string]$NinmeiProbe = '',
    [string]$StaticCardAppointer = '',
    [string]$NinmeiChars = '',
    [string]$CommandEcho = '',
    [string]$ListKindProbe = '',
    [string]$WorldCardId = '',
    [string]$DataRoot = '',
    [string]$InfoProbe = '',
    [string]$NinmeiCards = '',
    [string]$ClientExeOverride = '',
    [string]$ExpectedClientOverrideSha256 = '',
    [string]$CelestialClassSweep = ''
)
# Fresh sealed run on the recovered source database (goal step 2).
# Must run in the interactive console session (VIX RunInteractive) so the client window is visible.
# Copies the recovered cluster forward (source untouched), deploys the v128 authority, and launches the
# original client exactly once. Leaves PostgreSQL, the authority and the client running for later input.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$phase = 'INIT'
$sourceRoot = [IO.Path]::GetFullPath((Join-Path $RunRoot $SourceRunId))
$runDir = [IO.Path]::GetFullPath((Join-Path $RunRoot $RunId))
$receiptPath = Join-Path $runDir 'fresh-run-prep.json'
$failurePath = Join-Path $RunRoot ($RunId + '-fresh-run-failure.json')
$r = [ordered]@{
    schemaVersion = 1; status = 'PENDING'; runId = $RunId; sourceRunId = $SourceRunId; clientMode = $ClientMode; koreanRuntime = [bool]$KoreanRuntime
    phase = $null; error = $null; session = [ordered]@{}; preflight = [ordered]@{}; database = [ordered]@{}; authority = [ordered]@{}; client = [ordered]@{}; stability = [ordered]@{}
    operations = [ordered]@{ launches = 0; gameInputs = 0; automaticGameInputs = 0; inputRetries = 0; sourceWrites = 0 }
}
function Hash([string]$Path) { if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "FRESH_RUN_INPUT_MISSING:$Path" }; (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash }
function Norm([string]$Path) { $Path.Replace('/','\').TrimEnd('\').ToLowerInvariant() }
function WriteJson([object]$Value, [string]$Path) { if (Test-Path -LiteralPath $Path) { throw "FRESH_RUN_RECEIPT_EXISTS:$Path" }; [IO.File]::WriteAllText($Path, (($Value | ConvertTo-Json -Depth 12) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false)) }
if (-not ('FreshRunNative' -as [type])) { Add-Type -TypeDefinition @'
using System;using System.Runtime.InteropServices;using System.Text;
public static class FreshRunNative{
 public delegate bool EnumWindowsProc(IntPtr h,IntPtr p);[StructLayout(LayoutKind.Sequential)]public struct RECT{public int Left,Top,Right,Bottom;}
 [DllImport("user32.dll")]public static extern bool EnumWindows(EnumWindowsProc c,IntPtr p);[DllImport("user32.dll")]public static extern uint GetWindowThreadProcessId(IntPtr h,out uint p);
 [DllImport("user32.dll")]public static extern bool IsWindowVisible(IntPtr h);[DllImport("user32.dll")]public static extern bool GetWindowRect(IntPtr h,out RECT r);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)]public static extern int GetWindowText(IntPtr h,StringBuilder s,int n);[DllImport("kernel32.dll")]public static extern uint WTSGetActiveConsoleSessionId();
 [DllImport("user32.dll")]public static extern IntPtr GetForegroundWindow();}
'@ }
function Get-OwnedWindows([int]$OwnerPid) {
    $rows = [Collections.Generic.List[object]]::new()
    $cb = [FreshRunNative+EnumWindowsProc]{ param([IntPtr]$h, [IntPtr]$u); $o = [uint32]0; [void][FreshRunNative]::GetWindowThreadProcessId($h, [ref]$o); if ([int]$o -eq $OwnerPid -and [FreshRunNative]::IsWindowVisible($h)) { $rc = [FreshRunNative+RECT]::new(); [void][FreshRunNative]::GetWindowRect($h, [ref]$rc); $sb = [Text.StringBuilder]::new(256); [void][FreshRunNative]::GetWindowText($h, $sb, 256); $rows.Add([ordered]@{ hwnd = ('0x{0:X16}' -f $h.ToInt64()); ownerPid = [int]$o; visible = $true; title = $sb.ToString(); rect = [ordered]@{ left = $rc.Left; top = $rc.Top; right = $rc.Right; bottom = $rc.Bottom } }) }; $true }
    [void][FreshRunNative]::EnumWindows($cb, [IntPtr]::Zero); @($rows)
}
$dbPassword = $null
try {
    $phase = 'PREFLIGHT'
    if ($RunId -cnotmatch '^20[0-9]{6}T[0-9]{6}Z-natural-l1-relogin-v1$' -or $RunId -ceq $SourceRunId) { throw 'FRESH_RUN_ID_INVALID' }
    if ($ServerNoticeText.Length -lt 1 -or $ServerNoticeText.Length -gt 64 -or $ServerNoticeText -cnotmatch '^[\x20-\x7E]+$') { throw 'FRESH_RUN_SERVER_NOTICE_INVALID' }
    if (Test-Path -LiteralPath $runDir) { throw 'FRESH_RUN_DESTINATION_EXISTS' }
    if ($KoreanRuntime -and $ClientMode -ne 'Copy') { throw 'FRESH_RUN_KOREAN_REQUIRES_COPY_MODE' }
    if ($ClientExeOverride -and $ClientMode -ne 'Copy') { throw 'FRESH_RUN_CLIENT_OVERRIDE_REQUIRES_COPY_MODE' }
    $selfSession = [Diagnostics.Process]::GetCurrentProcess().SessionId
    $r.session = [ordered]@{ selfSessionId = $selfSession; activeConsoleSessionId = [int][FreshRunNative]::WTSGetActiveConsoleSessionId(); user = [Security.Principal.WindowsIdentity]::GetCurrent().Name; interactive = [Environment]::UserInteractive }
    if ($r.session.selfSessionId -ne $r.session.activeConsoleSessionId) { throw "FRESH_RUN_NOT_IN_INTERACTIVE_SESSION:$($r.session.selfSessionId)/$($r.session.activeConsoleSessionId)" }
    $forbidden = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -like 'G7MTClient*.exe' -or $_.Name -in @('Logh7.Server.exe','postgres.exe') })
    $authorityListeners = @(Get-NetTCPConnection -State Listen -LocalPort $AuthorityPort -ErrorAction SilentlyContinue | Where-Object { $_.LocalAddress -eq $BindAddress })
    $pgListeners = @(Get-NetTCPConnection -State Listen -LocalPort $PostgresPort -ErrorAction SilentlyContinue)
    $sourceData = Join-Path $sourceRoot 'postgres-data'; $sourceControl = Join-Path $sourceData 'global\pg_control'
    $pgZip = if ($PostgresRuntimeZipPath) { $PostgresRuntimeZipPath } else { Join-Path $sourceRoot 'pg-runtime-full.zip' }
    $serverZip = if ($ServerZipPath) { $ServerZipPath } else { Join-Path $sourceRoot 'logh7-server-win-x64.zip' }
    $secretRoot = if ($AccountSecretRoot) { $AccountSecretRoot } else { $sourceRoot }
    $installExe = Join-Path (Join-Path $InstallRoot 'exe') $ClientVariantFile
    $r.preflight = [ordered]@{
        forbiddenProcesses = @($forbidden | ForEach-Object { "$($_.Name):$($_.ProcessId)" }); authorityListenerCount = $authorityListeners.Count; postgresListenerCount = $pgListeners.Count
        loopback47900Portproxy = @(Get-NetTCPConnection -State Listen -LocalPort 47900 -LocalAddress 127.0.0.1 -ErrorAction SilentlyContinue).Count
        sourcePgControlSha256 = (Hash $sourceControl); sourcePostmasterPidPresent = (Test-Path -LiteralPath (Join-Path $sourceData 'postmaster.pid'))
        postgresRuntimeZipSha256 = (Hash $pgZip); serverZipSha256 = (Hash $serverZip); installClientSha256 = (Hash $installExe)
        dataRootPresent = (Test-Path -LiteralPath (Join-Path $InstallRoot 'data\MsgDat')); freeDiskMB = [int]((Get-PSDrive C).Free / 1MB); freePhysMB = [int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1024)
    }
    if ($forbidden.Count -ne 0) { throw 'FRESH_RUN_FORBIDDEN_PROCESS' }
    if ($authorityListeners.Count -ne 0 -or $pgListeners.Count -ne 0) { throw 'FRESH_RUN_PORT_BUSY' }
    if ($r.preflight.sourcePostmasterPidPresent -or $r.preflight.sourcePgControlSha256 -cne $ExpectedSourcePgControlSha256) { throw 'FRESH_RUN_SOURCE_NOT_CLEAN' }
    if ($r.preflight.postgresRuntimeZipSha256 -cne $ExpectedPostgresRuntimeSha256 -or $r.preflight.serverZipSha256 -cne $ExpectedServerZipSha256 -or $r.preflight.installClientSha256 -cne $ExpectedClientSha256) { throw 'FRESH_RUN_INPUT_HASH_INVALID' }
    if (-not $r.preflight.dataRootPresent) { throw 'FRESH_RUN_DATA_ROOT_MISSING' }
    if ($KoreanRuntime) { if ((Hash $ProxyPath) -cne $ExpectedProxySha256 -or (Hash $SidecarPath) -cne $ExpectedSidecarSha256) { throw 'FRESH_RUN_KOREAN_PAYLOAD_HASH_INVALID' } }

    $phase = 'CREATE_RUN_ROOT'
    New-Item -ItemType Directory -Path $runDir | Out-Null
    WriteJson ([ordered]@{ status = 'FRESH_RUN_PLAN'; runId = $RunId; sourceRunId = $SourceRunId; clientMode = $ClientMode; koreanRuntime = [bool]$KoreanRuntime; preflight = $r.preflight; session = $r.session }) (Join-Path $runDir 'fresh-run-plan.json')

    $phase = 'COPY_DATABASE'
    $data = Join-Path $runDir 'postgres-data'
    $rc = Start-Process -FilePath 'robocopy.exe' -ArgumentList @('"' + $sourceData + '"', '"' + $data + '"', '/E', '/COPY:DAT', '/R:1', '/W:1', '/NFL', '/NDL', '/NJH', '/NJS', '/NP') -Wait -PassThru -WindowStyle Hidden
    if ($rc.ExitCode -ge 8) { throw "FRESH_RUN_ROBOCOPY_FAILED:$($rc.ExitCode)" }
    foreach ($d in @('pg_commit_ts','pg_dynshmem','pg_logical\mappings','pg_logical\snapshots','pg_multixact\members','pg_multixact\offsets','pg_notify','pg_replslot','pg_serial','pg_snapshots','pg_stat','pg_stat_tmp','pg_subtrans','pg_tblspc','pg_twophase','pg_wal\archive_status','pg_wal\summaries','pg_xact')) { $p = Join-Path $data $d; if (-not (Test-Path -LiteralPath $p)) { New-Item -ItemType Directory -Path $p -Force | Out-Null } }
    if (Test-Path -LiteralPath (Join-Path $data 'postmaster.pid')) { Remove-Item -LiteralPath (Join-Path $data 'postmaster.pid') -Force }
    if (-not $ProvisionNewAccount) {
        Copy-Item -LiteralPath (Join-Path $secretRoot 'account-secret.dpapi') -Destination (Join-Path $runDir 'account-secret.dpapi')
        Copy-Item -LiteralPath (Join-Path $secretRoot 'account-receipt.json') -Destination (Join-Path $runDir 'account-receipt.json')
    }
    $r.database.copyPgControlSha256 = Hash (Join-Path $data 'global\pg_control')

    $phase = 'POSTGRES_START'
    $pgRoot = Join-Path $runDir 'postgresql\pgsql'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory($pgZip, $pgRoot)
    $postgresExe = Get-ChildItem -LiteralPath $pgRoot -Recurse -Filter 'postgres.exe' | Select-Object -First 1
    if ($null -eq $postgresExe) { throw 'FRESH_RUN_POSTGRES_EXE_MISSING' }
    $bin = $postgresExe.DirectoryName; $pgCtl = Join-Path $bin 'pg_ctl.exe'; $psql = Join-Path $bin 'psql.exe'; $pgIsReady = Join-Path $bin 'pg_isready.exe'
    $hba = Join-Path $data 'pg_hba.conf'; $hbaBackup = Join-Path $runDir 'pg_hba.original.conf'; Copy-Item -LiteralPath $hba -Destination $hbaBackup
    $hbaText = [IO.File]::ReadAllText($hba, [Text.Encoding]::UTF8); $pattern = '(?m)^(\s*host\s+all\s+all\s+127\.0\.0\.1/32\s+)scram-sha-256\s*$'
    if ([Text.RegularExpressions.Regex]::Matches($hbaText, $pattern).Count -ne 1) { throw 'FRESH_RUN_HBA_PATTERN_INVALID' }
    [IO.File]::WriteAllText($hba, [Text.RegularExpressions.Regex]::Replace($hbaText, $pattern, '$1trust'), [Text.UTF8Encoding]::new($false))
    $pgLog = Join-Path $runDir 'postgres.log'
    $pgStart = Start-Process -FilePath $pgCtl -ArgumentList ('-D "{0}" -l "{1}" -o "-h 127.0.0.1 -p {2}" -w -t 60 start' -f $data, $pgLog, $PostgresPort) -WindowStyle Hidden -PassThru
    $null = $pgStart.Handle
    if (-not $pgStart.WaitForExit(90000) -or [int]$pgStart.ExitCode -ne 0) { throw "FRESH_RUN_POSTGRES_START_FAILED:$([string]$pgStart.ExitCode):$(((Get-Content -LiteralPath $pgLog -Tail 5 -ErrorAction SilentlyContinue) | ForEach-Object { [string]$_ }) -join ' | ')" }
    & $pgIsReady -h 127.0.0.1 -p $PostgresPort -d logh7 -U logh7 *> $null; if ($LASTEXITCODE -ne 0) { throw 'FRESH_RUN_POSTGRES_NOT_READY' }
    $random = New-Object byte[] 24; $rng = [Security.Cryptography.RandomNumberGenerator]::Create(); try { $rng.GetBytes($random) } finally { $rng.Dispose() }
    $dbPassword = [Convert]::ToBase64String($random).TrimEnd('='); [Array]::Clear($random, 0, $random.Length); $escaped = $dbPassword.Replace("'", "''")
    & $psql -X -h 127.0.0.1 -p $PostgresPort -U logh7 -d logh7 -v ON_ERROR_STOP=1 -c "ALTER ROLE logh7 PASSWORD '$escaped';" *> (Join-Path $runDir 'alter-role.log'); if ($LASTEXITCODE -ne 0) { throw 'FRESH_RUN_DB_PASSWORD_ROTATION_FAILED' }
    Copy-Item -LiteralPath $hbaBackup -Destination $hba -Force; & $pgCtl -D $data -s reload; if ($LASTEXITCODE -ne 0) { throw 'FRESH_RUN_HBA_RELOAD_FAILED' }
    $env:PGPASSWORD = $dbPassword
    $accountCount = ([string](@(& $psql -X -h 127.0.0.1 -p $PostgresPort -U logh7 -d logh7 -t -A -c 'select count(*) from account;') | Select-Object -First 1)).Trim()
    $characterCount = ([string](@(& $psql -X -h 127.0.0.1 -p $PostgresPort -U logh7 -d logh7 -t -A -c 'select count(*) from character;') | Select-Object -First 1)).Trim()
    $gridRow = ([string](@(& $psql -X -h 127.0.0.1 -p $PostgresPort -U logh7 -d logh7 -t -A -c "select unit_id||'|'||character_id||'|'||authority_card_id||'|'||current_cell_id from original_grid_unit;") | Select-Object -First 1)).Trim()
    $env:PGPASSWORD = $null
    $r.database = [ordered]@{ engine = 'PostgreSQL 17.11'; port = $PostgresPort; dataDirectory = $data; copyPgControlSha256 = $r.database.copyPgControlSha256; accountRows = [int]$accountCount; characterRows = [int]$characterCount; gridUnit = $gridRow; passwordRecorded = $false; passwordRotated = $true }
    if ($accountCount -cne '1' -or $characterCount -cne '1') { throw 'FRESH_RUN_PERSISTED_ROWS_INVALID' }

    $phase = 'AUTHORITY_START'
    $serverRoot = Join-Path $runDir 'server'; New-Item -ItemType Directory -Path $serverRoot | Out-Null
    & (Join-Path $env:SystemRoot 'System32\tar.exe') -xf $serverZip -C $serverRoot; if ($LASTEXITCODE -ne 0) { throw 'FRESH_RUN_SERVER_EXTRACT_FAILED' }
    $serverExe = Join-Path $serverRoot 'Logh7.Server.exe'; $serverDll = Join-Path $serverRoot 'Logh7.Server.dll'
    if ((Hash $serverExe) -cne $ExpectedServerExeSha256 -or (Hash $serverDll) -cne $ExpectedServerDllSha256) { throw 'FRESH_RUN_SERVER_BINARY_INVALID' }
    if (-not $SkipMigrationCheck) {
        $migration = @(Get-ChildItem -LiteralPath $serverRoot -Recurse -File | Where-Object { $_.Name -ceq '0011_original_grid_unit.sql' })
        if ($migration.Count -lt 1 -or @($migration | Where-Object { (Hash $_.FullName) -cne $ExpectedMigration0011Sha256 }).Count -ne 0) { throw 'FRESH_RUN_MIGRATION0011_INVALID' }
    }
    $wire = Join-Path $runDir 'server-wire.jsonl'
    $env:LOGH7_DB_CONNECTION = "Host=127.0.0.1;Port=$PostgresPort;Database=logh7;Username=logh7;Password=$dbPassword;SSL Mode=Disable;Timeout=5;Command Timeout=5"; $env:LOGH7_SERVER_NOTICE = $ServerNoticeText
    if ($SsLoginOk) { $env:LOGH7_SS_LOGINOK = $SsLoginOk } else { $env:LOGH7_SS_LOGINOK = $null }
    if ($CelestialKlass) { $env:LOGH7_CELESTIAL_KLASS = $CelestialKlass } else { $env:LOGH7_CELESTIAL_KLASS = $null }
    if ($CelestialVariant) { $env:LOGH7_CELESTIAL_VARIANT = $CelestialVariant } else { $env:LOGH7_CELESTIAL_VARIANT = $null }
    if ($CelestialTwoDistinct) { $env:LOGH7_CELESTIAL_TWO_DISTINCT = $CelestialTwoDistinct } else { $env:LOGH7_CELESTIAL_TWO_DISTINCT = $null; $env:LOGH7_CELESTIAL_CLASS_SWEEP = $null }
    if ($CelestialClassSweep) { $env:LOGH7_CELESTIAL_CLASS_SWEEP = $CelestialClassSweep } else { $env:LOGH7_CELESTIAL_CLASS_SWEEP = $null }
    if ($ExtraCardCommands -and $ExtraCardCommands -cnotmatch '^[0-9]{1,2}(,[0-9]{1,2})*$') { throw 'FRESH_RUN_EXTRA_CARD_COMMANDS_INVALID' }
    if ($ExtraCardCommands) { $env:LOGH7_EXTRA_CARD_COMMANDS = $ExtraCardCommands } else { $env:LOGH7_EXTRA_CARD_COMMANDS = $null }
    if ($NinmeiProbe -in @('1','2','3','4','5','6','7','8','9','10')) { $env:LOGH7_NINMEI_PROBE = $NinmeiProbe } else { $env:LOGH7_NINMEI_PROBE = $null }
    if ($InfoProbe -eq '1') { $env:LOGH7_INFO_PROBE = '1' } else { $env:LOGH7_INFO_PROBE = $null }
    if ($NinmeiCards -and $NinmeiCards -cnotmatch '^[0-9]{1,3}(,[0-9]{1,3})*$') { throw 'FRESH_RUN_NINMEI_CARDS_INVALID' }
    if ($NinmeiCards) { $env:LOGH7_NINMEI_CARDS = $NinmeiCards } else { $env:LOGH7_NINMEI_CARDS = $null }
    if ($StaticCardAppointer -and $StaticCardAppointer -cnotmatch '^[0-9]{1,3}:[0-9]{1,5}(,[0-9]{1,3}:[0-9]{1,5})*$') { throw 'FRESH_RUN_STATIC_CARD_APPOINTER_INVALID' }
    if ($StaticCardAppointer) { $env:LOGH7_STATIC_CARD_APPOINTER = $StaticCardAppointer } else { $env:LOGH7_STATIC_CARD_APPOINTER = $null }
    if ($NinmeiChars -and $NinmeiChars -cnotmatch '^[0-9]{1,10}(,[0-9]{1,10})*$') { throw 'FRESH_RUN_NINMEI_CHARS_INVALID' }
    if ($NinmeiChars) { $env:LOGH7_NINMEI_CHARS = $NinmeiChars } else { $env:LOGH7_NINMEI_CHARS = $null }
    if ($CommandEcho -eq '1') { $env:LOGH7_COMMAND_ECHO = '1' } else { $env:LOGH7_COMMAND_ECHO = $null }
    if ($WorldCardId -ne '') { if ($WorldCardId -notmatch '^[0-9]{1,5}$') { throw 'WORLD_CARD_ID_INVALID' }; $env:LOGH7_WORLD_CARD_ID = $WorldCardId } else { $env:LOGH7_WORLD_CARD_ID = $null }
    if ($ListKindProbe) { if ($ListKindProbe -notmatch '^[0-9A-Fa-f]{1,4}:[0-9A-Fa-f]{4}(,[0-9A-Fa-f]{1,4}:[0-9A-Fa-f]{4})*$') { throw 'LIST_KIND_PROBE_INVALID' }; $env:LOGH7_LIST_KIND_PROBE = $ListKindProbe } else { $env:LOGH7_LIST_KIND_PROBE = $null }
    if ($ProvisionNewAccount) {
        # NEW_DESIGN: provision a fresh disposable account in this run's database (secret DPAPI-protected for the guest user).
        $phase = 'ACCOUNT_PROVISION'
        $secretOut = Join-Path $runDir 'account-secret.dpapi'; $receiptOut = Join-Path $runDir 'account-receipt.json'
        $prov = Start-Process -FilePath $serverExe -ArgumentList @('account-provision-disposable','--secret',$secretOut,'--receipt',$receiptOut) -WorkingDirectory $serverRoot -RedirectStandardOutput (Join-Path $runDir 'account-provision.stdout') -RedirectStandardError (Join-Path $runDir 'account-provision.stderr') -PassThru -WindowStyle Hidden
        $null = $prov.Handle
        if (-not $prov.WaitForExit(60000) -or [int]$prov.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $secretOut) -or -not (Test-Path -LiteralPath $receiptOut)) { throw "FRESH_RUN_ACCOUNT_PROVISION_FAILED:$([string]$prov.ExitCode):$(((Get-Content -LiteralPath (Join-Path $runDir 'account-provision.stderr') -ErrorAction SilentlyContinue) | ForEach-Object { [string]$_ }) -join ' | ')" }
        $r.database.provisionedAccount = [ordered]@{ receipt = $receiptOut; secretProtected = $true; valuesRecorded = $false }
        $env:PGPASSWORD = $dbPassword
        $r.database.accountRowsAfterProvision = [int](([string](@(& $psql -X -h 127.0.0.1 -p $PostgresPort -U logh7 -d logh7 -t -A -c 'select count(*) from account;') | Select-Object -First 1)).Trim())
        $env:PGPASSWORD = $null
        $phase = 'AUTHORITY_START'
    }
    $server = Start-Process -FilePath $serverExe -ArgumentList @('serve-original','--bind',$BindAddress,'--port',"$AuthorityPort",'--advertise',$BindAddress,'--session-bind','127.0.0.2','--session-advertise','127.0.0.2','--receipt',$wire) -WorkingDirectory $serverRoot -RedirectStandardOutput (Join-Path $runDir 'server.stdout') -RedirectStandardError (Join-Path $runDir 'server.stderr') -PassThru
    $null = $server.Handle
    $env:LOGH7_DB_CONNECTION = $null; $env:LOGH7_SERVER_NOTICE = $null
    $deadline = [datetime]::UtcNow.AddSeconds(25)
    do { Start-Sleep -Milliseconds 250; $l = @(Get-NetTCPConnection -State Listen -LocalPort $AuthorityPort -ErrorAction SilentlyContinue | Where-Object { $_.LocalAddress -eq $BindAddress -and $_.OwningProcess -eq $server.Id }) } while ($l.Count -ne 1 -and [datetime]::UtcNow -lt $deadline)
    if ($l.Count -ne 1) { throw "FRESH_RUN_AUTHORITY_LISTENER_NOT_READY:$(((Get-Content -LiteralPath (Join-Path $runDir 'server.stderr') -Tail 5 -ErrorAction SilentlyContinue) | ForEach-Object { [string]$_ }) -join ' | ')" }
    $r.authority = [ordered]@{ pid = $server.Id; path = $serverExe; sha256 = (Hash $serverExe); bindAddress = $BindAddress; port = $AuthorityPort; sessionBindAddress = '127.0.0.2'; wireReceiptPath = $wire; serverNoticeText = $ServerNoticeText; listenerCount = 1 }

    $phase = 'CLIENT_LAUNCH'
    if ($ClientMode -eq 'Install') {
        $clientPath = $installExe; $clientDir = Join-Path $InstallRoot 'exe'
    } else {
        $clientRoot = Join-Path $runDir 'client'; $clientDir = Join-Path $clientRoot 'exe'; New-Item -ItemType Directory -Path $clientDir | Out-Null
        $clientPath = Join-Path $clientDir 'G7MTClient.exe'
        # DIAGNOSTIC (2026-09-03): Copy mode may source a hash-pinned working copy staged outside the install tree
        # (e.g. item115 debug-log patch); the install tree itself is never modified. ExpectedClientSha256 still applies.
        $copySource = $(if ($ClientExeOverride) { if (-not (Test-Path -LiteralPath $ClientExeOverride)) { throw 'FRESH_RUN_CLIENT_OVERRIDE_MISSING' }; $ClientExeOverride } else { $installExe })
        Copy-Item -LiteralPath $copySource -Destination $clientPath
        foreach ($f in @('cursor.txt','String.txt','G7MTOracle.exe')) { $src = Join-Path (Join-Path $InstallRoot 'exe') $f; if (Test-Path -LiteralPath $src) { Copy-Item -LiteralPath $src -Destination (Join-Path $clientDir $f) } }
        $dataTarget = $(if ($DataRoot) { Join-Path $DataRoot 'data' } else { Join-Path $InstallRoot 'data' })
        if ($DataRoot -and -not (Test-Path -LiteralPath $dataTarget)) { throw 'FRESH_RUN_DATA_ROOT_MISSING' }
        New-Item -ItemType Junction -Path (Join-Path $clientRoot 'data') -Target $dataTarget | Out-Null
        if (Test-Path -LiteralPath (Join-Path $InstallRoot 'doc')) { New-Item -ItemType Junction -Path (Join-Path $clientRoot 'doc') -Target (Join-Path $InstallRoot 'doc') | Out-Null }
        if ($KoreanRuntime) { Copy-Item -LiteralPath $ProxyPath -Destination (Join-Path $clientDir 'd3d8.dll'); Copy-Item -LiteralPath $SidecarPath -Destination (Join-Path $clientDir 'ko-runtime.tsv') }
        $r.client.copyRoot = $clientRoot; $r.client.dataJunctionTarget = (Get-Item -LiteralPath (Join-Path $clientRoot 'data')).Target
    }
    $expectedLaunchSha = $(if ($ClientExeOverride) { if (-not $ExpectedClientOverrideSha256) { throw 'FRESH_RUN_CLIENT_OVERRIDE_SHA_REQUIRED' }; $ExpectedClientOverrideSha256 } else { $ExpectedClientSha256 })
    if ((Hash $clientPath) -cne $expectedLaunchSha) { throw 'FRESH_RUN_CLIENT_HASH_INVALID' }
    if ($KoreanRuntime) { $env:LOGH7_KO_RUNTIME = '1' } else { $env:LOGH7_KO_RUNTIME = $null }
    if ($KoreanRuntime -and $KoreanDiag) { $env:LOGH7_KO_DIAG = '1' } else { $env:LOGH7_KO_DIAG = $null }
    $launchedAt = [datetime]::UtcNow
    $client = Start-Process -FilePath $clientPath -WorkingDirectory $clientDir -PassThru
    $null = $client.Handle
    $r.operations.launches = 1
    $env:LOGH7_KO_RUNTIME = $null
    $deadline = [datetime]::UtcNow.AddSeconds(30); $windows = @()
    do { Start-Sleep -Milliseconds 200; if ($client.HasExited) { throw "FRESH_RUN_CLIENT_EXITED_BEFORE_HWND:exit=$($client.ExitCode):after=$(([datetime]::UtcNow - $launchedAt).TotalMilliseconds)ms" }; $windows = @(Get-OwnedWindows $client.Id) } while ($windows.Count -lt 1 -and [datetime]::UtcNow -lt $deadline)
    if ($windows.Count -lt 1) { throw 'FRESH_RUN_CLIENT_HWND_NOT_READY' }
    $main = $windows | Sort-Object { ($_.rect.right - $_.rect.left) * ($_.rect.bottom - $_.rect.top) } -Descending | Select-Object -First 1

    $phase = 'STABILITY'
    $stableStart = [datetime]::UtcNow; $samples = 0
    do { Start-Sleep -Milliseconds 250; if ($client.HasExited) { throw "FRESH_RUN_CLIENT_EXITED_DURING_STABILITY:after=$(([datetime]::UtcNow - $launchedAt).TotalMilliseconds)ms" }; $now = @(Get-OwnedWindows $client.Id | Where-Object { $_.hwnd -eq $main.hwnd }); $nl = @(Get-NetTCPConnection -State Listen -LocalPort $AuthorityPort -ErrorAction SilentlyContinue | Where-Object { $_.LocalAddress -eq $BindAddress -and $_.OwningProcess -eq $server.Id }); if ($now.Count -ne 1 -or $nl.Count -ne 1) { throw 'FRESH_RUN_CONTINUOUS_LIVENESS_BROKEN' }; $samples++ } while (([datetime]::UtcNow - $stableStart).TotalMilliseconds -lt 5000)
    $cim = Get-CimInstance Win32_Process -Filter "ProcessId=$($client.Id)"
    $fg = [FreshRunNative]::GetForegroundWindow(); $fgPid = [uint32]0; [void][FreshRunNative]::GetWindowThreadProcessId($fg, [ref]$fgPid)
    $r.client = [ordered]@{ variant = $ClientVariantFile; mode = $ClientMode; pid = $client.Id; startTimeUtc = (Get-Process -Id $client.Id).StartTime.ToUniversalTime().ToString('o'); path = $cim.ExecutablePath; workingDirectory = $clientDir; sha256 = (Hash $clientPath); sessionId = $cim.SessionId; hwnd = $main.hwnd; title = $main.title; windowRect = $main.rect; ownedWindowCount = $windows.Count; foregroundPid = [int]$fgPid; foregroundIsClient = ([int]$fgPid -eq $client.Id); copyRoot = $(if ($r.client.Contains('copyRoot')) { $r.client.copyRoot } else { $null }); koreanRuntimeEnv = [bool]$KoreanRuntime; clientTcpConnections = @(Get-NetTCPConnection -OwningProcess $client.Id -ErrorAction SilentlyContinue | ForEach-Object { "$($_.LocalAddress):$($_.LocalPort)->$($_.RemoteAddress):$($_.RemotePort) $($_.State)" }) }
    $r.stability = [ordered]@{ durationMilliseconds = [int]([datetime]::UtcNow - $stableStart).TotalMilliseconds; sampleCount = $samples; continuous = $true }
    $r.status = 'FRESH_RUN_PREINPUT_READY'
} catch {
    $r.error = $_.Exception.Message; $r.status = 'FRESH_RUN_FAILED'
} finally {
    $r.phase = $phase; $dbPassword = $null; $env:PGPASSWORD = $null; $env:LOGH7_DB_CONNECTION = $null; $env:LOGH7_SERVER_NOTICE = $null; $env:LOGH7_KO_RUNTIME = $null; $env:LOGH7_KO_DIAG = $null; $env:LOGH7_SS_LOGINOK = $null; $env:LOGH7_CELESTIAL_KLASS = $null; $env:LOGH7_CELESTIAL_VARIANT = $null; $env:LOGH7_CELESTIAL_TWO_DISTINCT = $null; $env:LOGH7_EXTRA_CARD_COMMANDS = $null; $env:LOGH7_NINMEI_PROBE = $null; $env:LOGH7_INFO_PROBE = $null; $env:LOGH7_NINMEI_CARDS = $null; $env:LOGH7_STATIC_CARD_APPOINTER = $null; $env:LOGH7_NINMEI_CHARS = $null; $env:LOGH7_COMMAND_ECHO = $null; $env:LOGH7_LIST_KIND_PROBE = $null
    $r.wireTail = @(Get-Content -LiteralPath (Join-Path $runDir 'server-wire.jsonl') -Tail 5 -ErrorAction SilentlyContinue | ForEach-Object { [string]$_ })
    $r.serverStderrTail = @(Get-Content -LiteralPath (Join-Path $runDir 'server.stderr') -Tail 5 -ErrorAction SilentlyContinue | ForEach-Object { [string]$_ })
    $target = if (Test-Path -LiteralPath $runDir) { $receiptPath } else { $failurePath }
    if (Test-Path -LiteralPath $target) { $target = $target + '.' + [guid]::NewGuid().ToString('N') + '.json' }
    WriteJson $r $target
}
if ($r.status -ne 'FRESH_RUN_PREINPUT_READY') { exit 1 }
exit 0
