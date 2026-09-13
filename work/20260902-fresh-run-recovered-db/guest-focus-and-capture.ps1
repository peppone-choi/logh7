[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$RunId,
    [Parameter(Mandatory=$true)][int]$ExpectedPid,
    [Parameter(Mandatory=$true)][string]$ExpectedHwnd,
    [Parameter(Mandatory=$true)][string]$OutputPath,
    [Parameter(Mandatory=$true)][string]$ReceiptPath,
    [int]$SettleMilliseconds = 1500,
    [switch]$NoFocus
)
# Brings the original client window to the foreground (AttachThreadInput + SetForegroundWindow, no click,
# no key) so its Direct3D surface presents, waits, then captures the primary screen and the client rectangle.
# Must run in the interactive console session.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ((Test-Path -LiteralPath $OutputPath) -or (Test-Path -LiteralPath $ReceiptPath)) { throw 'OUTPUT_EXISTS' }
$root = "C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1\$RunId"
$prep = Get-Content -LiteralPath (Join-Path $root 'fresh-run-prep.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($prep.runId -cne $RunId -or [int]$prep.client.pid -ne $ExpectedPid -or [string]$prep.client.hwnd -cne $ExpectedHwnd) { throw 'PREP_IDENTITY_MISMATCH' }
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
if (-not ('FocusCaptureNative' -as [type])) { Add-Type -TypeDefinition @'
using System;using System.Runtime.InteropServices;using System.Text;
public static class FocusCaptureNative{
 [StructLayout(LayoutKind.Sequential)]public struct RECT{public int Left,Top,Right,Bottom;}
 [DllImport("user32.dll")]public static extern bool IsWindow(IntPtr h);[DllImport("user32.dll")]public static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")]public static extern uint GetWindowThreadProcessId(IntPtr h,out uint p);[DllImport("user32.dll")]public static extern bool GetWindowRect(IntPtr h,out RECT r);
 [DllImport("user32.dll")]public static extern bool GetClientRect(IntPtr h,out RECT r);[StructLayout(LayoutKind.Sequential)]public struct POINT{public int X,Y;}
 [DllImport("user32.dll")]public static extern bool ClientToScreen(IntPtr h,ref POINT p);
 [DllImport("user32.dll")]public static extern IntPtr GetForegroundWindow();[DllImport("user32.dll")]public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")]public static extern bool AttachThreadInput(uint a,uint b,bool attach);[DllImport("kernel32.dll")]public static extern uint GetCurrentThreadId();
 [DllImport("user32.dll")]public static extern bool BringWindowToTop(IntPtr h);[DllImport("user32.dll")]public static extern bool ShowWindow(IntPtr h,int cmd);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)]public static extern int GetWindowText(IntPtr h,StringBuilder s,int n);}
