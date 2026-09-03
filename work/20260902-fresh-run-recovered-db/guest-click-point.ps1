[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$RunId,
    [Parameter(Mandatory=$true)][int]$ExpectedPid,
    [Parameter(Mandatory=$true)][string]$ExpectedHwnd,
    [Parameter(Mandatory=$true)][int]$X,
    [Parameter(Mandatory=$true)][int]$Y,
    [Parameter(Mandatory=$true)][string]$PointName,
    [Parameter(Mandatory=$true)][string]$ExpectedStage,
    [Parameter(Mandatory=$true)][string]$ReceiptPath,
    [string]$CoordinateProvenance = '1024x768 original-client fullscreen frame',
    [string]$PrepFileName = 'fresh-run-prep.json'
)
# Exactly one left click at absolute screen coordinates (SetCursorPos + mouse_event) on the original client,
# after verifying process/HWND identity and foreground ownership. No retry.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $ReceiptPath) { throw 'RECEIPT_EXISTS' }
if ($X -lt 0 -or $X -gt 1023 -or $Y -lt 0 -or $Y -gt 767) { throw 'POINT_OUT_OF_FRAME' }
$root = "C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1\$RunId"
$hwnd = [IntPtr][Convert]::ToInt64($ExpectedHwnd.Substring(2), 16)
if (-not ('ClickPointNative' -as [type])) { Add-Type -TypeDefinition @'
using System;using System.Runtime.InteropServices;
public static class ClickPointNative{
 [StructLayout(LayoutKind.Sequential)]public struct POINT{public int X,Y;}[StructLayout(LayoutKind.Sequential)]public struct RECT{public int Left,Top,Right,Bottom;}
 [DllImport("user32.dll")]public static extern bool IsWindow(IntPtr h);[DllImport("user32.dll")]public static extern uint GetWindowThreadProcessId(IntPtr h,out uint p);
 [DllImport("user32.dll")]public static extern IntPtr GetForegroundWindow();[DllImport("user32.dll")]public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")]public static extern bool AttachThreadInput(uint a,uint b,bool attach);[DllImport("kernel32.dll")]public static extern uint GetCurrentThreadId();
 [DllImport("user32.dll")]public static extern bool SetCursorPos(int x,int y);[DllImport("user32.dll")]public static extern bool GetCursorPos(out POINT p);
 [DllImport("user32.dll")]public static extern void mouse_event(uint flags,uint dx,uint dy,uint data,UIntPtr extra);[DllImport("user32.dll")]public static extern short GetAsyncKeyState(int key);
 [DllImport("user32.dll")]public static extern IntPtr WindowFromPoint(POINT p);[DllImport("user32.dll")]public static extern bool GetWindowRect(IntPtr h,out RECT r);}
'@ }
$prep = Get-Content -LiteralPath (Join-Path $root $PrepFileName) -Raw -Encoding UTF8 | ConvertFrom-Json
if ($prep.runId -cne $RunId -or [int]$prep.client.pid -ne $ExpectedPid -or [string]$prep.client.hwnd -cne $ExpectedHwnd) { throw 'PREP_IDENTITY_MISMATCH' }
$cim = Get-CimInstance Win32_Process -Filter "ProcessId=$ExpectedPid"
if ($null -eq $cim -or $cim.ExecutablePath -cne [string]$prep.client.path) { throw 'CLIENT_IDENTITY_MISMATCH' }
if (-not [ClickPointNative]::IsWindow($hwnd)) { throw 'CLIENT_HWND_INVALID' }
$owner = [uint32]0; $targetThread = [ClickPointNative]::GetWindowThreadProcessId($hwnd, [ref]$owner)
if ([int]$owner -ne $ExpectedPid) { throw 'CLIENT_HWND_OWNER_MISMATCH' }
if ([ClickPointNative]::GetAsyncKeyState(1) -lt 0) { throw 'LEFT_BUTTON_ALREADY_DOWN' }
$serverPid = [int]$prep.authority.pid
if (@(Get-NetTCPConnection -State Listen -LocalPort 47900 -ErrorAction SilentlyContinue | Where-Object { $_.LocalAddress -eq '202.8.80.179' -and $_.OwningProcess -eq $serverPid }).Count -ne 1) { throw 'AUTHORITY_LISTENER_INVALID' }
$fg = [ClickPointNative]::GetForegroundWindow(); $focusResult = $null
if ($fg -ne $hwnd) {
    $fo = [uint32]0; $fgThread = if ($fg -eq [IntPtr]::Zero) { [uint32]0 } else { [ClickPointNative]::GetWindowThreadProcessId($fg, [ref]$fo) }
    $cur = [ClickPointNative]::GetCurrentThreadId()
    $aF = if ($fgThread -eq 0 -or $fgThread -eq $cur) { $true } else { [ClickPointNative]::AttachThreadInput($cur, $fgThread, $true) }
    $aT = if ($targetThread -eq $cur) { $true } else { [ClickPointNative]::AttachThreadInput($cur, $targetThread, $true) }
    try { $focusResult = [ClickPointNative]::SetForegroundWindow($hwnd) } finally { if ($targetThread -ne $cur -and $aT) { [void][ClickPointNative]::AttachThreadInput($cur, $targetThread, $false) }; if ($fgThread -ne 0 -and $fgThread -ne $cur -and $aF) { [void][ClickPointNative]::AttachThreadInput($cur, $fgThread, $false) } }
    Start-Sleep -Milliseconds 300
    if ([ClickPointNative]::GetForegroundWindow() -ne $hwnd) { throw 'CLIENT_NOT_FOREGROUND' }
}
$wr = [ClickPointNative+RECT]::new(); [void][ClickPointNative]::GetWindowRect($hwnd, [ref]$wr)
$pt = [ClickPointNative+POINT]::new(); $pt.X = $X; $pt.Y = $Y
$under = [ClickPointNative]::WindowFromPoint($pt); $uo = [uint32]0; [void][ClickPointNative]::GetWindowThreadProcessId($under, [ref]$uo)
if ([int]$uo -ne $ExpectedPid) { throw "POINT_NOT_OVER_CLIENT:pid=$uo" }
$before = [ClickPointNative+POINT]::new(); [void][ClickPointNative]::GetCursorPos([ref]$before)
if (-not [ClickPointNative]::SetCursorPos($X, $Y)) { throw 'SET_CURSOR_FAILED' }
Start-Sleep -Milliseconds 150
[ClickPointNative]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 250; [ClickPointNative]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
$sentAt = [datetime]::UtcNow.ToString('o')
$after = [ClickPointNative+POINT]::new(); [void][ClickPointNative]::GetCursorPos([ref]$after)
$receipt = [ordered]@{
    status = 'ONE_CLIENT_CLICK_SENT'; sentAtUtc = $sentAt; runId = $RunId; expectedStage = $ExpectedStage; sessionId = [Diagnostics.Process]::GetCurrentProcess().SessionId
    client = [ordered]@{ pid = $ExpectedPid; hwnd = $ExpectedHwnd; windowRect = [ordered]@{ left = $wr.Left; top = $wr.Top; right = $wr.Right; bottom = $wr.Bottom }; foregroundBefore = ($fg -eq $hwnd); setForegroundWindowResult = $focusResult }
    point = [ordered]@{ name = $PointName; x = $X; y = $Y; coordinateProvenance = $CoordinateProvenance }
    cursorBefore = [ordered]@{ x = $before.X; y = $before.Y }; cursorAfter = [ordered]@{ x = $after.X; y = $after.Y }
    authority = [ordered]@{ pid = $serverPid }
    transport = 'guest user32 SetCursorPos plus mouse_event'; operations = [ordered]@{ clickAttempts = 1; clicks = 1; inputRetries = 0 }
}
[IO.File]::WriteAllText($ReceiptPath, (($receipt | ConvertTo-Json -Depth 8) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
exit 0
