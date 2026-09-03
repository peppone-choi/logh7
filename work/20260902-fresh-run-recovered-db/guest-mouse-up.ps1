[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$RunId,
    [Parameter(Mandatory=$true)][int]$ExpectedPid,
    [Parameter(Mandatory=$true)][string]$ExpectedHwnd,
    [Parameter(Mandatory=$true)][string]$ReceiptPath,
    [string]$PrepFileName = 'fresh-run-prep.json'
)
# Exactly one LEFT BUTTON UP (user32 mouse_event MOUSEEVENTF_LEFTUP) at the current cursor position on the
# original client, after verifying process/HWND identity and foreground ownership. No move, no press, no retry.
# Purpose: test whether a lobby panel wedge is a stuck button/capture state (see RPM diff 0x0221443C == 0x100).
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $ReceiptPath) { throw 'RECEIPT_EXISTS' }
$root = "C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1\$RunId"
$hwnd = [IntPtr][Convert]::ToInt64($ExpectedHwnd.Substring(2), 16)
if (-not ('MouseUpNative' -as [type])) { Add-Type -TypeDefinition @'
using System;using System.Runtime.InteropServices;
public static class MouseUpNative{
 [StructLayout(LayoutKind.Sequential)]public struct POINT{public int X,Y;}
 [DllImport("user32.dll")]public static extern bool IsWindow(IntPtr h);[DllImport("user32.dll")]public static extern uint GetWindowThreadProcessId(IntPtr h,out uint p);
 [DllImport("user32.dll")]public static extern IntPtr GetForegroundWindow();[DllImport("user32.dll")]public static extern bool GetCursorPos(out POINT p);
 [DllImport("user32.dll")]public static extern void mouse_event(uint flags,uint dx,uint dy,uint data,UIntPtr extra);[DllImport("user32.dll")]public static extern short GetAsyncKeyState(int key);}
'@ }
$prep = Get-Content -LiteralPath (Join-Path $root $PrepFileName) -Raw -Encoding UTF8 | ConvertFrom-Json
if ($prep.runId -cne $RunId -or [int]$prep.client.pid -ne $ExpectedPid -or [string]$prep.client.hwnd -cne $ExpectedHwnd) { throw 'PREP_IDENTITY_MISMATCH' }
$cim = Get-CimInstance Win32_Process -Filter "ProcessId=$ExpectedPid"
if ($null -eq $cim -or $cim.ExecutablePath -cne [string]$prep.client.path) { throw 'CLIENT_IDENTITY_MISMATCH' }
if (-not [MouseUpNative]::IsWindow($hwnd)) { throw 'CLIENT_HWND_INVALID' }
$owner = [uint32]0; [void][MouseUpNative]::GetWindowThreadProcessId($hwnd, [ref]$owner)
if ([int]$owner -ne $ExpectedPid) { throw 'CLIENT_HWND_OWNER_MISMATCH' }
$fg = [MouseUpNative]::GetForegroundWindow()
$p = New-Object MouseUpNative+POINT; [void][MouseUpNative]::GetCursorPos([ref]$p)
$asyncBefore = [int][MouseUpNative]::GetAsyncKeyState(1)
[MouseUpNative]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)   # MOUSEEVENTF_LEFTUP
Start-Sleep -Milliseconds 300
$asyncAfter = [int][MouseUpNative]::GetAsyncKeyState(1)
$receipt = [ordered]@{ status = 'ONE_LEFTUP_SENT'; sentAtUtc = [datetime]::UtcNow.ToString('o'); runId = $RunId; client = [ordered]@{ pid = $ExpectedPid; hwnd = $ExpectedHwnd; foregroundIsClient = ($fg -eq $hwnd) }; cursor = [ordered]@{ x = $p.X; y = $p.Y }; asyncLeftBefore = $asyncBefore; asyncLeftAfter = $asyncAfter; transport = 'guest user32 mouse_event LEFTUP only'; operations = [ordered]@{ presses = 0; releases = 1; inputRetries = 0; clicks = 0 } }
[IO.File]::WriteAllText($ReceiptPath, (($receipt | ConvertTo-Json -Depth 6) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
exit 0