'@ }
$hwnd = [IntPtr][Convert]::ToInt64($ExpectedHwnd.Substring(2), 16)
if (-not [FocusCaptureNative]::IsWindow($hwnd)) { throw 'CLIENT_HWND_INVALID' }
$owner = [uint32]0; $targetThread = [FocusCaptureNative]::GetWindowThreadProcessId($hwnd, [ref]$owner)
if ([int]$owner -ne $ExpectedPid) { throw 'CLIENT_HWND_OWNER_MISMATCH' }
$fgBefore = [FocusCaptureNative]::GetForegroundWindow(); $fgBeforePid = [uint32]0; [void][FocusCaptureNative]::GetWindowThreadProcessId($fgBefore, [ref]$fgBeforePid)
$focusResult = $null; $focusAttempted = $false
if (-not $NoFocus) {
    $focusAttempted = $true
    $fgThread = if ($fgBefore -eq [IntPtr]::Zero) { [uint32]0 } else { [FocusCaptureNative]::GetWindowThreadProcessId($fgBefore, [ref]([uint32]0)) }
    $cur = [FocusCaptureNative]::GetCurrentThreadId()
    $aF = if ($fgThread -eq 0 -or $fgThread -eq $cur) { $true } else { [FocusCaptureNative]::AttachThreadInput($cur, $fgThread, $true) }
    $aT = if ($targetThread -eq $cur) { $true } else { [FocusCaptureNative]::AttachThreadInput($cur, $targetThread, $true) }
    try { [void][FocusCaptureNative]::ShowWindow($hwnd, 5); [void][FocusCaptureNative]::BringWindowToTop($hwnd); $focusResult = [FocusCaptureNative]::SetForegroundWindow($hwnd) }
    finally { if ($targetThread -ne $cur -and $aT) { [void][FocusCaptureNative]::AttachThreadInput($cur, $targetThread, $false) }; if ($fgThread -ne 0 -and $fgThread -ne $cur -and $aF) { [void][FocusCaptureNative]::AttachThreadInput($cur, $fgThread, $false) } }
}
Start-Sleep -Milliseconds $SettleMilliseconds
$fgAfter = [FocusCaptureNative]::GetForegroundWindow(); $fgAfterPid = [uint32]0; [void][FocusCaptureNative]::GetWindowThreadProcessId($fgAfter, [ref]$fgAfterPid)
$wr = [FocusCaptureNative+RECT]::new(); [void][FocusCaptureNative]::GetWindowRect($hwnd, [ref]$wr)
$cr = [FocusCaptureNative+RECT]::new(); [void][FocusCaptureNative]::GetClientRect($hwnd, [ref]$cr)
$origin = [FocusCaptureNative+POINT]::new(); [void][FocusCaptureNative]::ClientToScreen($hwnd, [ref]$origin)
$bounds = [Windows.Forms.Screen]::PrimaryScreen.Bounds
$bmp = New-Object Drawing.Bitmap $bounds.Width, $bounds.Height
$g = [Drawing.Graphics]::FromImage($bmp); try { $g.CopyFromScreen($bounds.Location, [Drawing.Point]::Empty, $bounds.Size) } finally { $g.Dispose() }
$bmp.Save($OutputPath, [Drawing.Imaging.ImageFormat]::Png)
# client-area statistics
$cw = $cr.Right - $cr.Left; $ch = $cr.Bottom - $cr.Top; $nonWhite = 0; $nonBlack = 0; $count = 0
for ($y = 0; $y -lt $ch; $y += 6) { for ($x = 0; $x -lt $cw; $x += 6) { $px = $origin.X + $x; $py = $origin.Y + $y; if ($px -ge 0 -and $py -ge 0 -and $px -lt $bmp.Width -and $py -lt $bmp.Height) { $c = $bmp.GetPixel($px, $py); $s = $c.R + $c.G + $c.B; $count++; if ($s -lt 720) { $nonWhite++ }; if ($s -gt 30) { $nonBlack++ } } } }
$bmp.Dispose()
$sb = [Text.StringBuilder]::new(256); [void][FocusCaptureNative]::GetWindowText($fgAfter, $sb, 256)
$receipt = [ordered]@{
    status = 'GUEST_FOCUS_CAPTURE_DONE'; capturedAtUtc = [datetime]::UtcNow.ToString('o'); runId = $RunId; sessionId = [Diagnostics.Process]::GetCurrentProcess().SessionId
    client = [ordered]@{ pid = $ExpectedPid; hwnd = $ExpectedHwnd; alive = ($null -ne (Get-Process -Id $ExpectedPid -ErrorAction SilentlyContinue)); visible = [FocusCaptureNative]::IsWindowVisible($hwnd); windowRect = [ordered]@{ left = $wr.Left; top = $wr.Top; right = $wr.Right; bottom = $wr.Bottom }; clientOrigin = [ordered]@{ x = $origin.X; y = $origin.Y }; clientSize = [ordered]@{ width = $cw; height = $ch }; tcp = @(Get-NetTCPConnection -OwningProcess $ExpectedPid -ErrorAction SilentlyContinue | ForEach-Object { "$($_.LocalAddress):$($_.LocalPort)->$($_.RemoteAddress):$($_.RemotePort) $($_.State)" }) }
    focus = [ordered]@{ attempted = $focusAttempted; setForegroundWindowResult = $focusResult; foregroundBeforePid = [int]$fgBeforePid; foregroundAfterPid = [int]$fgAfterPid; foregroundAfterHwnd = ('0x{0:X16}' -f $fgAfter.ToInt64()); foregroundAfterTitle = $sb.ToString(); clientIsForeground = ($fgAfter -eq $hwnd) }
    clientArea = [ordered]@{ sampled = $count; nonWhite = $nonWhite; nonBlack = $nonBlack }
    outputSha256 = (Get-FileHash -LiteralPath $OutputPath -Algorithm SHA256).Hash
    operations = [ordered]@{ gameInputs = 0; clicks = 0; keyEvents = 0; focusCalls = $(if ($focusAttempted) { 1 } else { 0 }); inputRetries = 0 }
}
[IO.File]::WriteAllText($ReceiptPath, (($receipt | ConvertTo-Json -Depth 8) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
exit 0
