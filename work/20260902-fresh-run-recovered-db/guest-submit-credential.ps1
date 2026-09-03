[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$RunId,
    [Parameter(Mandatory=$true)][int]$ExpectedPid,
    [Parameter(Mandatory=$true)][string]$ExpectedHwnd,
    [Parameter(Mandatory=$true)][string]$ReceiptPath,
    [string]$PrepFileName = 'fresh-run-prep.json',
    [string]$WireFileName = 'server-wire.jsonl'
)
# One credential submission through user32 SendInput in the interactive session:
# focus client -> Backspace -> login -> Tab -> password -> Enter (same sequence the lane's earlier
# successful runs used). The credential is read from the run's DPAPI-protected account secret in memory
# only; values are never written to the receipt or output.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $ReceiptPath) { throw 'RECEIPT_EXISTS' }
$root = "C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1\$RunId"
$hwnd = [IntPtr][Convert]::ToInt64($ExpectedHwnd.Substring(2), 16)
$inputStarted = $false; $sentKeyEvents = 0
Add-Type -AssemblyName System.Security
if (-not ('CredentialInputNative' -as [type])) { Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class CredentialInputNative {
  [StructLayout(LayoutKind.Sequential)] public struct INPUT { public uint type; public INPUTUNION data; }
  [StructLayout(LayoutKind.Explicit)] public struct INPUTUNION {
    [FieldOffset(0)] public MOUSEINPUT mouse;
    [FieldOffset(0)] public KEYBDINPUT keyboard;
    [FieldOffset(0)] public HARDWAREINPUT hardware;
  }
  [StructLayout(LayoutKind.Sequential)] public struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint flags; public uint time; public UIntPtr extraInfo; }
  [StructLayout(LayoutKind.Sequential)] public struct KEYBDINPUT { public ushort virtualKey; public ushort scanCode; public uint flags; public uint time; public UIntPtr extraInfo; }
  [StructLayout(LayoutKind.Sequential)] public struct HARDWAREINPUT { public uint message; public ushort low; public ushort high; }
  [DllImport("user32.dll", SetLastError=true)] public static extern uint SendInput(uint count, INPUT[] inputs, int size);
  [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr hwnd);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint fromThread, uint toThread, bool attach);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hwnd);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern short GetKeyState(int key);
  [DllImport("user32.dll")] public static extern short GetAsyncKeyState(int key);
}
'@ }
function Write-Receipt([string]$Status, [string]$ErrorMessage) {
    $value = [ordered]@{
        schemaVersion = 1; runId = $RunId; status = $Status; recordedAtUtc = [datetime]::UtcNow.ToString('o'); sessionId = [Diagnostics.Process]::GetCurrentProcess().SessionId
        client = [ordered]@{ pid = $ExpectedPid; hwnd = ('0x{0:X16}' -f $hwnd.ToInt64()) }
        transport = 'guest user32 SendInput keyboard events'; secretValuesRecorded = $false
        operations = [ordered]@{ credentialInputAttempts = $(if ($inputStarted) { 1 } else { 0 }); credentialSubmissions = $(if ($Status -eq 'ONE_SENDINPUT_CREDENTIAL_SUBMISSION_SENT') { 1 } else { 0 }); keyEvents = $sentKeyEvents; inputRetries = 0; clicks = 0 }
        error = $ErrorMessage
    }
    [IO.File]::WriteAllText($ReceiptPath, (($value | ConvertTo-Json -Depth 8) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
}
function Send-Key([uint16]$VirtualKey, [int]$HoldMilliseconds) {
    $dk = [CredentialInputNative+KEYBDINPUT]::new(); $dk.virtualKey = $VirtualKey
    $du = [CredentialInputNative+INPUTUNION]::new(); $du.keyboard = $dk
    $down = [CredentialInputNative+INPUT]::new(); $down.type = 1; $down.data = $du
    $uk = [CredentialInputNative+KEYBDINPUT]::new(); $uk.virtualKey = $VirtualKey; $uk.flags = 2
    $uu = [CredentialInputNative+INPUTUNION]::new(); $uu.keyboard = $uk
    $up = [CredentialInputNative+INPUT]::new(); $up.type = 1; $up.data = $uu
    if ([CredentialInputNative]::SendInput(1, @($down), $inputSize) -ne 1) { throw "KEY_DOWN_SEND_FAILED:$([Runtime.InteropServices.Marshal]::GetLastWin32Error())" }
    $script:sentKeyEvents++
    Start-Sleep -Milliseconds $HoldMilliseconds
    if ([CredentialInputNative]::SendInput(1, @($up), $inputSize) -ne 1) { throw "KEY_UP_SEND_FAILED:$([Runtime.InteropServices.Marshal]::GetLastWin32Error())" }
    $script:sentKeyEvents++
    Start-Sleep -Milliseconds 80
}
function Send-LowerAscii([string]$Value) {
    foreach ($ch in $Value.ToCharArray()) {
        if ($ch -ge '0' -and $ch -le '9') { $vk = [uint16][int][char]$ch }
        elseif ($ch -ge 'a' -and $ch -le 'z') { $vk = [uint16][int][char]([char]::ToUpperInvariant($ch)) }
        else { throw 'CREDENTIAL_CHARACTER_OUTSIDE_LOWER_ASCII' }
        Send-Key $vk 80
    }
}
$protected = $null; $plaintext = $null; $login = $null; $password = $null
try {
    $inputSize = [Runtime.InteropServices.Marshal]::SizeOf([type][CredentialInputNative+INPUT])
    $expectedInputSize = if ([IntPtr]::Size -eq 8) { 40 } else { 28 }
    if ($inputSize -ne $expectedInputSize) { throw "INPUT_STRUCT_SIZE_INVALID:$inputSize" }
    $prep = Get-Content -LiteralPath (Join-Path $root $PrepFileName) -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($prep.runId -cne $RunId -or $prep.status -cne 'FRESH_RUN_PREINPUT_READY' -or [int]$prep.client.pid -ne $ExpectedPid -or [string]$prep.client.hwnd -cne ('0x{0:X16}' -f $hwnd.ToInt64())) { throw 'PREP_IDENTITY_MISMATCH' }
    $cim = Get-CimInstance Win32_Process -Filter "ProcessId=$ExpectedPid"
    if ($null -eq $cim -or $cim.ExecutablePath -cne [string]$prep.client.path) { throw 'CLIENT_IDENTITY_MISMATCH' }
    if ((Get-FileHash -LiteralPath $cim.ExecutablePath -Algorithm SHA256).Hash -cne [string]$prep.client.sha256) { throw 'CLIENT_HASH_MISMATCH' }
    if (-not [CredentialInputNative]::IsWindow($hwnd)) { throw 'CLIENT_HWND_INVALID' }
    $owner = [uint32]0; $targetThread = [CredentialInputNative]::GetWindowThreadProcessId($hwnd, [ref]$owner)
    if ([int]$owner -ne $ExpectedPid) { throw 'CLIENT_HWND_OWNER_MISMATCH' }
    $serverPid = [int]$prep.authority.pid
    if (@(Get-NetTCPConnection -State Listen -LocalPort 47900 -ErrorAction SilentlyContinue | Where-Object { $_.LocalAddress -eq '202.8.80.179' -and $_.OwningProcess -eq $serverPid }).Count -ne 1) { throw 'AUTHORITY_LISTENER_INVALID' }
    $wire = @(Get-Content -LiteralPath (Join-Path $root $WireFileName) -Encoding UTF8 | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($wire.Count -ne 1 -or ($wire[0] | ConvertFrom-Json).eventName -cne 'listener-ready') { throw 'WIRE_NOT_PREINPUT_LISTENER_ONLY' }
    if (([CredentialInputNative]::GetKeyState(0x14) -band 1) -ne 0) { throw 'CAPS_LOCK_ENABLED' }
    foreach ($key in @(0x10, 0x11, 0x12, 0x01)) { if ([CredentialInputNative]::GetAsyncKeyState($key) -lt 0) { throw 'INPUT_KEY_ALREADY_HELD' } }
    $foreground = [CredentialInputNative]::GetForegroundWindow(); $fo = [uint32]0
    $foregroundThread = if ($foreground -eq [IntPtr]::Zero) { [uint32]0 } else { [CredentialInputNative]::GetWindowThreadProcessId($foreground, [ref]$fo) }
    $currentThread = [CredentialInputNative]::GetCurrentThreadId()
    $aF = if ($foregroundThread -eq 0 -or $foregroundThread -eq $currentThread) { $true } else { [CredentialInputNative]::AttachThreadInput($currentThread, $foregroundThread, $true) }
    $aT = if ($targetThread -eq $currentThread) { $true } else { [CredentialInputNative]::AttachThreadInput($currentThread, $targetThread, $true) }
    try { if (-not [CredentialInputNative]::SetForegroundWindow($hwnd)) { throw 'CLIENT_FOCUS_FAILED' } }
    finally { if ($targetThread -ne $currentThread -and $aT) { [void][CredentialInputNative]::AttachThreadInput($currentThread, $targetThread, $false) }; if ($foregroundThread -ne 0 -and $foregroundThread -ne $currentThread -and $aF) { [void][CredentialInputNative]::AttachThreadInput($currentThread, $foregroundThread, $false) } }
    Start-Sleep -Milliseconds 400
    if ([CredentialInputNative]::GetForegroundWindow() -ne $hwnd) { throw 'CLIENT_NOT_FOREGROUND_AFTER_FOCUS' }
    $protected = [IO.File]::ReadAllBytes((Join-Path $root 'account-secret.dpapi'))
    $plaintext = [Security.Cryptography.ProtectedData]::Unprotect($protected, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
    $credential = [Text.Encoding]::UTF8.GetString($plaintext) | ConvertFrom-Json
    $login = [string]$credential.login; $password = [string]$credential.password
    if ($login -cnotmatch '^t[0-9a-f]{7}$' -or $password -cnotmatch '^[0-9a-f]{8}$') { throw 'CREDENTIAL_FORMAT_INVALID' }
    $inputStarted = $true
    Send-Key 0x08 250
    Send-LowerAscii $login
    Send-Key 0x09 250
    Send-LowerAscii $password
    Send-Key 0x0D 250
    Write-Receipt 'ONE_SENDINPUT_CREDENTIAL_SUBMISSION_SENT' $null
} catch {
    if (-not (Test-Path -LiteralPath $ReceiptPath)) { Write-Receipt 'SENDINPUT_CREDENTIAL_SUBMISSION_FAILED' $_.Exception.Message }
    exit 1
} finally {
    if ($plaintext) { [Array]::Clear($plaintext, 0, $plaintext.Length) }
    if ($protected) { [Array]::Clear($protected, 0, $protected.Length) }
    $login = $null; $password = $null
}
exit 0
