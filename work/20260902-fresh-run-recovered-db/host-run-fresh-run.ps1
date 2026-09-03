[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$RunId,
    [Parameter(Mandatory=$true)][string]$ServerNoticeText,
    [ValidateSet('Install','Copy')][string]$ClientMode = 'Install',
    [switch]$KoreanRuntime,
    [string]$ProxyPath = 'E:\logh7-greenfield\.worktrees\natural-authority-d02\work\20260902-korean-original-client-runtime\build\Release\d3d8.dll',
    [string]$SidecarPath = 'E:\logh7-greenfield\.worktrees\natural-authority-d02\work\20260902-korean-original-client-runtime\build\Release\ko-runtime.tsv',
    [string]$ExpectedProxySha256 = '',
    [string]$ExpectedSidecarSha256 = '',
    [string]$VixSourcePath = (Join-Path $PSScriptRoot 'fresh-run-vix.cs'),
    [string]$Vmx = 'E:\logh7-vms\oracle-win11-hd-re\oracle-win11-hd-re.vmx',
    [string]$VixDirectory = 'C:\Program Files (x86)\VMware\VMware VIX',
    [string]$SecretPath = 'E:\logh7-vms\oracle-win11-hd\.secrets\guest.dpapi',
    [string]$GuestUser = 'logh7-oracle',
    [switch]$SkipCapture,
    [string]$SourceRunId = '',
    [string]$ExpectedSourcePgControlSha256 = '',
    [string]$PostgresRuntimeZipPath = '',
    [string]$ServerZipPath = '',
    [string]$AccountSecretRoot = '',
    [string]$ExpectedServerZipSha256 = '',
    [string]$ExpectedServerExeSha256 = '',
    [string]$ExpectedServerDllSha256 = '',
    [string]$HostServerZipPath = '',
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
    [string]$CelestialClassSweep = '',
    [string]$ClientVariantFile = '',
    [string]$ExpectedClientSha256 = ''
)
# Host driver for one fresh sealed run: stages the guest scripts (hash-verified inside the guest),
# runs the preparation exactly once in the interactive session (VIX RunInteractive), copies the receipt
# back, then takes one input-free desktop capture. Secrets stay in host memory.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($RunId -cnotmatch '^20[0-9]{6}T[0-9]{6}Z-natural-l1-relogin-v1$') { throw 'RUN_ID_INVALID' }
$unit = $PSScriptRoot
$hostRun = Join-Path (Join-Path $unit 'runs') $RunId
if (Test-Path -LiteralPath $hostRun) { throw 'HOST_RUN_EXISTS' }
New-Item -ItemType Directory -Path $hostRun | Out-Null
$prepScript = Join-Path $unit 'guest-prepare-fresh-run.ps1'; $captureScript = Join-Path $unit 'guest-capture-desktop.ps1'
$prepHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $prepScript).Hash; $captureHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $captureScript).Hash
$guestStage = "C:\ProgramData\LOGH7\FreshRun\$RunId"
$guestPrep = Join-Path $guestStage 'guest-prepare-fresh-run.ps1'; $guestCapture = Join-Path $guestStage 'guest-capture-desktop.ps1'
$guestProxy = Join-Path $guestStage 'd3d8.dll'; $guestSidecar = Join-Path $guestStage 'ko-runtime.tsv'
$guestRunRoot = "C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1\$RunId"
$guestReceipt = Join-Path $guestRunRoot 'fresh-run-prep.json'; $guestFailure = "C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1\$RunId-fresh-run-failure.json"
$running = @(& 'C:\Program Files (x86)\VMware\VMware Workstation\vmrun.exe' -T ws list | Select-Object -Skip 1 | Where-Object { ([string]$_).Trim() -ceq $Vmx })
if ($running.Count -ne 1) { throw 'EXACT_VMX_NOT_RUNNING' }
Add-Type -AssemblyName System.Security
$hex = (Get-Content -LiteralPath $SecretPath -Raw -Encoding UTF8).Trim()
$protected = New-Object byte[] ($hex.Length / 2); for ($i = 0; $i -lt $protected.Length; $i++) { $protected[$i] = [Convert]::ToByte($hex.Substring($i * 2, 2), 16) }
$plain = [Security.Cryptography.ProtectedData]::Unprotect($protected, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
$password = [Text.Encoding]::Unicode.GetString($plain).TrimEnd([char]0)
Add-Type -Path $VixSourcePath
[FreshRunVix]::ConfigureLibraryDirectory($VixDirectory)
# Interactive login (VIX_LOGIN_IN_GUEST_REQUIRE_INTERACTIVE_ENVIRONMENT) so guest programs run in the console session.
$session = [FreshRunVix]::new($Vmx, $GuestUser, $password, $true)
$summary_vixSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $VixSourcePath).Hash
$sw = [Diagnostics.Stopwatch]::StartNew(); $prepError = $null; $captureError = $null
$summary = [ordered]@{ runId = $RunId; clientMode = $ClientMode; koreanRuntime = [bool]$KoreanRuntime; prepScriptSha256 = $prepHash; captureScriptSha256 = $captureHash; vixSourceSha256 = $summary_vixSha256; interactiveLogin = $session.InteractiveLogin; hostRun = $hostRun }
try {
    $mk = 'if (Test-Path -LiteralPath "' + $guestStage + '") { exit 31 }; New-Item -ItemType Directory -Path "' + $guestStage + '" -Force | Out-Null'
    $session.Run('C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe', '-NoProfile -NonInteractive -EncodedCommand ' + [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($mk)))
    $session.CopyToGuest($prepScript, $guestPrep); $session.CopyToGuest($captureScript, $guestCapture)
    $checks = @("if((Get-FileHash -LiteralPath '$guestPrep' -Algorithm SHA256).Hash -cne '$prepHash'){exit 34}", "if((Get-FileHash -LiteralPath '$guestCapture' -Algorithm SHA256).Hash -cne '$captureHash'){exit 35}")
    $koreanArgs = ''
    if ($KoreanRuntime) {
        $session.CopyToGuest($ProxyPath, $guestProxy); $session.CopyToGuest($SidecarPath, $guestSidecar)
        $checks += "if((Get-FileHash -LiteralPath '$guestProxy' -Algorithm SHA256).Hash -cne '$((Get-FileHash -Algorithm SHA256 -LiteralPath $ProxyPath).Hash)'){exit 36}"
        $checks += "if((Get-FileHash -LiteralPath '$guestSidecar' -Algorithm SHA256).Hash -cne '$((Get-FileHash -Algorithm SHA256 -LiteralPath $SidecarPath).Hash)'){exit 37}"
        $koreanArgs = ' -KoreanRuntime -ProxyPath "' + $guestProxy + '" -SidecarPath "' + $guestSidecar + '"'
        if ($ExpectedProxySha256) { $koreanArgs += ' -ExpectedProxySha256 "' + $ExpectedProxySha256 + '"' }
        if ($ExpectedSidecarSha256) { $koreanArgs += ' -ExpectedSidecarSha256 "' + $ExpectedSidecarSha256 + '"' }
        if ($KoreanDiag) { $koreanArgs += ' -KoreanDiag' }
    }
    $session.Run('C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe', '-NoProfile -NonInteractive -EncodedCommand ' + [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes(($checks -join ';'))))
    $summary.stagedAndVerified = $true
    $sourceArgs = ''
    if ($SourceRunId) { $sourceArgs += ' -SourceRunId "' + $SourceRunId + '"' }
    if ($ExpectedSourcePgControlSha256) { $sourceArgs += ' -ExpectedSourcePgControlSha256 "' + $ExpectedSourcePgControlSha256 + '"' }
    if ($PostgresRuntimeZipPath) { $sourceArgs += ' -PostgresRuntimeZipPath "' + $PostgresRuntimeZipPath + '"' }
    if ($ServerZipPath) { $sourceArgs += ' -ServerZipPath "' + $ServerZipPath + '"' }
    if ($AccountSecretRoot) { $sourceArgs += ' -AccountSecretRoot "' + $AccountSecretRoot + '"' }
    if ($HostServerZipPath) {
        # Stage a host-built authority zip into the guest run stage and point the guest at it.
        $guestServerZip = Join-Path $guestStage 'logh7-server-win-x64.zip'
        $session.CopyToGuest($HostServerZipPath, $guestServerZip)
        $zipHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $HostServerZipPath).Hash
        $session.Run('C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe', '-NoProfile -NonInteractive -EncodedCommand ' + [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes("if((Get-FileHash -LiteralPath '$guestServerZip' -Algorithm SHA256).Hash -cne '$zipHash'){exit 38}")))
        $sourceArgs += ' -ServerZipPath "' + $guestServerZip + '"'
        $summary.hostServerZipSha256 = $zipHash
    }
    if ($ExpectedServerZipSha256) { $sourceArgs += ' -ExpectedServerZipSha256 "' + $ExpectedServerZipSha256 + '"' }
    if ($ExpectedServerExeSha256) { $sourceArgs += ' -ExpectedServerExeSha256 "' + $ExpectedServerExeSha256 + '"' }
    if ($ExpectedServerDllSha256) { $sourceArgs += ' -ExpectedServerDllSha256 "' + $ExpectedServerDllSha256 + '"' }
    if ($ProvisionNewAccount) { $sourceArgs += ' -ProvisionNewAccount' }
    if ($SsLoginOk) { $sourceArgs += ' -SsLoginOk "' + $SsLoginOk + '"' }
    if ($SkipMigrationCheck) { $sourceArgs += ' -SkipMigrationCheck' }
    if ($CelestialKlass) { $sourceArgs += ' -CelestialKlass "' + $CelestialKlass + '"' }
    if ($CelestialVariant) { $sourceArgs += ' -CelestialVariant "' + $CelestialVariant + '"' }
    if ($CelestialTwoDistinct) { $sourceArgs += ' -CelestialTwoDistinct "' + $CelestialTwoDistinct + '"' }
    if ($ExtraCardCommands) { $sourceArgs += ' -ExtraCardCommands "' + $ExtraCardCommands + '"' }
    if ($NinmeiProbe) { $sourceArgs += ' -NinmeiProbe "' + $NinmeiProbe + '"' }
    if ($StaticCardAppointer) { $sourceArgs += ' -StaticCardAppointer "' + $StaticCardAppointer + '"' }
    if ($NinmeiChars) { $sourceArgs += ' -NinmeiChars "' + $NinmeiChars + '"' }
    if ($CommandEcho) { $sourceArgs += ' -CommandEcho "' + $CommandEcho + '"' }
    if ($ListKindProbe) { $sourceArgs += ' -ListKindProbe "' + $ListKindProbe + '"' }
    if ($WorldCardId -ne '') { $sourceArgs += ' -WorldCardId "' + $WorldCardId + '"' }
    if ($DataRoot) { $sourceArgs += ' -DataRoot "' + $DataRoot + '"' }
    if ($InfoProbe) { $sourceArgs += ' -InfoProbe "' + $InfoProbe + '"' }
    if ($NinmeiCards) { $sourceArgs += ' -NinmeiCards "' + $NinmeiCards + '"' }
    if ($ClientExeOverride) { $sourceArgs += ' -ClientExeOverride "' + $ClientExeOverride + '"' }
    if ($ExpectedClientOverrideSha256) { $sourceArgs += ' -ExpectedClientOverrideSha256 "' + $ExpectedClientOverrideSha256 + '"' }
    if ($CelestialClassSweep) { $sourceArgs += ' -CelestialClassSweep "' + $CelestialClassSweep + '"' }
    if ($ClientVariantFile) { $sourceArgs += ' -ClientVariantFile "' + $ClientVariantFile + '"' }
    if ($ExpectedClientSha256) { $sourceArgs += ' -ExpectedClientSha256 "' + $ExpectedClientSha256 + '"' }
    $summary.sourceOverrides = $sourceArgs
    $prepArgs = '-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $guestPrep + '" -RunId "' + $RunId + '" -ServerNoticeText "' + $ServerNoticeText + '" -ClientMode ' + $ClientMode + $koreanArgs + $sourceArgs
    try { $session.RunInteractive('C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe', $prepArgs) } catch { $prepError = $_.Exception.Message }
    $summary.prepElapsed = $sw.Elapsed.ToString(); $summary.prepGuestError = $prepError
    $hostReceipt = Join-Path $hostRun 'fresh-run-prep.json'
    try { $session.CopyFromGuest($guestReceipt, $hostReceipt) } catch { try { $session.CopyFromGuest($guestFailure, (Join-Path $hostRun 'fresh-run-failure.json')); $hostReceipt = Join-Path $hostRun 'fresh-run-failure.json' } catch { $hostReceipt = $null } }
    $summary.receiptPath = $hostReceipt
    if ($hostReceipt) {
        $rec = Get-Content -LiteralPath $hostReceipt -Raw -Encoding UTF8 | ConvertFrom-Json
        $summary.receiptSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $hostReceipt).Hash; $summary.status = $rec.status; $summary.phase = $rec.phase; $summary.error = $rec.error
        $summary.clientPid = $(if ($rec.client.PSObject.Properties['pid']) { $rec.client.pid } else { $null })
        $summary.clientHwnd = $(if ($rec.client.PSObject.Properties['hwnd']) { $rec.client.hwnd } else { $null })
        $summary.authorityPid = $(if ($rec.authority.PSObject.Properties['pid']) { $rec.authority.pid } else { $null })
    } else { $summary.status = 'NO_RECEIPT' }
    foreach ($f in @('server-wire.jsonl','server.stdout','server.stderr','fresh-run-plan.json','postgres.log')) { try { $session.CopyFromGuest((Join-Path $guestRunRoot $f), (Join-Path $hostRun $f)) } catch {} }
    if (-not $SkipCapture -and $summary.status -eq 'FRESH_RUN_PREINPUT_READY') {
        $guestPng = Join-Path $guestRunRoot 'preinput-desktop.png'; $guestCapReceipt = Join-Path $guestRunRoot 'preinput-desktop.json'
        $capArgs = '-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $guestCapture + '" -ExpectedPid ' + [int]$summary.clientPid + ' -OutputPath "' + $guestPng + '" -ReceiptPath "' + $guestCapReceipt + '"'
        try { $session.RunInteractive('C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe', $capArgs) } catch { $captureError = $_.Exception.Message }
        try { $session.CopyFromGuest($guestPng, (Join-Path $hostRun 'preinput-desktop.png')); $session.CopyFromGuest($guestCapReceipt, (Join-Path $hostRun 'preinput-desktop.json')); $summary.capturePng = (Join-Path $hostRun 'preinput-desktop.png'); $summary.capturePngSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $summary.capturePng).Hash } catch { $summary.captureCopyError = $_.Exception.Message }
        $summary.captureGuestError = $captureError
    }
} finally { $session.Dispose(); [Array]::Clear($plain, 0, $plain.Length); [Array]::Clear($protected, 0, $protected.Length); $password = $null }
$summary.totalElapsed = $sw.Elapsed.ToString()
[IO.File]::WriteAllText((Join-Path $hostRun 'host-summary.json'), (($summary | ConvertTo-Json -Depth 6) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
$summary | ConvertTo-Json -Depth 6
