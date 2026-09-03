[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$RunId,
    [Parameter(Mandatory=$true)][string]$StepName,
    [Parameter(Mandatory=$true)][string]$GuestScriptPath,
    [Parameter(Mandatory=$true)][string]$GuestArguments,
    [string[]]$CopyBack = @(),
    [switch]$ActivateWindow,
    [string]$VixSourcePath = (Join-Path $PSScriptRoot 'fresh-run-vix.cs'),
    [string]$Vmx = 'E:\logh7-vms\oracle-win11-hd-re\oracle-win11-hd-re.vmx',
    [string]$VixDirectory = 'C:\Program Files (x86)\VMware\VMware VIX',
    [string]$SecretPath = 'E:\logh7-vms\oracle-win11-hd\.secrets\guest.dpapi',
    [string]$GuestUser = 'logh7-oracle'
)
# Stages one guest step script (hash-verified inside the guest), runs it once under an interactive VIX
# login (console session), and copies the named guest run-root files back to the host run directory.
# By default the program is started without ACTIVATE_WINDOW so no terminal window is raised over the game.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($StepName -cnotmatch '^[a-z0-9-]{1,48}$') { throw 'STEP_NAME_INVALID' }
$hostRun = Join-Path (Join-Path $PSScriptRoot 'runs') $RunId
if (-not (Test-Path -LiteralPath $hostRun)) { throw 'HOST_RUN_MISSING' }
$stepReceipt = Join-Path $hostRun "step-$StepName-host.json"
if (Test-Path -LiteralPath $stepReceipt) { throw 'STEP_ALREADY_RUN' }
$guestRunRoot = "C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1\$RunId"
$guestStage = "C:\ProgramData\LOGH7\FreshRun\$RunId"
$leaf = Split-Path -Leaf $GuestScriptPath
$guestScript = Join-Path $guestStage ("step-$StepName-" + $leaf)
$scriptHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $GuestScriptPath).Hash
$running = @(& 'C:\Program Files (x86)\VMware\VMware Workstation\vmrun.exe' -T ws list | Select-Object -Skip 1 | Where-Object { ([string]$_).Trim() -ceq $Vmx })
if ($running.Count -ne 1) { throw 'EXACT_VMX_NOT_RUNNING' }
Add-Type -AssemblyName System.Security
$hex = (Get-Content -LiteralPath $SecretPath -Raw -Encoding UTF8).Trim()
$protected = New-Object byte[] ($hex.Length / 2); for ($i = 0; $i -lt $protected.Length; $i++) { $protected[$i] = [Convert]::ToByte($hex.Substring($i * 2, 2), 16) }
$plain = [Security.Cryptography.ProtectedData]::Unprotect($protected, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
$password = [Text.Encoding]::Unicode.GetString($plain).TrimEnd([char]0)
Add-Type -Path $VixSourcePath
[FreshRunVix]::ConfigureLibraryDirectory($VixDirectory)
$session = [FreshRunVix]::new($Vmx, $GuestUser, $password, $true)
$sw = [Diagnostics.Stopwatch]::StartNew(); $guestError = $null
$result = [ordered]@{ runId = $RunId; step = $StepName; guestScript = $leaf; guestScriptSha256 = $scriptHash; activateWindow = [bool]$ActivateWindow; arguments = $GuestArguments; startedAtUtc = [datetime]::UtcNow.ToString('o') }
try {
    # ensure the guest stage directory exists (it is removed by guest-clean-stage.ps1 after a run is closed)
    $session.Run('C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe', '-NoProfile -NonInteractive -EncodedCommand ' + [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes("New-Item -ItemType Directory -Force -Path '$guestStage' | Out-Null")))
    $session.CopyToGuest($GuestScriptPath, $guestScript)
    $verify = 'if((Get-FileHash -LiteralPath "' + $guestScript + '" -Algorithm SHA256).Hash -cne "' + $scriptHash + '"){exit 34}'
    $session.Run('C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe', '-NoProfile -NonInteractive -EncodedCommand ' + [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($verify)))
    $args = '-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $guestScript + '" ' + $GuestArguments
    try { if ($ActivateWindow) { $session.RunInteractive('C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe', $args) } else { $session.Run('C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe', $args) } } catch { $guestError = $_.Exception.Message }
    $result.guestError = $guestError; $result.guestElapsed = $sw.Elapsed.ToString()
    $copied = [ordered]@{}
    foreach ($f in $CopyBack) { $dst = Join-Path $hostRun $f; try { $session.CopyFromGuest((Join-Path $guestRunRoot $f), $dst); $copied[$f] = (Get-FileHash -Algorithm SHA256 -LiteralPath $dst).Hash } catch { $copied[$f] = 'COPY_FAILED:' + $_.Exception.Message } }
    $result.copiedBack = $copied
} finally { $session.Dispose(); [Array]::Clear($plain, 0, $plain.Length); [Array]::Clear($protected, 0, $protected.Length); $password = $null }
$result.totalElapsed = $sw.Elapsed.ToString()
[IO.File]::WriteAllText($stepReceipt, (($result | ConvertTo-Json -Depth 6) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
$result | ConvertTo-Json -Depth 6
